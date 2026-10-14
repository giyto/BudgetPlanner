package ru.hznik.budget.domain.notifications.parser

import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.notifications.Bank
import ru.hznik.budget.domain.notifications.BankNotification

/**
 * Общий разбор русскоязычных уведомлений банков (design.md add-notification-import, решение 3): сумма, остаток,
 * хвост карты, вид операции по ключевым словам и магазин между суммой и остатком. Банк переопределяет то,
 * что пишет по-своему: вид операции, выбор суммы и получателя.
 */
abstract class RuBankParser(bank: Bank) : BankNotificationParser {
    final override val bankId: String = bank.id
    final override val packageNames: Set<String> = bank.packageNames

    final override fun parse(notification: BankNotification): ParsedNotification? {
        val text = normalize(notification.fullText)
        if (text.isEmpty() || NOT_OPERATION.containsMatchIn(text)) return null
        val kind = kindOf(text) ?: return null
        val balanceMatch = BALANCE.find(text)
        val amountMatch = amountMatch(text, balanceMatch) ?: return null
        val amount = toMoney(amountMatch.groupValues[1]) ?: return null
        if (amount.kopecks <= 0) return null
        return ParsedNotification(
            bankId = bankId,
            kind = kind,
            amount = amount,
            merchant = merchant(text, kind, amountMatch, balanceMatch),
            balance = balanceMatch?.let { toMoney(it.groupValues[1]) },
            cardLast4 = CARD.find(text)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } },
            occurredAt = notification.postedAt,
            looksLikeSalary = kind == NotificationKind.INCOME && SALARY.containsMatchIn(text),
        )
    }

    protected open fun kindOf(text: String): NotificationKind? = when {
        OWN_TRANSFER.containsMatchIn(text) -> NotificationKind.OWN_TRANSFER
        REFUND.containsMatchIn(text) -> NotificationKind.REFUND
        INCOME.containsMatchIn(text) -> NotificationKind.INCOME
        TRANSFER.containsMatchIn(text) -> NotificationKind.TRANSFER_OUT
        PURCHASE.containsMatchIn(text) -> NotificationKind.PURCHASE
        else -> null
    }

    /** Первая сумма до остатка: остаток суммой операции не считается. */
    protected open fun amountMatch(text: String, balanceMatch: MatchResult?): MatchResult? =
        AMOUNT.findAll(text).firstOrNull { match -> balanceMatch == null || match.range.first < balanceMatch.range.first }

    protected open fun merchant(text: String, kind: NotificationKind, amountMatch: MatchResult, balanceMatch: MatchResult?): String? =
        cleanMerchant(text, amountMatch.range.last + 1, balanceMatch?.range?.first ?: text.length)

    /** Магазин или получатель — текст в границах без служебных слов, хвостов карт и времени. */
    protected fun cleanMerchant(text: String, from: Int, until: Int): String? {
        if (from >= until) return null
        var part = text.substring(from, until)
        part = CARD.replace(part, " ")
        part = SERVICE_WORDS.replace(part, " ")
        part = part.replace(Regex("""(?<!\d)\d{1,2}:\d{2}(?!\d)"""), " ")
        part = part.trim(' ', '.', ',', ':', ';', '-', '/', '«', '»', '"')
        part = part.replace(Regex(" {2,}"), " ").substringBefore(". ").substringBefore("; ").trim(' ', '.', ',', ';')
        return part.takeIf { it.length >= 2 && it.any(Char::isLetter) }?.take(MAX_MERCHANT)
    }

    private fun toMoney(raw: String): Money? {
        val cleaned = raw.replace(" ", "").replace(',', '.')
        val value = cleaned.toBigDecimalOrNull() ?: return null
        return Money(value.movePointRight(2).toLong())
    }

    private fun normalize(text: String): String =
        text.replace(' ', ' ').replace(' ', ' ').replace('−', '-')
            .replace(Regex("[\r\n\t]+"), " ").replace(Regex(" {2,}"), " ").trim()

    protected companion object {
        const val MAX_MERCHANT = 80

        /** RUR — старое обозначение рубля, его пишет Альфа-Банк. */
        const val CURRENCY = """(?:₽|руб\.?|р\.?(?![а-яё])|RUB|RUR)"""
        const val NUMBER = """(\d{1,3}(?: \d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)"""
        const val START = """(?<![\p{L}\d])"""

        val AMOUNT = Regex("""(?<![\d*•])[+-]?$NUMBER ?$CURRENCY""", RegexOption.IGNORE_CASE)
        val BALANCE = Regex("""(?:баланс|доступно|остаток)[:\s]*$NUMBER ?$CURRENCY""", RegexOption.IGNORE_CASE)
        val CARD = Regex("""(?:сч[её]т|карт[аеыу]?|mir|visa|ecmc|maestro|мир)?\s*[*•]+\s*(\d{4})(?!\d)|(?:сч[её]т|mir|visa|ecmc|maestro)-?\s?(\d{4})(?!\d)""", RegexOption.IGNORE_CASE)

        val NOT_OPERATION = Regex("""$START(?:код|пароль|вход в|никому не сообщайте|акция|акции|кешбэк до|предложени)""", RegexOption.IGNORE_CASE)
        val OWN_TRANSFER = Regex("""между своими|своими счетами|перевод себе|на свой сч""", RegexOption.IGNORE_CASE)
        val REFUND = Regex("""возврат|отмена покупки|отмена операции""", RegexOption.IGNORE_CASE)

        // «+сумма ₽», а не любой «+»: иначе перевод на номер «+7 900…» стал бы зачислением.
        val INCOME = Regex(
            """$START(?:зачислени|поступлени|пополнени|перевод от|входящий перевод|вам перевели|зарплат|стипенд|аванс)|\+\d[\d ]*(?:[.,]\d{1,2})? ?$CURRENCY""",
            RegexOption.IGNORE_CASE,
        )
        val TRANSFER = Regex("""перевод|сбп""", RegexOption.IGNORE_CASE)
        val PURCHASE = Regex("""покупка|оплата|списание|платёж|платеж""", RegexOption.IGNORE_CASE)
        val SALARY = Regex("""зарплат|заработн|стипенд|аванс""", RegexOption.IGNORE_CASE)

        // Границы слов через \p{L}, а не \b: на JVM 19+ \b не считает кириллицу буквами, а на Android считает.
        val SERVICE_WORDS = Regex(
            """(?<![\p{L}\d])(?:покупка|оплата|списание|перевод(?:\s+по\s+сбп)?|сбп|зачисление|поступление|пополнение|возврат(?:\s+покупки)?|отмена(?:\s+покупки)?|от|кому|получатель|отправитель|в|на|карта|карты|счёт|счет)(?![\p{L}\d])""",
            RegexOption.IGNORE_CASE,
        )
    }
}
