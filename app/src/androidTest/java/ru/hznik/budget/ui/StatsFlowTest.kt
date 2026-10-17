package ru.hznik.budget.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.budget.TEST_TODAY
import ru.hznik.budget.TestStart
import ru.hznik.budget.data.local.PeriodDao
import ru.hznik.budget.data.local.PeriodEntity
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.ui.stats.STATS_LIST_TAG
import java.time.LocalDate
import javax.inject.Inject

private fun FlowTestBase.openStats() = openTab("Статистика")

/** Прокручивает список статистики к узлу и ждёт его: данные приходят из базы не сразу. */
private fun FlowTestBase.scrollToStats(matcher: SemanticsMatcher) {
    rule.waitUntil(5_000) {
        runCatching { rule.onNodeWithTag(STATS_LIST_TAG).performScrollToNode(matcher) }.isSuccess
    }
}

private fun described(text: String) = hasContentDescription(text, substring = true)

/** Статистика текущего периода (spec statistics, задачи 8.1 и 8.4): период с 1 октября, сегодня 13 октября. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class StatsFlowTest : FlowTestBase() {
    @Test
    fun noSpendingShowsPlaceholder() {
        openStats()
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Трат пока нет")).fetchSemanticsNodes().isNotEmpty() }
        scrollToStats(hasText("Сравнение появится после первого завершённого периода"))
    }

    @Test
    fun moreThanFiveCategoriesShowRest() {
        listOf("Продукты", "Кафе и рестораны", "Транспорт", "Связь и интернет", "Развлечения", "Одежда", "Здоровье")
            .forEachIndexed { i, name -> insert(name, 700L - i * 100) }
        openStats()
        scrollToStats(described("Остальное: 22 процента, 600 рублей"))
        // В списке сначала пять категорий, «Показать все (7)» открывает остальные. Кнопка стоит сразу под списком
        // в той же карточке, поэтому, когда она видна, пять строк на месте, а шестой и седьмой нет совсем, а не только на экране.
        scrollToStats(hasText("Показать все (7)"))
        listOf("Продукты: 700", "Кафе и рестораны: 600", "Транспорт: 500", "Связь и интернет: 400", "Развлечения: 300").forEach { row ->
            rule.onAllNodes(described("$row рублей") and hasClickAction()).assertCountEquals(1)
        }
        rule.onAllNodes(described("Одежда: 200 рублей")).assertCountEquals(0)
        rule.onAllNodes(described("Здоровье: 100 рублей")).assertCountEquals(0)
        rule.onNode(hasText("Показать все (7)")).performClick()
        scrollToStats(described("Здоровье: 100 рублей, 4 процента"))
    }

    @Test
    fun categoryRowOpensItsOperationsForTheSameDays() {
        insert("Кафе и рестораны", 340)
        insert("Кафе и рестораны", 890, date = LocalDate.of(2026, 10, 9))
        insert("Кафе и рестораны", 500, date = LocalDate.of(2026, 9, 30))
        insert("Продукты", 1_000)
        openStats()
        val row = described("Кафе и рестораны: 1230 рублей") and hasClickAction()
        scrollToStats(row)
        rule.onNode(row).performClick()

        // Операции категории только за дни периода: трата 30 сентября в него не входит.
        rule.waitUntil(5_000) { rule.onAllNodes(described("Фильтр: Кафе и рестораны, с 1 октября по 13 октября") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(BOTTOM_BAR_TAG).assertDoesNotExist()
        rule.waitUntil(5_000) { rule.onAllNodes(described("2 операции, траты 1230 рублей")).fetchSemanticsNodes().isNotEmpty() }

        Espresso.pressBack()
        scrollToStats(row)
    }

    @Test
    fun monthModeCountsFromTheFirstDay() {
        insert("Продукты", 300, date = LocalDate.of(2026, 9, 30))
        insert("Кафе и рестораны", 200, date = LocalDate.of(2026, 10, 2))
        openStats()
        chooseType("Месяц")
        scrollToStats(described("Траты с 1 октября по 13 октября: 200 рублей"))
        scrollToStats(hasText("По сравнению с прошлым месяцем"))
    }

    @Test
    fun dayChartDescribesEachDayAgainstItsLimit() {
        insert("Продукты", 7_420, date = TEST_TODAY.minusDays(1))
        insert("Продукты", 450)
        openStats()
        // 12 октября лимит 18 000 / 13 = 1 384 ₽, 13 октября — (18 000 − 7 420) / 12 = 881 ₽.
        scrollToStats(described("12 октября: 7420 рублей, больше лимита 1384 рубля"))
        scrollToStats(described("13 октября: 450 рублей, в пределах лимита 881 рубль"))
        scrollToStats(described("с 1 октября по 11 октября трат нет"))
    }
}

/**
 * Сравнение и итоги периодов (задачи 7.2, 8.2, 8.3): текущий период с 25 сентября, прошлый — с 25 августа по 24 сентября
 * на 18 000 ₽.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class StatsHistoryFlowTest : FlowTestBase() {
    init {
        TestStart.periodStart = LocalDate.of(2026, 9, 25)
    }

    @Inject lateinit var periodDao: PeriodDao

    private fun pastPeriod(start: LocalDate, end: LocalDate, rubles: Long) = runBlocking {
        periodDao.insert(PeriodEntity(startDate = start.toEpochDay(), nextIncomeDate = end.toEpochDay(), endDate = end.toEpochDay(), startAmountKopecks = rubles * 100))
    }

    @Test
    fun fiveCategoriesShowPercentsAndComparison() {
        pastPeriod(LocalDate.of(2026, 8, 25), LocalDate.of(2026, 9, 25), 18_000)
        insert("Кафе и рестораны", 1_100, date = LocalDate.of(2026, 8, 30))
        insert("Транспорт", 1_250, date = LocalDate.of(2026, 9, 1))
        // Остальные категории в те же первые дни прошлого периода — без изменений, в сравнение не попадают.
        insert("Продукты", 3_380, date = LocalDate.of(2026, 8, 26))
        insert("Здоровье", 790, date = LocalDate.of(2026, 8, 27))
        insert("Другое", 1_100, date = LocalDate.of(2026, 8, 28))
        insert("Продукты", 3_380, date = LocalDate.of(2026, 10, 1))
        insert("Кафе и рестораны", 1_500, date = LocalDate.of(2026, 10, 2))
        insert("Транспорт", 1_100, date = LocalDate.of(2026, 10, 3))
        insert("Здоровье", 790, date = LocalDate.of(2026, 10, 4))
        insert("Другое", 1_100, date = LocalDate.of(2026, 10, 5))
        openStats()

        scrollToStats(described("Траты с 25 сентября по 13 октября: 7870 рублей. Продукты: 43 процента"))
        scrollToStats(described("Кафе и рестораны: на 400 рублей больше, 36 процентов"))
        scrollToStats(described("Транспорт: на 150 рублей меньше, 12 процентов"))
    }

    @Test
    fun pastPeriodTotalsOpenItsStatisticsAndChangeAfterDeleting() {
        pastPeriod(LocalDate.of(2026, 8, 25), LocalDate.of(2026, 9, 25), 18_000)
        insert("Зарплата", 2_000, type = OperationType.INCOME, date = LocalDate.of(2026, 9, 1))
        insert("Продукты", 18_000, date = LocalDate.of(2026, 9, 10))
        val old = insert("Кафе и рестораны", 400, date = LocalDate.of(2026, 9, 20))
        openStats()

        val row = described("Период с 25 августа по 24 сентября: бюджет 20000 рублей, потрачено 18400 рублей, осталось 1600 рублей")
        scrollToStats(row)

        runBlocking { operations.delete(old) }
        scrollToStats(described("потрачено 18000 рублей, осталось 2000 рублей"))

        rule.onNode(described("Период с 25 августа по 24 сентября") and hasClickAction()).performClick()
        waitForText("25 авг – 24 сен")
        rule.onNodeWithTag(BOTTOM_BAR_TAG).assertDoesNotExist()
        scrollToStats(described("Траты с 25 августа по 24 сентября: 18000 рублей"))

        Espresso.pressBack()
        scrollToStats(described("Период с 25 августа по 24 сентября"))
        rule.onNodeWithTag(BOTTOM_BAR_TAG).assertIsDisplayed()
    }

    @Test
    fun overspentPeriodIsMarked() {
        pastPeriod(LocalDate.of(2026, 8, 25), LocalDate.of(2026, 9, 25), 15_000)
        insert("Продукты", 15_600, date = LocalDate.of(2026, 9, 10))
        openStats()
        scrollToStats(described("потрачено 15600 рублей, перерасход 600 рублей"))
    }
}
