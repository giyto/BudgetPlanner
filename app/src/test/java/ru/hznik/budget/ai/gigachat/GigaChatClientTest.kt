package ru.hznik.budget.ai.gigachat

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.domain.ai.LlmError
import ru.hznik.budget.domain.ai.LlmMessage
import ru.hznik.budget.domain.ai.LlmResult
import ru.hznik.budget.domain.ai.TokenUsage
import ru.hznik.budget.domain.ai.ToolSpec
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.net.ssl.SSLHandshakeException

/** Клиент GigaChat на имитации сервера (design.md add-ai-assistant, решение 3; задачи 3.1–3.2). */
class GigaChatClientTest {
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("Europe/Moscow")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = MutableClock(Instant.parse("2026-10-17T09:00:00Z"))
    private val requests = mutableListOf<HttpRequestData>()
    private var chatResponses = ArrayDeque<MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>()
    private var oauthStatus = HttpStatusCode.OK
    private var tokenCounter = 0

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(key: String = "KEY123"): GigaChatClient {
        val engine = MockEngine { request ->
            requests += request
            if (request.url.toString() == GigaChatConfig.OAUTH_URL) {
                tokenCounter++
                val expires = clock.millis() + 30 * 60_000
                respond("""{"access_token":"token$tokenCounter","expires_at":$expires}""", oauthStatus, json)
            } else {
                chatResponses.removeFirst()(this, request)
            }
        }
        return GigaChatClient(HttpClient(engine) { expectSuccess = false }, key, clock)
    }

    private fun text(content: String, tokens: Long = 30): MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respond(
            """{"choices":[{"message":{"role":"assistant","content":"$content"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":$tokens}}""",
            HttpStatusCode.OK,
            json,
        )
    }

    private fun status(code: HttpStatusCode): MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond("{}", code, json) }

    private val chatRequests get() = requests.filter { it.url.toString() == GigaChatConfig.CHAT_URL }
    private val oauthRequests get() = requests.filter { it.url.toString() == GigaChatConfig.OAUTH_URL }

    private fun body(request: HttpRequestData): String = when (val content = request.body) {
        is TextContent -> content.text
        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
        else -> error("Неожиданное тело ${content::class}")
    }

    private val question = listOf(LlmMessage.System("Ты помощник"), LlmMessage.User("Привет"))

    @Test
    fun `token request has basic key rquid and scope`() = runTest {
        chatResponses += text("Здравствуйте")
        client().complete(question, emptyList())
        val oauth = oauthRequests.single()
        assertEquals("Basic KEY123", oauth.headers[HttpHeaders.Authorization])
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(oauth.headers["RqUID"]!!))
        assertEquals("scope=GIGACHAT_API_PERS", body(oauth))
        assertEquals("Bearer token1", chatRequests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `token is cached and refreshed a minute before expiry`() = runTest {
        val client = client()
        repeat(3) { chatResponses += text("ok") }
        client.complete(question, emptyList())
        clock.now = clock.now.plusSeconds(28 * 60)
        client.complete(question, emptyList())
        assertEquals("кэш на 28-й минуте", 1, oauthRequests.size)
        clock.now = clock.now.plusSeconds(90)
        client.complete(question, emptyList())
        assertEquals("за минуту до конца — новый токен", 2, oauthRequests.size)
        assertEquals("Bearer token2", chatRequests.last().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `rejected key is bad key and no chat request`() = runTest {
        oauthStatus = HttpStatusCode.Unauthorized
        assertEquals(LlmResult.Failure(LlmError.BAD_KEY), client().complete(question, emptyList()))
        assertTrue(chatRequests.isEmpty())
    }

    @Test
    fun `text answer with usage`() = runTest {
        chatResponses += text("Здравствуйте", tokens = 1200)
        assertEquals(LlmResult.Text("Здравствуйте", TokenUsage(1200)), client().complete(question, emptyList()))
        val sent = Json.parseToJsonElement(body(chatRequests.single())).jsonObject
        assertEquals("GigaChat-2", sent["model"]!!.jsonPrimitive.content)
        assertNull("без инструментов нет functions", sent["functions"])
        assertNull(sent["function_call"])
        assertEquals("system", sent["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tools and function messages are sent in gigachat format`() = runTest {
        chatResponses += text("Хватит")
        val messages = question + listOf(
            LlmMessage.AssistantToolCall("simulate_purchase", """{"amount":3000}""", "state-1"),
            LlmMessage.ToolResult("simulate_purchase", """{"can_spend_today":-2669}"""),
        )
        val tool = ToolSpec("simulate_purchase", "Расчёт покупки", """{"type":"object","properties":{"amount":{"type":"number"}},"required":["amount"]}""")
        client().complete(messages, listOf(tool))
        val sent = Json.parseToJsonElement(body(chatRequests.single())).jsonObject
        assertEquals("auto", sent["function_call"]!!.jsonPrimitive.content)
        val fn = sent["functions"]!!.jsonArray.single().jsonObject
        assertEquals("simulate_purchase", fn["name"]!!.jsonPrimitive.content)
        assertEquals("object", fn["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val call = sent["messages"]!!.jsonArray[2].jsonObject
        assertEquals("assistant", call["role"]!!.jsonPrimitive.content)
        assertEquals("", call["content"]!!.jsonPrimitive.content)
        assertEquals("state-1", call["functions_state_id"]!!.jsonPrimitive.content)
        assertEquals("3000", call["function_call"]!!.jsonObject["arguments"]!!.jsonObject["amount"]!!.jsonPrimitive.content)
        val result = sent["messages"]!!.jsonArray[3].jsonObject
        assertEquals("function", result["role"]!!.jsonPrimitive.content)
        assertEquals("simulate_purchase", result["name"]!!.jsonPrimitive.content)
        assertEquals("""{"can_spend_today":-2669}""", result["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `function call answer becomes tool call`() = runTest {
        chatResponses += {
            respond(
                """{"choices":[{"message":{"role":"assistant","content":"","function_call":{"name":"simulate_purchase","arguments":{"amount":3000}},"functions_state_id":"st-9"},"finish_reason":"function_call"}],"usage":{"total_tokens":173}}""",
                HttpStatusCode.OK,
                json,
            )
        }
        val result = client().complete(question, emptyList()) as LlmResult.ToolCall
        assertEquals("simulate_purchase", result.name)
        assertEquals("st-9", result.stateId)
        assertEquals(173L, result.usage.totalTokens)
        assertEquals("3000", Json.parseToJsonElement(result.argumentsJson).jsonObject["amount"]!!.jsonPrimitive.content)
    }

    @Test
    fun `server errors map to understandable errors`() = runTest {
        val client = client()
        chatResponses += status(HttpStatusCode.PaymentRequired)
        assertEquals(LlmResult.Failure(LlmError.TOKENS_EXHAUSTED), client.complete(question, emptyList()))
        chatResponses += status(HttpStatusCode.TooManyRequests)
        assertEquals(LlmResult.Failure(LlmError.RATE_LIMITED), client.complete(question, emptyList()))
        chatResponses += status(HttpStatusCode.InternalServerError)
        assertEquals(LlmResult.Failure(LlmError.UNAVAILABLE), client.complete(question, emptyList()))
        chatResponses += { respond("not json", HttpStatusCode.OK, json) }
        assertEquals(LlmResult.Failure(LlmError.UNAVAILABLE), client.complete(question, emptyList()))
    }

    @Test
    fun `no network and tls errors`() = runTest {
        val client = client()
        chatResponses += { throw IOException("Unable to resolve host") }
        assertEquals(LlmResult.Failure(LlmError.NO_NETWORK), client.complete(question, emptyList()))
        chatResponses += { throw SSLHandshakeException("bad certificate") }
        assertEquals(LlmResult.Failure(LlmError.UNAVAILABLE), client.complete(question, emptyList()))
    }

    @Test
    fun `expired token is refreshed once after 401`() = runTest {
        chatResponses += status(HttpStatusCode.Unauthorized)
        chatResponses += text("ok")
        assertEquals("ok", (client().complete(question, emptyList()) as LlmResult.Text).content)
        assertEquals(2, oauthRequests.size)
        assertEquals("Bearer token2", chatRequests.last().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `second 401 is bad key`() = runTest {
        chatResponses += status(HttpStatusCode.Unauthorized)
        chatResponses += status(HttpStatusCode.Unauthorized)
        assertEquals(LlmResult.Failure(LlmError.BAD_KEY), client().complete(question, emptyList()))
        assertEquals(2, chatRequests.size)
    }

    @Test
    fun `without key there are no requests`() = runTest {
        val client = client(key = "")
        assertEquals(false, client.isConfigured)
        assertEquals(LlmResult.Failure(LlmError.NOT_CONFIGURED), client.complete(question, emptyList()))
        assertTrue(requests.isEmpty())
    }
}
