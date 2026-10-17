package ru.hznik.budget.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import ru.hznik.budget.R
import ru.hznik.budget.domain.notifications.SetupStep
import ru.hznik.budget.ui.autoimport.AutoImportState
import ru.hznik.budget.ui.autoimport.AutoImportViewModel
import ru.hznik.budget.ui.autoimport.RefreshOnResume
import ru.hznik.budget.ui.autoimport.rememberStepOpener
import ru.hznik.budget.ui.components.AmountInput
import ru.hznik.budget.ui.components.AmountKey
import ru.hznik.budget.ui.components.AmountKeypad
import ru.hznik.budget.ui.components.BudgetDatePicker
import ru.hznik.budget.ui.components.ButtonSize
import ru.hznik.budget.ui.components.ChoiceChip
import ru.hznik.budget.ui.components.PaymentForm
import ru.hznik.budget.ui.components.PaymentFormState
import ru.hznik.budget.ui.components.PointsCard
import ru.hznik.budget.ui.components.PrimaryButton
import ru.hznik.budget.ui.components.SegmentedChoice
import ru.hznik.budget.ui.components.formatDayMonth
import ru.hznik.budget.ui.format.MoneyFormat
import ru.hznik.budget.ui.format.SpokenMoney
import ru.hznik.budget.ui.theme.BudgetTheme
import java.time.LocalDate
import ru.hznik.budget.ui.theme.Motion
import ru.hznik.budget.ui.theme.LocalReducedMotion
import ru.hznik.budget.ui.components.animatedColor

@Composable
fun OnboardingRoute(
    onFinished: () -> Unit,
    onExit: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
    autoImport: AutoImportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val importState by autoImport.state.collectAsStateWithLifecycle()
    val openStep = rememberStepOpener(autoImport)
    RefreshOnResume(autoImport)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.events.collect { onFinished() } }
    }
    // «Назад» листает шаги, на первом шаге закрывает приложение.
    BackHandler { if (!viewModel.back()) onExit() }
    OnboardingScreen(
        state = state,
        importState = importState,
        onOpenImportStep = openStep,
        onBack = { viewModel.back() },
        onNext = viewModel::next,
        onSkip = viewModel::skipPayments,
        onProtectionEnabled = viewModel::finishWithProtection,
        onNotificationsAnswered = viewModel::onNotificationPermissionAnswered,
        onKey = viewModel::onKey,
        onIncomeRegular = viewModel::onIncomeRegular,
        onIncomeDay = viewModel::onIncomeDay,
        onIncomeDate = viewModel::onIncomeDate,
        onOpenPaymentForm = viewModel::openPaymentForm,
        onPaymentFormChange = viewModel::onPaymentFormChange,
        onClosePaymentForm = viewModel::closePaymentForm,
        onAddPayment = viewModel::addPayment,
        onRemovePayment = viewModel::removePayment,
    )
}

@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    importState: AutoImportState,
    onOpenImportStep: (SetupStep) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onProtectionEnabled: () -> Unit,
    onNotificationsAnswered: () -> Unit,
    onKey: (AmountKey) -> Unit,
    onIncomeRegular: (Boolean) -> Unit,
    onIncomeDay: (Int) -> Unit,
    onIncomeDate: (LocalDate) -> Unit,
    onOpenPaymentForm: () -> Unit,
    onPaymentFormChange: (PaymentFormState) -> Unit,
    onClosePaymentForm: () -> Unit,
    onAddPayment: () -> Unit,
    onRemovePayment: (Int) -> Unit,
) {
    val colors = BudgetTheme.colors
    val protection = rememberProtectionStepState()
    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp)) {
            if (state.step != OnboardingStep.INTRO) {
                IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.action_back), tint = colors.ink) }
            }
            Spacer(Modifier.weight(1f))
            if (state.step == OnboardingStep.PAYMENTS || state.step == OnboardingStep.AUTO_IMPORT || state.step == OnboardingStep.NOTIFICATIONS) {
                TextButton(onClick = if (state.step == OnboardingStep.PAYMENTS) onSkip else onNext, enabled = !state.busy) {
                    Text(stringResource(R.string.onboarding_skip), color = colors.primaryText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
        }
        StepProgress(state.stepNumber, state.stepCount)

        // Шаг вперёд въезжает справа, «Назад» — слева (spec ui-motion «Шаги онбординга»); нижние кнопки не двигаются.
        val reduced = LocalReducedMotion.current
        AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                if (reduced) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    val sign = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally(tween(Motion.SCREEN)) { sign * it / 3 } + fadeIn(tween(Motion.SCREEN))) togetherWith
                        (slideOutHorizontally(tween(Motion.SCREEN)) { -sign * it / 3 } + fadeOut(tween(Motion.SCREEN)))
                }
            },
            label = "onboarding step",
            modifier = Modifier.weight(1f),
        ) { step ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                when (step) {
                    OnboardingStep.INTRO -> IntroStep()
                    OnboardingStep.MONEY -> MoneyStep(state, onKey, onIncomeRegular, onIncomeDay, onIncomeDate)
                    OnboardingStep.PAYMENTS -> PaymentsStep(state, onOpenPaymentForm, onRemovePayment)
                    OnboardingStep.AUTO_IMPORT -> AutoImportStep(importState)
                    OnboardingStep.NOTIFICATIONS -> NotificationsStep()
                    OnboardingStep.PROTECTION -> ProtectionStep(protection)
                }
            }
        }

        if (state.step == OnboardingStep.AUTO_IMPORT) {
            AutoImportButtons(importState, busy = state.busy, onOpen = onOpenImportStep, onNext = onNext)
        } else if (state.step == OnboardingStep.NOTIFICATIONS) {
            NotificationsButtons(busy = state.busy, onAnswered = onNotificationsAnswered, onLater = onNext)
        } else if (state.step == OnboardingStep.PROTECTION) {
            ProtectionButtons(protection, busy = state.busy, onEnabled = onProtectionEnabled, onLater = onNext)
        } else {
            PrimaryButton(
                text = stringResource(if (state.step == OnboardingStep.PAYMENTS) R.string.onboarding_done else R.string.onboarding_next),
                onClick = onNext,
                modifier = Modifier.padding(16.dp),
                enabled = state.canContinue,
            )
        }
    }

    state.paymentForm?.let { formState ->
        PaymentSheet(state, formState, onPaymentFormChange, onClosePaymentForm, onAddPayment)
    }
}

@Composable
private fun StepProgress(step: Int, count: Int) {
    val colors = BudgetTheme.colors
    Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(count) { index ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        // Новый отрезок закрашивается плавно.
                        .background(animatedColor(if (index < step) colors.primary else colors.card2), RoundedCornerShape(2.dp)),
                )
            }
        }
        Text(stringResource(R.string.onboarding_step, step, count), fontSize = 13.sp, color = colors.muted)
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        color = BudgetTheme.colors.ink,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun IntroStep() {
    val colors = BudgetTheme.colors
    StepTitle(stringResource(R.string.onboarding_intro_title))
    Text(stringResource(R.string.onboarding_intro_text), style = MaterialTheme.typography.bodyLarge, color = colors.sub)
    PointsCard(
        listOf(R.string.onboarding_intro_point_bank, R.string.onboarding_intro_point_phone, R.string.onboarding_intro_point_input)
            .map { R.drawable.ic_check to stringResource(it) },
        tileContainer = colors.positiveSoft,
        tileContent = colors.positive,
        modifier = Modifier.padding(top = 18.dp),
        dividers = false,
    )
}

@Composable
private fun MoneyStep(
    state: OnboardingUiState,
    onKey: (AmountKey) -> Unit,
    onIncomeRegular: (Boolean) -> Unit,
    onIncomeDay: (Int) -> Unit,
    onIncomeDate: (LocalDate) -> Unit,
) {
    val colors = BudgetTheme.colors
    var showDay by rememberSaveable { mutableStateOf(false) }
    var showDate by rememberSaveable { mutableStateOf(false) }
    val spoken = SpokenMoney.amount(AmountInput.toKopecks(state.amountInput))

    StepTitle(stringResource(R.string.onboarding_money_title))
    Text(
        AmountInput.display(state.amountInput),
        style = MaterialTheme.typography.displayMedium,
        color = colors.ink,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { contentDescription = spoken },
    )
    Text(stringResource(R.string.onboarding_income_title), style = MaterialTheme.typography.titleMedium, color = colors.ink, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
    SegmentedChoice(
        options = listOf(true to stringResource(R.string.onboarding_income_regular), false to stringResource(R.string.onboarding_income_irregular)),
        selected = state.incomeRegular,
        onSelect = onIncomeRegular,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    if (state.incomeRegular) {
        ChoiceChip(
            R.drawable.ic_calendar,
            state.incomeDay?.let { stringResource(R.string.onboarding_income_day, it) } ?: stringResource(R.string.onboarding_income_day_pick),
            { showDay = true },
            Modifier.fillMaxWidth(),
        )
    } else {
        ChoiceChip(
            R.drawable.ic_calendar,
            state.incomeDate?.let(::formatDayMonth) ?: stringResource(R.string.onboarding_income_date_pick),
            { showDate = true },
            Modifier.fillMaxWidth(),
        )
    }
    state.nextIncome?.let {
        Text(stringResource(R.string.onboarding_income_next, formatDayMonth(it)), fontSize = 13.sp, color = colors.muted, modifier = Modifier.padding(top = 6.dp))
    }
    AmountKeypad(onKey, Modifier.padding(top = 12.dp))

    if (showDay) DayOfMonthDialog(state.incomeDay, onDismiss = { showDay = false }) { onIncomeDay(it); showDay = false }
    if (showDate) {
        BudgetDatePicker(state.incomeDate, stringResource(R.string.onboarding_income_date_pick), isAllowed = { it.isAfter(state.today) }, onDismiss = { showDate = false }) {
            onIncomeDate(it)
            showDate = false
        }
    }
}

@Composable
private fun DayOfMonthDialog(selected: Int?, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    val colors = BudgetTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.onboarding_day_dialog)) },
        text = {
            // Столбцов столько, сколько помещается кругов по 44 dp: семь со всеми промежутками шире диалога на телефоне,
            // и в сетке на 7 столбцов круги сжимались бы в овалы уже 44 dp.
            LazyVerticalGrid(columns = GridCells.Adaptive(44.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items((1..31).toList()) { day ->
                    val isSelected = day == selected
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                        Surface(
                            selected = isSelected,
                            onClick = { onSelect(day) },
                            shape = CircleShape,
                            color = if (isSelected) colors.primary else colors.card,
                            contentColor = if (isSelected) colors.onPrimary else colors.ink,
                            modifier = Modifier.size(44.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) { Text(day.toString(), fontSize = 15.sp) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PaymentsStep(state: OnboardingUiState, onOpenForm: () -> Unit, onRemove: (Int) -> Unit) {
    val colors = BudgetTheme.colors
    StepTitle(stringResource(R.string.onboarding_payments_title))
    Text(stringResource(R.string.onboarding_payments_text), style = MaterialTheme.typography.bodyLarge, color = colors.sub)
    if (state.payments.isNotEmpty()) {
        Column(
            modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth()
                .background(colors.card, RoundedCornerShape(22.dp))
                .padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
            state.payments.forEachIndexed { index, payment ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 56.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(payment.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.ink)
                        Text(formatDayMonth(payment.dueDate), fontSize = 13.sp, color = colors.muted)
                    }
                    Text(MoneyFormat.amount(payment.amount.kopecks), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                    IconButton(onClick = { onRemove(index) }) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.payment_delete) + " «${payment.name}»", tint = colors.muted)
                    }
                }
            }
        }
    }
    ChoiceChip(R.drawable.ic_add, stringResource(R.string.period_add_payment), onOpenForm, Modifier.padding(top = 16.dp).fillMaxWidth())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentSheet(
    state: OnboardingUiState,
    formState: PaymentFormState,
    onChange: (PaymentFormState) -> Unit,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
) {
    val colors = BudgetTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.background) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.payment_new_title), style = MaterialTheme.typography.titleLarge, color = colors.ink, modifier = Modifier.padding(bottom = 12.dp))
            PaymentForm(formState, state.categories, state.defaultCategoryId, onChange)
            PrimaryButton(stringResource(R.string.payment_add), onClick = onAdd, modifier = Modifier.padding(top = 16.dp), size = ButtonSize.Medium)
        }
    }
}
