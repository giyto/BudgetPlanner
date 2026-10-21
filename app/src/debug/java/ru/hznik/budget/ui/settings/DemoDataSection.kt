package ru.hznik.budget.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.hznik.budget.R
import ru.hznik.budget.data.demo.DemoData
import ru.hznik.budget.ui.format.SpokenMoney
import ru.hznik.budget.ui.theme.BudgetTheme
import javax.inject.Inject

data class DemoDataUiState(val hasDemo: Boolean = false, val busy: Boolean = false, val message: String? = null)

@HiltViewModel
class DemoDataViewModel @Inject constructor(private val demo: DemoData) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<DemoDataUiState> = combine(demo.hasDemo, busy, message) { has, b, m -> DemoDataUiState(has, b, m) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DemoDataUiState())

    fun fill() = run {
        message.value = when (val result = demo.fill()) {
            is DemoData.Result.Filled ->
                "Добавлено: ${result.operations} ${SpokenMoney.plural(result.operations.toLong(), "операция", "операции", "операций")}, " +
                    "прошлых периодов: ${result.periods}"
            DemoData.Result.NoPeriod -> "Сначала пройдите онбординг: нужен текущий период"
            DemoData.Result.AlreadyFilled -> "Демо-данные уже добавлены"
        }
    }

    fun clear() = run { message.value = "Удалено демо-операций: ${demo.clear()}" }

    private fun run(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                busy.value = false
            }
        }
    }
}

/** Раздел «Для разработки»: есть только в отладочной сборке (набор src/debug, см. devtools/DevTools.kt). */
@Composable
fun DemoDataSection(onOpenSamples: () -> Unit, viewModel: DemoDataViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = BudgetTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.settings_debug),
            style = MaterialTheme.typography.titleMedium,
            color = colors.muted,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp).semantics { heading() },
        )
        DemoAction(
            title = stringResource(R.string.settings_demo_fill),
            hint = stringResource(R.string.settings_demo_fill_hint),
            enabled = !state.busy && !state.hasDemo,
            onClick = viewModel::fill,
        )
        DemoAction(
            title = stringResource(R.string.settings_demo_clear),
            hint = stringResource(R.string.settings_demo_clear_hint),
            enabled = !state.busy && state.hasDemo,
            onClick = viewModel::clear,
        )
        state.message?.let { Text(it, fontSize = 13.sp, color = colors.sub, modifier = Modifier.padding(start = 4.dp)) }
        NotificationSamplesItems(onOpen = onOpenSamples)
    }
}

@Composable
private fun DemoAction(title: String, hint: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = BudgetTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(22.dp),
        color = colors.card,
        contentColor = colors.ink,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
    ) {
        Column(modifier = Modifier.background(colors.card).padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) colors.ink else colors.muted)
            Text(hint, fontSize = 13.sp, color = colors.muted)
        }
    }
}
