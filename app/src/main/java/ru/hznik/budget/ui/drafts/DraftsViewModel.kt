package ru.hznik.budget.ui.drafts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.hznik.budget.ai.CategorySuggestions
import ru.hznik.budget.ai.DraftForHint
import ru.hznik.budget.ai.SuggestCategories
import ru.hznik.budget.domain.ai.AiSettings
import ru.hznik.budget.domain.ai.LlmError
import ru.hznik.budget.domain.ai.LlmProvider
import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.model.OperationItem
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.OperationRepository
import ru.hznik.budget.domain.time.today
import ru.hznik.budget.domain.usecase.ConfirmDrafts
import ru.hznik.budget.domain.usecase.DraftHint
import ru.hznik.budget.domain.usecase.MarkOwnTransfer
import ru.hznik.budget.domain.usecase.ObserveDrafts
import ru.hznik.budget.domain.usecase.SplitOwnTransfer
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import ru.hznik.budget.ui.launchSave

data class DraftsUiState(
    val loaded: Boolean = false,
    val drafts: List<OperationItem> = emptyList(),
    /** Категории расходов и доходов в порядке частоты выбора. */
    val expenseCategories: List<Category> = emptyList(),
    val incomeCategories: List<Category> = emptyList(),
    /** Категории, которые выбрал сам пользователь: id операции → id категории. */
    val chosen: Map<Long, Long> = emptyMap(),
    val today: LocalDate = LocalDate.MIN,
    /** Черновики с отмеченной галочкой «Запомнить для «…»». */
    val remember: Set<Long> = emptySet(),
    /** Черновики, у которых название пригодно для правила (spec drafts). */
    val rememberable: Set<Long> = emptySet(),
    /** Черновики с подсказкой словаря — подпись «Знакомый магазин». */
    val knownStores: Set<Long> = emptySet(),
    /** Подсказки помощника: id операции → id категории; не сохраняются без нажатия (spec drafts). */
    val aiHints: Map<Long, Long> = emptyMap(),
    /** Помощник включён и названия магазинов не скрыты — есть кнопка «Подсказать категории». */
    val aiAvailable: Boolean = false,
    val aiLoading: Boolean = false,
    val aiError: LlmError? = null,
) {
    /** Черновики, которым нужна подсказка: с названием, без выбора, без подсказки словаря, не перевод себе. */
    val aiCandidates: List<OperationItem>
        get() = drafts.filter {
            val id = it.operation.id
            it.operation.type != OperationType.OWN_TRANSFER && !it.operation.merchant.isNullOrBlank() && id !in chosen && id !in knownStores
        }

    fun categoriesFor(item: OperationItem): List<Category> =
        if (item.operation.type == OperationType.INCOME) incomeCategories else expenseCategories

    fun selectedFor(item: OperationItem): Long = chosen[item.operation.id] ?: aiHints[item.operation.id] ?: item.operation.categoryId
}

/** Подсказки помощника на экране черновиков: живут только в памяти экрана. */
private data class AiHintsState(val byOperation: Map<Long, Long> = emptyMap(), val loading: Boolean = false, val error: LlmError? = null)

@HiltViewModel
class DraftsViewModel @Inject constructor(
    observeDrafts: ObserveDrafts,
    categories: CategoryRepository,
    operations: OperationRepository,
    private val confirmDrafts: ConfirmDrafts,
    private val markOwnTransfer: MarkOwnTransfer,
    private val splitOwnTransfer: SplitOwnTransfer,
    private val suggestCategories: SuggestCategories,
    aiSettings: AiSettings,
    provider: LlmProvider,
    private val clock: Clock,
) : ViewModel() {
    private val chosen = MutableStateFlow<Map<Long, Long>>(emptyMap())
    private val ai = MutableStateFlow(AiHintsState())
    private val aiAvailable = combine(aiSettings.enabled, aiSettings.hideMerchants) { enabled, hide -> provider.isConfigured && enabled && !hide }
    private val remember = MutableStateFlow<Set<Long>>(emptySet())

    /** Черновики, где категорию выбрала галочка «Запомнить», а не пользователь. */
    private val autoChosen = mutableSetOf<Long>()

    private val baseState: Flow<DraftsUiState> = combine(
        observeDrafts(),
        categories.observeAll(),
        operations.observeCategoryUsage(clock.today().minusDays(USAGE_DAYS)),
        chosen,
        remember,
    ) { drafts, all, usage, picks, marked ->
        val visible = all.filter { !it.hidden }.sortedWith(compareByDescending<Category> { usage[it.id] ?: 0 }.thenBy { it.sortOrder })
        DraftsUiState(
            loaded = true,
            drafts = drafts,
            expenseCategories = visible.filter { it.kind == CategoryKind.EXPENSE },
            incomeCategories = visible.filter { it.kind == CategoryKind.INCOME },
            // Выбор, сделанный до того, как черновик склеился в перевод себе, не считается подтверждением пары.
            chosen = picks.filter { (id, categoryId) ->
                val op = drafts.firstOrNull { it.operation.id == id }?.operation ?: return@filter false
                op.type != OperationType.OWN_TRANSFER || categoryId == op.categoryId
            },
            today = clock.today(),
            remember = marked.filter { id -> drafts.any { it.operation.id == id } }.toSet(),
            rememberable = drafts.filter(confirmDrafts::canRemember).map { it.operation.id }.toSet(),
            knownStores = drafts.filter { DraftHint.isKnownStore(it.operation, all) }.map { it.operation.id }.toSet(),
        )
    }

    val uiState: StateFlow<DraftsUiState> = combine(baseState, ai, aiAvailable) { base, hints, available ->
        val ids = base.drafts.map { it.operation.id }.toSet()
        base.copy(
            aiHints = if (available) hints.byOperation.filterKeys { it in ids && it !in base.chosen } else emptyMap(),
            aiAvailable = available,
            aiLoading = hints.loading,
            aiError = hints.error.takeIf { available },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DraftsUiState())

    /** «Подсказать категории»: один запрос к помощнику; подсказки выбираются как предварительные (spec drafts). */
    fun suggest() {
        val state = uiState.value
        if (!state.aiAvailable || state.aiLoading) return
        val candidates = state.aiCandidates.map {
            DraftForHint(it.operation.id, it.operation.merchant.orEmpty(), it.operation.type.categoryKind)
        }
        if (candidates.isEmpty()) return
        ai.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = suggestCategories(candidates)) {
                is CategorySuggestions.Found -> ai.update { AiHintsState(byOperation = it.byOperation + result.byOperation) }
                is CategorySuggestions.Failure -> ai.update { it.copy(loading = false, error = result.error) }
                CategorySuggestions.Unavailable -> ai.update { it.copy(loading = false) }
            }
        }
    }

    fun choose(operationId: Long, categoryId: Long) {
        autoChosen -= operationId
        chosen.update { it + (operationId to categoryId) }
    }

    /**
     * Галочка «Запомнить для «…»». Отметка — тоже выбор показанной категории: без неё сохранять было бы нечего
     * (spec drafts, «Подтверждение подсказки»).
     */
    fun toggleRemember(item: OperationItem) {
        val id = item.operation.id
        if (id in remember.value) {
            remember.update { it - id }
            // Выбор, сделанный самой галочкой, тоже снимается: иначе черновик сохранился бы без выбора пользователя.
            if (autoChosen.remove(id)) chosen.update { it - id }
            return
        }
        remember.update { it + id }
        if (id !in uiState.value.chosen) {
            chosen.update { it + (id to uiState.value.selectedFor(item)) }
            autoChosen += id
        }
    }

    /** «Это перевод себе»: операция сразу уходит из бюджета; [remember] — запомнить имя как своё. */
    fun markOwn(operationId: Long, remember: Boolean) = launchSave { markOwnTransfer(operationId, remember) }

    /** «Это не перевод себе» у склеенной пары: снова две операции. */
    fun split(operationId: Long) = launchSave {
        chosen.update { it - operationId }
        splitOwnTransfer(operationId)
    }

    /** «Сохранить N операций»: только черновики, где категорию выбрал пользователь (spec drafts). */
    fun save() {
        val state = uiState.value
        val picks = state.drafts.mapNotNull { item -> state.chosen[item.operation.id]?.let { item to it } }.toMap()
        if (picks.isEmpty()) return
        val ids = picks.keys.map { it.operation.id }.toSet()
        launchSave {
            confirmDrafts(picks, remember = state.remember intersect ids)
            chosen.update { it - ids }
            remember.update { it - ids }
            autoChosen -= ids
        }
    }

    private companion object {
        const val USAGE_DAYS = 90L
    }
}
