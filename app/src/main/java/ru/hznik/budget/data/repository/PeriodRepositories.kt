package ru.hznik.budget.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.hznik.budget.data.local.BudgetDatabase
import ru.hznik.budget.data.local.OperationDao
import ru.hznik.budget.data.local.PeriodDao
import ru.hznik.budget.data.local.PeriodEntity
import ru.hznik.budget.data.local.PlannedPaymentDao
import ru.hznik.budget.data.local.PlannedPaymentEntity
import ru.hznik.budget.data.local.PlannedPaymentWithPaidDate
import ru.hznik.budget.data.local.toEntity
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.PaymentRepeat
import ru.hznik.budget.domain.model.Period
import ru.hznik.budget.domain.model.PlannedPayment
import ru.hznik.budget.domain.repository.PeriodRepository
import ru.hznik.budget.domain.repository.PlannedPaymentRepository
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

class RoomPeriodRepository @Inject constructor(
    private val db: BudgetDatabase,
    private val dao: PeriodDao,
    private val paymentDao: PlannedPaymentDao,
) : PeriodRepository {
    override fun observeCurrent(): Flow<Period?> = dao.observeCurrent().map { it?.toDomain() }

    override suspend fun getCurrent(): Period? = dao.getCurrent()?.toDomain()

    override suspend fun startNew(start: LocalDate, nextIncome: LocalDate, startAmountKopecks: Long, payments: List<PlannedPayment>): Long =
        db.withTransaction {
            // Текущий период заканчивается в день начала нового, поэтому текущий всегда один.
            dao.closeCurrent(start.toEpochDay())
            val id = dao.insert(
                PeriodEntity(startDate = start.toEpochDay(), nextIncomeDate = nextIncome.toEpochDay(), endDate = null, startAmountKopecks = startAmountKopecks),
            )
            payments.forEach { paymentDao.insert(it.copy(id = 0).toEntity()) }
            id
        }

    override suspend fun update(period: Period) = dao.update(period.toEntity())
}

class RoomPlannedPaymentRepository @Inject constructor(
    private val db: BudgetDatabase,
    private val dao: PlannedPaymentDao,
    private val operationDao: OperationDao,
    private val clock: Clock,
) : PlannedPaymentRepository {
    override fun observeAll(): Flow<List<PlannedPayment>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun get(id: Long): PlannedPayment? = dao.get(id)?.toDomain()

    override suspend fun save(payment: PlannedPayment): Long =
        if (payment.id == 0L) dao.insert(payment.toEntity()) else payment.id.also { dao.update(payment.toEntity()) }

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun markPaid(id: Long, operation: Operation, nextDueDate: LocalDate?): Long? = db.withTransaction {
        val payment = dao.get(id)?.payment ?: return@withTransaction null
        if (payment.paidOperationId != null) return@withTransaction null
        val operationId = operationDao.insert(operation.copy(id = 0).toEntity(createdAt = clock.millis()))
        dao.update(payment.copy(paidOperationId = operationId))
        // Повторная оплата после удаления траты не создаёт второй платёж на следующий месяц.
        if (nextDueDate != null && dao.countUnpaid(payment.name, payment.categoryId, nextDueDate.toEpochDay(), payment.repeat) == 0) {
            dao.insert(payment.copy(id = 0, dueDate = nextDueDate.toEpochDay(), paidOperationId = null))
        }
        operationId
    }
}

private fun PeriodEntity.toDomain() = Period(
    id = id,
    startDate = LocalDate.ofEpochDay(startDate),
    nextIncomeDate = LocalDate.ofEpochDay(nextIncomeDate),
    startAmountKopecks = startAmountKopecks,
    endDate = endDate?.let(LocalDate::ofEpochDay),
)

private fun Period.toEntity() = PeriodEntity(
    id = id,
    startDate = startDate.toEpochDay(),
    nextIncomeDate = nextIncomeDate.toEpochDay(),
    endDate = endDate?.toEpochDay(),
    startAmountKopecks = startAmountKopecks,
)

private fun PlannedPaymentWithPaidDate.toDomain() = PlannedPayment(
    id = payment.id,
    name = payment.name,
    amount = Money(payment.amountKopecks),
    dueDate = LocalDate.ofEpochDay(payment.dueDate),
    repeat = PaymentRepeat.valueOf(payment.repeat),
    repeatDay = payment.repeatDay,
    categoryId = payment.categoryId,
    paidOperationId = payment.paidOperationId,
    paidOn = paidLocalDate?.let(LocalDate::ofEpochDay),
)

private fun PlannedPayment.toEntity() = PlannedPaymentEntity(
    id = id,
    name = name,
    amountKopecks = amount.kopecks,
    dueDate = dueDate.toEpochDay(),
    repeat = repeat.name,
    repeatDay = repeatDay,
    categoryId = categoryId,
    paidOperationId = paidOperationId,
)
