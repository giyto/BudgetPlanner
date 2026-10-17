package ru.hznik.budget.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.hznik.budget.ui.format.MoneyFormat

/** Разбор суммы на разряды для прокрутки цифр (spec ui-motion «Прокрутка цифр суммы»; задача 2.1). */
class RollingTextTest {
    private fun rolling(old: Long, new: Long) =
        RollingText.columns(MoneyFormat.balance(old), MoneyFormat.balance(new)).filter { it.rolls }.map { it.old to it.new }

    @Test
    fun `only changed digits roll`() {
        assertEquals(listOf('5' to '0', '0' to '5'), rolling(1_500_00, 1_050_00))
    }

    @Test
    fun `columns are aligned from the right when length changes`() {
        val columns = RollingText.columns(MoneyFormat.balance(1_000_00), MoneyFormat.balance(950_00))
        // «₽» и пробел перед ним стоят в одних разрядах.
        assertEquals('₽' to '₽', columns.last().old to columns.last().new)
        val rolls = columns.filter { it.rolls }.map { it.old to it.new }
        assertEquals("тысяча пропадает, сотни и десятки меняются", listOf('1' to null, '0' to '9', '0' to '5'), rolls)
    }

    @Test
    fun `minus sign and kopecks`() {
        val columns = RollingText.columns(MoneyFormat.balance(300_00), MoneyFormat.balance(-300_00))
        assertEquals("цифры не меняются - не крутятся", 0, columns.count { it.rolls })
        assertEquals(MoneyFormat.MINUS, columns.first().new)
        assertEquals(listOf('5' to '7'), rolling(1_234_50, 1_234_70))
    }

    @Test
    fun `first show has nothing to roll`() {
        assertEquals(0, RollingText.columns(null, MoneyFormat.balance(1_500_00)).count { it.rolls })
    }
}
