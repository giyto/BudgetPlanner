package ru.hznik.budget.domain.budget

import java.time.LocalDate

/**
 * Ближайшее [dayOfMonth] число строго после [after]. Если в месяце нет такого числа, берётся последний день месяца:
 * доход 31 числа в феврале приходит 28-го (29-го в високосный год).
 */
fun nextIncomeDate(after: LocalDate, dayOfMonth: Int): LocalDate {
    val thisMonth = after.withDayOfMonth(minOf(dayOfMonth, after.lengthOfMonth()))
    if (thisMonth.isAfter(after)) return thisMonth
    val next = after.plusMonths(1)
    return next.withDayOfMonth(minOf(dayOfMonth, next.lengthOfMonth()))
}

/** Дата ежемесячного платежа в следующем месяце: число [repeatDay] или последний день месяца, если такого числа нет. */
fun nextMonthlyDueDate(dueDate: LocalDate, repeatDay: Int = dueDate.dayOfMonth): LocalDate {
    val next = dueDate.plusMonths(1)
    return next.withDayOfMonth(minOf(repeatDay, next.lengthOfMonth()))
}
