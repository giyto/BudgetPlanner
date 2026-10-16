package ru.hznik.budget.domain.usecase

import kotlinx.coroutines.flow.Flow
import ru.hznik.budget.domain.categorize.AutoCategorize
import ru.hznik.budget.domain.categorize.CategoryRule
import ru.hznik.budget.domain.categorize.CategoryRuleItem
import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.CategoryRuleRepository
import javax.inject.Inject

/** Правила для экрана «Правила для магазинов» (spec auto-categorization, «Правила в настройках»). */
class ObserveCategoryRules @Inject constructor(private val rules: CategoryRuleRepository) {
    operator fun invoke(): Flow<List<CategoryRuleItem>> = rules.observeAll()
}

/** Смена категории правила: только на видимую категорию того же вида; прошлые операции не меняются. */
class ChangeRuleCategory @Inject constructor(
    private val rules: CategoryRuleRepository,
    private val categories: CategoryRepository,
) {
    suspend operator fun invoke(rule: CategoryRule, categoryId: Long): Boolean {
        val category = categories.get(categoryId) ?: return false
        if (category.kind != rule.kind || category.hidden) return false
        rules.updateCategory(rule.id, categoryId)
        return true
    }
}

/** Удаление правила; возвращённое правило передаётся в [RestoreRule] для отмены. */
class DeleteRule @Inject constructor(private val rules: CategoryRuleRepository) {
    suspend operator fun invoke(rule: CategoryRule): CategoryRule {
        rules.delete(rule.id)
        return rule
    }
}

class RestoreRule @Inject constructor(private val rules: CategoryRuleRepository) {
    suspend operator fun invoke(rule: CategoryRule) = rules.restore(rule)
}

/**
 * Подпись «Знакомый магазин» у черновика (design.md add-auto-categorization, решение 5): покупка, чья предварительная
 * категория совпадает с категорией словаря для этого магазина. Вычисляется при показе, в базе не хранится.
 */
object DraftHint {
    fun isKnownStore(operation: Operation, categories: List<Category>): Boolean =
        operation.isDraft && operation.type == OperationType.EXPENSE &&
            AutoCategorize.dictionaryCategory(operation.merchant, categories)?.id == operation.categoryId
}
