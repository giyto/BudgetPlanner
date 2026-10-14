package ru.hznik.budget

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import ru.hznik.budget.domain.lock.AppLock
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
