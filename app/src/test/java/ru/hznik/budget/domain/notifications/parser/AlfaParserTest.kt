package ru.hznik.budget.domain.notifications.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.notifications.BankNotification
import java.time.Instant

/**
 * Парсер Альфа-Банка на настоящих образцах с телефона пользователя (14 октября 2026). Цифры счетов были
 * замаскированы при сборе и здесь заменены на 1234, имя отправителя заменено на вымышленное.
 */
class AlfaParserTest {
    private val parser = AlfaParser()
    private val at = Instant.parse("2026-10-14T13:28:00Z")

    private fun parse(title: String?, text: String) = parser.parse(BankNotification("ru.alfabank.mobile.android", "k", at, title, text))

    @Test
    fun `real sample - outgoing transfer from account`() {
        val p = parse("-5 000 ₽", "Списание со счета 4*1234; Перевод на сумму 5 000,00 RUB; Получатель: 796*1234; 14 октября 16:28")!!
        assertEquals(NotificationKind.TRANSFER_OUT, p.kind)
        assertEquals(Money(5_000_00), p.amount)
        assertEquals("1234", p.cardLast4)
        assertNull("получатель — номер счёта, а не имя", p.merchant)
        assertEquals("alfa", p.bankId)
    }

    @Test
    fun `real sample - incoming transfer from another bank via SBP`() {
        val p = parse("Уведомление", "Перевод на сумму 10.00 RUR из Сбербанк от ИВАН П. по СБП.")!!
        assertEquals(NotificationKind.INCOME, p.kind)
        assertEquals(Money(10_00), p.amount)
        assertEquals("ИВАН П", p.merchant)
    }

    @Test
    fun `typical purchase with sign in title`() {
        val p = parse("-1 249 ₽", "Покупка в OZON, карта *5678. Доступно 8 000,00 RUR")!!
        assertEquals(NotificationKind.PURCHASE, p.kind)
        assertEquals(Money(1_249_00), p.amount)
        assertEquals(Money(8_000_00), p.balance)
        assertEquals("5678", p.cardLast4)
        assertEquals("OZON", p.merchant)
    }

    /** Текст из сценария «Списание переводом» (spec notification-import): в заголовке знак «−» (U+2212), а не дефис. */
    @Test
    fun `spec sample with minus sign in title`() {
        val p = parse("${Char(0x2212)}5 000 ₽", "Списание со счета 4*1234; Перевод на сумму 5 000,00 RUB; Получатель: 796*1234")!!
        assertEquals(NotificationKind.TRANSFER_OUT, p.kind)
        assertEquals(Money(5_000_00), p.amount)
        assertEquals("1234", p.cardLast4)
    }

    @Test
    fun `ads are not operations`() {
        assertNull(parse("Альфа-Банк", "Акция: кешбэк до 10% на кафе в октябре"))
    }
}
