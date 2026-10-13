package ru.hznik.budget.data.time

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.util.TimeZone

class SystemZoneClockTest {
    private lateinit var originalZone: TimeZone

    @Before
    fun saveZone() {
        originalZone = TimeZone.getDefault()
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun `clock follows time zone change without recreation`() {
        val clock = SystemZoneClock()

        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
        assertEquals(ZoneId.of("Europe/Moscow"), clock.zone)

        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Vladivostok"))
        assertEquals(ZoneId.of("Asia/Vladivostok"), clock.zone)
    }
}
