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

// Экраны поверх разделов, без нижней панели.

/**
 * Ввод новой операции ([operationId] = null) или редактирование существующей. [forAccount] — новая операция счёта
 * сверки [bankId]/[cardLast4] (оба `null` — «Счёт без автоимпорта»), добавленная при поиске пропущенной операции.
 */
@Serializable data class OperationEditor(
    val operationId: Long? = null,
    val forAccount: Boolean = false,
    val bankId: String? = null,
    val cardLast4: String? = null,
)

/** Сверка с банком: счёт расхождения или сразу ручной ввод остатка ([manual]). */
@Serializable data class Reconcile(val bankId: String? = null, val cardLast4: String? = null, val manual: Boolean = false)

/** Операции счёта сверки начиная с момента [fromMillis] — поиск пропущенной операции. */
@Serializable data class AccountOperations(val bankId: String? = null, val cardLast4: String? = null, val fromMillis: Long)

@Serializable data object Settings

/** Экспорт операций в CSV, резервная копия и восстановление (раздел «Данные» в настройках). */
@Serializable data object ExportCsv

@Serializable data object Backup

@Serializable data object Restore

@Serializable data object Categories

/** Правила «магазин → категория». */
@Serializable data object CategoryRules

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
