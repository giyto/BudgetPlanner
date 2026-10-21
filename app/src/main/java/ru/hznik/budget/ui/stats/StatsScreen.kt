package ru.hznik.budget.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.hznik.budget.R
import ru.hznik.budget.domain.stats.DateRange
import ru.hznik.budget.domain.stats.MerchantSpending
import ru.hznik.budget.domain.stats.PeriodSummary
import ru.hznik.budget.domain.stats.SpendingChange
import ru.hznik.budget.domain.usecase.StatsMode
import ru.hznik.budget.ui.components.NavigationHeader
import ru.hznik.budget.ui.components.ScreenTitle
import ru.hznik.budget.ui.components.SegmentedChoice
import ru.hznik.budget.ui.format.MoneyFormat
import ru.hznik.budget.ui.format.SpokenMoney
import ru.hznik.budget.ui.theme.BudgetTheme

const val STATS_LIST_TAG = "stats_list"

private val CardShape = RoundedCornerShape(22.dp)

/** Раздел «Статистика» (spec statistics, макет Stats.dc.html). */
@Composable
fun StatsRoute(
    onOpenPeriod: (Long) -> Unit,
    onOpenCategory: (Long, DateRange) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StatsScreen(state, viewModel::onModeSelected, onOpenPeriod, onOpenCategory, modifier)
}

@Composable
fun StatsScreen(
    state: StatsUiState,
    onModeSelected: (StatsMode) -> Unit,
    onOpenPeriod: (Long) -> Unit,
    onOpenCategory: (Long, DateRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BudgetTheme.colors
    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize().background(colors.background).testTag(STATS_LIST_TAG),
    ) {
        item { ScreenTitle(stringResource(R.string.stats_title)) }
        item {
            SegmentedChoice(
                options = listOf(StatsMode.PERIOD to stringResource(R.string.stats_mode_period), StatsMode.MONTH to stringResource(R.string.stats_mode_month)),
                selected = state.mode,
                onSelect = onModeSelected,
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            )
        }
        val content = state.content
        if (content != null) {
            statsBlocks(content, onOpenCategory)
        }
        if (state.mode == StatsMode.PERIOD && state.pastPeriods.isNotEmpty()) {
            item { PastPeriodsCard(state.pastPeriods, onOpenPeriod, Modifier.padding(horizontal = 16.dp)) }
        }
    }
}

/** Статистика завершённого периода поверх разделов: те же блоки без переключателя и итогов. */
@Composable
fun PeriodStatsRoute(onBack: () -> Unit, onOpenCategory: (Long, DateRange) -> Unit, viewModel: PeriodStatsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = BudgetTheme.colors
    val content = state.content
    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().background(colors.background).testTag(STATS_LIST_TAG),
    ) {
        item {
            NavigationHeader(
                content?.let { StatsFormat.range(it.range) } ?: stringResource(R.string.stats_title),
                R.drawable.ic_back,
                stringResource(R.string.action_back),
                onBack,
            )
        }
        if (content != null) statsBlocks(content, onOpenCategory)
    }
}

private fun LazyListScope.statsBlocks(content: StatsContent, onOpenCategory: (Long, DateRange) -> Unit) {
    item {
        CategoryCard(
            range = content.range,
            breakdown = content.breakdown,
            onOpenCategory = { id -> onOpenCategory(id, content.range) },
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        )
    }
    item { DailyCard(content, Modifier.padding(horizontal = 16.dp)) }
    item { ComparisonCard(content.comparison, content.view, Modifier.padding(horizontal = 16.dp)) }
    if (content.merchants.isNotEmpty()) {
        item { MerchantsCard(content.merchants, Modifier.padding(horizontal = 16.dp)) }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().background(BudgetTheme.colors.card, CardShape).padding(horizontal = 18.dp, vertical = 18.dp),
    ) { content() }
}

@Composable
private fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontSize = 17.sp, color = BudgetTheme.colors.ink, modifier = modifier.semantics { heading() })
}

@Composable
private fun DailyCard(content: StatsContent, modifier: Modifier = Modifier) {
    val colors = BudgetTheme.colors
    val withLimit = content.days.any { it.limitKopecks != null }
    val within = stringResource(R.string.stats_within_limit)
    val over = stringResource(R.string.stats_over_limit)
    Card(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardTitle(stringResource(R.string.stats_by_day), Modifier.weight(1f))
            if (content.days.size > 7) Text(stringResource(R.string.stats_by_day_hint), fontSize = 14.sp, color = colors.muted)
        }
        if (content.days.isNotEmpty()) {
            // Новое состояние прокрутки на каждый диапазон: Vico прокручивает к концу только при первом показе,
            // а после смены режима или дня сегодняшний день должен снова быть виден.
            key(content.view, content.range) {
                DailyChart(
                    days = content.days,
                    todayLimitKopecks = content.todayLimitKopecks,
                    limitLabel = content.todayLimitKopecks?.let { stringResource(R.string.stats_limit, MoneyFormat.amount(it)) }.orEmpty(),
                    today = content.highlightedDay,
                    spoken = stringResource(R.string.stats_by_day) + ". " + StatsFormat.spokenDays(content.days, within, over),
                )
            }
        }
        if (withLimit) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.clearAndSetSemantics { }) {
                LegendDot(colors.chartBarOk, within)
                LegendDot(colors.chartBarOver, over)
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(12.dp).background(color, CircleShape))
        Text(label, fontSize = 14.sp, color = BudgetTheme.colors.sub)
    }
}

@Composable
private fun ComparisonCard(changes: List<SpendingChange>?, view: StatsView, modifier: Modifier = Modifier) {
    val colors = BudgetTheme.colors
    Card(modifier) {
        CardTitle(stringResource(if (view == StatsView.MONTH) R.string.stats_compare_month else R.string.stats_compare_period))
        val none = when (view) {
            StatsView.CURRENT_PERIOD -> R.string.stats_compare_none
            StatsView.MONTH -> R.string.stats_compare_none_month
            StatsView.FINISHED_PERIOD -> R.string.stats_compare_no_earlier
        }
        when {
            changes == null -> Text(stringResource(none), fontSize = 15.sp, color = colors.muted)
            changes.isEmpty() -> Text(stringResource(R.string.stats_compare_same), fontSize = 15.sp, color = colors.muted)
            else -> changes.forEach { ChangeRow(it) }
        }
    }
}

@Composable
private fun ChangeRow(change: SpendingChange) {
    val colors = BudgetTheme.colors
    val diff = MoneyFormat.amount(kotlin.math.abs(change.diffKopecks))
    val text = stringResource(if (change.isMore) R.string.stats_change_more else R.string.stats_change_less, change.category.name, diff)
    val badge = change.percent?.let { "${if (change.isMore) "↑" else "↓"} ${kotlin.math.abs(it)}%" } ?: stringResource(R.string.stats_change_new)
    val spokenDiff = SpokenMoney.amount(kotlin.math.abs(change.diffKopecks))
    val spoken = "${change.category.name}: на $spokenDiff ${if (change.isMore) "больше" else "меньше"}" +
        (change.percent?.let { ", ${kotlin.math.abs(it)} ${SpokenMoney.plural(kotlin.math.abs(it).toLong(), "процент", "процента", "процентов")}" } ?: ", новая трата")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
    ) {
        Text(
            badge,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (change.isMore) colors.warn else colors.positive,
            modifier = Modifier
                .background(if (change.isMore) colors.warnSoft else colors.positiveSoft, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Text(text, fontSize = 16.sp, color = colors.ink, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MerchantsCard(merchants: List<MerchantSpending>, modifier: Modifier = Modifier) {
    val colors = BudgetTheme.colors
    Card(modifier) {
        CardTitle(stringResource(R.string.stats_merchants))
        merchants.forEach { m ->
            val count = "${m.count} ${SpokenMoney.plural(m.count.toLong(), "операция", "операции", "операций")}"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clearAndSetSemantics { contentDescription = "${m.merchant}, $count, ${SpokenMoney.amount(m.kopecks)}" },
            ) {
                Column(Modifier.weight(1f)) {
                    Text(m.merchant, fontSize = 16.sp, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(count, fontSize = 13.sp, color = colors.muted)
                }
                Text(MoneyFormat.amount(m.kopecks), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            }
        }
    }
}

@Composable
private fun PastPeriodsCard(periods: List<PeriodSummary>, onOpenPeriod: (Long) -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetTheme.colors
    Card(modifier) {
        CardTitle(stringResource(R.string.stats_past_periods))
        periods.forEachIndexed { index, summary ->
            if (index > 0) HorizontalDivider(color = colors.line)
            PastPeriodRow(summary) { onOpenPeriod(summary.period.id) }
        }
    }
}

@Composable
private fun PastPeriodRow(summary: PeriodSummary, onClick: () -> Unit) {
    val colors = BudgetTheme.colors
    val left = if (summary.isOverspent) {
        stringResource(R.string.stats_period_overspent, MoneyFormat.amount(-summary.leftKopecks))
    } else {
        stringResource(R.string.stats_period_left, MoneyFormat.amount(summary.leftKopecks))
    }
    val spokenLeft = if (summary.isOverspent) "перерасход ${SpokenMoney.amount(-summary.leftKopecks)}" else "осталось ${SpokenMoney.amount(summary.leftKopecks)}"
    val spoken = "Период ${StatsFormat.spokenRange(summary.range)}: бюджет ${SpokenMoney.amount(summary.budgetKopecks)}, " +
        "потрачено ${SpokenMoney.amount(summary.spentKopecks)}, $spokenLeft"
    Surface(
        onClick = onClick,
        color = colors.card,
        contentColor = colors.ink,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        // Описание на содержимом, а не на Surface: иначе вместе с текстами пропало бы и действие нажатия.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp).clearAndSetSemantics { contentDescription = spoken },
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(StatsFormat.range(summary.range), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                Text(
                    stringResource(R.string.stats_period_budget, MoneyFormat.amount(summary.budgetKopecks)) + " · " +
                        stringResource(R.string.stats_period_spent, MoneyFormat.amount(summary.spentKopecks)),
                    fontSize = 13.sp,
                    color = colors.muted,
                )
                Text(left, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (summary.isOverspent) colors.warn else colors.positive)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = colors.muted)
        }
    }
}
