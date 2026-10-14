package ru.hznik.budget.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import ru.hznik.budget.R
import ru.hznik.budget.domain.notifications.SetupStep
import ru.hznik.budget.ui.autoimport.AutoImportState
import ru.hznik.budget.ui.autoimport.SetupStepButton
import ru.hznik.budget.ui.autoimport.stepHint
import ru.hznik.budget.ui.lock.PointsCard
import ru.hznik.budget.ui.theme.BudgetTheme

/** Содержимое шага «Автоимпорт» по макету Onboarding (spec onboarding, сценарий 5.1, шаг 4). */
@Composable
fun AutoImportStep(state: AutoImportState) {
    val colors = BudgetTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(20.dp))
        Box(modifier = Modifier.size(120.dp)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp).background(colors.card, CircleShape)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp).background(colors.primarySoft, RoundedCornerShape(20.dp))) {
                    Icon(painterResource(R.drawable.ic_bell), contentDescription = null, tint = colors.primaryText, modifier = Modifier.size(32.dp))
                }
            }
            if (state.accessGranted) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.align(Alignment.BottomEnd).size(36.dp).background(colors.background, CircleShape).padding(3.dp)
                        .background(colors.positiveSoft, CircleShape),
                ) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = colors.positive, modifier = Modifier.size(18.dp))
                }
            }
        }
        val lastStep = state.accessGranted && state.step == SetupStep.AUTOSTART
        Text(
            stringResource(if (lastStep) R.string.onboarding_import_last_title else R.string.onboarding_import_title),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp).semantics { heading() },
        )
        Text(
            stringResource(if (lastStep) R.string.onboarding_import_last_text else R.string.onboarding_import_text),
            fontSize = 15.sp,
            lineHeight = 22.sp,
            color = colors.sub,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp, start = 12.dp, end = 12.dp),
        )
        when (state.step) {
            SetupStep.RESTRICTED_SETTINGS -> RestrictedCard()
            SetupStep.AUTOSTART -> PointsCard(
                listOf(
                    R.drawable.ic_check to stringResource(R.string.onboarding_import_point_access),
                    R.drawable.ic_refresh to stringResource(R.string.onboarding_import_point_autostart),
                ),
                tileContainer = colors.positiveSoft,
                tileContent = colors.positive,
                modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
            )
            else -> {
                PointsCard(
                    listOf(
                        R.drawable.ic_check to stringResource(R.string.onboarding_import_point_banks),
                        R.drawable.ic_check to stringResource(R.string.onboarding_import_point_fields),
                        R.drawable.ic_check to stringResource(R.string.onboarding_import_point_secrets),
                        R.drawable.ic_check to stringResource(R.string.onboarding_import_point_offline),
                    ),
                    tileContainer = colors.positiveSoft,
                    tileContent = colors.positive,
                    modifier = Modifier.padding(top = 18.dp),
                )
                Text(
                    stringResource(R.string.onboarding_import_revoke),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp, start = 8.dp, end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun RestrictedCard() {
    val colors = BudgetTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp).fillMaxWidth().background(colors.warnSoft, RoundedCornerShape(22.dp)).padding(16.dp),
    ) {
        Text(stringResource(R.string.onboarding_import_restricted_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
        Text(stringResource(R.string.setup_restricted_hint), fontSize = 13.sp, lineHeight = 18.sp, color = colors.sub)
    }
}

/**
 * Кнопки шага: следующий шаг включения или «Дальше», когда всё включено, и «Вводить вручную».
 * Когда последний шаг пройден на этом экране, онбординг переходит дальше сам; если пользователь вернулся
 * на шаг уже со включённым автоимпортом, он остаётся здесь и нажимает «Дальше».
 */
@Composable
fun AutoImportButtons(state: AutoImportState, busy: Boolean, onOpen: (SetupStep) -> Unit, onNext: () -> Unit) {
    val colors = BudgetTheme.colors
    var pending by rememberSaveable { mutableStateOf(state.step != SetupStep.DONE) }
    LaunchedEffect(state.step) {
        if (state.step != SetupStep.DONE) {
            pending = true
        } else if (pending) {
            pending = false
            onNext()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(16.dp)) {
        if (state.step == SetupStep.DONE) {
            Button(
                onClick = onNext,
                enabled = !busy,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(stringResource(R.string.onboarding_next), fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        } else {
            SetupStepButton(state, onOpen = onOpen)
            // У шага «Снять ограничение» подсказка уже в карточке над кнопками.
            stepHint(state)?.takeIf { state.step != SetupStep.RESTRICTED_SETTINGS }?.let {
                Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted)
            }
            OutlinedButton(
                onClick = onNext,
                enabled = !busy,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, colors.chipBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.ink),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.onboarding_import_manual), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}
