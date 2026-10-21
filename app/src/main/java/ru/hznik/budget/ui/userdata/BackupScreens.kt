package ru.hznik.budget.ui.userdata

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.hznik.budget.R
import ru.hznik.budget.ui.components.NavigationHeader
import ru.hznik.budget.ui.components.PrimaryButton
import ru.hznik.budget.ui.components.SecondaryButton
import ru.hznik.budget.ui.components.SectionHeader
import ru.hznik.budget.ui.components.formatDayMonth
import ru.hznik.budget.ui.format.SpokenMoney
import ru.hznik.budget.ui.theme.BudgetTheme
import ru.hznik.budget.ui.theme.LocalReducedMotion
import ru.hznik.budget.ui.theme.Motion
import ru.hznik.budget.ui.theme.TextRoles
import java.time.ZoneId
import androidx.compose.ui.unit.sp

const val BACKUP_PASSWORD_TAG = "backup_password"
const val BACKUP_REPEAT_TAG = "backup_repeat"
const val RESTORE_PASSWORD_TAG = "restore_password"
const val RESTORE_FILE_TAG = "restore_file"

@Composable
fun BackupRoute(onBack: () -> Unit, viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Место сохранения выбирает пользователь; закрытое окно ничего не создаёт.
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.save(uri.toString())
    }
    BackupScreen(state, onBack, viewModel::onPassword, viewModel::onRepeat, onSave = { save.launch(viewModel.fileName) })
}

/** Резервная копия по макету DataExport.html (Backup). */
@Composable
fun BackupScreen(
    state: BackupUiState,
    onBack: () -> Unit,
    onPassword: (String) -> Unit,
    onRepeat: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BudgetTheme.colors
    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        NavigationHeader(stringResource(R.string.backup_title), R.drawable.ic_back, stringResource(R.string.action_back), onBack)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.backup_about),
                style = TextRoles.hint.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = colors.sub,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
            )
            SectionHeader(stringResource(R.string.backup_password))
            PasswordField(state.password, onPassword, isError = state.tooShort, modifier = Modifier.testTag(BACKUP_PASSWORD_TAG))
            AnimatedHint(if (state.tooShort) stringResource(R.string.backup_password_short) else null, colors.warn, R.drawable.ic_info)
            SectionHeader(stringResource(R.string.backup_password_repeat))
            PasswordField(state.repeat, onRepeat, isError = state.differ, modifier = Modifier.testTag(BACKUP_REPEAT_TAG))
            AnimatedHint(
                when {
                    state.matches -> stringResource(R.string.backup_passwords_match)
                    state.differ -> stringResource(R.string.backup_passwords_differ)
                    else -> null
                },
                if (state.matches) colors.positive else colors.warn,
                if (state.matches) R.drawable.ic_check else R.drawable.ic_info,
            )
            WarnBox(R.drawable.ic_alert, stringResource(R.string.backup_warning))
        }
        PrimaryButton(
            text = stringResource(R.string.backup_save),
            onClick = onSave,
            enabled = state.canSave,
            busy = state.saving,
            busyText = stringResource(R.string.backup_saving),
            icon = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null, modifier = Modifier.size(22.dp)) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).navigationBarsPadding(),
        )
    }
}

@Composable
fun RestoreRoute(onBack: () -> Unit, viewModel: RestoreViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Тип копии у системы неизвестен, поэтому показываются все файлы.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onFile(uri.toString())
    }
    RestoreScreen(
        state = state,
        onBack = onBack,
        onPickFile = { pick.launch(arrayOf("*/*")) },
        onPassword = viewModel::onPassword,
        onRestore = viewModel::check,
        onConfirm = viewModel::confirm,
        onCancelConfirm = viewModel::cancelConfirm,
        zone = viewModel.zone,
    )
}

/** Восстановление по макетам DataExport.html (Restore, RestoreConfirm). */
@Composable
fun RestoreScreen(
    state: RestoreUiState,
    onBack: () -> Unit,
    onPickFile: () -> Unit,
    onPassword: (String) -> Unit,
    onRestore: () -> Unit,
    onConfirm: () -> Unit,
    onCancelConfirm: () -> Unit,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    val colors = BudgetTheme.colors
    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        NavigationHeader(stringResource(R.string.restore_title), R.drawable.ic_back, stringResource(R.string.action_back), onBack)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionHeader(stringResource(R.string.restore_file))
            FileCard(state, onPickFile)
            SectionHeader(stringResource(R.string.restore_password))
            PasswordField(
                state.password,
                onPassword,
                isError = state.error != null,
                enabled = state.source != null,
                modifier = Modifier.testTag(RESTORE_PASSWORD_TAG),
            )
            AnimatedHint(
                when (state.error) {
                    RestoreError.WRONG_PASSWORD -> stringResource(R.string.restore_wrong_password)
                    RestoreError.NOT_BACKUP -> stringResource(R.string.restore_not_backup)
                    RestoreError.TOO_NEW -> stringResource(R.string.restore_too_new)
                    null -> null
                },
                colors.warn,
                R.drawable.ic_info,
            )
            WarnBox(R.drawable.ic_info, stringResource(R.string.restore_warning))
        }
        PrimaryButton(
            text = stringResource(R.string.restore_button),
            onClick = onRestore,
            enabled = state.canRestore,
            busy = state.checking,
            busyText = stringResource(R.string.restore_checking),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).navigationBarsPadding(),
        )
    }
    state.confirm?.let { valid ->
        val now = operations(valid.currentOperations)
        val copy = operations(valid.backupOperations)
        val date = formatDayMonth(valid.createdAt.atZone(zone).toLocalDate())
        AlertDialog(
            onDismissRequest = onCancelConfirm,
            title = { Text(stringResource(R.string.restore_confirm_title)) },
            text = { Text(stringResource(R.string.restore_confirm_text, now, copy, date)) },
            confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.restore_confirm)) } },
            dismissButton = { TextButton(onClick = onCancelConfirm) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun operations(n: Int) = "$n ${SpokenMoney.plural(n.toLong(), "операция", "операции", "операций")}"

/** Выбранный файл копии или кнопка выбора, если файл ещё не выбран. */
@Composable
private fun FileCard(state: RestoreUiState, onPickFile: () -> Unit) {
    val colors = BudgetTheme.colors
    val file = state.file
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(colors.card, RoundedCornerShape(22.dp))
            .clickable(onClick = onPickFile)
            .testTag(RESTORE_FILE_TAG)
            .heightIn(min = 64.dp)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).background(colors.primarySoft, RoundedCornerShape(12.dp))) {
            Icon(painterResource(R.drawable.ic_file_text), contentDescription = null, tint = colors.primaryText, modifier = Modifier.size(22.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file?.name ?: stringResource(R.string.restore_choose),
                style = TextRoles.itemTitle,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                file?.sizeBytes?.let(::fileSize) ?: stringResource(R.string.restore_choose_hint),
                style = TextRoles.hint,
                color = colors.muted,
            )
        }
        if (state.source != null) Text(stringResource(R.string.restore_other), style = TextRoles.button, color = colors.primaryText)
    }
}

/** «1,2 МБ» или «340 КБ». */
private fun fileSize(bytes: Long): String =
    if (bytes >= 1024 * 1024) "%.1f МБ".format(bytes / 1024.0 / 1024.0).replace('.', ',') else "${(bytes + 1023) / 1024} КБ"

/** Поле пароля по макету: рамка, точки вместо букв, кнопка «показать». */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = BudgetTheme.colors
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        enabled = enabled,
        isError = isError,
        shape = RoundedCornerShape(16.dp),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    painterResource(if (visible) R.drawable.ic_eye_off else R.drawable.ic_eye),
                    contentDescription = stringResource(if (visible) R.string.password_hide else R.string.password_show),
                    tint = colors.muted,
                )
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.chipBorder,
            errorBorderColor = colors.warn,
            focusedContainerColor = colors.card,
            unfocusedContainerColor = colors.card,
            errorContainerColor = colors.card,
            disabledContainerColor = colors.card,
        ),
        modifier = modifier.padding(horizontal = 16.dp).fillMaxWidth(),
    )
}

/** Подсказка под полем: появляется и исчезает проявлением (spec ui-motion «Анимации экранов данных»). */
@Composable
private fun AnimatedHint(text: String?, color: Color, @DrawableRes icon: Int) {
    val reduced = LocalReducedMotion.current
    // Последний текст показывается, пока подсказка исчезает.
    val last = remember { mutableStateOf(text) }
    if (text != null) last.value = text
    AnimatedVisibility(
        visible = text != null,
        enter = if (reduced) EnterTransition.None else fadeIn(tween(Motion.ITEM)),
        exit = if (reduced) ExitTransition.None else fadeOut(tween(Motion.ITEM)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Text(last.value.orEmpty(), style = TextRoles.hint, color = color)
        }
    }
}

/** Плашка-предупреждение цвета перерасхода, как в макете. */
@Composable
private fun WarnBox(@DrawableRes icon: Int, text: String) {
    val colors = BudgetTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .fillMaxWidth()
            .background(colors.warnSoft, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = colors.warn, modifier = Modifier.size(22.dp))
        Text(text, style = TextRoles.hint.copy(fontSize = 14.sp, lineHeight = 19.sp), color = colors.ink)
    }
}

/** «Удалить все данные?» по макету DataExport.html (DeleteAll). */
@Composable
fun DeleteAllDialog(onDismiss: () -> Unit, onBackupFirst: () -> Unit, viewModel: DeleteAllViewModel = hiltViewModel()) {
    val colors = BudgetTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.delete_text))
                Text(stringResource(R.string.delete_permissions))
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(text = stringResource(R.string.delete_confirm), onClick = viewModel::confirm, danger = true)
                SecondaryButton(text = stringResource(R.string.delete_backup_first), onClick = onBackupFirst)
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_cancel), style = TextRoles.button, color = colors.primaryText)
                }
            }
        },
    )
}
