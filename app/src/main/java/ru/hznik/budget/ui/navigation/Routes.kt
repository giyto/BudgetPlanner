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

/** Черновики из уведомлений банка (spec drafts). */
@Serializable data object Drafts

/** Образцы уведомлений банков (раздел «Для разработки», только отладочная сборка). */
@Serializable data object NotificationSamples

// Экраны поверх разделов, без нижней панели.

/** Ввод новой операции ([operationId] = null) или редактирование существующей. */
@Serializable data class OperationEditor(val operationId: Long? = null)

@Serializable data object Settings

@Serializable data object Categories

/** Новая категория вида [kind] ([categoryId] = null) или изменение существующей. */
@Serializable data class CategoryEditor(val kind: String, val categoryId: Long? = null)

/** Первый запуск: шаги онбординга, без нижней панели. */
@Serializable data object Onboarding

@Serializable data object Period

@Serializable data object NewPeriod

/** Новый обязательный платёж ([paymentId] = null) или изменение существующего. */
@Serializable data class PlannedPaymentEditor(val paymentId: Long? = null)

/** Статистика завершённого периода поверх разделов. */
@Serializable data class PeriodStats(val periodId: Long)

/** Операции категории за дни статистики (даты — номера дней от эпохи, включительно), поверх разделов. */
@Serializable data class CategoryOperations(val categoryId: Long, val fromEpochDay: Long, val toEpochDay: Long)
