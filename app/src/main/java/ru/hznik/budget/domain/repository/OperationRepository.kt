package ru.hznik.budget.domain.repository

import kotlinx.coroutines.flow.Flow
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationItem
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.reconcile.ReconAccount
import ru.hznik.budget.domain.search.SearchQuery
import ru.hznik.budget.domain.stats.DateRange
import java.time.Instant
import java.time.LocalDate

/** Условия списка операций. `null` в поле означает «без этого фильтра». */
data class OperationFilter(
    val query: SearchQuery = SearchQuery.EMPTY,
    val source: OperationSource? = null,
    val categoryId: Long? = null,
    /** Только операции этих дней — для перехода из статистики к операциям категории. */
    val dateRange: DateRange? = null,
    /** Только операции счёта сверки начиная с момента — для поиска пропущенной операции (spec operations «Фильтр по счёту»). */
    val account: AccountFilter? = null,
)

/** Операции счёта, включая обе стороны перевода себе, не раньше [from]. [includeNoDigits] — как в сверке. */
data class AccountFilter(val account: ReconAccount, val from: Instant, val includeNoDigits: Boolean)

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

    /** Черновики из уведомлений банка, новые сверху (spec drafts). */
    fun observeDrafts(): Flow<List<OperationItem>>

    /** Подтверждает категории черновиков одной транзакцией. */
    suspend fun confirmDrafts(confirmations: List<DraftConfirmation>)

    /**
     * Обновляет операцию и добавляет вторую ([counterpart], если есть) одной транзакцией: при сбое между записями
     * данные второй стороны склеенного перевода не теряются.
     */
    suspend fun updateAndInsert(updated: Operation, counterpart: Operation?)

    /** Операция с тем же ключом уведомления уже есть. */
    suspend fun existsByNotificationKey(key: String): Boolean

    /** Операция того же банка с той же суммой, типом и магазином в окне [from]–[to] (дедупликация FR-05). */
    suspend fun existsSimilarImport(bankId: String, type: OperationType, amount: Money, merchant: String?, from: Instant, to: Instant): Boolean

    /** Последнее зачисление, после которого стоит предложить новый период (сценарий 5.8). */
    fun observeNewPeriodSuggestion(): Flow<Operation?>

    suspend fun clearNewPeriodSuggestion()

    /** Вторая сторона перевода себе: операция другого банка нужного типа на ту же сумму в окне [from]–[to]. */
    suspend fun findTransferCounterpart(otherThanBank: String, type: OperationType, amount: Money, from: Instant, to: Instant): Operation?
}

/** Категория, выбранная пользователем для черновика, и тип операции под неё (перевод или трата). */
data class DraftConfirmation(val operationId: Long, val categoryId: Long, val type: OperationType)
