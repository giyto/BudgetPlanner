package ru.hznik.budget.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import ru.hznik.budget.R
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.OperationFilter
import ru.hznik.budget.domain.stats.DateRange
import ru.hznik.budget.domain.time.today
import ru.hznik.budget.domain.usecase.DayGroup
import ru.hznik.budget.domain.usecase.ObserveOperations
import ru.hznik.budget.ui.components.NavigationHeader
import ru.hznik.budget.ui.format.MoneyFormat
import ru.hznik.budget.ui.format.SpokenMoney
import ru.hznik.budget.ui.operations.DayCard
import ru.hznik.budget.ui.theme.BudgetTheme
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

data class CategoryOperationsUiState(
    val loaded: Boolean = false,
    val categoryName: String = "",
    val range: DateRange,
    val groups: List<DayGroup> = emptyList(),
    val today: LocalDate,
) {
    val count: Int get() = groups.sumOf { it.items.size }

    /** Траты категории за дни: траты и переводы минус возвраты. */
    val netSpentKopecks: Long get() = groups.sumOf { it.netSpentKopecks }
}

/** Операции одной категории за дни статистики (spec statistics, переход из списка категорий). */
@HiltViewModel
class CategoryOperationsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeOperations: ObserveOperations,
    categories: CategoryRepository,
    clock: Clock,
) : ViewModel() {
    private val categoryId: Long = checkNotNull(savedStateHandle.get<Long>(ARG_CATEGORY_ID))
    private val range = DateRange(
        LocalDate.ofEpochDay(checkNotNull(savedStateHandle.get<Long>(ARG_FROM))),
        LocalDate.ofEpochDay(checkNotNull(savedStateHandle.get<Long>(ARG_TO))),
    )
    private val today = clock.today()

    val uiState: StateFlow<CategoryOperationsUiState> = combine(
        observeOperations(OperationFilter(categoryId = categoryId, dateRange = range)),
        categories.observeAll(),
    ) { groups, all ->
        CategoryOperationsUiState(true, all.firstOrNull { it.id == categoryId }?.name.orEmpty(), range, groups, today)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoryOperationsUiState(range = range, today = today))

    companion object {
        /** Имена аргументов маршрута `CategoryOperations`. */
        const val ARG_CATEGORY_ID = "categoryId"
        const val ARG_FROM = "fromEpochDay"
        const val ARG_TO = "toEpochDay"
    }
}

@Composable
fun CategoryOperationsRoute(onBack: () -> Unit, onOpenOperation: (Long) -> Unit, viewModel: CategoryOperationsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = BudgetTheme.colors
    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().background(colors.background),
    ) {
        item {
            NavigationHeader(stringResource(R.string.operations_title), R.drawable.ic_back, stringResource(R.string.action_back), onBack)
        }
        item { FilterChip(state, onBack) }
        if (state.loaded) {
            item { Summary(state) }
            items(state.groups, key = { it.date.toEpochDay() }) { group -> DayCard(group, state.today, onOpenOperation) }
        }
    }
}

/** Плашка фильтра: категория и даты; крестик закрывает экран и возвращает в статистику. */
@Composable
private fun FilterChip(state: CategoryOperationsUiState, onClose: () -> Unit) {
    val colors = BudgetTheme.colors
    val label = "${state.categoryName} · ${StatsFormat.range(state.range)}"
    val closeLabel = stringResource(R.string.stats_remove_filter)
    Surface(
        onClick = onClose,
        shape = RoundedCornerShape(16.dp),
        color = colors.primarySoft,
        contentColor = colors.primaryText,
        modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 44.dp),
    ) {
        // Описание на содержимом, а не на Surface: иначе пропало бы действие нажатия для TalkBack.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).clearAndSetSemantics {
                contentDescription = "Фильтр: ${state.categoryName}, ${StatsFormat.spokenRange(state.range)}"
                onClick(label = closeLabel) { onClose(); true }
            },
        ) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Icon(painterResource(R.drawable.ic_close), contentDescription = null, modifier = Modifier.padding(start = 8.dp).size(16.dp))
        }
    }
}

@Composable
private fun Summary(state: CategoryOperationsUiState) {
    val colors = BudgetTheme.colors
    val count = "${state.count} ${SpokenMoney.plural(state.count.toLong(), "операция", "операции", "операций")}"
    val net = state.netSpentKopecks
    // Возвратов больше трат — показываем «+», как итог дня в разделе «Операции».
    val total = (if (net > 0) MoneyFormat.MINUS.toString() else if (net < 0) "+" else "") + MoneyFormat.amount(net)
    val spokenTotal = if (net < 0) "возвраты больше трат на ${SpokenMoney.amount(net)}" else "траты ${SpokenMoney.amount(net)}"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(colors.card, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .clearAndSetSemantics { contentDescription = "$count, $spokenTotal" },
    ) {
        Text(if (state.count == 0) stringResource(R.string.stats_no_spending) else count, fontSize = 15.sp, color = colors.sub, modifier = Modifier.weight(1f))
        Text(total, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
    }
}
