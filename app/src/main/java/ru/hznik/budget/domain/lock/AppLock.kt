package ru.hznik.budget.domain.lock

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Через сколько после ухода в фон блокируется приложение (FR-10). */
enum class LockTimeout(val millis: Long) {
    IMMEDIATELY(0),
    ONE_MINUTE(60_000),
    FIVE_MINUTES(300_000),
}

/** Настройки защиты входа; по умолчанию защита выключена, 1 минута, без скрытия (решения пользователя). */
data class LockSettings(
    val enabled: Boolean = false,
    val timeout: LockTimeout = LockTimeout.ONE_MINUTE,
    val hideInRecents: Boolean = false,
)

interface LockSettingsRepository {
    fun observe(): Flow<LockSettings>

    suspend fun setEnabled(enabled: Boolean)

    suspend fun setTimeout(timeout: LockTimeout)

    suspend fun setHideInRecents(hide: Boolean)
}

/** Монотонные часы: перевод часов телефона не должен сокращать или продлевать таймаут блокировки. */
fun interface ElapsedClock {
    fun elapsedMillis(): Long
}

enum class LockState {
    /** Настройки ещё не прочитаны: виден только фон окна, чтобы содержимое не мелькнуло перед блокировкой. */
    UNKNOWN,
    LOCKED,
    UNLOCKED,
}

/**
 * Когда приложение заблокировано (design.md add-data-protection, решение 7). Синглтон живёт столько же,
 * сколько процесс, поэтому холодный старт при включённой защите всегда начинается с блокировки.
 */
@Singleton
class AppLock @Inject constructor(private val elapsed: ElapsedClock) {
    private val _state = MutableStateFlow(LockState.UNKNOWN)
    val state: StateFlow<LockState> = _state.asStateFlow()

    private val _notice = MutableStateFlow(false)

    /** Защиту выключили сами, потому что на телефоне сняли блокировку экрана: один раз сообщить на главной. */
    val screenLockRemovedNotice: StateFlow<Boolean> = _notice.asStateFlow()

    private var settings: LockSettings? = null
    private var backgroundSince: Long? = null
    private var authenticating = false

    /** Ушёл в фон, пока было открыто системное окно: засчитывается, если проверка не удалась. */
    private var pendingBackgroundSince: Long? = null

    fun onSettings(new: LockSettings) {
        val first = settings == null
        settings = new
        // Защиту включили в настройках или онбординге после успешной проверки: блокировать сейчас незачем.
        when {
            !new.enabled -> _state.value = LockState.UNLOCKED
            first -> _state.value = LockState.LOCKED
        }
    }

    fun onBackground() {
        // Ввод PIN на части телефонов — отдельное окно системы: это ещё не уход из приложения.
        if (authenticating) {
            pendingBackgroundSince = elapsed.elapsedMillis()
            return
        }
        backgroundSince = elapsed.elapsedMillis()
    }

    fun onForeground() {
        if (authenticating) {
            // Вернулись из окна ввода PIN.
            pendingBackgroundSince = null
            return
        }
        val since = backgroundSince ?: return
        backgroundSince = null
        val current = settings ?: return
        if (current.enabled && elapsed.elapsedMillis() - since >= current.timeout.millis) _state.value = LockState.LOCKED
    }

    fun onAuthenticationStarted() {
        authenticating = true
    }

    fun onAuthenticationFinished(success: Boolean) {
        authenticating = false
        if (success) {
            backgroundSince = null
            _state.value = LockState.UNLOCKED
        } else {
            // Окно закрылось, когда пользователь уже ушёл из приложения: это время в фоне не теряется.
            pendingBackgroundSince?.let { backgroundSince = it }
        }
        pendingBackgroundSince = null
    }

    fun onScreenLockRemoved() {
        _notice.value = true
        _state.value = LockState.UNLOCKED
    }

    fun consumeNotice() {
        _notice.value = false
    }
}
