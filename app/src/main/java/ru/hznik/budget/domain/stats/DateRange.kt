package ru.hznik.budget.domain.stats

import ru.hznik.budget.domain.model.Period
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Дни с [start] по [endInclusive] включительно; пустой, если конец раньше начала. Основа всех агрегатов (design.md, решение 1). */
data class DateRange(val start: LocalDate, val endInclusive: LocalDate) {
    val isEmpty: Boolean get() = endInclusive.isBefore(start)

    /** Число дней в диапазоне. */
    val length: Long get() = if (isEmpty) 0 else ChronoUnit.DAYS.between(start, endInclusive) + 1

    val days: List<LocalDate> get() = (0 until length).map { start.plusDays(it) }

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(endInclusive)

    companion object {
        /** Текущий период с начала по сегодня; завершённый — целиком (`end_date` у него — первый день следующего). */
        fun of(period: Period, today: LocalDate): DateRange =
            DateRange(period.startDate, period.endDate?.minusDays(1) ?: today)

        /** Календарный месяц [today] с первого числа по сегодня. */
        fun month(today: LocalDate): DateRange = DateRange(today.withDayOfMonth(1), today)
    }
}

/** Первые дни [previous] той же длины, что [current], но не дальше конца [previous] (решение пользователя: «те же дни от начала»). */
fun previousPeriodRange(current: DateRange, previous: Period): DateRange {
    val previousEnd = previous.endDate?.minusDays(1) ?: previous.nextIncomeDate.minusDays(1)
    val sameLength = previous.startDate.plusDays(current.length - 1)
    return DateRange(previous.startDate, minOf(sameLength, previousEnd))
}

/** Те же числа прошлого месяца: с 1-го по день конца [current], а если в прошлом месяце дней меньше — по его последний день. */
fun previousMonthRange(current: DateRange): DateRange {
    val month = current.start.minusMonths(1)
    return DateRange(month.withDayOfMonth(1), month.withDayOfMonth(minOf(current.endInclusive.dayOfMonth, month.lengthOfMonth())))
}
