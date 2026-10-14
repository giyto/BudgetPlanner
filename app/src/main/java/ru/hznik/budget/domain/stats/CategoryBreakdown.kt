package ru.hznik.budget.domain.stats

import ru.hznik.budget.domain.model.Category

/** Доля в диаграмме категорий; [category] = `null` — «Остальное». */
data class CategorySlice(val category: Category?, val kopecks: Long, val percent: Int)

/**
 * Траты диапазона по категориям (spec statistics, design.md, решение 4): [all] — все категории для списка под кольцом,
 * [slices] — доли кольца (четыре крупнейшие и «Остальное», если категорий больше пяти).
 */
data class CategoryBreakdown(val totalKopecks: Long, val slices: List<CategorySlice>, val all: List<CategorySlice> = slices) {
    val isEmpty: Boolean get() = slices.isEmpty()

    /** Категории, сведённые в «Остальное»; пусто, если его нет. */
    val hidden: List<CategorySlice> get() = if (slices.lastOrNull()?.category == null) all.drop(slices.size - 1) else emptyList()

    companion object {
        /** Сколько долей показывается без сведения в «Остальное». */
        const val MAX_SLICES = 5

        /**
         * [totals] — траты по id категории (возвраты уже вычтены). Суммы ≤ 0 отбрасываются, остальные идут по убыванию,
         * при равенстве — в порядке категорий. Если категорий больше [MAX_SLICES], четыре крупнейшие остаются, прочие сводятся
         * в «Остальное». Проценты — целые с суммой ровно 100 (метод наибольшего остатка) и считаются по всем категориям,
         * поэтому доля «Остального» равна сумме долей спрятанных категорий в списке.
         */
        fun of(totals: Map<Long, Long>, categories: List<Category>): CategoryBreakdown {
            val byId = categories.associateBy { it.id }
            val positive = totals.filterValues { it > 0 }.mapNotNull { (id, kopecks) -> byId[id]?.let { it to kopecks } }
                .sortedWith(compareByDescending<Pair<Category, Long>> { it.second }.thenBy { it.first.sortOrder }.thenBy { it.first.id })
            if (positive.isEmpty()) return CategoryBreakdown(0, emptyList())

            val total = positive.sumOf { it.second }
            val percents = largestRemainderPercents(positive.map { it.second }, total)
            val all = positive.mapIndexed { i, (category, kopecks) -> CategorySlice(category, kopecks, percents[i]) }
            val slices = if (all.size > MAX_SLICES) {
                val rest = all.drop(MAX_SLICES - 1)
                all.take(MAX_SLICES - 1) + CategorySlice(null, rest.sumOf { it.kopecks }, rest.sumOf { it.percent })
            } else {
                all
            }
            return CategoryBreakdown(total, slices, all)
        }

        private fun largestRemainderPercents(values: List<Long>, total: Long): List<Int> {
            val exact = values.map { it * 100.0 / total }
            val floors = exact.map { it.toInt() }.toMutableList()
            val missing = 100 - floors.sum()
            exact.indices.sortedByDescending { exact[it] - floors[it] }.take(missing).forEach { floors[it]++ }
            return floors
        }
    }
}
