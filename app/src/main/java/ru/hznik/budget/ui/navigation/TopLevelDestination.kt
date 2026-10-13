package ru.hznik.budget.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import ru.hznik.budget.R

/** Вкладки нижней панели в порядке отображения. */
enum class TopLevelDestination(
    val graph: Any,
    val startRoute: Any,
    @param:DrawableRes val icon: Int,
    @param:StringRes val label: Int,
) {
    HOME(HomeGraph, Home, R.drawable.ic_nav_home, R.string.tab_home),
    OPERATIONS(OperationsGraph, Operations, R.drawable.ic_nav_operations, R.string.tab_operations),
    STATS(StatsGraph, Stats, R.drawable.ic_nav_stats, R.string.tab_stats),
    ASSISTANT(AssistantGraph, Assistant, R.drawable.ic_nav_assistant, R.string.tab_assistant),
}
