package ru.hznik.budget.domain.usecase

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.domain.model.IncomeSchedule
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.model.PaymentRepeat
import ru.hznik.budget.testing.CAFE
import ru.hznik.budget.testing.FakeCategoryRepository
import ru.hznik.budget.testing.FakeIncomeScheduleRepository
import ru.hznik.budget.testing.FakeOperationRepository
import ru.hznik.budget.testing.FakePeriodRepository
import ru.hznik.budget.testing.FakePlannedPaymentRepository
import ru.hznik.budget.testing.MOSCOW
import ru.hznik.budget.testing.TEST_CLOCK
import ru.hznik.budget.testing.TODAY
import ru.hznik.budget.testing.TRANSFERS
import ru.hznik.budget.testing.at
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Часы, которые идут вместе с виртуальным временем теста. */
private class SchedulerClock(private val scheduler: TestCoroutineScheduler, private val start: Instant) : Clock() {
    override fun getZone(): ZoneId = MOSCOW
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = start.plusMillis(scheduler.currentTime)
}

@OptIn(ExperimentalCoroutinesApi::class)
class PeriodUseCasesTest {
    private val categories = FakeCategoryRepository()
    private val operations = FakeOperationRepository(categories)
    private val payments = FakePlannedPaymentRepository(operations)
    private val periods = FakePeriodRepository(payments)
    private val schedule = FakeIncomeScheduleRepository()

    private fun rub(r: Long) = r * 100

    private fun draft(name: String, rubles: Long, due: LocalDate, repeat: PaymentRepeat = PaymentRepeat.ONCE, categoryId: Long = CAFE.id) =
        PaymentDraft(name, Money(rub(rubles)), due, repeat, categoryId)

    @Test
    fun `today ticker emits again right after midnight`() = runTest {
        val start = LocalDateTime.of(2026, 10, 13, 23, 59).atZone(MOSCOW).toInstant()
        TodayTicker(SchedulerClock(testScheduler, start)).today().test {
            assertEquals(LocalDate.of(2026, 10, 13), awaitItem())
            advanceTimeBy(59_000)
            expectNoEvents()
            advanceTimeBy(2_000)
            assertEquals(LocalDate.of(2026, 10, 14), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onboarding with regular income starts today and ends next 25th`() = runTest {
        val result = CompleteOnboarding(periods, schedule, TEST_CLOCK)(
            amountKopecks = rub(18_000),
            income = IncomeSchedule.Regular(25),
            chosenNextIncome = null,
            paymentDrafts = listOf(draft("Интернет", 650, LocalDate.of(2026, 10, 18), PaymentRepeat.MONTHLY)),
        )
        assertEquals(PeriodSetupResult.Done, result)
        val period = periods.getCurrent()!!
        assertEquals(TODAY, period.startDate)
        assertEquals(LocalDate.of(2026, 10, 25), period.nextIncomeDate)
        assertEquals(1, payments.state.value.size)
        assertTrue(ObserveOnboardingDone(periods, schedule)().first())
    }

    @Test
    fun `onboarding with irregular income needs a future date`() = runTest {
        val complete = CompleteOnboarding(periods, schedule, TEST_CLOCK)
        assertEquals(PeriodSetupResult.IncomeDateNotInFuture, complete(rub(100), IncomeSchedule.Irregular, null, emptyList()))
        assertEquals(PeriodSetupResult.IncomeDateNotInFuture, complete(rub(100), IncomeSchedule.Irregular, TODAY, emptyList()))
        assertFalse(ObserveOnboardingDone(periods, schedule)().first())

        assertEquals(PeriodSetupResult.Done, complete(rub(100), IncomeSchedule.Irregular, LocalDate.of(2026, 11, 1), emptyList()))
        assertEquals(LocalDate.of(2026, 11, 1), periods.getCurrent()!!.nextIncomeDate)
    }

    @Test
    fun `onboarding on income day moves the date a month ahead`() = runTest {
        val incomeDay = Clock.fixed(LocalDate.of(2026, 10, 25).atTime(9, 0).atZone(MOSCOW).toInstant(), MOSCOW)
        CompleteOnboarding(periods, schedule, incomeDay)(rub(100), IncomeSchedule.Regular(25), null, emptyList())
        assertEquals(LocalDate.of(2026, 11, 25), periods.getCurrent()!!.nextIncomeDate)
    }

    @Test
    fun `negative start amount is rejected`() = runTest {
        assertEquals(
            PeriodSetupResult.NegativeAmount,
            CompleteOnboarding(periods, schedule, TEST_CLOCK)(-1, IncomeSchedule.Regular(25), null, emptyList()),
        )
    }

    @Test
    fun `new period suggestion uses remainder before today and today income`() = runTest {
        periods.startNew(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 13), rub(18_000))
        schedule.set(IncomeSchedule.Regular(13))
        suspend fun add(type: OperationType, rubles: Long, date: LocalDate) = operations.insert(
            Operation(type = type, amount = Money(rub(rubles)), categoryId = if (type == OperationType.INCOME) 20 else CAFE.id,
                source = OperationSource.MANUAL, occurredAt = at(date)),
        )
        add(OperationType.INCOME, 2_000, LocalDate.of(2026, 10, 5))
        add(OperationType.EXPENSE, 17_500, LocalDate.of(2026, 10, 10))
        add(OperationType.INCOME, 15_000, TODAY)

        val suggestion = SuggestNewPeriod(periods, operations, schedule, TEST_CLOCK)()

        assertEquals(rub(2_500), suggestion.amountKopecks)
        assertEquals(rub(15_000), suggestion.incomeTodayKopecks)
        assertEquals(LocalDate.of(2026, 11, 13), suggestion.nextIncome)
    }

    @Test
    fun `starting a new period closes the current one`() = runTest {
        periods.startNew(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 25), rub(18_000))
        assertEquals(PeriodSetupResult.Done, StartNewPeriod(periods, TEST_CLOCK)(rub(2_500), LocalDate.of(2026, 11, 13)))

        val (old, current) = periods.state.value
        assertEquals(TODAY, old.endDate)
        assertEquals(TODAY, current.startDate)
        assertNull(current.endDate)
        assertEquals(PeriodSetupResult.IncomeDateNotInFuture, StartNewPeriod(periods, TEST_CLOCK)(rub(1), TODAY))
    }

    @Test
    fun `income date can be moved but must stay after start`() = runTest {
        periods.startNew(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 25), rub(18_000))
        val update = UpdateCurrentPeriod(periods)
        assertEquals(PeriodSetupResult.Done, update(rub(18_000), LocalDate.of(2026, 10, 28)))
        assertEquals(LocalDate.of(2026, 10, 28), periods.getCurrent()!!.nextIncomeDate)
        assertEquals(PeriodSetupResult.IncomeDateNotInFuture, update(rub(18_000), LocalDate.of(2026, 10, 1)))
    }

    @Test
    fun `marking paid creates expense and moves monthly payment`() = runTest {
        val id = payments.save(draft("Интернет", 650, LocalDate.of(2026, 10, 18), PaymentRepeat.MONTHLY).toPayment())

        val opId = MarkPaymentPaid(payments, categories, TEST_CLOCK)(id)!!

        val op = operations.get(opId)!!
        assertEquals(OperationType.EXPENSE, op.type)
        assertEquals(rub(650), op.amount.kopecks)
        assertEquals("Интернет", op.comment)
        assertEquals(TODAY, op.localDate)
        val all = payments.observeAll().first()
        assertEquals(listOf(LocalDate.of(2026, 10, 18), LocalDate.of(2026, 11, 18)), all.map { it.dueDate })
        assertTrue(all.first().isPaid)
        assertNull(MarkPaymentPaid(payments, categories, TEST_CLOCK)(id))
    }

    @Test
    fun `paying a transfers payment records a transfer`() = runTest {
        val id = payments.save(draft("Маме", 1_000, LocalDate.of(2026, 10, 20), categoryId = TRANSFERS.id).toPayment())
        val opId = MarkPaymentPaid(payments, categories, TEST_CLOCK)(id)!!
        assertEquals(OperationType.TRANSFER, operations.get(opId)!!.type)
    }

    @Test
    fun `deleting the payment expense makes it unpaid again`() = runTest {
        val id = payments.save(draft("Курсы", 3_000, LocalDate.of(2026, 10, 20)).toPayment())
        val opId = MarkPaymentPaid(payments, categories, TEST_CLOCK)(id)!!
        operations.delete(opId)
        assertFalse(payments.get(id)!!.isPaid)
    }

    @Test
    fun `paying again after deleting the expense does not duplicate next month`() = runTest {
        val id = payments.save(draft("Интернет", 650, LocalDate.of(2026, 10, 18), PaymentRepeat.MONTHLY).toPayment())
        val pay = MarkPaymentPaid(payments, categories, TEST_CLOCK)
        operations.delete(pay(id)!!)
        pay(id)
        assertEquals(listOf(LocalDate.of(2026, 11, 18)), payments.state.value.filter { it.paidOperationId == null }.map { it.dueDate })
        assertEquals(2, payments.state.value.size)
    }

    @Test
    fun `monthly payment on the 31st comes back after a short month`() = runTest {
        val clock = java.time.Clock.fixed(LocalDate.of(2027, 1, 31).atTime(12, 0).atZone(MOSCOW).toInstant(), MOSCOW)
        val pay = MarkPaymentPaid(payments, categories, clock)
        val jan = payments.save(draft("Аренда", 10_000, LocalDate.of(2027, 1, 31), PaymentRepeat.MONTHLY).toPayment())
        pay(jan)
        val feb = payments.state.value.single { it.paidOperationId == null }
        assertEquals(LocalDate.of(2027, 2, 28), feb.dueDate)
        pay(feb.id)
        assertEquals(LocalDate.of(2027, 3, 31), payments.state.value.single { it.paidOperationId == null }.dueDate)
    }

    @Test
    fun `editing a moved payment keeps its repeat day unless the date changes`() = runTest {
        val save = SavePlannedPayment(payments)
        val id = payments.save(draft("Аренда", 10_000, LocalDate.of(2027, 2, 28), PaymentRepeat.MONTHLY).toPayment(repeatDay = 31))
        save(id, draft("Аренда квартиры", 10_000, LocalDate.of(2027, 2, 28), PaymentRepeat.MONTHLY))
        assertEquals(31, payments.get(id)!!.repeatDay)
        save(id, draft("Аренда квартиры", 10_000, LocalDate.of(2027, 2, 20), PaymentRepeat.MONTHLY))
        assertEquals(20, payments.get(id)!!.repeatDay)
    }

    @Test
    fun `payment name is required`() = runTest {
        val save = SavePlannedPayment(payments)
        assertEquals(PaymentSaveResult.EmptyName, save(null, draft("  ", 100, TODAY)))
        assertEquals(PaymentSaveResult.NameTooLong, save(null, draft("а".repeat(41), 100, TODAY)))
        assertTrue(save(null, draft("Связь", 100, TODAY)) is PaymentSaveResult.Saved)
    }

    @Test
    fun `period status reacts to new operations`() = runTest {
        periods.startNew(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 25), rub(18_000))
        ObservePeriodStatus(periods, operations, payments, TodayTicker(TEST_CLOCK))().test {
            assertEquals(rub(1_500), awaitItem()!!.dailyLimit)
            operations.insert(Operation(type = OperationType.EXPENSE, amount = Money(rub(450)), categoryId = CAFE.id,
                source = OperationSource.MANUAL, occurredAt = at(TODAY)))
            assertEquals(rub(1_050), awaitItem()!!.canSpendToday)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
