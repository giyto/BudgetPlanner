package ru.hznik.budget.domain.notifications.parser

import ru.hznik.budget.domain.notifications.SupportedBanks

/**
 * Парсер уведомлений Т-Банка (design.md add-notification-import, решение 3).
 *
 * Предварительная версия по известным форматам: «Покупка, карта *1234. 450 ₽. PYATEROCHKA. Доступно 12 300 ₽»,
 * «Пополнение, счет RUB. 9 000 ₽. Доступно 9 450 ₽», «Перевод. Счет RUB. 500 ₽. Олег К. Доступно 7 500 ₽»,
 * «Входящий перевод 500 ₽ от Олег К.», «Отмена покупки 450 ₽». Правится по настоящим образцам, когда они появятся.
 */
class TBankParser : RuBankParser(SupportedBanks.TBANK) {
    /** Магазин или получатель идут сразу после суммы до «Доступно»; во входящем переводе — после «от». */
    override fun merchant(text: String, kind: NotificationKind, amountMatch: MatchResult, balanceMatch: MatchResult?): String? {
        if (kind == NotificationKind.INCOME) {
            SENDER.find(text)?.let { return cleanMerchant(text, it.groups[1]!!.range.first, it.groups[1]!!.range.last + 1) }
        }
        return super.merchant(text, kind, amountMatch, balanceMatch)
    }

    private companion object {
        val SENDER = Regex("""$START(?:от)\s+(.+?)(?:\.\s|\. ?доступно|$)""", RegexOption.IGNORE_CASE)
    }
}
