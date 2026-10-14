package ru.hznik.budget.domain.stats

import ru.hznik.budget.domain.budget.BudgetCalculator
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.Period
import ru.hznik.budget.domain.model.PlannedPayment
import java.time.LocalDate

/** Траты дня для диаграммы; [limitKopecks] — дневной лимит в начале того дня, `null` в режиме «Месяц». */
data class DaySpending(val date: LocalDate, val spentKopecks: Long, val limitKopecks: Long?) {
    val isOverLimit: Boolean get() = limitKopecks != null && spentKopecks > limitKopecks
}

/** Траты по дням (spec statistics, design.md, решение 3). */
object DailySpending {
    /**
     * Дни [range] периода с лимитом каждого дня по FR-03. Траты дня — как в расчёте периода: без оплат платежей в пределах
     * резерва, с вычетом возвратов, но не меньше нуля.
     */
    fun forPeriod(period: Period, operations: List<Operation>, payments: List<PlannedPayment>, range: DateRange): List<DaySpending> =
        range.days.map { day ->
            val status = BudgetCalculator.calculate(period, operations, payments, day)
            DaySpending(day, status.spentToday.coerceAtLeast(0), status.dailyLimit)
        }

    /** Все дни [range] без лимита; дни без трат — с нулём. */
    fun forRange(range: DateRange, byDay: Map<LocalDate, Long>): List<DaySpending> =
        range.days.map { day -> DaySpending(day, (byDay[day] ?: 0).coerceAtLeast(0), null) }
}
