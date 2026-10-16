package ru.hznik.budget.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasClickAction
import org.junit.Assert.assertTrue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import ru.hznik.budget.MainActivity
import ru.hznik.budget.TEST_TODAY
import ru.hznik.budget.TEST_ZONE
import ru.hznik.budget.TestAuth
import ru.hznik.budget.TestStart
import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.model.PaymentRepeat
import ru.hznik.budget.domain.model.PlannedPayment
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.OperationRepository
import ru.hznik.budget.domain.repository.PlannedPaymentRepository
import ru.hznik.budget.ui.home.HOME_HERO_TAG
import ru.hznik.budget.ui.lock.DATABASE_ERROR_TAG
import ru.hznik.budget.ui.lock.KEY_LOST_TAG
import ru.hznik.budget.ui.lock.LOCK_SCREEN_TAG
import java.time.LocalDate
import javax.inject.Inject

/**
 * День в календаре Material: его подпись — полная дата на языке телефона
 * («Monday, October 12, 2026» или «понедельник, 12 октября 2026 г.»), поэтому ищем год и число.
 */
fun calendarDay(day: Int) = SemanticsMatcher("день $day, 2026") { node ->
    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text ->
        val value = text.text
        "2026" in value && Regex("""(^|\D)$day(\D|$)""").containsMatchIn(value.replace("2026", ""))
    }
}

/**
 * Ждёт первый экран: пока не известно, пройден ли онбординг, виден только фон окна,
 * а загрузку из базы Compose-тест сам не ждёт (на медленном API 29 кнопок ещё нет).
 */
fun AndroidComposeTestRule<*, *>.waitForStartScreen() = waitUntil(10_000) {
    onAllNodes(
        hasTestTag(BOTTOM_BAR_TAG) or hasText("Шаг 1 из", substring = true) or hasTestTag(LOCK_SCREEN_TAG) or hasTestTag(KEY_LOST_TAG) or hasTestTag(DATABASE_ERROR_TAG),
    ).fetchSemanticsNodes().isNotEmpty()
}

/** Все нажимаемые элементы на экране не меньше 44 dp (раздел «Дизайн» CLAUDE.md, NFR-04). */
fun AndroidComposeTestRule<*, *>.assertTouchTargets() {
    val minPx = with(density) { 44.dp.toPx() }
    val nodes = onAllNodes(hasClickAction()).fetchSemanticsNodes()
    assertTrue("Нет нажимаемых элементов", nodes.isNotEmpty())
    nodes.forEach { node ->
        val bounds = node.touchBoundsInRoot
        // Ниже края прокручиваемого экрана видимая область элемента обрезана до нуля: тогда берём его размер.
        val width = maxOf(bounds.width, node.size.width.toFloat())
        val height = maxOf(bounds.height, node.size.height.toFloat())
        assertTrue("Элемент ${node.config} меньше 44 dp: $width×$height px", width >= minPx && height >= minPx)
    }
}

/** Общее для сквозных UI-тестов: Hilt с базой в памяти, фиксированные часы и шаги по экранам. */
abstract class FlowTestBase {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var operations: OperationRepository

    @Inject lateinit var categories: CategoryRepository

    @Inject lateinit var payments: PlannedPaymentRepository

    @Before
    fun inject() {
        hiltRule.inject()
        rule.waitForStartScreen()
    }

    @After
    fun resetStart() {
        TestStart.reset()
        TestAuth.reset()
    }

    // --- Данные напрямую в базу ---

    fun category(name: String, kind: CategoryKind = CategoryKind.EXPENSE): Category =
        runBlocking { categories.observeAll().first().first { it.name == name && it.kind == kind } }

    fun insert(
        categoryName: String,
        rubles: Long,
        kopecks: Long = 0,
        type: OperationType = OperationType.EXPENSE,
        date: LocalDate = TEST_TODAY,
        hour: Int = 12,
        merchant: String? = null,
        comment: String? = null,
        source: OperationSource = OperationSource.MANUAL,
    ): Long = runBlocking {
        val kind = if (type == OperationType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
        operations.insert(
            Operation(
                type = type, amount = Money(rubles * 100 + kopecks), categoryId = category(categoryName, kind).id,
                merchant = merchant, comment = comment, source = source,
                occurredAt = date.atTime(hour, 0).atZone(TEST_ZONE).toOffsetDateTime(),
            ),
        )
    }

    fun addPayment(name: String, rubles: Long, due: LocalDate, repeat: PaymentRepeat = PaymentRepeat.ONCE): Long = runBlocking {
        payments.save(PlannedPayment(name = name, amount = Money(rubles * 100), dueDate = due, repeat = repeat, categoryId = category("Другое").id))
    }

    fun allPayments(): List<PlannedPayment> = runBlocking { payments.observeAll().first() }

    fun allOperations(): List<Operation> = runBlocking { operations.observe(ru.hznik.budget.domain.repository.OperationFilter()).first().map { it.operation } }

    // --- Шаги по экранам ---

    fun openTab(label: String) = rule.onNode(hasText(label) and isSelectable()).performClick()

    fun tapAdd() = rule.onNode(hasContentDescription("Добавить операцию") and hasClickAction()).performClick()

    fun typeAmount(digits: String) = digits.forEach { ch ->
        val label = if (ch == ',') "Запятая" else ch.toString()
        rule.onNode(hasContentDescription(label) and hasClickAction()).performClick()
    }

    fun backspace(times: Int) = repeat(times) { rule.onNode(hasContentDescription("Стереть") and hasClickAction()).performClick() }

    fun chooseType(label: String) = rule.onNode(hasText(label) and isSelectable()).performClick()

    fun chooseCategory(name: String) = rule.onNode(hasText(name) and hasClickAction()).performClick()

    fun save() = rule.onNode(hasText("Сохранить") and hasClickAction()).performClick()

    /** Строки списка операций: у каждой описание для TalkBack с суммой словами. */
    fun rows(containing: String = "рубл"): SemanticsNodeInteractionCollection =
        rule.onAllNodes(hasContentDescription(containing, substring = true) and hasClickAction())

    fun rowCount(containing: String = "рубл"): Int = rows(containing).fetchSemanticsNodes().size

    fun waitForRows(count: Int, containing: String = "рубл") = rule.waitUntil(5_000) { rowCount(containing) == count }

    fun waitForText(text: String, substring: Boolean = false, timeoutMs: Long = 5_000) =
        rule.waitUntil(timeoutMs) { rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    /** Главная карточка: всё её содержимое собрано в одно описание для TalkBack. */
    fun hero(): SemanticsNodeInteraction = rule.onNodeWithTag(HOME_HERO_TAG)

    /** Ждёт, пока главная карточка скажет [text]: расчёт приходит из базы не сразу. */
    fun waitForHero(text: String) = rule.waitUntil(5_000) {
        rule.onAllNodes(hasTestTag(HOME_HERO_TAG) and hasContentDescription(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    fun openPeriodFromHome() = rule.onNode(hasText("Период до", substring = true) and hasClickAction()).performClick()
}
