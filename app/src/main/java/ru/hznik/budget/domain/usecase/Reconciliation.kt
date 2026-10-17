package ru.hznik.budget.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.hznik.budget.domain.budget.BalanceReconciler
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.reconcile.AutoReconcileSettings
import ru.hznik.budget.domain.reconcile.Mismatch
import ru.hznik.budget.domain.reconcile.ReconAccount
import ru.hznik.budget.domain.reconcile.ReconState
import ru.hznik.budget.domain.repository.AccountFilter
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.ReconciliationRepository
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import ru.hznik.budget.domain.model.byDefaultKey
import ru.hznik.budget.domain.model.DefaultCategories

/**
 * Один замок на все сценарии, которые читают и записывают состояние сверки: импорт уведомления, решение пользователя,
 * пересчёт и ручная сверка не должны перезаписать друг друга (иначе решённое расхождение вернулось бы).
 */
@Singleton
class ReconciliationLock @Inject constructor() {
    val mutex = Mutex()
}

/** Ожидаемый остаток и сравнение с банком — общая часть сценариев сверки (design.md add-balance-reconciliation, 3, 5). */
class ExpectedBalance @Inject constructor(private val repo: ReconciliationRepository) {
    suspend fun at(state: ReconState, at: Instant): Long = state.anchorKopecks + netChange(state, at)

    /** Сравнивает остаток банка [bankKopecks] на момент [at] от точки отсчёта [state]; совпадение сдвигает её. */
    suspend fun compare(state: ReconState, bankKopecks: Long, at: Instant): ReconState {
        return when (val result = BalanceReconciler.evaluate(state.anchorKopecks, netChange(state, at), bankKopecks)) {
            is BalanceReconciler.Result.Matched -> state.copy(anchorKopecks = bankKopecks, anchorAt = at, pending = null, lastReconciledAt = at)
            is BalanceReconciler.Result.Mismatched -> state.copy(pending = Mismatch(bankKopecks, result.expectedKopecks, at), lastReconciledAt = at)
        }
    }

    private suspend fun netChange(state: ReconState, at: Instant): Long {
        val account = state.account
        val cards = if (account.isManual) emptySet() else cardsOf(account.bankId)
        return repo.netChange(account, BalanceReconciler.includeNoDigits(account, cards), state.anchorAt, at)
    }

    suspend fun cardsOf(bankId: String?): Set<String> = repo.knownAccounts().filter { it.bankId == bankId }.mapNotNull { it.cardLast4 }.toSet()

    /**
     * Счёт для остатка из уведомления (design.md, решение 1): без цифр карты — единственная карта банка с цифрами,
     * если она одна; `null`, если карт несколько и остаток нельзя отнести ни к одной.
     */
    suspend fun accountFor(account: ReconAccount): ReconAccount? {
        if (account.isManual || account.cardLast4 != null) return account
        val cards = cardsOf(account.bankId)
        return when (cards.size) {
            0 -> account
            1 -> account.copy(cardLast4 = cards.single())
            else -> null
        }
    }
}

/**
 * Остаток из уведомления банка (spec «Точка отсчёта», «Обнаружение расхождения»). Первый остаток счёта — точка
 * отсчёта. [rebase] — уведомление описывает движение денег, которое не записано операцией (перевод между своими
 * счетами внутри банка, design.md, решение 5): остаток становится точкой отсчёта без сравнения, иначе было бы ложное
 * расхождение; открытое расхождение при этом сохраняется со своей разницей до решения пользователя.
 */
class ReconcileBalance @Inject constructor(
    private val repo: ReconciliationRepository,
    private val settings: AutoReconcileSettings,
    private val expected: ExpectedBalance,
    private val lock: ReconciliationLock,
) {
    suspend operator fun invoke(account: ReconAccount, bankKopecks: Long, at: Instant, rebase: Boolean = false) = lock.mutex.withLock {
        val target = expected.accountFor(account) ?: return@withLock
        val state = repo.get(target)
        if (state == null) {
            repo.save(ReconState(target, bankKopecks, at))
            return@withLock
        }
        // Остаток, пришедший позже, но описывающий более ранний момент, не сравнивается и не сдвигает точку отсчёта назад.
        if (!at.isAfter(state.anchorAt)) return@withLock
        if (rebase) {
            val pending = state.pending?.let { Mismatch(bankKopecks, bankKopecks - it.diffKopecks, at) }
            repo.save(state.copy(anchorKopecks = bankKopecks, anchorAt = at, pending = pending))
            return@withLock
        }
        if (!settings.enabled.first()) return@withLock
        repo.save(expected.compare(state, bankKopecks, at))
    }
}

/** Пересчёт расхождения после поиска пропущенной операции (spec «Поиск пропущенной операции»). */
class RecheckMismatch @Inject constructor(
    private val repo: ReconciliationRepository,
    private val expected: ExpectedBalance,
    private val lock: ReconciliationLock,
) {
    suspend operator fun invoke(account: ReconAccount) = lock.mutex.withLock {
        val state = repo.get(account) ?: return@withLock
        val pending = state.pending ?: return@withLock
        repo.save(expected.compare(state, pending.bankKopecks, pending.at))
    }
}

enum class Resolution { ADJUST, SKIP }

/**
 * Решение по расхождению (spec «Корректировка», «Пропуск»): корректировка на сумму разницы или пропуск. Точкой
 * отсчёта становится остаток банка.
 */
class ResolveMismatch @Inject constructor(
    private val repo: ReconciliationRepository,
    private val categories: CategoryRepository,
    private val lock: ReconciliationLock,
    private val clock: Clock,
) {
    suspend operator fun invoke(account: ReconAccount, resolution: Resolution) = lock.mutex.withLock {
        val state = repo.get(account) ?: return@withLock
        val pending = state.pending ?: return@withLock
        val resolved = state.copy(anchorKopecks = pending.bankKopecks, anchorAt = pending.at, pending = null, lastReconciledAt = clock.instant())
        val amount = Money.ofKopecksOrNull(abs(pending.diffKopecks))
        if (resolution == Resolution.SKIP || amount == null) {
            repo.save(resolved)
            return@withLock
        }
        val type = if (pending.diffKopecks < 0) OperationType.EXPENSE else OperationType.INCOME
        val key = if (type == OperationType.EXPENSE) DefaultCategories.OTHER_EXPENSE else DefaultCategories.OTHER_INCOME
        val category = categories.observeAll().first().byDefaultKey(key) ?: error("Нет категории по умолчанию $key")
        val adjustment = Operation(
            type = type,
            amount = amount,
            categoryId = category.id,
            merchant = ADJUSTMENT_TITLE,
            source = OperationSource.ADJUSTMENT,
            occurredAt = OffsetDateTime.now(clock),
            bankId = account.bankId,
            cardLast4 = account.cardLast4,
        )
        repo.saveWithAdjustment(adjustment, resolved)
    }

    companion object {
        const val ADJUSTMENT_TITLE = "Корректировка"
    }
}

/** Итог ручной сверки (spec «Ручная сверка»). */
sealed interface ManualResult {
    /** Первая сверка счёта: введённый остаток стал точкой отсчёта. */
    data object Anchored : ManualResult

    data object Matched : ManualResult

    data class Mismatched(val mismatch: Mismatch) : ManualResult
}

/** Ручная сверка: пользователь вводит реальный остаток; работает и при выключенной автоматической сверке. */
class ManualReconcile @Inject constructor(
    private val repo: ReconciliationRepository,
    private val expected: ExpectedBalance,
    private val lock: ReconciliationLock,
    private val clock: Clock,
) {
    suspend operator fun invoke(account: ReconAccount, bankKopecks: Long): ManualResult = lock.mutex.withLock {
        val now = clock.instant()
        val state = repo.get(account)
        if (state == null) {
            repo.save(ReconState(account, bankKopecks, now, pending = null, lastReconciledAt = now))
            return@withLock ManualResult.Anchored
        }
        val updated = expected.compare(state, bankKopecks, now)
        repo.save(updated)
        updated.pending?.let(ManualResult::Mismatched) ?: ManualResult.Matched
    }
}

/** Счёт и его ожидаемый остаток сейчас — для выбора счёта при ручной сверке. */
data class AccountBalance(val account: ReconAccount, val expectedKopecks: Long?)

/** Счета для ручной сверки: известные карты банков и «Счёт без автоимпорта» последним; банк без цифр — только если у него нет карт. */
class ReconcilableAccounts @Inject constructor(
    private val repo: ReconciliationRepository,
    private val expected: ExpectedBalance,
    private val clock: Clock,
) {
    suspend operator fun invoke(): List<AccountBalance> {
        val now = clock.instant()
        val known = repo.knownAccounts()
        val banksWithCards = known.filter { it.cardLast4 != null }.map { it.bankId }.toSet()
        val accounts = known.filter { it.cardLast4 != null || it.bankId !in banksWithCards }
            .sortedWith(compareBy({ it.bankId }, { it.cardLast4 })) + ReconAccount.MANUAL
        return accounts.map { account -> AccountBalance(account, repo.get(account)?.let { expected.at(it, now) }) }
    }
}

/** Счета с расхождением; пусто, пока автоматическая сверка выключена (spec «Настройка автоматической сверки»). */
class ObserveMismatches @Inject constructor(
    private val repo: ReconciliationRepository,
    private val settings: AutoReconcileSettings,
) {
    operator fun invoke(): Flow<List<ReconState>> =
        combine(repo.observeStates(), settings.enabled) { states, enabled ->
            if (enabled) states.filter { it.pending != null } else emptyList()
        }
}

/** Все счета со сверкой — для экрана сверки по нескольким картам. */
class ObserveReconStates @Inject constructor(private val repo: ReconciliationRepository) {
    operator fun invoke(): Flow<List<ReconState>> = repo.observeStates()
}

/** Время последней сверки — автоматической или ручной — и счёт, к которому она относится. */
class ObserveLastReconciliation @Inject constructor(private val repo: ReconciliationRepository) {
    operator fun invoke(): Flow<ReconState?> = repo.observeStates().map { states -> states.filter { it.lastReconciledAt != null }.maxByOrNull { it.lastReconciledAt!! } }
}

/**
 * Включение и выключение автоматической сверки. При включении точка отсчёта каждого счёта переносится на его
 * последний остаток из уведомлений, расхождения сбрасываются (spec «Сверка снова включена»).
 */
class SetAutoReconcile @Inject constructor(
    private val repo: ReconciliationRepository,
    private val settings: AutoReconcileSettings,
    private val expected: ExpectedBalance,
    private val lock: ReconciliationLock,
) {
    suspend operator fun invoke(enabled: Boolean) = lock.mutex.withLock {
        if (enabled && !settings.enabled.first()) {
            val rebased = mutableSetOf<ReconAccount>()
            repo.latestBalances().sortedBy { it.at }.forEach { balance ->
                val account = expected.accountFor(balance.account) ?: return@forEach
                val state = repo.get(account)
                repo.save(ReconState(account, balance.kopecks, balance.at, pending = null, lastReconciledAt = state?.lastReconciledAt))
                rebased += account
            }
            // Расхождения ручной сверки, сделанной при выключенной автоматической, тоже сбрасываются.
            repo.observeStates().first().filter { it.account !in rebased && it.pending != null }.forEach { repo.save(it.copy(pending = null)) }
        }
        settings.setEnabled(enabled)
    }
}

/**
 * Время новой операции, добавленной к расхождению счёта (design.md, решение 8): пропущенная операция совершена до
 * остатка банка, поэтому время позже остатка сдвигается на минуту раньше него — иначе операция не попала бы в
 * пересчёт и была бы учтена повторно в следующей сверке. Если до точки отсчёта меньше минуты, время — момент остатка:
 * операция должна остаться между точкой отсчёта и остатком.
 */
class PlaceOperationForAccount @Inject constructor(private val repo: ReconciliationRepository) {
    suspend operator fun invoke(account: ReconAccount, occurredAt: OffsetDateTime): OffsetDateTime {
        val state = repo.get(account) ?: return occurredAt
        val pendingAt = state.pending?.at ?: return occurredAt
        if (!occurredAt.toInstant().isAfter(pendingAt)) return occurredAt
        val minuteBefore = pendingAt.minus(1, ChronoUnit.MINUTES)
        val placed = if (minuteBefore.isAfter(state.anchorAt)) minuteBefore else pendingAt
        return OffsetDateTime.ofInstant(placed, occurredAt.offset)
    }
}

/** Фильтр списка операций по счёту сверки с теми же правилами для операций без цифр карты (spec operations «Фильтр по счёту»). */
class AccountOperationsFilter @Inject constructor(private val repo: ReconciliationRepository) {
    suspend operator fun invoke(account: ReconAccount, from: Instant): AccountFilter {
        val cards = if (account.isManual) emptySet() else repo.knownAccounts().filter { it.bankId == account.bankId }.mapNotNull { it.cardLast4 }.toSet()
        return AccountFilter(account, from, BalanceReconciler.includeNoDigits(account, cards))
    }
}
