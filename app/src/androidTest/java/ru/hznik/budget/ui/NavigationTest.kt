package ru.hznik.budget.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Сценарии spec app-navigation и требования к размеру и подписям кнопок из app-theme. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NavigationTest : FlowTestBase() {
    private val tabs = listOf("Главная", "Операции", "Статистика", "Помощник")
    private val titles = mapOf("Главная" to "Бюджет", "Операции" to "Операции", "Статистика" to "Статистика", "Помощник" to "Помощник")

    private fun tab(label: String) = rule.onNode(hasText(label) and isSelectable())
    private fun title(text: String) = rule.onNode(hasText(text) and isHeading())

    /** Экран ввода операции узнаётся по выбранному типу «Трата» в переключателе. */
    private fun editorTypeSwitch() = rule.onNode(hasText("Трата") and isSelectable())
    private fun assertOnTab(label: String) {
        tab(label).assertIsSelected()
        title(titles.getValue(label)).assertIsDisplayed()
    }

    @Test
    fun startsOnHomeWithHomeTabSelected() {
        assertOnTab("Главная")
        rule.onNodeWithTag(BOTTOM_BAR_TAG).assertIsDisplayed()
    }

    @Test
    fun tabsAreShownInOrder() {
        val lefts = tabs.map { tab(it).fetchSemanticsNode().boundsInRoot.left }
        assertEquals(lefts.sorted(), lefts)
    }

    @Test
    fun eachTabOpensAndBackReturnsHome() {
        tabs.drop(1).forEach { label ->
            tab(label).performClick()
            assertOnTab(label)

            Espresso.pressBack()
            assertOnTab("Главная")
        }
    }

    @Test
    fun reselectingActiveTabKeepsItsStartScreen() {
        tab("Статистика").performClick()
        tab("Статистика").performClick()
        assertOnTab("Статистика")
    }

    @Test
    fun backOnHomeClosesApp() {
        Espresso.pressBackUnconditionally()
        // onDestroy приходит с задержкой после завершения активности, поэтому ждём, а не проверяем сразу.
        rule.waitUntil(timeoutMillis = 10_000) { rule.activityRule.scenario.state == Lifecycle.State.DESTROYED }
    }

    @Test
    fun addButtonOpensNewOperationWithoutBottomBar() {
        rule.onNodeWithContentDescription("Добавить операцию").performClick()
        editorTypeSwitch().assertIsDisplayed()
        rule.onNodeWithTag(BOTTOM_BAR_TAG).assertDoesNotExist()

        Espresso.pressBack()
        assertOnTab("Главная")
        rule.onNodeWithTag(BOTTOM_BAR_TAG).assertIsDisplayed()

        rule.onNodeWithContentDescription("Добавить операцию").performClick()
        rule.onNodeWithContentDescription("Закрыть").performClick()
        assertOnTab("Главная")
    }

    @Test
    fun doubleTapOnCloseReturnsToHomeOnce() {
        rule.onNodeWithContentDescription("Добавить операцию").performClick()
        editorTypeSwitch().assertIsDisplayed()

        // Без продвижения часов уходящий экран остаётся на месте, как во время анимации перехода.
        rule.mainClock.autoAdvance = false
        val close = rule.onNodeWithContentDescription("Закрыть")
        close.performClick()
        close.performClick()
        rule.mainClock.autoAdvance = true

        assertOnTab("Главная")
    }

    @Test
    fun settingsButtonOpensSettingsWithoutBottomBar() {
        rule.onNodeWithContentDescription("Настройки").performClick()
        title("Настройки").assertIsDisplayed()
        rule.onNodeWithTag(BOTTOM_BAR_TAG).assertDoesNotExist()

        Espresso.pressBack()
        assertOnTab("Главная")

        rule.onNodeWithContentDescription("Настройки").performClick()
        rule.onNodeWithContentDescription("Назад").performClick()
        assertOnTab("Главная")
    }

    @Test
    fun selectedTabSurvivesActivityRecreation() {
        tab("Помощник").performClick()
        rule.activityRule.scenario.recreate()
        assertOnTab("Помощник")
    }

    @Test
    fun iconButtonsHaveDescriptions() {
        rule.onAllNodesWithContentDescription("Добавить операцию").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Настройки").assertCountEquals(1)
    }

    @Test
    fun clickableElementsAreAtLeast44dp() {
        rule.assertTouchTargets()
        rule.onNodeWithContentDescription("Добавить операцию").performClick()
        rule.assertTouchTargets()
        Espresso.pressBack()
        rule.onNodeWithContentDescription("Настройки").performClick()
        rule.assertTouchTargets()
    }
}
