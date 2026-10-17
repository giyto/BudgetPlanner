package ru.hznik.budget.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.hznik.budget.ui.theme.BudgetTheme
import ru.hznik.budget.ui.theme.TextRoles

/** Заголовок группы по макетам Settings и SettingsRules (.group): 22 dp от края экрана и 8 dp до карточки; отступ сверху задаёт [modifier]. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = TextRoles.groupHeader,
        color = BudgetTheme.colors.muted,
        modifier = modifier.padding(start = 22.dp, bottom = 8.dp).semantics { heading() },
    )
}

/**
 * Раздел экрана настроек: заголовок и карточки под ним через 12 dp. Разделы стоят в списке с промежутком 12 dp,
 * поэтому у заголовка ещё 6 dp сверху: вместе 18 dp, как в макете Settings.
 */
@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        SectionHeader(title, Modifier.padding(top = 6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/**
 * Строка карточки настроек с переключателем: заголовок, подпись и переключатель справа, нажимается вся строка.
 * Цвета переключателя из макета Settings: включён — синяя дорожка, выключен — дорожка цвета рамки чипа и белый
 * бегунок в обеих темах.
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = 64.dp,
    verticalPadding: Dp = 8.dp,
) {
    val colors = BudgetTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = verticalPadding),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = TextRoles.itemTitle, color = if (enabled) colors.ink else colors.muted)
            Text(subtitle, style = TextRoles.hint, color = colors.muted, modifier = Modifier.padding(top = 2.dp))
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
                uncheckedThumbColor = Color.White,
                uncheckedBorderColor = colors.chipBorder,
            ),
        )
    }
}
