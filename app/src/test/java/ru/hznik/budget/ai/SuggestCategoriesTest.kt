package ru.hznik.budget.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.ai.tools.FakeAiSettings
import ru.hznik.budget.domain.ai.LlmError
import ru.hznik.budget.domain.ai.LlmMessage
import ru.hznik.budget.domain.ai.LlmResult
import ru.hznik.budget.domain.ai.TokenUsage
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.testing.CAFE
import ru.hznik.budget.testing.FakeCategoryRepository
import ru.hznik.budget.testing.PRODUCTS
import ru.hznik.budget.testing.SALARY

/** Подсказка категорий для черновиков (spec drafts «Подсказка категорий от помощника»; задача 4.4). */
class SuggestCategoriesTest {
    private val categories = FakeCategoryRepository()
    private val settings = FakeAiSettings()

    private val drafts = listOf(
        DraftForHint(10, "ИП Смирнова А. В.", CategoryKind.EXPENSE),
        DraftForHint(11, "KOFEMANIYA", CategoryKind.EXPENSE),
        DraftForHint(12, "Перевод от Мама М.", CategoryKind.INCOME),
        DraftForHint(13, "KOFEMANIYA", CategoryKind.EXPENSE),
    )

    @Test
    fun `suggestions are matched to existing categories of the right kind`() = runTest {
        val provider = ScriptedLlmProvider(
            LlmResult.Text(
                "Вот:\n```json\n{\"ИП Смирнова А. В.\": \"продукты\", \"KOFEMANIYA\": \"Кафе и рестораны\", \"Перевод от Мама М.\": \"Казино\"}\n```",
                TokenUsage(150),
            ),
        )
        val result = SuggestCategories(provider, categories, settings)(drafts) as CategorySuggestions.Found
        assertEquals(mapOf(10L to PRODUCTS.id, 11L to CAFE.id, 13L to CAFE.id), result.byOperation)
        assertEquals("несуществующая категория отброшена", null, result.byOperation[12])
        assertEquals(150L, settings.tokensState.value)
    }

    @Test
    fun `request has only names kinds and categories`() = runTest {
        val provider = ScriptedLlmProvider(LlmResult.Text("{}", TokenUsage.NONE))
        SuggestCategories(provider, categories, settings)(drafts)
        val (messages, tools) = provider.calls.single()
        assertTrue("без инструментов", tools.isEmpty())
        val request = (messages.last() as LlmMessage.User).text
        assertTrue(request.contains("ИП Смирнова А. В. (трата)"))
        assertTrue(request.contains("Перевод от Мама М. (доход)"))
        assertTrue(request.contains(SALARY.name))
        assertEquals("одинаковые названия один раз", 1, Regex("KOFEMANIYA").findAll(request).count())
        assertFalse("сумм в запросе нет", request.contains("₽"))
    }

    @Test
    fun `hidden merchants or disabled assistant send nothing`() = runTest {
        val provider = ScriptedLlmProvider()
        settings.hideState.value = true
        assertEquals(CategorySuggestions.Unavailable, SuggestCategories(provider, categories, settings)(drafts))
        settings.hideState.value = false
        settings.enabledState.value = false
        assertEquals(CategorySuggestions.Unavailable, SuggestCategories(provider, categories, settings)(drafts))
        assertTrue(provider.calls.isEmpty())
    }

    @Test
    fun `error and garbage answers change nothing`() = runTest {
        val failing = ScriptedLlmProvider(LlmResult.Failure(LlmError.NO_NETWORK))
        assertEquals(CategorySuggestions.Failure(LlmError.NO_NETWORK), SuggestCategories(failing, categories, settings)(drafts))
        val garbage = ScriptedLlmProvider(LlmResult.Text("Не знаю", TokenUsage(5)))
        assertEquals(CategorySuggestions.Found(emptyMap()), SuggestCategories(garbage, categories, settings)(drafts))
    }
}
