package ru.hznik.budget.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Сценарии spec categories. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CategoriesFlowTest : FlowTestBase() {

    private fun openCategories() {
        rule.onNode(hasContentDescription("Настройки") and hasClickAction()).performClick()
        rule.onNode(hasText("Категории") and hasClickAction()).performClick()
    }

    private fun createExpenseCategory(name: String) {
        rule.onNode(hasText("Новая категория") and hasClickAction()).performClick()
        rule.onNode(hasSetTextAction()).performTextInput(name)
        // Клавиатура закрывает палитру внизу экрана.
        Espresso.closeSoftKeyboard()
        // На низком экране (Pixel 4) палитра может оказаться ниже края, поэтому сначала прокручиваем к элементу.
        rule.onNode(hasContentDescription("Спорт") and hasClickAction()).performScrollTo().performClick()
        rule.onNode(hasContentDescription("Синий") and hasClickAction()).performScrollTo().performClick()
        rule.onNode(hasText("Сохранить") and hasClickAction()).performClick()
    }

    private fun categoryNames(): List<String> = runBlocking { categories.observeAll().first().map { it.name } }

    @Test
    fun createOwnCategoryAndUseIt() {
        openCategories()
        createExpenseCategory("Спортзал")
        rule.waitUntil(5_000) { "Спортзал" in categoryNames() }

        val created = category("Спортзал")
        assertEquals("dumbbell", created.icon)
        assertEquals("blue", created.color)

        rule.onNode(hasContentDescription("Назад") and hasClickAction()).performClick()
        rule.onNode(hasContentDescription("Назад") and hasClickAction()).performClick()
        tapAdd()
        rule.onNode(hasText("Ещё") and hasClickAction()).performClick()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Спортзал"))
        rule.onNode(hasText("Спортзал") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun duplicateNameIsRejected() {
        openCategories()
        rule.onNode(hasText("Новая категория") and hasClickAction()).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("продукты")
        rule.onNode(hasText("Сохранить") and hasClickAction()).performClick()

        waitForText("Такая категория уже есть")
        assertEquals(1, categoryNames().count { it.equals("Продукты", ignoreCase = true) })
    }

    @Test
    fun renameIsVisibleInOperationList() {
        insert("Кафе и рестораны", 240)
        openCategories()
        rule.onNode(hasText("Кафе и рестораны") and hasClickAction()).performClick()
        rule.onNode(hasSetTextAction()).performTextClearance()
        rule.onNode(hasSetTextAction()).performTextInput("Кафе")
        rule.onNode(hasText("Сохранить") and hasClickAction()).performClick()
        waitForText("Кафе")

        rule.onNode(hasContentDescription("Назад") and hasClickAction()).performClick()
        rule.onNode(hasContentDescription("Назад") and hasClickAction()).performClick()
        openTab("Операции")
        waitForRows(1, "Кафе. ")
    }

    @Test
    fun hiddenCategoryIsNotOfferedForInput() {
        openCategories()
        rule.onNode(hasContentDescription("Скрыть «Продукты»") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { category("Продукты").hidden }

        rule.onNode(hasContentDescription("Назад") and hasClickAction()).performClick()
        rule.onNode(hasContentDescription("Назад") and hasClickAction()).performClick()
        tapAdd()
        assertTrue(rule.onAllNodes(hasText("Продукты") and hasClickAction()).fetchSemanticsNodes().isEmpty())
        rule.onNode(hasText("Ещё") and hasClickAction()).performClick()
        assertTrue(rule.onAllNodes(hasText("Продукты") and hasClickAction()).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun ownEmptyCategoryIsDeletedAndDefaultOnlyHidden() {
        openCategories()
        createExpenseCategory("Хобби")
        rule.waitUntil(5_000) { "Хобби" in categoryNames() }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Хобби"))
        rule.onNode(hasText("Хобби") and hasClickAction()).performClick()
        rule.onNode(hasContentDescription("Удалить категорию") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { "Хобби" !in categoryNames() }

        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Подписки"))
        rule.onNode(hasText("Подписки") and hasClickAction()).performClick()
        rule.onNode(hasContentDescription("Удалить категорию") and hasClickAction()).performClick()
        waitForText("Удалить нельзя")
        rule.onNode(hasText("Скрыть") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { category("Подписки").hidden }
    }
}
