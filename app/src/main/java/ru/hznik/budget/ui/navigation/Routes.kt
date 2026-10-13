package ru.hznik.budget.ui.navigation

import kotlinx.serialization.Serializable

// Графы разделов нижней панели. У каждого свой стек экранов, который сохраняется при переключении вкладок.
@Serializable data object HomeGraph
@Serializable data object OperationsGraph
@Serializable data object StatsGraph
@Serializable data object AssistantGraph

// Начальные экраны разделов.
@Serializable data object Home
@Serializable data object Operations
@Serializable data object Stats
@Serializable data object Assistant

// Экраны поверх разделов, без нижней панели.
@Serializable data object AddOperation
@Serializable data object Settings
