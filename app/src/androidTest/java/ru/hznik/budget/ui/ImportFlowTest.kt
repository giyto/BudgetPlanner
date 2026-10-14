package ru.hznik.budget.ui

import android.app.Notification
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import ru.hznik.budget.TEST_ZONE
import ru.hznik.budget.TestStart
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.notifications.BankNotification
import ru.hznik.budget.notifications.NotificationIntake
import ru.hznik.budget.notifications.toBankNotification
import ru.hznik.budget.ui.drafts.DRAFTS_LIST_TAG
import ru.hznik.budget.ui.home.DRAFTS_BANNER_TAG
import ru.hznik.budget.ui.home.INCOME_SUGGESTION_TAG
import javax.inject.Inject

/**
 * Уведомление Сбера → черновик → «можно сегодня» → подтверждение (spec notification-import, drafts, задачи 7.4 и 8.5).
 * Период 1–25 октября на 18 000 ₽, сегодня 13 октября: без трат «можно сегодня» 1 500 ₽.
 */
@HiltAndroidTest
class ImportFlowTest : FlowTestBase() {
    @Inject lateinit var intake: NotificationIntake

    private fun sber(text: String, key: String = "0|ru.sberbankmobile|1|null|10001") = runBlocking {
        intake.accept(BankNotification("ru.sberbankmobile", key, TestStart.now.atZone(TEST_ZONE).toInstant(), "СберБанк", text))
    }

    @Test
    fun purchaseBecomesDraftLowersLimitAndIsConfirmed() {
        waitForHero("Можно потратить сегодня 1500 рублей")
        sber("Покупка 450 ₽ Пятёрочка. Карта *1234. Баланс 12 300 ₽")

        waitForHero("Можно потратить сегодня 1050 рублей")
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(DRAFTS_BANNER_TAG)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasContentDescription("1 операция ждёт категорию", substring = true)).assertIsDisplayed()
        val op = allOperations().single()
        assertEquals(OperationSource.NOTIFICATION, op.source)
        assertEquals("Пятёрочка", op.merchant)

        rule.onNodeWithTag(DRAFTS_BANNER_TAG).performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(DRAFTS_LIST_TAG)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Пятёрочка")).assertIsDisplayed()
        rule.onNode(hasText("Продукты") and hasClickAction()).performClick()
        rule.onNode(hasText("Сохранить 1 операцию") and hasClickAction()).performClick()

        // Черновиков не осталось — экран закрылся, плашки нет, трата в «Продуктах».
        waitForHero("Можно потратить сегодня 1050 рублей")
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(DRAFTS_BANNER_TAG)).fetchSemanticsNodes().isEmpty() }
        assertEquals(category("Продукты").id, allOperations().single().categoryId)
        assertFalse(allOperations().single().isDraft)
    }

    /** Настоящее уведомление Android от имени пакета: тот же код, что выполняет сервис при доставке. */
    private fun statusBar(pkg: String, title: String?, bigText: String?, lines: List<String>? = null, groupSummary: Boolean = false): StatusBarNotification {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val builder = Notification.Builder(context, "test").setSmallIcon(android.R.drawable.ic_dialog_info)
        title?.let { builder.setContentTitle(it) }
        bigText?.let { builder.setContentText(it).setStyle(Notification.BigTextStyle().bigText(it)) }
        lines?.let { list -> builder.setStyle(Notification.InboxStyle().also { style -> list.forEach(style::addLine) }) }
        if (groupSummary) builder.setGroup("g").setGroupSummary(true)
        val postTime = TestStart.now.atZone(TEST_ZONE).toInstant().toEpochMilli()
        @Suppress("DEPRECATION")
        return StatusBarNotification(pkg, pkg, 7, null, 0, 0, 0, builder.build(), Process.myUserHandle(), postTime)
    }

    @Test
    fun realSberStatusBarNotificationBecomesDraft() {
        waitForHero("Можно потратить сегодня 1500 рублей")
        val bank = statusBar("ru.sberbankmobile", "СберБанк", "Покупка 450 ₽ Пятёрочка. Карта *1234. Баланс 12 300 ₽")
            .toBankNotification()!!
        assertEquals("СберБанк / Покупка 450 ₽ Пятёрочка. Карта *1234. Баланс 12 300 ₽", bank.fullText)
        runBlocking { intake.accept(bank) }
        waitForHero("Можно потратить сегодня 1050 рублей")
        assertEquals("Пятёрочка", allOperations().single().merchant)
    }

    @Test
    fun serviceIgnoresForeignAppsAndGroupSummaries() {
        assertNull(statusBar("org.telegram.messenger", "Олег", "Покупка 450 ₽ Пятёрочка").toBankNotification())
        assertNull(statusBar("ru.sberbankmobile", "СберБанк", "2 новых уведомления", groupSummary = true).toBankNotification())
        val inbox = statusBar("ru.sberbankmobile", "СберБанк", null, lines = listOf("Покупка 99 ₽ Метро", "Баланс 1 000 ₽")).toBankNotification()!!
        assertEquals("СберБанк / Покупка 99 ₽ Метро / Баланс 1 000 ₽", inbox.fullText)
    }

    private fun alfa(text: String, key: String = "0|ru.alfabank.mobile.android|1|null|10001", title: String = "Уведомление") = runBlocking {
        intake.accept(BankNotification("ru.alfabank.mobile.android", key, TestStart.now.atZone(TEST_ZONE).toInstant(), title, text))
    }

    @Test
    fun ownTransferPairDoesNotChangeTheBudget() {
        waitForHero("Можно потратить сегодня 1500 рублей")
        sber("Перевод 5 000 ₽ Иван П. Баланс 3 000 ₽")
        alfa("Перевод на сумму 5000.00 RUR из Сбербанк от ИВАН П. по СБП.")
        // Пара склеилась в перевод себе: «можно сегодня» как было, в черновиках одна карточка.
        waitForHero("Можно потратить сегодня 1500 рублей")
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(DRAFTS_BANNER_TAG)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(DRAFTS_BANNER_TAG).performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Нашлась пара: «ушло» и «пришло»")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Верно") and hasClickAction()).performClick()
        rule.onNode(hasText("Сохранить 1 операцию") and hasClickAction()).performClick()
        waitForHero("Можно потратить сегодня 1500 рублей")
        assertEquals(1, allOperations().size)
        assertEquals(ru.hznik.budget.domain.model.OperationType.OWN_TRANSFER, allOperations().single().type)
    }

    @Test
    fun oneSidedTransferMarkedAsOwnAndNameRemembered() {
        waitForHero("Можно потратить сегодня 1500 рублей")
        alfa("Перевод на сумму 10.00 RUR из Сбербанк от ИВАН П. по СБП.")
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(DRAFTS_BANNER_TAG)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(DRAFTS_BANNER_TAG).performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Это перевод себе")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Это перевод себе") and hasClickAction()).performClick()
        rule.onNode(hasText("Да, запомнить") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { allOperations().singleOrNull()?.type == ru.hznik.budget.domain.model.OperationType.OWN_TRANSFER }

        // Следующий перевод от «ИВАН П.» — сразу перевод себе, без черновика и без изменения бюджета.
        alfa("Перевод на сумму 300.00 RUR из Сбербанк от ИВАН П. по СБП.", key = "0|ru.alfabank.mobile.android|2|null|10001")
        rule.waitUntil(5_000) { allOperations().size == 2 }
        assertEquals(listOf(false, false), allOperations().map { it.isDraft })
        waitForHero("Можно потратить сегодня 1500 рублей")
    }

    @Test
    fun foreignAppNotificationChangesNothing() {
        waitForHero("Можно потратить сегодня 1500 рублей")
        runBlocking {
            intake.accept(BankNotification("org.telegram.messenger", "tg", TestStart.now.atZone(TEST_ZONE).toInstant(), "Олег", "Покупка 450 ₽ Пятёрочка"))
        }
        rule.waitForIdle()
        assertEquals(emptyList<Any>(), allOperations())
        rule.onNodeWithTag(DRAFTS_BANNER_TAG).assertDoesNotExist()
    }

    @Test
    fun duplicateDeliveryKeepsOneOperation() {
        waitForHero("Можно потратить сегодня 1500 рублей")
        sber("Покупка 450 ₽ Пятёрочка")
        sber("Покупка 450 ₽ Пятёрочка")
        sber("Покупка 450 ₽ ПЯТЁРОЧКА", key = "0|ru.sberbankmobile|2|null|10001")
        waitForHero("Можно потратить сегодня 1050 рублей")
        assertEquals(1, allOperations().size)
    }

    @Test
    fun stipendSuggestsNewPeriodAndCanBeDismissed() {
        waitForHero("Можно потратить сегодня 1500 рублей")
        sber("Зачисление стипендии 9 000 ₽. Баланс 9 450 ₽")
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(INCOME_SUGGESTION_TAG)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Пришло 9 000 ₽ — начать новый период?", substring = true)).assertIsDisplayed()
        rule.onNode(hasText("Не сейчас") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(INCOME_SUGGESTION_TAG)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun draftInOperationsListWaitsForCategory() {
        waitForHero("Можно потратить сегодня 1500 рублей")
        sber("Покупка 1 249 ₽ Ozon")
        openTab("Операции")
        rule.waitUntil(5_000) { rowCount("Ozon") == 1 }
        rule.onNode(hasContentDescription("ждёт категорию", substring = true)).assertIsDisplayed()
    }
}
