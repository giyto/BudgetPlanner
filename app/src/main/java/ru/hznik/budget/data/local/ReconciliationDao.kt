package ru.hznik.budget.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Состояние сверки и расчёт ожидаемого остатка (design.md add-balance-reconciliation, решения 1–3). */
@Dao
interface ReconciliationDao {
    @Query("SELECT * FROM reconciliation_accounts ORDER BY bank_id, card_last4")
    fun observeAll(): Flow<List<ReconciliationAccountEntity>>

    @Query("SELECT * FROM reconciliation_accounts WHERE bank_id = :bankId AND card_last4 = :cardLast4")
    suspend fun get(bankId: String, cardLast4: String): ReconciliationAccountEntity?

    /** Вставка или замена по уникальному счёту; [ReconciliationAccountEntity.id] берётся из существующей строки. */
    @Transaction
    suspend fun save(state: ReconciliationAccountEntity) {
        val existing = get(state.bankId, state.cardLast4)
        upsert(state.copy(id = existing?.id ?: 0))
    }

    @Upsert
    suspend fun upsert(state: ReconciliationAccountEntity)

    @Insert
    suspend fun insertOperation(operation: OperationEntity): Long

    /** Корректировка пишется, только если расхождение ещё открыто: повторное решение не создаёт вторую. */
    @Transaction
    suspend fun saveWithAdjustment(adjustment: OperationEntity, state: ReconciliationAccountEntity): Boolean {
        if (get(state.bankId, state.cardLast4)?.pendingBankKopecks == null) return false
        insertOperation(adjustment)
        save(state)
        return true
    }

    /**
     * Изменение остатка счёта в копейках по операциям в полуинтервале (:fromMillis, :toMillis]. Основная сторона:
     * доход и возврат плюс, трата и перевод минус, перевод себе — по исходному типу. Вторая сторона склеенного
     * перевода себе — с обратным знаком по своему времени. Корректировки не входят. `IS` сравнивает с учётом `NULL`:
     * при `:bankId = NULL` это «Счёт без автоимпорта».
     */
    @Query(
        """
        SELECT
            COALESCE((
                SELECT SUM(amount_kopecks * CASE type
                    WHEN 'INCOME' THEN 1 WHEN 'REFUND' THEN 1
                    WHEN 'EXPENSE' THEN -1 WHEN 'TRANSFER' THEN -1
                    WHEN 'OWN_TRANSFER' THEN CASE original_type WHEN 'INCOME' THEN 1 ELSE -1 END
                    ELSE 0 END)
                FROM operations
                WHERE source != 'ADJUSTMENT'
                    AND bank_id IS :bankId
                    AND (card_last4 IS :cardLast4 OR (:includeNoDigits AND card_last4 IS NULL))
                    AND occurred_at > :fromMillis AND occurred_at <= :toMillis
            ), 0)
            + COALESCE((
                SELECT SUM(amount_kopecks * CASE original_type WHEN 'INCOME' THEN -1 ELSE 1 END)
                FROM operations
                WHERE type = 'OWN_TRANSFER' AND counter_bank_id IS NOT NULL
                    AND counter_bank_id IS :bankId
                    AND (counter_card_last4 IS :cardLast4 OR (:includeNoDigits AND counter_card_last4 IS NULL))
                    AND counter_occurred_at > :fromMillis AND counter_occurred_at <= :toMillis
            ), 0)
        """,
    )
    suspend fun netChange(bankId: String?, cardLast4: String?, includeNoDigits: Boolean, fromMillis: Long, toMillis: Long): Long

    @Query(
        """
        SELECT bank_id, card_last4 FROM operations WHERE bank_id IS NOT NULL AND source != 'ADJUSTMENT'
        UNION SELECT counter_bank_id AS bank_id, counter_card_last4 AS card_last4 FROM operations WHERE counter_bank_id IS NOT NULL
        UNION SELECT bank_id, card_last4 FROM bank_balances
        UNION SELECT bank_id, NULLIF(card_last4, '') AS card_last4 FROM reconciliation_accounts WHERE bank_id != ''
        """,
    )
    suspend fun knownAccounts(): List<AccountRow>

    /** Последний остаток по каждому счёту: в SQLite голые колонки при `MAX` берутся из строки с максимумом. */
    @Query("SELECT bank_id, card_last4, balance_kopecks, MAX(observed_at) AS observed_at FROM bank_balances GROUP BY bank_id, card_last4")
    suspend fun latestBalances(): List<LatestBalanceRow>
}
