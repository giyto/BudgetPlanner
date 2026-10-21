package ru.hznik.budget.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.hznik.budget.R
import ru.hznik.budget.domain.categorize.CategoryRule
import ru.hznik.budget.domain.categorize.CategoryRuleItem
import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.usecase.ChangeRuleCategory
import ru.hznik.budget.domain.usecase.DeleteRule
import ru.hznik.budget.domain.usecase.ObserveCategoryRules
import ru.hznik.budget.domain.usecase.RestoreRule
import ru.hznik.budget.ui.AppViewModel
import ru.hznik.budget.ui.components.CategoryPickerSheet
import ru.hznik.budget.ui.components.CategoryTile
import ru.hznik.budget.ui.components.NavigationHeader
import ru.hznik.budget.ui.components.SectionHeader
import ru.hznik.budget.ui.format.SpokenMoney
import ru.hznik.budget.ui.settings.SettingsItem
import ru.hznik.budget.ui.theme.BudgetTheme
import ru.hznik.budget.ui.theme.TextRoles
import javax.inject.Inject
import ru.hznik.budget.ui.launchSave

const val RULES_LIST_TAG = "rules_list"

data class RulesUiState(
    val loaded: Boolean = false,
    val rules: List<CategoryRuleItem> = emptyList(),
    val categories: List<Category> = emptyList(),
) {
    val expenses: List<CategoryRuleItem> get() = rules.filter { it.rule.kind == CategoryKind.EXPENSE }
    val incomes: List<CategoryRuleItem> get() = rules.filter { it.rule.kind == CategoryKind.INCOME }

    /** Видимые категории того же вида, что у правила, — для выбора новой. */
    fun choicesFor(rule: CategoryRule): List<Category> = categories.filter { it.kind == rule.kind && !it.hidden }
}

/** Экран «Правила для магазинов» (spec auto-categorization, «Правила в настройках»). */
@HiltViewModel
class CategoryRulesViewModel @Inject constructor(
    observeRules: ObserveCategoryRules,
    categories: CategoryRepository,
    private val changeCategory: ChangeRuleCategory,
    private val deleteRule: DeleteRule,
    private val restoreRule: RestoreRule,
) : ViewModel() {
    val uiState: StateFlow<RulesUiState> = combine(observeRules(), categories.observeAll()) { rules, cats ->
        RulesUiState(loaded = true, rules = rules, categories = cats.sortedBy { it.sortOrder })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RulesUiState())

    fun change(rule: CategoryRule, categoryId: Long) = launchSave { changeCategory(rule, categoryId) }

    private val _lastDeleted = MutableStateFlow<CategoryRule?>(null)

    /** Удалённое правило, которое ещё можно вернуть (5 секунд, как у удаления операции). */
    val lastDeleted: StateFlow<CategoryRule?> = _lastDeleted.asStateFlow()
    private var undoJob: Job? = null

    fun delete(rule: CategoryRule) {
        undoJob?.cancel()
        undoJob = launchSave {
            _lastDeleted.value = deleteRule(rule)
            delay(AppViewModel.UNDO_WINDOW_MS)
            _lastDeleted.value = null
        }
    }

    fun undo() {
        val rule = _lastDeleted.value ?: return
        undoJob?.cancel()
        _lastDeleted.value = null
        // Пока шла отмена, мог появиться такой же магазин: тогда правило не возвращается (RestoreRule).
        launchSave { restoreRule(rule) }
    }
}

/** Число правил для пункта настроек. */
@HiltViewModel
class RulesCountViewModel @Inject constructor(observeRules: ObserveCategoryRules) : ViewModel() {
    val count: StateFlow<Int> = observeRules().map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

@Composable
fun CategoryRulesRoute(onBack: () -> Unit, viewModel: CategoryRulesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = BudgetTheme.colors
    val snackbar = remember { SnackbarHostState() }
    val lastDeleted by viewModel.lastDeleted.collectAsStateWithLifecycle()
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val deletedLabel = stringResource(R.string.rules_deleted)
    val undoLabel = stringResource(R.string.rules_undo)
    val onDelete: (CategoryRule) -> Unit = viewModel::delete
    // Snackbar живёт, пока ViewModel держит удалённое правило: 5 секунд, переживает поворот экрана.
    LaunchedEffect(lastDeleted) {
        snackbar.currentSnackbarData?.dismiss()
        if (lastDeleted != null) {
            val result = snackbar.showSnackbar(deletedLabel, actionLabel = undoLabel, duration = SnackbarDuration.Indefinite)
            if (result == SnackbarResult.ActionPerformed) viewModel.undo()
        }
    }
    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            NavigationHeader(stringResource(R.string.rules_title), R.drawable.ic_back, stringResource(R.string.action_back), onBack)
            if (state.loaded) {
                val n = state.rules.size
                Text(
                    stringResource(R.string.rules_count, n, SpokenMoney.plural(n.toLong(), "правило", "правила", "правил")),
                    fontSize = 14.sp, color = colors.muted, modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.weight(1f).testTag(RULES_LIST_TAG)) {
                if (state.loaded && state.rules.isEmpty()) {
                    item { Text(stringResource(R.string.rules_empty), fontSize = 15.sp, color = colors.sub, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) }
                }
                ruleGroup(R.string.rules_expenses, state.expenses, onOpen = { editingId = it.id }, onDelete = onDelete)
                ruleGroup(R.string.rules_incomes, state.incomes, onOpen = { editingId = it.id }, onDelete = onDelete)
                if (state.rules.isNotEmpty()) {
                    item { Text(stringResource(R.string.rules_note), style = TextRoles.hint, color = colors.muted, modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp)) }
                }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
    }
    val editing = editingId?.let { id -> state.rules.firstOrNull { it.rule.id == id } }
    editing?.let { item ->
        val rule = item.rule
        CategoryPickerSheet(
            categories = state.choicesFor(rule),
            selectedId = rule.categoryId,
            onDismiss = { editingId = null },
            title = stringResource(R.string.rules_sheet_title, rule.merchantDisplay),
            subtitle = stringResource(if (rule.kind == CategoryKind.INCOME) R.string.rules_sheet_income else R.string.rules_sheet_expense),
        ) { categoryId ->
            editingId = null
            viewModel.change(rule, categoryId)
        }
    }
}

private fun LazyListScope.ruleGroup(
    title: Int,
    rules: List<CategoryRuleItem>,
    onOpen: (CategoryRule) -> Unit,
    onDelete: (CategoryRule) -> Unit,
) {
    if (rules.isEmpty()) return
    // Вместе с отступом подписи «N правил» над списком первый заголовок отстоит на 18 dp, как в макете SettingsRules.
    item(key = "header_$title") { SectionHeader(stringResource(title), Modifier.padding(top = 14.dp)) }
    item(key = "group_$title") {
        Column(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(BudgetTheme.colors.card, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 4.dp)) {
            rules.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = BudgetTheme.colors.line)
                RuleRow(item, onOpen = { onOpen(item.rule) }, onDelete = { onDelete(item.rule) })
            }
        }
    }
}

@Composable
private fun RuleRow(item: CategoryRuleItem, onOpen: () -> Unit, onDelete: () -> Unit) {
    val colors = BudgetTheme.colors
    val changeLabel = stringResource(R.string.rules_change)
    val description = "${item.rule.merchantDisplay}: ${item.category.name}"
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 64.dp)
                .clickable(onClickLabel = changeLabel, onClick = onOpen)
                .clearAndSetSemantics {
                    contentDescription = description
                    onClick(label = changeLabel) { onOpen(); true }
                },
        ) {
            CategoryTile(item.category)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(item.rule.merchantDisplay, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.category.name, fontSize = 13.sp, color = colors.muted)
            }
        }
        IconButton(onClick = onDelete) {
            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.rules_delete, item.rule.merchantDisplay), tint = colors.muted)
        }
    }
}

/** Пункт настроек «Правила для магазинов» с числом правил (макет Settings). */
@Composable
fun RulesSettingsItem(onOpen: () -> Unit, viewModel: RulesCountViewModel = hiltViewModel()) {
    val count by viewModel.count.collectAsStateWithLifecycle()
    val hint = if (count == 0) {
        stringResource(R.string.settings_rules_hint_empty)
    } else {
        stringResource(R.string.settings_rules_hint, count, SpokenMoney.plural(count.toLong(), "правило", "правила", "правил"))
    }
    SettingsItem(stringResource(R.string.settings_rules), hint, onOpen)
}
