package ru.hznik.budget.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

enum class OperationType {
    EXPENSE,
    INCOME,

    /** Возврат покупки: уменьшает траты, а не увеличивает доходы (FR-03). */
    REFUND,

    /** Исходящий перевод: считается тратой в категории «Переводы» (FR-05). */
    TRANSFER,

    /** Перевод между своими счетами в разных банках: не трата и не доход, в бюджет и статистику не входит. */
    OWN_TRANSFER;

    /** Деньги уходят: трата или перевод. */
    val isOutgoing: Boolean get() = this == EXPENSE || this == TRANSFER

    /** Вид категории, к которой относится операция этого типа. */
    val categoryKind: CategoryKind get() = if (this == INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
}

enum class OperationSource {
    MANUAL,
    NOTIFICATION,

    /** Корректировка по сверке с банком (FR-13): трата или доход на сумму расхождения, только с подтверждения. */
    ADJUSTMENT,
}

/**
 * Операция. [occurredAt] хранит момент вместе со смещением часового пояса, в котором она произошла,
 * поэтому [localDate] не меняется, если позже телефон окажется в другом поясе.
 */
data class Operation(
    val id: Long = 0,
    val type: OperationType,
    val amount: Money,
    val categoryId: Long,
    val merchant: String? = null,
    val comment: String? = null,
    val source: OperationSource,
    val occurredAt: OffsetDateTime,
    /** Черновик из уведомления банка: уже в расчёте, категория не подтверждена (spec drafts). */
    val isDraft: Boolean = false,
    /** Банк и последние 4 цифры карты для операций из уведомлений. */
    val bankId: String? = null,
    val cardLast4: String? = null,
    val notificationKey: String? = null,
    /** Зачисление похоже на стипендию или зарплату: на главной предложить новый период (сценарий 5.8). */
    val suggestsNewPeriod: Boolean = false,
    /** Для перевода себе — каким он пришёл из уведомления (исходящий перевод или зачисление), чтобы отменить склейку. */
    val originalType: OperationType? = null,
    /** Вторая сторона перевода себе, если пришла пара уведомлений из двух банков. */
    val counterBankId: String? = null,
    val counterCardLast4: String? = null,
    val counterNotificationKey: String? = null,
    /** Имя и время из уведомления второй стороны — чтобы разделить пару без потерь. */
    val counterMerchant: String? = null,
    val counterOccurredAt: Instant? = null,
) {
    val localDate: LocalDate get() = occurredAt.toLocalDate()
}

/** Операция вместе с категорией, для списков. */
data class OperationItem(val operation: Operation, val category: Category) {
    /** Название в списке: магазин, иначе комментарий, иначе категория. */
    val title: String get() = operation.merchant ?: operation.comment ?: category.name
}
