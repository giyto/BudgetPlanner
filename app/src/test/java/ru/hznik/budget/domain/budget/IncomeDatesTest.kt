package ru.hznik.budget.domain.budget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class IncomeDatesTest {
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test
    fun `next income later this month`() {
        assertEquals(d(2026, 10, 25), nextIncomeDate(d(2026, 10, 13), 25))
    }

    @Test
    fun `income day today moves to next month`() {
        assertEquals(d(2026, 11, 25), nextIncomeDate(d(2026, 10, 25), 25))
    }

    @Test
    fun `short month uses its last day`() {
        assertEquals(d(2027, 2, 28), nextIncomeDate(d(2027, 1, 31), 31))
        assertEquals(d(2028, 2, 29), nextIncomeDate(d(2028, 1, 31), 31))
        assertEquals(d(2027, 1, 31), nextIncomeDate(d(2027, 1, 30), 31))
    }

    @Test
    fun `monthly payment moves one month ahead`() {
        assertEquals(d(2026, 11, 18), nextMonthlyDueDate(d(2026, 10, 18)))
        assertEquals(d(2027, 2, 28), nextMonthlyDueDate(d(2027, 1, 31)))
        // После короткого месяца платёж возвращается на своё число.
        assertEquals(d(2027, 3, 31), nextMonthlyDueDate(d(2027, 2, 28), repeatDay = 31))
        assertEquals(d(2027, 4, 30), nextMonthlyDueDate(d(2027, 3, 31), repeatDay = 31))
    }
}
