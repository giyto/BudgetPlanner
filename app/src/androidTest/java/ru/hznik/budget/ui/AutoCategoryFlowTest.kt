package ru.hznik.budget.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.TEST_ZONE
import ru.hznik.budget.TestStart
import ru.hznik.budget.domain.categorize.CategoryRule
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.notifications.BankNotification
import ru.hznik.budget.domain.repository.CategoryRuleRepository
import ru.hznik.budget.notifications.NotificationIntake
import ru.hznik.budget.ui.drafts.DRAFTS_LIST_TAG
import ru.hznik.budget.ui.drafts.rememberTag
import ru.hznik.budget.ui.home.DRAFTS_BANNER_TAG
import ru.hznik.budget.ui.rules.RULES_LIST_TAG
import javax.inject.Inject

/** Правила «магазин → категория» и подсказка словаря на экранах (spec auto-categorization, drafts; задачи 4.1–4.4). */
@HiltAndroidTest
class AutoCategoryFlowTest : FlowTestBase() {
    @Inject lateinit var intake: NotificationIntake

    @Inject lateinit var rules: CategoryRuleRepository

    private var key = 1

    private fun sber(text: String) = runBlocking {
        intake.accept(BankNotification("ru.sberbankmobile", "0|ru.sberbankmobile|${key++}|null|10001", TestStart.now.atZone(TEST_ZONE).toInstant(), "СберБанк", text))
    }

    private fun bannerShown() = rule.onAllNodes(hasTestTag(DRAFTS_BANNER_TAG)).fetchSemanticsNodes().isNotEmpty()

    private fun openDrafts() {
        rule.waitUntil(5_000) { bannerShown() }
        rule.onNodeWithTag(DRAFTS_BANNER_TAG).performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(DRAFTS_LIST_TAG)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun draftId(merchantStart: String): Long = allOperations().first { it.isDraft && it.merchant!!.startsWith(merchantStart) }.id

    private fun openRules() {
        rule.onNodeWithContentDescription("Настройки").performClick()
        rule.onNode(hasText("Правила для магазинов") and hasClickAction()).performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(RULES_LIST_TAG)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun rememberedStoreSkipsDraftsNextTime() {
        sber("Покупка 450 ₽ Ozon. Карта *1234")
        openDrafts()
        rule.onNode(hasText("Кафе и рестораны") and hasClickAction()).performClick()
        rule.onNodeWithTag(rememberTag(draftId("Ozon"))).performClick()
        rule.onNode(hasText("Сохранить 1 операцию") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { !bannerShown() && allOperations().none { it.isDraft } }

        sber("Покупка 300 ₽ OZON. Карта *1234")
        rule.waitUntil(5_000) { allOperations().size == 2 }
        val second = allOperations().first { it.merchant == "OZON" }
        assertFalse(second.isDraft)
        assertEquals(category("Кафе и рестораны").id, second.categoryId)
        assertFalse(bannerShown())
    }

    @Test
    fun rememberingConfirmsOtherDraftsOfTheSameStore() {
        sber("Покупка 450 ₽ PYATEROCHKA 1234. Карта *1234")
        sber("Покупка 120 ₽ PYATEROCHKA 5678. Карта *1234")
        sber("Покупка 350 ₽ Хозмаг. Карта *1234")
        openDrafts()
        // Галочка у покупки с подсказкой словаря выбирает и показанную категорию «Продукты».
        rule.onNodeWithTag(rememberTag(draftId("PYATEROCHKA 1234"))).performClick()
        rule.onNode(hasText("Сохранить 1 операцию") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { allOperations().count { it.isDraft } == 1 }
        val products = category("Продукты").id
        assertTrue(allOperations().filter { it.merchant!!.startsWith("PYATEROCHKA") }.all { !it.isDraft && it.categoryId == products })
        assertEquals("Хозмаг", allOperations().single { it.isDraft }.merchant)
    }

    @Test
    fun dictionaryHintIsConfirmedByTappingTheSelectedChip() {
        sber("Покупка 390 ₽ YANDEX*GO. Карта *1234")
        openDrafts()
        rule.onNode(hasText("Знакомый магазин")).assertIsDisplayed()
        rule.onNode(hasText("Транспорт") and hasClickAction()).performClick()
        rule.onNode(hasText("Сохранить 1 операцию") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { allOperations().none { it.isDraft } }
        assertEquals(category("Транспорт").id, allOperations().single().categoryId)
    }

    @Test
    fun ruleCategoryIsChangedAndDeletionCanBeUndone() {
        runBlocking { rules.confirmAndRemember(emptyList(), listOf(CategoryRule(merchantKey = "ozon", kind = CategoryKind.EXPENSE, categoryId = category("Продукты").id, merchantDisplay = "Ozon"))) }
        openRules()
        rule.onNode(hasText("1 правило")).assertIsDisplayed()
        rule.onNode(hasContentDescription("Ozon: Продукты") and hasClickAction()).performClick()
        waitForText("Категория для «Ozon»")
        rule.onNode(hasText("Транспорт") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Ozon: Транспорт")).fetchSemanticsNodes().isNotEmpty() }

        rule.onNode(hasContentDescription("Удалить правило «Ozon»") and hasClickAction()).performClick()
        waitForText("Правило удалено")
        rule.onNode(hasText("Отменить") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Ozon: Транспорт")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun emptyRulesScreenExplainsWhereRulesComeFrom() {
        openRules()
        waitForText("Правил пока нет", substring = true)
    }

    @Test
    fun draftsAndRulesHaveLargeEnoughTouchTargets() {
        sber("Покупка 450 ₽ PYATEROCHKA. Карта *1234")
        openDrafts()
        rule.assertTouchTargets()
        rule.onNodeWithContentDescription("Назад").performClick()
        runBlocking { rules.confirmAndRemember(emptyList(), listOf(CategoryRule(merchantKey = "ozon", kind = CategoryKind.EXPENSE, categoryId = category("Продукты").id, merchantDisplay = "Ozon"))) }
        openRules()
        rule.assertTouchTargets()
    }
}
