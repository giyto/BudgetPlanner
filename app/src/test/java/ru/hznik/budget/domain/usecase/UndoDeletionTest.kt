package ru.hznik.budget.domain.usecase

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.testing.CAFE
import ru.hznik.budget.testing.FakeCategoryRepository
import ru.hznik.budget.testing.FakeOperationRepository
import ru.hznik.budget.testing.TODAY
import ru.hznik.budget.testing.at

class UndoDeletionTest {
    private val categories = FakeCategoryRepository()
    private val operations = FakeOperationRepository(categories)
    private val buffer = UndoDeletionBuffer()
    private val delete = DeleteOperation(operations, buffer)
    private val restore = RestoreDeletedOperation(operations, categories, buffer)

    private suspend fun add(kopecks: Long) = operations.insert(
        Operation(type = OperationType.EXPENSE, amount = Money(kopecks), categoryId = CAFE.id, comment = "кофе",
            source = OperationSource.MANUAL, occurredAt = at(TODAY, 9, 15)),
    )

    @Test
    fun `delete removes at once and restore brings back the same operation`() = runTest {
        val id = add(24_000)
        val original = operations.get(id)

        buffer.events.test {
            assertTrue(delete(id))
            val event = awaitItem()
            assertTrue(operations.state.value.isEmpty())

            assertTrue(restore(event.token))
            assertEquals(listOf(original), operations.state.value)
        }
    }

    @Test
    fun `restore works only once`() = runTest {
        val id = add(100)
        buffer.events.test {
            delete(id)
            val token = awaitItem().token

            assertTrue(restore(token))
            assertFalse(restore(token))
            assertEquals(1, operations.state.value.size)
        }
    }

    @Test
    fun `new deletion replaces the previous one`() = runTest {
        val first = add(100)
        val second = add(200)
        buffer.events.test {
            delete(first)
            val firstToken = awaitItem().token
            delete(second)
            val secondToken = awaitItem().token

            assertFalse(restore(firstToken))
            assertTrue(restore(secondToken))
            assertEquals(listOf(200L), operations.state.value.map { it.amount.kopecks })
        }
    }

    @Test
    fun `restore is refused if the category was deleted meanwhile`() = runTest {
        val id = add(100)
        buffer.events.test {
            delete(id)
            val token = awaitItem().token
            categories.delete(CAFE.id)

            assertFalse(restore(token))
            assertTrue(operations.state.value.isEmpty())
        }
    }

    @Test
    fun `discarded deletion cannot be restored`() = runTest {
        val id = add(100)
        buffer.events.test {
            delete(id)
            val token = awaitItem().token
            buffer.discard(token)

            assertFalse(restore(token))
            assertTrue(operations.state.value.isEmpty())
        }
    }
}
