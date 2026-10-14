package ru.hznik.budget.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.usecase.DeleteOperation
import ru.hznik.budget.domain.usecase.ObserveOnboardingDone
import ru.hznik.budget.domain.usecase.RestoreDeletedOperation
import ru.hznik.budget.domain.usecase.UndoDeletionBuffer
import ru.hznik.budget.testing.CAFE
import ru.hznik.budget.testing.FakeCategoryRepository
import ru.hznik.budget.testing.FakeIncomeScheduleRepository
import ru.hznik.budget.testing.FakePeriodRepository
import ru.hznik.budget.testing.FakeOperationRepository
import ru.hznik.budget.testing.MainDispatcherRule
import ru.hznik.budget.testing.TODAY
import ru.hznik.budget.testing.at

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule(StandardTestDispatcher())

    private val categories = FakeCategoryRepository()
    private val operations = FakeOperationRepository(categories)
    private val buffer = UndoDeletionBuffer()
    private val delete = DeleteOperation(operations, buffer)
    // Лениво: viewModelScope должен стартовать уже на тестовом Dispatchers.Main из правила.
    private val viewModel by lazy {
        AppViewModel(
            buffer,
            RestoreDeletedOperation(operations, categories, buffer),
            ObserveOnboardingDone(FakePeriodRepository(), FakeIncomeScheduleRepository()),
        )
    }

    private suspend fun addAndDelete(kopecks: Long = 18_000): Long {
        val id = operations.insert(
            Operation(type = OperationType.EXPENSE, amount = Money(kopecks), categoryId = CAFE.id,
                source = OperationSource.MANUAL, occurredAt = at(TODAY)),
        )
        delete(id)
        return id
    }

    @Test
    fun `undo is offered for exactly 5 seconds, not the standard 4`() = runTest(mainRule.dispatcher) {
        viewModel
        runCurrent()
        addAndDelete()
        runCurrent()
        assertNotNull(viewModel.pendingUndo.value)

        advanceTimeBy(4_999)
        assertNotNull("Через 4,999 с отмена ещё доступна", viewModel.pendingUndo.value)

        advanceTimeBy(2)
        assertNull("Через 5 с сообщение убрано", viewModel.pendingUndo.value)
        viewModel.undo()
        runCurrent()
        assertTrue("После срока отмена ничего не возвращает", operations.state.value.isEmpty())
    }

    @Test
    fun `undo within the window restores the operation`() = runTest(mainRule.dispatcher) {
        viewModel
        runCurrent()
        val id = addAndDelete()
        runCurrent()
        advanceTimeBy(3_000)

        viewModel.undo()
        runCurrent()

        assertNull(viewModel.pendingUndo.value)
        assertEquals(listOf(id), operations.state.value.map { it.id })
    }

    @Test
    fun `new deletion restarts the window`() = runTest(mainRule.dispatcher) {
        viewModel
        runCurrent()
        addAndDelete(100)
        runCurrent()
        advanceTimeBy(4_000)
        addAndDelete(200)
        runCurrent()
        val second = viewModel.pendingUndo.value

        advanceTimeBy(4_000)
        assertEquals("Второе удаление ещё можно отменить через 4 с после него", second, viewModel.pendingUndo.value)
        advanceTimeBy(1_001)
        assertNull(viewModel.pendingUndo.value)
    }

    @Test
    fun `dismiss gives up the undo`() = runTest(mainRule.dispatcher) {
        viewModel
        runCurrent()
        addAndDelete()
        runCurrent()

        viewModel.dismiss()
        viewModel.undo()
        runCurrent()

        assertNull(viewModel.pendingUndo.value)
        assertTrue(operations.state.value.isEmpty())
    }
}
