package ru.hznik.budget.ui.stats

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.stats.DateRange
import ru.hznik.budget.domain.usecase.ObserveCategoryBreakdown
import ru.hznik.budget.domain.usecase.ObserveDailySpending
import ru.hznik.budget.domain.usecase.ObservePastPeriods
import ru.hznik.budget.domain.usecase.ObserveSpendingComparison
import ru.hznik.budget.domain.usecase.ObserveStatsScope
import ru.hznik.budget.domain.usecase.ObserveTopMerchants
import ru.hznik.budget.domain.usecase.StatsMode
import ru.hznik.budget.domain.usecase.TodayTicker
import ru.hznik.budget.testing.CAFE
import ru.hznik.budget.testing.FakeCategoryRepository
import ru.hznik.budget.testing.FakeOperationRepository
import ru.hznik.budget.testing.FakePeriodRepository
import ru.hznik.budget.testing.FakePlannedPaymentRepository
import ru.hznik.budget.testing.FakeStatisticsRepository
import ru.hznik.budget.testing.MainDispatcherRule
import ru.hznik.budget.testing.TEST_CLOCK
import ru.hznik.budget.testing.TODAY
import ru.hznik.budget.testing.at
import java.time.LocalDate

class StatsViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val categories = FakeCategoryRepository()
    private val operations = FakeOperationRepository(categories)
    private val payments = FakePlannedPaymentRepository(operations)
    private val periods = FakePeriodRepository(payments)
    private val stats = FakeStatisticsRepository(operations)
    private val scope = ObserveStatsScope(periods, TodayTicker(TEST_CLOCK))
    private val loader = StatsContentLoader(
        ObserveCategoryBreakdown(stats, categories),
        ObserveSpendingComparison(stats, categories),
        ObserveTopMerchants(stats),
        ObserveDailySpending(operations, payments, stats),
    )
    private val handle = SavedStateHandle()

    private fun viewModel() = StatsViewModel(handle, scope, loader, ObservePastPeriods(periods, stats))

    private suspend fun spend(rubles: Long, date: LocalDate = TODAY) = operations.insert(
        Operation(type = OperationType.EXPENSE, amount = Money(rubles * 100), categoryId = CAFE.id, source = OperationSource.MANUAL, occurredAt = at(date)),
    )

    @Test
    fun `period mode shows the current period with today limit`() = runTest {
        periods.startNew(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 25), 18_000_00)
        spend(450)
        viewModel().uiState.test {
            var state = awaitItem()
            while (state.content == null) state = awaitItem()
            val content = state.content!!
            assertEquals(StatsMode.PERIOD, state.mode)
            assertEquals(DateRange(LocalDate.of(2026, 10, 1), TODAY), content.range)
            assertEquals(45_000L, content.breakdown.totalKopecks)
            // Сегодня: 18 000 ₽ на 12 дней.
            assertEquals(1_500_00L, content.todayLimitKopecks)
            assertNull(content.comparison)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `month mode is kept in saved state and has no limit line`() = runTest {
        periods.startNew(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 25), 18_000_00)
        spend(300, LocalDate.of(2026, 10, 2))
        val vm = viewModel()
        vm.onModeSelected(StatsMode.MONTH)
        vm.uiState.test {
            var state = awaitItem()
            while (state.mode != StatsMode.MONTH || state.content == null) state = awaitItem()
            val content = state.content!!
            assertEquals(DateRange(LocalDate.of(2026, 10, 1), TODAY), content.range)
            assertEquals(30_000L, content.breakdown.totalKopecks)
            assertNull(content.todayLimitKopecks)
            assertTrue(state.pastPeriods.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        // Новый экземпляр с тем же SavedStateHandle — как после поворота экрана.
        viewModel().uiState.test {
            var state = awaitItem()
            while (!state.loaded) state = awaitItem()
            assertEquals(StatsMode.MONTH, state.mode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no spending gives an empty breakdown`() = runTest {
        periods.startNew(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 25), 18_000_00)
        viewModel().uiState.test {
            var state = awaitItem()
            while (state.content == null) state = awaitItem()
            assertTrue(state.content!!.breakdown.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
