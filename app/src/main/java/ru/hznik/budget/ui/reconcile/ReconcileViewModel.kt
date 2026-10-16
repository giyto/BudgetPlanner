package ru.hznik.budget.ui.reconcile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.hznik.budget.domain.reconcile.ReconAccount
import ru.hznik.budget.domain.reconcile.ReconState
import ru.hznik.budget.domain.usecase.AccountBalance
import ru.hznik.budget.domain.usecase.ManualReconcile
import ru.hznik.budget.domain.usecase.ManualResult
import ru.hznik.budget.domain.usecase.ObserveReconStates
import ru.hznik.budget.domain.usecase.RecheckMismatch
import ru.hznik.budget.domain.usecase.ReconcilableAccounts
import ru.hznik.budget.domain.usecase.Resolution
import ru.hznik.budget.domain.usecase.ResolveMismatch
import ru.hznik.budget.ui.components.AmountInput
import ru.hznik.budget.ui.components.AmountKey
import ru.hznik.budget.domain.time.today
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** Что делать с расхождением на виде одной карты (макет Reconcile). */
enum class ReconcileOption { ADJUST, FIND, SKIP }

/** Ввод остатка при ручной сверке (макет ReconcileManual). */
data class ManualState(
    val accounts: List<AccountBalance> = emptyList(),
    val selected: ReconAccount? = null,
    val input: String = "",
) {
    val inputKopecks: Long get() = AmountInput.toKopecks(input)
    val expectedKopecks: Long? get() = accounts.firstOrNull { it.account == selected }?.expectedKopecks
}

data class ReconcileUiState(
    val loaded: Boolean = false,
    /** Счета для вида нескольких карт: карты банков и счёт без автоимпорта, если у него расхождение. */
    val rows: List<ReconState> = emptyList(),
    /** Счёт, к которому относятся действия; `null` — расхождений нет. */
    val selected: ReconState? = null,
    val option: ReconcileOption = ReconcileOption.ADJUST,
    /** Не `null` — режим ручного ввода остатка. */
    val manual: ManualState? = null,
    val zone: ZoneId,
    val today: LocalDate,
) {
    val multiCard: Boolean get() = rows.size > 1
}

/** Экран «Сверка с банком» (spec balance-reconciliation, «Экран сверки», «Ручная сверка»). */
@HiltViewModel
class ReconcileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeStates: ObserveReconStates,
    private val resolveMismatch: ResolveMismatch,
    private val recheckMismatch: RecheckMismatch,
    private val manualReconcile: ManualReconcile,
    private val reconcilableAccounts: ReconcilableAccounts,
    private val clock: Clock,
) : ViewModel() {
    private val requested: ReconAccount? = savedStateHandle.get<String>(ARG_BANK).let { bank ->
        val card = savedStateHandle.get<String>(ARG_CARD)
        if (bank != null || card != null) ReconAccount(bank, card) else null
    }

    private data class Local(val selected: ReconAccount?, val option: ReconcileOption, val manual: ManualState?)

    private val local = MutableStateFlow(Local(requested, ReconcileOption.ADJUST, null))

    val uiState: StateFlow<ReconcileUiState> = combine(observeStates(), local) { states, l ->
        val rows = states.filter { !it.account.isManual || it.pending != null }.sortedWith(compareBy({ it.account.bankId == null }, { it.account.bankId }, { it.account.cardLast4 }))
        val pending = states.filter { it.pending != null }
        val selected = pending.firstOrNull { it.account == l.selected } ?: pending.firstOrNull()
        ReconcileUiState(loaded = true, rows = rows, selected = selected, option = l.option, manual = l.manual, zone = clock.zone, today = clock.today())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReconcileUiState(zone = clock.zone, today = clock.today()))

    private val _finished = MutableStateFlow(false)

    /** Ручная сверка сошлась или стала первой: экран закрывается. */
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    /** Экран открыт кнопкой «Сверить с банком»: сразу ручной ввод, «Назад» закрывает экран. */
    val openedForManual: Boolean = savedStateHandle.get<Boolean>(ARG_MANUAL) == true

    init {
        if (openedForManual) startManual(requested)
    }

    fun onOption(option: ReconcileOption) = local.update { it.copy(option = option) }

    fun onSelect(account: ReconAccount) = local.update { it.copy(selected = account) }

    private var resolving = false

    /** Повторное нажатие, пока решение записывается, игнорируется. */
    fun resolve(resolution: Resolution) {
        val account = uiState.value.selected?.account ?: return
        if (resolving) return
        resolving = true
        viewModelScope.launch {
            try {
                resolveMismatch(account, resolution)
            } finally {
                resolving = false
            }
        }
    }

    /** Возврат с поиска пропущенной операции: расхождение пересчитывается с новыми операциями. */
    fun onResume() {
        val account = uiState.value.selected?.account ?: local.value.selected ?: return
        if (local.value.manual != null) return
        viewModelScope.launch { recheckMismatch(account) }
    }

    fun startManual(preselected: ReconAccount? = null) {
        viewModelScope.launch {
            val accounts = reconcilableAccounts()
            val selected = preselected?.takeIf { p -> accounts.any { it.account == p } } ?: accounts.firstOrNull()?.account
            local.update { it.copy(manual = ManualState(accounts, selected)) }
        }
    }

    fun cancelManual() = local.update { it.copy(manual = null) }

    fun onManualAccount(account: ReconAccount) = local.update { l -> l.copy(manual = l.manual?.copy(selected = account)) }

    fun onManualKey(key: AmountKey) = local.update { l -> l.copy(manual = l.manual?.let { it.copy(input = AmountInput.press(it.input, key)) }) }

    fun submitManual() {
        val manual = local.value.manual ?: return
        val account = manual.selected ?: return
        viewModelScope.launch {
            when (manualReconcile(account, manual.inputKopecks)) {
                is ManualResult.Mismatched -> local.update { it.copy(selected = account, manual = null, option = ReconcileOption.ADJUST) }
                ManualResult.Anchored, ManualResult.Matched -> _finished.value = true
            }
        }
    }

    companion object {
        const val ARG_BANK = "bankId"
        const val ARG_CARD = "cardLast4"
        const val ARG_MANUAL = "manual"
    }
}
