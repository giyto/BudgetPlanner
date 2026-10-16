package ru.hznik.budget.domain.reminders

import kotlinx.coroutines.flow.Flow
import ru.hznik.budget.domain.budget.PeriodStatus
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.PlannedPayment
import ru.hznik.budget.domain.reconcile.ReconAccount
import ru.hznik.budget.domain.reconcile.ReconState
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Типы уведомлений приложения, каждый отключается отдельно (spec app-notifications). */
enum class NotificationType { OVERSPEND, PAYMENT, NEW_PERIOD, BANK_MISMATCH }

/** Уведомление к показу. Тексты собирает реализация [AppNotifier]. */
sealed interface AppNotification {
    val type: NotificationType

    data class Overspend(val overKopecks: Long, val tomorrowLimitKopecks: Long?) : AppNotification {
        override val type get() = NotificationType.OVERSPEND
    }

    data class PaymentDue(val paymentId: Long, val name: String, val amount: Money, val dueDate: LocalDate) : AppNotification {
        override val type get() = NotificationType.PAYMENT
    }

    data object IncomeDay : AppNotification {
        override val type get() = NotificationType.NEW_PERIOD
    }

    data class IncomeArrived(val amount: Money) : AppNotification {
        override val type get() = NotificationType.NEW_PERIOD
    }

    data class BankMismatch(val account: ReconAccount, val diffKopecks: Long) : AppNotification {
        override val type get() = NotificationType.BANK_MISMATCH
    }
}

/**
 * Журнал отправленного (design.md add-widget-and-reminders, решение 3): по нему уведомление не приходит дважды по
 * одной причине. Хранится в DataStore.
 */
data class NotificationLog(
    val overspendDate: LocalDate? = null,
    /** «id платежа@дата платежа». */
    val payments: Set<String> = emptySet(),
    /** id периода, для которого уже было «наступил день дохода». */
    val incomeDayPeriod: Long? = null,
    /** id периода, для которого уже было «пришло N ₽». */
    val incomeArrivedPeriod: Long? = null,
    /** «банк|карта|момент точки отсчёта» расхождения: пересчёт по новому остатку его не меняет. */
    val mismatches: Set<String> = emptySet(),
)

/** Что известно на момент проверки. */
data class PlannerInput(
    val now: LocalDateTime,
    val status: PeriodStatus?,
    val payments: List<PlannedPayment>,
    val mismatches: List<ReconState>,
    /** Зачисление, похожее на стипендию или зарплату, ещё не отклонённое (spec home-screen). */
    val incomeSuggestion: Money?,
    val enabled: Set<NotificationType>,
)

data class Plan(val notifications: List<AppNotification>, val log: NotificationLog)

/** Решение, какие уведомления показать сейчас (spec app-notifications). Чистая функция без часов и Android. */
object NotificationPlanner {
    /** Время напоминаний о платежах и дне дохода. */
    val REMINDER_TIME: LocalTime = LocalTime.of(10, 0)

    private const val MISMATCH_KEYS_KEPT = 50

    fun plan(input: PlannerInput, log: NotificationLog): Plan {
        val today = input.now.toLocalDate()
        val afterReminderTime = !input.now.toLocalTime().isBefore(REMINDER_TIME)
        val out = mutableListOf<AppNotification>()
        var next = log.copy(payments = log.payments.filter { key -> key.substringAfter('@').let { !LocalDate.parse(it).isBefore(today) } }.toSet())
        val status = input.status

        // Перерасход — после траты и не чаще раза в календарный день. Без трат сегодня лимит может быть
        // отрицательным с полуночи (деньги периода кончились): тогда ночная проверка ничего не присылает.
        if (status != null && status.isOverspent && status.spentToday > 0 && log.overspendDate != today) {
            if (NotificationType.OVERSPEND in input.enabled) out += AppNotification.Overspend(-status.canSpendToday, status.tomorrowLimit)
            next = next.copy(overspendDate = today)
        }

        // Платёж — накануне с 10:00; в день платежа и позже не напоминаем.
        if (afterReminderTime) {
            input.payments.filter { !it.isPaid && it.dueDate == today.plusDays(1) }.forEach { payment ->
                val key = "${payment.id}@${payment.dueDate}"
                if (key !in next.payments) {
                    if (NotificationType.PAYMENT in input.enabled) out += AppNotification.PaymentDue(payment.id, payment.name, payment.amount, payment.dueDate)
                    next = next.copy(payments = next.payments + key)
                }
            }
        }

        // Новый период — один раз на период для каждого повода.
        val periodId = status?.period?.id
        if (status != null && periodId != null) {
            if (status.isIncomeDue && afterReminderTime && log.incomeDayPeriod != periodId) {
                if (NotificationType.NEW_PERIOD in input.enabled) out += AppNotification.IncomeDay
                next = next.copy(incomeDayPeriod = periodId)
            }
            val income = input.incomeSuggestion
            if (income != null && !status.isIncomeDue && log.incomeArrivedPeriod != periodId) {
                if (NotificationType.NEW_PERIOD in input.enabled) out += AppNotification.IncomeArrived(income)
                next = next.copy(incomeArrivedPeriod = periodId)
            }
        }

        // Расхождение — один раз; пересчёт по новому остатку не меняет точку отсчёта и ключ.
        input.mismatches.forEach { state ->
            val pending = state.pending ?: return@forEach
            val key = "${state.account.bankId.orEmpty()}|${state.account.cardLast4.orEmpty()}|${state.anchorAt.toEpochMilli()}"
            if (key !in next.mismatches) {
                if (NotificationType.BANK_MISMATCH in input.enabled) out += AppNotification.BankMismatch(state.account, pending.diffKopecks)
                next = next.copy(mismatches = (next.mismatches + key).toList().takeLast(MISMATCH_KEYS_KEPT).toSet())
            }
        }
        return Plan(out, next)
    }
}

/** Переключатели типов и журнал отправленного (DataStore). */
interface NotificationSettings {
    val enabled: Flow<Set<NotificationType>>

    suspend fun setEnabled(type: NotificationType, enabled: Boolean)

    suspend fun log(): NotificationLog

    suspend fun saveLog(log: NotificationLog)
}

/** Показ уведомлений; реализация на Android. */
interface AppNotifier {
    fun show(notification: AppNotification, private: Boolean)
}

/** Обновление виджета; реализация на Glance. */
interface WidgetRefresher {
    suspend fun refresh()
}
