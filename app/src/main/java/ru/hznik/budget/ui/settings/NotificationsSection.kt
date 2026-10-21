package ru.hznik.budget.ui.settings

import android.Manifest
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.hznik.budget.R
import ru.hznik.budget.domain.reminders.NotificationSettings
import ru.hznik.budget.domain.reminders.NotificationType
import ru.hznik.budget.reminders.NotificationPermission
import ru.hznik.budget.reminders.canAskNotificationPermission
import ru.hznik.budget.reminders.openAppNotificationSettings
import ru.hznik.budget.ui.components.SettingsSection
import ru.hznik.budget.ui.components.SettingsSwitchRow
import ru.hznik.budget.ui.theme.BudgetTheme
import javax.inject.Inject
import ru.hznik.budget.ui.launchSave

const val NOTIFICATIONS_DENIED_TAG = "notifications_denied"

fun notificationSwitchTag(type: NotificationType) = "notification_switch_${type.name}"

data class NotificationsSectionState(
    val enabled: Set<NotificationType> = NotificationType.entries.toSet(),
    /** Система показывает уведомления приложения; `false` — строка «Уведомления запрещены в системе». */
    val systemAllowed: Boolean = true,
)

@HiltViewModel
class NotificationsSectionViewModel @Inject constructor(
    private val settings: NotificationSettings,
    private val permission: NotificationPermission,
) : ViewModel() {
    private val systemAllowed = MutableStateFlow(permission.areEnabled())

    val state: StateFlow<NotificationsSectionState> = combine(settings.enabled, systemAllowed, ::NotificationsSectionState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationsSectionState(systemAllowed = permission.areEnabled()))

    fun setEnabled(type: NotificationType, enabled: Boolean) {
        launchSave { settings.setEnabled(type, enabled) }
    }

    /** После возврата из системного запроса или настроек разрешение могло измениться. */
    fun refresh() {
        systemAllowed.value = permission.areEnabled()
    }

    fun isRequestNeeded(): Boolean = permission.isRequestNeeded()

    suspend fun wasRequested(): Boolean = permission.wasRequested()

    fun onRequested() {
        viewModelScope.launch { permission.markRequested() }
        refresh()
    }
}

/** Раздел «Уведомления» (макет Settings, spec app-notifications): четыре типа и разрешение системы. */
@Composable
fun NotificationsSection(viewModel: NotificationsSectionViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = BudgetTheme.colors
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.onRequested() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val allow: () -> Unit = {
        scope.launch {
            // Запрос, пока система его показывает; после окончательного отказа и на Android до 13 — настройки приложения.
            if (activity != null && viewModel.isRequestNeeded() && activity.canAskNotificationPermission(viewModel.wasRequested())) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                activity?.openAppNotificationSettings()
            }
        }
    }

    SettingsSection(stringResource(R.string.settings_group_notifications)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(colors.card, RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 6.dp)) {
            if (!state.systemAllowed) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(NOTIFICATIONS_DENIED_TAG),
                ) {
                    Text(
                        stringResource(R.string.settings_notif_denied),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.warn,
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    )
                    TextButton(onClick = allow, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.settings_notif_allow), color = colors.primaryText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                HorizontalDivider(color = colors.line)
            }
            NotificationType.entries.forEachIndexed { index, type ->
                if (index > 0) HorizontalDivider(color = colors.line)
                NotificationSwitch(type, checked = type in state.enabled, onChange = { viewModel.setEnabled(type, it) })
            }
        }
    }
}

@Composable
private fun NotificationSwitch(type: NotificationType, checked: Boolean, onChange: (Boolean) -> Unit) {
    val (title, sub) = when (type) {
        NotificationType.OVERSPEND -> R.string.settings_notif_overspend to R.string.settings_notif_overspend_sub
        NotificationType.PAYMENT -> R.string.settings_notif_payments to R.string.settings_notif_payments_sub
        NotificationType.NEW_PERIOD -> R.string.settings_notif_new_period to R.string.settings_notif_new_period_sub
        NotificationType.BANK_MISMATCH -> R.string.settings_notif_mismatch to R.string.settings_notif_mismatch_sub
    }
    SettingsSwitchRow(
        title = stringResource(title),
        subtitle = stringResource(sub),
        checked = checked,
        onCheckedChange = onChange,
        modifier = Modifier.testTag(notificationSwitchTag(type)),
    )
}
