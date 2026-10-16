package ru.hznik.budget.domain.repository

import kotlinx.coroutines.flow.Flow
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.reconcile.BankBalance
import ru.hznik.budget.domain.reconcile.ReconAccount
import ru.hznik.budget.domain.reconcile.ReconState
import java.time.Instant

/** Состояние сверки по счетам и данные для расчёта ожидаемого остатка (design.md add-balance-reconciliation). */
interface ReconciliationRepository {
    fun observeStates(): Flow<List<ReconState>>

    suspend fun get(account: ReconAccount): ReconState?

    suspend fun save(state: ReconState)

    /**
     * Изменение остатка счёта по операциям строго после [from] и не позже [to], в копейках со знаком: пополнения
     * плюс, списания минус, обе стороны перевода себе, черновики; без корректировок. [includeNoDigits] — учитывать
     * операции банка без цифр карты (design.md, решение 1).
     */
    suspend fun netChange(account: ReconAccount, includeNoDigits: Boolean, from: Instant, to: Instant): Long

    /** Все известные счета банков: из операций, остатков и состояний сверки. Без «Счёта без автоимпорта». */
    suspend fun knownAccounts(): List<ReconAccount>

    /** Последние остатки из уведомлений по каждому счёту. */
    suspend fun latestBalances(): List<BankBalance>

    /**
     * Корректировка и новое состояние счёта одной транзакцией: при сбое не остаётся корректировки без решения.
     * `false` — расхождение уже закрыто, ничего не записано.
     */
    suspend fun saveWithAdjustment(adjustment: Operation, state: ReconState): Boolean
}
