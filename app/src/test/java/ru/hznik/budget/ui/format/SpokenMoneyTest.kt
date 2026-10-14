package ru.hznik.budget.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.OperationType

class SpokenMoneyTest {
    private fun rubles(n: Long) = SpokenMoney.amount(n * 100)

    @Test
    fun `ruble forms`() {
        assertEquals("1 рубль", rubles(1))
        assertEquals("2 рубля", rubles(2))
        assertEquals("5 рублей", rubles(5))
        assertEquals("11 рублей", rubles(11))
        assertEquals("21 рубль", rubles(21))
        assertEquals("111 рублей", rubles(111))
        assertEquals("1234 рубля", rubles(1234))
    }

    @Test
    fun `kopeck forms`() {
        assertEquals("1 рубль 1 копейка", SpokenMoney.amount(101))
        assertEquals("3 рубля 22 копейки", SpokenMoney.amount(322))
        assertEquals("5 рублей 15 копеек", SpokenMoney.amount(515))
        assertEquals("50 копеек", SpokenMoney.amount(50))
    }

    @Test
    fun `operation has direction`() {
        assertEquals("минус 1234 рубля 50 копеек", SpokenMoney.operation(Money(123_450), OperationType.EXPENSE))
        assertEquals("плюс 1 рубль", SpokenMoney.operation(Money(100), OperationType.INCOME))
        assertEquals("плюс 3 рубля", SpokenMoney.operation(Money(300), OperationType.INCOME))
        assertEquals("плюс 11 рублей", SpokenMoney.operation(Money(1100), OperationType.REFUND))
        assertEquals("минус 500 рублей", SpokenMoney.operation(Money(50_000), OperationType.TRANSFER))
    }
}
