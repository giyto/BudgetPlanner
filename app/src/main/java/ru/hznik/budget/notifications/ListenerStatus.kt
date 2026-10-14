package ru.hznik.budget.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Подключён ли сервис уведомлений сейчас (в этом процессе); для плашки «Автоимпорт остановлен». */
object ListenerStatus {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun onConnected() {
        _connected.value = true
    }

    fun onDisconnected() {
        _connected.value = false
    }
}
