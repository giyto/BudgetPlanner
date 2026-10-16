package ru.hznik.budget.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import ru.hznik.budget.domain.ai.AiSettings
import ru.hznik.budget.domain.ai.LlmProvider
import ru.hznik.budget.ui.format.MoneyFormat
import ru.hznik.budget.ui.theme.BudgetTheme
import javax.inject.Inject

const val ASSISTANT_SWITCH_TAG = "assistant_switch"
const val ASSISTANT_HIDE_MERCHANTS_TAG = "assistant_hide_merchants"

data class AssistantSettingsState(val enabled: Boolean = false, val hideMerchants: Boolean = false, val tokensUsed: Long = 0, val configured: Boolean = true)

@HiltViewModel
class AssistantSettingsViewModel @Inject constructor(private val settings: AiSettings, provider: LlmProvider) : ViewModel() {
    private val configured = provider.isConfigured

    val state: StateFlow<AssistantSettingsState> = combine(settings.enabled, settings.hideMerchants, settings.tokensUsed) { enabled, hide, tokens ->
        AssistantSettingsState(enabled, hide, tokens, configured)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssistantSettingsState(configured = configured))

    fun setEnabled(enabled: Boolean) {
        if (enabled && !configured) return
        viewModelScope.launch { settings.setEnabled(enabled) }
    }

    fun setHideMerchants(hide: Boolean) = viewModelScope.launch { settings.setHideMerchants(hide) }
}

/** Раздел «ИИ-помощник» (макет Settings, spec ai-assistant): ключ в настройках не показывается. */
@Composable
fun AssistantSettingsSection(viewModel: AssistantSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = BudgetTheme.colors
    Text(
        stringResource(R.string.settings_group_assistant),
        fontSize = 14.sp,
        color = colors.muted,
        modifier = Modifier.padding(start = 22.dp, top = 6.dp).semantics { heading() },
    )
    Column(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(colors.card, RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 6.dp)) {
        SettingSwitch(
            title = stringResource(R.string.settings_assistant_enabled),
            sub = stringResource(if (state.configured) R.string.settings_assistant_enabled_sub else R.string.assistant_not_configured),
            checked = state.enabled,
            enabled = state.configured || state.enabled,
            tag = ASSISTANT_SWITCH_TAG,
            onChange = viewModel::setEnabled,
        )
        HorizontalDivider(color = colors.line)
        SettingSwitch(
            title = stringResource(R.string.settings_assistant_hide_merchants),
            sub = stringResource(R.string.settings_assistant_hide_merchants_sub),
            checked = state.hideMerchants,
            enabled = true,
            tag = ASSISTANT_HIDE_MERCHANTS_TAG,
            onChange = { viewModel.setHideMerchants(it) },
        )
        HorizontalDivider(color = colors.line)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.settings_assistant_tokens), fontSize = 15.sp, color = colors.ink, modifier = Modifier.weight(1f))
            Text(MoneyFormat.groupDigits(state.tokensUsed), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
        }
    }
}

@Composable
private fun SettingSwitch(title: String, sub: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val colors = BudgetTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .testTag(tag),
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) colors.ink else colors.muted)
            Text(sub, fontSize = 13.sp, color = colors.muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.primary, checkedThumbColor = colors.onPrimary),
        )
    }
}
