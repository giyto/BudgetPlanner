package ru.hznik.budget.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Остатки банка, ключи обработанных уведомлений и образцы (этап add-notification-import). */
@Dao
interface NotificationDao {
    @Insert
    suspend fun insertBalance(balance: BankBalanceEntity): Long

    /** −1, если уведомление с этим ключом уже обрабатывали. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKey(key: NotificationKeyEntity): Long

    @Query("SELECT COUNT(*) FROM notification_keys WHERE `key` = :key")
    suspend fun countKey(key: String): Int

    @Query("DELETE FROM notification_keys WHERE seen_at < :beforeMillis")
    suspend fun deleteKeysBefore(beforeMillis: Long)

    /** Повтор того же уведомления (после переподключения сервиса) не добавляет второй образец. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSample(sample: NotificationSampleEntity): Long

    @Query("SELECT * FROM notification_samples WHERE posted_at >= :sinceMillis ORDER BY posted_at DESC")
    fun observeSamples(sinceMillis: Long): Flow<List<NotificationSampleEntity>>

    @Query("DELETE FROM notification_samples WHERE posted_at < :beforeMillis")
    suspend fun deleteSamplesBefore(beforeMillis: Long)

    @Query("DELETE FROM notification_samples")
    suspend fun deleteAllSamples()
}
