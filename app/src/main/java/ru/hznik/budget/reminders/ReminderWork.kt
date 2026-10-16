package ru.hznik.budget.reminders

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.hznik.budget.domain.reminders.CheckNotifications
import ru.hznik.budget.domain.reminders.NotificationPlanner
import ru.hznik.budget.domain.reminders.WidgetRefresher
import ru.hznik.budget.domain.security.DatabaseAccess
import ru.hznik.budget.domain.security.DatabaseState
import ru.hznik.budget.notifications.NotificationIntake
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Постановка фоновых задач (design.md add-widget-and-reminders, решение 4): `DailyWork` на ближайшие 10:00 и
 * `MidnightWork` на 00:01. Каждая после выполнения ставит себя на следующий день; WorkManager сам восстанавливает
 * их после перезагрузки телефона.
 */
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) {
    /** При запуске приложения: уже поставленные задачи не трогаются. */
    fun ensureScheduled() {
        enqueue(DAILY, request<DailyWork>(NotificationPlanner.REMINDER_TIME), ExistingWorkPolicy.KEEP)
        enqueue(MIDNIGHT, request<MidnightWork>(MIDNIGHT_TIME), ExistingWorkPolicy.KEEP)
    }

    /**
     * Из самой задачи: следующая встаёт в цепочку после текущей. `REPLACE` отменил бы выполняющуюся задачу,
     * а `KEEP` ничего не поставил бы, пока она ещё идёт.
     */
    internal fun scheduleNextDaily() = enqueue(DAILY, request<DailyWork>(NotificationPlanner.REMINDER_TIME), ExistingWorkPolicy.APPEND_OR_REPLACE)

    internal fun scheduleNextMidnight() = enqueue(MIDNIGHT, request<MidnightWork>(MIDNIGHT_TIME), ExistingWorkPolicy.APPEND_OR_REPLACE)

    /** Сколько ждать до ближайшего [time]; ровно в [time] — уже следующий день. */
    fun delayUntil(time: LocalTime): Duration {
        val now = LocalDateTime.now(clock)
        val today = now.toLocalDate().atTime(time)
        val next = if (now.isBefore(today)) today else today.plusDays(1)
        return Duration.between(now, next)
    }

    private inline fun <reified W : CoroutineWorker> request(time: LocalTime): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<W>().setInitialDelay(delayUntil(time).toMillis(), TimeUnit.MILLISECONDS).build()

    private fun enqueue(name: String, request: OneTimeWorkRequest, policy: ExistingWorkPolicy) {
        WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
    }

    companion object {
        const val DAILY = "reminders_daily"
        const val MIDNIGHT = "reminders_midnight"
        val MIDNIGHT_TIME: LocalTime = LocalTime.of(0, 1)
    }
}

/** 10:00: напоминания о платежах на завтра, «наступил день дохода», очистка образцов, обновление виджета. */
@HiltWorker
class DailyWork @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val database: DatabaseAccess,
    // Lazy: сценарии тянут за собой Room, а базу можно создавать только после проверки ключа.
    private val check: Lazy<CheckNotifications>,
    private val intake: Lazy<NotificationIntake>,
    private val widget: WidgetRefresher,
    private val scheduler: ReminderScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            if (database.open() == DatabaseState.READY) {
                runCatching { intake.get().purgeExpired() }
                runCatching { check.get().invoke() }
            } else {
                runCatching { widget.refresh() }
            }
        } finally {
            // Остановленную системой задачу WorkManager запустит снова, и следующую поставит уже она:
            // иначе в цепочке копились бы лишние запуски.
            if (!isStopped) scheduler.scheduleNextDaily()
        }
        return Result.success()
    }
}

/** 00:01: новый день — виджет пересчитывает «можно сегодня» (spec home-widget). */
@HiltWorker
class MidnightWork @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val database: DatabaseAccess,
    private val check: Lazy<CheckNotifications>,
    private val widget: WidgetRefresher,
    private val scheduler: ReminderScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            if (database.open() == DatabaseState.READY) runCatching { check.get().invoke() } else runCatching { widget.refresh() }
        } finally {
            if (!isStopped) scheduler.scheduleNextMidnight()
        }
        return Result.success()
    }
}
