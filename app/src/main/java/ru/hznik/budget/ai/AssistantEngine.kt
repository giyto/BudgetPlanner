package ru.hznik.budget.ai

import kotlinx.coroutines.flow.first
import ru.hznik.budget.ai.tools.ToolRegistry
import ru.hznik.budget.domain.ai.AiSettings
import ru.hznik.budget.domain.ai.ChatMessage
import ru.hznik.budget.domain.ai.ChatRole
import ru.hznik.budget.domain.ai.LlmError
import ru.hznik.budget.domain.ai.LlmMessage
import ru.hznik.budget.domain.ai.LlmProvider
import ru.hznik.budget.domain.ai.LlmResult
import ru.hznik.budget.domain.time.today
import java.time.Clock
import javax.inject.Inject

/** Чем закончился вопрос. [tools] — имена выполненных инструментов для подписи под ответом. */
sealed interface AssistantAnswer {
    data class Text(val text: String, val tools: List<String>) : AssistantAnswer

    data class Failure(val error: LlmError) : AssistantAnswer

    /** Модель запросила шестой вызов инструмента: цикл остановлен (spec ai-assistant). */
    data object TooManyCalls : AssistantAnswer
}

/**
 * Цикл «вопрос → вызов функции → результат → ответ» (раздел 8.3 ТЗ, design.md add-ai-assistant, решение 2).
 * В модель уходят системный промпт, последние сообщения диалога и результаты инструментов — без базы операций.
 */
class AssistantEngine @Inject constructor(
    private val provider: LlmProvider,
    private val tools: ToolRegistry,
    private val settings: AiSettings,
    private val clock: Clock,
) {
    suspend fun ask(history: List<ChatMessage>, question: String): AssistantAnswer {
        val messages = mutableListOf<LlmMessage>(LlmMessage.System(systemPrompt()))
        messages += context(history, hideMerchants = settings.hideMerchants.first())
        messages += LlmMessage.User(question)
        val specs = tools.specs()
        val used = mutableListOf<String>()
        var calls = 0
        while (true) {
            when (val result = provider.complete(messages, specs)) {
                is LlmResult.Failure -> return AssistantAnswer.Failure(result.error)
                is LlmResult.Text -> {
                    settings.addTokens(result.usage.totalTokens)
                    return AssistantAnswer.Text(clean(result.content), used.distinct())
                }
                is LlmResult.ToolCall -> {
                    settings.addTokens(result.usage.totalTokens)
                    if (calls >= MAX_TOOL_CALLS) return AssistantAnswer.TooManyCalls
                    val output = tools.call(result.name, result.argumentsJson)
                    messages += LlmMessage.AssistantToolCall(result.name, result.argumentsJson, result.stateId)
                    messages += LlmMessage.ToolResult(result.name, output.json)
                    calls++
                    // Подпись под ответом - только за то, что действительно посчитано.
                    if (output.ok) used += result.name
                }
            }
        }
    }

    /**
     * Последние пары «вопрос - ответ». Вопрос без ответа (ошибка, повтор) пропускается: иначе два вопроса подряд.
     * При «Не отправлять названия магазинов» ответы, построенные по магазинам, тоже не отправляются (spec ai-assistant).
     */
    private fun context(history: List<ChatMessage>, hideMerchants: Boolean): List<LlmMessage> {
        val pairs = mutableListOf<LlmMessage>()
        history.forEachIndexed { i, message ->
            if (message.role != ChatRole.USER) return@forEachIndexed
            val answer = history.getOrNull(i + 1)?.takeIf { it.role == ChatRole.ASSISTANT } ?: return@forEachIndexed
            if (hideMerchants && MERCHANTS_TOOL in answer.tools) return@forEachIndexed
            pairs += LlmMessage.User(message.text)
            pairs += LlmMessage.Assistant(answer.text)
        }
        return pairs.takeLast(HISTORY_SIZE)
    }

    private fun systemPrompt(): String =
        """
        Ты помощник по личному бюджету в приложении BudgetPlanner. Сегодня ${clock.today()}.
        Отвечай по-русски, коротко и по делу, обычным языком, без markdown-таблиц.
        Все цифры о бюджете бери только из результатов инструментов. Не придумывай суммы и даты; если данных нет, так и скажи.
        Суммы пиши в рублях, разряды разделяй пробелом: 1 500 ₽.
        Отрицательное «можно потратить сегодня» означает перерасход на эту сумму. Не путай сегодняшний остаток с дневным лимитом на завтра.
        Изменять операции ты не можешь, давать инвестиционные советы нельзя.
        Вместо длинного тире ставь короткий дефис.
        """.trimIndent()

    /** Длинные тире в приложении заменены короткими; ответы модели — так же. */
    private fun clean(text: String): String = text.replace('—', '-').trim()

    companion object {
        const val MAX_TOOL_CALLS = 5
        const val HISTORY_SIZE = 10
        private const val MERCHANTS_TOOL = "get_top_merchants"
    }
}
