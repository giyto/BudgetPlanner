package ru.hznik.budget.domain.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.model.PaymentRepeat
import ru.hznik.budget.domain.model.Period
import ru.hznik.budget.domain.model.PlannedPayment
import ru.hznik.budget.testing.at
import java.time.LocalDate

/** Сценарии spec budget-period и planned-payments теми же числами. */
class BudgetCalculatorTest {
    private val today = LocalDate.of(2026, 10, 13)
    private val period = Period(startDate = LocalDate.of(2026, 10, 1), nextIncomeDate = LocalDate.of(2026, 10, 25), startAmountKopecks = rub(18_000))

    private var nextId = 1L
    private fun rub(r: Long) = r * 100

    private fun op(type: OperationType, rubles: Long, date: LocalDate = today, kopecks: Long = 0) = Operation(
        id = nextId++, type = type, amount = Money(rub(rubles) + kopecks), categoryId = 1,
        source = OperationSource.MANUAL, occurredAt = at(date),
    )

    private fun spend(rubles: Long, date: LocalDate = today) = op(OperationType.EXPENSE, rubles, date)

    private fun payment(rubles: Long, due: LocalDate, paidBy: Operation? = null) = PlannedPayment(
        id = nextId++, name = "Платёж", amount = Money(rub(rubles)), dueDate = due, repeat = PaymentRepeat.ONCE,
        categoryId = 1, paidOperationId = paidBy?.id, paidOn = paidBy?.localDate,
    )

    private fun calc(ops: List<Operation>, payments: List<PlannedPayment> = emptyList(), day: LocalDate = today, p: Period = period) =
        BudgetCalculator.calculate(p, ops, payments, day)

    // --- budget-period ---

    @Test
    fun `ordinary day 331 of 781`() {
        val status = calc(
            ops = listOf(spend(7_420, today.minusDays(1)), spend(450)),
            payments = listOf(payment(1_200, LocalDate.of(2026, 10, 18))),
        )
        assertEquals(12L, status.daysLeft)
        assertEquals(rub(9_380), status.availableAtStartOfDay)
        assertEquals(rub(781), status.dailyLimit)
        assertEquals(rub(331), status.canSpendToday)
        assertFalse(status.isOverspent)
        // Карточка периода из макета: потрачено 7 870, резерв 1 200, осталось 8 930.
        assertEquals(rub(7_870), status.spentInPeriod)
        assertEquals(rub(1_200), status.reserve)
        assertEquals(rub(8_930), status.left)
    }

    @Test
    fun `period bounds exclude operations before start`() {
        val status = calc(ops = listOf(spend(100, LocalDate.of(2026, 9, 30)), spend(200, LocalDate.of(2026, 10, 1))))
        assertEquals(rub(200), status.spentBeforeToday)
    }

    @Test
    fun `daily limit is rounded down to whole rubles`() {
        val p = period.copy(startAmountKopecks = rub(1_000), nextIncomeDate = today.plusDays(3))
        assertEquals(rub(333), calc(emptyList(), p = p).dailyLimit)
    }

    @Test
    fun `income inside the period raises the limit at once`() {
        val p = period.copy(nextIncomeDate = today.plusDays(10))
        val before = calc(emptyList(), p = p).dailyLimit
        val after = calc(listOf(op(OperationType.INCOME, 2_000)), p = p).dailyLimit
        assertEquals(rub(200), after - before)
        assertEquals(rub(20_000), calc(listOf(op(OperationType.INCOME, 2_000)), p = p).budget)
    }

    @Test
    fun `paying a payment due after the income date is ordinary spending`() {
        val p = period.copy(nextIncomeDate = LocalDate.of(2026, 10, 25))
        val rent = spend(5_000)
        val status = calc(listOf(rent), listOf(payment(5_000, LocalDate.of(2026, 11, 1), paidBy = rent)), p = p)
        assertEquals(rub(5_000), status.spentToday)
        assertEquals(0L, status.reserveAtStartOfDay)
    }

    @Test
    fun `refund reduces todays spending`() {
        val status = calc(listOf(spend(1_000), op(OperationType.REFUND, 400)))
        assertEquals(rub(600), status.spentToday)
    }

    @Test
    fun `transfer counts as spending`() {
        assertEquals(rub(500), calc(listOf(op(OperationType.TRANSFER, 500))).spentToday)
    }

    @Test
    fun `last day of the period gives everything available`() {
        val lastDay = period.nextIncomeDate.minusDays(1)
        val status = calc(listOf(spend(10_000, LocalDate.of(2026, 10, 5))), day = lastDay)
        assertEquals(1L, status.daysLeft)
        assertEquals(rub(8_000), status.dailyLimit)
        assertNull(status.tomorrowLimit)
    }

    @Test
    fun `days left across the month change`() {
        val p = Period(startDate = LocalDate.of(2026, 10, 25), nextIncomeDate = LocalDate.of(2026, 11, 25), startAmountKopecks = rub(25_000))
        assertEquals(25L, calc(emptyList(), day = LocalDate.of(2026, 10, 31), p = p).daysLeft)
    }

    @Test
    fun `overspend shows minus 249 and tomorrow limit 759`() {
        val status = calc(
            ops = listOf(spend(7_420, today.minusDays(1)), spend(1_030)),
            payments = listOf(payment(1_200, LocalDate.of(2026, 10, 18))),
        )
        assertTrue(status.isOverspent)
        assertEquals(rub(-249), status.canSpendToday)
        assertEquals(rub(759), status.tomorrowLimit)
    }

    @Test
    fun `tomorrow the limit is recalculated after overspend`() {
        val ops = listOf(spend(7_420, today.minusDays(1)), spend(1_030))
        val payments = listOf(payment(1_200, LocalDate.of(2026, 10, 18)))
        val tomorrow = calc(ops, payments, day = today.plusDays(1))
        assertEquals(rub(759), tomorrow.dailyLimit)
        assertEquals(rub(759), tomorrow.canSpendToday)
    }

    @Test
    fun `negative available is rounded down, not up`() {
        val p = period.copy(startAmountKopecks = 0, nextIncomeDate = today.plusDays(3))
        val status = calc(listOf(spend(1_000, today.minusDays(1))), p = p)
        assertEquals(rub(-334), status.dailyLimit)
    }

    @Test
    fun `income date reached keeps the period with one day`() {
        val status = calc(listOf(spend(100, today)), day = period.nextIncomeDate.plusDays(2))
        assertTrue(status.isIncomeDue)
        assertEquals(1L, status.daysLeft)
        assertEquals(rub(100), status.spentBeforeToday)
    }

    @Test
    fun `remainder before today excludes todays operations`() {
        val ops = listOf(
            op(OperationType.INCOME, 2_000, LocalDate.of(2026, 10, 5)),
            spend(17_500, LocalDate.of(2026, 10, 10)),
            op(OperationType.INCOME, 15_000, today),
            spend(300, today),
        )
        assertEquals(rub(2_500), BudgetCalculator.remainderBefore(period, ops, today))
    }

    // --- planned-payments ---

    @Test
    fun `payments after income date are not reserved`() {
        val status = calc(emptyList(), listOf(payment(650, LocalDate.of(2026, 10, 18)), payment(3_000, LocalDate.of(2026, 10, 28))))
        assertEquals(rub(650), status.reserve)
    }

    @Test
    fun `overdue unpaid payment stays reserved`() {
        assertEquals(rub(1_200), calc(emptyList(), listOf(payment(1_200, LocalDate.of(2026, 10, 10)))).reserve)
    }

    @Test
    fun `paying today does not change can spend`() {
        val unpaid = calc(listOf(spend(450)), listOf(payment(650, LocalDate.of(2026, 10, 18))))
        val payOp = spend(650)
        val paid = calc(listOf(spend(450), payOp), listOf(payment(650, LocalDate.of(2026, 10, 18), paidBy = payOp)))

        assertEquals(unpaid.canSpendToday, paid.canSpendToday)
        assertEquals(rub(0), paid.reserve)
        assertEquals(unpaid.left, paid.left)
    }

    @Test
    fun `paying more than planned counts the difference`() {
        val unpaid = calc(emptyList(), listOf(payment(650, LocalDate.of(2026, 10, 18))))
        val payOp = spend(700)
        val paid = calc(listOf(payOp), listOf(payment(650, LocalDate.of(2026, 10, 18), paidBy = payOp)))
        assertEquals(rub(50), unpaid.canSpendToday - paid.canSpendToday)
    }

    @Test
    fun `payment paid yesterday gives the same limit as if reserved`() {
        val due = LocalDate.of(2026, 10, 18)
        val payOp = spend(650, today.minusDays(1))
        val paidYesterday = calc(listOf(payOp), listOf(payment(650, due, paidBy = payOp)))
        val stillReserved = calc(emptyList(), listOf(payment(650, due)))
        assertEquals(stillReserved.dailyLimit, paidYesterday.dailyLimit)
    }
}
