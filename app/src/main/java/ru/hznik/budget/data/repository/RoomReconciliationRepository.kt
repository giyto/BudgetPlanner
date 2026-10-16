package ru.hznik.budget.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.hznik.budget.data.local.ReconciliationAccountEntity
import ru.hznik.budget.data.local.ReconciliationDao
import ru.hznik.budget.data.local.toEntity
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.reconcile.BankBalance
import ru.hznik.budget.domain.reconcile.Mismatch
import ru.hznik.budget.domain.reconcile.ReconAccount
import ru.hznik.budget.domain.reconcile.ReconState
import ru.hznik.budget.domain.repository.ReconciliationRepository
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

class RoomReconciliationRepository @Inject constructor(
    private val dao: ReconciliationDao,
    private val clock: Clock,
) : ReconciliationRepository {
    override fun observeStates(): Flow<List<ReconState>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun get(account: ReconAccount): ReconState? =
        dao.get(account.bankId.orEmpty(), account.cardLast4.orEmpty())?.toDomain()

    override suspend fun save(state: ReconState) = dao.save(state.toEntity())

    override suspend fun netChange(account: ReconAccount, includeNoDigits: Boolean, from: Instant, to: Instant): Long =
        dao.netChange(account.bankId, account.cardLast4, includeNoDigits, from.toEpochMilli(), to.toEpochMilli())

    override suspend fun knownAccounts(): List<ReconAccount> =
        dao.knownAccounts().map { ReconAccount(it.bankId, it.cardLast4) }.distinct()

    override suspend fun latestBalances(): List<BankBalance> =
        dao.latestBalances().map { BankBalance(ReconAccount(it.bankId, it.cardLast4), it.balanceKopecks, Instant.ofEpochMilli(it.observedAt)) }

    override suspend fun saveWithAdjustment(adjustment: Operation, state: ReconState): Boolean =
        dao.saveWithAdjustment(adjustment.copy(id = 0).toEntity(createdAt = clock.millis()), state.toEntity())
}

private fun ReconciliationAccountEntity.toDomain(): ReconState {
    val pendingBank = pendingBankKopecks
    return ReconState(
        account = ReconAccount(bankId.ifEmpty { null }, cardLast4.ifEmpty { null }),
        anchorKopecks = anchorKopecks,
        anchorAt = Instant.ofEpochMilli(anchorAt),
        pending = if (pendingBank != null && pendingExpectedKopecks != null && pendingAt != null) {
            Mismatch(pendingBank, pendingExpectedKopecks, Instant.ofEpochMilli(pendingAt))
        } else {
            null
        },
        lastReconciledAt = lastReconciledAt?.let(Instant::ofEpochMilli),
    )
}

private fun ReconState.toEntity() = ReconciliationAccountEntity(
    bankId = account.bankId.orEmpty(),
    cardLast4 = account.cardLast4.orEmpty(),
    anchorKopecks = anchorKopecks,
    anchorAt = anchorAt.toEpochMilli(),
    pendingBankKopecks = pending?.bankKopecks,
    pendingExpectedKopecks = pending?.expectedKopecks,
    pendingAt = pending?.at?.toEpochMilli(),
    lastReconciledAt = lastReconciledAt?.toEpochMilli(),
)
