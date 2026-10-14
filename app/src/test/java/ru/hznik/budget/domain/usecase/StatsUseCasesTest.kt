package ru.hznik.budget.domain.usecase

import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.stats.DateRange
import ru.hznik.budget.testing.CAFE
import ru.hznik.budget.testing.FakeCategoryRepository
import ru.hznik.budget.testing.FakeOperationRepository
import ru.hznik.budget.testing.FakePeriodRepository
import ru.hznik.budget.testing.FakePlannedPaymentRepository
import ru.hznik.budget.testing.FakeStatisticsRepository
import ru.hznik.budget.testing.PRODUCTS
import ru.hznik.budget.testing.SALARY
import ru.hznik.budget.testing.TEST_CLOCK
import ru.hznik.budget.testing.TODAY
import ru.hznik.budget.testing.TRANSPORT
import ru.hznik.budget.testing.at
import ru.hznik.budget.testing.category
import java.time.LocalDate

class StatsUseCasesTest {
    private val categories = FakeCategoryRepository()
    private val operations = FakeOperationRepository(categories)
    private val payments = FakePlannedPaymentRepository(operations)
    private val periods = FakePeriodRepository(payments)
    private val stats = FakeStatisticsRepository(operations)
    private val scope = ObserveStatsScope(periods, TodayTicker(TEST_CLOCK))

    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)
    private fun rub(r: Long) = r * 100

    private suspend fun add(type: OperationType, categoryId: Long, rubles: Long, date: LocalDate = TODAY, merchant: String? = null) =
        operations.insert(
            Operation(type = type, amount = Money(rub(rubles)), categoryId = categoryId, merchant = merchant,
                source = OperationSource.MANUAL, occurredAt = at(date)),
        )

    /** Прошлый период с 25 августа по 24 сентября и текущий с 25 сентября. */
    private suspend fun twoPeriods() {
        periods.startNew(d(8, 25), d(9, 25), rub(18_000))
        periods.startNew(d(9, 25), d(10, 25), rub(15_000))
    }

    @Test
    fun `period scope compares with the same first days of the previous period`() = runTest {
        twoPeriods()
        val s = scope.forMode(StatsMode.PERIOD).first()!!
        assertEquals(DateRange(d(9, 25), TODAY), s.range)
        assertEquals(DateRange(d(8, 25), d(9, 12)), s.previousRange)
    }

    @Test
    fun `first period has nothing to compare with`() = runTest {
        periods.startNew(d(10, 1), d(10, 25), rub(18_000))
        assertNull(scope.forMode(StatsMode.PERIOD).first()!!.previousRange)
        assertNull(ObserveSpendingComparison(stats, categories)(DateRange(d(10, 1), TODAY), null).first())
    }

    @Test
    fun `month scope runs from the first day and has no period`() = runTest {
        periods.startNew(d(10, 1), d(10, 25), rub(18_000))
        // Прошлый месяц раньше первого периода — сравнивать не с чем.
        assertNull(scope.forMode(StatsMode.MONTH).first()!!.previousRange)

        twoPeriods()
        val s = scope.forMode(StatsMode.MONTH).first()!!
        assertEquals(DateRange(d(10, 1), TODAY), s.range)
        assertEquals(DateRange(d(9, 1), d(9, 13)), s.previousRange)
        assertNull(s.period)
    }

    @Test
    fun `finished period scope compares with the one before it`() = runTest {
        periods.startNew(d(7, 25), d(8, 25), rub(10_000))
        twoPeriods()
        val august = periods.state.value.first { it.startDate == d(8, 25) }
        val s = scope.forFinishedPeriod(august.id).first()!!
        assertEquals(DateRange(d(8, 25), d(9, 24)), s.range)
        assertEquals(DateRange(d(7, 25), d(8, 24)), s.previousRange)
    }

    @Test
    fun `refund lowers its category and income is not counted`() = runTest {
        val clothes = categories.insert(category(0, "Одежда"))
        add(OperationType.EXPENSE, clothes, 3_000)
        add(OperationType.REFUND, clothes, 1_000)
        add(OperationType.EXPENSE, PRODUCTS.id, 2_000)
        add(OperationType.INCOME, SALARY.id, 10_000)
        val breakdown = ObserveCategoryBreakdown(stats, categories)(DateRange(d(10, 1), TODAY)).first()
        assertEquals(rub(4_000), breakdown.totalKopecks)
        assertEquals(listOf(50, 50), breakdown.slices.map { it.percent })
    }

    @Test
    fun `breakdown updates after a new operation`() = runTest {
        ObserveCategoryBreakdown(stats, categories)(DateRange(d(10, 1), TODAY)).test {
            assertEquals(0L, awaitItem().totalKopecks)
            add(OperationType.EXPENSE, CAFE.id, 500)
            assertEquals(rub(500), awaitItem().totalKopecks)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `top merchants skip operations without a merchant`() = runTest {
        repeat(3) { add(OperationType.EXPENSE, PRODUCTS.id, 700, merchant = "Пятёрочка") }
        add(OperationType.EXPENSE, CAFE.id, 340, merchant = "Кофейня")
        add(OperationType.EXPENSE, TRANSPORT.id, 5_000)
        val top = ObserveTopMerchants(stats)(DateRange(d(10, 1), TODAY)).first()
        assertEquals(listOf("Пятёрочка" to 3, "Кофейня" to 1), top.map { it.merchant to it.count })
        assertEquals(rub(2_100), top.first().kopecks)
    }

    @Test
    fun `daily spending of a period has limits, of a month has none`() = runTest {
        periods.startNew(d(10, 1), d(10, 25), rub(18_000))
        add(OperationType.EXPENSE, PRODUCTS.id, 7_420, d(10, 12))
        add(OperationType.EXPENSE, PRODUCTS.id, 450)
        val daily = ObserveDailySpending(operations, payments, stats)

        val periodDays = daily(scope.forMode(StatsMode.PERIOD).first()!!).first()
        assertEquals(13, periodDays.size)
        assertEquals(listOf(true, false), periodDays.takeLast(2).map { it.isOverLimit })

        val monthDays = daily(scope.forMode(StatsMode.MONTH).first()!!).first()
        assertEquals(13, monthDays.size)
        assertEquals(rub(7_420), monthDays[11].spentKopecks)
        assertNull(monthDays.last().limitKopecks)
    }

    @Test
    fun `past period totals change after deleting an old operation`() = runTest {
        twoPeriods()
        add(OperationType.INCOME, SALARY.id, 2_000, d(9, 1))
        add(OperationType.EXPENSE, PRODUCTS.id, 18_000, d(9, 10))
        val old = add(OperationType.EXPENSE, CAFE.id, 400, d(9, 20))
        ObservePastPeriods(periods, stats)().test {
            val summary = awaitItem().single()
            assertEquals(rub(20_000), summary.budgetKopecks)
            assertEquals(rub(18_400), summary.spentKopecks)
            assertEquals(rub(1_600), summary.leftKopecks)
            operations.delete(old)
            assertEquals(rub(18_000), awaitItem().single().spentKopecks)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no finished periods give an empty list`() = runTest {
        periods.startNew(d(10, 1), d(10, 25), rub(18_000))
        assertEquals(emptyList<Any>(), ObservePastPeriods(periods, stats)().first())
    }
}
