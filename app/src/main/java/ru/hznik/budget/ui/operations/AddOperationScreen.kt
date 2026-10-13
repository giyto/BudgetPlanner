package ru.hznik.budget.ui.operations

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ru.hznik.budget.R
import ru.hznik.budget.ui.components.NavigationHeader
import ru.hznik.budget.ui.components.PlaceholderScreen

/** Новая операция. Наполняется на этапе add-operations-and-categories по макету Add.dc.html. */
@Composable
fun AddOperationScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.add_operation_title)
    PlaceholderScreen(
        title = title,
        description = stringResource(R.string.add_operation_placeholder),
        modifier = modifier,
        header = {
            NavigationHeader(
                title = title,
                navigationIcon = R.drawable.ic_close,
                navigationLabel = stringResource(R.string.action_close),
                onNavigate = onClose,
            )
        },
    )
}
