package ru.hznik.budget.ai

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import ru.hznik.budget.ai.tools.normalizeName
import ru.hznik.budget.domain.ai.AiSettings
import ru.hznik.budget.domain.ai.LlmError
import ru.hznik.budget.domain.ai.LlmMessage
import ru.hznik.budget.domain.ai.LlmProvider
import ru.hznik.budget.domain.ai.LlmResult
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.repository.CategoryRepository
import javax.inject.Inject

/** Черновик, для которого нужна подсказка: только название и вид операции, без суммы и даты. */
data class DraftForHint(val operationId: Long, val merchant: String, val kind: CategoryKind)

sealed interface CategorySuggestions {
    /** id операции → id предложенной категории; неизвестные категории уже отброшены. */
    data class Found(val byOperation: Map<Long, Long>) : CategorySuggestions

    data class Failure(val error: LlmError) : CategorySuggestions

    /** Помощник выключен или названия магазинов скрыты: запроса не было. */
    data object Unavailable : CategorySuggestions
}

/**
 * Подсказка категорий для черновиков (FR-06, spec drafts, design.md add-ai-assistant, решение 8): один запрос
 * без инструментов. В модель уходят только названия, вид операции и имена категорий. Подсказку сохраняет
 * пользователь, сама она ничего не меняет.
 */
class SuggestCategories @Inject constructor(
    private val provider: LlmProvider,
    private val categories: CategoryRepository,
    private val settings: AiSettings,
) {
    suspend operator fun invoke(drafts: List<DraftForHint>): CategorySuggestions {
        if (!provider.isConfigured || !settings.enabled.first() || settings.hideMerchants.first()) return CategorySuggestions.Unavailable
        val merchants = drafts.map { it.merchant.trim() to it.kind }.filter { it.first.isNotEmpty() }.distinct().take(MAX_MERCHANTS)
        if (merchants.isEmpty()) return CategorySuggestions.Found(emptyMap())
        val all = categories.observeAll().first().filter { !it.hidden }
        val expense = all.filter { it.kind == CategoryKind.EXPENSE }
        val income = all.filter { it.kind == CategoryKind.INCOME }
        val request = buildString {
            appendLine("Категории трат: " + expense.joinToString(", ") { it.name })
            appendLine("Категории доходов: " + income.joinToString(", ") { it.name })
            appendLine("Операции:")
            merchants.forEach { (name, kind) -> appendLine("- $name (${if (kind == CategoryKind.INCOME) "доход" else "трата"})") }
        }
        val messages = listOf(LlmMessage.System(SYSTEM_PROMPT), LlmMessage.User(request))
        return when (val result = provider.complete(messages, emptyList())) {
            is LlmResult.Failure -> CategorySuggestions.Failure(result.error)
            is LlmResult.ToolCall -> {
                settings.addTokens(result.usage.totalTokens)
                CategorySuggestions.Found(emptyMap())
            }
            is LlmResult.Text -> {
                settings.addTokens(result.usage.totalTokens)
                val answer = parse(result.content)
                val byOperation = drafts.mapNotNull { draft ->
                    val suggested = answer[normalizeName(draft.merchant)] ?: return@mapNotNull null
                    val pool = if (draft.kind == CategoryKind.INCOME) income else expense
                    pool.firstOrNull { normalizeName(it.name) == normalizeName(suggested) }?.let { draft.operationId to it.id }
                }.toMap()
                CategorySuggestions.Found(byOperation)
            }
        }
    }

    /** JSON-объект из ответа; модель может обернуть его в текст или ```json. Ключи — нормализованные названия. */
    private fun parse(text: String): Map<String, String> {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyMap()
        val obj = runCatching { Json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrElse { return emptyMap() }
        return obj.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { normalizeName(key) to it } }.toMap()
    }

    private companion object {
        const val MAX_MERCHANTS = 20
        val SYSTEM_PROMPT =
            """
            Ты подбираешь категории для операций в приложении личного бюджета.
            Для каждой операции выбери одну категорию из списка нужного вида: для трат — из категорий трат, для доходов — из категорий доходов.
            Если не уверен, не включай операцию в ответ.
            Ответь только JSON-объектом без пояснений: {"название операции как в списке": "категория из списка"}.
            """.trimIndent()
    }
}
