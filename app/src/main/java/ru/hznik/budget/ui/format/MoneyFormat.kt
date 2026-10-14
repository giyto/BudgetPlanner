package ru.hznik.budget.ui.format

import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.OperationType

/**
 * Суммы для экрана: «−1 234,50 ₽», «+2 000 ₽». Разряды и «₽» отделяются неразрывным пробелом, чтобы сумма
 * не переносилась по строкам. Узкий U+202F не подходит: в шрифте Onest его нет, и разряды сливаются.
 * Без Android-зависимостей, тестируется на JVM.
 */
object MoneyFormat {
    const val GROUP_SEPARATOR = '\u00A0'
    const val CURRENCY_SEPARATOR = '\u00A0'
    const val MINUS = '\u2212'

    /** Сумма без знака: «1 234,50 ₽». */
    fun amount(kopecks: Long): String {
        val abs = kotlin.math.abs(kopecks)
        val rubles = groupDigits(abs / 100)
        val kop = (abs % 100).toInt()
        val number = if (kop == 0) rubles else "$rubles,${kop.toString().padStart(2, '0')}"
        return "$number$CURRENCY_SEPARATOR₽"
    }

    /** Сумма операции со знаком по её типу. */
    fun operation(money: Money, type: OperationType): String =
        (if (type.isOutgoing) MINUS else '+') + amount(money.kopecks)

    /** Итог дня: траты минус возвраты со знаком «−» и доходы со знаком «+», через «·». */
    fun dayTotal(netSpentKopecks: Long, incomeKopecks: Long): String {
        val parts = buildList {
            when {
                netSpentKopecks > 0 -> add(MINUS + amount(netSpentKopecks))
                netSpentKopecks < 0 -> add("+" + amount(netSpentKopecks))
            }
            if (incomeKopecks > 0) add("+" + amount(incomeKopecks))
        }
        return parts.joinToString(" · ")
    }

    /** Цифры целых рублей с разделителем разрядов. */
    fun groupDigits(value: Long): String =
        value.toString().reversed().chunked(3).joinToString(GROUP_SEPARATOR.toString()).reversed()
}
