package ru.hznik.budget.ui.autoimport

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.hznik.budget.domain.notifications.AutoImportSettings
import ru.hznik.budget.domain.notifications.AutoImportSetup
import ru.hznik.budget.domain.notifications.SetupFacts
import ru.hznik.budget.domain.notifications.SetupStep
import ru.hznik.budget.domain.notifications.SupportedBanks
import ru.hznik.budget.notifications.BankNotificationListener
import ru.hznik.budget.notifications.ListenerStatus
import javax.inject.Inject

data class AutoImportState(
    val step: SetupStep = SetupStep.NOTIFICATION_ACCESS,
    val accessGranted: Boolean = false,
    /** Автоимпорт включали, а сейчас он не работает: доступ отозван или сервис не подключается. */
    val stopped: Boolean = false,
    val needsAutostart: Boolean = false,
    /** Банки, чьи уведомления уже разбираются, и установлен ли каждый. */
    val banks: List<Pair<String, Boolean>> = emptyList(),
    /** Android 10 и прошивки без экрана переключателя: строку нужно найти в общем списке. */
    val accessOpensList: Boolean = false,
)

/** Шаги включения автоимпорта и проверка, что он жив (design.md add-notification-import, решение 8). */
@HiltViewModel
class AutoImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AutoImportSettings,
) : ViewModel() {
    private val granted = MutableStateFlow(SetupDevice.accessGranted(context))
    private val sberInstalled = MutableStateFlow(installedBanks())
    private val notConnecting = MutableStateFlow(false)
    private var rebindJob: Job? = null

    private val device = SetupDevice.facts(context)

    private val visited = combine(settings.accessAttempted, settings.restrictedVisited, settings.autostartDone) { a, r, s -> Triple(a, r, s) }

    val state: StateFlow<AutoImportState> = combine(
        combine(granted, sberInstalled, notConnecting) { g, s, n -> Triple(g, s, n) },
        settings.wasEnabled,
        visited,
        ListenerStatus.connected,
    ) { (accessGranted, sber, notConnected), wasEnabled, (accessAttempted, restrictedVisited, autostartDone), connected ->
        val facts = device.copy(
            accessGranted = accessGranted,
            accessAttempted = accessAttempted,
            restrictedVisited = restrictedVisited,
            autostartDone = autostartDone,
        )
        AutoImportState(
            step = AutoImportSetup.nextStep(facts),
            accessGranted = accessGranted,
            stopped = wasEnabled && (!accessGranted || (notConnected && !connected)),
            needsAutostart = AutoImportSetup.needsAutostart(facts.manufacturer),
            banks = sber,
            accessOpensList = facts.sdk < Build.VERSION_CODES.R,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AutoImportState())

    init {
        refresh()
    }

    /** После возврата из настроек системы: доступ мог появиться или пропасть, банк — установиться. */
    fun refresh() {
        val nowGranted = SetupDevice.accessGranted(context)
        granted.value = nowGranted
        sberInstalled.value = installedBanks()
        if (nowGranted) {
            viewModelScope.launch { settings.setWasEnabled() }
            watchConnection()
        }
    }

    private fun installedBanks(): List<Pair<String, Boolean>> =
        PARSED_BANKS.map { it.name to SetupDevice.isInstalled(context, it.packageNames) }

    /** Доступ есть, а сервис не подключился: попросить систему переподключить, потом считать остановленным. */
    private fun watchConnection() {
        if (rebindJob?.isActive == true) return
        rebindJob = viewModelScope.launch {
            delay(REBIND_AFTER_MS)
            if (ListenerStatus.connected.value) return@launch
            runCatching { NotificationListenerService.requestRebind(ComponentName(context, BankNotificationListener::class.java)) }
            delay(STOPPED_AFTER_MS)
            notConnecting.value = !ListenerStatus.connected.value
        }
    }

    /** Экран системы для шага; шаги, которые нельзя проверить, считаются пройденными после открытия. */
    fun intentsFor(step: SetupStep): List<Intent> {
        when (step) {
            SetupStep.NOTIFICATION_ACCESS -> viewModelScope.launch { settings.setAccessAttempted() }
            SetupStep.RESTRICTED_SETTINGS -> viewModelScope.launch { settings.setRestrictedVisited() }
            SetupStep.AUTOSTART -> viewModelScope.launch { settings.setAutostartDone() }
            SetupStep.DONE -> Unit
        }
        return SetupDevice.intents(context, step)
    }

    private companion object {
        /** Банки, для которых есть парсер. */
        val PARSED_BANKS = listOf(SupportedBanks.SBER, SupportedBanks.TBANK, SupportedBanks.ALFA)
        const val REBIND_AFTER_MS = 10_000L
        const val STOPPED_AFTER_MS = 5_000L
    }
}

/** Всё, что зависит от телефона: версия, производитель, источник установки, экраны системы. */
object SetupDevice {
    fun facts(context: Context) = SetupFacts(
        sdk = Build.VERSION.SDK_INT,
        manufacturer = Build.MANUFACTURER.orEmpty(),
        installedFromFile = installedFromFile(context),
        accessGranted = accessGranted(context),
        accessAttempted = false,
        restrictedVisited = false,
        autostartDone = false,
    )

    fun accessGranted(context: Context): Boolean = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    fun isInstalled(context: Context, packages: Set<String>): Boolean = packages.any { name ->
        try {
            context.packageManager.getPackageInfo(name, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Android 13+: установлено файлом APK (не магазином и не через adb) — доступ к уведомлениям ограничен. */
    private fun installedFromFile(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val source = runCatching { context.packageManager.getInstallSourceInfo(context.packageName).packageSource }.getOrNull()
        return source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE || source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
    }

    fun intents(context: Context, step: SetupStep): List<Intent> {
        val listener = ComponentName(context, BankNotificationListener::class.java).flattenToString()
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        return when (step) {
            SetupStep.NOTIFICATION_ACCESS -> buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener))
                }
                // Общий список; там, где Settings это поддерживает, строка BudgetPlanner подсвечена.
                add(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .putExtra(FRAGMENT_ARGS_KEY, listener)
                        .putExtra(SHOW_FRAGMENT_ARGS, Bundle().apply { putString(FRAGMENT_ARGS_KEY, listener) }),
                )
                add(Intent(Settings.ACTION_SETTINGS))
            }
            SetupStep.RESTRICTED_SETTINGS -> listOf(appDetails)
            SetupStep.AUTOSTART -> autostartScreens(Build.MANUFACTURER.orEmpty().lowercase()).map { Intent().setComponent(it) } + appDetails
            SetupStep.DONE -> emptyList()
        }
    }

    /** Экраны автозапуска производителей; официального способа их открыть нет, поэтому по списку с запасным «О приложении». */
    private fun autostartScreens(manufacturer: String): List<ComponentName> = when (manufacturer) {
        "xiaomi", "redmi", "poco" -> listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        "huawei" -> listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        )
        "honor" -> listOf(
            ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        )
        "oppo", "realme", "oneplus" -> listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
        )
        "vivo" -> listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        )
        else -> emptyList() // Samsung и остальные: батарея в «О приложении».
    }

    /** Открывает первый экран из списка, который есть на телефоне. */
    fun open(context: Context, intents: List<Intent>): Boolean = intents.any { intent ->
        try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            // Экран производителя есть, но закрыт для других приложений.
            false
        }
    }

    private const val FRAGMENT_ARGS_KEY = ":settings:fragment_args_key"
    private const val SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
}
