package ru.hznik.budget.ui

import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import ru.hznik.budget.MainActivity
import ru.hznik.budget.TEST_TODAY
import ru.hznik.budget.TEST_ZONE
import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.OperationRepository
import java.time.LocalDate
import javax.inject.Inject

/** Общее для сквозных UI-тестов: Hilt с базой в памяти, фиксированные часы и шаги по экранам. */
abstract class FlowTestBase {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var operations: OperationRepository

    @Inject lateinit var categories: CategoryRepository

    @Before
    fun inject() = hiltRule.inject()

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

    fun waitForText(text: String) = rule.waitUntil(5_000) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
}
