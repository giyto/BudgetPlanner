package ru.hznik.budget.ui.operations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.hznik.budget.R
import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.model.OperationItem
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.usecase.DayGroup
import ru.hznik.budget.ui.components.CategoryTile
import ru.hznik.budget.ui.components.ScreenTitle
import ru.hznik.budget.ui.format.MoneyFormat
import ru.hznik.budget.ui.format.SpokenMoney
import ru.hznik.budget.ui.theme.BudgetTheme
import java.time.format.DateTimeFormatter

const val OPERATIONS_LIST_TAG = "operations_list"

@Composable
fun OperationsRoute(
    onAddOperation: () -> Unit,
    onOpenOperation: (Long) -> Unit,
    viewModel: OperationsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    OperationsScreen(
        state = state,
        onQueryChanged = viewModel::onQueryChanged,
        onSourceSelected = viewModel::onSourceSelected,
        onCategorySelected = viewModel::onCategorySelected,
        onReset = viewModel::resetFilters,
        onAddOperation = onAddOperation,
        onOpenOperation = onOpenOperation,
    )
}

/** Список операций по дням с поиском и фильтрами, по макету Operations.dc.html. */
@Composable
fun OperationsScreen(
    state: OperationsUiState,
    onQueryChanged: (String) -> Unit,
    onSourceSelected: (OperationSource?) -> Unit,
    onCategorySelected: (Long?) -> Unit,
    onReset: () -> Unit,
    onAddOperation: () -> Unit,
    onOpenOperation: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BudgetTheme.colors
    var showCategoryFilter by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    Box(modifier = modifier.fillMaxSize().background(colors.background)) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().testTag(OPERATIONS_LIST_TAG),
        ) {
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ScreenTitle(stringResource(R.string.operations_title))
                    SearchField(state.query, onQueryChanged)
                    Filters(state, onSourceSelected, onCategorySelected, onOpenCategories = { showCategoryFilter = true })
                }
            }
            if (state.loaded && state.groups.isEmpty()) {
                item(key = "empty") { EmptyState(state.hasFilters, onReset) }
            }
            items(state.groups, key = { it.date.toEpochDay() }) { group ->
                DayCard(group, state, onOpenOperation)
            }
        }
        FloatingActionButton(
            onClick = onAddOperation,
            shape = RoundedCornerShape(20.dp),
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 20.dp)
                .size(60.dp),
        ) {
            Icon(painterResource(R.drawable.ic_add), stringResource(R.string.action_add_operation), Modifier.size(26.dp))
        }
    }

    if (showCategoryFilter) {
        CategoryFilterSheet(state.filterCategories, state.selectedCategory?.id, onDismiss = { showCategoryFilter = false }) {
            onCategorySelected(it)
            showCategoryFilter = false
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChanged: (String) -> Unit) {
    val colors = BudgetTheme.colors
    val label = stringResource(R.string.operations_search)
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.operations_search_hint)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null, Modifier.size(20.dp)) },
        trailingIcon = if (query.isNotEmpty()) {
            { IconButton(onClick = { onQueryChanged("") }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_reset)) } }
        } else {
            null
        },
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.card,
            unfocusedContainerColor = colors.card,
            unfocusedBorderColor = colors.card,
            focusedBorderColor = colors.primary,
            focusedTextColor = colors.ink,
            unfocusedTextColor = colors.ink,
            focusedPlaceholderColor = colors.muted,
            unfocusedPlaceholderColor = colors.muted,
            focusedLeadingIconColor = colors.muted,
            unfocusedLeadingIconColor = colors.muted,
        ),
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .semantics { contentDescription = label },
    )
}

@Composable
private fun Filters(
    state: OperationsUiState,
    onSourceSelected: (OperationSource?) -> Unit,
    onCategorySelected: (Long?) -> Unit,
    onOpenCategories: () -> Unit,
) {
    val filterLabel = stringResource(R.string.operations_filter)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = filterLabel },
    ) {
        listOf(
            null to R.string.operations_filter_all,
            OperationSource.NOTIFICATION to R.string.operations_filter_notification,
            OperationSource.MANUAL to R.string.operations_filter_manual,
        ).forEach { (source, label) ->
            BudgetFilterChip(text = stringResource(label), selected = state.source == source, onClick = { onSourceSelected(source) })
        }
        val category = state.selectedCategory
        // Сброс категории — пункт «Все категории» в открывающемся списке: отдельная кнопка на чипе была бы меньше 48 dp.
        BudgetFilterChip(
            text = category?.name ?: stringResource(R.string.operations_filter_category),
            selected = category != null,
            onClick = onOpenCategories,
            trailingIcon = R.drawable.ic_chevron_down,
        )
    }
}

@Composable
private fun BudgetFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailingIcon: Int? = null,
) {
    val colors = BudgetTheme.colors
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium) },
        trailingIcon = trailingIcon?.let { icon ->
            {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
            }
        },
        shape = RoundedCornerShape(18.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.card,
            labelColor = colors.ink,
            iconColor = colors.ink,
            selectedContainerColor = colors.primary,
            selectedLabelColor = colors.onPrimary,
            selectedTrailingIconColor = colors.onPrimary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = colors.chipBorder,
            selectedBorderColor = colors.primary,
        ),
        modifier = Modifier.heightIn(min = 40.dp),
    )
}

@Composable
private fun DayCard(group: DayGroup, state: OperationsUiState, onOpen: (Long) -> Unit) {
    val colors = BudgetTheme.colors
    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(
                dayTitle(group.date, state.today),
                style = MaterialTheme.typography.titleSmall,
                color = colors.sub,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            val spokenTotal = spokenDayTotal(group.netSpentKopecks, group.incomeKopecks)
            Text(
                MoneyFormat.dayTotal(group.netSpentKopecks, group.incomeKopecks),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                modifier = Modifier.semantics { contentDescription = spokenTotal },
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.card, RoundedCornerShape(22.dp))
                .padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
            group.items.forEachIndexed { index, item ->
                OperationRow(item, onOpen)
                if (index < group.items.lastIndex) HorizontalDivider(color = colors.line)
            }
        }
    }
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/** Итог дня для TalkBack: «траты 330 рублей, доходы 2000 рублей». */
private fun spokenDayTotal(netSpentKopecks: Long, incomeKopecks: Long): String = buildList {
    when {
        netSpentKopecks > 0 -> add("траты " + SpokenMoney.amount(netSpentKopecks))
        netSpentKopecks < 0 -> add("возвраты больше трат на " + SpokenMoney.amount(netSpentKopecks))
    }
    if (incomeKopecks > 0) add("доходы " + SpokenMoney.amount(incomeKopecks))
}.joinToString(", ")

@Composable
internal fun OperationRow(item: OperationItem, onOpen: (Long) -> Unit) {
    val colors = BudgetTheme.colors
    val op = item.operation
    val refundLabel = stringResource(R.string.operations_refund)
    val openLabel = stringResource(R.string.operations_open)
    val meta = listOfNotNull(
        refundLabel.takeIf { op.type == OperationType.REFUND },
        item.category.name.takeIf { item.title != item.category.name },
        TIME.format(op.occurredAt),
    ).joinToString(" · ")
    val incoming = !op.type.isOutgoing
    val description = "${item.title}. $meta. ${SpokenMoney.operation(op.amount, op.type)}"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(onClickLabel = openLabel) { onOpen(op.id) }
            .clearAndSetSemantics {
                contentDescription = description
                onClick(label = openLabel) { onOpen(op.id); true }
            }
            .padding(vertical = 10.dp),
    ) {
        CategoryTile(item.category)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, fontSize = 13.sp, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            MoneyFormat.operation(op.amount, op.type),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (incoming) colors.positive else colors.ink,
        )
    }
}

@Composable
private fun EmptyState(hasFilters: Boolean, onReset: () -> Unit) {
    val colors = BudgetTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
    ) {
        Text(
            stringResource(if (hasFilters) R.string.operations_not_found_title else R.string.operations_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = colors.ink,
        )
        Text(
            stringResource(if (hasFilters) R.string.operations_not_found_text else R.string.operations_empty_text),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
        )
        if (hasFilters) {
            TextButton(onClick = onReset) { Text(stringResource(R.string.action_reset), color = colors.primaryText) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryFilterSheet(categories: List<Category>, selectedId: Long?, onDismiss: () -> Unit, onSelect: (Long?) -> Unit) {
    val colors = BudgetTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        LazyColumn(modifier = Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            item {
                SheetRow(text = stringResource(R.string.operations_filter_category_clear), selected = selectedId == null, onClick = { onSelect(null) })
            }
            listOf(CategoryKind.EXPENSE to R.string.categories_tab_expense, CategoryKind.INCOME to R.string.categories_tab_income).forEach { (kind, title) ->
                item {
                    Text(
                        stringResource(title),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.muted,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
                    )
                }
                items(categories.filter { it.kind == kind }, key = { it.id }) { category ->
                    SheetRow(category.name, category.id == selectedId, { onSelect(category.id) }, category)
                }
            }
        }
    }
}

@Composable
private fun SheetRow(text: String, selected: Boolean, onClick: () -> Unit, category: Category? = null) {
    val colors = BudgetTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
    ) {
        if (category != null) {
            CategoryTile(category, size = 32.dp)
            Spacer(Modifier.width(12.dp))
        }
        Text(text, fontSize = 15.sp, color = colors.ink, modifier = Modifier.weight(1f))
        if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = colors.primaryText)
    }
}
