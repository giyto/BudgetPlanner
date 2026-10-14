package ru.hznik.budget.domain.usecase

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.model.PaymentRepeat
import ru.hznik.budget.domain.model.PlannedPayment
import ru.hznik.budget.testing.CAFE
import ru.hznik.budget.testing.FakeCategoryRepository
import ru.hznik.budget.testing.FakeOperationRepository
import ru.hznik.budget.testing.FakePlannedPaymentRepository
import ru.hznik.budget.testing.PRODUCTS
import ru.hznik.budget.testing.SALARY
import ru.hznik.budget.testing.TEST_CLOCK
import ru.hznik.budget.testing.TODAY
import ru.hznik.budget.testing.TRANSFERS
import ru.hznik.budget.testing.TRANSPORT
import ru.hznik.budget.testing.at
import ru.hznik.budget.testing.category

class CategoryUseCasesTest {
    private val categories = FakeCategoryRepository()
    private val operations = FakeOperationRepository(categories)
    private val payments = FakePlannedPaymentRepository(operations)

    private suspend fun spend(categoryId: Long, daysAgo: Long, times: Int = 1) = repeat(times) {
        operations.insert(Operation(type = OperationType.EXPENSE, amount = Money(100), categoryId = categoryId,
            source = OperationSource.MANUAL, occurredAt = at(TODAY.minusDays(daysAgo))))
    }

    @Test
    fun `input categories go by usage in last 30 days, then default order`() = runTest {
        spend(TRANSPORT.id, daysAgo = 1, times = 3)
        spend(PRODUCTS.id, daysAgo = 29, times = 2)
        spend(CAFE.id, daysAgo = 30, times = 5) // за пределами окна в 30 дней

        ObserveInputCategories(categories, operations, TEST_CLOCK)(CategoryKind.EXPENSE).test {
            assertEquals(listOf("Транспорт", "Продукты", "Кафе и рестораны", "Переводы"), awaitItem().map { it.name })
        }
    }

    @Test
    fun `input categories skip hidden and other kind`() = runTest {
        categories.update(CAFE.copy(hidden = true))

        ObserveInputCategories(categories, operations, TEST_CLOCK)(CategoryKind.EXPENSE).test {
            val names = awaitItem().map { it.name }
            assertFalse("Кафе и рестораны" in names)
            assertFalse(SALARY.name in names)
        }
    }

    @Test
    fun `new category gets next sort order`() = runTest {
        val result = SaveCategory(categories)(category(0, "  Спорт ", sortOrder = 0))

        val created = categories.get((result as CategorySaveResult.Saved).id)!!
        assertEquals("Спорт", created.name)
        assertEquals(TRANSFERS.sortOrder + 1, created.sortOrder)
        assertEquals(null, created.defaultKey)
    }

    @Test
    fun `name rules`() = runTest {
        val save = SaveCategory(categories)
        assertEquals(CategorySaveResult.EmptyName, save(category(0, "   ")))
        assertEquals(CategorySaveResult.NameTooLong, save(category(0, "а".repeat(31))))
        assertEquals(CategorySaveResult.DuplicateName, save(category(0, "продукты")))
        assertTrue(save(category(0, "Продукты", kind = CategoryKind.INCOME)) is CategorySaveResult.Saved)
    }

    @Test
    fun `editing changes name, icon and color but keeps kind and default key`() = runTest {
        SaveCategory(categories)(CAFE.copy(name = "Кафе", icon = "cup", color = "pink", kind = CategoryKind.INCOME, defaultKey = null))

        val edited = categories.get(CAFE.id)!!
        assertEquals("Кафе", edited.name)
        assertEquals("pink", edited.color)
        assertEquals(CategoryKind.EXPENSE, edited.kind)
        assertEquals("cafe", edited.defaultKey)
    }

    @Test
    fun `renaming to own name is not a duplicate`() = runTest {
        assertTrue(SaveCategory(categories)(CAFE.copy(icon = "cup")) is CategorySaveResult.Saved)
    }

    @Test
    fun `only own empty category can be deleted`() = runTest {
        val delete = DeleteCategory(categories)
        val ownEmpty = categories.insert(category(0, "Спорт"))
        val ownUsed = categories.insert(category(0, "Хобби"))
        spend(ownUsed, daysAgo = 0)

        assertEquals(CategoryDeleteResult.MUST_HIDE, delete(PRODUCTS.id))
        assertEquals(CategoryDeleteResult.MUST_HIDE, delete(ownUsed))
        assertEquals(CategoryDeleteResult.DELETED, delete(ownEmpty))
        assertEquals(null, categories.get(ownEmpty))
    }

    @Test
    fun `category of a planned payment cannot be deleted`() = runTest {
        val own = categories.insert(category(0, "Курсы"))
        payments.save(PlannedPayment(name = "Английский", amount = Money(300_000), dueDate = TODAY, repeat = PaymentRepeat.MONTHLY, categoryId = own))
        assertEquals(CategoryDeleteResult.MUST_HIDE, DeleteCategory(categories)(own))
    }

    @Test
    fun `hide and show`() = runTest {
        SetCategoryHidden(categories)(CAFE.id, true)
        assertTrue(categories.get(CAFE.id)!!.hidden)
        SetCategoryHidden(categories)(CAFE.id, false)
        assertFalse(categories.get(CAFE.id)!!.hidden)
    }
}
