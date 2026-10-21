package ru.hznik.budget.ui.userdata

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.hznik.budget.R
import ru.hznik.budget.ui.components.BudgetDatePicker
import ru.hznik.budget.ui.components.NavigationHeader
import ru.hznik.budget.ui.components.PrimaryButton
import ru.hznik.budget.ui.components.RollingAmount
import ru.hznik.budget.ui.components.SectionHeader
import ru.hznik.budget.ui.components.formatDayMonth
import ru.hznik.budget.ui.format.SpokenMoney
import ru.hznik.budget.ui.theme.BudgetTheme
import ru.hznik.budget.ui.theme.TextRoles
import java.time.LocalDate

const val EXPORT_COUNT_TAG = "export_count"
const val EXPORT_FROM_TAG = "export_from"
const val EXPORT_TO_TAG = "export_to"

@Composable
fun ExportRoute(onBack: () -> Unit, viewModel: ExportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Место сохранения выбирает пользователь в системном окне; закрытое окно ничего не создаёт.
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.export(uri.toString())
    }
    ExportScreen(state, onBack, viewModel::setFrom, viewModel::setTo, onSave = { save.launch(state.fileName) })
}

/** Экспорт в CSV по макету DataExport.html (ExportCsv). */
@Composable
fun ExportScreen(
    state: ExportUiState,
    onBack: () -> Unit,
    onFrom: (LocalDate) -> Unit,
    onTo: (LocalDate) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BudgetTheme.colors
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        NavigationHeader(stringResource(R.string.export_title), R.drawable.ic_back, stringResource(R.string.action_back), onBack)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionHeader(stringResource(if (state.isCurrentPeriod) R.string.export_dates_current else R.string.export_dates))
            val range = state.range
            Card {
                DateRow(stringResource(R.string.export_from), range?.start, Modifier.testTag(EXPORT_FROM_TAG)) { picking = FROM }
                HorizontalDivider(color = colors.line)
                DateRow(stringResource(R.string.export_to), range?.endInclusive, Modifier.testTag(EXPORT_TO_TAG)) { picking = TO }
            }
            SectionHeader(stringResource(R.string.export_in_file))
            Card {
                CountRow(state.count)
                HorizontalDivider(color = colors.line)
                Text(
                    stringResource(R.string.export_columns),
                    style = TextRoles.hint,
                    color = colors.muted,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            Text(
                stringResource(R.string.export_note),
                style = TextRoles.hint,
                color = colors.muted,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
            )
        }
        PrimaryButton(
            text = stringResource(R.string.export_save),
            onClick = onSave,
            enabled = state.canSave,
            busy = state.exporting,
            busyText = stringResource(R.string.export_saving),
            icon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(22.dp)) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).navigationBarsPadding(),
        )
    }
    val range = state.range
    if (picking != null && range != null) {
        val from = picking == FROM
        BudgetDatePicker(
            initial = if (from) range.start else range.endInclusive,
            title = stringResource(if (from) R.string.export_pick_from else R.string.export_pick_to),
            isAllowed = { true },
            onDismiss = { picking = null },
        ) { date ->
            if (from) onFrom(date) else onTo(date)
            picking = null
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(BudgetTheme.colors.card, RoundedCornerShape(22.dp))
            .padding(horizontal = 18.dp, vertical = 4.dp),
    ) { content() }
}

/** Строка даты «С 1 октября 2026»: нажатие открывает календарь. */
@Composable
private fun DateRow(label: String, date: LocalDate?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = BudgetTheme.colors
    val value = date?.let { "${formatDayMonth(it)} ${it.year}" }.orEmpty()
    val changeLabel = stringResource(R.string.period_change)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "$label $value"
                role = Role.Button
                onClick(label = changeLabel) { onClick(); true }
            }
            .heightIn(min = 64.dp)
            .padding(vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = TextRoles.hint, color = colors.muted)
            Text(value, style = TextRoles.itemTitle, color = colors.ink)
        }
        Icon(painterResource(R.drawable.ic_calendar), contentDescription = null, tint = colors.muted)
    }
}

/** «128 операций»: число прокручивается при смене дат; за даты без операций — «Нет операций за эти даты». */
@Composable
private fun CountRow(count: Int?) {
    val colors = BudgetTheme.colors
    val empty = count == 0
    val word = count?.let { SpokenMoney.plural(it.toLong(), "операция", "операции", "операций") }.orEmpty()
    val spoken = if (empty) stringResource(R.string.export_empty) else count?.let { "$it $word" }.orEmpty()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp)
            .testTag(EXPORT_COUNT_TAG)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .background(if (empty) colors.warnSoft else colors.positiveSoft, RoundedCornerShape(12.dp)),
        ) {
            Icon(
                painterResource(if (empty) R.drawable.ic_info else R.drawable.ic_list_details),
                contentDescription = null,
                tint = if (empty) colors.warn else colors.positive,
                modifier = Modifier.size(22.dp),
            )
        }
        Box(Modifier.size(12.dp))
        when {
            count == null -> Unit
            empty -> Text(stringResource(R.string.export_empty), style = TextRoles.itemTitle, color = colors.ink)
            else -> {
                RollingAmount(count.toString(), count.toLong(), LocalTextStyle.current.merge(TextRoles.itemTitle), colors.ink)
                Text(" $word", style = TextRoles.itemTitle, color = colors.ink)
            }
        }
    }
}

private const val FROM = "from"
private const val TO = "to"
