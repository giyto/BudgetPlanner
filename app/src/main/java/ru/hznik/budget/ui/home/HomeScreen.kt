package ru.hznik.budget.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.hznik.budget.R
import ru.hznik.budget.domain.budget.PeriodStatus
import ru.hznik.budget.domain.model.PlannedPayment
import ru.hznik.budget.ui.autoimport.AutoImportStoppedBanner
import ru.hznik.budget.ui.autoimport.AutoImportViewModel
import ru.hznik.budget.ui.autoimport.RefreshOnResume
import ru.hznik.budget.ui.autoimport.rememberStepOpener
import ru.hznik.budget.ui.components.formatDayMonth
import ru.hznik.budget.ui.format.MoneyFormat
import ru.hznik.budget.ui.format.SpokenMoney
import ru.hznik.budget.ui.operations.OperationRow
import ru.hznik.budget.ui.theme.BudgetTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

const val HOME_HERO_TAG = "home_hero"

@Composable
fun HomeRoute(
    onAddOperation: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPeriod: () -> Unit,
    onStartNewPeriod: () -> Unit,
    onOpenOperation: (Long) -> Unit,
    onAllOperations: () -> Unit,
    onOpenDrafts: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    autoImport: AutoImportViewModel = hiltViewModel(),
    importBanners: ImportBannersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val importState by autoImport.state.collectAsStateWithLifecycle()
    val bannersState by importBanners.state.collectAsStateWithLifecycle()
    val openStep = rememberStepOpener(autoImport)
    RefreshOnResume(autoImport)
    HomeScreen(
        state, onAddOperation, onOpenSettings, onOpenPeriod, onStartNewPeriod, onOpenOperation, onAllOperations,
        topBanner = {
            // Плашка дня дохода главнее: вторую в этот день не показываем (design.md add-notification-import, решение 7).
            val income = bannersState.suggestedIncome
            if (income != null && state.status?.isIncomeDue == false) {
                IncomeSuggestionBanner(income, onStart = onStartNewPeriod, onDismiss = importBanners::dismissSuggestion)
            }
        },
        banners = {
            DraftsBanner(bannersState.drafts, onOpenDrafts)
            AutoImportStoppedBanner(importState, onFix = openStep)
        },
    )
}

/** Главная по макету Main.dc.html. */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onAddOperation: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPeriod: () -> Unit,
    onStartNewPeriod: () -> Unit,
    onOpenOperation: (Long) -> Unit,
    onAllOperations: () -> Unit,
    modifier: Modifier = Modifier,
    topBanner: @Composable () -> Unit = {},
    banners: @Composable () -> Unit = {},
) {
    val colors = BudgetTheme.colors
    val status = state.status
    Box(modifier = modifier.fillMaxSize().background(colors.background)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 96.dp),
        ) {
            Header(status?.today, onOpenSettings)
            if (status != null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                    if (status.isIncomeDue) IncomeDayBanner(onStartNewPeriod)
                    topBanner()
                    HeroCard(status)
                    PeriodCard(status, state.nextPayment, onOpenPeriod)
                    banners()
                    RecentOperations(state, onOpenOperation, onAllOperations)
                }
            }
        }
        FloatingActionButton(
            onClick = onAddOperation,
            shape = RoundedCornerShape(20.dp),
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp).size(60.dp),
        ) {
            Icon(painterResource(R.drawable.ic_add), stringResource(R.string.action_add_operation), Modifier.size(26.dp))
        }
    }
}

private val RU = Locale.forLanguageTag("ru")
private val HEADER_DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM", RU)

@Composable
private fun Header(today: LocalDate?, onOpenSettings: () -> Unit) {
    val colors = BudgetTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (today != null) {
                Text(HEADER_DATE.format(today).replaceFirstChar { it.titlecase(RU) }, fontSize = 13.sp, color = colors.muted)
            }
            Text(
                stringResource(R.string.home_title),
                style = MaterialTheme.typography.titleLarge,
                color = colors.ink,
                modifier = Modifier.semantics { heading() },
            )
        }
        FilledIconButton(
            onClick = onOpenSettings,
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.card, contentColor = colors.ink),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.action_settings))
        }
    }
}

@Composable
private fun IncomeDayBanner(onStartNewPeriod: () -> Unit) {
    val colors = BudgetTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().background(colors.warnSoft, RoundedCornerShape(18.dp)).padding(14.dp),
    ) {
        Text(stringResource(R.string.home_income_day), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
        Text(stringResource(R.string.home_income_day_text), fontSize = 13.sp, color = colors.muted)
        Button(
            onClick = onStartNewPeriod,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.home_start_new_period))
        }
    }
}

@Composable
private fun HeroCard(status: PeriodStatus) {
    val colors = BudgetTheme.colors
    val overspent = status.isOverspent
    val label = stringResource(if (overspent) R.string.home_overspent else R.string.home_can_spend)
    val amount = if (overspent) MoneyFormat.MINUS + MoneyFormat.amount(-status.canSpendToday) else MoneyFormat.amount(status.canSpendToday)
    val sub = when {
        !overspent -> stringResource(R.string.home_of_daily_limit, MoneyFormat.amount(status.dailyLimit))
        status.tomorrowLimit != null -> stringResource(R.string.home_tomorrow_limit, MoneyFormat.amount(status.tomorrowLimit))
        else -> stringResource(R.string.home_tomorrow_new_period)
    }
    val progress = when {
        overspent -> 1f
        status.dailyLimit <= 0 -> 1f
        else -> (status.spentToday.toFloat() / status.dailyLimit).coerceIn(0f, 1f)
    }
    val progressLabel = if (overspent) {
        stringResource(R.string.home_limit_exceeded)
    } else {
        stringResource(R.string.home_daily_progress, (progress * 100).toInt())
    }
    val days = status.daysLeft
    val daysText = stringResource(R.string.home_days_left, days.toInt(), SpokenMoney.plural(days, "день", "дня", "дней"))
    val spentText = stringResource(R.string.home_spent_today, MoneyFormat.amount(status.spentToday))
    val spokenAmount = if (overspent) "минус " + SpokenMoney.amount(-status.canSpendToday) else SpokenMoney.amount(status.canSpendToday)
    val spoken = "$label $spokenAmount. $sub. $progressLabel. $spentText. $daysText"
    val amountColor = if (overspent) colors.warnOnHero else colors.heroText
    val barColor = if (overspent) colors.warnOnHero else colors.heroProgress

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colors.hero)
            // В тёмной теме у графитовой карточки рамка, как в макете DarkMain.
            .then(if (colors.isDark) Modifier.border(1.dp, colors.heroBorder, RoundedCornerShape(28.dp)) else Modifier)
            .padding(horizontal = 22.dp, vertical = 20.dp)
            .testTag(HOME_HERO_TAG)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, fontSize = 15.sp, color = colors.heroMuted)
            Text(amount, style = MaterialTheme.typography.displayLarge, color = amountColor, maxLines = 1)
            Text(sub, fontSize = 14.sp, color = colors.heroMuted)
        }
        Box(modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.16f))) {
            Box(modifier = Modifier.fillMaxWidth(progress).height(10.dp).clip(RoundedCornerShape(5.dp)).background(barColor))
        }
        Row {
            Text(spentText, fontSize = 13.sp, color = colors.heroMuted, modifier = Modifier.weight(1f))
            Text(daysText, fontSize = 13.sp, color = colors.heroMuted)
        }
    }
}

@Composable
private fun PeriodCard(status: PeriodStatus, nextPayment: PlannedPayment?, onOpenPeriod: () -> Unit) {
    val colors = BudgetTheme.colors
    val spent = status.spentInPeriod.coerceAtLeast(0)
    val reserve = status.reserve.coerceAtLeast(0)
    val left = status.left.coerceAtLeast(0)
    val total = (spent + reserve + left).coerceAtLeast(1)
    Surface(onClick = onOpenPeriod, shape = RoundedCornerShape(22.dp), color = colors.card, contentColor = colors.ink, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.home_period_until, formatDayMonth(status.period.nextIncomeDate)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(stringResource(R.string.home_period_of, MoneyFormat.amount(status.budget)), fontSize = 13.sp, color = colors.muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
                if (spent > 0) Box(Modifier.weight(spent.toFloat() / total).height(8.dp).background(colors.ink))
                if (reserve > 0) Box(Modifier.weight(reserve.toFloat() / total).height(8.dp).background(colors.reserve))
                if (left > 0) Box(Modifier.weight(left.toFloat() / total).height(8.dp).background(colors.left))
            }
            Row {
                PeriodNumber(stringResource(R.string.home_spent), status.spentInPeriod, colors.ink, Modifier.weight(1f))
                PeriodNumber(stringResource(R.string.home_reserve), status.reserve, colors.ink, Modifier.weight(1f))
                PeriodNumber(stringResource(R.string.home_left), status.left, if (status.left >= 0) colors.positive else colors.warn, Modifier.weight(1f))
            }
            if (nextPayment != null) {
                HorizontalDivider(color = colors.line)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_calendar), contentDescription = null, tint = colors.sub, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.home_next_payment, nextPayment.name, formatDayMonth(nextPayment.dueDate)),
                        fontSize = 13.sp,
                        color = colors.sub,
                        modifier = Modifier.weight(1f),
                    )
                    Text(MoneyFormat.amount(nextPayment.amount.kopecks), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                }
            }
        }
    }
}

@Composable
private fun PeriodNumber(label: String, kopecks: Long, color: Color, modifier: Modifier = Modifier) {
    val text = (if (kopecks < 0) MoneyFormat.MINUS.toString() else "") + MoneyFormat.amount(kopecks)
    val spoken = label + " " + (if (kopecks < 0) "минус " else "") + SpokenMoney.amount(kopecks)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = modifier.clearAndSetSemantics { contentDescription = spoken }) {
        Text(label, fontSize = 12.sp, color = BudgetTheme.colors.muted)
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

@Composable
private fun RecentOperations(state: HomeUiState, onOpenOperation: (Long) -> Unit, onAllOperations: () -> Unit) {
    val colors = BudgetTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
            Text(stringResource(R.string.home_recent), style = MaterialTheme.typography.titleMedium, color = colors.ink, modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = onAllOperations) {
                Text(stringResource(R.string.home_all), color = colors.primaryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(modifier = Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(22.dp)).padding(horizontal = 14.dp, vertical = 4.dp)) {
            if (state.recent.isEmpty()) {
                Text(stringResource(R.string.home_no_operations), fontSize = 14.sp, color = colors.muted, modifier = Modifier.padding(vertical = 14.dp))
            }
            state.recent.forEachIndexed { index, item ->
                OperationRow(item, onOpenOperation)
                if (index < state.recent.lastIndex) HorizontalDivider(color = colors.line)
            }
        }
    }
}
