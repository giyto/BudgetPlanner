package ru.hznik.budget.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.hznik.budget.testing.MainDispatcherRule

/** Сбой записи из экрана не роняет приложение: кнопка снова доступна, пользователь видит сообщение. */
class SaveFailuresTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    /** Как редактор: успешное сохранение закрывает экран, поэтому кнопка остаётся занятой. */
    private class EditorViewModel : ViewModel() {
        var busy = false
        var saved = 0

        fun save(fail: Boolean) {
            busy = true
            launchSave(onFailure = { busy = false }) {
                if (fail) error("База недоступна")
                saved++
            }
        }
    }

    @Test
    fun `failed save frees the button and reports the failure`() = runTest(UnconfinedTestDispatcher()) {
        val reports = mutableListOf<Unit>()
        val collector = launch { SaveFailures.events.toList(reports) }
        val viewModel = EditorViewModel()
        viewModel.save(fail = true)
        assertFalse(viewModel.busy)
        assertEquals(0, viewModel.saved)
        assertEquals(1, reports.size)
        collector.cancel()
    }

    @Test
    fun `successful save reports nothing and keeps the closing screen busy`() = runTest(UnconfinedTestDispatcher()) {
        val reports = mutableListOf<Unit>()
        val collector = launch { SaveFailures.events.toList(reports) }
        val viewModel = EditorViewModel()
        viewModel.save(fail = false)
        assertTrue(viewModel.busy)
        assertEquals(1, viewModel.saved)
        assertEquals(0, reports.size)
        collector.cancel()
    }
}
