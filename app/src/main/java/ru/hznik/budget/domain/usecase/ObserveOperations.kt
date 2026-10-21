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

/** Порция списка операций: [hasMore] — в базе есть операции старше показанных. */
data class OperationsPage(val groups: List<DayGroup>, val hasMore: Boolean)

class ObserveOperations @Inject constructor(private val operations: OperationRepository) {
    /** Группы по дням, новые дни сверху; порядок внутри дня берётся из репозитория (новые сверху). */
    operator fun invoke(filter: OperationFilter): Flow<List<DayGroup>> =
        operations.observe(filter).map(::groupByDay)

    /**
     * Не больше [OperationFilter.limit] новых операций (задача 7.2: 50 000 операций не читаются разом). Последний день
     * порции может быть неполным, поэтому он не показывается, пока не догружен: итог дня в заголовке всегда верный.
     */
    fun page(filter: OperationFilter): Flow<OperationsPage> =
        operations.observe(filter).map { items ->
            val groups = groupByDay(items)
            val hasMore = filter.limit != null && items.size >= filter.limit
            OperationsPage(if (hasMore && groups.size > 1) groups.dropLast(1) else groups, hasMore)
        }
}

fun groupByDay(items: List<OperationItem>): List<DayGroup> =
    items.groupBy { it.operation.localDate }
        .toSortedMap(reverseOrder())
        .map { (date, dayItems) -> DayGroup(date, dayItems) }
