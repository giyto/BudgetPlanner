package ru.hznik.budget.ui.autoimport

import androidx.activity.compose.LocalActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ru.hznik.budget.R
import ru.hznik.budget.domain.notifications.SetupStep
import ru.hznik.budget.ui.components.AlertBanner
import ru.hznik.budget.ui.components.PrimaryButton
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
    PrimaryButton(stepButtonText(state.step), onClick = { onOpen(state.step) }, modifier = modifier, enabled = state.step != SetupStep.DONE)
}

/** Плашка на главной: автоимпорт включали, а он не работает. Справа вместо стрелки — «Исправить». */
@Composable
fun AutoImportStoppedBanner(state: AutoImportState, onFix: (SetupStep) -> Unit) {
    if (!state.stopped) return
    val step = if (state.step == SetupStep.DONE) SetupStep.NOTIFICATION_ACCESS else state.step
    val fix = stringResource(R.string.auto_import_fix)
    AlertBanner(
        icon = R.drawable.ic_bell,
        title = stringResource(R.string.auto_import_stopped),
        subtitle = stringResource(R.string.auto_import_stopped_text),
        onClickLabel = fix,
        onClick = { onFix(step) },
        modifier = Modifier.testTag(AUTO_IMPORT_STOPPED_TAG),
    ) {
        Text(fix, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = BudgetTheme.colors.primaryText)
    }
}
