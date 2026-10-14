package ru.hznik.budget.domain.usecase

import ru.hznik.budget.domain.budget.nextMonthlyDueDate
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.model.PaymentRepeat
import ru.hznik.budget.domain.model.PlannedPayment
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.PlannedPaymentRepository
import java.time.Clock
import java.time.OffsetDateTime
import javax.inject.Inject

sealed interface PaymentSaveResult {
    data class Saved(val id: Long) : PaymentSaveResult
    data object EmptyName : PaymentSaveResult
    data object NameTooLong : PaymentSaveResult
}

/** Новый или изменённый обязательный платёж. Оплаченный платёж сохраняет привязку к трате. */
class SavePlannedPayment @Inject constructor(private val payments: PlannedPaymentRepository) {
    suspend operator fun invoke(id: Long?, draft: PaymentDraft): PaymentSaveResult {
        val name = draft.name.trim()
        if (name.isEmpty()) return PaymentSaveResult.EmptyName
        if (name.length > PlannedPayment.MAX_NAME_LENGTH) return PaymentSaveResult.NameTooLong
        val existing = id?.let { payments.get(it) }
        // День повтора меняется, только если выбрана другая дата: иначе перенесённый на 28 февраля платёж забыл бы про 31-е.
        val repeatDay = existing?.takeIf { it.dueDate == draft.dueDate }?.repeatDay ?: draft.dueDate.dayOfMonth
        val payment = draft.copy(name = name).toPayment(id = existing?.id ?: 0, paidOperationId = existing?.paidOperationId, repeatDay = repeatDay)
        return PaymentSaveResult.Saved(payments.save(payment))
    }
}

class DeletePlannedPayment @Inject constructor(private val payments: PlannedPaymentRepository) {
    suspend operator fun invoke(id: Long) = payments.delete(id)
}

/**
 * «Оплачено»: трата сегодня на сумму платежа в его категории с названием в комментарии (spec planned-payments).
 * Ежемесячный платёж переносится на следующий месяц. Возвращает id траты или `null`, если платёж уже оплачен.
 */
class MarkPaymentPaid @Inject constructor(
    private val payments: PlannedPaymentRepository,
    private val categories: CategoryRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: Long): Long? {
        val payment = payments.get(id) ?: return null
        if (payment.isPaid) return null
        // Как при ручном вводе: трата в категории «Переводы» записывается переводом.
        val type = if (categories.get(payment.categoryId)?.isTransfers == true) OperationType.TRANSFER else OperationType.EXPENSE
        val operation = Operation(
            type = type,
            amount = payment.amount,
            categoryId = payment.categoryId,
            comment = payment.name,
            source = OperationSource.MANUAL,
            occurredAt = OffsetDateTime.now(clock),
        )
        val next = if (payment.repeat == PaymentRepeat.MONTHLY) nextMonthlyDueDate(payment.dueDate, payment.repeatDay) else null
        return payments.markPaid(id, operation, next)
    }
}
