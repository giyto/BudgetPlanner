package ru.hznik.budget.data.notifications

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.hznik.budget.data.local.BankBalanceEntity
import ru.hznik.budget.data.local.NotificationDao
import ru.hznik.budget.data.local.NotificationKeyEntity
import ru.hznik.budget.data.local.NotificationSampleEntity
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.notifications.AutoImportSettings
import ru.hznik.budget.domain.notifications.NotificationRepository
import ru.hznik.budget.domain.notifications.NotificationSample
import ru.hznik.budget.domain.notifications.SampleSettings
import java.time.Instant
import javax.inject.Inject

class RoomNotificationRepository @Inject constructor(private val dao: NotificationDao) : NotificationRepository {
    override fun observeSamples(since: Instant): Flow<List<NotificationSample>> =
        dao.observeSamples(since.toEpochMilli()).map { rows ->
            rows.map { NotificationSample(it.id, it.bankId, Instant.ofEpochMilli(it.postedAt), it.text) }
        }

    override suspend fun addSample(bankId: String, postedAt: Instant, text: String) {
        dao.insertSample(NotificationSampleEntity(bankId = bankId, postedAt = postedAt.toEpochMilli(), text = text))
    }

    override suspend fun deleteSamplesBefore(before: Instant) = dao.deleteSamplesBefore(before.toEpochMilli())

    override suspend fun deleteAllSamples() = dao.deleteAllSamples()

    override suspend fun markSeen(key: String, bankId: String, at: Instant): Boolean =
        dao.insertKey(NotificationKeyEntity(key = key, bankId = bankId, seenAt = at.toEpochMilli())) != -1L

    override suspend fun isSeen(key: String): Boolean = dao.countKey(key) > 0

    override suspend fun deleteKeysBefore(before: Instant) = dao.deleteKeysBefore(before.toEpochMilli())

    override suspend fun addBalance(bankId: String, cardLast4: String?, balance: Money, observedAt: Instant) {
        dao.insertBalance(BankBalanceEntity(bankId = bankId, cardLast4 = cardLast4, balanceKopecks = balance.kopecks, observedAt = observedAt.toEpochMilli()))
    }
}

/** Переключатель режима образцов в DataStore `settings`. */
class DataStoreSampleSettings @Inject constructor(private val dataStore: DataStore<Preferences>) : SampleSettings {
    override val enabled: Flow<Boolean> = dataStore.data.map { it[ENABLED] ?: false }

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[ENABLED] = enabled }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("notification_samples_enabled")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationBindings {
    @Binds
    abstract fun bindNotificationRepository(impl: RoomNotificationRepository): NotificationRepository

    @Binds
    abstract fun bindSampleSettings(impl: DataStoreSampleSettings): SampleSettings

    @Binds
    abstract fun bindAutoImportSettings(impl: DataStoreAutoImportSettings): AutoImportSettings
}

/** Флаги включения автоимпорта в DataStore `settings`. */
class DataStoreAutoImportSettings @Inject constructor(private val dataStore: DataStore<Preferences>) : AutoImportSettings {
    override val wasEnabled: Flow<Boolean> = dataStore.data.map { it[WAS_ENABLED] ?: false }
    override val accessAttempted: Flow<Boolean> = dataStore.data.map { it[ACCESS_ATTEMPTED] ?: false }
    override val restrictedVisited: Flow<Boolean> = dataStore.data.map { it[RESTRICTED_VISITED] ?: false }
    override val autostartDone: Flow<Boolean> = dataStore.data.map { it[AUTOSTART_DONE] ?: false }

    override suspend fun setWasEnabled() {
        dataStore.edit { it[WAS_ENABLED] = true }
    }

    override suspend fun setAccessAttempted() {
        dataStore.edit { it[ACCESS_ATTEMPTED] = true }
    }

    override suspend fun setRestrictedVisited() {
        dataStore.edit { it[RESTRICTED_VISITED] = true }
    }

    override val ownNames: Flow<Set<String>> = dataStore.data.map { it[OWN_NAMES].orEmpty() }

    override suspend fun addOwnName(normalizedName: String) {
        dataStore.edit { it[OWN_NAMES] = it[OWN_NAMES].orEmpty() + normalizedName }
    }

    override suspend fun setAutostartDone() {
        dataStore.edit { it[AUTOSTART_DONE] = true }
    }

    private companion object {
        val WAS_ENABLED = booleanPreferencesKey("auto_import_was_enabled")
        val ACCESS_ATTEMPTED = booleanPreferencesKey("auto_import_access_attempted")
        val RESTRICTED_VISITED = booleanPreferencesKey("auto_import_restricted_visited")
        val AUTOSTART_DONE = booleanPreferencesKey("auto_import_autostart_done")
        val OWN_NAMES = stringSetPreferencesKey("own_transfer_names")
    }
}
