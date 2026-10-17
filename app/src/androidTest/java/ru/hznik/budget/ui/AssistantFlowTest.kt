package ru.hznik.budget.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.TestLlmProvider
import ru.hznik.budget.TestStart
import ru.hznik.budget.domain.ai.AiSettings
import ru.hznik.budget.domain.ai.LlmError
import ru.hznik.budget.domain.ai.LlmMessage
import ru.hznik.budget.domain.ai.LlmResult
import ru.hznik.budget.domain.ai.TokenUsage
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.ui.assistant.ASSISTANT_INPUT_TAG
import ru.hznik.budget.ui.assistant.ASSISTANT_SEND_TAG
import ru.hznik.budget.ui.drafts.DRAFTS_AI_SUGGEST_TAG
import ru.hznik.budget.ui.settings.ASSISTANT_HIDE_MERCHANTS_TAG
import ru.hznik.budget.ui.settings.ASSISTANT_SWITCH_TAG
import javax.inject.Inject

private fun FlowTestBase.openAssistant() = rule.onNode(hasText("Помощник") and isSelectable()).performClick()

/** Раздел «Помощник»: включение и сборка без ключа (spec ai-assistant; задача 5.1). */
@HiltAndroidTest
class AssistantIntroFlowTest : FlowTestBase() {
    @Inject lateinit var aiSettings: AiSettings

    @Test
    fun enableOpensChatWithoutKeyInput() {
        openAssistant()
        waitForText("Включить помощника")
        rule.onNodeWithText("Уходит в модель GigaChat").assertIsDisplayed()
        rule.assertTouchTargets()
        assertTrue("до включения запросов к модели нет", TestLlmProvider.calls.isEmpty())

        tapText("Включить помощника")
        waitForText("Чем помочь?")
        assertTrue(runBlocking { aiSettings.enabled.first() })
        rule.onNodeWithText("На что я больше всего трачу?").assertIsDisplayed()
        rule.assertTouchTargets()
    }
}

@HiltAndroidTest
class AssistantNotConfiguredFlowTest : FlowTestBase() {
    init {
        TestStart.aiConfigured = false
    }

    @Test
    fun buildWithoutKeyShowsUnavailable() {
        openAssistant()
        waitForText("Помощник недоступен в этой сборке")
        rule.onNode(hasText("Включить помощника") and hasClickAction()).assertDoesNotExist()
    }
}

/** Чат: вопрос, ответ с подписью, ошибки, история и «Новый диалог» (задача 5.2). */
@HiltAndroidTest
class AssistantChatFlowTest : FlowTestBase() {
    @Inject lateinit var aiSettings: AiSettings

    private fun enabled() {
        runBlocking { aiSettings.setEnabled(true) }
        openAssistant()
        waitForText("Чем помочь?")
    }

    private fun ask(text: String) {
        rule.onNodeWithTag(ASSISTANT_INPUT_TAG).performTextInput(text)
        rule.onNodeWithTag(ASSISTANT_SEND_TAG).performClick()
    }

    @Test
    fun suggestionGoesThroughToolAndShowsCaption() {
        enabled()
        TestLlmProvider.answers += LlmResult.ToolCall("simulate_purchase", """{"amount":3000}""", "s1", TokenUsage(100))
        TestLlmProvider.answers += LlmResult.Text("Хватит — после покупки останется **12 600 ₽**", TokenUsage(200))
        tapText("Хватит ли до стипендии, если куплю наушники за 3 000 ₽?")

        waitForText("Посчитал по вашему бюджету", timeoutMs = 10_000)
        waitForText("Хватит - после покупки останется 12 600 ₽")
        waitForText("Израсходовано 300 токенов")
        // Второй запрос к модели содержит результат локального расчёта, а не операции.
        val second = TestLlmProvider.calls[1]
        val result = second.last() as LlmMessage.ToolResult
        assertTrue(result.resultJson, result.resultJson.contains("can_spend_today_after"))
        rule.assertTouchTargets()
    }

    @Test
    fun noNetworkThenRetry() {
        enabled()
        TestLlmProvider.answers += LlmResult.Failure(LlmError.NO_NETWORK)
        ask("Сколько ушло на кафе?")
        waitForText("Нет соединения с интернетом", timeoutMs = 10_000)
        rule.onNodeWithText("Сколько ушло на кафе?").assertIsDisplayed()

        TestLlmProvider.answers += LlmResult.Text("На кафе ушло 340 ₽", TokenUsage(50))
        tapText("Повторить")
        waitForText("На кафе ушло 340 ₽", timeoutMs = 10_000)
        rule.onNodeWithText("Нет соединения с интернетом").assertDoesNotExist()
        assertEquals("вопрос не задублировался", 1, rule.onAllNodes(hasText("Сколько ушло на кафе?")).fetchSemanticsNodes().size)
    }

    @Test
    fun tapOutsideClosesKeyboardAndKeepsText() {
        enabled()
        val input = rule.onNodeWithTag(ASSISTANT_INPUT_TAG)
        input.performClick()
        input.performTextInput("Сколько ушло")
        input.assertIsFocused()

        // Пустое место над полем: заголовок «Чем помочь?» не нажимается сам.
        rule.onNodeWithText("Чем помочь?").performClick()
        input.assertIsNotFocused()
        rule.onNodeWithText("Сколько ушло").assertIsDisplayed()
        assertTrue("нажатие вне поля ничего не отправило", TestLlmProvider.calls.isEmpty())
    }

    @Test
    fun limitExhaustedMessage() {
        enabled()
        TestLlmProvider.answers += LlmResult.Failure(LlmError.TOKENS_EXHAUSTED)
        ask("Как дожить до стипендии?")
        waitForText("Лимит помощника закончился, попробуйте позже", timeoutMs = 10_000)
    }

    @Test
    fun historySurvivesRecreateAndNewDialogClearsIt() {
        enabled()
        TestLlmProvider.answers += LlmResult.Text("Привет!", TokenUsage(5))
        ask("Привет")
        waitForText("Привет!", timeoutMs = 10_000)

        rule.activityRule.scenario.recreate()
        openAssistant()
        waitForText("Привет!")

        rule.onNode(hasContentDescription("Новый диалог") and hasClickAction()).performClick()
        tapText("Удалить и начать")
        waitForText("Чем помочь?")
        rule.onNodeWithText("Привет!").assertDoesNotExist()
    }
}

/** Раздел «ИИ-помощник» в настройках (задача 5.3). */
@HiltAndroidTest
class AssistantSettingsFlowTest : FlowTestBase() {
    @Inject lateinit var aiSettings: AiSettings

    @Test
    fun switchesInSettings() {
        runBlocking { aiSettings.setEnabled(true) }
        openSettings()
        val assistant = rule.onNodeWithTag(ASSISTANT_SWITCH_TAG)
        assistant.performScrollTo().assertIsOn()
        rule.onNodeWithText("Израсходовано на этом телефоне").performScrollTo().assertIsDisplayed()

        val hide = rule.onNodeWithTag(ASSISTANT_HIDE_MERCHANTS_TAG)
        hide.performScrollTo().assertIsOff()
        hide.performClick()
        rule.waitUntil(5_000) { runBlocking { aiSettings.hideMerchants.first() } }

        assistant.performScrollTo().performClick()
        rule.waitUntil(5_000) { runBlocking { !aiSettings.enabled.first() } }
        rule.assertTouchTargets()
    }
}

/** «Подсказать категории» на экране черновиков (spec drafts; задача 5.4). */
@HiltAndroidTest
class DraftsAiSuggestFlowTest : FlowTestBase() {
    @Inject lateinit var aiSettings: AiSettings

    private fun openDraftFromBank() {
        insert("Другое", 350, merchant = "ИП Смирнова А. В.", source = OperationSource.NOTIFICATION, draft = true)
        openDrafts()
    }

    @Test
    fun suggestionIsSavedOnlyAfterTap() {
        runBlocking { aiSettings.setEnabled(true) }
        openDraftFromBank()
        TestLlmProvider.answers += LlmResult.Text("""{"ИП Смирнова А. В.": "Продукты"}""", TokenUsage(40))
        rule.onNodeWithTag(DRAFTS_AI_SUGGEST_TAG).performClick()
        waitForText("Подсказал помощник", timeoutMs = 10_000)
        val request = (TestLlmProvider.calls.single().last() as LlmMessage.User).text
        assertFalse("сумма не уходит в модель", request.contains("350"))
        rule.onNode(hasText("Сохранить", substring = true) and hasClickAction()).assertIsNotEnabled()

        tapText("Продукты")
        rule.onNode(hasText("Сохранить 1", substring = true) and hasClickAction()).assertIsEnabled().performClick()
        rule.waitUntil(5_000) { allOperations().none { it.isDraft } }
        assertEquals("Продукты", category("Продукты").name)
        assertEquals(category("Продукты").id, allOperations().single().categoryId)
    }

    @Test
    fun noButtonWhenMerchantsHidden() {
        runBlocking {
            aiSettings.setEnabled(true)
            aiSettings.setHideMerchants(true)
        }
        openDraftFromBank()
        rule.onNodeWithTag(DRAFTS_AI_SUGGEST_TAG).assertDoesNotExist()
    }
}

/** Выключенный помощник не обращается к модели и в сеть (задача 6.2). */
@HiltAndroidTest
class AssistantOffNoRequestsFlowTest : FlowTestBase() {
    @Test
    fun noModelCallsWhenOff() {
        insert("Продукты", 450, type = OperationType.EXPENSE)
        waitForHero("Можно потратить сегодня")
        rule.onNode(hasText("Операции") and isSelectable()).performClick()
        rule.onNode(hasText("Статистика") and isSelectable()).performClick()
        openAssistant()
        waitForText("Включить помощника")
        rule.onNode(hasText("Главная") and isSelectable()).performClick()
        openSettings()
        rule.onNodeWithTag(ASSISTANT_SWITCH_TAG).performScrollTo().assertIsOff()
        rule.waitForIdle()
        assertTrue(TestLlmProvider.calls.isEmpty())
        assertTrue(rule.onAllNodes(hasTestTag(DRAFTS_AI_SUGGEST_TAG)).fetchSemanticsNodes().isEmpty())
    }
}
