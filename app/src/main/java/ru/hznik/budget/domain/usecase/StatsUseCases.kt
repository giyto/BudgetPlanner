package ru.hznik.budget.domain.usecase

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import ru.hznik.budget.domain.model.Period
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.OperationRepository
import ru.hznik.budget.domain.repository.PeriodRepository
import ru.hznik.budget.domain.repository.PlannedPaymentRepository
import ru.hznik.budget.domain.repository.StatisticsRepository
import ru.hznik.budget.domain.stats.CategoryBreakdown
import ru.hznik.budget.domain.stats.DailySpending
import ru.hznik.budget.domain.stats.DateRange
import ru.hznik.budget.domain.stats.DaySpending
import ru.hznik.budget.domain.stats.MerchantSpending
import ru.hznik.budget.domain.stats.PeriodSummary
import ru.hznik.budget.domain.stats.SpendingChange
import ru.hznik.budget.domain.stats.SpendingComparison
import ru.hznik.budget.domain.stats.previousMonthRange
import ru.hznik.budget.domain.stats.previousPeriodRange
import javax.inject.Inject

enum class StatsMode { PERIOD, MONTH }

/**
 * Что считает экран статистики: [range], диапазон для сравнения [previousRange] (`null` — сравнивать не с чем)
 * и [period], если лимиты дней считаются по периоду (в режиме «Месяц» периода нет).
 */
data class StatsScope(val range: DateRange, val previousRange: DateRange?, val period: Period?)

/** Диапазоны режимов «Период» и «Месяц» и завершённого периода (design.md, решение 1). Пересчитываются при смене дня. */
class ObserveStatsScope @Inject constructor(
    private val periods: PeriodRepository,
    private val ticker: TodayTicker,
) {
    /** `null` — периода ещё нет (онбординг не пройден). */
    fun forMode(mode: StatsMode): Flow<StatsScope?> = when (mode) {
        StatsMode.PERIOD -> combine(periods.observeCurrent(), periods.observeFinished(), ticker.today()) { current, finished, today ->
            current?.let {
                val range = DateRange.of(it, today)
                // Предыдущий — последний завершённый, который закончился не позже начала текущего.
                val previous = finished.firstOrNull { p -> p.endDate?.isAfter(it.startDate) == false }
                StatsScope(range, previous?.let { p -> previousPeriodRange(range, p) }, it)
            }
        }
        StatsMode.MONTH -> combine(periods.observeCurrent(), periods.observeFinished(), ticker.today()) { current, finished, today ->
            val range = DateRange.month(today)
            // Прошлый месяц до первого периода — данных ещё нет, сравнивать не с чем.
            val firstStart = (finished.map { it.startDate } + listOfNotNull(current?.startDate)).minOrNull()
            val previous = previousMonthRange(range).takeIf { firstStart != null && !it.endInclusive.isBefore(firstStart) }
            StatsScope(range, previous, null)
        }
    }

    /** Завершённый период [periodId] целиком и сравнение с периодом перед ним; `null`, если такого периода нет. */
    fun forFinishedPeriod(periodId: Long): Flow<StatsScope?> = combine(periods.observeFinished(), ticker.today()) { finished, today ->
        val index = finished.indexOfFirst { it.id == periodId }
        if (index < 0) return@combine null
        val period = finished[index]
        val range = DateRange.of(period, today)
        // Список отсортирован от новых к старым, поэтому предыдущий период — следующий элемент.
        StatsScope(range, finished.getOrNull(index + 1)?.let { previousPeriodRange(range, it) }, period)
    }
}

class ObserveCategoryBreakdown @Inject constructor(
    private val stats: StatisticsRepository,
    private val categories: CategoryRepository,
) {
    operator fun invoke(range: DateRange): Flow<CategoryBreakdown> =
        combine(stats.spendingByCategory(range), categories.observeAll()) { totals, all -> CategoryBreakdown.of(totals, all) }
}

class ObserveSpendingComparison @Inject constructor(
    private val stats: StatisticsRepository,
    private val categories: CategoryRepository,
) {
    /** `null`, если сравнивать не с чем (первый период). */
    operator fun invoke(current: DateRange, previous: DateRange?): Flow<List<SpendingChange>?> =
        if (previous == null) {
            flowOf(null)
        } else {
            combine(stats.spendingByCategory(current), stats.spendingByCategory(previous), categories.observeAll()) { now, before, all ->
                SpendingComparison.of(now, before, all)
            }
        }
}

class ObserveTopMerchants @Inject constructor(private val stats: StatisticsRepository) {
    operator fun invoke(range: DateRange): Flow<List<MerchantSpending>> = stats.topMerchants(range, LIMIT)

    companion object {
        const val LIMIT = 5
    }
}

/** Траты по дням: в периоде — с лимитом каждого дня через расчёт FR-03, в месяце — запросом без лимита. */
class ObserveDailySpending @Inject constructor(
    private val operations: OperationRepository,
    private val payments: PlannedPaymentRepository,
    private val stats: StatisticsRepository,
) {
    operator fun invoke(scope: StatsScope): Flow<List<DaySpending>> {
        val period = scope.period
        return if (period == null) {
            stats.spendingByDay(scope.range).map { DailySpending.forRange(scope.range, it) }
        } else {
            // У завершённого периода — только его операции, а не всё до сегодняшнего дня (NFR-01).
            val periodOperations = period.endDate?.let { operations.observeBetween(period.startDate, it) } ?: operations.observeFrom(period.startDate)
            combine(periodOperations, payments.observeAll()) { ops, pays ->
                DailySpending.forPeriod(period, ops, pays, scope.range)
            }
        }
    }
}

/** Итоги завершённых периодов, новые сверху; считаются по операциям, поэтому правка старой операции меняет итог сразу. */
@OptIn(ExperimentalCoroutinesApi::class)
class ObservePastPeriods @Inject constructor(
    private val periods: PeriodRepository,
    private val stats: StatisticsRepository,
) {
    operator fun invoke(): Flow<List<PeriodSummary>> = periods.observeFinished().flatMapLatest { finished ->
        if (finished.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(
                finished.map { period ->
                    val range = DateRange.of(period, period.startDate)
                    stats.totals(range).map { PeriodSummary.of(period, range, it) }
                },
            ) { it.toList() }
        }
    }
}
