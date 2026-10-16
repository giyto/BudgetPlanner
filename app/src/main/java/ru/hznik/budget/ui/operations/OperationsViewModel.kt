package ru.hznik.budget.ui.operations

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.reconcile.ReconAccount
import ru.hznik.budget.domain.repository.AccountFilter
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.OperationFilter
import ru.hznik.budget.domain.search.SearchQuery
import ru.hznik.budget.domain.time.today
import ru.hznik.budget.domain.usecase.AccountOperationsFilter
import ru.hznik.budget.domain.usecase.DayGroup
import ru.hznik.budget.domain.usecase.ObserveOperations
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import javax.inject.Inject

data class OperationsUiState(
    val loaded: Boolean = false,
    val query: String = "",
    val source: OperationSource? = null,
    val selectedCategory: Category? = null,
    val filterCategories: List<Category> = emptyList(),
    val groups: List<DayGroup> = emptyList(),
    val today: LocalDate,
    /** Фильтр «счёт с даты» из экрана сверки; `null` — без него. */
    val accountFilter: AccountFilter? = null,
    /** Пояс часов приложения: в нём показывается дата фильтра по счёту. */
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    val hasFilters: Boolean get() = query.isNotBlank() || source != null || selectedCategory != null || accountFilter != null
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class OperationsViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    observeOperations: ObserveOperations,
    categories: CategoryRepository,
    accountOperationsFilter: AccountOperationsFilter,
    private val clock: Clock,
) : ViewModel() {
    // Поиск и фильтры переживают поворот экрана и смерть процесса.
    private val query = savedStateHandle.getStateFlow(KEY_QUERY, "")
    private val source = savedStateHandle.getStateFlow<String?>(KEY_SOURCE, null)
    private val savedCategoryId = savedStateHandle.getStateFlow<Long?>(KEY_CATEGORY, null)
    private val allCategories = categories.observeAll()

    // Если выбранную категорию удалили, фильтр по ней снимается, а не остаётся невидимым.
    private val categoryId = combine(savedCategoryId, allCategories) { id, cats -> id?.takeIf { cats.any { it.id == id } } }
        .distinctUntilChanged()

    // Счёт приходит аргументом экрана AccountOperations; снятый крестиком фильтр не возвращается после поворота.
    private val account = ReconAccount(savedStateHandle.get<String>(ARG_BANK), savedStateHandle.get<String>(ARG_CARD))
    private val accountFilter = savedStateHandle.getStateFlow<Long?>(ARG_FROM, null)
        .mapLatest { from -> from?.let { accountOperationsFilter(account, Instant.ofEpochMilli(it)) } }

    private val groups = combine(
        query.debounce { if (it.isBlank()) 0 else SEARCH_DEBOUNCE_MS },
        source,
        categoryId,
        accountFilter,
    ) { q, s, c, a -> OperationFilter(SearchQuery(q), s?.let(OperationSource::valueOf), c, account = a) }
        .flatMapLatest { observeOperations(it) }

    val uiState: StateFlow<OperationsUiState> =
        combine(combine(query, source, categoryId, ::Triple), allCategories, groups, accountFilter) { (q, s, c), cats, g, a ->
            OperationsUiState(
                loaded = true,
                query = q,
                source = s?.let(OperationSource::valueOf),
                selectedCategory = cats.firstOrNull { it.id == c },
                filterCategories = cats,
                groups = g,
                today = clock.today(),
                accountFilter = a,
                zone = clock.zone,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OperationsUiState(today = clock.today()))

    fun onQueryChanged(text: String) {
        savedStateHandle[KEY_QUERY] = text
    }

    fun onSourceSelected(source: OperationSource?) {
        savedStateHandle[KEY_SOURCE] = source?.name
    }

    fun onCategorySelected(id: Long?) {
        savedStateHandle[KEY_CATEGORY] = id
    }

    fun clearAccountFilter() {
        savedStateHandle[ARG_FROM] = null
    }

    fun resetFilters() {
        onQueryChanged("")
        onSourceSelected(null)
        onCategorySelected(null)
        clearAccountFilter()
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 200L
        private const val KEY_QUERY = "query"
        private const val KEY_SOURCE = "source"
        private const val KEY_CATEGORY = "categoryId"
        const val ARG_BANK = "bankId"
        const val ARG_CARD = "cardLast4"
        const val ARG_FROM = "fromMillis"
    }
}
