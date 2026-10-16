package ru.hznik.budget.reminders

import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.hznik.budget.CountingWidgetRefresher
import ru.hznik.budget.RecordingAppNotifier
import ru.hznik.budget.TEST_TODAY
import ru.hznik.budget.TEST_ZONE
import ru.hznik.budget.TestStart
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.PaymentRepeat
import ru.hznik.budget.domain.model.PlannedPayment
import ru.hznik.budget.domain.notifications.NotificationRepository
import ru.hznik.budget.domain.reminders.AppNotification
import ru.hznik.budget.domain.reminders.AppNotifier
import ru.hznik.budget.domain.reminders.WidgetRefresher
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.PlannedPaymentRepository
import java.time.Instant
import javax.inject.Inject

/** Фоновые задачи на фиксированных часах (design.md add-widget-and-reminders, решение 4; задачи 4.2–4.3). */
@HiltAndroidTest
class ReminderWorkTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var notifier: AppNotifier

    @Inject lateinit var widget: WidgetRefresher

    @Inject lateinit var payments: PlannedPaymentRepository

    @Inject lateinit var categories: CategoryRepository

    @Inject lateinit var samples: NotificationRepository

    private val context: Context = ApplicationProvider.getApplicationContext()

    init {
        // 13 октября 10:00 — время напоминаний.
        TestStart.now = TEST_TODAY.atTime(10, 0)
    }

    @Before
    fun setUp() {
        hiltRule.inject()
        val config = Configuration.Builder().setWorkerFactory(workerFactory).setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @After
    fun tearDown() {
        TestStart.reset()
    }

    private inline fun <reified W : ListenableWorker> run(): ListenableWorker.Result =
        runBlocking { TestListenableWorkerBuilder<W>(context).setWorkerFactory(workerFactory).build().startWork().get() }

    private fun recorded() = (notifier as RecordingAppNotifier).shown.map { it.first }

    private fun enqueued(name: String) = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get().filter { it.state == WorkInfo.State.ENQUEUED }

    @Test
    fun dailyWorkRemindsAboutTomorrowsPaymentOnceAndSchedulesTheNextDay() {
        val other = runBlocking { categories.observeAll().first().first { it.defaultKey == "other_expense" } }
        val id = runBlocking {
            payments.save(PlannedPayment(name = "Интернет", amount = Money(650_00), dueDate = TEST_TODAY.plusDays(1), repeat = PaymentRepeat.MONTHLY, categoryId = other.id))
        }

        assertEquals(ListenableWorker.Result.success(), run<DailyWork>())
        assertEquals(listOf<AppNotification>(AppNotification.PaymentDue(id, "Интернет", Money(650_00), TEST_TODAY.plusDays(1))), recorded())
        assertTrue((widget as CountingWidgetRefresher).refreshes.get() >= 1)
        assertEquals(1, enqueued(ReminderScheduler.DAILY).size)

        // Повторный запуск в тот же день (например, после перезагрузки) напоминание не повторяет.
        run<DailyWork>()
        assertEquals(1, recorded().size)
    }

    @Test
    fun dailyWorkDeletesSamplesOlderThanADay() {
        val now = TEST_TODAY.atTime(10, 0).atZone(TEST_ZONE).toInstant()
        runBlocking {
            samples.addSample("sber", now.minusSeconds(25 * 3600), "старый образец")
            samples.addSample("sber", now.minusSeconds(3600), "свежий образец")
        }
        run<DailyWork>()
        val left = runBlocking { samples.observeSamples(Instant.EPOCH).first() }
        assertEquals(listOf("свежий образец"), left.map { it.text })
    }

    @Test
    fun midnightWorkRefreshesTheWidgetAndSchedulesItself() {
        assertEquals(ListenableWorker.Result.success(), run<MidnightWork>())
        assertTrue((widget as CountingWidgetRefresher).refreshes.get() >= 1)
        assertEquals(1, enqueued(ReminderScheduler.MIDNIGHT).size)
    }
}

/** День дохода: «Наступил день дохода» в 10:00 (spec app-notifications «Предложение нового периода»). */
@HiltAndroidTest
class IncomeDayWorkTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var notifier: AppNotifier

    private val context: Context = ApplicationProvider.getApplicationContext()

    init {
        TestStart.nextIncome = TEST_TODAY
        TestStart.now = TEST_TODAY.atTime(10, 5)
    }

    @Before
    fun setUp() {
        hiltRule.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setWorkerFactory(workerFactory).setExecutor(SynchronousExecutor()).build(),
        )
    }

    @After
    fun tearDown() {
        TestStart.reset()
    }

    @Test
    fun incomeDayIsNotified() {
        runBlocking { TestListenableWorkerBuilder<DailyWork>(context).setWorkerFactory(workerFactory).build().startWork().get() }
        assertEquals(listOf<AppNotification>(AppNotification.IncomeDay), (notifier as RecordingAppNotifier).shown.map { it.first })
    }
}
