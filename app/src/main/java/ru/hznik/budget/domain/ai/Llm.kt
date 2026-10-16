package ru.hznik.budget.domain.ai

/**
 * Модель ИИ за интерфейсом (FR-08, design.md add-ai-assistant, решение 1): один шаг диалога, без цикла вызовов.
 * Цикл и ограничение в 5 вызовов — правило приложения, а не провайдера; GigaChat можно заменить без изменения
 * остального кода. Аргументы и результаты функций — JSON-строки, чтобы домен не зависел от сериализации.
 */
interface LlmProvider {
    /** В сборке есть ключ: без него помощник недоступен и запросов нет (spec ai-assistant). */
    val isConfigured: Boolean

    suspend fun complete(messages: List<LlmMessage>, tools: List<ToolSpec>): LlmResult
}

/** Сообщение диалога в том виде, в каком его увидит модель. */
sealed interface LlmMessage {
    data class System(val text: String) : LlmMessage

    data class User(val text: String) : LlmMessage

    data class Assistant(val text: String) : LlmMessage

    /** Модель попросила вызвать функцию; [stateId] возвращается модели вместе с результатом. */
    data class AssistantToolCall(val name: String, val argumentsJson: String, val stateId: String?) : LlmMessage

    /** Результат локального инструмента — JSON-объект строкой. */
    data class ToolResult(val name: String, val resultJson: String) : LlmMessage
}

/** Описание инструмента для модели: имя, описание и JSON-схема параметров. */
data class ToolSpec(val name: String, val description: String, val parametersJsonSchema: String)

/** Сколько токенов ушло на один запрос (поле `usage` ответа). */
data class TokenUsage(val totalTokens: Long) {
    companion object {
        val NONE = TokenUsage(0)
    }
}

sealed interface LlmResult {
    data class Text(val content: String, val usage: TokenUsage) : LlmResult

    data class ToolCall(val name: String, val argumentsJson: String, val stateId: String?, val usage: TokenUsage) : LlmResult

    data class Failure(val error: LlmError) : LlmResult
}

/** Ошибки, которые чат показывает понятным текстом (spec ai-assistant «Ошибки»). */
enum class LlmError {
    /** Нет сети или соединение оборвалось. */
    NO_NETWORK,

    /** Сервер отклонил ключ (401 при получении токена). */
    BAD_KEY,

    /** Закончились токены (402). */
    TOKENS_EXHAUSTED,

    /** Слишком много запросов (429). */
    RATE_LIMITED,

    /** Сервис не отвечает, ошибка 5xx, непонятный ответ или ошибка TLS. */
    UNAVAILABLE,

    /** Ключа в сборке нет. */
    NOT_CONFIGURED,
}
