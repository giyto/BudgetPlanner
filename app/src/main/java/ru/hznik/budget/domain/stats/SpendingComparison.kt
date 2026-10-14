package ru.hznik.budget.domain.stats

import ru.hznik.budget.domain.model.Category
import kotlin.math.abs
import kotlin.math.roundToInt

/** Изменение трат категории; [percent] = `null` — в прошлом трат не было («новая»). */
data class SpendingChange(val category: Category, val currentKopecks: Long, val previousKopecks: Long, val percent: Int?) {
    val diffKopecks: Long get() = currentKopecks - previousKopecks

    val isMore: Boolean get() = diffKopecks > 0
}

/** Сравнение трат по категориям двух диапазонов (spec statistics, design.md, решение 5). */
object SpendingComparison {
    const val DEFAULT_LIMIT = 3

    /** До [limit] категорий с наибольшим изменением суммы, без неизменных; суммы ≤ 0 считаются нулём. */
    fun of(current: Map<Long, Long>, previous: Map<Long, Long>, categories: List<Category>, limit: Int = DEFAULT_LIMIT): List<SpendingChange> {
        val byId = categories.associateBy { it.id }
        return (current.keys + previous.keys).mapNotNull { id ->
            val category = byId[id] ?: return@mapNotNull null
            val now = (current[id] ?: 0).coerceAtLeast(0)
            val before = (previous[id] ?: 0).coerceAtLeast(0)
            if (now == before) return@mapNotNull null
            val percent = if (before == 0L) null else ((now - before) * 100.0 / before).roundToInt()
            SpendingChange(category, now, before, percent)
        }.sortedWith(compareByDescending<SpendingChange> { abs(it.diffKopecks) }.thenBy { it.category.sortOrder }).take(limit)
    }
}
