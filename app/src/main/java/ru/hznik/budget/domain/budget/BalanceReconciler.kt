package ru.hznik.budget.domain.budget

import ru.hznik.budget.domain.reconcile.ReconAccount

/** Сравнение остатка банка с расчётом (FR-13). Суммы в копейках со знаком. */
object BalanceReconciler {
    /** Разница до 1 ₽ включительно — не расхождение. */
    const val THRESHOLD_KOPECKS = 100L

    sealed interface Result {
        val expectedKopecks: Long

        data class Matched(override val expectedKopecks: Long) : Result

        data class Mismatched(override val expectedKopecks: Long, val diffKopecks: Long) : Result
    }

    fun evaluate(anchorKopecks: Long, netChangeKopecks: Long, bankKopecks: Long): Result {
        val expected = anchorKopecks + netChangeKopecks
        val diff = bankKopecks - expected
        return if (kotlin.math.abs(diff) > THRESHOLD_KOPECKS) Result.Mismatched(expected, diff) else Result.Matched(expected)
    }

    /**
     * Учитывать ли в счёте операции банка без цифр карты (design.md, решение 1): только если у банка ровно одна
     * карта с цифрами и это карта счёта. Счёт без цифр и так включает операции без цифр, ручной — не включает.
     */
    fun includeNoDigits(account: ReconAccount, cardsOfBank: Set<String>): Boolean {
        val card = account.cardLast4 ?: return false
        return cardsOfBank == setOf(card)
    }
}
