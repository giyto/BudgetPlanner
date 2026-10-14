package ru.hznik.budget.ui.format

import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.OperationType

/**
 * Суммы для TalkBack: «минус 1234 рубля 50 копеек». Числа остаются цифрами — TalkBack читает их по-русски сам,
 * а слова «рубль» и «копейка» нужны в правильной форме, иначе символ «₽» озвучивается непредсказуемо.
 */
object SpokenMoney {
    fun operation(money: Money, type: OperationType): String =
        when {
            type == OperationType.OWN_TRANSFER -> "перевод себе " + amount(money.kopecks)
            type.isOutgoing -> "минус " + amount(money.kopecks)
            else -> "плюс " + amount(money.kopecks)
        }

    /** Сумма без знака: «1234 рубля 50 копеек», «50 копеек», «3 рубля». */
    fun amount(kopecks: Long): String {
        val abs = kotlin.math.abs(kopecks)
        val rubles = abs / 100
        val kop = abs % 100
        val parts = buildList {
            if (rubles > 0 || kop == 0L) add("$rubles ${plural(rubles, "рубль", "рубля", "рублей")}")
            if (kop > 0) add("$kop ${plural(kop, "копейка", "копейки", "копеек")}")
        }
        return parts.joinToString(" ")
    }

    /** Форма слова для числа по правилам русского языка: 1 рубль, 3 рубля, 5 рублей, 11 рублей, 21 рубль. */
    fun plural(n: Long, one: String, few: String, many: String): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1L -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }
}
