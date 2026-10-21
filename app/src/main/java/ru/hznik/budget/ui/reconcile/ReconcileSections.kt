package ru.hznik.budget.ui.reconcile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.hznik.budget.R
import ru.hznik.budget.domain.reconcile.AutoReconcileSettings
import ru.hznik.budget.domain.reconcile.ReconState
import ru.hznik.budget.domain.time.today
import ru.hznik.budget.domain.usecase.ObserveLastReconciliation
import ru.hznik.budget.domain.usecase.SetAutoReconcile
import ru.hznik.budget.ui.components.SecondaryButton
import ru.hznik.budget.ui.components.SettingsSection
import ru.hznik.budget.ui.components.SettingsSwitchRow
import ru.hznik.budget.ui.components.formatDayMonth
import ru.hznik.budget.ui.format.MoneyFormat
import ru.hznik.budget.ui.format.accountLabel
import ru.hznik.budget.ui.theme.BudgetTheme
import ru.hznik.budget.ui.theme.TextRoles
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import ru.hznik.budget.ui.launchSave

const val PERIOD_RECONCILE_BUTTON_TAG = "period_reconcile"
const val AUTO_RECONCILE_SWITCH_TAG = "auto_reconcile_switch"

data class ReconcileSectionState(val last: ReconState? = null, val autoEnabled: Boolean = true, val zone: ZoneId, val today: LocalDate)

/** Время последней сверки для экрана периода и переключатель автоматической сверки для настроек. */
@HiltViewModel
class ReconcileSectionViewModel @Inject constructor(
    observeLast: ObserveLastReconciliation,
    settings: AutoReconcileSettings,
    private val setAutoReconcile: SetAutoReconcile,
    clock: Clock,
) : ViewModel() {
    val state: StateFlow<ReconcileSectionState> = combine(observeLast(), settings.enabled) { last, enabled ->
        ReconcileSectionState(last, enabled, clock.zone, clock.today())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReconcileSectionState(zone = clock.zone, today = clock.today()))

    fun setAuto(enabled: Boolean) {
        launchSave { setAutoReconcile(enabled) }
    }
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/** «Сверка с банком» на экране периода по макету PeriodReconcile (spec budget-period «Сверка на экране периода»). */
@Composable
fun PeriodReconcileSection(onReconcile: () -> Unit, viewModel: ReconcileSectionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = BudgetTheme.colors
    val last = state.last
    val lastAt = last?.lastReconciledAt
    val subtitle = if (last == null || lastAt == null) {
        stringResource(R.string.period_reconcile_never)
    } else {
        val local = lastAt.atZone(state.zone)
        val day = if (local.toLocalDate() == state.today) "сегодня" else formatDayMonth(local.toLocalDate())
        val account = accountLabel(last.account)
        val result = last.pending?.let { stringResource(R.string.period_reconcile_mismatch, account, MoneyFormat.amount(it.diffKopecks)) }
            ?: stringResource(R.string.period_reconcile_matches, account)
        stringResource(R.string.period_reconcile_last_value, "$day, ${TIME.format(local)}", result)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.period_reconcile_header), style = MaterialTheme.typography.titleMedium, color = colors.ink, modifier = Modifier.padding(top = 8.dp))
        Column(modifier = Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(22.dp)).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).background(colors.positiveSoft, RoundedCornerShape(12.dp))) {
                    Icon(painterResource(R.drawable.ic_scale), contentDescription = null, tint = colors.positive, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.period_reconcile_last), style = TextRoles.itemTitle, color = colors.ink)
                    Text(subtitle, fontSize = 13.sp, color = colors.muted)
                }
            }
            SecondaryButton(
                text = stringResource(R.string.period_reconcile_button),
                onClick = onReconcile,
                modifier = Modifier.padding(top = 14.dp).testTag(PERIOD_RECONCILE_BUTTON_TAG),
                icon = { Icon(painterResource(R.drawable.ic_bank), contentDescription = null, modifier = Modifier.size(20.dp)) },
            )
            Text(stringResource(R.string.period_reconcile_hint), style = TextRoles.hint, color = colors.muted, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/** Раздел настроек «Сверка с банком» по макету Settings. */
@Composable
fun ReconcileSettingsSection(viewModel: ReconcileSectionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = BudgetTheme.colors
    SettingsSection(stringResource(R.string.settings_reconcile_header)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(colors.card, RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 6.dp)) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_reconcile_auto),
                subtitle = stringResource(R.string.settings_reconcile_auto_sub),
                checked = state.autoEnabled,
                onCheckedChange = viewModel::setAuto,
                modifier = Modifier.testTag(AUTO_RECONCILE_SWITCH_TAG),
                minHeight = 72.dp,
            )
            HorizontalDivider(color = colors.line)
            Text(stringResource(R.string.settings_reconcile_note), style = TextRoles.hint, color = colors.muted, modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}
