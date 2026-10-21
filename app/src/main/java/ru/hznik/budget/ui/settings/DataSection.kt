package ru.hznik.budget.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.hznik.budget.R
import ru.hznik.budget.ui.components.SettingsSection
import ru.hznik.budget.ui.theme.BudgetTheme
import ru.hznik.budget.ui.theme.TextRoles
import ru.hznik.budget.ui.userdata.DeleteAllDialog
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue

/**
 * Раздел «Данные» по макету DataExport.html (SettingsData): экспорт, резервная копия и восстановление в одной карточке,
 * «Удалить все данные» отдельно цветом предупреждения (spec data-export «Раздел «Данные» в настройках»).
 */
@Composable
fun DataSection(onExport: () -> Unit, onBackup: () -> Unit, onRestore: () -> Unit) {
    val colors = BudgetTheme.colors
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    if (confirmDelete) {
        DeleteAllDialog(
            onDismiss = { confirmDelete = false },
            onBackupFirst = {
                confirmDelete = false
                onBackup()
            },
        )
    }
    SettingsSection(stringResource(R.string.settings_group_data)) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(colors.card),
        ) {
            DataRow(R.drawable.ic_file_spreadsheet, stringResource(R.string.data_export), stringResource(R.string.data_export_hint), onClick = onExport)
            HorizontalDivider(color = colors.line, modifier = Modifier.padding(horizontal = 18.dp))
            DataRow(R.drawable.ic_lock, stringResource(R.string.data_backup), stringResource(R.string.data_backup_hint), onClick = onBackup)
            HorizontalDivider(color = colors.line, modifier = Modifier.padding(horizontal = 18.dp))
            DataRow(R.drawable.ic_restore, stringResource(R.string.data_restore), stringResource(R.string.data_restore_hint), onClick = onRestore)
        }
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(colors.card),
        ) {
            DataRow(
                R.drawable.ic_delete,
                stringResource(R.string.data_delete),
                stringResource(R.string.data_delete_hint),
                tint = colors.warn,
                tileColor = colors.warnSoft,
                titleColor = colors.warn,
                chevron = false,
                onClick = { confirmDelete = true },
            )
        }
    }
}

@Composable
private fun DataRow(
    @DrawableRes icon: Int,
    title: String,
    hint: String,
    tint: Color = BudgetTheme.colors.primaryText,
    tileColor: Color = BudgetTheme.colors.primarySoft,
    titleColor: Color = BudgetTheme.colors.ink,
    chevron: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = BudgetTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).background(tileColor, RoundedCornerShape(12.dp))) {
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = TextRoles.itemTitle, color = titleColor)
            Text(hint, style = TextRoles.hint, color = colors.muted, modifier = Modifier.padding(top = 2.dp))
        }
        if (chevron) Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = colors.muted)
    }
}
