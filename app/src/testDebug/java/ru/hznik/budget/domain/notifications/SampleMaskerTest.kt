package ru.hznik.budget.domain.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

/** Маскировка образцов (spec notification-import, «Режим сбора образцов»). */
class SampleMaskerTest {
    private fun mask(text: String) = SampleMasker.mask(text)

    @Test
    fun `card tail is masked and amounts stay`() {
        assertEquals(
            "Покупка 450 ₽ Пятёрочка. Карта *XXXX. Баланс 12 300,50 ₽",
            mask("Покупка 450 ₽ Пятёрочка. Карта *1234. Баланс 12 300,50 ₽"),
        )
    }

    @Test
    fun `different card tail styles`() {
        assertEquals("MIR-XXXX 14:05 Оплата 99 RUB", mask("MIR-5678 14:05 Оплата 99 RUB"))
        assertEquals("Карта •• XXXX", mask("Карта •• 4321"))
        assertEquals("СЧЁТXXXX Покупка 1250р", mask("СЧЁТ1234 Покупка 1250р"))
    }

    @Test
    fun `account numbers and phones are masked entirely`() {
        assertEquals("Счёт XXXXXXXXXXXXXXXXXXXX", mask("Счёт 40817810099910004312"))
        assertEquals("Перевод от +X XXX XXX-XX-XX", mask("Перевод от +7 912 345-67-89"))
        assertEquals("Перевод по номеру X (XXX) XXX-XX-XX", mask("Перевод по номеру 8 (912) 345-67-89"))
    }

    @Test
    fun `big amounts with thousand separators stay`() {
        assertEquals("Зачисление 1 000 000 ₽", mask("Зачисление 1 000 000 ₽"))
        assertEquals("Зачисление 18 000 руб.", mask("Зачисление 18 000 руб."))
        assertEquals("Покупка 2500.00 р. баланс", mask("Покупка 2500.00 р. баланс"))
    }

    @Test
    fun `card tail right before an amount is masked`() {
        assertEquals("Карта *XXXX 150,00 ₽", mask("Карта *1234 150,00 ₽"))
        assertEquals("Счёт …XXXX 500 р", mask("Счёт …6789 500 р"))
    }

    @Test
    fun `non-breaking spaces in amounts behave like spaces`() {
        assertEquals("Покупка 1\u00A0250\u00A0₽", mask("Покупка 1\u00A0250\u00A0₽"))
        assertEquals("Покупка 1250\u00A0₽", mask("Покупка 1250\u00A0₽"))
        assertEquals("Баланс 12\u202F300,50 ₽", mask("Баланс 12\u202F300,50 ₽"))
    }

    @Test
    fun `phones without plus seven are masked`() {
        assertEquals("Перевод XXX XXX-XX-XX", mask("Перевод 912 345-67-89"))
        assertEquals("Перевод X XXX XXX XX XX", mask("Перевод 7 912 345 67 89"))
    }

    @Test
    fun `short dates and times stay`() {
        assertEquals("14.10 12:40 покупка", mask("14.10 12:40 покупка"))
    }

    @Test
    fun `code of four or more digits is masked`() {
        assertEquals("Код для входа: XXXXXX", mask("Код для входа: 482913"))
    }
}
