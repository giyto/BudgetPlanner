package ru.hznik.budget.ai.tools

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.hznik.budget.domain.budget.BudgetCalculator
import ru.hznik.budget.domain.budget.PeriodStatus
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.OperationFilter
import ru.hznik.budget.domain.repository.OperationRepository
import ru.hznik.budget.domain.repository.PeriodRepository
import ru.hznik.budget.domain.repository.PlannedPaymentRepository
import ru.hznik.budget.domain.repository.StatisticsRepository
import ru.hznik.budget.domain.stats.CategoryBreakdown
import ru.hznik.budget.domain.stats.SpendingComparison
import ru.hznik.budget.domain.time.today
import ru.hznik.budget.domain.usecase.ObservePeriodStatus
import ru.hznik.budget.domain.usecase.ObserveStatsScope
import ru.hznik.budget.domain.usecase.StatsMode
import java.time.Clock
import java.time.OffsetDateTime
import javax.inject.Inject
import kotlin.math.roundToLong

private fun noPeriod(): Nothing = throw ToolException("Бюджет ещё не настроен: нет текущего периода")

/** Состояние периода — те же цифры, что на главной (FR-03). */
class PeriodStatusTool @Inject constructor(private val observeStatus: ObservePeriodStatus) : AssistantTool {
    override val name = "get_period_status"
    override val description =
        "Текущий период бюджета: бюджет, резерв на обязательные платежи, потрачено, дневной лимит, сколько можно потратить сегодня, дней до дохода. Суммы в рублях."
    override val parametersSchema = NO_PARAMS

    override suspend fun run(args: JsonObject): JsonObject = statusJson(observeStatus().first() ?: noPeriod())
}

internal fun statusJson(status: PeriodStatus): JsonObject = buildJsonObject {
    put("period_start", status.period.startDate.toString())
    put("next_income_date", status.period.nextIncomeDate.toString())
    put("today", status.today.toString())
    put("days_left", status.daysLeft)
    put("budget", rubles(status.budget))
    put("reserve_for_payments", rubles(status.reserve))
    put("spent_in_period", rubles(status.spentInPeriod))
    put("left_in_period", rubles(status.left))
    put("daily_limit", rubles(status.dailyLimit))
    put("spent_today", rubles(status.spentToday))
    put("can_spend_today", rubles(status.canSpendToday))
    put("tomorrow_daily_limit", status.tomorrowLimit?.let(::rubles) ?: JsonNull)
    put("overspent_today", status.isOverspent)
}

/** Траты за интервал, всего или по одной категории: сумма и число операций. */
class SpendingTool @Inject constructor(
    private val periods: PeriodRepository,
    private val categories: CategoryRepository,
    private val stats: StatisticsRepository,
    private val operations: OperationRepository,
    private val clock: Clock,
) : AssistantTool {
    override val name = "get_spending"
    override val description = "Сколько потрачено за интервал: сумма в рублях и число операций. Можно указать категорию трат по названию."
    override val parametersSchema =
        """{"type":"object","properties":{"category":{"type":"string","description":"Название категории трат, например «Кафе и рестораны». Без него — все траты"},$RANGE_PROPERTIES}}"""

    override suspend fun run(args: JsonObject): JsonObject {
        val range = args.range(clock.today(), periods.getCurrent())
        val categoryName = args.string("category")
        val category = categoryName?.let { findExpenseCategory(it, categories) }
        val spent = if (category == null) stats.totals(range).first().spentKopecks else stats.spendingByCategory(range).first()[category.id] ?: 0
        val count = operations.observe(OperationFilter(categoryId = category?.id, dateRange = range)).first()
            .count { it.operation.type == OperationType.EXPENSE || it.operation.type == OperationType.TRANSFER }
        return buildJsonObject {
            put("category", category?.name ?: "все траты")
            put("spent", rubles(spent.coerceAtLeast(0)))
            put("operations_count", count)
        }.with(rangeJson(range))
    }
}

/** Категория трат по названию: точное совпадение без регистра или единственная, что начинается так же. */
internal suspend fun findExpenseCategory(name: String, categories: CategoryRepository) = run {
    val all = categories.observeAll().first().filter { it.kind == CategoryKind.EXPENSE }
    val key = normalizeName(name)
    all.firstOrNull { normalizeName(it.name) == key }
        ?: all.filter { normalizeName(it.name).startsWith(key) || normalizeName(it.name).contains(key) }.singleOrNull()
        ?: throw ToolException("Категории «$name» нет. Есть: " + all.joinToString(", ") { it.name })
}

/** Траты по категориям за интервал с долями — как кольцо на экране статистики. */
class SpendingByCategoryTool @Inject constructor(
    private val periods: PeriodRepository,
    private val categories: CategoryRepository,
    private val stats: StatisticsRepository,
    private val clock: Clock,
) : AssistantTool {
    override val name = "get_spending_by_category"
    override val description = "Траты по категориям за интервал: сумма в рублях и доля в процентах, от больших к меньшим."
    override val parametersSchema = """{"type":"object","properties":{$RANGE_PROPERTIES}}"""

    override suspend fun run(args: JsonObject): JsonObject {
        val range = args.range(clock.today(), periods.getCurrent())
        val breakdown = CategoryBreakdown.of(stats.spendingByCategory(range).first(), categories.observeAll().first())
        return buildJsonObject {
            put("total_spent", rubles(breakdown.totalKopecks))
            put(
                "categories",
                jsonList(
                    breakdown.all.map { slice ->
                        buildJsonObject {
                            put("category", slice.category?.name ?: "Остальное")
                            put("spent", rubles(slice.kopecks))
                            put("percent", slice.percent)
                        }
                    },
                ),
            )
        }.with(rangeJson(range))
    }
}

/** Магазины с наибольшими тратами; недоступен при «Не отправлять названия магазинов». */
class TopMerchantsTool @Inject constructor(
    private val periods: PeriodRepository,
    private val stats: StatisticsRepository,
    private val clock: Clock,
) : AssistantTool {
    override val name = "get_top_merchants"
    override val description = "Магазины и получатели с наибольшими тратами за интервал: название, сумма в рублях, число операций."
    override val parametersSchema =
        """{"type":"object","properties":{$RANGE_PROPERTIES,"limit":{"type":"integer","description":"Сколько магазинов вернуть, от 1 до 10. По умолчанию 5"}}}"""
    override val sendsMerchants = true

    override suspend fun run(args: JsonObject): JsonObject {
        val range = args.range(clock.today(), periods.getCurrent())
        val limit = (args.int("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val merchants = stats.topMerchants(range, limit).first()
        return buildJsonObject {
            put(
                "merchants",
                jsonList(
                    merchants.map {
                        buildJsonObject {
                            put("merchant", it.merchant)
                            put("spent", rubles(it.kopecks))
                            put("operations_count", it.count)
                        }
                    },
                ),
            )
        }.with(rangeJson(range))
    }

    private companion object {
        const val DEFAULT_LIMIT = 5
        const val MAX_LIMIT = 10
    }
}

/** Гипотетическая покупка сегодня: расчёт периода с ней и без неё (сценарий 5.7). Данные не меняются. */
class SimulatePurchaseTool @Inject constructor(
    private val periods: PeriodRepository,
    private val operations: OperationRepository,
    private val payments: PlannedPaymentRepository,
    private val clock: Clock,
) : AssistantTool {
    override val name = "simulate_purchase"
    override val description =
        "Что будет с бюджетом, если сегодня купить что-то на указанную сумму. Ничего не сохраняет. Поля: can_spend_today_* - сколько ещё можно потратить сегодня, отрицательное число - перерасход на эту сумму; tomorrow_daily_limit_* - лимит на каждый день начиная с завтра (null, если завтра доход); left_in_period_* - сколько останется до дня дохода. Суффиксы _before и _after - без покупки и с ней."
    override val parametersSchema =
        """{"type":"object","properties":{"amount":{"type":"number","description":"Сумма покупки в рублях"}},"required":["amount"]}"""

    override suspend fun run(args: JsonObject): JsonObject {
        val rublesAmount = args.number("amount") ?: throw ToolException("Не указана сумма покупки")
        val kopecks = (rublesAmount * 100).roundToLong()
        val money = Money.ofKopecksOrNull(kopecks) ?: throw ToolException("Сумма покупки должна быть больше нуля")
        val period = periods.getCurrent() ?: noPeriod()
        val today = clock.today()
        val ops = operations.observeFrom(period.startDate).first()
        val pays = payments.observeAll().first()
        val before = BudgetCalculator.calculate(period, ops, pays, today)
        // Категория не важна для расчёта: покупка только воображаемая и никуда не записывается.
        val purchase = Operation(type = OperationType.EXPENSE, amount = money, categoryId = 0, source = OperationSource.MANUAL, occurredAt = OffsetDateTime.now(clock))
        val after = BudgetCalculator.calculate(period, ops + purchase, pays, today)
        return buildJsonObject {
            put("purchase", rubles(kopecks))
            put("next_income_date", period.nextIncomeDate.toString())
            put("days_left", after.daysLeft)
            put("can_spend_today_before", rubles(before.canSpendToday))
            put("can_spend_today_after", rubles(after.canSpendToday))
            put("overspent_today_after", after.isOverspent)
            put("tomorrow_daily_limit_before", before.tomorrowLimit?.let(::rubles) ?: JsonNull)
            put("tomorrow_daily_limit_after", after.tomorrowLimit?.let(::rubles) ?: JsonNull)
            put("left_in_period_before", rubles(before.left))
            put("left_in_period_after", rubles(after.left))
        }
    }
}

/** Разница трат по категориям с прошлым периодом — как сравнение на экране статистики. */
class ComparePeriodsTool @Inject constructor(
    private val scope: ObserveStatsScope,
    private val categories: CategoryRepository,
    private val stats: StatisticsRepository,
) : AssistantTool {
    override val name = "compare_periods"
    override val description =
        "Сравнение трат текущего периода с тем же числом дней прошлого периода по категориям: суммы в рублях, разница и изменение в процентах."
    override val parametersSchema = NO_PARAMS

    override suspend fun run(args: JsonObject): JsonObject {
        val current = scope.forMode(StatsMode.PERIOD).first() ?: noPeriod()
        val previous = current.previousRange ?: throw ToolException("Прошлого периода ещё нет, сравнивать не с чем")
        val now = stats.spendingByCategory(current.range).first()
        val before = stats.spendingByCategory(previous).first()
        val changes = SpendingComparison.of(now, before, categories.observeAll().first(), limit = MAX_CATEGORIES)
        return buildJsonObject {
            put("current", buildJsonObject { put("from", current.range.start.toString()); put("to", current.range.endInclusive.toString()) })
            put("previous", buildJsonObject { put("from", previous.start.toString()); put("to", previous.endInclusive.toString()) })
            put("current_total", rubles(now.values.sum().coerceAtLeast(0)))
            put("previous_total", rubles(before.values.sum().coerceAtLeast(0)))
            put(
                "changes",
                jsonList(
                    changes.map {
                        buildJsonObject {
                            put("category", it.category.name)
                            put("current", rubles(it.currentKopecks))
                            put("previous", rubles(it.previousKopecks))
                            put("difference", rubles(it.diffKopecks))
                            put("percent", it.percent)
                        }
                    },
                ),
            )
        }
    }

    private companion object {
        const val MAX_CATEGORIES = 10
    }
}
