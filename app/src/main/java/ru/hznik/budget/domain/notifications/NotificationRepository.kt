package ru.hznik.budget.domain.notifications

import kotlinx.coroutines.flow.Flow
import ru.hznik.budget.domain.model.Money
import java.time.Instant

/** Образцы, ключи обработанных уведомлений и остатки банка. */
interface NotificationRepository {
    /** Образцы не старше [since], новые сверху. */
    fun observeSamples(since: Instant): Flow<List<NotificationSample>>

    /** Повтор того же уведомления (тот же банк, время и текст) не добавляет второй образец. */
    suspend fun addSample(bankId: String, postedAt: Instant, text: String)

    suspend fun deleteSamplesBefore(before: Instant)

    suspend fun deleteAllSamples()

    /** Запоминает ключ уведомления; `false`, если его уже обрабатывали. */
    suspend fun markSeen(key: String, bankId: String, at: Instant): Boolean

    suspend fun isSeen(key: String): Boolean

    suspend fun deleteKeysBefore(before: Instant)

    suspend fun addBalance(bankId: String, cardLast4: String?, balance: Money, observedAt: Instant)
}

/** Включён ли режим сбора образцов (только отладочная сборка, по умолчанию выключен). */
interface SampleSettings {
    val enabled: Flow<Boolean>

    suspend fun setEnabled(enabled: Boolean)
}
