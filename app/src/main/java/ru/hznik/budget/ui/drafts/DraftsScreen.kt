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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.hznik.budget.R
import ru.hznik.budget.domain.ai.LlmError
import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.OperationItem
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.ui.components.animatedColor
import ru.hznik.budget.ui.components.NavigationHeader
import ru.hznik.budget.ui.components.PrimaryButton
import ru.hznik.budget.ui.components.SecondaryButton
import ru.hznik.budget.ui.format.MoneyFormat
import ru.hznik.budget.ui.format.SpokenMoney
import ru.hznik.budget.ui.format.ownTransferRoute
import ru.hznik.budget.ui.operations.dateChipLabel
import ru.hznik.budget.ui.theme.BudgetTheme
import ru.hznik.budget.ui.theme.TextRoles
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import ru.hznik.budget.ui.components.itemMotion

const val DRAFTS_LIST_TAG = "drafts_list"

/** Тег галочки «Запомнить» у черновика — для UI-тестов. */
fun rememberTag(operationId: Long) = "drafts_remember_$operationId"

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
                Box(itemMotion()) {
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
        }
        val toSave = state.chosen.size
        Box(modifier = Modifier.fillMaxWidth().background(colors.background).padding(16.dp).navigationBarsPadding()) {
            PrimaryButton(
                text = stringResource(R.string.drafts_save, toSave, SpokenMoney.plural(toSave.toLong(), "операцию", "операции", "операций")),
                onClick = onSave,
                enabled = toSave > 0,
            )
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
        SecondaryButton(
            text = stringResource(R.string.drafts_ai_suggest),
            onClick = onClick,
            modifier = Modifier.testTag(DRAFTS_AI_SUGGEST_TAG),
            enabled = !loading,
            contentColor = colors.primaryText,
            icon = {
                if (loading) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = colors.primaryText, modifier = Modifier.size(18.dp))
                } else {
                    Icon(painterResource(R.drawable.ic_nav_assistant), contentDescription = null, modifier = Modifier.size(18.dp))
                }
            },
        )
        val message = when (error) {
            null -> null
            LlmError.NO_NETWORK -> R.string.assistant_error_network
            LlmError.RATE_LIMITED -> R.string.assistant_error_rate
            LlmError.TOKENS_EXHAUSTED -> R.string.assistant_error_tokens
            else -> R.string.assistant_error_unavailable
        }
        Text(
            stringResource(message ?: R.string.drafts_ai_note),
            style = TextRoles.hint,
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
    Column(modifier = Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(22.dp)).padding(16.dp)) {
        DraftHeader(item, today)
        if (op.type == OperationType.TRANSFER && !userChose) {
            DraftTag(stringResource(R.string.drafts_by_type), colors.hint, colors.hintSoft)
        }
        // Категория из словаря популярных магазинов (макет DraftsKnownStore).
        if (knownStore && !userChose) {
            DraftTag(stringResource(R.string.drafts_known_store), colors.positive, colors.positiveSoft)
        }
        // Подсказка помощника (макет DraftsAiSuggest): сохраняется только по нажатию на выбранную категорию.
        if (aiHint && !userChose) {
            DraftTag(stringResource(R.string.drafts_ai_hint), colors.primaryText, colors.primarySoft)
        }
        CategoryChips(categories, selectedId, onChoose)
        // «Запомнить для «…»»: правило «магазин → категория», по умолчанию выключено (spec drafts).
        if (rememberable) {
            RememberRow(op.id, op.merchant.orEmpty().trim(), checked = remember, onToggle = onToggleRemember)
        }
        // Переводы людям и от людей могут быть переводами себе в другой банк (макет «Перевод себе»).
        if (op.type == OperationType.TRANSFER || op.type == OperationType.INCOME) {
            MarkOwnRow(onMarkOwn)
        }
    }
}

/** Верх карточки черновика: иконка вида, название, день и время с видом операции, сумма. */
@Composable
private fun DraftHeader(item: OperationItem, today: LocalDate) {
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
            Text(title, style = TextRoles.itemTitle, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
}

/** Метка под верхом карточки (макет .tag): «по типу операции», «Знакомый магазин», подсказка помощника, найденная пара. */
@Composable
private fun DraftTag(text: String, color: Color, background: Color) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = Modifier.padding(top = 10.dp).background(background, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Ряд чипов категорий с прокруткой; выбранный чип закрашивается плавно. */
@Composable
private fun CategoryChips(categories: List<Category>, selectedId: Long, onChoose: (Long) -> Unit) {
    val colors = BudgetTheme.colors
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
        items(categories, key = { it.id }) { category ->
            val selected = category.id == selectedId
            Surface(
                onClick = { onChoose(category.id) },
                shape = RoundedCornerShape(16.dp),
                color = animatedColor(if (selected) colors.primary else colors.card),
                contentColor = animatedColor(if (selected) colors.onPrimary else colors.ink),
                // Рамка цвета чипа у выбранного неотличима от её отсутствия, зато цвет меняется плавно.
                border = BorderStroke(1.dp, animatedColor(if (selected) colors.primary else colors.chipBorder)),
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
}

/** Галочка «Запомнить для «[merchant]»» у черновика с названием магазина. */
@Composable
private fun RememberRow(operationId: Long, merchant: String, checked: Boolean, onToggle: () -> Unit) {
    val colors = BudgetTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .testTag(rememberTag(operationId)),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = colors.primary, uncheckedColor = colors.chipBorder, checkmarkColor = colors.onPrimary),
        )
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.drafts_remember, merchant), fontSize = 15.sp, color = colors.ink)
    }
}

/** Строка «Это перевод себе» под переводом или зачислением. */
@Composable
private fun MarkOwnRow(onClick: () -> Unit) {
    val colors = BudgetTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .border(1.dp, colors.chipBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        Icon(painterResource(R.drawable.ic_cat_transfer), contentDescription = null, tint = colors.primaryText, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.drafts_mark_own), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.primaryText, modifier = Modifier.weight(1f))
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = colors.muted, modifier = Modifier.size(18.dp))
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
                Text(stringResource(R.string.own_transfer_title), style = TextRoles.itemTitle, color = colors.ink)
                Text("$route · $day, ${TIME.format(op.occurredAt)}", fontSize = 13.sp, color = colors.muted)
            }
            Spacer(Modifier.width(8.dp))
            Text(MoneyFormat.operation(op.amount, op.type), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.muted)
        }
        DraftTag(stringResource(R.string.own_transfer_pair_found), colors.positive, colors.positiveSoft)
        Text(stringResource(R.string.own_transfer_pair_text), style = TextRoles.hint, color = colors.sub, modifier = Modifier.padding(top = 8.dp))
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
