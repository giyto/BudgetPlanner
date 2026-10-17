package ru.hznik.budget.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.hznik.budget.domain.notifications.BankNotification
import ru.hznik.budget.domain.notifications.SupportedBanks
import java.time.Instant
import javax.inject.Inject
import ru.hznik.budget.common.runLogged

/**
 * Доступ к уведомлениям (FR-05). Пакет проверяется первой строкой: уведомление чужого приложения
 * не читается, не сохраняется и не попадает в лог (раздел 10 ТЗ).
 */
@AndroidEntryPoint
class BankNotificationListener : NotificationListenerService() {
    @Inject lateinit var intake: NotificationIntake

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn)

    override fun onListenerConnected() {
        ListenerStatus.onConnected()
        scope.launch { runLogged(TAG, "Очистка образцов не удалась") { intake.purgeExpired() } }
        // Уведомления, пришедшие, пока сервис был выгружен и ещё висящие в шторке.
        val active = runCatching { activeNotifications }.getOrNull().orEmpty()
        active.forEach(::handle)
    }

    override fun onListenerDisconnected() {
        ListenerStatus.onDisconnected()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun handle(sbn: StatusBarNotification) {
        // Чтение extras банка может бросить (например, BadParcelableException): сервис не падает и ничего не пишет в лог.
        val bank = runCatching { sbn.toBankNotification() }.getOrNull() ?: return
        scope.launch { runLogged(TAG, "Импорт уведомления не удался") { intake.accept(bank) } }
    }
}

/**
 * Уведомление банка из списка, как его видит приложение. Пакет проверяется первой строкой: у чужого уведомления
 * extras не читаются вовсе. Вынесено из сервиса, чтобы тест проверял на настоящем `StatusBarNotification` тот же код.
 */
internal fun StatusBarNotification.toBankNotification(): BankNotification? {
    if (SupportedBanks.byPackage(packageName) == null) return null
    val notification = notification ?: return null
    // Сводка группы повторяет вложенные уведомления, текущие (загрузка, плеер) — не операции.
    if (notification.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return null
    val extras = notification.extras ?: return null
    val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        ?: extras.getCharSequence(Notification.EXTRA_TEXT)
        ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" / ")
    return BankNotification(
        packageName = packageName,
        // Банк может переиспользовать id уведомления для разных покупок; время у повторной доставки то же.
        key = "$key|$postTime",
        postedAt = Instant.ofEpochMilli(postTime),
        title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        text = text?.toString(),
    )
}

private const val TAG = "BankNotifications"
