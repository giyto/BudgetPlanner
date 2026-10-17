package ru.hznik.budget.data.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.hznik.budget.domain.reminders.NotificationLog
import ru.hznik.budget.domain.reminders.NotificationType
import java.time.LocalDate

/**
 * Переключатели типов уведомлений и журнал отправленного (spec app-notifications, «по умолчанию все включены»;
 * design.md add-widget-and-reminders, решения 3 и 7).
 */
class NotificationSettingsStoreTest {
    @Test
    fun `all types are enabled and the log is empty by default`() = runTest {
        val settings = DataStoreNotificationSettings(InMemoryPreferencesDataStore())

        assertEquals(NotificationType.entries.toSet(), settings.enabled.first())
        assertEquals(NotificationLog(), settings.log())
    }

    @Test
    fun `a type can be switched off and back on`() = runTest {
        val store = InMemoryPreferencesDataStore()
        val settings = DataStoreNotificationSettings(store)

        settings.setEnabled(NotificationType.OVERSPEND, false)
        settings.setEnabled(NotificationType.BANK_MISMATCH, false)
        assertEquals(
            setOf(NotificationType.PAYMENT, NotificationType.NEW_PERIOD),
            DataStoreNotificationSettings(store).enabled.first(),
        )

        settings.setEnabled(NotificationType.OVERSPEND, true)
        assertEquals(
            setOf(NotificationType.OVERSPEND, NotificationType.PAYMENT, NotificationType.NEW_PERIOD),
            settings.enabled.first(),
        )
    }

    @Test
    fun `log is saved, read back in order and cleared`() = runTest {
        val store = InMemoryPreferencesDataStore()
        val settings = DataStoreNotificationSettings(store)
        val log = NotificationLog(
            overspendDate = LocalDate.of(2026, 10, 17),
            payments = setOf("7@2026-10-20"),
            incomeDayPeriod = 3,
            incomeArrivedPeriod = 4,
            mismatches = linkedSetOf("sber|1234|100", "tbank||200"),
        )

        settings.saveLog(log)
        val restored = DataStoreNotificationSettings(store).log()
        assertEquals(log, restored)
        // Порядок расхождений сохраняется: при обрезке журнала уходят самые старые ключи.
        assertEquals(listOf("sber|1234|100", "tbank||200"), restored.mismatches.toList())

        settings.saveLog(NotificationLog())
        assertEquals(NotificationLog(), settings.log())
    }
}
