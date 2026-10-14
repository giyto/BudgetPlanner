package ru.hznik.budget.domain.model

import java.time.LocalDate

/**
 * Период бюджета: от [startDate] включительно до [nextIncomeDate] не включительно.
 * У текущего периода [endDate] = null; у прошлых — дата начала следующего периода.
 */
data class Period(
    val id: Long = 0,
    val startDate: LocalDate,
    val nextIncomeDate: LocalDate,
    val startAmountKopecks: Long,
    val endDate: LocalDate? = null,
)

/** Как приходит доход: решение по открытому вопросу 3 раздела 17 ТЗ — поддерживаются оба варианта. */
sealed interface IncomeSchedule {
    /** Каждый месяц [dayOfMonth] числа; в коротком месяце — последний день. */
    data class Regular(val dayOfMonth: Int) : IncomeSchedule {
        init {
            require(dayOfMonth in 1..31) { "День месяца вне диапазона: $dayOfMonth" }
        }
    }

    /** Дата следующего дохода вводится каждый раз. */
    data object Irregular : IncomeSchedule
}

enum class PaymentRepeat { ONCE, MONTHLY }

/**
 * Обязательный платёж. Оплачен, если есть [paidOperationId]; [paidOn] — день траты оплаты.
 */
data class PlannedPayment(
    val id: Long = 0,
    val name: String,
    val amount: Money,
    val dueDate: LocalDate,
    val repeat: PaymentRepeat,
    val categoryId: Long,
    val paidOperationId: Long? = null,
    val paidOn: LocalDate? = null,
    /** День месяца ежемесячного платежа: после короткого месяца платёж возвращается на него (31 → 28 февраля → 31 марта). */
    val repeatDay: Int = dueDate.dayOfMonth,
) {
    val isPaid: Boolean get() = paidOperationId != null

    companion object {
        const val MAX_NAME_LENGTH = 40
    }
}
