package ru.hznik.budget.common

import android.util.Log
import kotlin.coroutines.cancellation.CancellationException

/**
 * Выполняет [block] так, что сбой не роняет фоновую работу (автоимпорт, напоминания, виджет), но и не пропадает
 * бесследно: в журнал попадает только [what] и класс исключения — без сумм, магазинов и текста уведомлений
 * (раздел 10.2 ТЗ). Отмена корутины пробрасывается дальше. Возвращает `null`, если [block] не удался.
 */
inline fun <T> runLogged(tag: String, what: String, block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(tag, "$what: ${e.javaClass.simpleName}")
        null
    }
