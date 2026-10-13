package ru.hznik.budget.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalBudgetColors = staticCompositionLocalOf { LightBudgetColors }

/** Доступ к токенам палитры, которых нет в Material 3: главная карточка, «осталось», перерасход, категории. */
object BudgetTheme {
    val colors: BudgetColors
        @Composable
        @ReadOnlyComposable
        get() = LocalBudgetColors.current
}

/**
 * Тема приложения. Цвета всегда берутся из макетов: динамические цвета Android 12+ не используются,
 * чтобы приложение выглядело одинаково на любом телефоне.
 */
@Composable
fun BudgetPlannerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkBudgetColors else LightBudgetColors
    CompositionLocalProvider(LocalBudgetColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = BudgetTypography,
            content = content,
        )
    }
}

private fun BudgetColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primarySoft,
        onPrimaryContainer = primaryText,
        secondary = primaryText,
        onSecondary = onPrimary,
        // Индикатор активной вкладки NavigationBar.
        secondaryContainer = primarySoft,
        onSecondaryContainer = primaryText,
        tertiary = positive,
        tertiaryContainer = positiveSoft,
        onTertiaryContainer = positive,
        background = background,
        onBackground = ink,
        surface = card,
        onSurface = ink,
        surfaceVariant = card2,
        onSurfaceVariant = muted,
        surfaceContainerLowest = card,
        surfaceContainerLow = card,
        surfaceContainer = navBar,
        surfaceContainerHigh = card,
        surfaceContainerHighest = card2,
        inverseSurface = hero,
        inverseOnSurface = heroText,
        outline = chipBorder,
        outlineVariant = line,
        error = warn,
        onError = onPrimary,
        errorContainer = warnSoft,
        onErrorContainer = warn,
        scrim = shadow,
    )
}
