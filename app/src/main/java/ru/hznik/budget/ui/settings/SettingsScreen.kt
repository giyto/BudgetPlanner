package ru.hznik.budget.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.hznik.budget.R
import ru.hznik.budget.ui.components.NavigationHeader
import ru.hznik.budget.ui.theme.BudgetTheme

/** Настройки. Разделы добавляются по мере этапов (раздел 7.9 ТЗ). */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenPeriod: () -> Unit,
    modifier: Modifier = Modifier,
    autoImport: @Composable () -> Unit = {},
    reconcile: @Composable () -> Unit = {},
    assistant: @Composable () -> Unit = {},
    notifications: @Composable () -> Unit = {},
    protection: @Composable () -> Unit = {},
    rules: @Composable () -> Unit = {},
    extra: @Composable () -> Unit = {},
) {
    val colors = BudgetTheme.colors
    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        NavigationHeader(stringResource(R.string.settings_title), R.drawable.ic_back, stringResource(R.string.action_back), onBack)
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        ) {
            autoImport()
            reconcile()
            assistant()
            notifications()
            protection()
            Text(
                stringResource(R.string.settings_group_budget),
                fontSize = 14.sp,
                color = colors.muted,
                modifier = Modifier.padding(start = 22.dp, top = 6.dp).semantics { heading() },
            )
            SettingsItem(stringResource(R.string.settings_period), stringResource(R.string.settings_period_hint), onOpenPeriod)
            SettingsItem(stringResource(R.string.settings_categories), stringResource(R.string.settings_categories_hint), onOpenCategories)
            rules()
            extra()
        }
    }
}

@Composable
internal fun SettingsItem(title: String, hint: String, onClick: () -> Unit) {
    val colors = BudgetTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(colors.card)
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            Text(hint, fontSize = 13.sp, color = colors.muted)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = colors.muted)
    }
}
