package ru.hznik.budget.ui

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.CountingWidgetRefresher
import ru.hznik.budget.MainActivity
import ru.hznik.budget.RecordingAppNotifier
import ru.hznik.budget.TestAuth
import ru.hznik.budget.TestStart
import ru.hznik.budget.domain.lock.LockSettings
import ru.hznik.budget.domain.reminders.AppNotification
import ru.hznik.budget.domain.reminders.AppNotifier
import ru.hznik.budget.domain.reminders.NotificationSettings
import ru.hznik.budget.domain.reminders.NotificationType
import ru.hznik.budget.domain.reminders.WidgetRefresher
import ru.hznik.budget.reminders.AppAction
import ru.hznik.budget.reminders.AppActionBus
import ru.hznik.budget.reminders.AppActionIntents
import ru.hznik.budget.reminders.PendingAction
import ru.hznik.budget.ui.lock.AuthResult
import ru.hznik.budget.ui.lock.LOCK_SCREEN_TAG
import ru.hznik.budget.ui.settings.NOTIFICATIONS_DENIED_TAG
import ru.hznik.budget.ui.settings.notificationSwitchTag
import javax.inject.Inject

/** Экран ввода операции: его заголовок — только название окна для TalkBack, поэтому ждём кнопку «Сохранить». */
private fun FlowTestBase.waitForEditor() =
    rule.waitUntil(5_000) { rule.onAllNodes(hasText("Сохранить") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }

/** Шаг онбординга «Подсказки вовремя» (spec onboarding, задача 5.1). */
@HiltAndroidTest
class OnboardingNotificationsFlowTest : FlowTestBase() {
    init {
        TestStart.onboarded = false
        TestStart.notificationPermissionNeeded = true
    }

    @Test
    fun stepComesAfterAutoImportAndLeadsToProtection() {
        rule.onNodeWithText("Шаг 1 из 6").assertIsDisplayed()
        walkOnboardingPastAutoImport()

        rule.onNodeWithText("Шаг 5 из 6").assertIsDisplayed()
        rule.onNodeWithText("Подсказки вовремя").assertIsDisplayed()
        rule.onNode(hasText("Разрешить уведомления") and hasClickAction()).assertIsDisplayed()
        rule.assertTouchTargets()

        tapText("Не сейчас")
        rule.onNodeWithText("Шаг 6 из 6").assertIsDisplayed()
        rule.onNodeWithText("Защитить вход?").assertIsDisplayed()
    }
}

/** Раздел «Уведомления» в настройках (spec app-notifications, задача 5.2). */
@HiltAndroidTest
class NotificationsSettingsFlowTest : FlowTestBase() {
    @Inject lateinit var notificationSettings: NotificationSettings

    @Test
    fun switchesTurnTypesOffAndOn() {
        waitForHero("Можно потратить сегодня")
        openSettings()
        val overspend = rule.onNodeWithTag(notificationSwitchTag(NotificationType.OVERSPEND))
        overspend.performScrollTo().assertIsOn()
        rule.onNodeWithTag(NOTIFICATIONS_DENIED_TAG).assertDoesNotExist()

        overspend.performClick()
        // Состояние переключателя приходит из DataStore не сразу: сначала ждём настройку, потом проверяем экран.
        rule.waitUntil(5_000) { runBlocking { NotificationType.OVERSPEND !in notificationSettings.enabled.first() } }
        rule.waitForIdle()
        overspend.assertIsOff()
        assertTrue(NotificationType.PAYMENT in runBlocking { notificationSettings.enabled.first() })

        overspend.performClick()
        rule.waitUntil(5_000) { runBlocking { NotificationType.OVERSPEND in notificationSettings.enabled.first() } }
        rule.waitForIdle()
        overspend.assertIsOn()
        rule.assertTouchTargets()
    }
}

/** Без разрешения раздел показывает «Уведомления запрещены в системе» и «Разрешить». */
@HiltAndroidTest
class NotificationsDeniedFlowTest : FlowTestBase() {
    init {
        TestStart.notificationPermissionNeeded = true
    }

    @Test
    fun deniedRowWithAllowButton() {
        waitForHero("Можно потратить сегодня")
        openSettings()
        rule.onNodeWithTag(NOTIFICATIONS_DENIED_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Уведомления запрещены в системе").assertIsDisplayed()
        rule.onNode(hasText("Разрешить") and hasClickAction()).assertIsDisplayed()
    }
}

/** Перерасход в приложении: одно уведомление за день и обновление виджета (spec app-notifications, home-widget). */
@HiltAndroidTest
class OverspendNotificationFlowTest : FlowTestBase() {
    @Inject lateinit var notifier: AppNotifier

    @Inject lateinit var widget: WidgetRefresher

    @Test
    fun overspendIsNotifiedOnceAndWidgetRefreshes() {
        val recorded = (notifier as RecordingAppNotifier).shown
        val refreshes = (widget as CountingWidgetRefresher).refreshes
        waitForHero("Можно потратить сегодня 1500 рублей")
        rule.waitUntil(5_000) { refreshes.get() >= 1 }
        assertTrue(recorded.isEmpty())

        insert("Продукты", 2_000)
        rule.waitUntil(5_000) { recorded.isNotEmpty() }
        val (notification, private) = recorded.single()
        assertEquals(500_00L, (notification as AppNotification.Overspend).overKopecks)
        assertFalse(private)

        val before = refreshes.get()
        insert("Продукты", 100)
        rule.waitUntil(5_000) { refreshes.get() > before }
        assertEquals(1, recorded.size)
    }
}

/**
 * Переход как из `PendingIntent`: тот же `Intent` передаётся в обработчик активности. Через `startActivity`
 * в тесте нельзя: задачу создаёт загрузчик androidx.test, и система открывает вторую активность вместо
 * `onNewIntent`. Доставку системой проверяет ручная проверка на эмуляторе и телефоне.
 */
private fun FlowTestBase.send(intent: Intent) = rule.runOnUiThread { rule.activity.handleAction(intent) }

/** Переходы из виджета и уведомлений (design.md, решение 6; задача 3.3). */
@HiltAndroidTest
class AppActionFlowTest : FlowTestBase() {
    @Inject lateinit var actions: AppActionBus

    /**
     * Передаёт [intent] активности и в том же проходе главного потока читает отложенный переход. Навигация снимает его
     * с шины в своей корутине уже после этого прохода, поэтому `null` здесь значит, что переход не был принят вовсе.
     */
    private fun sendAndReadPending(intent: Intent): PendingAction? = rule.runOnUiThread {
        rule.activity.handleAction(intent)
        actions.pending.value
    }

    @Test
    fun addExpenseActionOpensEditor() {
        waitForHero("Можно потратить сегодня")
        // Принятый переход виден на шине: так проверка `null` в тестах ниже не может пройти впустую.
        assertEquals(PendingAction(AppAction.ADD_EXPENSE), sendAndReadPending(AppActionIntents.intent(rule.activity, PendingAction(AppAction.ADD_EXPENSE))))
        waitForEditor()
    }

    @Test
    fun newPeriodActionOpensNewPeriod() {
        waitForHero("Можно потратить сегодня")
        send(AppActionIntents.intent(rule.activity, PendingAction(AppAction.NEW_PERIOD)))
        waitForText("Новый период")
    }

    @Test
    fun relaunchFromRecentsDoesNotRepeatAction() {
        waitForHero("Можно потратить сегодня")
        val fromHistory = AppActionIntents.intent(rule.activity, PendingAction(AppAction.ADD_EXPENSE))
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(sendAndReadPending(fromHistory))
        rule.waitForIdle()
        rule.onNode(hasText("Сохранить") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun actionSentDirectlyToMainActivityIsIgnored() {
        waitForHero("Можно потратить сегодня")
        val direct = Intent(AppActionIntents.intent(rule.activity, PendingAction(AppAction.ADD_EXPENSE)))
            .setClass(rule.activity, MainActivity::class.java)
        assertNull(sendAndReadPending(direct))
        rule.waitForIdle()
        rule.onNode(hasText("Сохранить") and hasClickAction()).assertDoesNotExist()
        waitForHero("Можно потратить сегодня")
    }
}

/** «+» на виджете при защите входа: сначала экран блокировки, после разблокировки — ввод траты. */
@HiltAndroidTest
class AppActionLockedFlowTest : FlowTestBase() {
    init {
        TestStart.lock = LockSettings(enabled = true)
        TestAuth.result = AuthResult.Cancelled
    }

    @Test
    fun addExpenseOpensAfterUnlock() {
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(LOCK_SCREEN_TAG)).fetchSemanticsNodes().isNotEmpty() }
        send(AppActionIntents.intent(rule.activity, PendingAction(AppAction.ADD_EXPENSE)))
        rule.waitForIdle()
        rule.onNodeWithTag(LOCK_SCREEN_TAG).assertIsDisplayed()
        rule.onNode(hasText("Сохранить") and hasClickAction()).assertDoesNotExist()

        TestAuth.result = AuthResult.Success
        tapText("Разблокировать")
        waitForEditor()
    }
}

/** Онбординг пройден в этом же запуске: переход из виджета всё равно работает (найдено при ручной проверке). */
@HiltAndroidTest
class AppActionAfterOnboardingFlowTest : FlowTestBase() {
    init {
        TestStart.onboarded = false
    }

    @Test
    fun addExpenseWorksRightAfterOnboarding() {
        walkOnboardingPastAutoImport()
        tapText("Не сейчас")
        waitForHero("Можно потратить сегодня")

        send(AppActionIntents.intent(rule.activity, PendingAction(AppAction.ADD_EXPENSE)))
        waitForEditor()
    }
}
