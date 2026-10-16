package ru.hznik.budget.domain.categorize

import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.CategoryKind

/** Правило «магазин → категория» одного вида: трата или доход (spec auto-categorization, «Правило категории»). */
data class CategoryRule(
    val id: Long = 0,
    /** Нормализованное название, см. [MerchantKey]. */
    val merchantKey: String,
    val kind: CategoryKind,
    val categoryId: Long,
    /** Как показывать магазин в списке правил. */
    val merchantDisplay: String,
)

/** Правило вместе с категорией — для экрана правил. */
data class CategoryRuleItem(val rule: CategoryRule, val category: Category)
