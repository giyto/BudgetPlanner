package ru.hznik.budget.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.hznik.budget.domain.lock.LockSettingsRepository
import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.model.IncomeSchedule
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.time.today
import ru.hznik.budget.domain.usecase.CompleteOnboarding
import ru.hznik.budget.domain.usecase.PaymentDraft
import ru.hznik.budget.domain.usecase.PeriodSetupResult
import ru.hznik.budget.domain.usecase.resolveNextIncome
import ru.hznik.budget.reminders.NotificationPermission
import ru.hznik.budget.ui.components.AmountInput
import ru.hznik.budget.ui.components.AmountKey
import ru.hznik.budget.ui.components.PaymentFormState
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/**
 * Шаги онбординга (сценарий 5.1): автоимпорт, затем уведомления и защита входа. Шаг уведомлений есть только
 * на Android 13+ без выданного разрешения (add-widget-and-reminders).
 */
enum class OnboardingStep { INTRO, MONEY, PAYMENTS, AUTO_IMPORT, NOTIFICATIONS, PROTECTION }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.INTRO,
    val amountInput: String = "",
    val incomeRegular: Boolean = true,
    val incomeDay: Int? = null,
    val incomeDate: LocalDate? = null,
    val payments: List<PaymentDraft> = emptyList(),
    /** Открытая форма нового платежа; `null` — форма закрыта. */
    val paymentForm: PaymentFormState? = null,
    val categories: List<Category> = emptyList(),
    val today: LocalDate,
    val busy: Boolean = false,
    /** Показывать ли шаг «Подсказки вовремя»; решается один раз при открытии онбординга. */
    val showNotifications: Boolean = false,
) {
    val steps: List<OnboardingStep> get() = OnboardingStep.entries.filter { it != OnboardingStep.NOTIFICATIONS || showNotifications }

    val defaultCategoryId: Long? get() = categories.firstOrNull { it.defaultKey == "other_expense" }?.id ?: categories.lastOrNull()?.id

    val stepNumber: Int get() = steps.indexOf(step) + 1
    val stepCount: Int get() = steps.size

    val schedule: IncomeSchedule? get() = if (incomeRegular) incomeDay?.let { IncomeSchedule.Regular(it) } else IncomeSchedule.Irregular

    /** Дата следующего дохода, как её посчитает завершение онбординга. */
    val nextIncome: LocalDate? get() = schedule?.let { resolveNextIncome(it, if (incomeRegular) null else incomeDate, today) }

    val canContinue: Boolean get() = when (step) {
        OnboardingStep.INTRO -> true
        OnboardingStep.MONEY -> amountInput.isNotEmpty() && nextIncome?.isAfter(today) == true
        OnboardingStep.PAYMENTS, OnboardingStep.AUTO_IMPORT, OnboardingStep.NOTIFICATIONS, OnboardingStep.PROTECTION -> !busy
    }
}

sealed interface OnboardingEvent {
    data object Finished : OnboardingEvent
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    categories: CategoryRepository,
    private val completeOnboarding: CompleteOnboarding,
    private val lockSettings: LockSettingsRepository,
    private val notificationPermission: NotificationPermission,
    private val clock: Clock,
) : ViewModel() {
    private val form = MutableStateFlow(OnboardingUiState(today = clock.today(), showNotifications = notificationPermission.isRequestNeeded()))
    private val _events = Channel<OnboardingEvent>(Channel.BUFFERED)
    val events: Flow<OnboardingEvent> = _events.receiveAsFlow()

    // Без combine и stateIn: поле ввода получает новое значение в том же кадре, иначе при быстром наборе теряются буквы.
    val uiState: StateFlow<OnboardingUiState> = form.asStateFlow()

    init {
        viewModelScope.launch {
            categories.observeAll().collect { all ->
                form.update { it.copy(categories = all.filter { c -> c.kind == CategoryKind.EXPENSE && !c.hidden }) }
            }
        }
    }

    fun next() {
        val state = form.value
        if (!state.canContinue) return
        when (state.step) {
            OnboardingStep.INTRO -> form.update { it.copy(step = OnboardingStep.MONEY) }
            OnboardingStep.MONEY -> form.update { it.copy(step = OnboardingStep.PAYMENTS) }
            OnboardingStep.PAYMENTS -> form.update { it.copy(step = OnboardingStep.AUTO_IMPORT) }
            // «Дальше», «Вводить вручную» и «Пропустить» на шаге автоимпорта — к уведомлениям или защите входа.
            OnboardingStep.AUTO_IMPORT -> form.update { it.copy(step = if (it.showNotifications) OnboardingStep.NOTIFICATIONS else OnboardingStep.PROTECTION) }
            OnboardingStep.NOTIFICATIONS -> form.update { it.copy(step = OnboardingStep.PROTECTION) }
            OnboardingStep.PROTECTION -> finish(enableProtection = false)
        }
    }

    /** «Назад»: предыдущий шаг. `false` на первом шаге — пусть закроется приложение. */
    fun back(): Boolean {
        val step = form.value.step
        if (step == OnboardingStep.INTRO) return false
        form.update { it.copy(step = it.steps[it.steps.indexOf(step) - 1]) }
        return true
    }

    /** Системный запрос разрешения закрыт с любым ответом: отказ не мешает пользоваться приложением. */
    fun onNotificationPermissionAnswered() {
        viewModelScope.launch { notificationPermission.markRequested() }
        next()
    }

    fun onKey(key: AmountKey) = form.update { it.copy(amountInput = AmountInput.press(it.amountInput, key)) }

    fun onIncomeRegular(regular: Boolean) = form.update { it.copy(incomeRegular = regular) }

    fun onIncomeDay(day: Int) = form.update { it.copy(incomeDay = day) }

    fun onIncomeDate(date: LocalDate) = form.update { if (date.isAfter(it.today)) it.copy(incomeDate = date) else it }

    fun openPaymentForm() = form.update {
        it.copy(paymentForm = PaymentFormState(dueDate = it.nextIncome?.minusDays(1)?.takeIf { d -> d.isAfter(it.today) } ?: it.today))
    }

    fun onPaymentFormChange(state: PaymentFormState) = form.update { it.copy(paymentForm = state) }

    fun closePaymentForm() = form.update { it.copy(paymentForm = null) }

    fun addPayment() {
        val state = form.value
        val formState = state.paymentForm ?: return
        val draft = state.defaultCategoryId?.let { formState.toDraft(it) }
        if (draft == null) {
            form.update { it.copy(paymentForm = formState.copy(showErrors = true)) }
            return
        }
        form.update { it.copy(payments = it.payments + draft, paymentForm = null) }
    }

    fun removePayment(index: Int) = form.update { it.copy(payments = it.payments.filterIndexed { i, _ -> i != index }) }

    /** «Пропустить» на шаге платежей: дальше без платежей. */
    fun skipPayments() {
        form.update { it.copy(payments = emptyList(), step = OnboardingStep.AUTO_IMPORT) }
    }

    /** «Включить» на шаге защиты после успешной проверки разблокировкой. */
    fun finishWithProtection() = finish(enableProtection = true)

    /** Данные онбординга записываются одним действием в конце, на шаге защиты (design.md add-data-protection, решение 10). */
    private fun finish(enableProtection: Boolean) {
        val state = form.value
        val schedule = state.schedule ?: return
        if (state.busy) return
        form.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = completeOnboarding(
                amountKopecks = AmountInput.toKopecks(state.amountInput),
                income = schedule,
                chosenNextIncome = if (state.incomeRegular) null else state.incomeDate,
                paymentDrafts = state.payments,
            )
            when (result) {
                PeriodSetupResult.Done -> {
                    if (enableProtection) lockSettings.setEnabled(true)
                    _events.send(OnboardingEvent.Finished)
                }
                // Экран простоял за полночь и выбранная дата дохода уже не в будущем: вернуться к её выбору.
                PeriodSetupResult.IncomeDateNotInFuture ->
                    form.update { it.copy(busy = false, today = clock.today(), incomeDate = null, step = OnboardingStep.MONEY) }
                PeriodSetupResult.NegativeAmount -> form.update { it.copy(busy = false) }
            }
        }
    }
}
