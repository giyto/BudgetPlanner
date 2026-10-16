package ru.hznik.budget.domain.reminders

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.hznik.budget.domain.lock.LockSettingsRepository
import ru.hznik.budget.domain.repository.PlannedPaymentRepository
import ru.hznik.budget.domain.usecase.IncomePeriodSuggestion
import ru.hznik.budget.domain.usecase.ObserveMismatches
import ru.hznik.budget.domain.usecase.ObservePeriodStatus
import java.time.Clock
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** Один замок на проверку уведомлений: импорт, приложение и фоновые задачи не отправят одно уведомление дважды. */
@Singleton
class NotificationsLock @Inject constructor() {
    val mutex = Mutex()
}

/**
 * Проверка уведомлений приложения и обновление виджета (design.md add-widget-and-reminders, решения 2–3). Вызывается
 * после импорта операции, при изменении расчёта в приложении и фоновыми задачами. База к этому моменту открыта.
 */
class CheckNotifications @Inject constructor(
    private val observeStatus: ObservePeriodStatus,
    private val payments: PlannedPaymentRepository,
    private val observeMismatches: ObserveMismatches,
    private val incomeSuggestion: IncomePeriodSuggestion,
    private val settings: NotificationSettings,
    private val lockSettings: LockSettingsRepository,
    private val notifier: AppNotifier,
    private val widget: WidgetRefresher,
    private val lock: NotificationsLock,
    private val clock: Clock,
) {
    suspend operator fun invoke(refreshWidget: Boolean = true) {
        lock.mutex.withLock {
            val input = PlannerInput(
                now = LocalDateTime.now(clock),
                status = observeStatus().first(),
                payments = payments.observeAll().first(),
                mismatches = observeMismatches().first(),
                incomeSuggestion = incomeSuggestion.observe().first()?.amount,
                enabled = settings.enabled.first(),
            )
            val plan = NotificationPlanner.plan(input, settings.log())
            if (plan.notifications.isNotEmpty()) {
                // При защите входа текст уведомления не виден на экране блокировки телефона (spec app-notifications).
                val private = lockSettings.observe().first().enabled
                plan.notifications.forEach { notifier.show(it, private) }
            }
            settings.saveLog(plan.log)
        }
        if (refreshWidget) widget.refresh()
    }
}
