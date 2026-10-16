package ru.hznik.budget.ui.operations

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.usecase.AccountOperationsFilter
import ru.hznik.budget.domain.usecase.ObserveOperations
import ru.hznik.budget.testing.CAFE
import ru.hznik.budget.testing.FakeCategoryRepository
import ru.hznik.budget.testing.FakeOperationRepository
import ru.hznik.budget.testing.FakeReconciliationRepository
import ru.hznik.budget.testing.MainDispatcherRule
import ru.hznik.budget.testing.PRODUCTS
import ru.hznik.budget.testing.TEST_CLOCK
import ru.hznik.budget.testing.TODAY
import ru.hznik.budget.testing.at

@OptIn(ExperimentalCoroutinesApi::class)
class OperationsViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule(StandardTestDispatcher())

    private val categories = FakeCategoryRepository()
    private val operations = FakeOperationRepository(categories)
    private val savedState = SavedStateHandle()

    private fun viewModel() = OperationsViewModel(savedState, ObserveOperations(operations), categories, AccountOperationsFilter(FakeReconciliationRepository(operations)), TEST_CLOCK)

    private suspend fun add(category: Long, rubles: Long, source: OperationSource = OperationSource.MANUAL, comment: String? = null) =
        operations.insert(Operation(type = OperationType.EXPENSE, amount = Money(rubles * 100), categoryId = category,
            comment = comment, source = source, occurredAt = at(TODAY)))

    @Test
    fun `search waits 200 ms after typing`() = runTest(mainRule.dispatcher) {
        add(CAFE.id, 240)
        add(PRODUCTS.id, 450)
        val vm = viewModel()
        vm.uiState.test {
            advanceUntilIdle()
            assertEquals(2, expectMostRecentItem().groups.single().items.size)

            vm.onQueryChanged("кафе")
            advanceTimeBy(100)
            val typing = expectMostRecentItem()
            assertEquals("кафе", typing.query)
            assertEquals(2, typing.groups.single().items.size)

            advanceTimeBy(150)
            assertEquals(listOf(CAFE.id), expectMostRecentItem().groups.single().items.map { it.category.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `source and category filters combine and survive in saved state`() = runTest(mainRule.dispatcher) {
        add(PRODUCTS.id, 1)
        add(PRODUCTS.id, 2, source = OperationSource.NOTIFICATION)
        add(CAFE.id, 3)
        val vm = viewModel()
        vm.onSourceSelected(OperationSource.MANUAL)
        vm.onCategorySelected(PRODUCTS.id)
        vm.uiState.test {
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertEquals(listOf(100L), state.groups.single().items.map { it.operation.amount.kopecks })
            assertEquals(PRODUCTS.id, state.selectedCategory?.id)
            assertTrue(state.hasFilters)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(OperationSource.MANUAL.name, savedState.get<String>("source"))
    }

    @Test
    fun `reset clears search and filters`() = runTest(mainRule.dispatcher) {
        val vm = viewModel()
        vm.onQueryChanged("что-то")
        vm.onSourceSelected(OperationSource.NOTIFICATION)
        vm.resetFilters()
        vm.uiState.test {
            advanceUntilIdle()
            assertFalse(expectMostRecentItem().hasFilters)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
