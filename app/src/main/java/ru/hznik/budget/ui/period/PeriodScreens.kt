package ru.hznik.budget.ui.period

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import ru.hznik.budget.R
import ru.hznik.budget.domain.model.PaymentRepeat
import ru.hznik.budget.ui.components.AmountInput
import ru.hznik.budget.ui.components.AmountKeypad
import ru.hznik.budget.ui.components.BudgetDatePicker
import ru.hznik.budget.ui.components.CategoryTile
import ru.hznik.budget.ui.components.ChoiceChip
import ru.hznik.budget.ui.components.NavigationHeader
import ru.hznik.budget.ui.components.RollingAmount
import ru.hznik.budget.ui.components.PaymentForm
import ru.hznik.budget.ui.components.formatDayMonth
import ru.hznik.budget.ui.components.parseRubles
import ru.hznik.budget.ui.format.MoneyFormat
import ru.hznik.budget.ui.format.SpokenMoney
import ru.hznik.budget.ui.theme.BudgetTheme

/** Сбор одноразовых событий «закрыть» только на активном экране. */
@Composable
private fun CloseOnEvent(events: Flow<Unit>, onClose: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(events, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) { events.collect { onClose() } }
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

// --- Период и платежи ---

@Composable
fun PeriodRoute(
    onBack: () -> Unit,
    onStartNewPeriod: () -> Unit,
    onAddPayment: () -> Unit,
    onEditPayment: (Long) -> Unit,
    viewModel: PeriodViewModel = hiltViewModel(),
    reconcile: @Composable () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = BudgetTheme.colors
    var editAmount by rememberSaveable { mutableStateOf(false) }
    var editDate by rememberSaveable { mutableStateOf(false) }
    val status = state.status

    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        NavigationHeader(stringResource(R.string.period_title), R.drawable.ic_back, stringResource(R.string.action_back), onBack)
        if (status == null) return@Column
        val period = status.period
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 8.dp)) {
                Text(
                    stringResource(R.string.period_dates, formatDayMonth(period.startDate), formatDayMonth(period.nextIncomeDate.minusDays(1))),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.ink,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                PeriodLine(stringResource(R.string.period_income_date), formatDayMonth(period.nextIncomeDate)) { editDate = true }
                HorizontalDivider(color = colors.line)
                PeriodLine(stringResource(R.string.period_start_amount), MoneyFormat.amount(period.startAmountKopecks), period.startAmountKopecks) { editAmount = true }
                HorizontalDivider(color = colors.line)
                PeriodLine(stringResource(R.string.period_budget), MoneyFormat.amount(status.budget), status.budget, onChange = null)
            }
            OutlinedButton(onClick = onStartNewPeriod, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(stringResource(R.string.home_start_new_period), color = colors.primaryText, fontWeight = FontWeight.SemiBold)
            }
            reconcile()
            Text(
                stringResource(R.string.period_payments),
                style = MaterialTheme.typography.titleMedium,
                color = colors.ink,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp).semantics { heading() },
            )
            Column(modifier = Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(22.dp)).padding(horizontal = 14.dp, vertical = 4.dp)) {
                if (state.payments.isEmpty()) {
                    Text(stringResource(R.string.period_no_payments), fontSize = 14.sp, color = colors.muted, modifier = Modifier.padding(vertical = 14.dp))
                }
                state.payments.forEachIndexed { index, payment ->
                    val category = state.categories[payment.categoryId]
                    val repeat = stringResource(if (payment.repeat == PaymentRepeat.MONTHLY) R.string.period_monthly else R.string.period_once)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { onEditPayment(payment.id) }.padding(vertical = 8.dp),
                    ) {
                        if (category != null) CategoryTile(category)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(payment.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.ink)
                            Text("${formatDayMonth(payment.dueDate)} · $repeat · ${MoneyFormat.amount(payment.amount.kopecks)}", fontSize = 13.sp, color = colors.muted)
                        }
                        if (payment.isPaid) {
                            Text(stringResource(R.string.period_paid), fontSize = 13.sp, color = colors.positive, fontWeight = FontWeight.SemiBold)
                        } else {
                            TextButton(onClick = { viewModel.markPaid(payment.id) }, modifier = Modifier.semantics { contentDescription = "Отметить «${payment.name}» оплаченным" }) {
                                Text(stringResource(R.string.period_mark_paid), color = colors.primaryText, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    if (index < state.payments.lastIndex) HorizontalDivider(color = colors.line)
                }
            }
            ChoiceChip(R.drawable.ic_add, stringResource(R.string.period_add_payment), onAddPayment, Modifier.fillMaxWidth().padding(bottom = 16.dp))
        }

        if (editDate) {
            BudgetDatePicker(period.nextIncomeDate, stringResource(R.string.period_income_date), isAllowed = { it.isAfter(period.startDate) }, onDismiss = { editDate = false }) {
                viewModel.changeIncomeDate(it)
                editDate = false
            }
        }
        if (editAmount) {
            AmountDialog(AmountInput.fromKopecks(period.startAmountKopecks), onDismiss = { editAmount = false }) {
                viewModel.changeStartAmount(it)
                editAmount = false
            }
        }
    }
}

/** Строка карточки периода; у суммы ([kopecks] не `null`) изменившиеся цифры прокручиваются. */
@Composable
private fun PeriodLine(label: String, value: String, kopecks: Long? = null, onChange: (() -> Unit)?) {
    val colors = BudgetTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (onChange != null) Modifier.clickable(onClickLabel = stringResource(R.string.period_change), onClick = onChange) else Modifier),
    ) {
        Text(label, fontSize = 14.sp, color = colors.muted, modifier = Modifier.weight(1f))
        if (kopecks != null) {
            RollingAmount(value, kopecks, LocalTextStyle.current.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), colors.ink)
        } else {
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
        }
        if (onChange != null) {
            Spacer(Modifier.width(8.dp))
            Icon(painterResource(R.drawable.ic_comment), contentDescription = null, tint = colors.muted)
        }
    }
}

@Composable
private fun AmountDialog(initial: String, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    // Ноль — допустимая стартовая сумма, отрицательная — нет.
    val kopecks = if (text.trim() == "0") 0L else parseRubles(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.period_amount_dialog)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(10) },
                label = { Text(stringResource(R.string.payment_amount)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = { TextButton(onClick = { kopecks?.let(onConfirm) }, enabled = kopecks != null) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// --- Обязательный платёж ---

@Composable
fun PaymentEditorRoute(onClose: () -> Unit, viewModel: PaymentEditorViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CloseOnEvent(viewModel.close, onClose)
    val colors = BudgetTheme.colors
    Column(modifier = Modifier.fillMaxSize().background(colors.background).imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NavigationHeader(
                stringResource(if (state.isEdit) R.string.payment_edit_title else R.string.payment_new_title),
                R.drawable.ic_back,
                stringResource(R.string.action_back),
                onClose,
                Modifier.weight(1f),
            )
            if (state.isEdit) {
                IconButton(onClick = viewModel::delete, modifier = Modifier.padding(end = 4.dp)) {
                    Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.payment_delete), tint = colors.ink)
                }
            }
        }
        if (state.loaded) {
            PaymentForm(
                state.form,
                state.categories,
                state.defaultCategoryId,
                viewModel::onChange,
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        PrimaryButton(stringResource(R.string.action_save), state.loaded, viewModel::save, Modifier.padding(16.dp))
    }
}

// --- Новый период ---

@Composable
fun NewPeriodRoute(onClose: () -> Unit, viewModel: NewPeriodViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CloseOnEvent(viewModel.close, onClose)
    val colors = BudgetTheme.colors
    var pickDate by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        NavigationHeader(stringResource(R.string.new_period_title), R.drawable.ic_close, stringResource(R.string.action_close), onClose)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.new_period_amount_title), style = MaterialTheme.typography.titleMedium, color = colors.ink)
            Text(
                AmountInput.display(state.amountInput),
                style = MaterialTheme.typography.displayMedium,
                color = colors.ink,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics { contentDescription = SpokenMoney.amount(state.amountKopecks) },
            )
            Text(stringResource(R.string.new_period_amount_hint), fontSize = 13.sp, color = colors.muted)
            if (state.incomeTodayKopecks > 0) {
                Text(
                    stringResource(R.string.new_period_income_today, MoneyFormat.amount(state.incomeTodayKopecks)),
                    fontSize = 13.sp,
                    color = colors.ink,
                    modifier = Modifier.fillMaxWidth().background(colors.warnSoft, RoundedCornerShape(12.dp)).padding(10.dp),
                )
            }
            ChoiceChip(
                R.drawable.ic_calendar,
                state.nextIncome?.let { stringResource(R.string.onboarding_income_next, formatDayMonth(it)) } ?: stringResource(R.string.onboarding_income_date_pick),
                { pickDate = true },
                Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.new_period_budget, MoneyFormat.amount(state.budgetKopecks)),
                style = MaterialTheme.typography.titleSmall,
                color = colors.ink,
            )
            AmountKeypad(viewModel::onKey, Modifier.padding(top = 4.dp))
        }
        PrimaryButton(stringResource(R.string.new_period_start), state.canStart, viewModel::start, Modifier.padding(16.dp))
    }

    if (pickDate) {
        BudgetDatePicker(state.nextIncome, stringResource(R.string.period_income_date), isAllowed = { it.isAfter(state.today) }, onDismiss = { pickDate = false }) {
            viewModel.onNextIncome(it)
            pickDate = false
        }
    }
}
