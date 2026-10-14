package ru.hznik.budget.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.budget.TEST_TODAY
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.ui.format.MoneyFormat

/**
 * День в календаре Material: его подпись — полная дата на языке телефона
 * («Monday, October 12, 2026» или «понедельник, 12 октября 2026 г.»), поэтому ищем год и число.
 */
private fun calendarDay(day: Int) = SemanticsMatcher("день $day октября 2026") { node ->
    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text ->
        val value = text.text
        "2026" in value && Regex("""(^|\D)$day(\D|$)""").containsMatchIn(value.replace("2026", ""))
    }
}

/** Сценарии spec operations и изменений app-navigation этапа add-operations-and-categories. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OperationsFlowTest : FlowTestBase() {

    // --- Ввод (9.2) ---

    @Test
    fun quickExpenseIsSavedAsManualToday() {
        tapAdd()
        typeAmount("180")
        chooseCategory("Кафе и рестораны")
        save()

        rule.waitUntil(5_000) { allOperations().isNotEmpty() }
        val op = allOperations().single()
        assertEquals(18_000L, op.amount.kopecks)
        assertEquals(OperationSource.MANUAL, op.source)
        assertEquals(TEST_TODAY, op.localDate)

        openTab("Операции")
        waitForRows(1, "минус 180 рублей")
    }

    @Test
    fun saveNeedsAmountAndCategory() {
        tapAdd()
        val saveButton = rule.onNode(hasText("Сохранить") and hasClickAction())
        saveButton.assertIsNotEnabled()
        typeAmount("5")
        saveButton.assertIsNotEnabled()
        chooseCategory("Продукты")
        saveButton.assertIsEnabled()
        backspace(1)
        saveButton.assertIsNotEnabled()
    }

    @Test
    fun transferIsEnteredThroughTransfersCategory() {
        tapAdd()
        typeAmount("500")
        rule.onNode(hasText("Ещё") and hasClickAction()).performClick()
        rule.onNode(hasText("Переводы") and hasClickAction()).performClick()
        save()

        rule.waitUntil(5_000) { allOperations().isNotEmpty() }
        assertEquals(OperationType.TRANSFER, allOperations().single().type)
    }

    @Test
    fun refundReducesDayTotal() {
        insert("Одежда", 2000)
        openTab("Операции")
        tapAdd()
        chooseType("Возврат")
        typeAmount("1500")
        chooseCategory("Одежда")
        save()

        waitForRows(1, "плюс 1500 рублей")
        rule.onNode(hasText(MoneyFormat.dayTotal(50_000, 0))).assertIsDisplayed()
        assertEquals(OperationType.REFUND, allOperations().first { it.amount.kopecks == 150_000L }.type)
    }

    @Test
    fun yesterdayCanBeChosenButNotTomorrow() {
        openTab("Операции")
        tapAdd()
        typeAmount("100")
        chooseCategory("Продукты")
        rule.onNode(hasText("Сегодня") and hasClickAction()).performClick()
        rule.onNode(calendarDay(14)).assertIsNotEnabled()
        rule.onNode(calendarDay(12)).performClick()
        rule.onNode(hasText("Готово") and hasClickAction()).performClick()
        rule.onNode(hasText("Вчера") and hasClickAction()).assertIsDisplayed()
        save()

        waitForText("Вчера, 12 октября")
        assertEquals(TEST_TODAY.minusDays(1), allOperations().single().localDate)
    }

    // --- Редактирование и удаление (9.3) ---

    @Test
    fun editingChangesAmountWithoutNewOperation() {
        insert("Кафе и рестораны", 180)
        openTab("Операции")
        waitForRows(1)
        rows().onFirst().performClick()
        backspace(3)
        typeAmount("210")
        save()

        waitForRows(1, "минус 210 рублей")
        assertEquals(listOf(21_000L), allOperations().map { it.amount.kopecks })
    }

    @Test
    fun closingWithoutSavingKeepsOperation() {
        insert("Кафе и рестораны", 180)
        openTab("Операции")
        waitForRows(1)
        rows().onFirst().performClick()
        typeAmount("9")
        rule.onNode(hasContentDescription("Закрыть") and hasClickAction()).performClick()

        waitForRows(1, "минус 180 рублей")
        assertEquals(listOf(18_000L), allOperations().map { it.amount.kopecks })
    }

    @Test
    fun deleteAndUndo() {
        val originalId = insert("Кафе и рестораны", 180, comment = "кофе")
        openTab("Операции")
        waitForRows(1)
        rows().onFirst().performClick()
        rule.onNode(hasContentDescription("Удалить операцию") and hasClickAction()).performClick()

        waitForText("Операция удалена")
        assertEquals(0, rowCount())
        rule.onNode(hasText("Отменить") and hasClickAction()).performClick()

        waitForRows(1, "минус 180 рублей")
        assertEquals("кофе", allOperations().single().comment)
        assertEquals(originalId, allOperations().single().id)
    }

    @Test
    fun undoMessageDisappearsAndDeletionStays() {
        insert("Кафе и рестораны", 180)
        openTab("Операции")
        waitForRows(1)
        rows().onFirst().performClick()
        rule.onNode(hasContentDescription("Удалить операцию") and hasClickAction()).performClick()

        waitForText("Операция удалена")
        // Точная длительность (5 секунд, а не 4) проверяется в AppViewModelTest с виртуальным временем.
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Операция удалена")).fetchSemanticsNodes().isEmpty() }
        assertEquals(0, allOperations().size)
    }

    @Test
    fun addFromOperationsReturnsToOperations() {
        openTab("Операции")
        tapAdd()
        typeAmount("90")
        chooseCategory("Транспорт")
        save()

        waitForRows(1, "минус 90 рублей")
        rule.onNode(hasText("Операции") and isSelectable()).assertIsSelected()
    }

    // --- Список (9.4) ---

    @Test
    fun dayTitles() {
        insert("Продукты", 1, date = TEST_TODAY)
        insert("Продукты", 2, date = TEST_TODAY.minusDays(1))
        insert("Продукты", 3, date = TEST_TODAY.minusDays(2))
        openTab("Операции")

        waitForText("Сегодня, 13 октября")
        waitForText("Вчера, 12 октября")
        waitForText("Воскресенье, 11 октября")
    }

    @Test
    fun searchByCategoryNameAndComment() {
        insert("Кафе и рестораны", 240)
        insert("Продукты", 120, comment = "Зашёл в КАФЕ за водой")
        insert("Транспорт", 90)
        openTab("Операции")
        waitForRows(3)

        rule.onNode(hasSetTextAction()).performTextInput("кафе")
        waitForRows(2)
    }

    @Test
    fun searchByAmount() {
        insert("Продукты", 450)
        insert("Продукты", 450, kopecks = 90)
        insert("Продукты", 45)
        openTab("Операции")
        waitForRows(3)

        rule.onNode(hasSetTextAction()).performTextInput("450")
        waitForRows(2)
    }

    @Test
    fun filtersCombineWithSearch() {
        insert("Продукты", 1, merchant = "Пятёрочка")
        insert("Продукты", 2, merchant = "Пятёрочка", source = OperationSource.NOTIFICATION)
        insert("Кафе и рестораны", 3, merchant = "Пятёрочка кафе")
        insert("Продукты", 4, merchant = "ВкусВилл")
        openTab("Операции")
        waitForRows(4)

        rule.onNode(hasText("Вручную") and hasClickAction()).performClick()
        rule.onNode(hasText("Категория") and hasClickAction()).performClick()
        rule.onNode(hasText("Продукты") and hasClickAction()).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("пят")

        waitForRows(1, "минус 1 рубль")
    }

    @Test
    fun emptyAndNotFoundStates() {
        openTab("Операции")
        waitForText("Операций пока нет")

        insert("Продукты", 1)
        waitForRows(1)
        rule.onNode(hasSetTextAction()).performTextInput("ничего такого")
        waitForText("Ничего не найдено")
        rule.onNode(hasText("Сбросить") and hasClickAction()).performClick()
        waitForRows(1)
    }
}
