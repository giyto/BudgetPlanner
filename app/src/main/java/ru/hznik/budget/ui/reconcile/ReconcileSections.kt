package ru.hznik.budget.ui.reconcile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
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
import ru.hznik.budget.ui.components.formatDayMonth
import ru.hznik.budget.ui.format.MoneyFormat
import ru.hznik.budget.ui.format.accountLabel
import ru.hznik.budget.ui.theme.BudgetTheme
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

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
        viewModelScope.launch { setAutoReconcile(enabled) }
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
                    Text(stringResource(R.string.period_reconcile_last), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                    Text(subtitle, fontSize = 13.sp, color = colors.muted)
                }
            }
            OutlinedButton(
                onClick = onReconcile,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp).heightIn(min = 52.dp).testTag(PERIOD_RECONCILE_BUTTON_TAG),
            ) {
                Icon(painterResource(R.drawable.ic_bank), contentDescription = null, tint = colors.ink, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.period_reconcile_button), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            }
            Text(stringResource(R.string.period_reconcile_hint), fontSize = 13.sp, color = colors.muted, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/** Раздел настроек «Сверка с банком» по макету Settings. */
@Composable
fun ReconcileSettingsSection(viewModel: ReconcileSectionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = BudgetTheme.colors
    Text(
        stringResource(R.string.settings_reconcile_header),
        fontSize = 14.sp,
        color = colors.muted,
        modifier = Modifier.padding(start = 22.dp, top = 6.dp).semantics { heading() },
    )
    Column(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(colors.card, RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .toggleable(value = state.autoEnabled, role = Role.Switch, onValueChange = viewModel::setAuto)
                .testTag(AUTO_RECONCILE_SWITCH_TAG),
        ) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(stringResource(R.string.settings_reconcile_auto), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                Text(stringResource(R.string.settings_reconcile_auto_sub), fontSize = 13.sp, color = colors.muted)
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = state.autoEnabled,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = colors.primary, checkedThumbColor = colors.onPrimary),
            )
        }
        HorizontalDivider(color = colors.line)
        Text(stringResource(R.string.settings_reconcile_note), fontSize = 13.sp, color = colors.muted, modifier = Modifier.padding(vertical = 12.dp))
    }
}
