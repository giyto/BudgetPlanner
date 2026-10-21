package ru.hznik.budget.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationItem
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.repository.OperationFilter
import ru.hznik.budget.testing.CAFE
import ru.hznik.budget.testing.FakeCategoryRepository
import ru.hznik.budget.testing.FakeOperationRepository
import ru.hznik.budget.testing.SALARY
import ru.hznik.budget.testing.TODAY
import ru.hznik.budget.testing.TRANSFERS
import ru.hznik.budget.testing.at

class DayGroupTest {
    private fun item(type: OperationType, rubles: Long, day: java.time.LocalDate = TODAY, hour: Int = 12) = OperationItem(
        Operation(type = type, amount = Money(rubles * 100), categoryId = 0, source = OperationSource.MANUAL, occurredAt = at(day, hour)),
        if (type == OperationType.INCOME) SALARY else if (type == OperationType.TRANSFER) TRANSFERS else CAFE,
    )

    @Test
    fun `day totals separate spending and income`() {
        val group = DayGroup(TODAY, listOf(item(OperationType.EXPENSE, 240), item(OperationType.EXPENSE, 90), item(OperationType.INCOME, 2000)))

        assertEquals(33_000L, group.netSpentKopecks)
        assertEquals(200_000L, group.incomeKopecks)
    }

    @Test
    fun `transfer counts as spending and refund reduces it`() {
        val group = DayGroup(TODAY, listOf(item(OperationType.EXPENSE, 2000), item(OperationType.TRANSFER, 500), item(OperationType.REFUND, 1500)))

        assertEquals(100_000L, group.netSpentKopecks)
        assertEquals(0L, group.incomeKopecks)
    }

    @Test
    fun `groups go from newest day and keep order inside a day`() {
        val items = listOf(
            item(OperationType.EXPENSE, 1, TODAY, 18),
            item(OperationType.EXPENSE, 2, TODAY, 9),
            item(OperationType.EXPENSE, 3, TODAY.minusDays(2)),
            item(OperationType.EXPENSE, 4, TODAY.minusDays(1)),
        )

        val groups = groupByDay(items)

        assertEquals(listOf(TODAY, TODAY.minusDays(1), TODAY.minusDays(2)), groups.map { it.date })
        assertEquals(listOf(100L, 200L), groups.first().items.map { it.operation.amount.kopecks })
    }

    @Test
    fun `page hides the possibly incomplete last day until the next portion`() = runTest {
        val repository = FakeOperationRepository(FakeCategoryRepository())
        listOf(TODAY to 2, TODAY.minusDays(1) to 2, TODAY.minusDays(2) to 2).forEach { (day, count) ->
            repeat(count) { repository.insert(item(OperationType.EXPENSE, 100, day, 10 + it).operation.copy(categoryId = CAFE.id)) }
        }
        val observe = ObserveOperations(repository)

        // 5 из 6 операций: позавчера пришло не целиком, его нет в порции.
        val first = observe.page(OperationFilter(limit = 5)).first()
        assertEquals(listOf(TODAY, TODAY.minusDays(1)), first.groups.map { it.date })
        assertTrue(first.hasMore)

        val all = observe.page(OperationFilter(limit = 10)).first()
        assertEquals(3, all.groups.size)
        assertFalse(all.hasMore)
    }
}
