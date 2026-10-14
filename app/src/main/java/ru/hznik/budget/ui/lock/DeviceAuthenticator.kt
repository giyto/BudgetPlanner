package ru.hznik.budget.ui.lock

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

/** Можно ли проверить владельца разблокировкой телефона. */
enum class AuthAvailability {
    AVAILABLE,

    /** На телефоне нет PIN, ключа, пароля и биометрии: защиту включить нельзя. */
    NO_SCREEN_LOCK,

    UNAVAILABLE,
}

sealed interface AuthResult {
    data object Success : AuthResult

    /** Пользователь закрыл системное окно. */
    data object Cancelled : AuthResult

    /** Окно закрыла система (приложение ушло в фон) или его нельзя было показать: при возврате показать снова. */
    data object Interrupted : AuthResult

    /** Ошибка системы, например слишком много попыток; текст от системы уже на языке телефона. */
    data class Error(val message: String) : AuthResult
}

/**
 * Проверка через системную разблокировку телефона (design.md add-data-protection, решение 6).
 * Интерфейс нужен UI-тестам: системное окно Compose-тест нажать не может.
 */
interface DeviceAuthenticator {
    fun availability(): AuthAvailability

    suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String): AuthResult
}

val LocalDeviceAuthenticator = staticCompositionLocalOf<DeviceAuthenticator> { error("DeviceAuthenticator не передан") }

class BiometricDeviceAuthenticator @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceAuthenticator {
    override fun availability(): AuthAvailability {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceSecure != true) return AuthAvailability.NO_SCREEN_LOCK
        return when (BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> AuthAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AuthAvailability.NO_SCREEN_LOCK
            else -> AuthAvailability.UNAVAILABLE
        }
    }

    override suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String): AuthResult =
        // После onSaveInstanceState BiometricPrompt молча не показывается и не отвечает: корутина зависла бы.
        if (activity.supportFragmentManager.isStateSaved) {
            AuthResult.Interrupted
        } else suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(AuthResult.Success)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!continuation.isActive) return
                        continuation.resume(
                            when (errorCode) {
                                in USER_CANCEL_CODES -> AuthResult.Cancelled
                                BiometricPrompt.ERROR_CANCELED -> AuthResult.Interrupted
                                else -> AuthResult.Error(errString.toString())
                            },
                        )
                    }
                    // onAuthenticationFailed — неподходящий палец: системное окно остаётся открытым и просит ещё раз.
                },
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(AUTHENTICATORS)
                    .build(),
            )
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }

    private companion object {
        /** STRONG вместе с DEVICE_CREDENTIAL и один DEVICE_CREDENTIAL не работают на API 29, эта пара работает на API 29–37. */
        const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

        val USER_CANCEL_CODES = setOf(BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthenticatorModule {
    @Binds
    abstract fun bindDeviceAuthenticator(impl: BiometricDeviceAuthenticator): DeviceAuthenticator
}
