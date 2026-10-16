package ru.hznik.budget.domain.reminders

import ru.hznik.budget.domain.budget.PeriodStatus

/** Что показывает виджет рабочего стола (spec home-widget, design.md add-widget-and-reminders, решение 1). */
sealed interface WidgetSnapshot {
    /** Онбординг не пройден, периода нет или база недоступна: «Откройте BudgetPlanner». */
    data object NoData : WidgetSnapshot

    /** Защита входа и «Прятать сумму на виджете»: «••• ₽» и кнопка «+». */
    data object Hidden : WidgetSnapshot

    /** Суммы в копейках; [spentFraction] — потраченная доля дневного лимита от 0 до 1. */
    data class Shown(
        val canSpendKopecks: Long,
        val dailyLimitKopecks: Long,
        val spentFraction: Float,
        val daysLeft: Long,
    ) : WidgetSnapshot {
        val overspent: Boolean get() = canSpendKopecks < 0
    }

    companion object {
        fun of(status: PeriodStatus?, amountHidden: Boolean): WidgetSnapshot = when {
            status == null -> NoData
            amountHidden -> Hidden
            else -> {
                val limit = status.dailyLimit
                val fraction = if (limit <= 0) 1f else (status.spentToday.toFloat() / limit).coerceIn(0f, 1f)
                Shown(status.canSpendToday, limit, fraction, status.daysLeft)
            }
        }
    }
}
