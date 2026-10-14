package ru.hznik.budget.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.OperationType

class MoneyFormatTest {
    // В ожидаемых строках: U+00A0 между разрядами и перед «₽», U+2212 — минус.
    @Test
    fun `expense with kopecks`() {
        assertEquals("\u22121\u00A0234,50\u00A0₽", MoneyFormat.operation(Money(123_450), OperationType.EXPENSE))
    }

    @Test
    fun `whole income without kopecks`() {
        assertEquals("+2\u00A0000\u00A0₽", MoneyFormat.operation(Money(200_000), OperationType.INCOME))
    }

    @Test
    fun `transfer is outgoing and refund is incoming`() {
        assertEquals("\u2212500\u00A0₽", MoneyFormat.operation(Money(50_000), OperationType.TRANSFER))
        assertEquals("+1\u00A0500\u00A0₽", MoneyFormat.operation(Money(150_000), OperationType.REFUND))
    }

    @Test
    fun `single digit kopecks are padded`() {
        assertEquals("0,05\u00A0₽", MoneyFormat.amount(5))
    }

    @Test
    fun `large amount groups digits`() {
        assertEquals("9\u00A0999\u00A0999,99\u00A0₽", MoneyFormat.amount(999_999_999))
    }

    @Test
    fun `day total`() {
        assertEquals("\u2212330\u00A0₽ · +2\u00A0000\u00A0₽", MoneyFormat.dayTotal(33_000, 200_000))
        assertEquals("\u2212330\u00A0₽", MoneyFormat.dayTotal(33_000, 0))
        assertEquals("+2\u00A0000\u00A0₽", MoneyFormat.dayTotal(0, 200_000))
        assertEquals("+500\u00A0₽", MoneyFormat.dayTotal(-50_000, 0))
    }
}
