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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
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
import ru.hznik.budget.ui.lock.AuthAvailability
import ru.hznik.budget.ui.lock.AuthResult
import ru.hznik.budget.ui.lock.LocalDeviceAuthenticator
import ru.hznik.budget.ui.theme.BudgetTheme
import javax.inject.Inject

@HiltViewModel
class ProtectionViewModel @Inject constructor(private val repository: LockSettingsRepository) : ViewModel() {
    val settings: StateFlow<LockSettings?> = repository.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Вызывается только после успешной проверки разблокировкой. */
    fun setEnabled(enabled: Boolean) = viewModelScope.launch { repository.setEnabled(enabled) }

    fun setTimeout(timeout: LockTimeout) = viewModelScope.launch { repository.setTimeout(timeout) }

    fun setHideInRecents(hide: Boolean) = viewModelScope.launch { repository.setHideInRecents(hide) }
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

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.settings_group_protection),
            fontSize = 14.sp,
            color = colors.muted,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp).semantics { heading() },
        )
        Column(modifier = Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 4.dp)) {
            SwitchRow(
                title = stringResource(R.string.protection_enabled),
                hint = stringResource(R.string.protection_enabled_hint),
                checked = current.enabled,
                enabled = canToggle,
                onCheckedChange = toggle,
            )
            if (!current.enabled && availability != AuthAvailability.AVAILABLE) {
                NoScreenLockHint(availability)
            }
            error?.let { Text(it, fontSize = 13.sp, color = colors.warn, modifier = Modifier.padding(bottom = 10.dp)) }
            if (current.enabled) {
                Divider()
                Column(modifier = Modifier.padding(top = 14.dp, bottom = 16.dp)) {
                    Text(stringResource(R.string.protection_timeout), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
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
            }
            Divider()
            SwitchRow(
                title = stringResource(R.string.protection_hide),
                hint = stringResource(R.string.protection_hide_hint),
                checked = current.hideInRecents,
                enabled = true,
                onCheckedChange = viewModel::setHideInRecents,
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
            fontSize = 13.sp,
            lineHeight = 18.sp,
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
private fun SwitchRow(title: String, hint: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = BudgetTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) colors.ink else colors.muted)
            Text(hint, fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.primary,
                checkedThumbColor = colors.onPrimary,
                uncheckedTrackColor = colors.chipBorder,
                uncheckedThumbColor = if (colors.isDark) colors.muted else colors.card,
                uncheckedBorderColor = colors.chipBorder,
            ),
        )
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(BudgetTheme.colors.line))
}
