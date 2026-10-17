package ru.hznik.budget.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.hznik.budget.ui.theme.BudgetTheme

/** Заголовок экрана шрифтом Unbounded, как в макетах. */
@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = BudgetTheme.colors.ink,
        modifier = modifier
            .padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 12.dp)
            .semantics { heading() },
    )
}

/**
 * Заголовок экрана поверх разделов: слева кнопка «Закрыть» или «Назад», справа название. [actions] — кнопки
 * у правого края (например, «Удалить»); отступы у них свои.
 */
@Composable
fun NavigationHeader(
    title: String,
    @DrawableRes navigationIcon: Int,
    navigationLabel: String,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f).padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        ) {
            IconButton(onClick = onNavigate) {
                Icon(
                    painter = painterResource(navigationIcon),
                    contentDescription = navigationLabel,
                    tint = BudgetTheme.colors.ink,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = BudgetTheme.colors.ink,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .semantics { heading() },
            )
        }
        actions()
    }
}
