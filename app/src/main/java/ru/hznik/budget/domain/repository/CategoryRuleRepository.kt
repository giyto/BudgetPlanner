package ru.hznik.budget.domain.repository

import kotlinx.coroutines.flow.Flow
import ru.hznik.budget.domain.categorize.CategoryRule
import ru.hznik.budget.domain.categorize.CategoryRuleItem
import ru.hznik.budget.domain.model.CategoryKind

/** Правила категорий (design.md add-auto-categorization, решения 2 и 6). */
interface CategoryRuleRepository {
    suspend fun find(merchantKey: String, kind: CategoryKind): CategoryRule?

    /** Правила с категориями: сначала траты, внутри — по названию магазина. */
    fun observeAll(): Flow<List<CategoryRuleItem>>

    suspend fun updateCategory(ruleId: Long, categoryId: Long)

    suspend fun delete(ruleId: Long)

    /** Возвращает удалённое правило с тем же id (отмена удаления). */
    suspend fun restore(rule: CategoryRule)

    /**
     * Подтверждение черновиков и запись правил одной транзакцией: правило того же магазина и вида заменяется.
     * При сбое не остаётся ни подтверждений без правил, ни правил без подтверждений.
     */
    suspend fun confirmAndRemember(confirmations: List<DraftConfirmation>, rules: List<CategoryRule>)
}
