package ru.hznik.budget.domain.notifications.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.notifications.BankNotification
import java.time.Instant

/**
 * Парсер Сбера на типичных форматах (spec notification-import, «Разбор уведомлений Сбера»). Это предварительные
 * тексты: после сбора образцов с телефона сюда добавятся настоящие замаскированные уведомления (tasks.md, 6.1).
 */
class SberParserTest {
    private val parser = SberParser()
    private val at = Instant.parse("2026-10-14T09:40:00Z")

    private fun parse(text: String, title: String? = "СберБанк") =
        parser.parse(BankNotification("ru.sberbankmobile", "k", at, title, text))

    @Test
    fun `sms style purchase with balance and card`() {
        val p = parse("СЧЁТ1234 12:40 Покупка 450р PYATEROCHKA Баланс: 12 300.50р", title = null)!!
        assertEquals(NotificationKind.PURCHASE, p.kind)
        assertEquals(Money(450_00), p.amount)
        assertEquals("PYATEROCHKA", p.merchant)
        assertEquals(Money(12_300_50), p.balance)
        assertEquals("1234", p.cardLast4)
    }

    @Test
    fun `push style purchase`() {
        val p = parse("Покупка 1 249 ₽ Ozon. Карта *5678. Баланс 8 000,00 ₽")!!
        assertEquals(NotificationKind.PURCHASE, p.kind)
        assertEquals(Money(1_249_00), p.amount)
        assertEquals("Ozon", p.merchant)
        assertEquals(Money(8_000_00), p.balance)
        assertEquals("5678", p.cardLast4)
    }

    @Test
    fun `non-breaking spaces in amounts`() {
        val p = parse("Оплата 2 500 ₽ Кофейня Зерно")!!
        assertEquals(NotificationKind.PURCHASE, p.kind)
        assertEquals(Money(2_500_00), p.amount)
        assertEquals("Кофейня Зерно", p.merchant)
        assertNull(p.balance)
    }

    @Test
    fun `outgoing transfer to a person`() {
        val p = parse("Перевод 500 ₽ Олег К. Баланс 7 500 ₽")!!
        assertEquals(NotificationKind.TRANSFER_OUT, p.kind)
        assertEquals("Олег К", p.merchant)
        assertEquals(Money(7_500_00), p.balance)
    }

    @Test
    fun `incoming money and salary`() {
        val gift = parse("Перевод от Мама М. +2 000 ₽")!!
        assertEquals(NotificationKind.INCOME, gift.kind)
        assertEquals(Money(2_000_00), gift.amount)
        assertFalse(gift.looksLikeSalary)

        val salary = parse("Зачисление стипендии 9 000 ₽. Баланс 9 450 ₽")!!
        assertEquals(NotificationKind.INCOME, salary.kind)
        assertEquals(Money(9_000_00), salary.amount)
        assertTrue(salary.looksLikeSalary)
    }

    @Test
    fun `refund and own transfer`() {
        val refund = parse("Возврат покупки 450р PYATEROCHKA")!!
        assertEquals(NotificationKind.REFUND, refund.kind)
        assertEquals(Money(450_00), refund.amount)
        assertEquals("PYATEROCHKA", refund.merchant)

        val own = parse("Перевод между своими счетами 1 000 ₽")!!
        assertEquals(NotificationKind.OWN_TRANSFER, own.kind)
        assertEquals(Money(1_000_00), own.amount)
    }

    @Test
    fun `codes and ads are not operations`() {
        assertNull(parse("Код для входа в СберБанк Онлайн: XXXXXX. Никому не сообщайте код"))
        assertNull(parse("Акция: кешбэк до 30% в кафе"))
        assertNull(parse("Ваша карта готова, заберите её в отделении"))
    }

    @Test
    fun `transaction word is not an ad and phone plus is not income`() {
        assertEquals(NotificationKind.PURCHASE, parse("Покупка 300 ₽ Транзакция одобрена Пекарня")!!.kind)
        val transfer = parse("Перевод по СБП 500 ₽ на +7 900 XXX-XX-XX Олег К.")!!
        assertEquals(NotificationKind.TRANSFER_OUT, transfer.kind)
        assertEquals(Money(500_00), transfer.amount)
    }

    @Test
    fun `balance is not taken as the amount`() {
        val p = parse("Покупка 99 ₽ Метро Баланс: 1 000 ₽")!!
        assertEquals(Money(99_00), p.amount)
        assertEquals(Money(1_000_00), p.balance)
    }
}
