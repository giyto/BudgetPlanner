package ru.hznik.budget.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import ru.hznik.budget.common.runLogged
import androidx.annotation.StringRes

/** Сбои записи из экранов: общий экран показывает «Не удалось сохранить» (BudgetApp). */
object SaveFailures {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    fun report() {
        _events.tryEmit(Unit)
    }
}

/** Короткие сообщения об успехе для общего экрана: «Файл сохранён», «Копия сохранена» (BudgetApp). */
object Notices {
    private val _events = MutableSharedFlow<Int>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<Int> = _events.asSharedFlow()

    fun show(@StringRes message: Int) {
        _events.tryEmit(message)
    }
}

/**
 * Запись в базу из экрана. Сбой (например, базы) не роняет приложение: в журнал попадает только класс исключения,
 * пользователь видит «Не удалось сохранить», а [onFailure] возвращает экран в рабочее состояние — кнопку можно нажать
 * снова. После успешной записи, которая закрывает экран, кнопка остаётся занятой: экран, уезжающий во время перехода,
 * ещё принимает нажатия, и повтор записал бы второй раз.
 */
fun ViewModel.launchSave(onFailure: () -> Unit = {}, block: suspend CoroutineScope.() -> Unit): Job =
    viewModelScope.launch {
        runLogged(TAG, "Запись не удалась") { block(); true } ?: run {
            SaveFailures.report()
            onFailure()
        }
    }

private const val TAG = "Save"
