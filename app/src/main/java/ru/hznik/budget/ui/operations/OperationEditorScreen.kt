package ru.hznik.budget.ui.operations

import ru.hznik.budget.ui.components.AmountInput
import ru.hznik.budget.ui.components.AmountKey
import ru.hznik.budget.ui.components.AmountKeypad
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import ru.hznik.budget.R
import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.usecase.InputType
import ru.hznik.budget.domain.usecase.SaveOperation
import ru.hznik.budget.ui.components.CategoryTile
import ru.hznik.budget.ui.format.SpokenMoney
import ru.hznik.budget.ui.theme.BudgetTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun OperationEditorRoute(onClose: () -> Unit, viewModel: OperationEditorViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Событие забирается из канала только на активном экране: во время анимации входа или в фоне
    // onClose (dropUnlessResumed) его бы проигнорировал, и экран остался бы открытым.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.events.collect { onClose() }
        }
    }
    OperationEditorScreen(
        state = state,
        onClose = onClose,
        onKey = viewModel::onKey,
        onTypeSelected = viewModel::onTypeSelected,
        onCategorySelected = viewModel::onCategorySelected,
        onDateSelected = viewModel::onDateSelected,
        onCommentChanged = viewModel::onCommentChanged,
        onSave = viewModel::save,
        onDelete = viewModel::delete,
    )
}

/** Ввод и редактирование операции по макету Add.dc.html. */
@Composable
fun OperationEditorScreen(
    state: EditorUiState,
    onClose: () -> Unit,
    onKey: (AmountKey) -> Unit,
    onTypeSelected: (InputType) -> Unit,
    onCategorySelected: (Long) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    onCommentChanged: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BudgetTheme.colors
    var showAllCategories by rememberSaveable { mutableStateOf(false) }
    var showComment by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(if (state.isEdit) R.string.editor_edit_title else R.string.editor_new_title)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .semantics { paneTitle = title },
    ) {
        EditorHeader(state.isEdit, state.inputType, onClose, onTypeSelected, onDelete)
        AmountDisplay(state.amountInput, state.inputType)

        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Высота строки постоянна: без кнопки «Ещё» (у доходов всего 5 категорий) сетка не должна прыгать.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(
                    text = stringResource(R.string.editor_categories_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    modifier = Modifier.weight(1f),
                )
                if (state.allCategories.size > state.topCategories.size) {
                    TextButton(onClick = { showAllCategories = true }) {
                        Text(stringResource(R.string.action_more), color = colors.primaryText, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            CategoryGrid(state.topCategories, state.selectedCategoryId, onCategorySelected)
        }

        Row(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipButton(
                icon = R.drawable.ic_calendar,
                text = dateChipLabel(state.date, state.today, stringResource(R.string.editor_today), stringResource(R.string.editor_yesterday)),
                onClick = { showDatePicker = true },
                modifier = Modifier.weight(1f),
            )
            ChipButton(
                icon = R.drawable.ic_comment,
                text = state.comment.ifBlank { stringResource(R.string.editor_comment) },
                onClick = { showComment = true },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.weight(1f))
        AmountKeypad(onKey, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
        Button(
            onClick = onSave,
            enabled = state.canSave,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp)
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(stringResource(R.string.action_save), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    if (showAllCategories) {
        AllCategoriesSheet(state.allCategories, state.selectedCategoryId, onDismiss = { showAllCategories = false }) {
            onCategorySelected(it)
            showAllCategories = false
        }
    }
    if (showComment) {
        CommentSheet(state.comment, onCommentChanged, onDismiss = { showComment = false })
    }
    if (showDatePicker) {
        OperationDatePicker(state.date, state.today, onDismiss = { showDatePicker = false }) {
            onDateSelected(it)
            showDatePicker = false
        }
    }
}

@Composable
private fun EditorHeader(
    isEdit: Boolean,
    inputType: InputType,
    onClose: () -> Unit,
    onTypeSelected: (InputType) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = BudgetTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
    ) {
        IconButton(onClick = onClose) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_close), tint = colors.ink)
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            TypeSegment(inputType, onTypeSelected)
        }
        if (isEdit) {
            IconButton(onClick = onDelete) {
                Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.editor_delete), tint = colors.ink)
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

@Composable
private fun TypeSegment(selected: InputType, onSelect: (InputType) -> Unit) {
    val colors = BudgetTheme.colors
    val label = stringResource(R.string.editor_type_group)
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .background(colors.segmentTrack, RoundedCornerShape(24.dp))
            .padding(4.dp)
            .semantics { contentDescription = label },
    ) {
        listOf(
            InputType.EXPENSE to R.string.editor_type_expense,
            InputType.INCOME to R.string.editor_type_income,
            InputType.REFUND to R.string.editor_type_refund,
        ).forEach { (type, text) ->
            val isSelected = type == selected
            Surface(
                selected = isSelected,
                onClick = { onSelect(type) },
                shape = RoundedCornerShape(20.dp),
                color = if (isSelected) colors.segmentOn else colors.segmentTrack,
                contentColor = colors.ink,
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
                    Text(stringResource(text), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

@Composable
private fun AmountDisplay(input: String, type: InputType) {
    val colors = BudgetTheme.colors
    val incoming = type != InputType.EXPENSE
    val kopecks = AmountInput.toKopecks(input)
    val spoken = stringResource(R.string.editor_amount) + ", " +
        (if (incoming) "плюс " else "") + SpokenMoney.amount(kopecks)
    Text(
        text = (if (incoming) "+" else "") + AmountInput.display(input),
        style = MaterialTheme.typography.displayMedium,
        color = if (incoming) colors.positive else colors.ink,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp)
            .semantics { contentDescription = spoken },
    )
}

@Composable
private fun CategoryGrid(categories: List<Category>, selectedId: Long?, onSelect: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        categories.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { category ->
                    CategoryChoice(category, category.id == selectedId, { onSelect(category.id) }, Modifier.weight(1f))
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CategoryChoice(category: Category, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetTheme.colors
    Surface(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) colors.primary else colors.card,
        contentColor = if (selected) colors.onPrimary else colors.ink,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.chipBorder),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(
                text = category.name,
                fontSize = 14.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChipButton(icon: Int, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetTheme.colors
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = colors.card,
        contentColor = colors.ink,
        border = BorderStroke(1.dp, colors.chipBorder),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 10.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllCategoriesSheet(
    categories: List<Category>,
    selectedId: Long?,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
) {
    val colors = BudgetTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Text(
            stringResource(R.string.editor_all_categories),
            style = MaterialTheme.typography.titleLarge,
            color = colors.ink,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            items(categories, key = { it.id }) { category ->
                val selected = category.id == selectedId
                Surface(
                    selected = selected,
                    onClick = { onSelect(category.id) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) colors.primarySoft else colors.card,
                    contentColor = colors.ink,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp)) {
                        CategoryTile(category, size = 36.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(category.name, fontSize = 13.sp, lineHeight = 15.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentSheet(comment: String, onChange: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = BudgetTheme.colors
    var text by rememberSaveable { mutableStateOf(comment) }
    ModalBottomSheet(onDismissRequest = { onChange(text); onDismiss() }, containerColor = colors.background) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(SaveOperation.MAX_COMMENT_LENGTH) },
                label = { Text(stringResource(R.string.editor_comment)) },
                placeholder = { Text(stringResource(R.string.editor_comment_hint)) },
                supportingText = { Text("${text.length} / ${SaveOperation.MAX_COMMENT_LENGTH}") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onChange(text); onDismiss() },
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(stringResource(R.string.action_done))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OperationDatePicker(date: LocalDate, today: LocalDate, onDismiss: () -> Unit, onSelect: (LocalDate) -> Unit) {
    // DatePicker работает с полуночью UTC выбранного дня.
    val selectable = remember(today) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = !utcMillisToDate(utcTimeMillis).isAfter(today)
            override fun isSelectableYear(year: Int) = year <= today.year
        }
    }
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = selectable,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { pickerState.selectedDateMillis?.let { onSelect(utcMillisToDate(it)) } ?: onDismiss() }) {
                Text(stringResource(R.string.action_done))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        DatePicker(state = pickerState, title = { Text(stringResource(R.string.editor_pick_date), Modifier.padding(start = 24.dp, top = 16.dp)) })
    }
}

private fun utcMillisToDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
