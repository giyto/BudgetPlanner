package ru.hznik.budget.ui.autoimport

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ru.hznik.budget.R
import ru.hznik.budget.domain.notifications.SetupStep
import ru.hznik.budget.ui.theme.BudgetTheme

const val AUTO_IMPORT_STOPPED_TAG = "auto_import_stopped"

/** Пересчитывает шаги, когда пользователь возвращается из настроек системы. */
@Composable
fun RefreshOnResume(viewModel: AutoImportViewModel) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
}

/** Открывает экран системы для шага: через активность, чтобы «Назад» вернул в приложение. */
@Composable
fun rememberStepOpener(viewModel: AutoImportViewModel): (SetupStep) -> Unit {
    val activity = LocalActivity.current
    val context = LocalContext.current
    return { step -> SetupDevice.open(activity ?: context, viewModel.intentsFor(step)) }
}

@Composable
fun stepButtonText(step: SetupStep): String = stringResource(
    when (step) {
        SetupStep.RESTRICTED_SETTINGS -> R.string.setup_restricted
        SetupStep.NOTIFICATION_ACCESS -> R.string.setup_access
        SetupStep.AUTOSTART -> R.string.setup_autostart
        SetupStep.DONE -> R.string.setup_done
    },
)

/** Подсказка под кнопкой: что именно сделать на открывшемся экране. */
@Composable
fun stepHint(state: AutoImportState): String? = when (state.step) {
    SetupStep.RESTRICTED_SETTINGS -> stringResource(R.string.setup_restricted_hint)
    SetupStep.NOTIFICATION_ACCESS -> stringResource(if (state.accessOpensList) R.string.setup_access_hint_list else R.string.setup_access_hint)
    SetupStep.AUTOSTART -> stringResource(R.string.setup_autostart_hint)
    SetupStep.DONE -> null
}

/** Главная кнопка следующего шага включения автоимпорта (spec notification-import). */
@Composable
fun SetupStepButton(state: AutoImportState, onOpen: (SetupStep) -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetTheme.colors
    Button(
        onClick = { onOpen(state.step) },
        enabled = state.step != SetupStep.DONE,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) {
        Text(stepButtonText(state.step), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Плашка на главной: автоимпорт включали, а он не работает. */
@Composable
fun AutoImportStoppedBanner(state: AutoImportState, onFix: (SetupStep) -> Unit) {
    if (!state.stopped) return
    val colors = BudgetTheme.colors
    val step = if (state.step == SetupStep.DONE) SetupStep.NOTIFICATION_ACCESS else state.step
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.warnSoft, RoundedCornerShape(22.dp))
            .clickable { onFix(step) }
            .semantics { role = Role.Button }
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(AUTO_IMPORT_STOPPED_TAG),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp).background(colors.card, RoundedCornerShape(14.dp))) {
            Icon(painterResource(R.drawable.ic_bell), contentDescription = null, tint = colors.warn, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.auto_import_stopped), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            Text(stringResource(R.string.auto_import_stopped_text), fontSize = 13.sp, color = colors.muted)
        }
        Text(stringResource(R.string.auto_import_fix), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.primaryText)
    }
}
