package ru.hznik.budget.domain.reconcile

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Счёт для сверки (spec balance-reconciliation, «Счета для сверки»): банк и последние 4 цифры карты.
 * `bankId == null` — «Счёт без автоимпорта», операции, введённые вручную без банка.
 */
data class ReconAccount(val bankId: String?, val cardLast4: String?) {
    val isManual: Boolean get() = bankId == null

    companion object {
        val MANUAL = ReconAccount(bankId = null, cardLast4 = null)
    }
}

/**
 * Расхождение, ждущее решения пользователя. Суммы в копейках со знаком: остаток бывает нулевым и отрицательным.
 * [diffKopecks] < 0 — банк показывает меньше расчёта.
 */
data class Mismatch(val bankKopecks: Long, val expectedKopecks: Long, val at: Instant) {
    val diffKopecks: Long get() = bankKopecks - expectedKopecks
}

/** Состояние сверки счёта: точка отсчёта, расхождение и время последней сверки. */
data class ReconState(
    val account: ReconAccount,
    val anchorKopecks: Long,
    val anchorAt: Instant,
    val pending: Mismatch? = null,
    val lastReconciledAt: Instant? = null,
)

/** Последний остаток банка по счёту из уведомлений. */
data class BankBalance(val account: ReconAccount, val kopecks: Long, val at: Instant)

/** Переключатель «Автоматическая сверка» (по умолчанию включён). */
interface AutoReconcileSettings {
    val enabled: Flow<Boolean>

    suspend fun setEnabled(enabled: Boolean)
}
