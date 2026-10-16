package ru.hznik.budget.reminders

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Куда ведёт нажатие на виджет или уведомление (design.md add-widget-and-reminders, решение 6). */
enum class AppAction { OPEN_HOME, ADD_EXPENSE, NEW_PERIOD, RECONCILE }

/** Переход, который ждёт, пока приложение будет готово: пройдена разблокировка и известен онбординг. */
data class PendingAction(val action: AppAction, val bankId: String? = null, val cardLast4: String? = null)

/** Одноразовое событие перехода от активности к навигации. */
@Singleton
class AppActionBus @Inject constructor() {
    private val _pending = MutableStateFlow<PendingAction?>(null)
    val pending: StateFlow<PendingAction?> = _pending.asStateFlow()

    fun post(action: PendingAction) {
        _pending.value = action
    }

    fun consume(action: PendingAction) {
        _pending.compareAndSet(action, null)
    }
}

/**
 * `Intent` с действием приходят только через неэкспортируемый `AppActionAlias` (манифест): так переход можно
 * запустить лишь из `PendingIntent` самого приложения. Такое же действие, присланное другим приложением прямо
 * в `MainActivity`, не разбирается.
 */
object AppActionIntents {
    private const val ALIAS = "ru.hznik.budget.AppActionAlias"
    private const val PREFIX = "ru.hznik.budget.action."
    private const val EXTRA_BANK = "bank"
    private const val EXTRA_CARD = "card"

    /** Явный `Intent` на `AppActionAlias`; уже открытое приложение получает его в `onNewIntent`. */
    fun intent(context: Context, action: PendingAction): Intent = Intent(PREFIX + action.action.name)
        .setComponent(ComponentName(context.packageName, ALIAS))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(EXTRA_BANK, action.bankId)
        .putExtra(EXTRA_CARD, action.cardLast4)

    fun pendingIntent(context: Context, action: PendingAction): PendingIntent {
        val intent = intent(context, action)
        // Разные счета сверки — разные PendingIntent, иначе система подставит дополнения первого.
        // hashCode у enum разный в разных процессах, поэтому код считается из порядкового номера и строк.
        val requestCode = action.action.ordinal * 31 + "${action.bankId}|${action.cardLast4}".hashCode()
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun parse(intent: Intent?): PendingAction? {
        if (intent?.component?.className != ALIAS) return null
        val name = intent.action?.removePrefix(PREFIX) ?: return null
        val action = AppAction.entries.firstOrNull { it.name == name } ?: return null
        return PendingAction(action, intent.getStringExtra(EXTRA_BANK), intent.getStringExtra(EXTRA_CARD))
    }
}
