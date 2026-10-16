package ru.hznik.budget.domain.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.domain.budget.BudgetCalculator
import ru.hznik.budget.domain.budget.PeriodStatus
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.model.PaymentRepeat
import ru.hznik.budget.domain.model.Period
import ru.hznik.budget.domain.model.PlannedPayment
import ru.hznik.budget.domain.reconcile.Mismatch
import ru.hznik.budget.domain.reconcile.ReconAccount
import ru.hznik.budget.domain.reconcile.ReconState
import ru.hznik.budget.testing.at
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Когда приходят уведомления приложения (spec app-notifications) и что показывает виджет (spec home-widget). */
class NotificationPlannerTest {
    private val today = LocalDate.of(2026, 10, 17)
    private val period = Period(id = 7, startDate = LocalDate.of(2026, 10, 1), nextIncomeDate = LocalDate.of(2026, 10, 25), startAmountKopecks = 18_000_00)
    private val all = NotificationType.entries.toSet()

    private fun status(spentRubles: Long = 0, day: LocalDate = today, p: Period = period): PeriodStatus {
        val ops = if (spentRubles == 0L) emptyList() else listOf(
            Operation(id = 1, type = OperationType.EXPENSE, amount = Money(spentRubles * 100), categoryId = 1, source = OperationSource.MANUAL, occurredAt = at(day)),
        )
        return BudgetCalculator.calculate(p, ops, emptyList(), day)
    }

    private fun payment(due: LocalDate, paidOn: LocalDate? = null) = PlannedPayment(
        id = 3, name = "Интернет", amount = Money(650_00), dueDate = due, repeat = PaymentRepeat.MONTHLY, categoryId = 1,
        paidOperationId = paidOn?.let { 99 }, paidOn = paidOn,
    )

    private fun input(
        time: LocalTime = LocalTime.of(12, 0),
        status: PeriodStatus? = status(),
        payments: List<PlannedPayment> = emptyList(),
        mismatches: List<ReconState> = emptyList(),
        income: Money? = null,
        enabled: Set<NotificationType> = all,
        day: LocalDate = today,
    ) = PlannerInput(LocalDateTime.of(day, time), status, payments, mismatches, income, enabled)

    // --- Перерасход ---

    @Test
    fun `overspend is notified once a day`() {
        val first = NotificationPlanner.plan(input(status = status(spentRubles = 5_000)), NotificationLog())
        val over = first.notifications.single() as AppNotification.Overspend
        assertTrue(over.overKopecks > 0)
        assertEquals(today, first.log.overspendDate)
        val second = NotificationPlanner.plan(input(status = status(spentRubles = 6_000)), first.log)
        assertTrue(second.notifications.isEmpty())
    }

    @Test
    fun `next day overspend is notified again`() {
        val log = NotificationLog(overspendDate = today.minusDays(1))
        val plan = NotificationPlanner.plan(input(status = status(spentRubles = 5_000)), log)
        assertTrue(plan.notifications.single() is AppNotification.Overspend)
    }

    @Test
    fun `negative limit from midnight without spending sends nothing`() {
        // Деньги периода кончились вчера: лимит отрицательный с полуночи, но сегодня трат нет.
        val ops = listOf(
            Operation(id = 1, type = OperationType.EXPENSE, amount = Money(19_000_00), categoryId = 1, source = OperationSource.MANUAL, occurredAt = at(today.minusDays(1))),
        )
        val st = BudgetCalculator.calculate(period, ops, emptyList(), today)
        assertTrue(st.isOverspent)
        val midnight = NotificationPlanner.plan(input(time = LocalTime.of(0, 1), status = st), NotificationLog())
        assertTrue(midnight.notifications.isEmpty())
        assertNull(midnight.log.overspendDate)
    }

    @Test
    fun `no overspend no notification`() {
        assertTrue(NotificationPlanner.plan(input(status = status(spentRubles = 100)), NotificationLog()).notifications.isEmpty())
    }

    // --- Платёж ---

    @Test
    fun `payment is reminded the day before from 10 00 and only once`() {
        val p = payment(today.plusDays(1))
        assertTrue(NotificationPlanner.plan(input(time = LocalTime.of(9, 59), payments = listOf(p)), NotificationLog()).notifications.isEmpty())
        val plan = NotificationPlanner.plan(input(time = LocalTime.of(10, 0), payments = listOf(p)), NotificationLog())
        val due = plan.notifications.single() as AppNotification.PaymentDue
        assertEquals("Интернет", due.name)
        assertTrue(NotificationPlanner.plan(input(time = LocalTime.of(18, 0), payments = listOf(p)), plan.log).notifications.isEmpty())
    }

    @Test
    fun `missed reminder still comes later the same day but not on the due day`() {
        val p = payment(today.plusDays(1))
        assertTrue(NotificationPlanner.plan(input(time = LocalTime.of(21, 30), payments = listOf(p)), NotificationLog()).notifications.isNotEmpty())
        val onDueDay = input(time = LocalTime.of(10, 0), payments = listOf(payment(today)), day = today)
        assertTrue(NotificationPlanner.plan(onDueDay, NotificationLog()).notifications.isEmpty())
    }

    @Test
    fun `paid payment is not reminded`() {
        val plan = NotificationPlanner.plan(input(time = LocalTime.of(10, 0), payments = listOf(payment(today.plusDays(1), paidOn = today.minusDays(1)))), NotificationLog())
        assertTrue(plan.notifications.isEmpty())
    }

    @Test
    fun `old payment keys are dropped from the log`() {
        val plan = NotificationPlanner.plan(input(), NotificationLog(payments = setOf("3@2026-10-10", "3@2026-11-18")))
        assertEquals(setOf("3@2026-11-18"), plan.log.payments)
    }

    // --- Новый период ---

    @Test
    fun `income day is notified at 10 00 once per period`() {
        val incomeDay = period.nextIncomeDate
        val st = status(day = incomeDay)
        assertTrue(NotificationPlanner.plan(input(time = LocalTime.of(8, 0), status = st, day = incomeDay), NotificationLog()).notifications.isEmpty())
        val plan = NotificationPlanner.plan(input(time = LocalTime.of(10, 5), status = st, day = incomeDay), NotificationLog())
        assertEquals(AppNotification.IncomeDay, plan.notifications.single())
        assertTrue(NotificationPlanner.plan(input(time = LocalTime.of(15, 0), status = st, day = incomeDay), plan.log).notifications.isEmpty())
    }

    @Test
    fun `income arrived is notified once per period`() {
        val plan = NotificationPlanner.plan(input(income = Money(9_000_00)), NotificationLog())
        assertEquals(AppNotification.IncomeArrived(Money(9_000_00)), plan.notifications.single())
        assertEquals(7L, plan.log.incomeArrivedPeriod)
        assertTrue(NotificationPlanner.plan(input(income = Money(9_000_00)), plan.log).notifications.isEmpty())
    }

    // --- Расхождение ---

    @Test
    fun `mismatch is notified once even after recalculation`() {
        val alfa = ReconAccount("alfa", "1234")
        val anchor = Instant.parse("2026-10-17T06:00:00Z")
        val first = ReconState(alfa, 12_750_00, anchor, Mismatch(12_300_00, 12_750_00, anchor.plusSeconds(3600)))
        val plan = NotificationPlanner.plan(input(mismatches = listOf(first)), NotificationLog())
        val n = plan.notifications.single() as AppNotification.BankMismatch
        assertEquals(-450_00L, n.diffKopecks)
        val recalculated = first.copy(pending = Mismatch(12_200_00, 12_650_00, anchor.plusSeconds(7200)))
        assertTrue(NotificationPlanner.plan(input(mismatches = listOf(recalculated)), plan.log).notifications.isEmpty())
    }

    // --- Отключение по типам ---

    @Test
    fun `disabled types send nothing but still count as handled`() {
        val plan = NotificationPlanner.plan(
            input(time = LocalTime.of(10, 0), status = status(spentRubles = 5_000), payments = listOf(payment(today.plusDays(1))), enabled = setOf(NotificationType.PAYMENT)),
            NotificationLog(),
        )
        assertTrue(plan.notifications.single() is AppNotification.PaymentDue)
        assertEquals(today, plan.log.overspendDate)
    }

    // --- Виджет ---

    @Test
    fun `widget snapshot shows hides or has no data`() {
        assertEquals(WidgetSnapshot.NoData, WidgetSnapshot.of(null, lockEnabled = false))
        assertEquals(WidgetSnapshot.Hidden, WidgetSnapshot.of(status(), lockEnabled = true))
        val shown = WidgetSnapshot.of(status(spentRubles = 100), lockEnabled = false) as WidgetSnapshot.Shown
        assertEquals(shown.dailyLimitKopecks - 100_00, shown.canSpendKopecks)
        assertTrue(shown.spentFraction > 0f && shown.spentFraction < 1f)
        val over = WidgetSnapshot.of(status(spentRubles = 5_000), lockEnabled = false) as WidgetSnapshot.Shown
        assertTrue(over.overspent)
        assertEquals(1f, over.spentFraction)
    }

    @Test
    fun `no status no notifications`() {
        assertTrue(NotificationPlanner.plan(input(status = null, income = Money(100_00)), NotificationLog()).notifications.isEmpty())
        assertNull(NotificationPlanner.plan(input(status = null), NotificationLog()).log.overspendDate)
    }
}
