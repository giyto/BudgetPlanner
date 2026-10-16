package ru.hznik.budget.ai.tools

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.domain.ai.AiSettings
import ru.hznik.budget.domain.budget.BudgetCalculator
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.model.PaymentRepeat
import ru.hznik.budget.domain.model.Period
import ru.hznik.budget.domain.model.PlannedPayment
import ru.hznik.budget.domain.usecase.ObservePeriodStatus
import ru.hznik.budget.domain.usecase.ObserveStatsScope
import ru.hznik.budget.domain.usecase.TodayTicker
import ru.hznik.budget.testing.CAFE
import ru.hznik.budget.testing.FakeCategoryRepository
import ru.hznik.budget.testing.FakeOperationRepository
import ru.hznik.budget.testing.FakePeriodRepository
import ru.hznik.budget.testing.FakePlannedPaymentRepository
import ru.hznik.budget.testing.FakeStatisticsRepository
import ru.hznik.budget.testing.PRODUCTS
import ru.hznik.budget.testing.TEST_CLOCK
import ru.hznik.budget.testing.TODAY
import ru.hznik.budget.testing.TRANSPORT
import ru.hznik.budget.testing.at
import java.time.LocalDate

class FakeAiSettings(enabled: Boolean = true, hideMerchants: Boolean = false) : AiSettings {
    val enabledState = MutableStateFlow(enabled)
    val hideState = MutableStateFlow(hideMerchants)
    val tokensState = MutableStateFlow(0L)

    override val enabled: Flow<Boolean> = enabledState
    override val hideMerchants: Flow<Boolean> = hideState
    override val tokensUsed: Flow<Long> = tokensState

    override suspend fun setEnabled(enabled: Boolean) {
        enabledState.value = enabled
    }

    override suspend fun setHideMerchants(hide: Boolean) {
        hideState.value = hide
    }

    override suspend fun addTokens(tokens: Long) {
        if (tokens > 0) tokensState.value += tokens
    }
}

/** Инструменты помощника на фейковых данных (spec ai-assistant «Локальные инструменты»; задачи 4.1–4.2). */
class AssistantToolsTest {
    private val categories = FakeCategoryRepository()
    private val operations = FakeOperationRepository(categories)
    private val payments = FakePlannedPaymentRepository(operations)
    private val periods = FakePeriodRepository(payments)
    private val stats = FakeStatisticsRepository(operations)
    private val ticker = TodayTicker(TEST_CLOCK)
    private val current = Period(id = 2, startDate = LocalDate.of(2026, 10, 1), nextIncomeDate = LocalDate.of(2026, 10, 25), startAmountKopecks = 18_000_00)
    private val settings = FakeAiSettings()

    private val statusTool = PeriodStatusTool(ObservePeriodStatus(periods, operations, payments, ticker))
    private val spendingTool = SpendingTool(periods, categories, stats, operations, TEST_CLOCK)
    private val byCategoryTool = SpendingByCategoryTool(periods, categories, stats, TEST_CLOCK)
    private val merchantsTool = TopMerchantsTool(periods, stats, TEST_CLOCK)
    private val simulateTool = SimulatePurchaseTool(periods, operations, payments, TEST_CLOCK)
    private val compareTool = ComparePeriodsTool(ObserveStatsScope(periods, ticker), categories, stats)
    private val registry = ToolRegistry(setOf(statusTool, spendingTool, byCategoryTool, merchantsTool, simulateTool, compareTool), settings)

    private fun op(categoryId: Long, rubles: Long, date: LocalDate, merchant: String? = null, draft: Boolean = false, comment: String? = null) = Operation(
        type = OperationType.EXPENSE, amount = Money(rubles * 100), categoryId = categoryId, merchant = merchant, comment = comment,
        source = OperationSource.NOTIFICATION, occurredAt = at(date), isDraft = draft, bankId = "alfa", cardLast4 = "5678",
    )

    private suspend fun setUp(withPrevious: Boolean = false) {
        periods.state.value = buildList {
            if (withPrevious) add(Period(id = 1, startDate = LocalDate.of(2026, 9, 1), nextIncomeDate = LocalDate.of(2026, 10, 1), endDate = LocalDate.of(2026, 10, 1), startAmountKopecks = 15_000_00))
            add(current)
        }
        if (withPrevious) operations.insert(op(CAFE.id, 500, LocalDate.of(2026, 9, 5), merchant = "KOFEMANIYA"))
        operations.insert(op(PRODUCTS.id, 1_000, LocalDate.of(2026, 10, 5), merchant = "ПЯТЁРОЧКА", comment = "секретный комментарий"))
        operations.insert(op(TRANSPORT.id, 70, LocalDate.of(2026, 10, 10)))
        operations.insert(op(CAFE.id, 340, TODAY, merchant = "KOFEMANIYA", draft = true))
        payments.save(PlannedPayment(name = "Интернет", amount = Money(650_00), dueDate = LocalDate.of(2026, 10, 20), repeat = PaymentRepeat.MONTHLY, categoryId = PRODUCTS.id))
    }

    private fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> if (v is Number) put(k, v) else put(k, v.toString()) }
    }

    private fun JsonObject.rub(key: String) = this[key]!!.jsonPrimitive.double

    /** Все доступные инструменты подряд — что вообще может уйти в модель. */
    private suspend fun runAll(): String = registry.available()
        .map { tool -> registry.run(tool.name, if (tool.name == "simulate_purchase") """{"amount":500}""" else "{}") }
        .joinToString(separator = " | ")

    @Test
    fun `period status matches the home screen calculation`() = runTest {
        setUp()
        val expected = BudgetCalculator.calculate(current, operations.state.value, payments.state.value, TODAY)
        val r = statusTool.run(JsonObject(emptyMap()))
        assertEquals(18_000.0, r.rub("budget"), 0.0)
        assertEquals(650.0, r.rub("reserve_for_payments"), 0.0)
        assertEquals(1_410.0, r.rub("spent_in_period"), 0.0)
        assertEquals(expected.dailyLimit / 100.0, r.rub("daily_limit"), 0.0)
        assertEquals(expected.canSpendToday / 100.0, r.rub("can_spend_today"), 0.0)
        assertEquals(12, r["days_left"]!!.jsonPrimitive.int)
        assertEquals("2026-10-25", r["next_income_date"]!!.jsonPrimitive.content)
    }

    @Test
    fun `spending by category name counts drafts and operations`() = runTest {
        setUp()
        val cafe = spendingTool.run(args("category" to "кафе"))
        assertEquals("Кафе и рестораны", cafe["category"]!!.jsonPrimitive.content)
        assertEquals(340.0, cafe.rub("spent"), 0.0)
        assertEquals(1, cafe["operations_count"]!!.jsonPrimitive.int)
        val all = spendingTool.run(JsonObject(emptyMap()))
        assertEquals(1_410.0, all.rub("spent"), 0.0)
        assertEquals(3, all["operations_count"]!!.jsonPrimitive.int)
        assertEquals("2026-10-01", all["range"]!!.jsonObject["from"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown category and bad date are errors with hints`() = runTest {
        setUp()
        val unknown = Json.parseToJsonElement(registry.run("get_spending", """{"category":"Казино"}""")).jsonObject
        val message = unknown["error"]!!.jsonPrimitive.content
        assertTrue(message, message.contains("Казино") && message.contains("Продукты"))
        val badDate = Json.parseToJsonElement(registry.run("get_spending", """{"from":"13.10.2026"}""")).jsonObject
        assertTrue(badDate["error"]!!.jsonPrimitive.content.contains("ГГГГ-ММ-ДД"))
        assertEquals(3, operations.state.value.size)
    }

    @Test
    fun `spending by category is sorted with percents`() = runTest {
        setUp()
        val r = byCategoryTool.run(JsonObject(emptyMap()))
        assertEquals(1_410.0, r.rub("total_spent"), 0.0)
        val first = r["categories"]!!.jsonArray.first().jsonObject
        assertEquals("Продукты", first["category"]!!.jsonPrimitive.content)
        assertEquals(71, first["percent"]!!.jsonPrimitive.int)
    }

    @Test
    fun `simulate purchase changes only the calculation`() = runTest {
        setUp()
        val r = simulateTool.run(args("amount" to 3_000))
        assertEquals(r.rub("can_spend_today_before") - 3_000, r.rub("can_spend_today_after"), 0.001)
        assertEquals(r.rub("left_in_period_before") - 3_000, r.rub("left_in_period_after"), 0.001)
        assertEquals(true, r["overspent_today_after"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(r.rub("tomorrow_daily_limit_after") < r.rub("tomorrow_daily_limit_before"))
        assertEquals("воображаемая покупка не записана", 3, operations.state.value.size)
        val zero = Json.parseToJsonElement(registry.run("simulate_purchase", """{"amount":0}""")).jsonObject
        assertTrue(zero.containsKey("error"))
    }

    @Test
    fun `compare with previous period by category`() = runTest {
        setUp(withPrevious = true)
        val r = compareTool.run(JsonObject(emptyMap()))
        val changes = r["changes"]!!.jsonArray.map { it.jsonObject }
        val products = changes.first { it["category"]!!.jsonPrimitive.content == "Продукты" }
        assertEquals(1_000.0, products.rub("difference"), 0.0)
        assertTrue("новая категория без процента", products["percent"] is kotlinx.serialization.json.JsonNull)
        val cafe = changes.first { it["category"]!!.jsonPrimitive.content == "Кафе и рестораны" }
        assertEquals(-160.0, cafe.rub("difference"), 0.0)
    }

    @Test
    fun `compare without previous period is an error`() = runTest {
        setUp()
        val r = Json.parseToJsonElement(registry.run("compare_periods", "{}")).jsonObject
        assertTrue(r["error"]!!.jsonPrimitive.content.contains("Прошлого периода"))
    }

    @Test
    fun `results never contain card digits bank or comments`() = runTest {
        setUp(withPrevious = true)
        val everything = runAll()
        listOf("5678", "alfa", "секретный").forEach { assertFalse("в результатах есть «$it»", everything.contains(it)) }
        assertTrue("названия магазинов отдаёт только инструмент магазинов", everything.contains("KOFEMANIYA"))
    }

    @Test
    fun `hidden merchants remove the merchants tool and names`() = runTest {
        setUp()
        settings.hideState.value = true
        val names = registry.specs().map { it.name }
        assertFalse(names.contains("get_top_merchants"))
        assertEquals(5, names.size)
        val direct = Json.parseToJsonElement(registry.run("get_top_merchants", "{}")).jsonObject
        assertTrue("по имени тоже нельзя", direct.containsKey("error"))
        val everything = runAll()
        assertFalse(everything.contains("KOFEMANIYA"))
        assertFalse(everything.contains("ПЯТЁРОЧКА"))
    }

    @Test
    fun `merchants tool lists merchants with limit`() = runTest {
        setUp()
        val r = merchantsTool.run(args("limit" to 1))
        val list = r["merchants"]!!.jsonArray
        assertEquals(1, list.size)
        assertEquals("ПЯТЁРОЧКА", list[0].jsonObject["merchant"]!!.jsonPrimitive.content)
        assertNull(r["error"])
    }
}
