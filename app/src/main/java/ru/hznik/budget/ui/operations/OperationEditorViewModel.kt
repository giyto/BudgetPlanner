package ru.hznik.budget.ui.operations

import ru.hznik.budget.ui.components.AmountInput
import ru.hznik.budget.ui.components.AmountKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.reconcile.ReconAccount
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.OperationRepository
import ru.hznik.budget.domain.time.today
import ru.hznik.budget.domain.usecase.DeleteOperation
import ru.hznik.budget.domain.usecase.InputType
import ru.hznik.budget.domain.usecase.ObserveInputCategories
import ru.hznik.budget.domain.usecase.OperationDraft
import ru.hznik.budget.domain.usecase.SaveOperation
import ru.hznik.budget.domain.usecase.SaveResult
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import javax.inject.Inject

data class EditorUiState(
    val isEdit: Boolean,
    val loaded: Boolean,
    val inputType: InputType,
    val amountInput: String,
    /** Первые 6 категорий для сетки; выбранная из «Ещё» подставляется на место последней. */
    val topCategories: List<Category>,
    val allCategories: List<Category>,
    val selectedCategoryId: Long?,
    val date: LocalDate,
    val today: LocalDate,
    val comment: String,
    /** Счёт сверки новой операции, добавленной при поиске пропущенной; `null` — обычный ввод. */
    val account: ReconAccount? = null,
) {
    val amountKopecks: Long get() = AmountInput.toKopecks(amountInput)
    val canSave: Boolean get() = loaded && amountKopecks > 0 && selectedCategoryId != null
}

sealed interface EditorEvent {
    data object Close : EditorEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OperationEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeInputCategories: ObserveInputCategories,
    categories: CategoryRepository,
    private val operations: OperationRepository,
    private val saveOperation: SaveOperation,
    private val deleteOperation: DeleteOperation,
    private val clock: Clock,
) : ViewModel() {
    // Аргумент типобезопасного маршрута OperationEditor лежит в SavedStateHandle под именем свойства.
    private val operationId: Long? = savedStateHandle.get<Long>(ARG_OPERATION_ID)
    private val account: ReconAccount? = if (savedStateHandle.get<Boolean>(ARG_FOR_ACCOUNT) == true) {
        ReconAccount(savedStateHandle.get<String>(ARG_BANK), savedStateHandle.get<String>(ARG_CARD))
    } else {
        null
    }

    private data class Form(
        val loaded: Boolean,
        val inputType: InputType = InputType.EXPENSE,
        val amountInput: String = "",
        val categoryId: Long? = null,
        val date: LocalDate,
        /** Пользователь выбрал дату сам; иначе новая операция сохраняется сегодняшним днём на момент сохранения. */
        val dateChosen: Boolean = false,
        val comment: String = "",
        /** Время и смещение исходной операции: при редактировании сохраняются. */
        val originalTime: OffsetDateTime? = null,
    )

    private val form = MutableStateFlow(Form(loaded = operationId == null, date = clock.today()))
    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events: Flow<EditorEvent> = _events.receiveAsFlow()
    private var busy = false

    private val inputCategories = form.map { it.inputType.operationType.categoryKind }
        .distinctUntilChanged()
        .flatMapLatest { observeInputCategories(it) }

    val uiState: StateFlow<EditorUiState> = combine(form, inputCategories, categories.observeAll()) { f, input, all ->
        val top = input.take(TOP_COUNT).let { visible ->
            val selected = all.firstOrNull { it.id == f.categoryId }
            if (selected == null || visible.any { it.id == selected.id }) visible else visible.take(TOP_COUNT - 1) + selected
        }
        f.toUiState(top, input)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), form.value.toUiState(emptyList(), emptyList()))

    init {
        if (operationId != null) {
            viewModelScope.launch {
                val operation = operations.get(operationId)
                if (operation == null) {
                    _events.send(EditorEvent.Close)
                    return@launch
                }
                form.value = Form(
                    loaded = true,
                    inputType = InputType.of(operation.type),
                    amountInput = AmountInput.fromKopecks(operation.amount.kopecks),
                    categoryId = operation.categoryId,
                    date = operation.localDate,
                    comment = operation.comment.orEmpty(),
                    originalTime = operation.occurredAt,
                )
            }
        }
    }

    fun onKey(key: AmountKey) = form.update { it.copy(amountInput = AmountInput.press(it.amountInput, key)) }

    fun onTypeSelected(type: InputType) = form.update {
        // Доход и траты используют разные категории, выбранная сбрасывается при смене вида.
        val sameKind = it.inputType.operationType.categoryKind == type.operationType.categoryKind
        it.copy(inputType = type, categoryId = if (sameKind) it.categoryId else null)
    }

    fun onCategorySelected(id: Long) = form.update { it.copy(categoryId = id) }

    fun onDateSelected(date: LocalDate) = form.update { if (date.isAfter(clock.today())) it else it.copy(date = date, dateChosen = true) }

    fun onCommentChanged(text: String) = form.update { it.copy(comment = text.take(SaveOperation.MAX_COMMENT_LENGTH)) }

    fun save() {
        val f = form.value
        if (busy || !uiState.value.canSave) return
        busy = true
        viewModelScope.launch {
            val now = OffsetDateTime.now(clock)
            val date = if (f.dateChosen || f.originalTime != null) f.date else clock.today()
            val candidate = f.originalTime?.let { OffsetDateTime.of(date, it.toLocalTime(), it.offset) }
                ?: date.atTime(LocalTime.now(clock)).atZone(clock.zone).toOffsetDateTime()
            // Перенос старой операции на сегодня не должен давать момент позже текущего.
            val occurredAt = if (candidate.isAfter(now)) now else candidate
            val result = saveOperation(
                OperationDraft(
                    id = operationId,
                    inputType = f.inputType,
                    amount = Money.ofKopecksOrNull(AmountInput.toKopecks(f.amountInput)),
                    categoryId = f.categoryId,
                    comment = f.comment,
                    occurredAt = occurredAt,
                    account = account,
                ),
            )
            if (result is SaveResult.Saved) _events.send(EditorEvent.Close)
            busy = false
        }
    }

    fun delete() {
        val id = operationId ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            deleteOperation(id)
            _events.send(EditorEvent.Close)
        }
    }

    private fun Form.toUiState(top: List<Category>, all: List<Category>) = EditorUiState(
        isEdit = operationId != null,
        loaded = loaded,
        inputType = inputType,
        amountInput = amountInput,
        topCategories = top,
        allCategories = all,
        selectedCategoryId = categoryId,
        date = date,
        today = clock.today(),
        comment = comment,
        account = account,
    )

    companion object {
        const val ARG_OPERATION_ID = "operationId"
        const val ARG_FOR_ACCOUNT = "forAccount"
        const val ARG_BANK = "bankId"
        const val ARG_CARD = "cardLast4"
        const val TOP_COUNT = 6
    }
}
