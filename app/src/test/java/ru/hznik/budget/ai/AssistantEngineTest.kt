package ru.hznik.budget.ai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.ai.tools.AssistantTool
import ru.hznik.budget.ai.tools.FakeAiSettings
import ru.hznik.budget.ai.tools.ToolRegistry
import ru.hznik.budget.domain.ai.ChatMessage
import ru.hznik.budget.domain.ai.ChatRole
import ru.hznik.budget.domain.ai.LlmError
import ru.hznik.budget.domain.ai.LlmMessage
import ru.hznik.budget.domain.ai.LlmProvider
import ru.hznik.budget.domain.ai.LlmResult
import ru.hznik.budget.domain.ai.TokenUsage
import ru.hznik.budget.domain.ai.ToolSpec
import ru.hznik.budget.testing.TEST_CLOCK

/** Модель по сценарию: отдаёт ответы по очереди и запоминает, что ей прислали. */
class ScriptedLlmProvider(vararg script: LlmResult, override val isConfigured: Boolean = true) : LlmProvider {
    val answers = ArrayDeque(script.toList())
    val calls = mutableListOf<Pair<List<LlmMessage>, List<ToolSpec>>>()

    override suspend fun complete(messages: List<LlmMessage>, tools: List<ToolSpec>): LlmResult {
        calls += messages.toList() to tools
        return answers.removeFirst()
    }
}

/** Цикл помощника (раздел 8.3 ТЗ, spec ai-assistant; задача 4.3). */
class AssistantEngineTest {
    private class FakeTool : AssistantTool {
        var runs = 0
        override val name = "simulate_purchase"
        override val description = "Расчёт покупки"
        override val parametersSchema = """{"type":"object","properties":{"amount":{"type":"number"}}}"""
        override suspend fun run(args: JsonObject): JsonObject {
            runs++
            return buildJsonObject { put("can_spend_today_after", -2669) }
        }
    }

    private val tool = FakeTool()
    private val settings = FakeAiSettings()
    private fun engine(provider: LlmProvider) = AssistantEngine(provider, ToolRegistry(setOf(tool), settings), settings, TEST_CLOCK)

    private fun call(tokens: Long = 100) = LlmResult.ToolCall("simulate_purchase", """{"amount":3000}""", "state-1", TokenUsage(tokens))

    @Test
    fun `headphones question goes through the tool`() = runTest {
        val provider = ScriptedLlmProvider(call(), LlmResult.Text("Хватит — останется 5 930 ₽", TokenUsage(200)))
        val answer = engine(provider).ask(emptyList(), "Хватит ли до стипендии, если куплю наушники за 3 000 ₽?")
        assertEquals(AssistantAnswer.Text("Хватит - останется 5 930 ₽", listOf("simulate_purchase")), answer)
        val second = provider.calls[1].first
        assertEquals(LlmMessage.AssistantToolCall("simulate_purchase", """{"amount":3000}""", "state-1"), second[second.size - 2])
        assertEquals(LlmMessage.ToolResult("simulate_purchase", """{"can_spend_today_after":-2669}"""), second.last())
        assertEquals(300L, settings.tokensState.value)
        assertEquals(listOf("simulate_purchase"), provider.calls[0].second.map { it.name })
        val system = provider.calls[0].first.first() as LlmMessage.System
        assertTrue("в промпте сегодняшняя дата", system.text.contains("2026-10-13"))
    }

    @Test
    fun `answer without tools has no caption`() = runTest {
        val answer = engine(ScriptedLlmProvider(LlmResult.Text("Здравствуйте!", TokenUsage(20)))).ask(emptyList(), "Привет")
        assertEquals(AssistantAnswer.Text("Здравствуйте!", emptyList()), answer)
        assertEquals(0, tool.runs)
    }

    @Test
    fun `sixth tool call stops the loop`() = runTest {
        val provider = ScriptedLlmProvider(*Array(6) { call(10) })
        assertEquals(AssistantAnswer.TooManyCalls, engine(provider).ask(emptyList(), "Посчитай"))
        assertEquals("шестой вызов не выполняется", 5, tool.runs)
        assertEquals(60L, settings.tokensState.value)
    }

    @Test
    fun `error in the middle of the loop`() = runTest {
        val provider = ScriptedLlmProvider(call(), LlmResult.Failure(LlmError.NO_NETWORK))
        assertEquals(AssistantAnswer.Failure(LlmError.NO_NETWORK), engine(provider).ask(emptyList(), "Посчитай"))
    }

    @Test
    fun `only last ten messages without errors go to the model`() = runTest {
        val t = TEST_CLOCK.instant()
        val history = (1..12).map { i -> ChatMessage(role = if (i % 2 == 1) ChatRole.USER else ChatRole.ASSISTANT, text = "сообщение $i", createdAt = t) } +
            ChatMessage(role = ChatRole.ERROR, text = "NO_NETWORK", createdAt = t)
        val provider = ScriptedLlmProvider(LlmResult.Text("ok", TokenUsage.NONE))
        engine(provider).ask(history, "Новый вопрос")
        val sent = provider.calls.single().first
        assertTrue(sent.first() is LlmMessage.System)
        assertEquals(LlmMessage.User("Новый вопрос"), sent.last())
        val middle = sent.drop(1).dropLast(1)
        assertEquals(10, middle.size)
        assertEquals(LlmMessage.User("сообщение 3"), middle.first())
        assertTrue(middle.none { (it as? LlmMessage.User)?.text == "NO_NETWORK" || (it as? LlmMessage.Assistant)?.text == "NO_NETWORK" })
    }

    @Test
    fun `failed tool gives no caption`() = runTest {
        settings.hideState.value = true
        val merchants = object : AssistantTool {
            override val name = "get_top_merchants"
            override val description = "Магазины"
            override val parametersSchema = """{"type":"object","properties":{}}"""
            override val sendsMerchants = true
            override suspend fun run(args: JsonObject): JsonObject = buildJsonObject { put("merchants", "KOFEMANIYA") }
        }
        val provider = ScriptedLlmProvider(
            LlmResult.ToolCall("get_top_merchants", "{}", null, TokenUsage(10)),
            LlmResult.Text("Названия магазинов скрыты настройкой", TokenUsage(10)),
        )
        val engine = AssistantEngine(provider, ToolRegistry(setOf(tool, merchants), settings), settings, TEST_CLOCK)
        val answer = engine.ask(emptyList(), "Где я трачу больше всего?") as AssistantAnswer.Text
        assertTrue("скрытый инструмент не сработал - подписи нет", answer.tools.isEmpty())
        val result = provider.calls[1].first.last() as LlmMessage.ToolResult
        assertTrue(result.resultJson.contains("error"))
        assertTrue(!result.resultJson.contains("KOFEMANIYA"))
    }

    @Test
    fun `question without answer is not sent twice in a row`() = runTest {
        val t = TEST_CLOCK.instant()
        val history = listOf(
            ChatMessage(role = ChatRole.USER, text = "Вопрос без ответа", createdAt = t),
            ChatMessage(role = ChatRole.ERROR, text = "NO_NETWORK", createdAt = t),
            ChatMessage(role = ChatRole.USER, text = "Второй", createdAt = t),
            ChatMessage(role = ChatRole.ASSISTANT, text = "Ответ", createdAt = t),
        )
        val provider = ScriptedLlmProvider(LlmResult.Text("ok", TokenUsage.NONE))
        engine(provider).ask(history, "Третий")
        val sent = provider.calls.single().first.drop(1)
        assertEquals(listOf(LlmMessage.User("Второй"), LlmMessage.Assistant("Ответ"), LlmMessage.User("Третий")), sent)
    }

    @Test
    fun `hidden merchants drop answers built from merchants`() = runTest {
        val t = TEST_CLOCK.instant()
        val history = listOf(
            ChatMessage(role = ChatRole.USER, text = "Где трачу?", createdAt = t),
            ChatMessage(role = ChatRole.ASSISTANT, text = "Больше всего в KOFEMANIYA", tools = listOf("get_top_merchants"), createdAt = t),
            ChatMessage(role = ChatRole.USER, text = "А по категориям?", createdAt = t),
            ChatMessage(role = ChatRole.ASSISTANT, text = "Продукты 1 000 ₽", tools = listOf("get_spending_by_category"), createdAt = t),
        )
        settings.hideState.value = true
        val provider = ScriptedLlmProvider(LlmResult.Text("ok", TokenUsage.NONE))
        engine(provider).ask(history, "Ещё")
        val sent = provider.calls.single().first.joinToString { it.toString() }
        assertTrue(!sent.contains("KOFEMANIYA"))
        assertTrue(sent.contains("Продукты 1 000 ₽"))
    }
}
