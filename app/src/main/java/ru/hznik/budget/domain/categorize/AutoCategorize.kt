package ru.hznik.budget.domain.categorize

import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.repository.CategoryRuleRepository
import javax.inject.Inject

/**
 * Категория и тип новой операции из уведомления; [isDraft] — категорию ещё должен подтвердить пользователь.
 * [originalType] — исходный тип перевода или зачисления, сохранённого по правилу: по нему вторая сторона из другого
 * банка ещё может склеить его в перевод себе (design.md, решение 3).
 */
data class Categorized(val type: OperationType, val categoryId: Long, val isDraft: Boolean, val originalType: OperationType? = null)

/**
 * Категория новой операции из уведомления (spec auto-categorization, design.md, решения 3–4): правило пользователя,
 * иначе подсказка словаря для покупки, иначе предварительная категория по типу. Переводы себе сюда не попадают —
 * их импорт решает раньше.
 */
class AutoCategorize @Inject constructor(private val rules: CategoryRuleRepository) {
    suspend operator fun invoke(type: OperationType, merchant: String?, provisional: Category, categories: List<Category>): Categorized {
        val key = MerchantKey.of(merchant)
        val rule = key?.let { rules.find(it, type.categoryKind) }
        val ruleCategory = rule?.let { r -> categories.firstOrNull { it.id == r.categoryId && !it.hidden && it.kind == type.categoryKind } }
        if (ruleCategory != null) {
            val pairable = type == OperationType.TRANSFER || type == OperationType.INCOME
            return Categorized(typeFor(type, ruleCategory), ruleCategory.id, isDraft = false, originalType = type.takeIf { pairable })
        }
        if (type == OperationType.EXPENSE) {
            dictionaryCategory(merchant, categories)?.let { return Categorized(type, it.id, isDraft = true) }
        }
        return Categorized(type, provisional.id, isDraft = true)
    }

    companion object {
        /** Категория из словаря для покупки: видимая категория по умолчанию или `null`. */
        fun dictionaryCategory(merchant: String?, categories: List<Category>): Category? {
            val key = MerchantDictionary.categoryKeyFor(merchant) ?: return null
            return categories.firstOrNull { it.defaultKey == key && !it.hidden }
        }

        /** Тип по категории правила: «Переводы» делают трату переводом и наоборот; возврат и доход не меняются. */
        fun typeFor(type: OperationType, category: Category): OperationType = when (type) {
            OperationType.EXPENSE, OperationType.TRANSFER -> if (category.isTransfers) OperationType.TRANSFER else OperationType.EXPENSE
            OperationType.INCOME, OperationType.REFUND, OperationType.OWN_TRANSFER -> type
        }
    }
}
