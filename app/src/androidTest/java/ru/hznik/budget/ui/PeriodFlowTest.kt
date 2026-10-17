package ru.hznik.budget.ui

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.budget.TEST_TODAY
import ru.hznik.budget.TestStart
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.model.PaymentRepeat
import ru.hznik.budget.ui.format.MoneyFormat
import java.time.LocalDate

private fun rub(rubles: Long) = MoneyFormat.amount(rubles * 100)

/** Онбординг (spec onboarding, задача 9.2): приложение запускается впервые, данных нет. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest : FlowTestBase() {
    init {
        TestStart.onboarded = false
    }

    private fun next() = tapText("Дальше")

    @Test
    fun firstLaunchWithRegularIncome() {
        rule.onNodeWithText("Шаг 1 из 5").assertIsDisplayed()
        rule.onNodeWithTag(BOTTOM_BAR_TAG).assertDoesNotExist()
        next()

        rule.onNodeWithText("Шаг 2 из 5").assertIsDisplayed()
        typeAmount("18000")
        tapText("Выберите число")
        tapText("25")
        rule.onNodeWithText("Следующий доход: 25 октября").assertIsDisplayed()
        next()

        rule.onNodeWithText("Шаг 3 из 5").assertIsDisplayed()
        tapText("Готово")
        rule.onNodeWithText("Шаг 4 из 5").assertIsDisplayed()
        rule.onNodeWithText("Траты будут добавляться сами").assertIsDisplayed()
        tapText("Вводить вручную")
        rule.onNodeWithText("Шаг 5 из 5").assertIsDisplayed()
        tapText("Не сейчас")

        // 18 000 ₽ на 12 дней до 25 октября.
        waitForHero("Можно потратить сегодня 1500 рублей")
        rule.onNodeWithTag(BOTTOM_BAR_TAG).assertIsDisplayed()

        // Повторный запуск сразу на главной.
        rule.activityRule.scenario.recreate()
        waitForHero("Можно потратить сегодня 1500 рублей")
        rule.onNodeWithText("Шаг 1 из 5").assertDoesNotExist()
    }

    @Test
    fun irregularIncomeWithPaymentReservesMoney() {
        next()
        typeAmount("18000")
        chooseType("Нерегулярно")
        tapText("Выберите дату")
        rule.onNode(calendarDay(23)).performClick()
        tapText("Готово")
        rule.onNodeWithText("Следующий доход: 23 октября").assertIsDisplayed()
        next()

        tapText("Добавить платёж")
        rule.onNode(hasSetTextAction() and hasText("Название")).performTextInput("Интернет")
        rule.onNode(hasSetTextAction() and hasText("Сумма, ₽")).performTextInput("600")
        Espresso.closeSoftKeyboard()
        tapText("Добавить")
        waitForText("Интернет")
        tapText("Готово")
        tapText("Вводить вручную")
        tapText("Не сейчас")

        // (18 000 − 600) ₽ на 10 дней до 23 октября.
        waitForHero("Можно потратить сегодня 1740 рублей")
        assertEquals(listOf("Интернет"), allPayments().map { it.name })
    }

    @Test
    fun skipFinishesWithoutPayments() {
        next()
        typeAmount("5000")
        tapText("Выберите число")
        tapText("25")
        next()
        tapText("Пропустить")
        tapText("Пропустить") // шаг автоимпорта
        tapText("Не сейчас")
        waitForHero("Можно потратить сегодня 416 рублей")
        assertEquals(emptyList<Any>(), allPayments())
    }

    @Test
    fun cannotContinueWithoutIncomeDate() {
        next()
        typeAmount("5000")
        next()
        rule.onNodeWithText("Шаг 2 из 5").assertIsDisplayed()
    }

    @Test
    fun backOnFirstStepClosesApp() {
        Espresso.pressBackUnconditionally()
        rule.waitUntil(timeoutMillis = 10_000) { rule.activityRule.scenario.state == Lifecycle.State.DESTROYED }
    }

    @Test
    fun backOnSecondStepReturnsToFirst() {
        next()
        Espresso.pressBack()
        rule.onNodeWithText("Шаг 1 из 5").assertIsDisplayed()
    }
}

/**
 * Онбординг с включёнными анимациями: шаг вперёд въезжает справа, «Назад» — слева, тест дожидается конца сдвига
 * (spec ui-motion «Шаги онбординга», задача 3.2). Остальные сценарии онбординга — в [OnboardingFlowTest].
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OnboardingAnimatedFlowTest : FlowTestBase() {
    init {
        TestStart.onboarded = false
        TestStart.animations = true
    }

    @Test
    fun walkingForwardThroughAllStepsOpensHome() {
        rule.onNodeWithText("Шаг 1 из 5").assertIsDisplayed()
        walkOnboardingPastAutoImport()

        rule.onNodeWithText("Шаг 5 из 5").assertIsDisplayed()
        rule.onNodeWithText("Защитить вход?").assertIsDisplayed()
        // Сдвиг закончился: уехавшего шага «Автоимпорт» в дереве уже нет.
        rule.onNodeWithText("Траты будут добавляться сами").assertDoesNotExist()
        tapText("Не сейчас")
        waitForHero("Можно потратить сегодня 1500 рублей")
    }

    @Test
    fun backSlidesToThePreviousStep() {
        tapText("Дальше")
        rule.onNodeWithText("Шаг 2 из 5").assertIsDisplayed()
        rule.onNode(hasText("Выберите число") and hasClickAction()).assertIsDisplayed()

        Espresso.pressBack()
        rule.onNodeWithText("Шаг 1 из 5").assertIsDisplayed()
        rule.onNode(hasText("Выберите число")).assertDoesNotExist()
    }
}

/** Главная и платежи (spec budget-period и planned-payments, задачи 9.3 и 9.4): период с 1 по 25 октября на 18 000 ₽. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HomeFlowTest : FlowTestBase() {
    private val paymentDay = LocalDate.of(2026, 10, 18)

    @Test
    fun ordinaryDayShows331Of781() {
        insert("Продукты", 7_420, date = TEST_TODAY.minusDays(1))
        insert("Продукты", 450)
        addPayment("Интернет", 1_200, paymentDay)

        waitForHero("Можно потратить сегодня 331 рубль")
        hero().assert(hasContentDescription("из ${rub(781)} дневного лимита", substring = true))
        hero().assert(hasContentDescription("12 дней до дохода", substring = true))
        rule.onNodeWithText("Ближайший платёж: Интернет, 18 октября").assertIsDisplayed()
    }

    @Test
    fun overspendShowsMinus249AndTomorrowLimit() {
        insert("Продукты", 7_420, date = TEST_TODAY.minusDays(1))
        insert("Продукты", 1_030)
        addPayment("Интернет", 1_200, paymentDay)

        waitForHero("Перерасход сегодня минус 249 рублей")
        hero().assert(hasContentDescription("Завтра дневной лимит станет ${rub(759)}", substring = true))
    }

    @Test
    fun incomeRaisesTheLimitAtOnce() {
        waitForHero("Можно потратить сегодня 1500 рублей")
        insert("Стипендия", 2_400, type = OperationType.INCOME)
        waitForHero("Можно потратить сегодня 1700 рублей")
    }

    @Test
    fun dayWordFormsFollowTheNumber() {
        waitForHero("12 дней до дохода")
        changeIncomeDate(16)
        waitForHero("3 дня до дохода")
        changeIncomeDate(14)
        waitForHero("1 день до дохода")
    }

    @Test
    fun allOpensOperationsTab() {
        insert("Продукты", 100)
        insert("Кафе и рестораны", 200)
        insert("Транспорт", 300)
        // У строк операций описание «минус … рублей», у карточки периода — без «минус».
        waitForRows(2, containing = "минус")
        rule.onNode(hasText("Все") and hasClickAction()).performScrollTo().performClick()
        rule.onNode(hasText("Операции") and isSelectable()).assertIsSelected()
        waitForRows(3, containing = "минус")
    }

    @Test
    fun markingPaidKeepsCanSpendAndMovesMonthlyPayment() {
        addPayment("Интернет", 1_200, paymentDay, PaymentRepeat.MONTHLY)
        waitForHero("Можно потратить сегодня 1400 рублей")

        openPeriodFromHome()
        rule.onNode(hasContentDescription("Отметить «Интернет» оплаченным")).performScrollTo().performClick()
        waitForText("Оплачен")
        waitForText("18 ноября", substring = true)
        Espresso.pressBack()

        // Трата 1 200 ₽ записана, но «можно сегодня» то же: деньги на платёж были отложены.
        waitForHero("Можно потратить сегодня 1400 рублей")
        assertEquals(1_200_00L, allOperations().single().amount.kopecks)
        val next = allPayments().single { !it.isPaid }
        assertEquals(LocalDate.of(2026, 11, 18), next.dueDate)
    }

    @Test
    fun deletingPaymentExpenseMakesItUnpaidAgain() {
        addPayment("Курсы", 3_000, paymentDay)
        openPeriodFromHome()
        rule.onNode(hasContentDescription("Отметить «Курсы» оплаченным")).performScrollTo().performClick()
        waitForText("Оплачен")
        val expense = allOperations().single()
        assertNotNull(allPayments().single().paidOperationId)

        runBlocking { operations.delete(expense.id) }
        rule.waitUntil(5_000) { allPayments().single().paidOperationId == null }
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Отметить «Курсы» оплаченным")).fetchSemanticsNodes().isNotEmpty() }
        assertNull(allPayments().single().paidOn)
    }

    @Test
    fun paymentAddedFromPeriodScreenGoesToReserve() {
        openPeriodFromHome()
        tapText("Добавить платёж")
        rule.onNode(hasSetTextAction() and hasText("Название")).performTextInput("Связь")
        rule.onNode(hasSetTextAction() and hasText("Сумма, ₽")).performTextInput("600")
        Espresso.closeSoftKeyboard()
        tapText("Сохранить")
        waitForText("Связь")
        Espresso.pressBack()
        // (18 000 − 600) ₽ на 12 дней.
        waitForHero("Можно потратить сегодня 1450 рублей")
    }

    private fun changeIncomeDate(day: Int) {
        openPeriodFromHome()
        tapText("Дата дохода")
        rule.onNode(calendarDay(day)).performClick()
        tapText("Готово")
        waitForText("$day октября")
        Espresso.pressBack()
    }
}

/** День дохода и новый период (spec budget-period, задача 9.5): доход приходит 13 октября, то есть сегодня. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NewPeriodFlowTest : FlowTestBase() {
    init {
        TestStart.nextIncome = TEST_TODAY
    }

    @Test
    fun incomeDayStartsNewPeriodWithSuggestedRemainder() {
        insert("Продукты", 3_000, date = LocalDate.of(2026, 10, 5))
        insert("Стипендия", 2_000, type = OperationType.INCOME)
        waitForText("Наступил день дохода")
        rule.onNode(hasText("Начать новый период") and hasClickAction()).performScrollTo().performClick()

        // Остаток до сегодняшнего дня: 18 000 − 3 000; доход этого дня добавится сам.
        rule.onNode(hasContentDescription("15000 рублей")).assertIsDisplayed()
        rule.onNodeWithText("Доход ${rub(2_000)}, записанный сегодня", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Следующий доход: 13 ноября").assertIsDisplayed()
        rule.onNodeWithText("Бюджет нового периода: ${rub(17_000)}").assertIsDisplayed()
        tapText("Начать период")

        // 17 000 ₽ на 31 день до 13 ноября.
        waitForHero("Можно потратить сегодня 548 рублей")
        hero().assert(hasContentDescription("31 день до дохода", substring = true))
        rule.onNodeWithText("Наступил день дохода").assertDoesNotExist()
    }
}
