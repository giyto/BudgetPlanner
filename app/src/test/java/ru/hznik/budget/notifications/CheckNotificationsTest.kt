package ru.hznik.budget.notifications

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.domain.categorize.AutoCategorize
import ru.hznik.budget.domain.lock.LockSettings
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.model.Period
import ru.hznik.budget.domain.notifications.BankNotification
import ru.hznik.budget.domain.notifications.parser.SberParser
import ru.hznik.budget.domain.reminders.AppNotification
import ru.hznik.budget.domain.reminders.AppNotifier
import ru.hznik.budget.domain.reminders.CheckNotifications
import ru.hznik.budget.domain.reminders.NotificationLog
import ru.hznik.budget.domain.reminders.NotificationSettings
import ru.hznik.budget.domain.reminders.NotificationType
import ru.hznik.budget.domain.reminders.NotificationsLock
import ru.hznik.budget.domain.reminders.WidgetRefresher
import ru.hznik.budget.domain.security.DatabaseAccess
import ru.hznik.budget.domain.security.DatabaseState
import ru.hznik.budget.domain.usecase.ExpectedBalance
import ru.hznik.budget.domain.usecase.ImportNotification
import ru.hznik.budget.domain.usecase.IncomePeriodSuggestion
import ru.hznik.budget.domain.usecase.ObserveMismatches
import ru.hznik.budget.domain.usecase.ObservePeriodStatus
import ru.hznik.budget.domain.usecase.ReconcileBalance
import ru.hznik.budget.domain.usecase.ReconciliationLock
import ru.hznik.budget.domain.usecase.TodayTicker
import ru.hznik.budget.testing.FakeAutoReconcileSettings
import ru.hznik.budget.testing.FakeCategoryRepository
import ru.hznik.budget.testing.FakeCategoryRuleRepository
import ru.hznik.budget.testing.FakeLockSettingsRepository
import ru.hznik.budget.testing.FakeOperationRepository
import ru.hznik.budget.testing.FakePeriodRepository
import ru.hznik.budget.testing.FakePlannedPaymentRepository
import ru.hznik.budget.testing.FakeReconciliationRepository
import ru.hznik.budget.testing.CAFE
import ru.hznik.budget.testing.PRODUCTS
import ru.hznik.budget.testing.SALARY
import ru.hznik.budget.testing.TEST_CLOCK
import ru.hznik.budget.testing.TRANSFERS
import ru.hznik.budget.testing.TRANSPORT
import ru.hznik.budget.testing.category
import java.time.LocalDate

class FakeNotificationSettings(initial: Set<NotificationType> = NotificationType.entries.toSet()) : NotificationSettings {
    val state = MutableStateFlow(initial)
    var savedLog = NotificationLog()

    override val enabled: Flow<Set<NotificationType>> = state

    override suspend fun setEnabled(type: NotificationType, enabled: Boolean) {
        state.value = if (enabled) state.value + type else state.value - type
    }

    override suspend fun log(): NotificationLog = savedLog

    override suspend fun saveLog(log: NotificationLog) {
        savedLog = log
    }
}

class RecordingNotifier : AppNotifier {
    val shown = mutableListOf<Pair<AppNotification, Boolean>>()

    override fun show(notification: AppNotification, private: Boolean) {
        shown += notification to private
    }
}

class CountingWidgetRefresher : WidgetRefresher {
    var refreshes = 0

    override suspend fun refresh() {
        refreshes++
    }
}

/** Проверка уведомлений после импорта (spec app-notifications «Перерасход», spec home-widget «Обновление»). */
class CheckNotificationsTest {
    private val categories = FakeCategoryRepository(
        listOf(PRODUCTS, CAFE, TRANSPORT, TRANSFERS, SALARY, category(30, "Другое", defaultKey = "other_expense"), category(31, "Другое", kind = CategoryKind.INCOME, defaultKey = "other_income")),
    )
    private val operations = FakeOperationRepository(categories)
    private val payments = FakePlannedPaymentRepository(operations)
    private val periods = FakePeriodRepository(payments).apply {
        // Период 1–25 октября на 18 000 ₽: 13 октября дневной лимит 1 500 ₽.
        state.value = listOf(Period(id = 1, startDate = LocalDate.of(2026, 10, 1), nextIncomeDate = LocalDate.of(2026, 10, 25), startAmountKopecks = 18_000_00))
    }
    private val recon = FakeReconciliationRepository(operations)
    private val reconSettings = FakeAutoReconcileSettings()
    private val notificationRepository = FakeNotificationRepository()
    private val settings = FakeNotificationSettings()
    private val lockSettings = FakeLockSettingsRepository()
    private val notifier = RecordingNotifier()
    private val widget = CountingWidgetRefresher()
    private val check = CheckNotifications(
        ObservePeriodStatus(periods, operations, payments, TodayTicker(TEST_CLOCK)),
        payments,
        ObserveMismatches(recon, reconSettings),
        IncomePeriodSuggestion(operations),
        settings,
        lockSettings,
        notifier,
        widget,
        NotificationsLock(),
        TEST_CLOCK,
    )
    private val import = ImportNotification(
        operations, categories, notificationRepository, periods, FakeAutoImportSettings(),
        ReconcileBalance(recon, reconSettings, ExpectedBalance(recon), ReconciliationLock()), AutoCategorize(FakeCategoryRuleRepository(operations, categories)), TEST_CLOCK,
    )
    private val ready = object : DatabaseAccess {
        override suspend fun open() = DatabaseState.READY
        override suspend fun wipe() = Unit
    }
    private val intake = NotificationIntake(ready, { notificationRepository }, FakeSampleSettings(initial = false), setOf(SberParser()), { import }, { check }, TEST_CLOCK)

    private fun purchase(key: String, text: String) = BankNotification("ru.sberbankmobile", key, TEST_CLOCK.instant(), "СберБанк", text)

    @Test
    fun `overspending purchase from a bank gives one notification and refreshes the widget`() = runTest {
        intake.accept(purchase("k1", "Покупка 2 000 ₽ Хозмаг. Карта *1234"))
        val (over, private) = notifier.shown.single()
        assertEquals(AppNotification.Overspend(overKopecks = 500_00, tomorrowLimitKopecks = 1_454_00), over)
        assertEquals(false, private)
        assertEquals(1, widget.refreshes)

        // Вторая трата в тот же день: виджет обновляется, второго уведомления нет.
        intake.accept(purchase("k2", "Покупка 300 ₽ Хозмаг. Карта *1234"))
        assertEquals(1, notifier.shown.size)
        assertEquals(2, widget.refreshes)
    }

    @Test
    fun `with app lock the notification is private`() = runTest {
        lockSettings.state.value = LockSettings(enabled = true)
        intake.accept(purchase("k1", "Покупка 2 000 ₽ Хозмаг. Карта *1234"))
        assertTrue(notifier.shown.single().second)
    }

    @Test
    fun `purchase within the limit only refreshes the widget`() = runTest {
        intake.accept(purchase("k1", "Покупка 450 ₽ Хозмаг. Карта *1234"))
        assertTrue(notifier.shown.isEmpty())
        assertEquals(1, widget.refreshes)
    }
}
