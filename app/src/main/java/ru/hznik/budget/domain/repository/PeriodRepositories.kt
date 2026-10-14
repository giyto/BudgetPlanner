package ru.hznik.budget.domain.repository

import kotlinx.coroutines.flow.Flow
import ru.hznik.budget.domain.model.IncomeSchedule
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.Period
import ru.hznik.budget.domain.model.PlannedPayment
import java.time.LocalDate

interface PeriodRepository {
    fun observeCurrent(): Flow<Period?>

    suspend fun getCurrent(): Period?

    /** Завершённые периоды, новые сверху. */
    fun observeFinished(): Flow<List<Period>>

    /** Начинает период с [start]: текущий закрывается этой же датой, всё в одной транзакции. */
    suspend fun startNew(start: LocalDate, nextIncome: LocalDate, startAmountKopecks: Long, payments: List<PlannedPayment> = emptyList()): Long

    suspend fun update(period: Period)
}

interface PlannedPaymentRepository {
    /** Все платежи по дате, у оплаченных заполнен день оплаты. */
    fun observeAll(): Flow<List<PlannedPayment>>

    suspend fun get(id: Long): PlannedPayment?

    /** Новый ([PlannedPayment.id] = 0) или изменённый платёж. */
    suspend fun save(payment: PlannedPayment): Long

    suspend fun delete(id: Long)

    /**
     * В одной транзакции записывает трату [operation], привязывает её к платежу и для ежемесячного платежа создаёт
     * следующий на [nextDueDate]. Возвращает id траты или `null`, если платежа нет или он уже оплачен.
     */
    suspend fun markPaid(id: Long, operation: Operation, nextDueDate: LocalDate?): Long?
}

interface IncomeScheduleRepository {
    /** `null`, пока расписание не задано (онбординг не пройден). */
    fun observe(): Flow<IncomeSchedule?>

    suspend fun set(schedule: IncomeSchedule)
}
