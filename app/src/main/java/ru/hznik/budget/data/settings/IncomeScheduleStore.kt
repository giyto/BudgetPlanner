package ru.hznik.budget.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.hznik.budget.domain.lock.LockSettingsRepository
import ru.hznik.budget.domain.model.IncomeSchedule
import ru.hznik.budget.domain.repository.IncomeScheduleRepository
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope

/** Расписание дохода в DataStore: это настройка, а не данные периода (design.md, решение 3). */
class DataStoreIncomeScheduleRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : IncomeScheduleRepository {
    override fun observe(): Flow<IncomeSchedule?> = dataStore.data.map { prefs ->
        when (prefs[KIND]) {
            REGULAR -> prefs[DAY]?.let { IncomeSchedule.Regular(it) }
            IRREGULAR -> IncomeSchedule.Irregular
            else -> null
        }
    }

    override suspend fun set(schedule: IncomeSchedule) {
        dataStore.edit { prefs ->
            when (schedule) {
                is IncomeSchedule.Regular -> {
                    prefs[KIND] = REGULAR
                    prefs[DAY] = schedule.dayOfMonth
                }
                IncomeSchedule.Irregular -> {
                    prefs[KIND] = IRREGULAR
                    prefs.remove(DAY)
                }
            }
        }
    }

    private companion object {
        val KIND = stringPreferencesKey("income_kind")
        val DAY = intPreferencesKey("income_day")
        const val REGULAR = "REGULAR"
        const val IRREGULAR = "IRREGULAR"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {
    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        createSettingsDataStore { context.preferencesDataStoreFile("settings") }
}

/**
 * DataStore `settings`. Повреждённый файл заменяется настройками по умолчанию с включённой защитой входа, иначе каждое
 * чтение бросало бы исключение и приложение падало бы при каждом запуске. Общий для модуля и теста повреждённого файла.
 */
internal fun createSettingsDataStore(
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    produceFile: () -> File,
): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { DataStoreLockSettingsRepository.afterCorruption() },
        scope = scope,
        produceFile = produceFile,
    )

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsBindings {
    @Binds
    abstract fun bindIncomeScheduleRepository(impl: DataStoreIncomeScheduleRepository): IncomeScheduleRepository

    @Binds
    abstract fun bindLockSettingsRepository(impl: DataStoreLockSettingsRepository): LockSettingsRepository
}
