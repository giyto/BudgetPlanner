package ru.hznik.budget.ui.operations

import org.junit.Assert.assertEquals
import org.junit.Test

class AmountInputTest {
    private fun type(vararg keys: AmountKey, start: String = ""): String = keys.fold(start, AmountInput::press)
    private fun d(n: Int) = AmountKey.Digit(n)

    @Test
    fun `digits, comma and backspace`() {
        assertEquals("180", type(d(1), d(8), d(0)))
        assertEquals("1234,5", type(d(1), d(2), d(3), d(4), AmountKey.Comma, d(5)))
        assertEquals("12", type(AmountKey.Backspace, start = "123"))
    }

    @Test
    fun `comma at start adds zero and second comma is ignored`() {
        assertEquals("0,", type(AmountKey.Comma))
        assertEquals("1,5", type(AmountKey.Comma, start = "1,5"))
    }

    @Test
    fun `leading zero is replaced`() {
        assertEquals("7", type(d(7), start = "0"))
        assertEquals("0,7", type(d(7), start = "0,"))
    }

    @Test
    fun `limits to seven ruble digits and two kopeck digits`() {
        assertEquals("1234567", type(d(8), start = "1234567"))
        assertEquals("1234567,99", type(d(9), start = "1234567,99"))
        assertEquals("1234567,9", type(d(9), start = "1234567,"))
    }

    @Test
    fun `conversion to kopecks and back`() {
        assertEquals(0L, AmountInput.toKopecks(""))
        assertEquals(18_000L, AmountInput.toKopecks("180"))
        assertEquals(123_450L, AmountInput.toKopecks("1234,5"))
        assertEquals(123_405L, AmountInput.toKopecks("1234,05"))
        assertEquals(100L, AmountInput.toKopecks("1,"))
        assertEquals("210", AmountInput.fromKopecks(21_000))
        assertEquals("210,50", AmountInput.fromKopecks(21_050))
    }

    @Test
    fun `display groups digits and keeps typed comma`() {
        assertEquals("0\u00A0₽", AmountInput.display(""))
        assertEquals("1\u00A0234,5\u00A0₽", AmountInput.display("1234,5"))
        assertEquals("12,\u00A0₽", AmountInput.display("12,"))
    }
}
