package ru.hznik.budget.ui.stats

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ru.hznik.budget.R
import ru.hznik.budget.ui.components.PlaceholderScreen

/** Статистика. Наполняется на этапе add-statistics. */
@Composable
fun StatsScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = stringResource(R.string.stats_title),
        description = stringResource(R.string.stats_placeholder),
        modifier = modifier,
    )
}
