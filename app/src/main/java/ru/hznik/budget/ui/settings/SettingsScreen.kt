package ru.hznik.budget.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ru.hznik.budget.R
import ru.hznik.budget.ui.components.NavigationHeader
import ru.hznik.budget.ui.components.PlaceholderScreen

/** Настройки. Разделы добавляются по мере этапов (раздел 7.9 ТЗ). */
@Composable
fun SettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.settings_title)
    PlaceholderScreen(
        title = title,
        description = stringResource(R.string.settings_placeholder),
        modifier = modifier,
        header = {
            NavigationHeader(
                title = title,
                navigationIcon = R.drawable.ic_back,
                navigationLabel = stringResource(R.string.action_back),
                onNavigate = onBack,
            )
        },
    )
}
