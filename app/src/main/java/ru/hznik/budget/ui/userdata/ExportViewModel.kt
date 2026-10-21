package ru.hznik.budget.ui.userdata

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import ru.hznik.budget.R
import ru.hznik.budget.domain.export.ExportOperations
import ru.hznik.budget.domain.export.UserFiles
import ru.hznik.budget.domain.repository.PeriodRepository
import ru.hznik.budget.domain.stats.DateRange
import ru.hznik.budget.domain.time.today
import ru.hznik.budget.ui.Notices
import ru.hznik.budget.ui.launchSave
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/** Экран экспорта: даты, сколько операций попадёт в файл, сохранение (spec data-export «Экспорт операций в CSV»). */
data class ExportUiState(
    val range: DateRange? = null,
    val isCurrentPeriod: Boolean = true,
    /** `null`, пока не посчитано. */
    val count: Int? = null,
    val exporting: Boolean = false,
) {
    /** Имя, которое предлагается в окне сохранения. */
    val fileName: String get() = range?.let { "budget-${it.start}_${it.endInclusive}.csv" } ?: "budget.csv"

    val canSave: Boolean get() = range != null && (count ?: 0) > 0 && !exporting
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ExportViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    periods: PeriodRepository,
    private val exportOperations: ExportOperations,
    private val files: UserFiles,
    clock: Clock,
) : ViewModel() {
    private val today: LocalDate = clock.today()

    /** Текущий период целиком, с первого дня до дня перед доходом. */
    private val currentPeriod = periods.observeCurrent().map { period ->
        period?.let { DateRange(it.startDate, it.nextIncomeDate.minusDays(1)) } ?: DateRange(today, today)
    }
    // Выбранные даты переживают смерть процесса, пока открыто окно сохранения: файл и его содержимое совпадают.
    private val chosenFrom = savedStateHandle.getStateFlow<Long?>(KEY_FROM, null)
    private val chosenTo = savedStateHandle.getStateFlow<Long?>(KEY_TO, null)
    private val chosen = combine(chosenFrom, chosenTo) { from, to ->
        if (from != null && to != null) DateRange(LocalDate.ofEpochDay(from), LocalDate.ofEpochDay(to)) else null
    }
    private val exporting = MutableStateFlow(false)
    private val range = combine(currentPeriod, chosen) { current, picked -> picked ?: current }

    val state: StateFlow<ExportUiState> = combine(
        currentPeriod,
        range,
        range.flatMapLatest { exportOperations.observeCount(it) },
        exporting,
    ) { current, selected, count, busy ->
        ExportUiState(range = selected, isCurrentPeriod = selected == current, count = count, exporting = busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExportUiState())

    /** Начало позже конца — конец сдвигается на тот же день. */
    fun setFrom(date: LocalDate) {
        val current = state.value.range ?: return
        choose(DateRange(date, if (date.isAfter(current.endInclusive)) date else current.endInclusive))
    }

    /** Конец раньше начала — начало сдвигается на тот же день. */
    fun setTo(date: LocalDate) {
        val current = state.value.range ?: return
        choose(DateRange(if (date.isBefore(current.start)) date else current.start, date))
    }

    private fun choose(range: DateRange) {
        savedStateHandle[KEY_FROM] = range.start.toEpochDay()
        savedStateHandle[KEY_TO] = range.endInclusive.toEpochDay()
    }

    /** Запись в файл, выбранный в системном окне ([target] — его адрес). */
    fun export(target: String) {
        val range = state.value.range ?: return
        if (exporting.value) return
        exporting.value = true
        launchSave(onFailure = { exporting.value = false }) {
            val text = exportOperations(range)
            if (text != null) {
                try {
                    files.writeText(target, text)
                } catch (e: Exception) {
                    // Окно сохранения уже создало файл: пустой или неполный не оставляем.
                    files.delete(target)
                    throw e
                }
                Notices.show(R.string.export_saved)
            }
            exporting.update { false }
        }
    }

    private companion object {
        const val KEY_FROM = "fromEpochDay"
        const val KEY_TO = "toEpochDay"
    }
}
