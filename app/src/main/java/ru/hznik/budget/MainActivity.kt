package ru.hznik.budget

import android.content.Intent
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import ru.hznik.budget.domain.lock.AppLock
import ru.hznik.budget.reminders.AppActionBus
import ru.hznik.budget.reminders.AppActionIntents
import ru.hznik.budget.ui.BudgetApp
import ru.hznik.budget.ui.lock.AppRoot
import ru.hznik.budget.ui.lock.DeviceAuthenticator
import ru.hznik.budget.ui.lock.LocalDeviceAuthenticator
import ru.hznik.budget.ui.lock.TrackedAuthenticator
import ru.hznik.budget.ui.theme.BudgetPlannerTheme
import javax.inject.Inject

/** `FragmentActivity`, потому что системное окно проверки (`BiometricPrompt`) живёт во фрагменте. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var authenticator: DeviceAuthenticator

    @Inject lateinit var appLock: AppLock

    @Inject lateinit var actions: AppActionBus

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // После поворота экрана тот же Intent приходит снова: переход уже выполнен.
        if (savedInstanceState == null) handleAction(intent)
        enableEdgeToEdge()
        val tracked = TrackedAuthenticator(authenticator, appLock)
        setContent {
            BudgetPlannerTheme {
                CompositionLocalProvider(LocalDeviceAuthenticator provides tracked) {
                    AppRoot(content = { BudgetApp() })
                }
            }
        }
    }

    /** Нажатие на виджет или уведомление, когда приложение уже открыто (singleTop из PendingIntent). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAction(intent)
    }

    /** Действие из `Intent` в навигацию; открыт для UI-теста, который не может вызвать `onNewIntent`. */
    @VisibleForTesting
    internal fun handleAction(intent: Intent?) {
        // Задача, открытая из «Недавних», повторила бы переход, с которого её когда-то запустили.
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        AppActionIntents.parse(intent)?.let(actions::post)
    }

    // Активность одна, поэтому её onStop — уход из приложения. ProcessLifecycleOwner сообщает об этом
    // с задержкой 0,7 с и пропускает быстрый возврат, а «Сразу» должно блокировать и его. Поворот экрана не считается.
    override fun onStart() {
        super.onStart()
        appLock.onForeground()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) appLock.onBackground()
    }
}
