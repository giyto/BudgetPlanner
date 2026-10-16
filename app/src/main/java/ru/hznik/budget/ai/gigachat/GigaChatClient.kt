package ru.hznik.budget.ai.gigachat

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import ru.hznik.budget.domain.ai.LlmError
import ru.hznik.budget.domain.ai.LlmMessage
import ru.hznik.budget.domain.ai.LlmProvider
import ru.hznik.budget.domain.ai.LlmResult
import ru.hznik.budget.domain.ai.TokenUsage
import ru.hznik.budget.domain.ai.ToolSpec
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Clock
import javax.net.ssl.SSLException

/**
 * GigaChat за [LlmProvider] (design.md add-ai-assistant, решение 3): `chat/completions` с функциями, один запрос
 * за раз (бесплатный тариф даёт один поток), повтор один раз после 401 с новым токеном. Тексты, ключ и токен
 * не логируются.
 */
class GigaChatClient internal constructor(
    private val client: HttpClient,
    private val authKey: String,
    clock: Clock,
) : LlmProvider {
    private val auth = GigaChatAuth(client, authKey, clock)
    private val mutex = Mutex()

    override val isConfigured: Boolean get() = authKey.isNotBlank()

    override suspend fun complete(messages: List<LlmMessage>, tools: List<ToolSpec>): LlmResult {
        if (!isConfigured) return LlmResult.Failure(LlmError.NOT_CONFIGURED)
        return mutex.withLock {
            try {
                val body = GigaChatJson.encodeToString(ChatRequest.serializer(), request(messages, tools))
                var response = send(body)
                if (response.status == HttpStatusCode.Unauthorized) {
                    auth.invalidate()
                    response = send(body)
                }
                parse(response)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GigaChatException) {
                LlmResult.Failure(e.error)
            } catch (e: Exception) {
                LlmResult.Failure(networkError(e))
            }
        }
    }

    private suspend fun send(body: String): HttpResponse {
        val token = auth.token()
        return client.post(GigaChatConfig.CHAT_URL) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/json")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private suspend fun parse(response: HttpResponse): LlmResult {
        when (response.status) {
            HttpStatusCode.OK -> Unit
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> return LlmResult.Failure(LlmError.BAD_KEY)
            HttpStatusCode.PaymentRequired -> return LlmResult.Failure(LlmError.TOKENS_EXHAUSTED)
            HttpStatusCode.TooManyRequests -> return LlmResult.Failure(LlmError.RATE_LIMITED)
            else -> return LlmResult.Failure(LlmError.UNAVAILABLE)
        }
        val parsed = runCatching { GigaChatJson.decodeFromString(ChatResponse.serializer(), response.bodyAsText()) }
            .getOrElse { return LlmResult.Failure(LlmError.UNAVAILABLE) }
        val choice = parsed.choices.firstOrNull() ?: return LlmResult.Failure(LlmError.UNAVAILABLE)
        val usage = TokenUsage(parsed.usage?.totalTokens ?: 0)
        val call = choice.message.functionCall
        return if (call != null && choice.finishReason == "function_call") {
            LlmResult.ToolCall(call.name, argumentsJson(call.arguments), choice.message.functionsStateId, usage)
        } else {
            LlmResult.Text(choice.message.content.trim(), usage)
        }
    }

    private fun request(messages: List<LlmMessage>, tools: List<ToolSpec>) = ChatRequest(
        model = GigaChatConfig.MODEL,
        messages = messages.map(::toDto),
        functions = tools.takeIf { it.isNotEmpty() }?.map {
            FunctionDto(it.name, it.description, GigaChatJson.parseToJsonElement(it.parametersJsonSchema))
        },
        functionCall = if (tools.isNotEmpty()) "auto" else null,
    )

    private fun toDto(message: LlmMessage): MessageDto = when (message) {
        is LlmMessage.System -> MessageDto(role = "system", content = message.text)
        is LlmMessage.User -> MessageDto(role = "user", content = message.text)
        is LlmMessage.Assistant -> MessageDto(role = "assistant", content = message.text)
        is LlmMessage.AssistantToolCall -> MessageDto(
            role = "assistant",
            content = "",
            functionCall = FunctionCallDto(message.name, GigaChatJson.parseToJsonElement(message.argumentsJson)),
            functionsStateId = message.stateId,
        )
        is LlmMessage.ToolResult -> MessageDto(role = "function", content = message.resultJson, name = message.name)
    }

    /** Аргументы приходят объектом; если строкой — это JSON внутри строки. */
    private fun argumentsJson(arguments: JsonElement?): String = when (arguments) {
        null -> "{}"
        is JsonPrimitive -> arguments.contentOrNull?.takeIf { it.isNotBlank() } ?: "{}"
        is JsonObject -> arguments.toString()
        else -> "{}"
    }

    private fun networkError(e: Exception): LlmError = when (e) {
        is SSLException -> LlmError.UNAVAILABLE
        is HttpRequestTimeoutException, is SocketTimeoutException -> LlmError.UNAVAILABLE
        is IOException -> LlmError.NO_NETWORK
        else -> LlmError.UNAVAILABLE
    }
}
