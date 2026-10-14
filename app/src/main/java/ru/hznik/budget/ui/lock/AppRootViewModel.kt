package ru.hznik.budget.ui.lock

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.hznik.budget.domain.lock.AppLock
import ru.hznik.budget.domain.lock.LockSettings
import ru.hznik.budget.domain.lock.LockSettingsRepository
import ru.hznik.budget.domain.lock.LockState
import ru.hznik.budget.domain.security.DatabaseAccess
import ru.hznik.budget.domain.security.DatabaseState
import javax.inject.Inject

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
) : ViewModel() {
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
        viewModelScope.launch { databaseState.value = database.open() }
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
        }
    }

    fun wipe() {
        viewModelScope.launch {
            databaseState.value = null
            database.wipe()
            databaseState.value = database.open()
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
