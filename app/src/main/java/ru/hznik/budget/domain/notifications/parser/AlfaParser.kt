package ru.hznik.budget.domain.notifications.parser

import ru.hznik.budget.domain.notifications.SupportedBanks

/**
 * Парсер уведомлений Альфа-Банка по образцам с телефона пользователя (tasks.md, 6.4):
 * «−5 000 ₽ / Списание со счета 4*1234; Перевод на сумму 5 000,00 RUB; Получатель: 796*1234; 14 октября 16:28» и
 * «Уведомление / Перевод на сумму 10.00 RUR из Сбербанк от ИВАН П. по СБП.». Знак суммы в заголовке и слова
 * «Списание» и «из … от» говорят о направлении; сумма — после «на сумму», если она есть.
 */
class AlfaParser : RuBankParser(SupportedBanks.ALFA) {
    override fun kindOf(text: String): NotificationKind? {
        if (OWN_TRANSFER.containsMatchIn(text)) return NotificationKind.OWN_TRANSFER
        if (REFUND.containsMatchIn(text)) return NotificationKind.REFUND
        val outgoing = text.startsWith("-") || DEBIT.containsMatchIn(text)
        val incoming = text.startsWith("+") || INCOMING_TRANSFER.containsMatchIn(text)
        return when {
            outgoing && PURCHASE_WORDS.containsMatchIn(text) -> NotificationKind.PURCHASE
            outgoing && TRANSFER.containsMatchIn(text) -> NotificationKind.TRANSFER_OUT
            outgoing -> NotificationKind.PURCHASE
            incoming -> NotificationKind.INCOME
            else -> super.kindOf(text)
        }
    }

    /** «на сумму 5 000,00 RUB» точнее суммы в заголовке: в ней есть копейки. */
    override fun amountMatch(text: String, balanceMatch: MatchResult?): MatchResult? =
        ON_SUM.find(text) ?: super.amountMatch(text, balanceMatch)

    override fun merchant(text: String, kind: NotificationKind, amountMatch: MatchResult, balanceMatch: MatchResult?): String? {
        SENDER.find(text)?.let { return cleanMerchant(text, it.groups[1]!!.range.first, it.groups[1]!!.range.last + 1) }
        RECIPIENT.find(text)?.let { return cleanMerchant(text, it.groups[1]!!.range.first, it.groups[1]!!.range.last + 1) }
        return super.merchant(text, kind, amountMatch, balanceMatch)
    }

    private companion object {
        val DEBIT = Regex("""$START(?:списание|оплата|покупка)""", RegexOption.IGNORE_CASE)
        val PURCHASE_WORDS = Regex("""$START(?:покупка|оплата)""", RegexOption.IGNORE_CASE)
        /** «Перевод на сумму 10.00 RUR из Сбербанк от ИВАН П.» — деньги пришли из другого банка. */
        val INCOMING_TRANSFER = Regex("""$START(?:из\s+\S+.*?\sот\s|зачислени|поступлени|пополнени)""", RegexOption.IGNORE_CASE)
        val ON_SUM = Regex("""на сумму\s*[+-]?$NUMBER ?$CURRENCY""", RegexOption.IGNORE_CASE)
        val SENDER = Regex("""\sот\s+(.+?)(?:\s+по\s+сбп|[.;]|$)""", RegexOption.IGNORE_CASE)
        val RECIPIENT = Regex("""получатель:?\s*(.+?)(?:;|$)""", RegexOption.IGNORE_CASE)
    }
}
