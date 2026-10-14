package ru.hznik.budget.data.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DemoDataPlanTest {
    private val today = LocalDate.of(2026, 10, 20)
    private val currentStart = LocalDate.of(2026, 10, 14)
    private val plan = DemoDataPlan.generate(currentStart, earliestStart = currentStart, today = today)

    @Test
    fun `two past periods end where the user's periods begin`() {
        assertEquals(
            listOf(LocalDate.of(2026, 8, 14) to LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 14) to currentStart),
            plan.periods.map { it.start to it.end },
        )
    }

    @Test
    fun `operations never touch today or the future and stay in demo periods or the current one`() {
        val dates = plan.operations.map { it.at.toLocalDate() }
        assertTrue(dates.all { it.isBefore(today) })
        assertTrue(dates.all { !it.isBefore(LocalDate.of(2026, 8, 14)) })
        assertTrue(plan.operations.size > 60)
    }

    @Test
    fun `same seed gives the same data`() {
        assertEquals(plan, DemoDataPlan.generate(currentStart, currentStart, today))
    }
}
