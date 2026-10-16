package ru.hznik.budget.reminders

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.hznik.budget.R
import ru.hznik.budget.domain.reminders.AppNotification
import ru.hznik.budget.domain.reminders.AppNotifier
import ru.hznik.budget.domain.reminders.NotificationType
import ru.hznik.budget.ui.format.MoneyFormat
import ru.hznik.budget.ui.format.accountLabel
import javax.inject.Inject

/** Каналы уведомлений: по одному на тип, чтобы каждый выключался и в системных настройках (design.md, решение 5). */
object NotificationChannels {
    fun id(type: NotificationType): String = when (type) {
        NotificationType.OVERSPEND -> "overspend"
        NotificationType.PAYMENT -> "payments"
        NotificationType.NEW_PERIOD -> "new_period"
        NotificationType.BANK_MISMATCH -> "bank_mismatch"
    }

    private fun name(type: NotificationType): Int = when (type) {
        NotificationType.OVERSPEND -> R.string.channel_overspend
        NotificationType.PAYMENT -> R.string.channel_payments
        NotificationType.NEW_PERIOD -> R.string.channel_new_period
        NotificationType.BANK_MISMATCH -> R.string.channel_bank_mismatch
    }

    /** Создаёт или обновляет названия каналов; повторный вызов безопасен. */
    fun create(context: Context) {
        val channels = NotificationType.entries.map { type ->
            NotificationChannelCompat.Builder(id(type), NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(name(type)))
                .build()
        }
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(channels)
    }
}

/**
 * Показ уведомлений приложения (spec app-notifications). В тексте только суммы, название платежа и «Банк *1234».
 * При защите входа уведомление частное: на защищённом экране блокировки видна только публичная версия без сумм.
 */
class SystemAppNotifier @Inject constructor(@ApplicationContext private val context: Context) : AppNotifier {
    override fun show(notification: AppNotification, private: Boolean) {
        // Без разрешения (Android 13+) система уведомление всё равно не покажет.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val channel = NotificationChannels.id(notification.type)
        val content = content(notification)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
            .setContentIntent(AppActionIntents.pendingIntent(context, content.action))
            .setAutoCancel(true)
            .setCategory(if (notification is AppNotification.PaymentDue) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
        if (private) {
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, channel)
                        .setSmallIcon(R.drawable.ic_bell)
                        .setContentTitle(context.getString(R.string.app_name))
                        .setContentText(context.getString(R.string.notif_public_text))
                        .build(),
                )
        }
        NotificationManagerCompat.from(context).notify(content.tag, content.id, builder.build())
    }

    private class Content(val title: String, val text: String, val action: PendingAction, val tag: String, val id: Int)

    private fun content(notification: AppNotification): Content = when (notification) {
        is AppNotification.Overspend -> Content(
            title = context.getString(R.string.notif_overspend_title, MoneyFormat.amount(notification.overKopecks)),
            text = notification.tomorrowLimitKopecks?.let { context.getString(R.string.notif_overspend_text, MoneyFormat.balance(it)) }
                ?: context.getString(R.string.notif_overspend_text_last_day),
            action = PendingAction(AppAction.OPEN_HOME),
            tag = "overspend",
            id = 0,
        )
        is AppNotification.PaymentDue -> Content(
            title = context.getString(R.string.notif_payment_title, notification.name, MoneyFormat.amount(notification.amount.kopecks)),
            text = context.getString(R.string.notif_payment_text),
            action = PendingAction(AppAction.OPEN_HOME),
            tag = "payment",
            id = notification.paymentId.toInt(),
        )
        AppNotification.IncomeDay -> Content(
            title = context.getString(R.string.notif_income_day_title),
            text = context.getString(R.string.notif_income_day_text),
            action = PendingAction(AppAction.NEW_PERIOD),
            tag = "new_period",
            id = 0,
        )
        is AppNotification.IncomeArrived -> Content(
            title = context.getString(R.string.notif_income_arrived_title, MoneyFormat.amount(notification.amount.kopecks)),
            text = context.getString(R.string.notif_income_arrived_text),
            action = PendingAction(AppAction.NEW_PERIOD),
            tag = "new_period",
            id = 0,
        )
        is AppNotification.BankMismatch -> Content(
            title = context.getString(
                R.string.notif_mismatch_title,
                MoneyFormat.amount(notification.diffKopecks),
                accountLabel(notification.account, context.getString(R.string.reconcile_account_manual)),
            ),
            text = context.getString(R.string.notif_mismatch_text),
            action = PendingAction(AppAction.RECONCILE, notification.account.bankId, notification.account.cardLast4),
            tag = "mismatch",
            id = "${notification.account.bankId}|${notification.account.cardLast4}".hashCode(),
        )
    }
}
