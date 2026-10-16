package ru.hznik.budget.ui.reconcile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.hznik.budget.R
import ru.hznik.budget.domain.reconcile.Mismatch
import ru.hznik.budget.domain.reconcile.ReconAccount
import ru.hznik.budget.domain.reconcile.ReconState
import ru.hznik.budget.domain.usecase.Resolution
import ru.hznik.budget.ui.components.AmountInput
import ru.hznik.budget.ui.components.AmountKey
import ru.hznik.budget.ui.components.AmountKeypad
import ru.hznik.budget.ui.components.NavigationHeader
import ru.hznik.budget.ui.components.formatDayMonth
import ru.hznik.budget.ui.format.MoneyFormat
import ru.hznik.budget.ui.format.SpokenMoney
import ru.hznik.budget.ui.format.accountLabel
import ru.hznik.budget.ui.theme.BudgetTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

const val RECONCILE_APPLY_TAG = "reconcile_apply"
const val RECONCILE_MANUAL_SUBMIT_TAG = "reconcile_manual_submit"

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun ReconcileRoute(
    onBack: () -> Unit,
    onFindMissing: (ReconAccount, Instant) -> Unit,
    viewModel: ReconcileViewModel = hiltViewModel(),
    onFinished: () -> Unit = onBack,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val finished by viewModel.finished.collectAsStateWithLifecycle()
    // Не через dropUnlessResumed: результат может прийти, когда приложение уже в фоне, и закрытие не должно потеряться.
    LaunchedEffect(finished) { if (finished) onFinished() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    val openedForManual = viewModel.openedForManual
    // Ручной ввод, открытый с экрана сверки, закрывается назад к ней; открытый с экрана периода — закрывает экран.
    BackHandler(enabled = state.manual != null && !openedForManual) { viewModel.cancelManual() }
    val back = if (state.manual != null && !openedForManual) viewModel::cancelManual else onBack

    val colors = BudgetTheme.colors
    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        NavigationHeader(stringResource(R.string.reconcile_title), R.drawable.ic_back, stringResource(R.string.action_back), back)
        val manual = state.manual
        val selected = state.selected
        when {
            !state.loaded -> Unit
            manual != null -> ManualContent(manual, viewModel::onManualAccount, viewModel::onManualKey, viewModel::submitManual)
            selected == null -> NoMismatchContent(state.rows, onManual = { viewModel.startManual() })
            state.multiCard -> CardsContent(
                state, selected,
                onSelect = viewModel::onSelect,
                onAdjust = { viewModel.resolve(Resolution.ADJUST) },
                onFind = { onFindMissing(selected.account, selected.anchorAt) },
                onSkip = { viewModel.resolve(Resolution.SKIP) },
            )
            else -> SingleCardContent(
                state, selected,
                onOption = viewModel::onOption,
                onApply = {
                    when (state.option) {
                        ReconcileOption.ADJUST -> viewModel.resolve(Resolution.ADJUST)
                        ReconcileOption.FIND -> onFindMissing(selected.account, selected.anchorAt)
                        ReconcileOption.SKIP -> viewModel.resolve(Resolution.SKIP)
                    }
                },
                onManual = { viewModel.startManual(selected.account) },
            )
        }
    }
}

private fun balance(kopecks: Long): String = MoneyFormat.balance(kopecks)

private fun signedDiff(kopecks: Long): String = (if (kopecks < 0) MoneyFormat.MINUS.toString() else "+") + MoneyFormat.amount(kopecks)

private fun spokenDiff(kopecks: Long): String = (if (kopecks < 0) "минус " else "плюс ") + SpokenMoney.amount(kopecks)

private fun moment(at: Instant, zone: ZoneId, today: LocalDate): String {
    val local = at.atZone(zone)
    val day = if (local.toLocalDate() == today) "сегодня" else formatDayMonth(local.toLocalDate())
    return "$day, ${TIME.format(local)}"
}

/** Вид одной карты по макету Reconcile. */
@Composable
private fun SingleCardContent(
    state: ReconcileUiState,
    selected: ReconState,
    onOption: (ReconcileOption) -> Unit,
    onApply: () -> Unit,
    onManual: () -> Unit,
) {
    val colors = BudgetTheme.colors
    val pending = selected.pending ?: return
    val today = state.today
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Text("${accountLabel(selected.account)} · ${moment(pending.at, state.zone, today)}", fontSize = 14.sp, color = colors.muted, modifier = Modifier.padding(start = 4.dp))
            ComparisonCard(pending, state.zone)
            Text(
                stringResource(if (pending.diffKopecks < 0) R.string.reconcile_reason_less else R.string.reconcile_reason_more),
                fontSize = 15.sp, color = colors.sub, modifier = Modifier.padding(horizontal = 4.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.selectableGroup()) {
                OptionCard(
                    selected = state.option == ReconcileOption.ADJUST,
                    title = stringResource(if (pending.diffKopecks < 0) R.string.reconcile_option_expense else R.string.reconcile_option_income),
                    subtitle = stringResource(R.string.reconcile_option_adjust_sub, signedDiff(pending.diffKopecks)),
                    onClick = { onOption(ReconcileOption.ADJUST) },
                )
                OptionCard(
                    selected = state.option == ReconcileOption.FIND,
                    title = stringResource(R.string.reconcile_option_find),
                    subtitle = stringResource(R.string.reconcile_option_find_sub, formatDayMonth(selected.anchorAt.atZone(state.zone).toLocalDate())),
                    onClick = { onOption(ReconcileOption.FIND) },
                )
                OptionCard(
                    selected = state.option == ReconcileOption.SKIP,
                    title = stringResource(R.string.reconcile_option_skip),
                    subtitle = stringResource(R.string.reconcile_option_skip_sub, balance(pending.bankKopecks)),
                    onClick = { onOption(ReconcileOption.SKIP) },
                )
            }
            TextButton(onClick = onManual, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.reconcile_enter_manually), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.primaryText)
            }
        }
        val applyLabel = when (state.option) {
            ReconcileOption.ADJUST -> R.string.reconcile_apply
            ReconcileOption.FIND -> R.string.reconcile_find
            ReconcileOption.SKIP -> R.string.reconcile_option_skip
        }
        PrimaryButton(stringResource(applyLabel), onApply, Modifier.testTag(RECONCILE_APPLY_TAG))
    }
}

@Composable
private fun ComparisonCard(pending: Mismatch, zone: ZoneId) {
    val colors = BudgetTheme.colors
    Column(modifier = Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 6.dp)) {
        ComparisonRow(stringResource(R.string.reconcile_in_bank), stringResource(R.string.reconcile_in_bank_at, TIME.format(pending.at.atZone(zone))), balance(pending.bankKopecks), SpokenMoney.amount(pending.bankKopecks))
        HorizontalDivider(color = colors.line)
        ComparisonRow(stringResource(R.string.reconcile_in_app), stringResource(R.string.reconcile_in_app_sub), balance(pending.expectedKopecks), SpokenMoney.amount(pending.expectedKopecks))
        HorizontalDivider(color = colors.line)
        val diffLabel = stringResource(R.string.reconcile_difference)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).clearAndSetSemantics { contentDescription = "$diffLabel: ${spokenDiff(pending.diffKopecks)}" },
        ) {
            Text(diffLabel, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink, modifier = Modifier.weight(1f))
            Text(
                signedDiff(pending.diffKopecks), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.warn,
                modifier = Modifier.background(colors.warnSoft, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ComparisonRow(title: String, subtitle: String, amount: String, spokenAmount: String) {
    val colors = BudgetTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clearAndSetSemantics { contentDescription = "$title, $subtitle: $spokenAmount" },
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colors.ink)
            Text(subtitle, fontSize = 13.sp, color = colors.muted)
        }
        Text(amount, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
    }
}

@Composable
private fun OptionCard(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    val colors = BudgetTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.card, RoundedCornerShape(20.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.line, RoundedCornerShape(20.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = colors.primary, unselectedColor = colors.chipBorder))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            Text(subtitle, fontSize = 14.sp, color = colors.muted)
        }
    }
}

/** Вид нескольких карт по макету ReconcileCards. */
@Composable
private fun CardsContent(
    state: ReconcileUiState,
    selected: ReconState,
    onSelect: (ReconAccount) -> Unit,
    onAdjust: () -> Unit,
    onFind: () -> Unit,
    onSkip: () -> Unit,
) {
    val colors = BudgetTheme.colors
    val pending = selected.pending ?: return
    val label = accountLabel(selected.account)
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(colors.warnSoft, RoundedCornerShape(22.dp)).padding(16.dp)) {
            Text(stringResource(R.string.reconcile_cards_header, label), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            Text(
                stringResource(if (pending.diffKopecks < 0) R.string.reconcile_cards_less else R.string.reconcile_cards_more, MoneyFormat.amount(pending.diffKopecks)),
                fontSize = 14.sp, color = colors.sub, modifier = Modifier.padding(top = 6.dp),
            )
        }
        Column(modifier = Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 4.dp).selectableGroup()) {
            state.rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = colors.line)
                CardRow(row, isSelected = row.account == selected.account, onSelect = { onSelect(row.account) })
            }
        }
        Text(stringResource(R.string.reconcile_cards_question, label), fontSize = 14.sp, color = colors.muted, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
        val amount = MoneyFormat.amount(pending.diffKopecks)
        Button(
            onClick = onAdjust,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(RECONCILE_APPLY_TAG),
        ) { Text(stringResource(if (pending.diffKopecks < 0) R.string.reconcile_cards_expense else R.string.reconcile_cards_income, amount), fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        OutlinedButton(onClick = onFind, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(stringResource(R.string.reconcile_option_find), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
        }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.reconcile_option_skip), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.muted)
        }
    }
}

@Composable
private fun CardRow(row: ReconState, isSelected: Boolean, onSelect: () -> Unit) {
    val colors = BudgetTheme.colors
    val pending = row.pending
    val label = accountLabel(row.account)
    val expected = pending?.expectedKopecks ?: row.anchorKopecks
    val bank = pending?.bankKopecks ?: row.anchorKopecks
    val details = stringResource(R.string.reconcile_cards_row, balance(expected), balance(bank))
    val match = stringResource(R.string.reconcile_cards_match)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .then(if (pending != null) Modifier.selectable(selected = isSelected, role = Role.RadioButton, onClick = onSelect) else Modifier)
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 8.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).background(colors.card2, RoundedCornerShape(12.dp))) {
            Icon(painterResource(R.drawable.ic_card), contentDescription = null, tint = colors.muted, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (isSelected) colors.primaryText else colors.ink)
            Text(details, fontSize = 13.sp, color = colors.muted)
        }
        if (pending != null) {
            Text(signedDiff(pending.diffKopecks), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.warn)
        } else {
            Text(match, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.positive)
        }
    }
}

@Composable
private fun NoMismatchContent(rows: List<ReconState>, onManual: () -> Unit) {
    val colors = BudgetTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(colors.positiveSoft, RoundedCornerShape(22.dp)).padding(16.dp)) {
            Text(stringResource(R.string.reconcile_none), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            Text(stringResource(R.string.reconcile_none_text), fontSize = 14.sp, color = colors.sub, modifier = Modifier.padding(top = 6.dp))
        }
        if (rows.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 4.dp)) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(color = colors.line)
                    CardRow(row, isSelected = false, onSelect = {})
                }
            }
        }
        OutlinedButton(onClick = onManual, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(stringResource(R.string.reconcile_enter_manually), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
        }
    }
}

/** Ручная сверка по макету ReconcileManual: выбор счёта, ввод остатка с клавиатуры. */
@Composable
private fun ManualContent(
    manual: ManualState,
    onAccount: (ReconAccount) -> Unit,
    onKey: (AmountKey) -> Unit,
    onSubmit: () -> Unit,
) {
    val colors = BudgetTheme.colors
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.reconcile_manual_subtitle), fontSize = 14.sp, color = colors.muted, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
            Column(modifier = Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 4.dp).selectableGroup()) {
                manual.accounts.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = colors.line)
                    val selected = item.account == manual.selected
                    val subtitle = when {
                        item.account.isManual && item.expectedKopecks == null -> stringResource(R.string.reconcile_account_manual_sub)
                        item.expectedKopecks != null -> stringResource(R.string.reconcile_manual_expected, balance(item.expectedKopecks))
                        else -> stringResource(R.string.reconcile_manual_no_anchor)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { onAccount(item.account) })
                            .heightIn(min = 56.dp)
                            .padding(vertical = 6.dp),
                    ) {
                        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = colors.primary, unselectedColor = colors.chipBorder))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(accountLabel(item.account), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                            Text(subtitle, fontSize = 13.sp, color = colors.muted)
                        }
                    }
                }
            }
            Text(
                AmountInput.display(manual.input), style = MaterialTheme.typography.displayMedium, color = colors.ink, textAlign = TextAlign.Center, maxLines = 1,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            )
            val expected = manual.expectedKopecks
            if (expected != null && manual.input.isNotEmpty()) {
                val diff = manual.inputKopecks - expected
                val (text, soft, ink) = when {
                    abs(diff) <= 100 -> Triple(stringResource(R.string.reconcile_manual_same), colors.positiveSoft, colors.positive)
                    diff < 0 -> Triple(stringResource(R.string.reconcile_manual_less, MoneyFormat.amount(diff)), colors.warnSoft, colors.warn)
                    else -> Triple(stringResource(R.string.reconcile_manual_more, MoneyFormat.amount(diff)), colors.warnSoft, colors.warn)
                }
                Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ink, modifier = Modifier.background(soft, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
        AmountKeypad(onKey, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        PrimaryButton(stringResource(R.string.reconcile_manual_submit), onSubmit, Modifier.testTag(RECONCILE_MANUAL_SUBMIT_TAG), enabled = manual.selected != null && manual.input.isNotEmpty())
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = BudgetTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
        modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp).heightIn(min = 56.dp),
    ) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}
