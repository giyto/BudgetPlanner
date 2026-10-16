package ru.hznik.budget.ui

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
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
import ru.hznik.budget.reminders.AppActionIntents
import ru.hznik.budget.reminders.PendingAction
import ru.hznik.budget.ui.lock.AuthResult
import ru.hznik.budget.ui.lock.LOCK_SCREEN_TAG
import ru.hznik.budget.ui.settings.NOTIFICATIONS_DENIED_TAG
import ru.hznik.budget.ui.settings.notificationSwitchTag
import javax.inject.Inject

private fun FlowTestBase.tapNode(text: String) = rule.onNode(hasText(text) and hasClickAction()).performClick()

/** Экран ввода операции: его заголовок — только название окна для TalkBack, поэтому ждём кнопку «Сохранить». */
private fun FlowTestBase.waitForEditor() =
    rule.waitUntil(5_000) { rule.onAllNodes(hasText("Сохранить") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }

private fun FlowTestBase.openSettingsScreen() = rule.onNode(hasContentDescription("Настройки") and hasClickAction()).performClick()

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
        tapNode("Дальше")
        typeAmount("18000")
        tapNode("Выберите число")
        tapNode("25")
        tapNode("Дальше")
        tapNode("Пропустить")
        tapNode("Вводить вручную")

        rule.onNodeWithText("Шаг 5 из 6").assertIsDisplayed()
        rule.onNodeWithText("Подсказки вовремя").assertIsDisplayed()
        rule.onNode(hasText("Разрешить уведомления") and hasClickAction()).assertIsDisplayed()
        rule.assertTouchTargets()

        tapNode("Не сейчас")
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
        openSettingsScreen()
        val overspend = rule.onNodeWithTag(notificationSwitchTag(NotificationType.OVERSPEND))
        overspend.performScrollTo().assertIsOn()
        rule.onNodeWithTag(NOTIFICATIONS_DENIED_TAG).assertDoesNotExist()

        overspend.performClick()
        overspend.assertIsOff()
        rule.waitUntil(5_000) { runBlocking { NotificationType.OVERSPEND !in notificationSettings.enabled.first() } }
        assertTrue(NotificationType.PAYMENT in runBlocking { notificationSettings.enabled.first() })

        overspend.performClick()
        overspend.assertIsOn()
        rule.waitUntil(5_000) { runBlocking { NotificationType.OVERSPEND in notificationSettings.enabled.first() } }
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
        openSettingsScreen()
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
    @Test
    fun addExpenseActionOpensEditor() {
        waitForHero("Можно потратить сегодня")
        send(AppActionIntents.intent(rule.activity, PendingAction(AppAction.ADD_EXPENSE)))
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
        send(fromHistory)
        rule.waitForIdle()
        rule.onNode(hasText("Сохранить") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun actionSentDirectlyToMainActivityIsIgnored() {
        waitForHero("Можно потратить сегодня")
        val direct = Intent(AppActionIntents.intent(rule.activity, PendingAction(AppAction.ADD_EXPENSE)))
            .setClass(rule.activity, MainActivity::class.java)
        send(direct)
        rule.waitForIdle()
        Thread.sleep(500)
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
        tapNode("Разблокировать")
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
        tapNode("Дальше")
        typeAmount("18000")
        tapNode("Выберите число")
        tapNode("25")
        tapNode("Дальше")
        tapNode("Пропустить")
        tapNode("Вводить вручную")
        tapNode("Не сейчас")
        waitForHero("Можно потратить сегодня")

        send(AppActionIntents.intent(rule.activity, PendingAction(AppAction.ADD_EXPENSE)))
        waitForEditor()
    }
}
