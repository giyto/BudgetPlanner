package ru.hznik.budget.ui.period

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.hznik.budget.domain.budget.PeriodStatus
import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.model.IncomeSchedule
import ru.hznik.budget.domain.model.PlannedPayment
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.PlannedPaymentRepository
import ru.hznik.budget.domain.time.today
import ru.hznik.budget.domain.usecase.DeletePlannedPayment
import ru.hznik.budget.domain.usecase.MarkPaymentPaid
import ru.hznik.budget.domain.usecase.ObservePeriodStatus
import ru.hznik.budget.domain.usecase.PaymentSaveResult
import ru.hznik.budget.domain.usecase.PeriodSetupResult
import ru.hznik.budget.domain.usecase.SavePlannedPayment
import ru.hznik.budget.domain.usecase.StartNewPeriod
import ru.hznik.budget.domain.usecase.SuggestNewPeriod
import ru.hznik.budget.domain.usecase.UpdateCurrentPeriod
import ru.hznik.budget.ui.components.AmountInput
import ru.hznik.budget.ui.components.AmountKey
import ru.hznik.budget.ui.components.PaymentFormState
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import ru.hznik.budget.domain.model.byDefaultKey
import ru.hznik.budget.domain.model.DefaultCategories
import ru.hznik.budget.ui.launchSave

/** Ключ категории по умолчанию для новых платежей. */

data class PeriodUiState(
    val status: PeriodStatus? = null,
    val payments: List<PlannedPayment> = emptyList(),
    val categories: Map<Long, Category> = emptyMap(),
)

@HiltViewModel
class PeriodViewModel @Inject constructor(
    observePeriodStatus: ObservePeriodStatus,
    payments: PlannedPaymentRepository,
    categories: CategoryRepository,
    private val markPaid: MarkPaymentPaid,
    private val updatePeriod: UpdateCurrentPeriod,
) : ViewModel() {
    val uiState: StateFlow<PeriodUiState> = combine(observePeriodStatus(), payments.observeAll(), categories.observeAll()) { status, all, cats ->
        // Оплаченные разовые платежи из прошлого больше не нужны в списке, оплаченные в этом периоде остаются для наглядности.
        val start = status?.period?.startDate
        PeriodUiState(status, all.filter { !it.isPaid || (start != null && it.paidOn != null && !it.paidOn.isBefore(start)) }, cats.associateBy { it.id })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeriodUiState())

    fun markPaid(id: Long) {
        launchSave { markPaid.invoke(id) }
    }

    fun changeStartAmount(kopecks: Long) {
        val period = uiState.value.status?.period ?: return
        launchSave { updatePeriod(kopecks, period.nextIncomeDate) }
    }

    fun changeIncomeDate(date: LocalDate) {
        val period = uiState.value.status?.period ?: return
        launchSave { updatePeriod(period.startAmountKopecks, date) }
    }
}

data class PaymentEditorUiState(
    val loaded: Boolean,
    val isEdit: Boolean,
    val form: PaymentFormState,
    /** Все категории трат, включая скрытые. */
    val allExpense: List<Category> = emptyList(),
) {
    /** Скрытая категория остаётся в списке, если она уже выбрана у платежа. */
    val categories: List<Category> get() = allExpense.filter { !it.hidden || it.id == form.categoryId }

    val defaultCategoryId: Long? get() = categories.byDefaultKey(DefaultCategories.OTHER_EXPENSE)?.id ?: categories.lastOrNull()?.id
}

@HiltViewModel
class PaymentEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val payments: PlannedPaymentRepository,
    categories: CategoryRepository,
    private val save: SavePlannedPayment,
    private val delete: DeletePlannedPayment,
    clock: Clock,
) : ViewModel() {
    private val paymentId: Long? = savedStateHandle.get<Long>(ARG_PAYMENT_ID)
    private val form = MutableStateFlow(PaymentEditorUiState(loaded = paymentId == null, isEdit = paymentId != null, form = PaymentFormState(dueDate = clock.today())))
    private val _close = Channel<Unit>(Channel.BUFFERED)
    val close: Flow<Unit> = _close.receiveAsFlow()
    private var busy = false

    // Без combine и stateIn: поле ввода получает новое значение в том же кадре, иначе при быстром наборе теряются буквы.
    val uiState: StateFlow<PaymentEditorUiState> = form.asStateFlow()

    init {
        viewModelScope.launch {
            categories.observeAll().collect { all -> form.update { it.copy(allExpense = all.filter { c -> c.kind == CategoryKind.EXPENSE }) } }
        }
        if (paymentId != null) {
            viewModelScope.launch {
                val payment = payments.get(paymentId) ?: return@launch _close.send(Unit)
                form.update { it.copy(loaded = true, form = PaymentFormState.from(payment)) }
            }
        }
    }

    fun onChange(state: PaymentFormState) = form.update { it.copy(form = state) }

    fun save() {
        val state = uiState.value
        val draft = state.defaultCategoryId?.let { state.form.toDraft(it) }
        if (draft == null) {
            form.update { it.copy(form = it.form.copy(showErrors = true)) }
            return
        }
        if (busy) return
        busy = true
        launchSave(onFailure = { busy = false }) {
            if (save(paymentId, draft) is PaymentSaveResult.Saved) {
                _close.send(Unit)
            } else {
                form.update { it.copy(form = it.form.copy(showErrors = true)) }
                busy = false
            }
        }
    }

    fun delete() {
        val id = paymentId ?: return
        if (busy) return
        busy = true
        launchSave(onFailure = { busy = false }) {
            delete.invoke(id)
            _close.send(Unit)
        }
    }

    companion object {
        const val ARG_PAYMENT_ID = "paymentId"
    }
}

data class NewPeriodUiState(
    val loaded: Boolean = false,
    val amountInput: String = "",
    val incomeTodayKopecks: Long = 0,
    val nextIncome: LocalDate? = null,
    val schedule: IncomeSchedule? = null,
    val today: LocalDate,
    val busy: Boolean = false,
) {
    val amountKopecks: Long get() = AmountInput.toKopecks(amountInput)
    val budgetKopecks: Long get() = amountKopecks + incomeTodayKopecks
    val canStart: Boolean get() = loaded && !busy && nextIncome?.isAfter(today) == true
}

@HiltViewModel
class NewPeriodViewModel @Inject constructor(
    private val suggest: SuggestNewPeriod,
    private val startNewPeriod: StartNewPeriod,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(NewPeriodUiState(today = clock.today()))
    val uiState: StateFlow<NewPeriodUiState> = _state
    private val _close = Channel<Unit>(Channel.BUFFERED)
    val close: Flow<Unit> = _close.receiveAsFlow()

    init {
        viewModelScope.launch {
            val s = suggest()
            _state.update {
                it.copy(
                    loaded = true,
                    amountInput = AmountInput.fromKopecks(s.amountKopecks).takeIf { s.amountKopecks > 0 }.orEmpty(),
                    incomeTodayKopecks = s.incomeTodayKopecks,
                    nextIncome = s.nextIncome,
                    schedule = s.schedule,
                )
            }
        }
    }

    fun onKey(key: AmountKey) = _state.update { it.copy(amountInput = AmountInput.press(it.amountInput, key)) }

    fun onNextIncome(date: LocalDate) = _state.update { if (date.isAfter(it.today)) it.copy(nextIncome = date) else it }

    fun start() {
        val s = _state.value
        if (!s.canStart) return
        _state.update { it.copy(busy = true) }
        launchSave(onFailure = { _state.update { it.copy(busy = false) } }) {
            if (startNewPeriod(s.amountKopecks, s.nextIncome) == PeriodSetupResult.Done) {
                _close.send(Unit)
            } else {
                // Например, экран простоял за полночь: дата дохода, которая уже не в будущем, сбрасывается для нового выбора.
                val today = clock.today()
                _state.update { it.copy(busy = false, today = today, nextIncome = it.nextIncome?.takeIf { d -> d.isAfter(today) }) }
            }
        }
    }
}
