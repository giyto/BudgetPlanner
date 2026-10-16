package ru.hznik.budget.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sort_order, id")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun get(id: Long): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories WHERE kind = :kind AND name_search = :nameSearch AND (:excludeId IS NULL OR id != :excludeId)")
    suspend fun countByName(kind: String, nameSearch: String, excludeId: Long?): Int

    @Query(
        "SELECT EXISTS(SELECT 1 FROM operations WHERE category_id = :id) " +
            "OR EXISTS(SELECT 1 FROM planned_payments WHERE category_id = :id)",
    )
    suspend fun isUsed(id: Long): Boolean

    @Query("SELECT COALESCE(MAX(sort_order), 0) FROM categories WHERE kind = :kind")
    suspend fun maxSortOrder(kind: String): Int

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface OperationDao {
    /**
     * Операции по фильтру, новые сверху. [query] — нормализованная строка с экранированными % и _
     * (пустая — без поиска), [rubles] — целая часть суммы для поиска по числу. Фильтр по счёту — те же правила
     * сопоставления, что в `ReconciliationDao.netChange`.
     */
    @Query(
        """
        SELECT o.*,
            c.id AS c_id, c.kind AS c_kind, c.name AS c_name, c.name_search AS c_name_search,
            c.icon AS c_icon, c.color AS c_color, c.default_key AS c_default_key,
            c.sort_order AS c_sort_order, c.hidden AS c_hidden
        FROM operations o
        JOIN categories c ON c.id = o.category_id
        WHERE (:source IS NULL OR o.source = :source)
            AND (:categoryId IS NULL OR o.category_id = :categoryId)
            AND (:fromEpochDay IS NULL OR o.local_date BETWEEN :fromEpochDay AND :toEpochDay)
            AND (
                NOT :accountActive
                OR (o.bank_id IS :accountBank AND (o.card_last4 IS :accountCard OR (:includeNoDigits AND o.card_last4 IS NULL))
                    AND o.occurred_at >= :accountFromMillis)
                OR (o.counter_bank_id IS NOT NULL AND o.counter_bank_id IS :accountBank
                    AND (o.counter_card_last4 IS :accountCard OR (:includeNoDigits AND o.counter_card_last4 IS NULL))
                    AND o.counter_occurred_at >= :accountFromMillis)
            )
            AND (
                :query = ''
                OR o.search_text LIKE '%' || :query || '%' ESCAPE '\'
                OR c.name_search LIKE '%' || :query || '%' ESCAPE '\'
                OR (:rubles IS NOT NULL AND o.amount_kopecks / 100 = :rubles)
            )
        ORDER BY o.occurred_at DESC, o.id DESC
        """,
    )
    fun observe(
        query: String,
        rubles: Long?,
        source: String?,
        categoryId: Long?,
        fromEpochDay: Long?,
        toEpochDay: Long?,
        accountActive: Boolean = false,
        accountBank: String? = null,
        accountCard: String? = null,
        includeNoDigits: Boolean = false,
        accountFromMillis: Long = 0,
    ): Flow<List<OperationWithCategory>>

    @Query("SELECT category_id, COUNT(*) AS count FROM operations WHERE local_date >= :sinceEpochDay GROUP BY category_id")
    fun observeCategoryUsage(sinceEpochDay: Long): Flow<List<CategoryUsage>>

    /** Операции начиная с дня [fromEpochDay] — для расчёта периода. */
    @Query("SELECT * FROM operations WHERE local_date >= :fromEpochDay ORDER BY occurred_at")
    fun observeFrom(fromEpochDay: Long): Flow<List<OperationEntity>>

    @Query("SELECT * FROM operations WHERE local_date >= :fromEpochDay AND local_date < :toEpochDay ORDER BY occurred_at")
    fun observeBetween(fromEpochDay: Long, toEpochDay: Long): Flow<List<OperationEntity>>

    @Query("SELECT * FROM operations WHERE local_date >= :fromEpochDay AND local_date < :toEpochDay")
    suspend fun getBetween(fromEpochDay: Long, toEpochDay: Long): List<OperationEntity>

    @Query(
        """
        SELECT o.*,
            c.id AS c_id, c.kind AS c_kind, c.name AS c_name, c.name_search AS c_name_search,
            c.icon AS c_icon, c.color AS c_color, c.default_key AS c_default_key,
            c.sort_order AS c_sort_order, c.hidden AS c_hidden
        FROM operations o
        JOIN categories c ON c.id = o.category_id
        ORDER BY o.occurred_at DESC, o.id DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<OperationWithCategory>>

    @Query("SELECT * FROM operations WHERE id = :id")
    suspend fun get(id: Long): OperationEntity?

    @Insert
    suspend fun insert(operation: OperationEntity): Long

    @Update
    suspend fun update(operation: OperationEntity)

    @Query("DELETE FROM operations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        """
        SELECT o.*,
            c.id AS c_id, c.kind AS c_kind, c.name AS c_name, c.name_search AS c_name_search,
            c.icon AS c_icon, c.color AS c_color, c.default_key AS c_default_key,
            c.sort_order AS c_sort_order, c.hidden AS c_hidden
        FROM operations o
        JOIN categories c ON c.id = o.category_id
        WHERE o.is_draft = 1
        ORDER BY o.occurred_at DESC, o.id DESC
        """,
    )
    fun observeDrafts(): Flow<List<OperationWithCategory>>

    @Query("UPDATE operations SET category_id = :categoryId, type = :type, is_draft = 0 WHERE id = :id AND is_draft = 1")
    suspend fun confirmDraft(id: Long, categoryId: Long, type: String)

    @Transaction
    suspend fun confirmDrafts(items: List<Triple<Long, Long, String>>) {
        items.forEach { (id, categoryId, type) -> confirmDraft(id, categoryId, type) }
    }

    /** Ключ любой из сторон, в том числе второй стороны склеенного перевода себе. */
    @Query("SELECT COUNT(*) FROM operations WHERE notification_key = :key OR counter_notification_key = :key")
    suspend fun countByNotificationKey(key: String): Int

    /**
     * Кандидаты в дубли: операции этого банка или склеенные переводы, где этот банк — вторая сторона. Тип и магазин
     * сравниваются в коде: NOCASE в SQLite не знает кириллицы, а у склеенной пары тип стороны хранится отдельно.
     */
    @Query(
        """
        SELECT * FROM operations
        WHERE (bank_id = :bankId OR counter_bank_id = :bankId) AND amount_kopecks = :amountKopecks
            AND occurred_at BETWEEN :fromMillis AND :toMillis
        """,
    )
    suspend fun importCandidates(bankId: String, amountKopecks: Long, fromMillis: Long, toMillis: Long): List<OperationEntity>

    @Query(
        """
        SELECT * FROM operations
        WHERE bank_id IS NOT NULL AND bank_id != :otherThanBank AND amount_kopecks = :amountKopecks
            AND occurred_at BETWEEN :fromMillis AND :toMillis
            AND ((type = :type AND is_draft = 1) OR (type = 'OWN_TRANSFER' AND original_type = :type AND counter_bank_id IS NULL))
        ORDER BY ABS(occurred_at - (:fromMillis + :toMillis) / 2) LIMIT 1
        """,
    )
    suspend fun findTransferCounterpart(otherThanBank: String, type: String, amountKopecks: Long, fromMillis: Long, toMillis: Long): OperationEntity?

    @Query("SELECT * FROM operations WHERE suggests_new_period = 1 ORDER BY occurred_at DESC LIMIT 1")
    fun observeNewPeriodSuggestion(): Flow<OperationEntity?>

    @Query("UPDATE operations SET suggests_new_period = 0 WHERE suggests_new_period = 1")
    suspend fun clearNewPeriodSuggestion()
}

@Dao
interface PeriodDao {
    @Query("SELECT * FROM periods WHERE end_date IS NULL ORDER BY id DESC LIMIT 1")
    fun observeCurrent(): Flow<PeriodEntity?>

    @Query("SELECT * FROM periods WHERE end_date IS NULL ORDER BY id DESC LIMIT 1")
    suspend fun getCurrent(): PeriodEntity?

    @Query("SELECT * FROM periods ORDER BY start_date")
    suspend fun getAll(): List<PeriodEntity>

    /** Завершённые периоды, новые сверху; периоды нулевой длины (новый начат в тот же день) не показываются. */
    @Query("SELECT * FROM periods WHERE end_date IS NOT NULL AND end_date > start_date ORDER BY start_date DESC")
    fun observeFinished(): Flow<List<PeriodEntity>>

    @Query("DELETE FROM periods WHERE id = :id")
    suspend fun delete(id: Long)

    /** Закрывает текущий период: он заканчивается в день начала следующего. */
    @Query("UPDATE periods SET end_date = :endEpochDay WHERE end_date IS NULL")
    suspend fun closeCurrent(endEpochDay: Long)

    @Insert
    suspend fun insert(period: PeriodEntity): Long

    @Update
    suspend fun update(period: PeriodEntity)
}

@Dao
interface PlannedPaymentDao {
    @Query(
        """
        SELECT p.*, o.local_date AS paid_local_date
        FROM planned_payments p
        LEFT JOIN operations o ON o.id = p.paid_operation_id
        ORDER BY p.due_date, p.id
        """,
    )
    fun observeAll(): Flow<List<PlannedPaymentWithPaidDate>>

    @Query("SELECT p.*, o.local_date AS paid_local_date FROM planned_payments p LEFT JOIN operations o ON o.id = p.paid_operation_id WHERE p.id = :id")
    suspend fun get(id: Long): PlannedPaymentWithPaidDate?

    @Insert
    suspend fun insert(payment: PlannedPaymentEntity): Long

    @Update
    suspend fun update(payment: PlannedPaymentEntity)

    @Query(
        "SELECT COUNT(*) FROM planned_payments WHERE name = :name AND category_id = :categoryId " +
            "AND due_date = :dueEpochDay AND repeat = :repeat AND paid_operation_id IS NULL",
    )
    suspend fun countUnpaid(name: String, categoryId: Long, dueEpochDay: Long, repeat: String): Int

    @Query("DELETE FROM planned_payments WHERE id = :id")
    suspend fun delete(id: Long)
}
