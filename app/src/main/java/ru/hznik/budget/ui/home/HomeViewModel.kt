package ru.hznik.budget.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import ru.hznik.budget.domain.budget.PeriodStatus
import ru.hznik.budget.domain.model.OperationItem
import ru.hznik.budget.domain.model.PlannedPayment
import ru.hznik.budget.domain.repository.OperationRepository
import ru.hznik.budget.domain.repository.PlannedPaymentRepository
import ru.hznik.budget.domain.usecase.ObservePeriodStatus
import javax.inject.Inject

data class HomeUiState(
    val loaded: Boolean = false,
    /** `null`, пока периода нет (онбординг не пройден). */
    val status: PeriodStatus? = null,
    val nextPayment: PlannedPayment? = null,
    val recent: List<OperationItem> = emptyList(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    observePeriodStatus: ObservePeriodStatus,
    payments: PlannedPaymentRepository,
    operations: OperationRepository,
) : ViewModel() {
    val uiState: StateFlow<HomeUiState> = combine(
        observePeriodStatus(),
        payments.observeAll(),
        operations.observeRecent(RECENT_COUNT),
    ) { status, allPayments, recent ->
        HomeUiState(
            loaded = true,
            status = status,
            // Список уже отсортирован по дате: ближайший неоплаченный — первый неоплаченный.
            nextPayment = allPayments.firstOrNull { !it.isPaid },
            recent = recent,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    companion object {
        const val RECENT_COUNT = 2
    }
}
