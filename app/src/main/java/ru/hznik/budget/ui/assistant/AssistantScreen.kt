package ru.hznik.budget.ui.assistant

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ru.hznik.budget.R
import ru.hznik.budget.ui.components.PlaceholderScreen

/** ИИ-помощник. Наполняется на этапе add-ai-assistant. */
@Composable
fun AssistantScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = stringResource(R.string.assistant_title),
        description = stringResource(R.string.assistant_placeholder),
        modifier = modifier,
    )
}
