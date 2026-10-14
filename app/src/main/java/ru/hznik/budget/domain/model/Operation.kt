package ru.hznik.budget.domain.model

import java.time.LocalDate
import java.time.OffsetDateTime

enum class OperationType {
    EXPENSE,
    INCOME,

    /** Возврат покупки: уменьшает траты, а не увеличивает доходы (FR-03). */
    REFUND,

    /** Исходящий перевод: считается тратой в категории «Переводы» (FR-05). */
    TRANSFER;

    /** Деньги уходят: трата или перевод. */
    val isOutgoing: Boolean get() = this == EXPENSE || this == TRANSFER

    /** Вид категории, к которой относится операция этого типа. */
    val categoryKind: CategoryKind get() = if (this == INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
}

enum class OperationSource { MANUAL, NOTIFICATION }

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
) {
    val localDate: LocalDate get() = occurredAt.toLocalDate()
}

/** Операция вместе с категорией, для списков. */
data class OperationItem(val operation: Operation, val category: Category) {
    /** Название в списке: магазин, иначе комментарий, иначе категория. */
    val title: String get() = operation.merchant ?: operation.comment ?: category.name
}
