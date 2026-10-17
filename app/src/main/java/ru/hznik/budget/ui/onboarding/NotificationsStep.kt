package ru.hznik.budget.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.hznik.budget.R
import ru.hznik.budget.ui.components.Badge
import ru.hznik.budget.ui.components.PointsCard
import ru.hznik.budget.ui.components.PrimaryButton
import ru.hznik.budget.ui.components.SecondaryButton
import ru.hznik.budget.ui.theme.BudgetTheme
import ru.hznik.budget.ui.theme.TextRoles

/** Шаг «Подсказки вовремя» (макет OnboardingNotifications, spec onboarding): что и когда пришлёт приложение. */
@Composable
fun NotificationsStep() {
    val colors = BudgetTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(20.dp))
        Badge(painterResource(R.drawable.ic_bell), container = colors.primarySoft, content = colors.primaryText)
        Text(
            stringResource(R.string.onboarding_notif_title),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp).semantics { heading() },
        )
        Text(
            stringResource(R.string.onboarding_notif_text),
            fontSize = 15.sp,
            lineHeight = 22.sp,
            color = colors.sub,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp, start = 12.dp, end = 12.dp),
        )
        PointsCard(
            listOf(
                R.drawable.ic_alert to stringResource(R.string.onboarding_notif_overspend),
                R.drawable.ic_calendar to stringResource(R.string.onboarding_notif_payment),
                R.drawable.ic_cat_wallet to stringResource(R.string.onboarding_notif_income),
                R.drawable.ic_scale to stringResource(R.string.onboarding_notif_mismatch),
            ),
            tileContainer = colors.positiveSoft,
            tileContent = colors.positive,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            stringResource(R.string.onboarding_notif_note),
            style = TextRoles.hint,
            color = colors.muted,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp, end = 4.dp, bottom = 8.dp),
        )
    }
}

/** «Разрешить уведомления» открывает системный запрос; любой ответ и «Не сейчас» ведут к защите входа. */
@Composable
fun NotificationsButtons(busy: Boolean, onAnswered: () -> Unit, onLater: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onAnswered() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(16.dp)) {
        PrimaryButton(stringResource(R.string.onboarding_notif_allow), onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }, enabled = !busy)
        SecondaryButton(stringResource(R.string.onboarding_notif_later), onClick = onLater, enabled = !busy)
    }
}
