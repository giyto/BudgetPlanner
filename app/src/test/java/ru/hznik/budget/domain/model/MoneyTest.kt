package ru.hznik.budget.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test(expected = IllegalArgumentException::class)
    fun `zero is rejected`() {
        Money(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `above maximum is rejected`() {
        Money(1_000_000_000)
    }

    @Test
    fun `bounds are accepted`() {
        assertEquals(1L, Money(1).kopecks)
        assertEquals(9_999_999L, Money(999_999_999).rubles)
    }

    @Test
    fun `rubles and kopecks parts`() {
        val money = Money(123_450)
        assertEquals(1234L, money.rubles)
        assertEquals(50, money.kopecksPart)
    }

    @Test
    fun `ofKopecksOrNull returns null outside range`() {
        assertNull(Money.ofKopecksOrNull(0))
        assertNull(Money.ofKopecksOrNull(-5))
        assertEquals(Money(500), Money.ofKopecksOrNull(500))
    }
}
