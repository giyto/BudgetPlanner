package ru.hznik.budget.domain.notifications.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.notifications.BankNotification
import java.time.Instant

/** Парсер Т-Банка на типичных форматах; настоящие образцы добавятся, когда соберутся. */
class TBankParserTest {
    private val parser = TBankParser()
    private val at = Instant.parse("2026-10-14T09:40:00Z")

    private fun parse(text: String, title: String? = "Т-Банк") =
        parser.parse(BankNotification("com.idamob.tinkoff.android", "k", at, title, text))

    @Test
    fun `purchase with card and available balance`() {
        val p = parse("Покупка, карта *1234. 450 ₽. PYATEROCHKA. Доступно 12 300 ₽")!!
        assertEquals(NotificationKind.PURCHASE, p.kind)
        assertEquals(Money(450_00), p.amount)
        assertEquals("PYATEROCHKA", p.merchant)
        assertEquals(Money(12_300_00), p.balance)
        assertEquals("1234", p.cardLast4)
        assertEquals("tbank", p.bankId)
    }

    @Test
    fun `top up is income`() {
        val p = parse("Пополнение, счет RUB. 9 000 ₽. Доступно 9 450 ₽")!!
        assertEquals(NotificationKind.INCOME, p.kind)
        assertEquals(Money(9_000_00), p.amount)
        assertEquals(Money(9_450_00), p.balance)
    }

    @Test
    fun `outgoing and incoming transfers`() {
        val out = parse("Перевод. Счет RUB. 500 ₽. Олег К. Доступно 7 500 ₽")!!
        assertEquals(NotificationKind.TRANSFER_OUT, out.kind)
        assertEquals(Money(500_00), out.amount)
        assertEquals("Олег К", out.merchant)
        assertEquals(Money(7_500_00), out.balance)

        val incoming = parse("Входящий перевод 500 ₽ от Мама М.")!!
        assertEquals(NotificationKind.INCOME, incoming.kind)
        assertEquals(Money(500_00), incoming.amount)
        assertEquals("Мама М", incoming.merchant)
    }

    @Test
    fun `cancelled purchase is a refund and codes are skipped`() {
        val refund = parse("Отмена покупки 450 ₽. PYATEROCHKA")!!
        assertEquals(NotificationKind.REFUND, refund.kind)
        assertEquals(Money(450_00), refund.amount)
        assertEquals("PYATEROCHKA", refund.merchant)
        assertNull(parse("Код подтверждения: XXXX. Никому не сообщайте"))
    }
}
