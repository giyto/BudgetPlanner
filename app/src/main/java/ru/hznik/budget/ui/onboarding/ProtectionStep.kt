package ru.hznik.budget.ui.onboarding

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import ru.hznik.budget.R
import ru.hznik.budget.ui.lock.AuthAvailability
import ru.hznik.budget.ui.lock.AuthResult
import ru.hznik.budget.ui.lock.Badge
import ru.hznik.budget.ui.lock.DeviceAuthenticator
import ru.hznik.budget.ui.lock.LocalDeviceAuthenticator
import ru.hznik.budget.ui.lock.PointsCard
import ru.hznik.budget.ui.settings.openScreenLockSettings
import ru.hznik.budget.ui.theme.BudgetTheme

/** Доступность проверки и ошибка последней попытки на шаге «Защита входа». */
@Stable
class ProtectionStepState(val authenticator: DeviceAuthenticator) {
    var availability by mutableStateOf(authenticator.availability())
    var error by mutableStateOf<String?>(null)
}

@Composable
fun rememberProtectionStepState(): ProtectionStepState {
    val authenticator = LocalDeviceAuthenticator.current
    val state = remember(authenticator) { ProtectionStepState(authenticator) }
    // После возврата из системных настроек блокировка экрана могла появиться.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state.availability = authenticator.availability() }
    return state
}

/** Шаг 4 (сценарий 5.1, шаг 5): предложение защитить вход разблокировкой телефона. */
@Composable
fun ProtectionStep(state: ProtectionStepState) {
    val colors = BudgetTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(20.dp))
        Badge(painterResource(R.drawable.ic_fingerprint), container = colors.primarySoft, content = colors.primaryText)
        Text(
            stringResource(R.string.onboarding_protection_title),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp).semantics { heading() },
        )
        Text(
            stringResource(R.string.onboarding_protection_text),
            fontSize = 15.sp,
            lineHeight = 22.sp,
            color = colors.sub,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp, start = 12.dp, end = 12.dp),
        )
        val hint = when (state.availability) {
            AuthAvailability.AVAILABLE -> null
            AuthAvailability.NO_SCREEN_LOCK -> stringResource(R.string.protection_no_screen_lock)
            AuthAvailability.UNAVAILABLE -> stringResource(R.string.protection_unavailable)
        }
        (state.error ?: hint)?.let {
            Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = colors.warn, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        }
        PointsCard(
            listOf(
                R.drawable.ic_check to stringResource(R.string.onboarding_protection_point_phone),
                R.drawable.ic_check to stringResource(R.string.onboarding_protection_point_timeout),
                R.drawable.ic_check to stringResource(R.string.onboarding_protection_point_settings),
            ),
            tileContainer = colors.positiveSoft,
            tileContent = colors.positive,
            modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
        )
    }
}

/** «Включить» (или «Настроить блокировку экрана», если её нет) и «Не сейчас» внизу под пальцем. */
@Composable
fun ProtectionButtons(state: ProtectionStepState, busy: Boolean, onEnabled: () -> Unit, onLater: () -> Unit) {
    val colors = BudgetTheme.colors
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.auth_title)
    val subtitle = stringResource(R.string.auth_enable_subtitle)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(16.dp)) {
        when (state.availability) {
            AuthAvailability.AVAILABLE -> Button(
                onClick = {
                    val host = activity as? FragmentActivity ?: return@Button
                    scope.launch {
                        when (val result = state.authenticator.authenticate(host, title, subtitle)) {
                            AuthResult.Success -> {
                                state.error = null
                                onEnabled()
                            }
                            AuthResult.Cancelled, AuthResult.Interrupted -> Unit
                            is AuthResult.Error -> state.error = result.message
                        }
                    }
                },
                enabled = !busy,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(stringResource(R.string.onboarding_protection_enable), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            AuthAvailability.NO_SCREEN_LOCK -> Button(
                onClick = { activity?.openScreenLockSettings() },
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(stringResource(R.string.onboarding_protection_setup), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            AuthAvailability.UNAVAILABLE -> Unit
        }
        OutlinedButton(
            onClick = onLater,
            enabled = !busy,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, colors.chipBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.ink),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(stringResource(R.string.onboarding_protection_later), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
