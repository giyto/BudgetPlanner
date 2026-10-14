package ru.hznik.budget.domain.notifications

/**
 * Маскировка текста образца (design.md add-notification-import, решение 2): суммы остаются, а номера карт,
 * счетов, телефонов и любые 4 и больше цифр подряд заменяются на `X`. Даты и время короче 4 цифр подряд и остаются.
 *
 * Пробелы перечислены явно (обычный, неразрывный U+00A0, узкий неразрывный U+202F): `\s` на JVM и в ICU
 * на Android ведёт себя по-разному, и тесты расходились бы с телефоном.
 */
object SampleMasker {
    private const val SPACE = "[ \u00A0\u202F]"

    /**
     * Сумма: число с разделителями тысяч или без них, необязательные копейки и знак валюты сразу после.
     * `(?<!\d)` не даёт сумме начаться посреди хвоста карты: в «*1234 150 ₽» сумма — только «150 ₽».
     */
    private val AMOUNT = Regex(
        """(?<!\d)(?:\d{1,3}(?:$SPACE\d{3})+|\d+)(?:[.,]\d{1,2})?$SPACE?(?:₽|руб\.?|р\.|р(?![а-яё])|RUB|RUR)""",
        RegexOption.IGNORE_CASE,
    )

    /** Телефон вида 3-3-2-2 с префиксом +7, 7 или 8 или без него: скобки, дефисы и пробелы между группами. */
    private val PHONE = Regex("""(?<!\d)(?:\+?7|8)?[ \u00A0\u202F(-]*\d{3}[ \u00A0\u202F)-]*\d{3}[ \u00A0\u202F-]*\d{2}[ \u00A0\u202F-]*\d{2}(?!\d)""")

    private val LONG_DIGITS = Regex("""\d{4,}""")

    fun mask(text: String): String {
        val amounts = AMOUNT.findAll(text).map { it.range }.toList()
        fun insideAmount(range: IntRange) = amounts.any { range.first >= it.first && range.last <= it.last }
        val chars = text.toCharArray()
        for (match in PHONE.findAll(text)) {
            if (insideAmount(match.range)) continue
            for (i in match.range) if (chars[i].isDigit()) chars[i] = 'X'
        }
        for (match in LONG_DIGITS.findAll(text)) {
            if (insideAmount(match.range)) continue
            for (i in match.range) chars[i] = 'X'
        }
        return String(chars)
    }
}
