package ru.hznik.budget.ui.stats

import ru.hznik.budget.domain.stats.DateRange
import ru.hznik.budget.domain.stats.DaySpending
import ru.hznik.budget.ui.format.SpokenMoney
import java.time.DayOfWeek
import java.time.LocalDate

/** Подписи дат статистики как в макете: «25 сен – 13 окт», «Пн 12». */
object StatsFormat {
    private val SHORT_MONTHS = listOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
    private val FULL_MONTHS = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )
    private val SHORT_DAYS = mapOf(
        DayOfWeek.MONDAY to "Пн", DayOfWeek.TUESDAY to "Вт", DayOfWeek.WEDNESDAY to "Ср", DayOfWeek.THURSDAY to "Чт",
        DayOfWeek.FRIDAY to "Пт", DayOfWeek.SATURDAY to "Сб", DayOfWeek.SUNDAY to "Вс",
    )

    fun shortDate(date: LocalDate): String = "${date.dayOfMonth} ${SHORT_MONTHS[date.monthValue - 1]}"

    fun fullDate(date: LocalDate): String = "${date.dayOfMonth} ${FULL_MONTHS[date.monthValue - 1]}"

    /** «25 сен – 13 окт». */
    fun range(range: DateRange): String = "${shortDate(range.start)} – ${shortDate(range.endInclusive)}"

    /** «с 25 сентября по 13 октября» — для TalkBack. */
    fun spokenRange(range: DateRange): String = "с ${fullDate(range.start)} по ${fullDate(range.endInclusive)}"

    /** «Пн 12» под столбиком. */
    fun dayLabel(date: LocalDate): String = "${SHORT_DAYS.getValue(date.dayOfWeek)} ${date.dayOfMonth}"

    /**
     * Описание диаграммы по дням для TalkBack: каждый день с суммой и отметкой о лимите, дни без трат подряд объединяются
     * («с 1 по 3 октября трат нет»), иначе описание длинного периода было бы слишком длинным.
     */
    fun spokenDays(days: List<DaySpending>, withinLimit: String, overLimit: String): String {
        val parts = mutableListOf<String>()
        var index = 0
        while (index < days.size) {
            val day = days[index]
            if (day.spentKopecks == 0L) {
                var end = index
                while (end + 1 < days.size && days[end + 1].spentKopecks == 0L) end++
                parts += if (end == index) "${fullDate(day.date)}: трат нет" else "с ${fullDate(day.date)} по ${fullDate(days[end].date)} трат нет"
                index = end + 1
                continue
            }
            val limit = day.limitKopecks?.let { ", ${if (day.isOverLimit) overLimit else withinLimit} ${SpokenMoney.amount(it)}" }.orEmpty()
            parts += "${fullDate(day.date)}: ${SpokenMoney.amount(day.spentKopecks)}$limit"
            index++
        }
        return parts.joinToString(". ")
    }
}
