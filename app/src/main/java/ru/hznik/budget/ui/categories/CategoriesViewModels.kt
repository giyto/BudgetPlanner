package ru.hznik.budget.ui.categories

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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.usecase.CategoryDeleteResult
import ru.hznik.budget.domain.usecase.CategorySaveResult
import ru.hznik.budget.domain.usecase.DeleteCategory
import ru.hznik.budget.domain.usecase.SaveCategory
import ru.hznik.budget.domain.usecase.SetCategoryHidden
import ru.hznik.budget.ui.theme.CategoryColorKeys
import javax.inject.Inject

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    categories: CategoryRepository,
    private val setHidden: SetCategoryHidden,
) : ViewModel() {
    val categories: StateFlow<Map<CategoryKind, List<Category>>> = categories.observeAll()
        .map { all -> all.groupBy { it.kind } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setHidden(id: Long, hidden: Boolean) {
        viewModelScope.launch { setHidden.invoke(id, hidden) }
    }
}

enum class NameError { EMPTY, TOO_LONG, DUPLICATE }

data class CategoryEditorUiState(
    val loaded: Boolean,
    val isEdit: Boolean,
    val kind: CategoryKind,
    val name: String = "",
    val icon: String = "dots",
    val color: String = CategoryColorKeys.first(),
    val hidden: Boolean = false,
    val nameError: NameError? = null,
    val showMustHide: Boolean = false,
)

@HiltViewModel
class CategoryEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val categories: CategoryRepository,
    private val saveCategory: SaveCategory,
    private val deleteCategory: DeleteCategory,
    private val setHidden: SetCategoryHidden,
) : ViewModel() {
    // Аргументы маршрута CategoryEditor.
    private val categoryId: Long? = savedStateHandle.get<Long>(ARG_CATEGORY_ID)
    private val kind = CategoryKind.valueOf(savedStateHandle.get<String>(ARG_KIND) ?: CategoryKind.EXPENSE.name)

    private val _state = MutableStateFlow(CategoryEditorUiState(loaded = categoryId == null, isEdit = categoryId != null, kind = kind))
    val state: StateFlow<CategoryEditorUiState> = _state.asStateFlow()

    private val _close = Channel<Unit>(Channel.BUFFERED)
    val close: Flow<Unit> = _close.receiveAsFlow()
    private var busy = false

    init {
        if (categoryId != null) {
            viewModelScope.launch {
                val category = categories.get(categoryId) ?: return@launch _close.send(Unit)
                _state.update {
                    it.copy(loaded = true, kind = category.kind, name = category.name, icon = category.icon, color = category.color, hidden = category.hidden)
                }
            }
        }
    }

    fun onNameChanged(name: String) = _state.update { it.copy(name = name, nameError = null) }

    fun onIconSelected(icon: String) = _state.update { it.copy(icon = icon) }

    fun onColorSelected(color: String) = _state.update { it.copy(color = color) }

    fun save() {
        val s = _state.value
        // Проверка названия и вставка — два шага, второе нажатие наткнулось бы на уникальный индекс.
        if (!s.loaded || busy) return
        busy = true
        viewModelScope.launch {
            val result = saveCategory(
                Category(id = categoryId ?: 0, kind = s.kind, name = s.name, icon = s.icon, color = s.color, sortOrder = 0, hidden = s.hidden),
            )
            when (result) {
                is CategorySaveResult.Saved, CategorySaveResult.NotFound -> _close.send(Unit)
                CategorySaveResult.EmptyName -> _state.update { it.copy(nameError = NameError.EMPTY) }
                CategorySaveResult.NameTooLong -> _state.update { it.copy(nameError = NameError.TOO_LONG) }
                CategorySaveResult.DuplicateName -> _state.update { it.copy(nameError = NameError.DUPLICATE) }
            }
            busy = false
        }
    }

    fun delete() {
        val id = categoryId ?: return
        viewModelScope.launch {
            when (deleteCategory(id)) {
                CategoryDeleteResult.DELETED, CategoryDeleteResult.NOT_FOUND -> _close.send(Unit)
                CategoryDeleteResult.MUST_HIDE -> _state.update { it.copy(showMustHide = true) }
            }
        }
    }

    fun dismissMustHide() = _state.update { it.copy(showMustHide = false) }

    fun hideInstead() {
        val id = categoryId ?: return
        viewModelScope.launch {
            setHidden(id, true)
            _close.send(Unit)
        }
    }

    companion object {
        const val ARG_CATEGORY_ID = "categoryId"
        const val ARG_KIND = "kind"
    }
}
