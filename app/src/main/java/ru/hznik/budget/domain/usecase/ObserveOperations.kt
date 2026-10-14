package ru.hznik.budget.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.hznik.budget.domain.model.OperationItem
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.repository.OperationFilter
import ru.hznik.budget.domain.repository.OperationRepository
import java.time.LocalDate
import javax.inject.Inject

/** Операции одного дня с итогами. */
data class DayGroup(val date: LocalDate, val items: List<OperationItem>) {
    /** Траты и переводы минус возвраты, в копейках. Может быть отрицательным, если возвратов больше. */
    val netSpentKopecks: Long = items.sumOf {
        val op = it.operation
        when {
            op.type.isOutgoing -> op.amount.kopecks
            op.type == OperationType.REFUND -> -op.amount.kopecks
            else -> 0L
        }
    }

    val incomeKopecks: Long = items.filter { it.operation.type == OperationType.INCOME }.sumOf { it.operation.amount.kopecks }
}

class ObserveOperations @Inject constructor(private val operations: OperationRepository) {
    /** Группы по дням, новые дни сверху; порядок внутри дня берётся из репозитория (новые сверху). */
    operator fun invoke(filter: OperationFilter): Flow<List<DayGroup>> =
        operations.observe(filter).map(::groupByDay)
}

fun groupByDay(items: List<OperationItem>): List<DayGroup> =
    items.groupBy { it.operation.localDate }
        .toSortedMap(reverseOrder())
        .map { (date, dayItems) -> DayGroup(date, dayItems) }
