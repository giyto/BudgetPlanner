package ru.hznik.budget.ui.lock

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.hznik.budget.domain.lock.AppLock
import ru.hznik.budget.domain.lock.LockSettings
import ru.hznik.budget.domain.lock.LockSettingsRepository
import ru.hznik.budget.domain.lock.LockState
import ru.hznik.budget.domain.reminders.CheckNotifications
import ru.hznik.budget.domain.reminders.WidgetRefresher
import ru.hznik.budget.domain.security.DatabaseAccess
import ru.hznik.budget.domain.security.DatabaseState
import ru.hznik.budget.domain.usecase.ObservePeriodStatus
import ru.hznik.budget.notifications.NotificationIntake
import javax.inject.Inject
import ru.hznik.budget.common.runLogged

/** Что показывает активность: фон, экран потери ключа, экран блокировки или приложение. */
sealed interface RootState {
    data object Loading : RootState

    data object KeyLost : RootState

    /** Временный сбой при открытии базы: только «Попробовать снова». */
    data object DatabaseError : RootState

    data class Ready(val locked: Boolean, val hideInRecents: Boolean) : RootState
}

@HiltViewModel
class AppRootViewModel @Inject constructor(
    private val database: DatabaseAccess,
    private val lockSettings: LockSettingsRepository,
    private val appLock: AppLock,
    private val authenticator: DeviceAuthenticator,
    // Lazy: приём уведомлений тянет за собой Room, а базу можно создавать только после проверки ключа.
    private val notificationIntake: Lazy<NotificationIntake>,
    // Lazy по той же причине: расчёт и проверка уведомлений читают Room.
    private val observeStatus: Lazy<ObservePeriodStatus>,
    private val checkNotifications: Lazy<CheckNotifications>,
    private val widget: WidgetRefresher,
) : ViewModel() {
    private var statusWatcher: Job? = null

    private val databaseState = MutableStateFlow<DatabaseState?>(null)
    private val settings = MutableStateFlow<LockSettings?>(null)

    val state: StateFlow<RootState> = combine(databaseState, settings, appLock.state) { db, current, lock ->
        when {
            db == null -> RootState.Loading
            db == DatabaseState.KEY_LOST -> RootState.KeyLost
            db == DatabaseState.ERROR -> RootState.DatabaseError
            current == null || lock == LockState.UNKNOWN -> RootState.Loading
            else -> RootState.Ready(locked = lock == LockState.LOCKED, hideInRecents = current.hideInRecents)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RootState.Loading)

    init {
        viewModelScope.launch {
            databaseState.value = database.open()
            // Образцы старше суток удаляются и при запуске, даже если режим сбора выключен.
            runLogged(TAG, "Очистка образцов не удалась") { notificationIntake.get().purgeExpired() }
            watchStatusIfReady()
        }
        viewModelScope.launch {
            lockSettings.observe().collect { current ->
                settings.value = current
                if (!disableIfScreenLockRemoved(current)) appLock.onSettings(current)
            }
        }
    }

    /** Активность снова на экране: вдруг блокировку экрана сняли, пока приложение было в фоне. */
    fun onStart() {
        val current = settings.value ?: return
        viewModelScope.launch { disableIfScreenLockRemoved(current) }
    }

    fun retry() {
        viewModelScope.launch {
            databaseState.value = null
            databaseState.value = database.open()
            watchStatusIfReady()
        }
    }

    fun wipe() {
        viewModelScope.launch {
            databaseState.value = null
            database.wipe()
            databaseState.value = database.open()
            watchStatusIfReady()
        }
    }

    /**
     * Любое изменение расчёта (операция, период, платёж, новый день) и смена защиты входа: проверка уведомлений
     * и обновление виджета (design.md add-widget-and-reminders, решения 2–3). Первая проверка — сразу при запуске:
     * так приходит напоминание, пропущенное фоновой задачей.
     */
    private fun watchStatusIfReady() {
        if (databaseState.value != DatabaseState.READY) {
            // Данные недоступны: виджет не должен показывать прежнюю сумму (spec home-widget).
            viewModelScope.launch { runLogged(TAG, "Обновление виджета не удалось") { widget.refresh() } }
            return
        }
        if (statusWatcher != null) return
        statusWatcher = viewModelScope.launch {
            combine(observeStatus.get()(), lockSettings.observe().map { it.widgetAmountHidden }.distinctUntilChanged()) { status, locked -> status to locked }
                .conflate()
                .collect { runLogged(TAG, "Проверка уведомлений не удалась") { checkNotifications.get().invoke() } }
        }
    }

    private suspend fun disableIfScreenLockRemoved(current: LockSettings): Boolean {
        if (!current.enabled || authenticator.availability() != AuthAvailability.NO_SCREEN_LOCK) return false
        // Снять блокировку экрана можно только зная её PIN, поэтому выключение защиты — не обход.
        appLock.onScreenLockRemoved()
        lockSettings.setEnabled(false)
        return true
    }
}

/**
 * Проверка с уведомлением [AppLock]: пока открыто системное окно, уход в фон не считается,
 * а успешная проверка снимает блокировку.
 */
class TrackedAuthenticator(
    private val delegate: DeviceAuthenticator,
    private val appLock: AppLock,
) : DeviceAuthenticator {
    override fun availability(): AuthAvailability = delegate.availability()

    override suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String): AuthResult {
        appLock.onAuthenticationStarted()
        var result: AuthResult = AuthResult.Cancelled
        try {
            result = delegate.authenticate(activity, title, subtitle)
            return result
        } finally {
            appLock.onAuthenticationFinished(result == AuthResult.Success)
        }
    }
}

private const val TAG = "AppRoot"
