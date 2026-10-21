package ru.hznik.budget.ui

import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Test
import ru.hznik.budget.TEST_TODAY
import ru.hznik.budget.TestStart
import ru.hznik.budget.domain.model.OperationSource

/**
 * Автоматическая проверка доступности (Accessibility Test Framework) на основных экранах (spec app-theme «Подписи для
 * TalkBack»; задача 6.1): элементы без подписи и мелкие области нажатия — ошибка.
 */
@HiltAndroidTest
class AccessibilityFlowTest : FlowTestBase() {
    @Before
    fun checks() {
        rule.enableAccessibilityChecks()
    }

    /** Проверка всех окон экрана (диалоги и нижние листы — отдельные окна). */
    private fun checkScreen() {
        rule.waitForIdle()
        rule.onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { i -> rule.onAllNodes(isRoot())[i].tryPerformAccessibilityChecks() }
    }

    @Test
    fun mainScreensPassAccessibilityChecks() {
        insert("Продукты", 450, date = TEST_TODAY, merchant = "Пятёрочка")
        insert("Другое", 340, date = TEST_TODAY, merchant = "Кофемания", source = OperationSource.NOTIFICATION, draft = true)
        waitForHero("Можно потратить сегодня")
        checkScreen()

        openTab("Операции")
        waitForRows(2)
        checkScreen()

        tapAdd()
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Стереть") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        checkScreen()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

        openTab("Главная")
        openDrafts()
        checkScreen()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

        openTab("Статистика")
        waitForText("Статистика")
        checkScreen()

        openTab("Помощник")
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Помощник", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        checkScreen()

        openTab("Главная")
        openSettings()
        waitForText("Настройки")
        checkScreen()
    }
}

/** Онбординг с проверкой доступности: первый шаг и шаг суммы. */
@HiltAndroidTest
class OnboardingAccessibilityFlowTest : FlowTestBase() {
    init {
        TestStart.onboarded = false
    }

    @Before
    fun checks() {
        rule.enableAccessibilityChecks()
    }

    @Test
    fun onboardingStepsPassAccessibilityChecks() {
        waitForText("Шаг 1 из", substring = true)
        rule.onAllNodes(isRoot())[0].tryPerformAccessibilityChecks()
        tapText("Дальше")
        waitForText("Шаг 2 из", substring = true)
        rule.onAllNodes(isRoot())[0].tryPerformAccessibilityChecks()
    }
}
