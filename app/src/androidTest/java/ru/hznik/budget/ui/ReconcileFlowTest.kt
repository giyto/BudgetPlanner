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
import androidx.compose.ui.semantics.SemanticsProperties
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.TEST_TODAY
import ru.hznik.budget.TEST_ZONE
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.reconcile.Mismatch
import ru.hznik.budget.domain.reconcile.ReconAccount
import ru.hznik.budget.domain.reconcile.ReconState
import ru.hznik.budget.domain.repository.ReconciliationRepository
import ru.hznik.budget.ui.home.HOME_HERO_TAG
import ru.hznik.budget.ui.reconcile.AUTO_RECONCILE_SWITCH_TAG
import ru.hznik.budget.ui.reconcile.MISMATCH_BANNER_TAG
import ru.hznik.budget.ui.reconcile.PERIOD_RECONCILE_BUTTON_TAG
import ru.hznik.budget.ui.reconcile.RECONCILE_APPLY_TAG
import ru.hznik.budget.ui.reconcile.RECONCILE_MANUAL_SUBMIT_TAG
import java.time.Instant
import javax.inject.Inject

/** Сверка с банком на экранах (spec balance-reconciliation, home-screen, operations, budget-period; задачи 3.1–3.8). */
@HiltAndroidTest
class ReconcileFlowTest : FlowTestBase() {
    @Inject lateinit var reconciliation: ReconciliationRepository

    private val alfa = ReconAccount("alfa", "1234")
    private val sber = ReconAccount("sber", "5678")
    private val anchorAt: Instant = TEST_TODAY.atTime(9, 0).atZone(TEST_ZONE).toInstant()
    private val balanceAt: Instant = TEST_TODAY.atTime(11, 0).atZone(TEST_ZONE).toInstant()

    private fun mismatch(account: ReconAccount, bankRub: Long, expectedRub: Long) = runBlocking {
        reconciliation.save(ReconState(account, expectedRub * 100, anchorAt, Mismatch(bankRub * 100, expectedRub * 100, balanceAt), lastReconciledAt = balanceAt))
    }

    private fun matched(account: ReconAccount, rub: Long) = runBlocking {
        reconciliation.save(ReconState(account, rub * 100, balanceAt, lastReconciledAt = balanceAt))
    }

    private fun banner() = rule.onNodeWithTag(MISMATCH_BANNER_TAG)

    private fun waitForBanner(text: String) = rule.waitUntil(5_000) {
        rule.onAllNodes(hasTestTag(MISMATCH_BANNER_TAG) and hasContentDescription(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    /** «Можно потратить сегодня» в рублях из описания главной карточки. */
    private fun canSpendToday(): Long {
        val description = rule.onNodeWithTag(HOME_HERO_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        return Regex("""Можно потратить сегодня (-?\d+) рубл""").find(description)!!.groupValues[1].toLong()
    }

    /** Строки сверки и чип фильтра собраны в одно описание для TalkBack, текста в них для поиска нет. */
    private fun waitForDescription(text: String) = rule.waitUntil(5_000) {
        rule.onAllNodes(hasContentDescription(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun bannerShown() = rule.onAllNodes(hasTestTag(MISMATCH_BANNER_TAG)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun singleCardMismatchIsFixedWithAnAdjustment() {
        mismatch(alfa, bankRub = 12_300, expectedRub = 12_750)
        waitForBanner("Расхождение с банком 450 рублей")
        banner().assertIsDisplayed()
        waitForHero("Можно потратить сегодня")
        val before = canSpendToday()

        banner().performClick()
        waitForDescription("В банке")
        rule.onNode(hasContentDescription("Разница: минус 450 рублей")).assertIsDisplayed()
        rule.onNodeWithTag(RECONCILE_APPLY_TAG).performClick()

        waitForText("Расхождений нет")
        val adjustment = allOperations().single { it.source == OperationSource.ADJUSTMENT }
        assertEquals(OperationType.EXPENSE, adjustment.type)
        assertEquals(Money(450_00), adjustment.amount)
        assertEquals("alfa", adjustment.bankId)

        rule.onNodeWithContentDescription("Назад").performClick()
        waitForHero("Можно потратить сегодня")
        rule.waitUntil(5_000) { !bannerShown() }
        // Корректировка — обычная трата: «можно сегодня» меньше на её сумму.
        waitForHero("Можно потратить сегодня ${before - 450} рублей")
    }

    @Test
    fun twoCardsShowOneBannerAndAListWithMatchingCard() {
        mismatch(alfa, bankRub = 4_410, expectedRub = 4_710)
        mismatch(sber, bankRub = 12_000, expectedRub = 12_300)
        waitForBanner("Расхождение с банком по 2 картам")
        banner().performClick()
        waitForText("Альфа-Банк *1234")
        rule.onNode(hasText("Сбер *5678")).assertIsDisplayed()
        rule.onNode(hasText("Пропустить") and hasClickAction()).performClick()
        // Пропуск относится к первой карте, вторая остаётся с расхождением, первая — «сходится».
        waitForText("сходится")
        runBlocking {
            assertEquals(null, reconciliation.get(alfa)!!.pending)
            assertEquals(-300_00L, reconciliation.get(sber)!!.pending!!.diffKopecks)
        }
        assertTrue(allOperations().none { it.source == OperationSource.ADJUSTMENT })
    }

    @Test
    fun matchedCardIsMarkedInTheList() {
        mismatch(alfa, bankRub = 4_410, expectedRub = 4_710)
        matched(sber, 12_300)
        waitForBanner("Расхождение с банком 300 рублей")
        banner().performClick()
        waitForText("сходится")
        rule.onNode(hasText("Записать трату 300", substring = true) and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun turningAutoReconciliationOffHidesTheBannerButKeepsManual() {
        mismatch(alfa, bankRub = 12_300, expectedRub = 12_750)
        waitForBanner("Расхождение с банком 450 рублей")
        openSettings()
        rule.onNodeWithTag(AUTO_RECONCILE_SWITCH_TAG).performScrollTo().performClick()
        rule.onNodeWithContentDescription("Назад").performClick()
        rule.waitUntil(5_000) { !bannerShown() }

        openPeriodFromHome()
        rule.onNodeWithTag(PERIOD_RECONCILE_BUTTON_TAG).performScrollTo().performClick()
        waitForText("Сколько сейчас на счёте?")
    }

    @Test
    fun firstManualReconciliationFromThePeriodScreen() {
        openPeriodFromHome()
        waitForText("Сверок ещё не было")
        rule.onNodeWithTag(PERIOD_RECONCILE_BUTTON_TAG).performScrollTo().performClick()
        waitForText("Счёт без автоимпорта")
        rule.onNode(hasText("Счёт без автоимпорта")).performClick()
        typeAmount("15000")
        rule.onNodeWithTag(RECONCILE_MANUAL_SUBMIT_TAG).performClick()

        // Первая сверка счёта: экран закрывается, на экране периода — время сверки.
        waitForText("сегодня, 12:00 · Счёт без автоимпорта сходится", substring = true)
        runBlocking { assertEquals(15_000_00L, reconciliation.get(ReconAccount.MANUAL)!!.anchorKopecks) }
    }

    @Test
    fun missingPurchaseAddedFromTheFilteredListResolvesTheMismatch() {
        runBlocking {
            operations.insert(
                Operation(
                    type = OperationType.EXPENSE, amount = Money(100_00), categoryId = category("Другое").id, merchant = "OZON",
                    source = OperationSource.NOTIFICATION, occurredAt = TEST_TODAY.atTime(10, 0).atZone(TEST_ZONE).toOffsetDateTime(),
                    bankId = "alfa", cardLast4 = "1234",
                ),
            )
        }
        // Точка отсчёта 4 710, покупка 100 → ожидали 4 610, банк показал 4 310.
        mismatch(alfa, bankRub = 4_310, expectedRub = 4_610)
        runBlocking { reconciliation.save(reconciliation.get(alfa)!!.copy(anchorKopecks = 4_710_00)) }
        waitForBanner("Расхождение с банком 300 рублей")
        banner().performClick()
        waitForText("Найти пропущенную операцию")
        rule.onNode(hasText("Найти пропущенную операцию")).performClick()
        rule.onNodeWithTag(RECONCILE_APPLY_TAG).performClick()

        waitForDescription("Фильтр: Альфа-Банк *1234 с")
        waitForRows(1)
        tapAdd()
        waitForText("Альфа-Банк *1234")
        typeAmount("300")
        chooseCategory("Продукты")
        save()
        waitForRows(2)
        rule.onNodeWithContentDescription("Назад").performClick()

        waitForText("Расхождений нет")
        val added = allOperations().single { it.source == OperationSource.MANUAL }
        assertEquals("alfa", added.bankId)
        assertEquals("1234", added.cardLast4)
        assertTrue(allOperations().none { it.source == OperationSource.ADJUSTMENT })
    }

    @Test
    fun adjustmentIsShownWithItsAccountAndOnlyUnderAll() {
        mismatch(alfa, bankRub = 12_300, expectedRub = 12_750)
        waitForBanner("Расхождение с банком 450 рублей")
        banner().performClick()
        rule.onNodeWithTag(RECONCILE_APPLY_TAG).performClick()
        waitForText("Расхождений нет")
        rule.onNodeWithContentDescription("Назад").performClick()

        openTab("Операции")
        waitForRows(1, containing = "Корректировка. Другое · Альфа-Банк *1234")
        rule.onNode(hasText("Из уведомлений") and hasClickAction()).performClick()
        waitForRows(0, containing = "Корректировка")
        rule.onNode(hasText("Вручную") and hasClickAction()).performClick()
        waitForRows(0, containing = "Корректировка")
    }

    @Test
    fun reconcileScreensHaveLargeEnoughTouchTargets() {
        mismatch(alfa, bankRub = 4_410, expectedRub = 4_710)
        waitForBanner("Расхождение с банком 300 рублей")
        rule.assertTouchTargets()
        banner().performClick()
        waitForDescription("В банке")
        rule.assertTouchTargets()
        rule.onNode(hasText("Ввести остаток вручную") and hasClickAction()).performClick()
        waitForText("Сколько сейчас на счёте?")
        rule.assertTouchTargets()
    }
}
