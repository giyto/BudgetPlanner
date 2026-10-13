package ru.hznik.budget.ui.operations

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ru.hznik.budget.R
import ru.hznik.budget.ui.components.PlaceholderScreen

/** Список операций. Наполняется на этапе add-operations-and-categories. */
@Composable
fun OperationsScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = stringResource(R.string.operations_title),
        description = stringResource(R.string.operations_placeholder),
        modifier = modifier,
    )
}
