package ru.hznik.budget.ui

import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.FakeDatabaseAccess
import ru.hznik.budget.InMemoryLockSettingsRepository
import ru.hznik.budget.TestAuth
import ru.hznik.budget.TestElapsedClock
import ru.hznik.budget.TestStart
import ru.hznik.budget.domain.lock.AppLock
import ru.hznik.budget.domain.lock.LockSettings
import ru.hznik.budget.domain.lock.LockSettingsRepository
import ru.hznik.budget.domain.lock.LockTimeout
import ru.hznik.budget.domain.security.DatabaseAccess
import ru.hznik.budget.ui.home.HOME_HERO_TAG
import ru.hznik.budget.ui.lock.AuthAvailability
import ru.hznik.budget.ui.lock.AuthResult
import ru.hznik.budget.ui.lock.DATABASE_ERROR_TAG
import ru.hznik.budget.ui.lock.KEY_LOST_TAG
import ru.hznik.budget.ui.lock.LOCK_SCREEN_TAG
import ru.hznik.budget.ui.stats.STATS_LIST_TAG
import javax.inject.Inject

private fun FlowTestBase.tap(text: String) = rule.onNode(hasText(text) and hasClickAction()).performClick()

private fun FlowTestBase.waitForTag(tag: String) = rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

private fun FlowTestBase.openSettings() = rule.onNode(hasContentDescription("Настройки") and hasClickAction()).performClick()

private fun FlowTestBase.switch(title: String) = rule.onNode(hasText(title, substring = true) and isToggleable())

/** Экран блокировки (spec app-lock, задача 5.5): системная проверка подменена [TestAuth]. */
@HiltAndroidTest
class LockScreenFlowTest : FlowTestBase() {
    init {
        TestStart.lock = LockSettings(enabled = true, timeout = LockTimeout.ONE_MINUTE)
        TestAuth.result = AuthResult.Cancelled
    }

    @Inject lateinit var appLock: AppLock

    @Test
    fun coldStartIsLockedAndCancelKeepsAmountsHidden() {
        waitForTag(LOCK_SCREEN_TAG)
        rule.onNodeWithText("BudgetPlanner заблокирован").assertIsDisplayed()
        // Проверка открылась сама и была отменена: суммы в дереве нет совсем.
        rule.waitUntil(5_000) { TestAuth.calls >= 1 }
        rule.onNodeWithTag(HOME_HERO_TAG).assertDoesNotExist()
        rule.onNodeWithTag(BOTTOM_BAR_TAG).assertDoesNotExist()

        TestAuth.result = AuthResult.Success
        tap("Разблокировать")
        waitForHero("Можно потратить сегодня")
        rule.onNodeWithTag(LOCK_SCREEN_TAG).assertDoesNotExist()
    }

    @Test
    fun returningToTheAppOpensTheCheckAgain() {
        waitForTag(LOCK_SCREEN_TAG)
        rule.waitUntil(5_000) { TestAuth.calls == 1 }
        // Отмена пользователем: окно само больше не открывается, пока он не уйдёт и не вернётся.
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitUntil(5_000) { TestAuth.calls == 2 }
        rule.onNodeWithTag(LOCK_SCREEN_TAG).assertExists()
    }

    @Test
    fun errorFromSystemIsShown() {
        waitForTag(LOCK_SCREEN_TAG)
        TestAuth.result = AuthResult.Error("Слишком много попыток")
        tap("Разблокировать")
        waitForText("Слишком много попыток")
    }

    @Test
    fun afterUnlockTheSameScreenIsOpen() {
        waitForTag(LOCK_SCREEN_TAG)
        TestAuth.result = AuthResult.Success
        tap("Разблокировать")
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(BOTTOM_BAR_TAG)).fetchSemanticsNodes().isNotEmpty() }
        openTab("Статистика")
        waitForTag(STATS_LIST_TAG)

        // Свернули на 40 секунд — без проверки; на 3 минуты — блокировка.
        TestAuth.result = AuthResult.Cancelled
        rule.runOnUiThread { appLock.onBackground() }
        TestElapsedClock.now += 40_000
        rule.runOnUiThread { appLock.onForeground() }
        rule.waitForIdle()
        rule.onNodeWithTag(STATS_LIST_TAG).assertExists()

        rule.runOnUiThread { appLock.onBackground() }
        TestElapsedClock.now += 3 * 60_000
        rule.runOnUiThread { appLock.onForeground() }
        waitForTag(LOCK_SCREEN_TAG)
        rule.onNodeWithTag(STATS_LIST_TAG).assertDoesNotExist()

        TestAuth.result = AuthResult.Success
        tap("Разблокировать")
        waitForTag(STATS_LIST_TAG)
        rule.onNode(hasText("Статистика") and hasClickAction()).assertIsDisplayed()
    }
}

/** Телефон без блокировки экрана, а защита была включена (spec app-lock). */
@HiltAndroidTest
class ScreenLockRemovedFlowTest : FlowTestBase() {
    init {
        TestStart.lock = LockSettings(enabled = true)
        TestAuth.availability = AuthAvailability.NO_SCREEN_LOCK
    }

    @Inject lateinit var lockSettings: LockSettingsRepository

    @Test
    fun protectionIsTurnedOffWithNotice() {
        waitForText("Защита входа выключена: на телефоне нет блокировки экрана")
        rule.onNodeWithTag(LOCK_SCREEN_TAG).assertDoesNotExist()
        assertFalse((lockSettings as InMemoryLockSettingsRepository).state.value.enabled)
    }
}

/** Потеря ключа базы (spec data-encryption, задача 6.2). */
@HiltAndroidTest
class KeyLostFlowTest : FlowTestBase() {
    init {
        TestStart.keyLost = true
        TestStart.onboarded = false
    }

    @Inject lateinit var database: DatabaseAccess

    @Test
    fun cancelKeepsDataAndConfirmStartsOnboarding() {
        waitForTag(KEY_LOST_TAG)
        rule.onNodeWithText("Не удалось открыть данные").assertIsDisplayed()
        rule.onNodeWithTag(BOTTOM_BAR_TAG).assertDoesNotExist()

        tap("Удалить данные и начать заново")
        tap("Отмена")
        rule.onNodeWithTag(KEY_LOST_TAG).assertIsDisplayed()
        assertFalse((database as FakeDatabaseAccess).wiped)

        tap("Удалить данные и начать заново")
        tap("Удалить")
        waitForText("Шаг 1 из 5")
        assertTrue((database as FakeDatabaseAccess).wiped)
    }
}

/** Временный сбой при открытии базы: только «Попробовать снова», удалить данные не предлагается. */
@HiltAndroidTest
class DatabaseErrorFlowTest : FlowTestBase() {
    init {
        TestStart.databaseError = true
    }

    @Test
    fun retryOpensTheApp() {
        waitForTag(DATABASE_ERROR_TAG)
        rule.onNode(hasText("Удалить данные и начать заново")).assertDoesNotExist()
        tap("Попробовать снова")
        waitForHero("Можно потратить сегодня")
    }
}

/** Раздел «Защита входа» в настройках (spec app-lock, задача 7.3). */
@HiltAndroidTest
class ProtectionSettingsFlowTest : FlowTestBase() {
    @Inject lateinit var lockSettings: LockSettingsRepository

    private val state get() = (lockSettings as InMemoryLockSettingsRepository).state.value

    @Test
    fun enableNeedsCheckAndShowsTimeout() {
        openSettings()
        TestAuth.result = AuthResult.Cancelled
        switch("Вход по отпечатку или PIN").performClick()
        rule.waitForIdle()
        switch("Вход по отпечатку или PIN").assertIsOff()
        assertFalse(state.enabled)

        TestAuth.result = AuthResult.Success
        switch("Вход по отпечатку или PIN").performClick()
        rule.waitUntil(5_000) { state.enabled }
        switch("Вход по отпечатку или PIN").assertIsOn()
        waitForText("Блокировать")
        rule.onNodeWithText("Через 1 мин").assertIsDisplayed()

        tap("Сразу")
        rule.waitUntil(5_000) { state.timeout == LockTimeout.IMMEDIATELY }

        // Выключение тоже через проверку.
        switch("Вход по отпечатку или PIN").performClick()
        rule.waitUntil(5_000) { !state.enabled }
        assertEquals(3, TestAuth.calls)
    }

    @Test
    fun hideInRecentsSetsFlagSecure() {
        openSettings()
        switch("Скрывать в недавних").performScrollTo().performClick()
        rule.waitUntil(5_000) { state.hideInRecents }
        rule.waitUntil(5_000) { rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 }

        switch("Скрывать в недавних").performClick()
        rule.waitUntil(5_000) { rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0 }
    }

    @Test
    fun withoutScreenLockSwitchIsDisabledWithHint() {
        TestAuth.availability = AuthAvailability.NO_SCREEN_LOCK
        openSettings()
        waitForText("Сначала настройте блокировку экрана телефона", substring = true)
        switch("Вход по отпечатку или PIN").assertIsNotEnabled()
        rule.onNode(hasText("Настроить") and hasClickAction()).assertIsDisplayed()
    }
}

/** Шаг онбординга «Защита входа» (spec onboarding). */
@HiltAndroidTest
class OnboardingProtectionFlowTest : FlowTestBase() {
    init {
        TestStart.onboarded = false
    }

    @Inject lateinit var lockSettings: LockSettingsRepository

    private fun reachProtectionStep() {
        tap("Дальше")
        typeAmount("18000")
        tap("Выберите число")
        tap("25")
        tap("Дальше")
        tap("Пропустить")
        tap("Вводить вручную")
        rule.onNodeWithText("Шаг 5 из 5").assertIsDisplayed()
        rule.onNodeWithText("Защитить вход?").assertIsDisplayed()
    }

    @Test
    fun enableTurnsProtectionOnAndOpensHome() {
        reachProtectionStep()
        TestAuth.result = AuthResult.Cancelled
        tap("Включить")
        rule.waitForIdle()
        rule.onNodeWithText("Шаг 5 из 5").assertIsDisplayed()

        TestAuth.result = AuthResult.Success
        tap("Включить")
        waitForHero("Можно потратить сегодня 1500 рублей")
        assertTrue((lockSettings as InMemoryLockSettingsRepository).state.value.enabled)
        // Защиту только что включили после проверки: блокировать сразу незачем.
        rule.onNodeWithTag(LOCK_SCREEN_TAG).assertDoesNotExist()
    }

    @Test
    fun withoutScreenLockOffersSetup() {
        // Доступность проверяется при появлении шага и после возврата на экран.
        TestAuth.availability = AuthAvailability.NO_SCREEN_LOCK
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        reachProtectionStep()
        rule.onNode(hasText("Настроить блокировку экрана") and hasClickAction()).assertIsDisplayed()
        rule.onNode(hasText("Включить") and hasClickAction()).assertDoesNotExist()
        tap("Не сейчас")
        waitForHero("Можно потратить сегодня")
        assertFalse((lockSettings as InMemoryLockSettingsRepository).state.value.enabled)
    }
}
