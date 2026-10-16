package ru.hznik.budget.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ru.hznik.budget.domain.reminders.NotificationLog
import ru.hznik.budget.domain.reminders.NotificationSettings
import ru.hznik.budget.domain.reminders.NotificationType
import java.time.LocalDate
import javax.inject.Inject

/**
 * Переключатели типов уведомлений и журнал отправленного в DataStore `settings` (design.md add-widget-and-reminders,
 * решения 3 и 7). Хранятся выключенные типы, поэтому по умолчанию включены все.
 */
class DataStoreNotificationSettings @Inject constructor(private val dataStore: DataStore<Preferences>) : NotificationSettings {
    override val enabled: Flow<Set<NotificationType>> = dataStore.data.map { prefs ->
        val disabled = prefs[DISABLED].orEmpty()
        NotificationType.entries.filterTo(mutableSetOf()) { it.name !in disabled }
    }

    override suspend fun setEnabled(type: NotificationType, enabled: Boolean) {
        dataStore.edit { prefs ->
            val disabled = prefs[DISABLED].orEmpty()
            prefs[DISABLED] = if (enabled) disabled - type.name else disabled + type.name
        }
    }

    override suspend fun log(): NotificationLog {
        val prefs = dataStore.data.first()
        return NotificationLog(
            overspendDate = prefs[OVERSPEND_DAY]?.let(LocalDate::ofEpochDay),
            payments = prefs[PAYMENTS].orEmpty(),
            incomeDayPeriod = prefs[INCOME_DAY_PERIOD],
            incomeArrivedPeriod = prefs[INCOME_ARRIVED_PERIOD],
            // Строкой, а не набором: порядок нужен, чтобы при обрезке журнала уходили самые старые ключи.
            mismatches = prefs[MISMATCHES]?.split('\n')?.filter { it.isNotEmpty() }?.toCollection(LinkedHashSet()).orEmpty(),
        )
    }

    override suspend fun saveLog(log: NotificationLog) {
        dataStore.edit { prefs ->
            log.overspendDate?.let { prefs[OVERSPEND_DAY] = it.toEpochDay() } ?: prefs.remove(OVERSPEND_DAY)
            prefs[PAYMENTS] = log.payments
            log.incomeDayPeriod?.let { prefs[INCOME_DAY_PERIOD] = it } ?: prefs.remove(INCOME_DAY_PERIOD)
            log.incomeArrivedPeriod?.let { prefs[INCOME_ARRIVED_PERIOD] = it } ?: prefs.remove(INCOME_ARRIVED_PERIOD)
            prefs[MISMATCHES] = log.mismatches.joinToString("\n")
        }
    }

    private companion object {
        val DISABLED = stringSetPreferencesKey("notifications_disabled")
        val OVERSPEND_DAY = longPreferencesKey("notif_log_overspend_day")
        val PAYMENTS = stringSetPreferencesKey("notif_log_payments")
        val INCOME_DAY_PERIOD = longPreferencesKey("notif_log_income_day_period")
        val INCOME_ARRIVED_PERIOD = longPreferencesKey("notif_log_income_arrived_period")
        val MISMATCHES = stringPreferencesKey("notif_log_mismatches")
    }
}

/** Отдельный модуль: в тестах настройки работают на настоящем DataStore из отдельного файла теста. */
@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationSettingsBindings {
    @Binds
    abstract fun bindNotificationSettings(impl: DataStoreNotificationSettings): NotificationSettings
}
