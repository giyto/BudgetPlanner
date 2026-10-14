package ru.hznik.budget.ui

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.hznik.budget.domain.model.IncomeSchedule
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.model.PaymentRepeat
import ru.hznik.budget.domain.model.PlannedPayment
import ru.hznik.budget.domain.usecase.CompleteOnboarding
import ru.hznik.budget.domain.usecase.ObservePeriodStatus
import ru.hznik.budget.domain.usecase.TodayTicker
import ru.hznik.budget.testing.CAFE
import ru.hznik.budget.testing.FakeCategoryRepository
import ru.hznik.budget.testing.FakeIncomeScheduleRepository
import ru.hznik.budget.testing.FakeLockSettingsRepository
import ru.hznik.budget.testing.FakeOperationRepository
import ru.hznik.budget.testing.FakePeriodRepository
import ru.hznik.budget.testing.FakePlannedPaymentRepository
import ru.hznik.budget.testing.MainDispatcherRule
import ru.hznik.budget.testing.TEST_CLOCK
import ru.hznik.budget.testing.TODAY
import ru.hznik.budget.testing.at
import ru.hznik.budget.ui.components.AmountKey
import ru.hznik.budget.ui.home.HomeViewModel
import ru.hznik.budget.ui.onboarding.OnboardingEvent
import ru.hznik.budget.ui.onboarding.OnboardingStep
import ru.hznik.budget.ui.onboarding.OnboardingViewModel
import java.time.LocalDate

class StageThreeViewModelsTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val categories = FakeCategoryRepository()
    private val operations = FakeOperationRepository(categories)
    private val payments = FakePlannedPaymentRepository(operations)
    private val periods = FakePeriodRepository(payments)
    private val schedule = FakeIncomeScheduleRepository()
    private val lockSettings = FakeLockSettingsRepository()

    private fun rub(r: Long) = r * 100

    private fun onboarding() = OnboardingViewModel(categories, CompleteOnboarding(periods, schedule, TEST_CLOCK), lockSettings, TEST_CLOCK)

    @Test
    fun `onboarding goes through steps and finishes with a period`() = runTest {
        val vm = onboarding()
        vm.uiState.test {
            assertEquals(OnboardingStep.INTRO, awaitItem().step)
            vm.next()
            assertEquals(OnboardingStep.MONEY, expectMostRecentItem().step)

            vm.next() // без суммы и дохода дальше нельзя
            assertEquals(OnboardingStep.MONEY, vm.uiState.value.step)

            listOf(1, 8, 0, 0, 0).forEach { vm.onKey(AmountKey.Digit(it)) }
            vm.onIncomeDay(25)
            val money = expectMostRecentItem()
            assertTrue(money.canContinue)
            assertEquals(LocalDate.of(2026, 10, 25), money.nextIncome)

            vm.next()
            assertEquals(OnboardingStep.PAYMENTS, expectMostRecentItem().step)
            vm.openPaymentForm()
            val form = expectMostRecentItem().paymentForm!!
            vm.onPaymentFormChange(form.copy(name = "Интернет", amountText = "650", repeat = PaymentRepeat.MONTHLY))
            vm.addPayment()
            assertEquals(1, expectMostRecentItem().payments.size)
            cancelAndIgnoreRemainingEvents()
        }
        vm.next()
        assertEquals(OnboardingStep.PROTECTION, vm.uiState.value.step)
        assertNull("данные пишутся только на последнем шаге", periods.getCurrent())
        vm.events.test {
            vm.next() // «Не сейчас»
            assertEquals(OnboardingEvent.Finished, awaitItem())
        }
        assertFalse(lockSettings.state.value.enabled)
        val period = periods.getCurrent()!!
        assertEquals(rub(18_000), period.startAmountKopecks)
        assertEquals(LocalDate.of(2026, 10, 25), period.nextIncomeDate)
        assertEquals(IncomeSchedule.Regular(25), schedule.state.value)
        assertEquals(listOf("Интернет"), payments.state.value.map { it.name })
    }

    @Test
    fun `back goes to previous step and is not handled on the first`() = runTest {
        val vm = onboarding()
        assertFalse(vm.back())
        vm.next()
        assertTrue(vm.back())
        vm.uiState.test {
            assertEquals(OnboardingStep.INTRO, expectMostRecentItem().step)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `skipping payments finishes without them`() = runTest {
        val vm = onboarding()
        vm.next()
        vm.onKey(AmountKey.Digit(5))
        vm.onIncomeRegular(false)
        vm.onIncomeDate(LocalDate.of(2026, 11, 1))
        vm.uiState.test {
            assertTrue(expectMostRecentItem().canContinue)
            cancelAndIgnoreRemainingEvents()
        }
        vm.next()
        vm.skipPayments()
        assertEquals(OnboardingStep.PROTECTION, vm.uiState.value.step)
        vm.events.test {
            vm.finishWithProtection()
            awaitItem()
        }
        assertTrue("«Включить» после проверки включает защиту", lockSettings.state.value.enabled)
        assertEquals(IncomeSchedule.Irregular, schedule.state.value)
        assertTrue(payments.state.value.isEmpty())
    }

    @Test
    fun `invalid payment form shows errors instead of adding`() = runTest {
        val vm = onboarding()
        vm.openPaymentForm()
        vm.addPayment()
        vm.uiState.test {
            val state = expectMostRecentItem()
            assertTrue(state.paymentForm!!.showErrors)
            assertTrue(state.payments.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `home shows status, nearest unpaid payment and two recent operations`() = runTest {
        periods.startNew(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 25), rub(18_000))
        payments.save(PlannedPayment(name = "Курсы", amount = Money(rub(3_000)), dueDate = LocalDate.of(2026, 10, 20), repeat = PaymentRepeat.ONCE, categoryId = CAFE.id))
        payments.save(PlannedPayment(name = "Интернет", amount = Money(rub(650)), dueDate = LocalDate.of(2026, 10, 18), repeat = PaymentRepeat.MONTHLY, categoryId = CAFE.id))
        repeat(3) { i ->
            operations.insert(Operation(type = OperationType.EXPENSE, amount = Money(rub(100L + i)), categoryId = CAFE.id,
                source = OperationSource.MANUAL, occurredAt = at(TODAY, 9 + i)))
        }
        val vm = HomeViewModel(ObservePeriodStatus(periods, operations, payments, TodayTicker(TEST_CLOCK)), payments, operations)
        vm.uiState.test {
            var state = awaitItem()
            while (!state.loaded) state = awaitItem()
            assertNotNull(state.status)
            assertEquals("Интернет", state.nextPayment?.name)
            assertEquals(listOf(rub(102), rub(101)), state.recent.map { it.operation.amount.kopecks })
            assertEquals(rub(3_650), state.status!!.reserve)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `home without period has no status`() = runTest {
        val vm = HomeViewModel(ObservePeriodStatus(periods, operations, payments, TodayTicker(TEST_CLOCK)), payments, operations)
        vm.uiState.test {
            var state = awaitItem()
            while (!state.loaded) state = awaitItem()
            assertNull(state.status)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
