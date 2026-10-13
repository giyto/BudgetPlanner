package ru.hznik.budget.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.hznik.budget.R
import ru.hznik.budget.ui.components.PlaceholderScreen
import ru.hznik.budget.ui.components.ScreenTitle
import ru.hznik.budget.ui.theme.BudgetPlannerTheme
import ru.hznik.budget.ui.theme.BudgetTheme

/** Главная. Пока заглушка: заголовок, кнопка настроек и кнопка «+» по макету Main.dc.html. */
@Composable
fun HomeScreen(
    onAddOperation: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        PlaceholderScreen(
            title = stringResource(R.string.home_title),
            description = stringResource(R.string.home_placeholder),
            header = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 16.dp),
                ) {
                    ScreenTitle(stringResource(R.string.home_title), Modifier.weight(1f))
                    FilledIconButton(
                        onClick = onOpenSettings,
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = BudgetTheme.colors.card,
                            contentColor = BudgetTheme.colors.ink,
                        ),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = stringResource(R.string.action_settings),
                        )
                    }
                }
            },
        )
        FloatingActionButton(
            onClick = onAddOperation,
            shape = RoundedCornerShape(20.dp),
            containerColor = BudgetTheme.colors.primary,
            contentColor = BudgetTheme.colors.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 20.dp)
                .size(60.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = stringResource(R.string.action_add_operation),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Preview(name = "Светлая")
@Composable
private fun HomeScreenLightPreview() {
    BudgetPlannerTheme(darkTheme = false) { HomeScreen(onAddOperation = {}, onOpenSettings = {}) }
}

@Preview(name = "Тёмная")
@Composable
private fun HomeScreenDarkPreview() {
    BudgetPlannerTheme(darkTheme = true) { HomeScreen(onAddOperation = {}, onOpenSettings = {}) }
}
