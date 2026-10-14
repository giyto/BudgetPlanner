package ru.hznik.budget.ui.operations

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DayLabelsTest {
    private val today = LocalDate.of(2026, 10, 13)

    @Test
    fun `today, yesterday and weekday titles`() {
        assertEquals("Сегодня, 13 октября", dayTitle(today, today))
        assertEquals("Вчера, 12 октября", dayTitle(today.minusDays(1), today))
        assertEquals("Воскресенье, 11 октября", dayTitle(today.minusDays(2), today))
    }

    @Test
    fun `previous year shows the year`() {
        assertEquals("Среда, 31 декабря 2025", dayTitle(LocalDate.of(2025, 12, 31), today))
    }
}
