package ru.hznik.budget.domain.repository

import kotlinx.coroutines.flow.Flow
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationItem
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.search.SearchQuery
import ru.hznik.budget.domain.stats.DateRange
import java.time.LocalDate

/** Условия списка операций. `null` в поле означает «без этого фильтра». */
data class OperationFilter(
    val query: SearchQuery = SearchQuery.EMPTY,
    val source: OperationSource? = null,
    val categoryId: Long? = null,
    /** Только операции этих дней — для перехода из статистики к операциям категории. */
    val dateRange: DateRange? = null,
)

interface OperationRepository {
    /** Операции по фильтру, новые сверху. */
    fun observe(filter: OperationFilter): Flow<List<OperationItem>>

    /** Операции начиная с дня [from] включительно — для расчёта периода. */
    fun observeFrom(from: LocalDate): Flow<List<Operation>>

    /** Операции с дня [from] включительно по день [until] не включая — для завершённого периода. */
    fun observeBetween(from: LocalDate, until: LocalDate): Flow<List<Operation>>

    /** Последние [limit] операций, новые сверху. */
    fun observeRecent(limit: Int): Flow<List<OperationItem>>

    /** Число операций по категориям начиная с [since] включительно. */
    fun observeCategoryUsage(since: LocalDate): Flow<Map<Long, Int>>

    suspend fun get(id: Long): Operation?

    suspend fun insert(operation: Operation): Long

    suspend fun update(operation: Operation)

    suspend fun delete(id: Long)

    /** Вставляет удалённую операцию обратно с тем же id. */
    suspend fun restore(operation: Operation)
}
