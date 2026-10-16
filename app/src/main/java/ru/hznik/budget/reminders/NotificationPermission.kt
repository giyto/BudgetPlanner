package ru.hznik.budget.reminders

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Разрешение на уведомления (spec app-notifications «Разрешение на уведомления», design.md, решение 7). */
interface NotificationPermission {
    /** Android 13+ и разрешение ещё не выдано: есть шаг онбординга и кнопка «Разрешить». */
    fun isRequestNeeded(): Boolean

    /** Система показывает уведомления приложения: разрешение есть и уведомления не выключены целиком. */
    fun areEnabled(): Boolean

    /** Системный запрос уже показывали хотя бы раз. */
    suspend fun wasRequested(): Boolean

    suspend fun markRequested()
}

class SystemNotificationPermission @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
) : NotificationPermission {
    override fun isRequestNeeded(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    override fun areEnabled(): Boolean = !isRequestNeeded() && NotificationManagerCompat.from(context).areNotificationsEnabled()

    override suspend fun wasRequested(): Boolean = dataStore.data.first()[REQUESTED] ?: false

    override suspend fun markRequested() {
        dataStore.edit { it[REQUESTED] = true }
    }

    private companion object {
        val REQUESTED = booleanPreferencesKey("notification_permission_requested")
    }
}

/**
 * Показывает ли система запрос снова: после отказа «больше не спрашивать» (или двух отказов) запрос
 * не появляется, и остаётся только открыть настройки уведомлений приложения.
 */
fun Activity.canAskNotificationPermission(wasRequested: Boolean): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        (!wasRequested || ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS))

/** Системные настройки уведомлений приложения. */
fun Context.openAppNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}
