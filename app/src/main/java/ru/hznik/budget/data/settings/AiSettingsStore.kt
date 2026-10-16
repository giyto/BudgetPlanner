package ru.hznik.budget.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.hznik.budget.domain.ai.AiSettings
import javax.inject.Inject

/** Настройки помощника в DataStore `settings` (design.md add-ai-assistant, решение 5); ключа здесь нет. */
class DataStoreAiSettings @Inject constructor(private val dataStore: DataStore<Preferences>) : AiSettings {
    override val enabled: Flow<Boolean> = dataStore.data.map { it[ENABLED] ?: false }

    override val hideMerchants: Flow<Boolean> = dataStore.data.map { it[HIDE_MERCHANTS] ?: false }

    override val tokensUsed: Flow<Long> = dataStore.data.map { it[TOKENS_USED] ?: 0L }

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[ENABLED] = enabled }
    }

    override suspend fun setHideMerchants(hide: Boolean) {
        dataStore.edit { it[HIDE_MERCHANTS] = hide }
    }

    override suspend fun addTokens(tokens: Long) {
        if (tokens <= 0) return
        dataStore.edit { it[TOKENS_USED] = (it[TOKENS_USED] ?: 0L) + tokens }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("ai_enabled")
        val HIDE_MERCHANTS = booleanPreferencesKey("ai_hide_merchants")
        val TOKENS_USED = longPreferencesKey("ai_tokens_used")
    }
}

/** Отдельный модуль: в тестах настройки работают на настоящем DataStore из отдельного файла теста. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AiSettingsBindings {
    @Binds
    abstract fun bindAiSettings(impl: DataStoreAiSettings): AiSettings
}
