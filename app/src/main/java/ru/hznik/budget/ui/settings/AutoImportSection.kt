package ru.hznik.budget.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.hznik.budget.R
import ru.hznik.budget.domain.notifications.SetupStep
import ru.hznik.budget.ui.autoimport.AutoImportViewModel
import ru.hznik.budget.ui.autoimport.RefreshOnResume
import ru.hznik.budget.ui.autoimport.SetupStepButton
import ru.hznik.budget.ui.autoimport.rememberStepOpener
import ru.hznik.budget.ui.autoimport.stepHint
import ru.hznik.budget.ui.theme.BudgetTheme

const val AUTO_IMPORT_STATUS_TAG = "auto_import_status"

/** Раздел «Автоимпорт» в настройках (spec notification-import, «Состояние автоимпорта в настройках»). */
@Composable
fun AutoImportSection(viewModel: AutoImportViewModel = hiltViewModel()) {
    val colors = BudgetTheme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()
    val open = rememberStepOpener(viewModel)
    RefreshOnResume(viewModel)

    val (statusTitle, statusHint, ok) = when {
        state.stopped -> Triple(R.string.auto_import_stopped, R.string.auto_import_stopped_text, false)
        state.accessGranted -> Triple(R.string.auto_import_on, R.string.auto_import_on_hint, true)
        else -> Triple(R.string.auto_import_off, R.string.auto_import_off_hint, false)
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.settings_group_auto_import),
            fontSize = 14.sp,
            color = colors.muted,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp).semantics { heading() },
        )
        Column(modifier = Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 64.dp).testTag(AUTO_IMPORT_STATUS_TAG)) {
                Box(Modifier.size(10.dp).background(if (ok) colors.positive else colors.warn, CircleShape))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(statusTitle), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                    Text(stringResource(statusHint), fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted)
                }
            }
            if (state.step != SetupStep.DONE) {
                SetupStepButton(state, onOpen = open, modifier = Modifier.padding(top = 4.dp))
                stepHint(state)?.let { Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
            Text(stringResource(R.string.auto_import_banks), fontSize = 13.sp, color = colors.muted, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            state.banks.forEach { (name, installed) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(name, fontSize = 15.sp, color = colors.ink, modifier = Modifier.weight(1f))
                    Text(
                        stringResource(if (installed) R.string.auto_import_bank_installed else R.string.auto_import_bank_missing),
                        fontSize = 13.sp,
                        color = if (installed) colors.positive else colors.muted,
                    )
                }
            }
            // Автозапуск нельзя проверить, поэтому для таких прошивок кнопка остаётся и после прохождения шага.
            if (state.needsAutostart && state.accessGranted && state.step == SetupStep.DONE) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
                TextButton(onClick = { open(SetupStep.AUTOSTART) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.auto_import_autostart_open), color = colors.primaryText, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
