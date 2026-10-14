package ru.hznik.budget.domain.budget

import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.model.Period
import ru.hznik.budget.domain.model.PlannedPayment
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Результат расчёта FR-03 на день [today]. Все суммы в копейках. */
data class PeriodStatus(
    val today: LocalDate,
    val period: Period,
    /** Стартовая сумма + доходы периода. */
    val budget: Long,
    /** Траты (с переводами, минус возвраты) до сегодняшнего дня. */
    val spentBeforeToday: Long,
    /** Траты за сегодня для «можно сегодня»: без оплат обязательных платежей в пределах их суммы. */
    val spentToday: Long,
    /** Все траты за сегодня, включая оплаты платежей. */
    val spentTodayTotal: Long,
    /** Резерв на начало сегодняшнего дня: оплаченные сегодня платежи ещё в нём. */
    val reserveAtStartOfDay: Long,
    /** Резерв сейчас: только неоплаченные платежи до даты дохода. */
    val reserve: Long,
    val daysLeft: Long,
    val availableAtStartOfDay: Long,
    val dailyLimit: Long,
    val canSpendToday: Long,
    /** Дневной лимит завтра; `null`, если завтра дата дохода. */
    val tomorrowLimit: Long?,
) {
    val isOverspent: Boolean get() = canSpendToday < 0

    /** Наступила дата дохода: пора начинать новый период. */
    val isIncomeDue: Boolean get() = !today.isBefore(period.nextIncomeDate)

    /** Потрачено за период по сегодня включительно. */
    val spentInPeriod: Long get() = spentBeforeToday + spentTodayTotal

    /** «Осталось» в карточке периода: бюджет − потрачено − резерв. */
    val left: Long get() = budget - spentInPeriod - reserve
}

/** Расчёт периода по FR-03 — чистая функция без часов и репозиториев (design.md, решение 1). */
object BudgetCalculator {

    fun calculate(period: Period, operations: List<Operation>, payments: List<PlannedPayment>, today: LocalDate): PeriodStatus {
        val inPeriod = operations.filter { !it.localDate.isBefore(period.startDate) && !it.localDate.isAfter(today) }
        val (beforeToday, todayOps) = inPeriod.partition { it.localDate.isBefore(today) }
        // В резерв входят платежи до даты дохода; резервом покрывается только их оплата.
        val reserved = payments.filter { it.dueDate.isBefore(period.nextIncomeDate) }
        val paymentsByOperation = reserved.filter { it.paidOperationId != null }.associateBy { it.paidOperationId }

        val budget = period.startAmountKopecks + inPeriod.sumOf(::income)
        val spentBeforeToday = beforeToday.sumOf(::spending)
        val spentTodayTotal = todayOps.sumOf(::spending)
        // Оплата обязательного платежа не уменьшает «можно сегодня» в пределах суммы платежа: деньги были в резерве.
        val coveredByReserve = todayOps.sumOf { op ->
            val payment = paymentsByOperation[op.id]
            if (payment != null && op.type.isOutgoing) minOf(op.amount.kopecks, payment.amount.kopecks) else 0L
        }
        val spentToday = spentTodayTotal - coveredByReserve

        val reserveAtStartOfDay = reserved.filter { it.paidOn == null || !it.paidOn.isBefore(today) }.sumOf { it.amount.kopecks }
        val reserve = reserved.filter { !it.isPaid }.sumOf { it.amount.kopecks }

        val daysLeft = maxOf(1L, ChronoUnit.DAYS.between(today, period.nextIncomeDate))
        val available = budget - spentBeforeToday - reserveAtStartOfDay
        val dailyLimit = floorToRuble(Math.floorDiv(available, daysLeft))

        val tomorrowLimit = if (daysLeft > 1) {
            floorToRuble(Math.floorDiv(budget - spentBeforeToday - spentTodayTotal - reserve, daysLeft - 1))
        } else {
            null
        }

        return PeriodStatus(
            today = today,
            period = period,
            budget = budget,
            spentBeforeToday = spentBeforeToday,
            spentToday = spentToday,
            spentTodayTotal = spentTodayTotal,
            reserveAtStartOfDay = reserveAtStartOfDay,
            reserve = reserve,
            daysLeft = daysLeft,
            availableAtStartOfDay = available,
            dailyLimit = dailyLimit,
            canSpendToday = dailyLimit - spentToday,
            tomorrowLimit = tomorrowLimit,
        )
    }

    /**
     * Подсказка суммы нового периода: стартовая сумма + доходы − траты по операциям периода раньше [today].
     * Операции сегодняшнего дня войдут в новый период, поэтому здесь их нет (spec budget-period).
     */
    fun remainderBefore(period: Period, operations: List<Operation>, today: LocalDate): Long {
        val before = operations.filter { !it.localDate.isBefore(period.startDate) && it.localDate.isBefore(today) }
        return period.startAmountKopecks + before.sumOf(::income) - before.sumOf(::spending)
    }

    private fun income(op: Operation): Long = if (op.type == OperationType.INCOME) op.amount.kopecks else 0L

    /** Траты и переводы со знаком «+», возвраты со знаком «−» (FR-03). */
    private fun spending(op: Operation): Long = when {
        op.type.isOutgoing -> op.amount.kopecks
        op.type == OperationType.REFUND -> -op.amount.kopecks
        else -> 0L
    }

    /** Округление вниз до целого рубля, в том числе для отрицательных сумм: перерасход не прячется. */
    private fun floorToRuble(kopecks: Long): Long = Math.floorDiv(kopecks, 100L) * 100L
}
