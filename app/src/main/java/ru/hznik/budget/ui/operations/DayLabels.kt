package ru.hznik.budget.ui.operations

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RU = Locale.forLanguageTag("ru")
private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM", RU)
private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMMM yyyy", RU)
private val WEEKDAY = DateTimeFormatter.ofPattern("EEEE", RU)
private val SHORT = DateTimeFormatter.ofPattern("d MMM", RU)

/** Заголовок группы дня: «Сегодня, 13 октября», «Вчера, 12 октября», «Воскресенье, 11 октября». */
fun dayTitle(date: LocalDate, today: LocalDate): String {
    val dayMonth = (if (date.year == today.year) DAY_MONTH else DAY_MONTH_YEAR).format(date)
    val prefix = when (date) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> WEEKDAY.format(date).replaceFirstChar { it.titlecase(RU) }
    }
    return "$prefix, $dayMonth"
}

/** Подпись чипа даты на экране ввода: «Сегодня», «Вчера» или «11 окт.». */
fun dateChipLabel(date: LocalDate, today: LocalDate, todayLabel: String, yesterdayLabel: String): String = when (date) {
    today -> todayLabel
    today.minusDays(1) -> yesterdayLabel
    else -> SHORT.format(date)
}
