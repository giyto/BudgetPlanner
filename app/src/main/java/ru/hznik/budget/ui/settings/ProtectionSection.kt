package ru.hznik.budget.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.hznik.budget.R
import ru.hznik.budget.domain.lock.LockSettings
import ru.hznik.budget.domain.lock.LockSettingsRepository
import ru.hznik.budget.domain.lock.LockTimeout
import ru.hznik.budget.ui.components.SegmentedChoice
import ru.hznik.budget.ui.components.SettingsSection
import ru.hznik.budget.ui.components.SettingsSwitchRow
import ru.hznik.budget.ui.lock.AuthAvailability
import ru.hznik.budget.ui.lock.AuthResult
import ru.hznik.budget.ui.lock.LocalDeviceAuthenticator
import ru.hznik.budget.ui.theme.BudgetTheme
import ru.hznik.budget.ui.theme.TextRoles
import javax.inject.Inject
import ru.hznik.budget.ui.launchSave

@HiltViewModel
class ProtectionViewModel @Inject constructor(private val repository: LockSettingsRepository) : ViewModel() {
    val settings: StateFlow<LockSettings?> = repository.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Вызывается только после успешной проверки разблокировкой. */
    fun setEnabled(enabled: Boolean) = launchSave { repository.setEnabled(enabled) }

    fun setTimeout(timeout: LockTimeout) = launchSave { repository.setTimeout(timeout) }

    fun setHideInRecents(hide: Boolean) = launchSave { repository.setHideInRecents(hide) }

    fun setHideWidgetAmount(hide: Boolean) = launchSave { repository.setHideWidgetAmount(hide) }
}

/** Раздел «Защита входа» в настройках (spec app-lock, design.md add-data-protection, решение 10). */
@Composable
fun ProtectionSection(viewModel: ProtectionViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val current = settings ?: return
    val colors = BudgetTheme.colors
    val activity = LocalActivity.current
    val authenticator = LocalDeviceAuthenticator.current
    val scope = rememberCoroutineScope()
    var availability by remember { mutableStateOf(authenticator.availability()) }
    var error by remember { mutableStateOf<String?>(null) }
    // После возврата из системных настроек блокировка экрана могла появиться.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { availability = authenticator.availability() }

    val title = stringResource(R.string.auth_title)
    val enableSubtitle = stringResource(R.string.auth_enable_subtitle)
    val disableSubtitle = stringResource(R.string.auth_disable_subtitle)
    val canToggle = current.enabled || availability == AuthAvailability.AVAILABLE
    val toggle: (Boolean) -> Unit = { target ->
        val host = activity as? FragmentActivity
        if (host != null && canToggle) {
            scope.launch {
                when (val result = authenticator.authenticate(host, title, if (target) enableSubtitle else disableSubtitle)) {
                    AuthResult.Success -> {
                        error = null
                        viewModel.setEnabled(target)
                    }
                    AuthResult.Cancelled, AuthResult.Interrupted -> Unit
                    is AuthResult.Error -> error = result.message
                }
            }
        }
    }

    SettingsSection(stringResource(R.string.settings_group_protection)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(colors.card, RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 4.dp)) {
            SettingsSwitchRow(
                title = stringResource(R.string.protection_enabled),
                subtitle = stringResource(R.string.protection_enabled_hint),
                checked = current.enabled,
                onCheckedChange = toggle,
                enabled = canToggle,
                verticalPadding = 10.dp,
            )
            if (!current.enabled && availability != AuthAvailability.AVAILABLE) {
                NoScreenLockHint(availability)
            }
            error?.let { Text(it, fontSize = 13.sp, color = colors.warn, modifier = Modifier.padding(bottom = 10.dp)) }
            if (current.enabled) {
                Divider()
                Column(modifier = Modifier.padding(top = 14.dp, bottom = 16.dp)) {
                    Text(stringResource(R.string.protection_timeout), style = TextRoles.itemTitle, color = colors.ink)
                    Spacer(Modifier.height(10.dp))
                    SegmentedChoice(
                        options = listOf(
                            LockTimeout.IMMEDIATELY to stringResource(R.string.protection_timeout_immediately),
                            LockTimeout.ONE_MINUTE to stringResource(R.string.protection_timeout_one_minute),
                            LockTimeout.FIVE_MINUTES to stringResource(R.string.protection_timeout_five_minutes),
                        ),
                        selected = current.timeout,
                        onSelect = viewModel::setTimeout,
                    )
                }
                Divider()
                SettingsSwitchRow(
                    title = stringResource(R.string.protection_hide_widget),
                    subtitle = stringResource(R.string.protection_hide_widget_hint),
                    checked = current.hideWidgetAmount,
                    onCheckedChange = viewModel::setHideWidgetAmount,
                    verticalPadding = 10.dp,
                )
            }
            Divider()
            SettingsSwitchRow(
                title = stringResource(R.string.protection_hide),
                subtitle = stringResource(R.string.protection_hide_hint),
                checked = current.hideInRecents,
                onCheckedChange = viewModel::setHideInRecents,
                verticalPadding = 10.dp,
            )
        }
    }
}

@Composable
private fun NoScreenLockHint(availability: AuthAvailability) {
    val colors = BudgetTheme.colors
    val activity = LocalActivity.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
        Text(
            stringResource(if (availability == AuthAvailability.NO_SCREEN_LOCK) R.string.protection_no_screen_lock else R.string.protection_unavailable),
            style = TextRoles.hint,
            color = colors.muted,
            modifier = Modifier.weight(1f),
        )
        if (availability == AuthAvailability.NO_SCREEN_LOCK) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { activity?.openScreenLockSettings() }, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(stringResource(R.string.protection_setup_screen_lock), color = colors.primaryText, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Системные настройки блокировки экрана; на части прошивок раздела безопасности нет — тогда общие настройки. */
fun android.app.Activity.openScreenLockSettings() {
    try {
        startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
    } catch (e: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(BudgetTheme.colors.line))
}
