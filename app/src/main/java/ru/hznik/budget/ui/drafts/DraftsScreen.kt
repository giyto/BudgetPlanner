package ru.hznik.budget.ui.drafts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import ru.hznik.budget.R
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
import ru.hznik.budget.ui.components.NavigationHeader
import ru.hznik.budget.ui.format.MoneyFormat
import ru.hznik.budget.ui.format.SpokenMoney
import ru.hznik.budget.ui.format.ownTransferRoute
import ru.hznik.budget.ui.operations.dateChipLabel
import ru.hznik.budget.ui.theme.BudgetTheme
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

const val DRAFTS_LIST_TAG = "drafts_list"

/** Тег галочки «Запомнить» у черновика — для UI-тестов. */
fun rememberTag(operationId: Long) = "drafts_remember_$operationId"

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
    fun markOwn(operationId: Long, remember: Boolean) = viewModelScope.launch { markOwnTransfer(operationId, remember) }

    /** «Это не перевод себе» у склеенной пары: снова две операции. */
    fun split(operationId: Long) = viewModelScope.launch {
        chosen.update { it - operationId }
        splitOwnTransfer(operationId)
    }

    /** «Сохранить N операций»: только черновики, где категорию выбрал пользователь (spec drafts). */
    fun save() {
        val state = uiState.value
        val picks = state.drafts.mapNotNull { item -> state.chosen[item.operation.id]?.let { item to it } }.toMap()
        if (picks.isEmpty()) return
        val ids = picks.keys.map { it.operation.id }.toSet()
        viewModelScope.launch {
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

@Composable
fun DraftsRoute(onBack: () -> Unit, onEmpty: () -> Unit, viewModel: DraftsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Все черновики подтверждены — экран закрывается (spec drafts, «Нет черновиков»). Не через onBack:
    // тот отбрасывает нажатие, пока идёт анимация перехода, и экран остался бы пустым.
    LaunchedEffect(state.loaded, state.drafts.isEmpty()) {
        if (state.loaded && state.drafts.isEmpty()) onEmpty()
    }
    // «Это перевод себе» у операции с именем: сначала спросить, это ли пользователь (макет «Перевод себе»).
    // id, а не сама операция: переживает поворот экрана.
    var askNameId by rememberSaveable { mutableStateOf<Long?>(null) }
    val askName = askNameId?.let { id -> state.drafts.firstOrNull { it.operation.id == id } }
    DraftsScreen(
        state = state,
        onBack = onBack,
        onChoose = viewModel::choose,
        onSave = viewModel::save,
        onToggleRemember = viewModel::toggleRemember,
        onMarkOwn = { item -> if (item.operation.merchant != null) askNameId = item.operation.id else viewModel.markOwn(item.operation.id, remember = false) },
        onSplit = viewModel::split,
        onSuggest = viewModel::suggest,
    )
    askName?.let { item ->
        OwnNameDialog(
            name = item.operation.merchant.orEmpty(),
            onRemember = { askNameId = null; viewModel.markOwn(item.operation.id, remember = true) },
            onOnce = { askNameId = null; viewModel.markOwn(item.operation.id, remember = false) },
            onDismiss = { askNameId = null },
        )
    }
}

@Composable
private fun OwnNameDialog(name: String, onRemember: () -> Unit, onOnce: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.own_name_title, name)) },
        text = { Text(stringResource(R.string.own_name_text, name)) },
        confirmButton = { TextButton(onClick = onRemember) { Text(stringResource(R.string.own_name_remember)) } },
        dismissButton = { TextButton(onClick = onOnce) { Text(stringResource(R.string.own_name_once)) } },
    )
}

/** Экран «Черновики» по макету Drafts. */
@Composable
fun DraftsScreen(
    state: DraftsUiState,
    onBack: () -> Unit,
    onChoose: (Long, Long) -> Unit,
    onSave: () -> Unit,
    onMarkOwn: (OperationItem) -> Unit = {},
    onSplit: (Long) -> Unit = {},
    onToggleRemember: (OperationItem) -> Unit = {},
    onSuggest: () -> Unit = {},
) {
    val colors = BudgetTheme.colors
    val count = state.drafts.size
    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        NavigationHeader(stringResource(R.string.drafts_title), R.drawable.ic_back, stringResource(R.string.action_back), onBack)
        Text(
            stringResource(R.string.drafts_subtitle, count, SpokenMoney.plural(count.toLong(), "операция", "операции", "операций")),
            fontSize = 14.sp,
            color = colors.muted,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
        )
        if (state.aiAvailable && (state.aiCandidates.isNotEmpty() || state.aiLoading)) {
            AiSuggestButton(loading = state.aiLoading, error = state.aiError, onClick = onSuggest)
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            modifier = Modifier.weight(1f).testTag(DRAFTS_LIST_TAG),
        ) {
            items(state.drafts, key = { it.operation.id }) { item ->
                if (item.operation.type == OperationType.OWN_TRANSFER) {
                    OwnTransferCard(
                        item,
                        state.today,
                        confirmed = item.operation.id in state.chosen,
                        onConfirm = { onChoose(item.operation.id, item.operation.categoryId) },
                        onSplit = { onSplit(item.operation.id) },
                    )
                } else {
                    val id = item.operation.id
                    DraftCard(
                        item, state.today, state.categoriesFor(item), state.selectedFor(item),
                        userChose = id in state.chosen,
                        onMarkOwn = { onMarkOwn(item) },
                        knownStore = id in state.knownStores,
                        aiHint = id in state.aiHints,
                        rememberable = id in state.rememberable,
                        remember = id in state.remember,
                        onToggleRemember = { onToggleRemember(item) },
                    ) { onChoose(id, it) }
                }
            }
        }
        val toSave = state.chosen.size
        Box(modifier = Modifier.fillMaxWidth().background(colors.background).padding(16.dp).navigationBarsPadding()) {
            Button(
                onClick = onSave,
                enabled = toSave > 0,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(
                    stringResource(R.string.drafts_save, toSave, SpokenMoney.plural(toSave.toLong(), "операцию", "операции", "операций")),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

const val DRAFTS_AI_SUGGEST_TAG = "drafts_ai_suggest"

/** «Подсказать категории» и что уходит в модель; при ошибке — тот же текст, что в чате помощника. */
@Composable
private fun AiSuggestButton(loading: Boolean, error: LlmError?, onClick: () -> Unit) {
    val colors = BudgetTheme.colors
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        OutlinedButton(
            onClick = onClick,
            enabled = !loading,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, colors.chipBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primaryText),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(DRAFTS_AI_SUGGEST_TAG),
        ) {
            if (loading) {
                CircularProgressIndicator(strokeWidth = 2.dp, color = colors.primaryText, modifier = Modifier.size(18.dp))
            } else {
                Icon(painterResource(R.drawable.ic_nav_assistant), contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.drafts_ai_suggest), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        val message = when (error) {
            null -> null
            LlmError.NO_NETWORK -> R.string.assistant_error_network
            LlmError.RATE_LIMITED -> R.string.assistant_error_rate
            LlmError.TOKENS_EXHAUSTED -> R.string.assistant_error_tokens
            else -> R.string.assistant_error_unavailable
        }
        Text(
            stringResource(message ?: R.string.drafts_ai_note),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = if (message != null) colors.warn else colors.muted,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
        )
    }
}

@Composable
private fun DraftCard(
    item: OperationItem,
    today: LocalDate,
    categories: List<Category>,
    selectedId: Long,
    userChose: Boolean,
    onMarkOwn: () -> Unit,
    knownStore: Boolean = false,
    aiHint: Boolean = false,
    rememberable: Boolean = false,
    remember: Boolean = false,
    onToggleRemember: () -> Unit = {},
    onChoose: (Long) -> Unit,
) {
    val colors = BudgetTheme.colors
    val op = item.operation
    val kind = stringResource(
        when (op.type) {
            OperationType.EXPENSE -> R.string.drafts_kind_purchase
            OperationType.TRANSFER -> R.string.drafts_kind_transfer
            OperationType.INCOME -> R.string.drafts_kind_income
            OperationType.REFUND -> R.string.drafts_kind_refund
            OperationType.OWN_TRANSFER -> R.string.drafts_kind_own
        },
    )
    val title = when (op.type) {
        OperationType.TRANSFER -> stringResource(R.string.drafts_transfer_title, op.merchant ?: item.category.name)
        else -> op.merchant ?: item.category.name
    }
    val day = dateChipLabel(op.localDate, today, stringResource(R.string.editor_today), stringResource(R.string.editor_yesterday))
    Column(modifier = Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(22.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).background(colors.card2, RoundedCornerShape(12.dp))) {
                Icon(
                    painterResource(if (op.type == OperationType.TRANSFER) R.drawable.ic_cat_transfer else R.drawable.ic_cat_cart),
                    contentDescription = null,
                    tint = colors.muted,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("$day, ${TIME.format(op.occurredAt)} · $kind", fontSize = 13.sp, color = colors.muted)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                MoneyFormat.operation(op.amount, op.type),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (op.type.isOutgoing) colors.ink else colors.positive,
            )
        }
        if (op.type == OperationType.TRANSFER && !userChose) {
            Text(
                stringResource(R.string.drafts_by_type),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.hint,
                modifier = Modifier.padding(top = 10.dp).background(colors.hintSoft, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        // Категория из словаря популярных магазинов (макет DraftsKnownStore).
        if (knownStore && !userChose) {
            Text(
                stringResource(R.string.drafts_known_store),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.positive,
                modifier = Modifier.padding(top = 10.dp).background(colors.positiveSoft, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        // Подсказка помощника (макет DraftsAiSuggest): сохраняется только по нажатию на выбранную категорию.
        if (aiHint && !userChose) {
            Text(
                stringResource(R.string.drafts_ai_hint),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.primaryText,
                modifier = Modifier.padding(top = 10.dp).background(colors.primarySoft, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
            items(categories, key = { it.id }) { category ->
                val selected = category.id == selectedId
                Surface(
                    onClick = { onChoose(category.id) },
                    shape = RoundedCornerShape(16.dp),
                    color = if (selected) colors.primary else colors.card,
                    contentColor = if (selected) colors.onPrimary else colors.ink,
                    border = if (selected) null else BorderStroke(1.dp, colors.chipBorder),
                    modifier = Modifier.heightIn(min = 44.dp).semantics {
                        role = Role.RadioButton
                        this.selected = selected
                    },
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(category.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
        // «Запомнить для «…»»: правило «магазин → категория», по умолчанию выключено (spec drafts).
        if (rememberable) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(value = remember, role = Role.Checkbox, onValueChange = { onToggleRemember() })
                    .testTag(rememberTag(op.id)),
            ) {
                Checkbox(
                    checked = remember,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = colors.primary, uncheckedColor = colors.chipBorder, checkmarkColor = colors.onPrimary),
                )
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.drafts_remember, op.merchant.orEmpty().trim()), fontSize = 15.sp, color = colors.ink)
            }
        }
        // Переводы людям и от людей могут быть переводами себе в другой банк (макет «Перевод себе»).
        if (op.type == OperationType.TRANSFER || op.type == OperationType.INCOME) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .border(1.dp, colors.chipBorder, RoundedCornerShape(12.dp))
                    .clickable(onClick = onMarkOwn)
                    .padding(horizontal = 12.dp),
            ) {
                Icon(painterResource(R.drawable.ic_cat_transfer), contentDescription = null, tint = colors.primaryText, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.drafts_mark_own), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.primaryText, modifier = Modifier.weight(1f))
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Склеенная пара «ушло — пришло» (макет «Черновики: пара найдена сама»). */
@Composable
private fun OwnTransferCard(item: OperationItem, today: LocalDate, confirmed: Boolean, onConfirm: () -> Unit, onSplit: () -> Unit) {
    val colors = BudgetTheme.colors
    val op = item.operation
    val day = dateChipLabel(op.localDate, today, stringResource(R.string.editor_today), stringResource(R.string.editor_yesterday))
    val route = ownTransferRoute(op, stringResource(R.string.own_account))
    Column(modifier = Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(22.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).background(colors.positiveSoft, RoundedCornerShape(12.dp))) {
                Icon(painterResource(R.drawable.ic_cat_transfer), contentDescription = null, tint = colors.positive, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.own_transfer_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                Text("$route · $day, ${TIME.format(op.occurredAt)}", fontSize = 13.sp, color = colors.muted)
            }
            Spacer(Modifier.width(8.dp))
            Text(MoneyFormat.operation(op.amount, op.type), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.muted)
        }
        Text(
            stringResource(R.string.own_transfer_pair_found),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = colors.positive,
            modifier = Modifier.padding(top = 10.dp).background(colors.positiveSoft, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
        Text(stringResource(R.string.own_transfer_pair_text), fontSize = 13.sp, lineHeight = 18.sp, color = colors.sub, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
            Surface(
                onClick = onConfirm,
                shape = RoundedCornerShape(16.dp),
                color = if (confirmed) colors.primary else colors.card,
                contentColor = if (confirmed) colors.onPrimary else colors.ink,
                border = if (confirmed) null else BorderStroke(1.dp, colors.chipBorder),
                modifier = Modifier.weight(1f).heightIn(min = 44.dp).semantics { role = Role.RadioButton; this.selected = confirmed },
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(10.dp)) { Text(stringResource(R.string.own_transfer_confirm), fontSize = 15.sp, fontWeight = FontWeight.Medium) }
            }
            Surface(
                onClick = onSplit,
                shape = RoundedCornerShape(16.dp),
                color = colors.card,
                contentColor = colors.ink,
                border = BorderStroke(1.dp, colors.chipBorder),
                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(10.dp)) { Text(stringResource(R.string.own_transfer_split), fontSize = 15.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}
