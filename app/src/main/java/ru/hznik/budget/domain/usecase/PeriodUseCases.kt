package ru.hznik.budget.domain.usecase

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import ru.hznik.budget.domain.budget.BudgetCalculator
import ru.hznik.budget.domain.budget.PeriodStatus
import ru.hznik.budget.domain.budget.nextIncomeDate
import ru.hznik.budget.domain.model.IncomeSchedule
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.model.Period
import ru.hznik.budget.domain.model.PaymentRepeat
import ru.hznik.budget.domain.model.PlannedPayment
import ru.hznik.budget.domain.repository.IncomeScheduleRepository
import ru.hznik.budget.domain.repository.OperationRepository
import ru.hznik.budget.domain.repository.PeriodRepository
import ru.hznik.budget.domain.repository.PlannedPaymentRepository
import ru.hznik.budget.domain.time.today
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/**
 * Текущая дата: выдаётся сразу при подписке и снова сразу после каждой полуночи по часам [clock].
 * Пересчёт «при открытии» даёт новая подписка экрана, «в полночь» — эта задержка (design.md, решение 5).
 */
class TodayTicker @Inject constructor(private val clock: Clock) {
    fun today(): Flow<LocalDate> = flow {
        while (true) {
            val today = clock.today()
            emit(today)
            val nextMidnight = today.plusDays(1).atStartOfDay(clock.zone).toInstant().toEpochMilli()
            delay(maxOf(1L, nextMidnight - clock.millis()))
        }
    }.distinctUntilChanged()
}

/** Статус текущего периода по FR-03; `null`, пока периода нет (онбординг не пройден). */
@OptIn(ExperimentalCoroutinesApi::class)
class ObservePeriodStatus @Inject constructor(
    private val periods: PeriodRepository,
    private val operations: OperationRepository,
    private val payments: PlannedPaymentRepository,
    private val ticker: TodayTicker,
) {
    operator fun invoke(): Flow<PeriodStatus?> = periods.observeCurrent().flatMapLatest { period ->
        if (period == null) {
            flowOf(null)
        } else {
            combine(operations.observeFrom(period.startDate), payments.observeAll(), ticker.today()) { ops, pays, today ->
                BudgetCalculator.calculate(period, ops, pays, today)
            }
        }
    }
}

/** Онбординг пройден, если есть текущий период и расписание дохода (design.md, решение 3). */
class ObserveOnboardingDone @Inject constructor(
    private val periods: PeriodRepository,
    private val schedule: IncomeScheduleRepository,
) {
    operator fun invoke(): Flow<Boolean> =
        combine(periods.observeCurrent(), schedule.observe()) { period, income -> period != null && income != null }
            .distinctUntilChanged()
}

/** Данные платежа с экрана ввода. */
data class PaymentDraft(
    val name: String,
    val amount: Money,
    val dueDate: LocalDate,
    val repeat: PaymentRepeat,
    val categoryId: Long,
)

sealed interface PeriodSetupResult {
    data object Done : PeriodSetupResult
    data object NegativeAmount : PeriodSetupResult
    data object IncomeDateNotInFuture : PeriodSetupResult
}

/** Дата следующего дохода: при регулярном доходе подставляется, при нерегулярном берётся введённая. */
fun resolveNextIncome(schedule: IncomeSchedule, chosen: LocalDate?, after: LocalDate): LocalDate? = when (schedule) {
    is IncomeSchedule.Regular -> chosen ?: nextIncomeDate(after, schedule.dayOfMonth)
    IncomeSchedule.Irregular -> chosen
}

/** Конец онбординга: период с сегодняшнего дня, обязательные платежи и расписание дохода. */
class CompleteOnboarding @Inject constructor(
    private val periods: PeriodRepository,
    private val schedule: IncomeScheduleRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        amountKopecks: Long,
        income: IncomeSchedule,
        chosenNextIncome: LocalDate?,
        paymentDrafts: List<PaymentDraft>,
    ): PeriodSetupResult {
        val today = clock.today()
        if (amountKopecks < 0) return PeriodSetupResult.NegativeAmount
        val nextIncome = resolveNextIncome(income, chosenNextIncome, today)
        if (nextIncome == null || !nextIncome.isAfter(today)) return PeriodSetupResult.IncomeDateNotInFuture
        // Онбординг пройден, когда есть и расписание, и период. Расписание пишется первым, а период с платежами —
        // одной транзакцией последним: если процесс прервётся, повтор не задвоит ни период, ни платежи.
        schedule.set(income)
        periods.startNew(today, nextIncome, amountKopecks, paymentDrafts.map { it.toPayment() })
        return PeriodSetupResult.Done
    }
}

/** Подсказки экрана «Новый период». */
data class NewPeriodSuggestion(
    val amountKopecks: Long,
    /** Доходы, уже записанные сегодня: они войдут в новый период сами. */
    val incomeTodayKopecks: Long,
    val nextIncome: LocalDate?,
    val schedule: IncomeSchedule?,
)

class SuggestNewPeriod @Inject constructor(
    private val periods: PeriodRepository,
    private val operations: OperationRepository,
    private val schedule: IncomeScheduleRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(): NewPeriodSuggestion {
        val today = clock.today()
        val income = schedule.observe().first()
        val period = periods.getCurrent()
        val ops = period?.let { operations.observeFrom(it.startDate).first() }.orEmpty()
        return NewPeriodSuggestion(
            amountKopecks = period?.let { BudgetCalculator.remainderBefore(it, ops, today) }?.coerceAtLeast(0) ?: 0,
            incomeTodayKopecks = ops.filter { it.localDate == today && it.type == OperationType.INCOME }.sumOf { it.amount.kopecks },
            nextIncome = income?.let { resolveNextIncome(it, null, today) },
            schedule = income,
        )
    }
}

/** Новый период с сегодняшнего дня; текущий закрывается этой датой и остаётся в истории. */
class StartNewPeriod @Inject constructor(
    private val periods: PeriodRepository,
    private val operations: OperationRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(amountKopecks: Long, nextIncome: LocalDate?): PeriodSetupResult {
        val today = clock.today()
        if (amountKopecks < 0) return PeriodSetupResult.NegativeAmount
        if (nextIncome == null || !nextIncome.isAfter(today)) return PeriodSetupResult.IncomeDateNotInFuture
        // Предложение «Пришло N ₽ — начать новый период?» больше не нужно (сценарий 5.8). Снимается раньше
        // нового периода: иначе проверка уведомлений между двумя записями увидит новый период с этим предложением.
        operations.clearNewPeriodSuggestion()
        periods.startNew(today, nextIncome, amountKopecks)
        return PeriodSetupResult.Done
    }
}

/** Изменение стартовой суммы и даты дохода текущего периода. */
class UpdateCurrentPeriod @Inject constructor(private val periods: PeriodRepository) {
    suspend operator fun invoke(amountKopecks: Long, nextIncome: LocalDate): PeriodSetupResult {
        val current = periods.getCurrent() ?: return PeriodSetupResult.Done
        if (amountKopecks < 0) return PeriodSetupResult.NegativeAmount
        if (!nextIncome.isAfter(current.startDate)) return PeriodSetupResult.IncomeDateNotInFuture
        periods.update(current.copy(startAmountKopecks = amountKopecks, nextIncomeDate = nextIncome))
        return PeriodSetupResult.Done
    }
}

internal fun PaymentDraft.toPayment(id: Long = 0, paidOperationId: Long? = null, repeatDay: Int = dueDate.dayOfMonth) = PlannedPayment(
    id = id, name = name.trim(), amount = amount, dueDate = dueDate, repeat = repeat, categoryId = categoryId,
    paidOperationId = paidOperationId, repeatDay = repeatDay,
)

/** Текущий период (для экранов, которым нужен сам период, а не расчёт). */
class ObserveCurrentPeriod @Inject constructor(private val periods: PeriodRepository) {
    operator fun invoke(): Flow<Period?> = periods.observeCurrent()
}
