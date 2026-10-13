package ru.hznik.budget.domain.time

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class TodayTest {
    private val moscow = ZoneId.of("Europe/Moscow")
    private val lastMinuteOfYear = LocalDateTime.of(2026, 12, 31, 23, 59).atZone(moscow).toInstant()

    @Test
    fun `fixed clock gives fixed date instead of system date`() {
        val clock = Clock.fixed(lastMinuteOfYear, moscow)

        assertEquals(LocalDate.of(2026, 12, 31), clock.today())
    }

    @Test
    fun `date depends on clock time zone`() {
        val clock = Clock.fixed(lastMinuteOfYear, ZoneId.of("Asia/Vladivostok"))

        assertEquals(LocalDate.of(2027, 1, 1), clock.today())
    }
}
