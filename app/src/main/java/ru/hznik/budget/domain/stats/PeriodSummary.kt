package ru.hznik.budget.domain.stats

import ru.hznik.budget.domain.model.Period

/** Доходы и траты диапазона одной строкой (траты — с вычетом возвратов). */
data class RangeTotals(val incomeKopecks: Long, val spentKopecks: Long)

/** Магазин в топе: число операций и сумма трат. */
data class MerchantSpending(val merchant: String, val count: Int, val kopecks: Long)

/** Итог завершённого периода: бюджет = стартовая сумма + доходы, остаток = бюджет − потрачено (spec statistics). */
data class PeriodSummary(val period: Period, val range: DateRange, val budgetKopecks: Long, val spentKopecks: Long) {
    val leftKopecks: Long get() = budgetKopecks - spentKopecks

    val isOverspent: Boolean get() = leftKopecks < 0

    companion object {
        fun of(period: Period, range: DateRange, totals: RangeTotals) =
            PeriodSummary(period, range, period.startAmountKopecks + totals.incomeKopecks, totals.spentKopecks)
    }
}
