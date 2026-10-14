package ru.hznik.budget.ui.components

import ru.hznik.budget.ui.format.MoneyFormat

/** Клавиша цифровой клавиатуры экрана ввода. */
sealed interface AmountKey {
    data class Digit(val value: Int) : AmountKey
    data object Comma : AmountKey
    data object Backspace : AmountKey
}

/**
 * Набор суммы с клавиатуры по правилам макета Add.dc.html: до 7 цифр рублей и 2 цифр копеек,
 * одна запятая, ведущий ноль заменяется цифрой. Строка вида «1234,5».
 */
object AmountInput {
    private const val MAX_RUBLE_DIGITS = 7
    private const val MAX_KOPECK_DIGITS = 2

    fun press(current: String, key: AmountKey): String = when (key) {
        AmountKey.Backspace -> current.dropLast(1)
        AmountKey.Comma -> if (',' in current) current else (current.ifEmpty { "0" }) + ","
        is AmountKey.Digit -> {
            val parts = current.split(',')
            when {
                parts.size == 2 && parts[1].length >= MAX_KOPECK_DIGITS -> current
                parts.size == 1 && parts[0].length >= MAX_RUBLE_DIGITS -> current
                current == "0" -> key.value.toString()
                else -> current + key.value
            }
        }
    }

    /** Сумма в копейках; 0 для пустой строки. */
    fun toKopecks(input: String): Long {
        if (input.isEmpty()) return 0
        val parts = input.split(',')
        val rubles = parts[0].ifEmpty { "0" }.toLong()
        val kopecks = parts.getOrNull(1).orEmpty().padEnd(MAX_KOPECK_DIGITS, '0').take(MAX_KOPECK_DIGITS).toLong()
        return rubles * 100 + kopecks
    }

    /** Строка набора для существующей суммы: «210», «210,50». */
    fun fromKopecks(kopecks: Long): String {
        val rubles = kopecks / 100
        val kop = kopecks % 100
        return if (kop == 0L) rubles.toString() else "$rubles,${kop.toString().padStart(2, '0')}"
    }

    /** Набранная сумма для показа: «1 234,5 ₽», «0 ₽». */
    fun display(input: String): String {
        val parts = input.ifEmpty { "0" }.split(',')
        val rubles = MoneyFormat.groupDigits(parts[0].ifEmpty { "0" }.toLong())
        val tail = if (parts.size == 2) "," + parts[1] else ""
        return "$rubles$tail${MoneyFormat.CURRENCY_SEPARATOR}₽"
    }
}
