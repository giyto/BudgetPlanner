package ru.hznik.budget.ui.home

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.hznik.budget.R
import ru.hznik.budget.domain.budget.PeriodStatus
import ru.hznik.budget.domain.model.PlannedPayment
import ru.hznik.budget.ui.autoimport.AutoImportStoppedBanner
import ru.hznik.budget.ui.autoimport.AutoImportViewModel
import ru.hznik.budget.ui.autoimport.RefreshOnResume
import ru.hznik.budget.ui.autoimport.rememberStepOpener
import ru.hznik.budget.ui.components.AddOperationFab
import ru.hznik.budget.ui.components.AnimatedBanner
import ru.hznik.budget.ui.components.ButtonSize
import ru.hznik.budget.ui.components.PrimaryButton
import ru.hznik.budget.ui.components.FittedRollingAmount
import ru.hznik.budget.ui.components.RollingAmount
import ru.hznik.budget.ui.components.animateFloatSaved
import ru.hznik.budget.ui.components.formatDayMonth
import ru.hznik.budget.ui.components.pressScale
import ru.hznik.budget.ui.format.MoneyFormat
import ru.hznik.budget.ui.format.SpokenMoney
import ru.hznik.budget.ui.operations.OperationRow
import ru.hznik.budget.ui.reconcile.MismatchBanner
import ru.hznik.budget.ui.reconcile.MismatchBannerViewModel
import ru.hznik.budget.ui.theme.BudgetTheme
import ru.hznik.budget.ui.theme.Motion
import ru.hznik.budget.ui.theme.motionTween
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.foundation.text.TextAutoSize
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.FlowRow

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
    onOpenReconcile: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    autoImport: AutoImportViewModel = hiltViewModel(),
    importBanners: ImportBannersViewModel = hiltViewModel(),
    mismatchBanner: MismatchBannerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val importState by autoImport.state.collectAsStateWithLifecycle()
    val bannersState by importBanners.state.collectAsStateWithLifecycle()
    val mismatches by mismatchBanner.mismatches.collectAsStateWithLifecycle()
    val openStep = rememberStepOpener(autoImport)
    RefreshOnResume(autoImport)
    // Холодный запуск считается до показа суммы «можно сегодня», а не до пустого кадра (задача 7.1, «Fully drawn»).
    ReportDrawnWhen { state.loaded && state.status != null }
    HomeScreen(
        state, onAddOperation, onOpenSettings, onOpenPeriod, onStartNewPeriod, onOpenOperation, onAllOperations,
        topBanner = {
            // Плашка дня дохода главнее: вторую в этот день не показываем (design.md add-notification-import, решение 7).
            val income = bannersState.suggestedIncome.takeIf { state.status?.isIncomeDue == false }
            AnimatedBanner(income, bottom = BANNER_GAP) {
                IncomeSuggestionBanner(it, onStart = onStartNewPeriod, onDismiss = importBanners::dismissSuggestion)
            }
        },
        banners = {
            // Расхождение с банком выше черновиков (spec home-screen «Плашка расхождения с банком»).
            AnimatedBanner(mismatches.takeIf { it.isNotEmpty() }, top = BANNER_GAP) { MismatchBanner(it, onOpenReconcile) }
            AnimatedBanner(bannersState.drafts.takeIf { it > 0 }, top = BANNER_GAP, saveable = true) { DraftsBanner(it, onOpenDrafts) }
            AnimatedBanner(importState.takeIf { it.stopped }, top = BANNER_GAP) { AutoImportStoppedBanner(it, onFix = openStep) }
        },
    )
}

/** Отступ между карточками главной; у плашек он входит в анимацию появления (spec ui-motion «Появление плашек»). */
private val BANNER_GAP = 12.dp

/**
 * Главная по макету Main.dc.html. Плашки в [topBanner] идут с отступом [BANNER_GAP] снизу, в [banners] — сверху
 * (`AnimatedBanner`).
 */
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
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    AnimatedBanner(Unit.takeIf { status.isIncomeDue }, bottom = BANNER_GAP) { IncomeDayBanner(onStartNewPeriod) }
                    topBanner()
                    HeroCard(status)
                    Spacer(Modifier.height(BANNER_GAP))
                    PeriodCard(status, state.nextPayment, onOpenPeriod)
                    banners()
                    Spacer(Modifier.height(BANNER_GAP))
                    RecentOperations(state, onOpenOperation, onAllOperations)
                }
            }
        }
        AddOperationFab(onAddOperation, Modifier.align(Alignment.BottomEnd))
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
        PrimaryButton(stringResource(R.string.home_start_new_period), onStartNewPeriod, size = ButtonSize.Compact)
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
    val amountColor by animateColorAsState(if (overspent) colors.warnOnHero else colors.heroText, motionTween(Motion.ITEM), label = "amount")
    val barColor by animateColorAsState(if (overspent) colors.warnOnHero else colors.heroProgress, motionTween(Motion.ITEM), label = "bar")
    val shownProgress by animateFloatSaved(progress)

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .pressScale()
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
            FittedRollingAmount(
                amount,
                status.canSpendToday,
                MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"),
                amountColor,
                minFontSize = MaterialTheme.typography.titleLarge.fontSize,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(sub, fontSize = 14.sp, color = colors.heroMuted)
        }
        Box(modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.16f))) {
            Box(modifier = Modifier.fillMaxWidth(shownProgress).height(10.dp).clip(RoundedCornerShape(5.dp)).background(barColor))
        }
        // При крупном шрифте «дни до дохода» уходят на следующую строку, а не рвут соседнюю подпись по буквам.
        FlowRow(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(spentText, fontSize = 13.sp, color = colors.heroMuted, modifier = Modifier.padding(end = 8.dp))
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
    val spentShare by animateFloatSaved(spent.toFloat() / total)
    val reserveShare by animateFloatSaved(reserve.toFloat() / total)
    val leftShare by animateFloatSaved(left.toFloat() / total)
    Surface(
        onClick = onOpenPeriod,
        shape = RoundedCornerShape(22.dp),
        color = colors.card,
        contentColor = colors.ink,
        modifier = Modifier.fillMaxWidth().pressScale(),
    ) {
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
                // Отрезок, доля которого плавно уходит в ноль, исчезает в конце анимации.
                if (spentShare > MIN_SHARE) Box(Modifier.weight(spentShare).height(8.dp).background(colors.ink))
                if (reserveShare > MIN_SHARE) Box(Modifier.weight(reserveShare).height(8.dp).background(colors.reserve))
                if (leftShare > MIN_SHARE) Box(Modifier.weight(leftShare).height(8.dp).background(colors.left))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

private const val MIN_SHARE = 0.0001f

@Composable
private fun PeriodNumber(label: String, kopecks: Long, color: Color, modifier: Modifier = Modifier) {
    val text = (if (kopecks < 0) MoneyFormat.MINUS.toString() else "") + MoneyFormat.amount(kopecks)
    val spoken = label + " " + (if (kopecks < 0) "минус " else "") + SpokenMoney.amount(kopecks)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = modifier.clearAndSetSemantics { contentDescription = spoken }) {
        // Три числа в ряд: при крупном шрифте подпись и сумма уменьшаются, чтобы не наезжать на соседние.
        Text(label, color = BudgetTheme.colors.muted, maxLines = 1, softWrap = false, autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 12.sp))
        val shownColor by animateColorAsState(color, motionTween(Motion.ITEM), label = "number")
        FittedRollingAmount(text, kopecks, LocalTextStyle.current.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), shownColor, minFontSize = 10.sp)
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
