package ru.hznik.budget.ui.stats

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.hznik.budget.domain.stats.CategoryBreakdown
import ru.hznik.budget.domain.stats.DateRange
import ru.hznik.budget.domain.stats.DaySpending
import ru.hznik.budget.domain.stats.MerchantSpending
import ru.hznik.budget.domain.stats.PeriodSummary
import ru.hznik.budget.domain.stats.SpendingChange
import ru.hznik.budget.domain.usecase.ObserveCategoryBreakdown
import ru.hznik.budget.domain.usecase.ObserveDailySpending
import ru.hznik.budget.domain.usecase.ObservePastPeriods
import ru.hznik.budget.domain.usecase.ObserveSpendingComparison
import ru.hznik.budget.domain.usecase.ObserveStatsScope
import ru.hznik.budget.domain.usecase.ObserveTopMerchants
import ru.hznik.budget.domain.usecase.StatsMode
import ru.hznik.budget.domain.usecase.StatsScope
import java.time.LocalDate
import javax.inject.Inject

/** Что показывает экран: от этого зависят пунктир лимита, выделение сегодняшнего дня и тексты сравнения. */
enum class StatsView { CURRENT_PERIOD, MONTH, FINISHED_PERIOD }

/** Блоки статистики одного диапазона — общие для раздела и для завершённого периода. */
data class StatsContent(
    val view: StatsView,
    val range: DateRange,
    val breakdown: CategoryBreakdown,
    val days: List<DaySpending>,
    /** Сегодняшний лимит для пунктира; `null` — линии нет (режим «Месяц» и завершённый период). */
    val todayLimitKopecks: Long?,
    /** `null` — сравнивать не с чем. */
    val comparison: List<SpendingChange>?,
    val merchants: List<MerchantSpending>,
) {
    /** Сегодняшний день выделяется под столбиком в текущем периоде и месяце (они заканчиваются сегодня). */
    val highlightedDay: LocalDate? get() = range.endInclusive.takeIf { view != StatsView.FINISHED_PERIOD }
}

data class StatsUiState(
    val loaded: Boolean = false,
    val mode: StatsMode = StatsMode.PERIOD,
    val content: StatsContent? = null,
    val pastPeriods: List<PeriodSummary> = emptyList(),
)

/** Собирает [StatsContent] для диапазона из use cases (design.md, решение 7). */
class StatsContentLoader @Inject constructor(
    private val breakdown: ObserveCategoryBreakdown,
    private val comparison: ObserveSpendingComparison,
    private val merchants: ObserveTopMerchants,
    private val daily: ObserveDailySpending,
) {
    fun load(scope: StatsScope, view: StatsView): Flow<StatsContent> = combine(
        breakdown(scope.range),
        daily(scope),
        comparison(scope.range, scope.previousRange),
        merchants(scope.range),
    ) { categories, days, changes, top ->
        StatsContent(
            view = view,
            range = scope.range,
            breakdown = categories,
            days = days,
            // Пунктир — только для текущего периода и только при положительном лимите: ниже нуля его не нарисовать.
            todayLimitKopecks = days.lastOrNull()?.limitKopecks?.takeIf { view == StatsView.CURRENT_PERIOD && it > 0 },
            comparison = changes,
            merchants = top,
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val observeScope: ObserveStatsScope,
    private val loader: StatsContentLoader,
    private val observePastPeriods: ObservePastPeriods,
) : ViewModel() {
    private val mode: StateFlow<String> = savedStateHandle.getStateFlow(KEY_MODE, StatsMode.PERIOD.name)

    val uiState: StateFlow<StatsUiState> = mode.map { StatsMode.valueOf(it) }.flatMapLatest { mode ->
        val past = if (mode == StatsMode.PERIOD) observePastPeriods() else flowOf(emptyList())
        val content = observeScope.forMode(mode).flatMapLatest { scope ->
            val view = if (mode == StatsMode.PERIOD) StatsView.CURRENT_PERIOD else StatsView.MONTH
            if (scope == null) flowOf(null) else loader.load(scope, view)
        }
        combine(content, past) { c, periods -> StatsUiState(loaded = true, mode = mode, content = c, pastPeriods = periods) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    /** Режим сохраняется при повороте экрана и переключении вкладок (spec statistics). */
    fun onModeSelected(mode: StatsMode) {
        savedStateHandle[KEY_MODE] = mode.name
    }

    private companion object {
        const val KEY_MODE = "statsMode"
    }
}

data class PeriodStatsUiState(val loaded: Boolean = false, val content: StatsContent? = null)

/** Статистика завершённого периода: те же блоки без переключателя и итогов. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PeriodStatsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeScope: ObserveStatsScope,
    loader: StatsContentLoader,
) : ViewModel() {
    private val periodId: Long = checkNotNull(savedStateHandle.get<Long>(ARG_PERIOD_ID))

    val uiState: StateFlow<PeriodStatsUiState> = observeScope.forFinishedPeriod(periodId).flatMapLatest { scope ->
        if (scope == null) flowOf(PeriodStatsUiState(loaded = true)) else loader.load(scope, StatsView.FINISHED_PERIOD).map { PeriodStatsUiState(true, it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeriodStatsUiState())

    companion object {
        /** Имя аргумента маршрута `PeriodStats(periodId)`. */
        const val ARG_PERIOD_ID = "periodId"
    }
}
