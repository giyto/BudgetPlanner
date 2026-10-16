package ru.hznik.budget.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.hznik.budget.domain.reconcile.AutoReconcileSettings
import javax.inject.Inject

/** Переключатель «Автоматическая сверка» в DataStore `settings`, по умолчанию включён. */
class DataStoreAutoReconcileSettings @Inject constructor(private val dataStore: DataStore<Preferences>) : AutoReconcileSettings {
    override val enabled: Flow<Boolean> = dataStore.data.map { it[ENABLED] ?: true }

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[ENABLED] = enabled }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("auto_reconcile")
    }
}

/** Отдельный модуль: в тестах настройка работает на настоящем DataStore из отдельного файла теста. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AutoReconcileBindings {
    @Binds
    abstract fun bindAutoReconcileSettings(impl: DataStoreAutoReconcileSettings): AutoReconcileSettings
}
