package ru.hznik.budget.ui.operations

import ru.hznik.budget.ui.components.AmountKey
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.usecase.DeleteOperation
import ru.hznik.budget.domain.usecase.InputType
import ru.hznik.budget.domain.usecase.ObserveInputCategories
import ru.hznik.budget.domain.usecase.PlaceOperationForAccount
import ru.hznik.budget.domain.usecase.SaveOperation
import ru.hznik.budget.domain.usecase.UndoDeletionBuffer
import ru.hznik.budget.testing.CAFE
import ru.hznik.budget.testing.FakeCategoryRepository
import ru.hznik.budget.testing.FakeOperationRepository
import ru.hznik.budget.testing.FakeReconciliationRepository
import ru.hznik.budget.testing.MOSCOW
import ru.hznik.budget.testing.MainDispatcherRule
import ru.hznik.budget.testing.SALARY
import ru.hznik.budget.testing.TEST_CLOCK
import ru.hznik.budget.testing.TODAY
import ru.hznik.budget.testing.at
import java.time.LocalTime

/** Часы, которые тест может перевести вперёд. */
private class MutableClock(var current: java.time.Clock) : java.time.Clock() {
    override fun getZone(): java.time.ZoneId = current.zone
    override fun withZone(zone: java.time.ZoneId): java.time.Clock = current.withZone(zone)
    override fun instant(): java.time.Instant = current.instant()
}

class OperationEditorViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val categories = FakeCategoryRepository()
    private val operations = FakeOperationRepository(categories)
    private val buffer = UndoDeletionBuffer()

    private fun viewModel(operationId: Long? = null) = OperationEditorViewModel(
        savedStateHandle = SavedStateHandle(operationId?.let { mapOf(OperationEditorViewModel.ARG_OPERATION_ID to it) } ?: emptyMap()),
        observeInputCategories = ObserveInputCategories(categories, operations, TEST_CLOCK),
        categories = categories,
        operations = operations,
        saveOperation = SaveOperation(operations, categories, PlaceOperationForAccount(FakeReconciliationRepository(operations)), TEST_CLOCK),
        deleteOperation = DeleteOperation(operations, buffer),
        clock = TEST_CLOCK,
    )

    private fun OperationEditorViewModel.type(vararg digits: Int) = digits.forEach { onKey(AmountKey.Digit(it)) }

    @Test
    fun `typing amount and choosing category enables save, saving closes the screen`() = runTest {
        val vm = viewModel()
        vm.uiState.test {
            assertFalse(awaitItem().canSave)
            vm.type(1, 8, 0)
            assertFalse(expectMostRecentItem().canSave)
            vm.onCategorySelected(CAFE.id)
            val state = expectMostRecentItem()
            assertEquals(18_000L, state.amountKopecks)
            assertTrue(state.canSave)
            cancelAndIgnoreRemainingEvents()
        }
        vm.events.test {
            vm.save()
            assertEquals(EditorEvent.Close, awaitItem())
        }
        val saved = operations.state.value.single()
        assertEquals(OperationType.EXPENSE, saved.type)
        assertEquals(TODAY, saved.localDate)
        assertEquals(LocalTime.of(12, 0), saved.occurredAt.toLocalTime())
    }

    @Test
    fun `switching to income resets expense category, refund keeps it`() = runTest {
        val vm = viewModel()
        vm.uiState.test {
            awaitItem()
            vm.onCategorySelected(CAFE.id)
            vm.onTypeSelected(InputType.REFUND)
            assertEquals(CAFE.id, expectMostRecentItem().selectedCategoryId)
            vm.onTypeSelected(InputType.INCOME)
            val state = expectMostRecentItem()
            assertNull(state.selectedCategoryId)
            assertEquals(listOf(SALARY.id), state.allCategories.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `future date is ignored`() = runTest {
        val vm = viewModel()
        vm.uiState.test {
            awaitItem()
            vm.onDateSelected(TODAY.plusDays(1))
            // Будущая дата не меняет состояние, поэтому нового значения в потоке нет.
            expectNoEvents()
            assertEquals(TODAY, vm.uiState.value.date)
            vm.onDateSelected(TODAY.minusDays(1))
            assertEquals(TODAY.minusDays(1), expectMostRecentItem().date)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `editing loads the operation and keeps its time when the date changes`() = runTest {
        val id = operations.insert(
            Operation(type = OperationType.EXPENSE, amount = Money(18_050), categoryId = CAFE.id, comment = "кофе",
                source = OperationSource.MANUAL, occurredAt = at(TODAY, 9, 15)),
        )
        val vm = viewModel(id)
        vm.uiState.test {
            val state = expectMostRecentItem()
            assertTrue(state.isEdit)
            assertEquals("180,50", state.amountInput)
            assertEquals(CAFE.id, state.selectedCategoryId)
            assertEquals("кофе", state.comment)
            cancelAndIgnoreRemainingEvents()
        }
        vm.onDateSelected(TODAY.minusDays(1))
        vm.events.test {
            vm.save()
            awaitItem()
        }
        val edited = operations.state.value.single()
        assertEquals(id, edited.id)
        assertEquals(TODAY.minusDays(1).atTime(9, 15).atZone(MOSCOW).toOffsetDateTime(), edited.occurredAt)
    }

    @Test
    fun `moving an evening operation to today does not put it in the future`() = runTest {
        // В тестовых часах сейчас 12:00; вчерашняя операция в 21:00, перенесённая на сегодня, получает 12:00.
        val id = operations.insert(
            Operation(type = OperationType.EXPENSE, amount = Money(100), categoryId = CAFE.id,
                source = OperationSource.MANUAL, occurredAt = at(TODAY.minusDays(1), 21, 0)),
        )
        val vm = viewModel(id)
        vm.uiState.test {
            expectMostRecentItem()
            cancelAndIgnoreRemainingEvents()
        }
        vm.onDateSelected(TODAY)
        vm.events.test {
            vm.save()
            awaitItem()
        }
        assertEquals(LocalTime.of(12, 0), operations.state.value.single().occurredAt.toLocalTime())
        assertEquals(TODAY, operations.state.value.single().localDate)
    }

    @Test
    fun `new operation without chosen date is saved for today at save time`() = runTest {
        // Экран открыт «вчера» (часы ViewModel), сохранение — уже сегодня.
        val openedYesterday = java.time.Clock.offset(TEST_CLOCK, java.time.Duration.ofDays(-1))
        val clock = MutableClock(openedYesterday)
        val vm = OperationEditorViewModel(
            savedStateHandle = SavedStateHandle(),
            observeInputCategories = ObserveInputCategories(categories, operations, openedYesterday),
            categories = categories,
            operations = operations,
            saveOperation = SaveOperation(operations, categories, PlaceOperationForAccount(FakeReconciliationRepository(operations)), TEST_CLOCK),
            deleteOperation = DeleteOperation(operations, buffer),
            clock = clock,
        )
        assertEquals(TODAY.minusDays(1), vm.uiState.value.date)
        vm.type(5)
        vm.onCategorySelected(CAFE.id)
        clock.current = TEST_CLOCK
        vm.uiState.test {
            assertTrue(expectMostRecentItem().canSave)
            cancelAndIgnoreRemainingEvents()
        }
        vm.events.test {
            vm.save()
            awaitItem()
        }
        assertEquals(TODAY, operations.state.value.single().localDate)
    }

    @Test
    fun `delete removes the operation, offers undo and closes`() = runTest {
        val id = operations.insert(
            Operation(type = OperationType.EXPENSE, amount = Money(100), categoryId = CAFE.id, source = OperationSource.MANUAL, occurredAt = at(TODAY)),
        )
        val vm = viewModel(id)
        buffer.events.test {
            vm.events.test {
                vm.delete()
                assertEquals(EditorEvent.Close, awaitItem())
            }
            awaitItem()
        }
        assertTrue(operations.state.value.isEmpty())
    }
}
