package ru.hznik.budget.domain.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.model.PaymentRepeat
import ru.hznik.budget.domain.model.Period
import ru.hznik.budget.domain.model.PlannedPayment
import ru.hznik.budget.testing.at
import ru.hznik.budget.testing.category
import java.time.LocalDate

/** Сценарии spec statistics теми же числами. */
class StatsDomainTest {
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)
    private fun rub(r: Long) = r * 100

    private val products = category(1, "Продукты")
    private val cafe = category(2, "Кафе")
    private val transport = category(3, "Транспорт")
    private val health = category(4, "Здоровье")
    private val clothes = category(5, "Одежда")
    private val other = category(10, "Другое")

    // --- Диапазоны ---

    @Test
    fun `finished period range ends the day before the next one starts`() {
        val period = Period(startDate = d(2026, 8, 25), nextIncomeDate = d(2026, 9, 25), startAmountKopecks = 0, endDate = d(2026, 9, 25))
        assertEquals(DateRange(d(2026, 8, 25), d(2026, 9, 24)), DateRange.of(period, d(2026, 10, 13)))
    }

    @Test
    fun `previous period range takes the same first days`() {
        val current = DateRange(d(2026, 9, 25), d(2026, 10, 13))
        assertEquals(19L, current.length)
        val previous = Period(startDate = d(2026, 8, 25), nextIncomeDate = d(2026, 9, 25), startAmountKopecks = 0, endDate = d(2026, 9, 25))
        assertEquals(DateRange(d(2026, 8, 25), d(2026, 9, 12)), previousPeriodRange(current, previous))
    }

    @Test
    fun `shorter previous period is taken whole`() {
        val current = DateRange(d(2026, 9, 25), d(2026, 10, 13))
        val previous = Period(startDate = d(2026, 9, 15), nextIncomeDate = d(2026, 9, 25), startAmountKopecks = 0, endDate = d(2026, 9, 25))
        assertEquals(DateRange(d(2026, 9, 15), d(2026, 9, 24)), previousPeriodRange(current, previous))
    }

    @Test
    fun `previous month takes the same days, cut by its length`() {
        assertEquals(DateRange(d(2026, 9, 1), d(2026, 9, 13)), previousMonthRange(DateRange.month(d(2026, 10, 13))))
        assertEquals(DateRange(d(2027, 2, 1), d(2027, 2, 28)), previousMonthRange(DateRange.month(d(2027, 3, 31))))
    }

    // --- Категории ---

    @Test
    fun `five categories give 43, 19, 14, 14, 10 percent`() {
        val totals = mapOf(products.id to rub(3_380), cafe.id to rub(1_500), transport.id to rub(1_100), health.id to rub(790), other.id to rub(1_100))
        val breakdown = CategoryBreakdown.of(totals, listOf(products, cafe, transport, health, other))
        assertEquals(rub(7_870), breakdown.totalKopecks)
        assertEquals(listOf("Продукты", "Кафе", "Транспорт", "Другое", "Здоровье"), breakdown.slices.map { it.category!!.name })
        assertEquals(listOf(43, 19, 14, 14, 10), breakdown.slices.map { it.percent })
    }

    @Test
    fun `more than five categories keep four and the rest`() {
        val cats = (1L..7L).map { category(it, "К$it") }
        val totals = cats.associate { it.id to rub(800 - it.id * 100) }
        val breakdown = CategoryBreakdown.of(totals, cats)
        assertEquals(5, breakdown.slices.size)
        assertNull(breakdown.slices.last().category)
        assertEquals(rub(300 + 200 + 100), breakdown.slices.last().kopecks)
        assertEquals(100, breakdown.slices.sumOf { it.percent })
        // Список под кольцом — все семь категорий; доля «Остального» равна сумме долей спрятанных.
        assertEquals(7, breakdown.all.size)
        assertEquals(100, breakdown.all.sumOf { it.percent })
        assertEquals(listOf("К5", "К6", "К7"), breakdown.hidden.map { it.category!!.name })
        assertEquals(breakdown.hidden.sumOf { it.percent }, breakdown.slices.last().percent)
        assertEquals(listOf(25, 21, 18, 14, 11, 7, 4), breakdown.all.map { it.percent })
    }

    @Test
    fun `five or fewer categories have nothing hidden`() {
        val breakdown = CategoryBreakdown.of(mapOf(products.id to rub(100), cafe.id to rub(50)), listOf(products, cafe))
        assertEquals(breakdown.slices, breakdown.all)
        assertTrue(breakdown.hidden.isEmpty())
    }

    @Test
    fun `category with more refunds than spending is not shown`() {
        val breakdown = CategoryBreakdown.of(mapOf(clothes.id to rub(-500), products.id to rub(2_000)), listOf(products, clothes))
        assertEquals(listOf("Продукты"), breakdown.slices.map { it.category!!.name })
        assertEquals(listOf(100), breakdown.slices.map { it.percent })
    }

    @Test
    fun `no spending gives an empty breakdown`() {
        assertTrue(CategoryBreakdown.of(emptyMap(), listOf(products)).isEmpty)
    }

    // --- Сравнение ---

    @Test
    fun `comparison shows plus 36 and minus 12 percent`() {
        val changes = SpendingComparison.of(
            current = mapOf(cafe.id to rub(1_500), transport.id to rub(1_100), products.id to rub(900)),
            previous = mapOf(cafe.id to rub(1_100), transport.id to rub(1_250), products.id to rub(900)),
            categories = listOf(products, cafe, transport),
        )
        assertEquals(listOf("Кафе", "Транспорт"), changes.map { it.category.name })
        assertEquals(listOf(36, -12), changes.map { it.percent })
        assertEquals(listOf(rub(400), rub(-150)), changes.map { it.diffKopecks })
    }

    @Test
    fun `category absent before is new`() {
        val change = SpendingComparison.of(mapOf(clothes.id to rub(1_200)), emptyMap(), listOf(clothes)).single()
        assertNull(change.percent)
        assertTrue(change.isMore)
    }

    @Test
    fun `comparison keeps three biggest changes`() {
        val cats = (1L..5L).map { category(it, "К$it") }
        val changes = SpendingComparison.of(cats.associate { it.id to rub(it.id * 100) }, emptyMap(), cats)
        assertEquals(listOf(5L, 4L, 3L), changes.map { it.category.id })
    }

    // --- По дням ---

    private val period = Period(startDate = d(2026, 10, 1), nextIncomeDate = d(2026, 10, 25), startAmountKopecks = rub(18_000))
    private var nextId = 1L
    private fun spend(rubles: Long, date: LocalDate) = Operation(
        id = nextId++, type = OperationType.EXPENSE, amount = Money(rub(rubles)), categoryId = 1,
        source = OperationSource.MANUAL, occurredAt = at(date),
    )

    @Test
    fun `each day is compared with its own limit`() {
        val ops = listOf(spend(7_420, d(2026, 10, 12)), spend(450, d(2026, 10, 13)))
        val days = DailySpending.forPeriod(period, ops, emptyList(), DateRange(d(2026, 10, 12), d(2026, 10, 13)))
        // 12 октября: 18 000 / 13 дней = 1 384 ₽; 13 октября: (18 000 − 7 420) / 12 = 881 ₽.
        assertEquals(listOf(rub(1_384), rub(881)), days.map { it.limitKopecks })
        assertEquals(listOf(true, false), days.map { it.isOverLimit })
    }

    @Test
    fun `paying a reserved payment keeps the day within the limit`() {
        val rent = spend(10_000, d(2026, 10, 13))
        val payment = PlannedPayment(
            id = 99, name = "Аренда", amount = Money(rub(10_000)), dueDate = d(2026, 10, 20), repeat = PaymentRepeat.MONTHLY,
            categoryId = 1, paidOperationId = rent.id, paidOn = rent.localDate,
        )
        val day = DailySpending.forPeriod(period, listOf(rent), listOf(payment), DateRange(d(2026, 10, 13), d(2026, 10, 13))).single()
        assertEquals(0L, day.spentKopecks)
        assertFalse(day.isOverLimit)
    }

    @Test
    fun `month days have no limit and zero for empty days`() {
        val days = DailySpending.forRange(DateRange(d(2026, 10, 1), d(2026, 10, 3)), mapOf(d(2026, 10, 2) to rub(300)))
        assertEquals(listOf(0L, rub(300), 0L), days.map { it.spentKopecks })
        assertTrue(days.all { it.limitKopecks == null && !it.isOverLimit })
    }

    // --- Итоги ---

    @Test
    fun `period summary has budget, spent and left`() {
        val p = Period(startDate = d(2026, 8, 25), nextIncomeDate = d(2026, 9, 25), startAmountKopecks = rub(18_000), endDate = d(2026, 9, 25))
        val summary = PeriodSummary.of(p, DateRange.of(p, d(2026, 10, 13)), RangeTotals(rub(2_000), rub(18_400)))
        assertEquals(rub(20_000), summary.budgetKopecks)
        assertEquals(rub(1_600), summary.leftKopecks)
        assertFalse(summary.isOverspent)
    }

    @Test
    fun `period summary shows overspend`() {
        val p = Period(startDate = d(2026, 8, 25), nextIncomeDate = d(2026, 9, 25), startAmountKopecks = rub(15_000), endDate = d(2026, 9, 25))
        val summary = PeriodSummary.of(p, DateRange.of(p, d(2026, 10, 13)), RangeTotals(0, rub(15_600)))
        assertTrue(summary.isOverspent)
        assertEquals(rub(-600), summary.leftKopecks)
    }
}
