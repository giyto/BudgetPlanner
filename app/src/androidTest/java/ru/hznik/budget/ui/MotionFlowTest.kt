package ru.hznik.budget.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.hznik.budget.TEST_ZONE
import ru.hznik.budget.TestLlmProvider
import ru.hznik.budget.TestStart
import ru.hznik.budget.domain.ai.AiSettings
import ru.hznik.budget.domain.ai.LlmResult
import ru.hznik.budget.domain.ai.TokenUsage
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.notifications.BankNotification
import ru.hznik.budget.notifications.NotificationIntake
import ru.hznik.budget.ui.assistant.ASSISTANT_INPUT_TAG
import ru.hznik.budget.ui.assistant.ASSISTANT_SEND_TAG
import ru.hznik.budget.ui.components.AmountInput
import ru.hznik.budget.ui.drafts.DRAFTS_LIST_TAG
import ru.hznik.budget.ui.home.DRAFTS_BANNER_TAG
import ru.hznik.budget.ui.home.HOME_HERO_TAG
import ru.hznik.budget.ui.stats.STATS_DONUT_TAG
import ru.hznik.budget.ui.theme.Motion
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.sin

/**
 * Анимации включены (spec ui-motion, задачи 2.2–2.5, 3.1, 3.3, 4.2, 5.1): изменения доезжают до итоговых значений, TalkBack слышит
 * только итог. Период 1–25 октября на 18 000 ₽, сегодня 13 октября: без трат «можно сегодня» 1 500 ₽.
 */
@HiltAndroidTest
class MotionFlowTest : FlowTestBase() {
    init {
        TestStart.animations = true
    }

    @Inject lateinit var intake: NotificationIntake

    @Inject lateinit var aiSettings: AiSettings

    private fun sber(text: String) = runBlocking {
        intake.accept(BankNotification("ru.sberbankmobile", "0|ru.sberbankmobile|1|null|10001", TestStart.now.atZone(TEST_ZONE).toInstant(), "СберБанк", text))
    }

    private fun heroSays(text: String) =
        rule.onAllNodes(hasTestTag(HOME_HERO_TAG) and hasContentDescription(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun purchaseRollsDigitsAndTalkBackHearsOnlyTheResult() {
        waitForHero("Можно потратить сегодня 1500 рублей")
        rule.mainClock.autoAdvance = false
        sber("Покупка 450 ₽ Пятёрочка. Карта *1234. Баланс 12 300 ₽")
        rule.waitUntil(5_000) {
            rule.mainClock.advanceTimeByFrame()
            heroSays("1050 рублей")
        }

        // Цифры ещё крутятся, а описание карточки уже итоговое, без промежуточных сумм.
        rule.mainClock.advanceTimeBy(Motion.DIGITS / 2L)
        rule.onNodeWithTag(HOME_HERO_TAG).assert(hasContentDescription("Можно потратить сегодня 1050 рублей", substring = true))

        rule.mainClock.advanceTimeBy(Motion.DIGITS.toLong() + Motion.BAR)
        rule.mainClock.autoAdvance = true
        waitForHero("Можно потратить сегодня 1050 рублей")
    }

    @Test
    fun keypadAmountChangesInstantly() {
        waitForHero("Можно потратить сегодня")
        tapAdd()
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Стереть") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.autoAdvance = false
        listOf("4", "45", "450").forEach { typed ->
            typeAmount(typed.last().toString())
            // Один кадр — и цифра уже на экране: сумма на клавиатуре не прокручивается.
            rule.mainClock.advanceTimeByFrame()
            rule.onNode(hasText(AmountInput.display(typed))).assertExists()
        }
        rule.mainClock.autoAdvance = true
    }

    private fun insertFiveCategories() {
        insert("Продукты", 3_380, date = LocalDate.of(2026, 10, 1))
        insert("Кафе и рестораны", 1_500, date = LocalDate.of(2026, 10, 2))
        insert("Транспорт", 1_100, date = LocalDate.of(2026, 10, 3))
        insert("Здоровье", 790, date = LocalDate.of(2026, 10, 4))
        insert("Другое", 1_100, date = LocalDate.of(2026, 10, 5))
    }

    private fun donutSays(text: String) =
        rule.onAllNodes(hasTestTag(STATS_DONUT_TAG) and hasContentDescription(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun statsAmountsAreShownWhileRingIsDrawing() {
        insertFiveCategories()
        waitForHero("Можно потратить сегодня")
        rule.mainClock.autoAdvance = false
        openTab("Статистика")
        rule.waitUntil(5_000) {
            rule.mainClock.advanceTimeByFrame()
            donutSays("Продукты: 43 процента")
        }
        // Кольцо ещё дорисовывается, а суммы и проценты в списке уже видны.
        rule.onNode(hasContentDescription("Продукты: 3380 рублей, 43 процента")).assertExists()
        rule.mainClock.advanceTimeBy(Motion.CHART.toLong())
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun tappingRingSliceSelectsCategory() {
        insertFiveCategories()
        waitForHero("Можно потратить сегодня")
        openTab("Статистика")
        rule.waitUntil(5_000) { donutSays("Продукты: 43 процента") }
        // Точка на кольце чуть правее верха — первая доля, «Продукты».
        rule.onNodeWithTag(STATS_DONUT_TAG).performTouchInput {
            val radius = 60.dp.toPx()
            val ring = radius - 9.dp.toPx()
            val top = (height - 2 * radius) / 2
            val angle = Math.toRadians(20.0)
            click(Offset(radius + ring * sin(angle).toFloat(), top + radius - ring * cos(angle).toFloat()))
        }
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasTestTag(STATS_DONUT_TAG) and hasStateDescription("Выбрано: Продукты, 3380 рублей, 43 процента")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun deletedRowReturnsAfterUndo() {
        insert("Кафе и рестораны", 180, hour = 10)
        insert("Продукты", 300, hour = 11)
        openTab("Операции")
        waitForRows(2)
        rows("минус 180 рублей").onFirst().performClick()
        rule.onNode(hasContentDescription("Удалить операцию") and hasClickAction()).performClick()

        // Строка плавно уходит из карточки дня, после «Отменить» плавно возвращается.
        waitForText("Операция удалена")
        waitForRows(1)
        rule.onNode(hasText("Отменить") and hasClickAction()).performClick()
        waitForRows(2)
    }

    @Test
    fun confirmedDraftCardsLeaveTheList() {
        listOf("Пятёрочка", "Магнит", "Лента").forEachIndexed { i, store ->
            insert("Другое", 100L + i * 100, hour = 9 + i, merchant = store, source = OperationSource.NOTIFICATION, draft = true)
        }
        waitForHero("Можно потратить сегодня")
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(DRAFTS_BANNER_TAG)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(DRAFTS_BANNER_TAG).performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(DRAFTS_LIST_TAG)).fetchSemanticsNodes().isNotEmpty() }
        val stores = hasText("Пятёрочка") or hasText("Магнит") or hasText("Лента")
        rule.waitUntil(5_000) { rule.onAllNodes(stores).fetchSemanticsNodes().size == 3 }

        val products = hasText("Продукты") and hasClickAction()
        rule.onAllNodes(products)[0].performClick()
        rule.onAllNodes(products)[1].performClick()
        rule.onNode(hasText("Сохранить 2 операции") and hasClickAction()).performClick()

        // Две карточки плавно исчезают, третья остаётся.
        rule.waitUntil(5_000) { rule.onAllNodes(stores).fetchSemanticsNodes().size == 1 }
    }

    @Test
    fun chatAnswerAppearsAndIndicatorGoesAway() {
        runBlocking { aiSettings.setEnabled(true) }
        val gate = CompletableDeferred<Unit>()
        TestLlmProvider.gate = gate
        TestLlmProvider.answers += LlmResult.Text("На кафе ушло 340 ₽", TokenUsage(50))
        waitForHero("Можно потратить сегодня")
        openTab("Помощник")
        waitForText("Чем помочь?")

        // Бесконечное мигание точек не даёт тесту дождаться покоя, поэтому время идёт вручную.
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(ASSISTANT_INPUT_TAG).performTextInput("Сколько ушло на кафе?")
        rule.onNodeWithTag(ASSISTANT_SEND_TAG).performClick()
        val thinking = hasContentDescription("Считаю по вашему бюджету", substring = true)
        rule.waitUntil(5_000) {
            rule.mainClock.advanceTimeByFrame()
            rule.onAllNodes(thinking).fetchSemanticsNodes().isNotEmpty()
        }
        rule.mainClock.advanceTimeBy(1_200)

        gate.complete(Unit)
        rule.waitUntil(5_000) {
            rule.mainClock.advanceTimeByFrame()
            rule.onAllNodes(hasText("На кафе ушло 340 ₽")).fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodes(thinking).fetchSemanticsNodes().isEmpty()
        }
        rule.mainClock.advanceTimeBy(Motion.ITEM.toLong())
        rule.mainClock.autoAdvance = true
    }

    @Test
    fun draftsBannerCollapsesAfterConfirmation() {
        waitForHero("Можно потратить сегодня 1500 рублей")
        sber("Покупка 450 ₽ Пятёрочка. Карта *1234. Баланс 12 300 ₽")
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(DRAFTS_BANNER_TAG)).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithTag(DRAFTS_BANNER_TAG).performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(DRAFTS_LIST_TAG)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Продукты") and hasClickAction()).performClick()
        rule.onNode(hasText("Сохранить 1 операцию") and hasClickAction()).performClick()

        // На главной плашка сворачивается и пропадает, сумма та же.
        waitForHero("Можно потратить сегодня 1050 рублей")
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(DRAFTS_BANNER_TAG)).fetchSemanticsNodes().isEmpty() }
    }
}
