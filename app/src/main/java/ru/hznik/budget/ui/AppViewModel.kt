package ru.hznik.budget.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.hznik.budget.domain.lock.AppLock
import ru.hznik.budget.domain.usecase.DeletionEvent
import ru.hznik.budget.domain.usecase.ObserveOnboardingDone
import ru.hznik.budget.domain.usecase.RestoreDeletedOperation
import ru.hznik.budget.domain.usecase.UndoDeletionBuffer
import ru.hznik.budget.reminders.AppActionBus
import ru.hznik.budget.reminders.PendingAction
import javax.inject.Inject

/**
 * События уровня всего приложения. Сейчас — отмена удаления операции (design.md, решение 5).
 * Срок отмены отсчитывается здесь, а не в интерфейсе: так он не сбрасывается при повороте экрана
 * и проверяется unit-тестом с виртуальным временем.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val buffer: UndoDeletionBuffer,
    private val restore: RestoreDeletedOperation,
    observeOnboardingDone: ObserveOnboardingDone,
    private val appLock: AppLock,
    private val actions: AppActionBus,
) : ViewModel() {
    /** Переход из виджета или уведомления; BudgetApp виден только после разблокировки, поэтому и переход — после неё. */
    val pendingAction: StateFlow<PendingAction?> = actions.pending

    fun consumeAction(action: PendingAction) = actions.consume(action)

    /** Пройден ли онбординг; `null`, пока неизвестно — тогда виден только фон окна (design.md, решение 6). */
    val onboardingDone: StateFlow<Boolean?> = observeOnboardingDone().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Защиту входа выключили сами: на телефоне сняли блокировку экрана (spec app-lock). */
    val screenLockRemovedNotice: StateFlow<Boolean> = appLock.screenLockRemovedNotice

    fun consumeScreenLockNotice() = appLock.consumeNotice()

    private val _pendingUndo = MutableStateFlow<DeletionEvent?>(null)

    /** Удаление, которое ещё можно отменить; `null` — сообщение «Операция удалена» не показывается. */
    val pendingUndo: StateFlow<DeletionEvent?> = _pendingUndo.asStateFlow()

    init {
        viewModelScope.launch {
            // Новое удаление заменяет прежнее и заново запускает отсчёт.
            buffer.events.collectLatest { event ->
                _pendingUndo.value = event
                delay(UNDO_WINDOW_MS)
                if (_pendingUndo.value == event) dismiss()
            }
        }
    }

    fun undo() {
        val event = _pendingUndo.value ?: return
        _pendingUndo.value = null
        viewModelScope.launch { restore(event.token) }
    }

    /** Отказ от отмены: истёк срок или сообщение смахнули. */
    fun dismiss() {
        val event = _pendingUndo.value ?: return
        _pendingUndo.value = null
        buffer.discard(event.token)
    }

    companion object {
        /** Сколько показывается «Операция удалена» с кнопкой «Отменить» (spec operations). */
        const val UNDO_WINDOW_MS = 5_000L
    }
}
