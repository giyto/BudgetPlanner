package ru.hznik.budget.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.hznik.budget.domain.lock.LockSettings
import ru.hznik.budget.domain.lock.LockSettingsRepository
import ru.hznik.budget.domain.lock.LockTimeout
import javax.inject.Inject

/** Настройки защиты входа в DataStore `settings` (design.md add-data-protection, решение 7). */
class DataStoreLockSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : LockSettingsRepository {
    override fun observe(): Flow<LockSettings> = dataStore.data.map { prefs ->
        LockSettings(
            enabled = prefs[ENABLED] ?: false,
            timeout = prefs[TIMEOUT]?.let { name -> LockTimeout.entries.firstOrNull { it.name == name } } ?: LockTimeout.ONE_MINUTE,
            hideInRecents = prefs[HIDE_IN_RECENTS] ?: false,
        )
    }

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[ENABLED] = enabled }
    }

    override suspend fun setTimeout(timeout: LockTimeout) {
        dataStore.edit { it[TIMEOUT] = timeout.name }
    }

    override suspend fun setHideInRecents(hide: Boolean) {
        dataStore.edit { it[HIDE_IN_RECENTS] = hide }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("lock_enabled")
        val TIMEOUT = stringPreferencesKey("lock_timeout")
        val HIDE_IN_RECENTS = booleanPreferencesKey("lock_hide_in_recents")
    }
}
