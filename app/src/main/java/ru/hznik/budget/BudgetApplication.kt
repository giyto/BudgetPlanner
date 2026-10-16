package ru.hznik.budget

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import ru.hznik.budget.reminders.NotificationChannels
import ru.hznik.budget.reminders.ReminderScheduler
import javax.inject.Inject

/** Каналы уведомлений и фоновые задачи создаются при каждом запуске процесса (add-widget-and-reminders). */
@HiltAndroidApp
class BudgetApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var reminderScheduler: ReminderScheduler

    // WorkManager создаётся по требованию с фабрикой Hilt; стандартный запуск отключён в манифесте.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
        reminderScheduler.ensureScheduled()
    }
}
