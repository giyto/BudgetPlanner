package ru.hznik.budget.ui.categories

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import ru.hznik.budget.R
import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.ui.components.CategoryIcons
import ru.hznik.budget.ui.components.CategoryTile
import ru.hznik.budget.ui.components.NavigationHeader
import ru.hznik.budget.ui.theme.BudgetTheme
import ru.hznik.budget.ui.theme.CategoryColorKeys
import ru.hznik.budget.ui.theme.categoryColors

@Composable
fun CategoriesRoute(
    onBack: () -> Unit,
    onAdd: (CategoryKind) -> Unit,
    onEdit: (Category) -> Unit,
    viewModel: CategoriesViewModel = hiltViewModel(),
) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val kind = if (tab == 0) CategoryKind.EXPENSE else CategoryKind.INCOME
    val colors = BudgetTheme.colors

    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        NavigationHeader(stringResource(R.string.categories_title), R.drawable.ic_back, stringResource(R.string.action_back), onBack)
        PrimaryTabRow(selectedTabIndex = tab, containerColor = colors.background, contentColor = colors.ink) {
            listOf(R.string.categories_tab_expense, R.string.categories_tab_income).forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(title), style = MaterialTheme.typography.titleSmall) })
            }
        }
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(categories[kind].orEmpty(), key = { it.id }) { category ->
                CategoryRow(category, onClick = { onEdit(category) }, onToggleHidden = { viewModel.setHidden(category.id, !category.hidden) })
            }
        }
        Button(
            onClick = { onAdd(kind) },
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            modifier = Modifier.padding(16.dp).fillMaxWidth().height(56.dp),
        ) {
            Text(stringResource(R.string.categories_add), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CategoryRow(category: Category, onClick: () -> Unit, onToggleHidden: () -> Unit) {
    val colors = BudgetTheme.colors
    val toggleLabel = stringResource(if (category.hidden) R.string.categories_show else R.string.categories_hide) + " «${category.name}»"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 8.dp),
    ) {
        CategoryTile(category, Modifier.alpha(if (category.hidden) 0.45f else 1f))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(category.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (category.hidden) colors.muted else colors.ink)
            if (category.hidden) Text(stringResource(R.string.categories_hidden), fontSize = 13.sp, color = colors.muted)
        }
        IconButton(onClick = onToggleHidden) {
            Icon(
                painterResource(if (category.hidden) R.drawable.ic_eye else R.drawable.ic_eye_off),
                contentDescription = toggleLabel,
                tint = colors.muted,
            )
        }
    }
}

@Composable
fun CategoryEditorRoute(onClose: () -> Unit, viewModel: CategoryEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Событие забирается из канала только на активном экране: во время анимации входа или в фоне
    // onClose (dropUnlessResumed) его бы проигнорировал, и экран остался бы открытым.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.close.collect { onClose() }
        }
    }
    val colors = BudgetTheme.colors

    Column(modifier = Modifier.fillMaxSize().background(colors.background).imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NavigationHeader(
                title = stringResource(if (state.isEdit) R.string.category_edit_title else R.string.category_new_title),
                navigationIcon = R.drawable.ic_back,
                navigationLabel = stringResource(R.string.action_back),
                onNavigate = onClose,
                modifier = Modifier.weight(1f),
            )
            if (state.isEdit) {
                IconButton(onClick = viewModel::delete, modifier = Modifier.padding(end = 4.dp)) {
                    Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.category_delete), tint = colors.ink)
                }
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryTile(state.icon, state.color, size = 56.dp)
                Spacer(Modifier.width(16.dp))
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::onNameChanged,
                    label = { Text(stringResource(R.string.category_name)) },
                    singleLine = true,
                    isError = state.nameError != null,
                    supportingText = state.nameError?.let { error ->
                        {
                            Text(
                                stringResource(
                                    when (error) {
                                        NameError.EMPTY -> R.string.category_error_empty
                                        NameError.TOO_LONG -> R.string.category_error_long
                                        NameError.DUPLICATE -> R.string.category_error_duplicate
                                    },
                                ),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f),
                )
            }
            SectionTitle(stringResource(R.string.category_icon))
            IconPicker(state.icon, state.color, viewModel::onIconSelected)
            SectionTitle(stringResource(R.string.category_color))
            ColorPicker(state.color, viewModel::onColorSelected)
        }
        Button(
            onClick = viewModel::save,
            enabled = state.loaded,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            modifier = Modifier.padding(16.dp).fillMaxWidth().height(56.dp),
        ) {
            Text(stringResource(R.string.action_save), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    if (state.showMustHide) {
        AlertDialog(
            onDismissRequest = viewModel::dismissMustHide,
            title = { Text(stringResource(R.string.category_must_hide_title)) },
            text = { Text(stringResource(R.string.category_must_hide_text)) },
            confirmButton = { TextButton(onClick = viewModel::hideInstead) { Text(stringResource(R.string.categories_hide)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissMustHide) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = BudgetTheme.colors.muted)
}

@Composable
private fun IconPicker(selected: String, color: String, onSelect: (String) -> Unit) {
    val colors = BudgetTheme.colors
    val tile = colors.categoryColors(color)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CategoryIcons.all.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (key, icon) ->
                    val isSelected = key == selected
                    Surface(
                        selected = isSelected,
                        onClick = { onSelect(key) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) tile.container else colors.card,
                        contentColor = if (isSelected) tile.content else colors.ink,
                        border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) tile.content else colors.chipBorder),
                        modifier = Modifier.weight(1f).height(52.dp).semantics { contentDescription = CategoryIcons.label(key) },
                    ) {
                        Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), contentDescription = null) }
                    }
                }
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ColorPicker(selected: String, onSelect: (String) -> Unit) {
    val colors = BudgetTheme.colors
    val names = mapOf(
        "green" to R.string.category_color_green, "orange" to R.string.category_color_orange,
        "blue" to R.string.category_color_blue, "purple" to R.string.category_color_purple,
        "pink" to R.string.category_color_pink, "teal" to R.string.category_color_teal,
        "amber" to R.string.category_color_amber, "gray" to R.string.category_color_gray,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        CategoryColorKeys.forEach { key ->
            val isSelected = key == selected
            val label = stringResource(names.getValue(key))
            Surface(
                selected = isSelected,
                onClick = { onSelect(key) },
                shape = CircleShape,
                color = colors.categoryColors(key).content,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .border(if (isSelected) 3.dp else 0.dp, if (isSelected) colors.ink else colors.background, CircleShape)
                    .semantics { contentDescription = label },
            ) {
                if (isSelected) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = colors.background, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
