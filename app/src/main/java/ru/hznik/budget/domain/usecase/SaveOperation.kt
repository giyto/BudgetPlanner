package ru.hznik.budget.domain.usecase

import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.OperationRepository
import ru.hznik.budget.domain.time.today
import java.time.Clock
import java.time.OffsetDateTime
import javax.inject.Inject

/** Тип, который пользователь выбирает при ручном вводе. Перевод получается из траты с категорией «Переводы». */
enum class InputType(val operationType: OperationType) {
    EXPENSE(OperationType.EXPENSE),
    INCOME(OperationType.INCOME),
    REFUND(OperationType.REFUND);

    companion object {
        fun of(type: OperationType): InputType = when (type) {
            OperationType.EXPENSE, OperationType.TRANSFER -> EXPENSE
            OperationType.INCOME -> INCOME
            OperationType.REFUND -> REFUND
        }
    }
}

/** Данные с экрана ввода. [id] `null` — новая операция. */
data class OperationDraft(
    val id: Long? = null,
    val inputType: InputType,
    val amount: Money?,
    val categoryId: Long?,
    val comment: String,
    val occurredAt: OffsetDateTime,
)

sealed interface SaveResult {
    data class Saved(val id: Long) : SaveResult
    data class Invalid(val error: SaveError) : SaveResult
}

enum class SaveError { MISSING_AMOUNT, MISSING_CATEGORY, CATEGORY_KIND_MISMATCH, FUTURE_DATE, COMMENT_TOO_LONG, NOT_FOUND }

/** Проверяет ввод по spec `operations` и сохраняет операцию. */
class SaveOperation @Inject constructor(
    private val operations: OperationRepository,
    private val categories: CategoryRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(draft: OperationDraft): SaveResult {
        val amount = draft.amount ?: return invalid(SaveError.MISSING_AMOUNT)
        val categoryId = draft.categoryId ?: return invalid(SaveError.MISSING_CATEGORY)
        val category = categories.get(categoryId) ?: return invalid(SaveError.MISSING_CATEGORY)
        if (category.kind != draft.inputType.operationType.categoryKind) return invalid(SaveError.CATEGORY_KIND_MISMATCH)
        if (draft.occurredAt.toLocalDate().isAfter(clock.today())) return invalid(SaveError.FUTURE_DATE)
        val comment = draft.comment.trim().ifEmpty { null }
        if (comment != null && comment.length > MAX_COMMENT_LENGTH) return invalid(SaveError.COMMENT_TOO_LONG)

        val type = if (draft.inputType == InputType.EXPENSE && category.isTransfers) OperationType.TRANSFER else draft.inputType.operationType

        if (draft.id == null) {
            val operation = Operation(
                type = type,
                amount = amount,
                categoryId = categoryId,
                comment = comment,
                source = OperationSource.MANUAL,
                occurredAt = draft.occurredAt,
            )
            return SaveResult.Saved(operations.insert(operation))
        }

        // При редактировании источник и магазин остаются прежними.
        val existing = operations.get(draft.id) ?: return invalid(SaveError.NOT_FOUND)
        operations.update(
            existing.copy(type = type, amount = amount, categoryId = categoryId, comment = comment, occurredAt = draft.occurredAt),
        )
        return SaveResult.Saved(existing.id)
    }

    private fun invalid(error: SaveError) = SaveResult.Invalid(error)

    companion object {
        const val MAX_COMMENT_LENGTH = 200
    }
}
