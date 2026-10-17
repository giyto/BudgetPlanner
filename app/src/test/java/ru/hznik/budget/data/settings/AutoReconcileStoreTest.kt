package ru.hznik.budget.data.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Переключатель «Автоматическая сверка» (spec balance-reconciliation): по умолчанию включён, выбор сохраняется. */
class AutoReconcileStoreTest {
    @Test
    fun `auto reconciliation is on by default`() = runTest {
        assertTrue(DataStoreAutoReconcileSettings(InMemoryPreferencesDataStore()).enabled.first())
    }

    @Test
    fun `switch is saved and read back`() = runTest {
        val store = InMemoryPreferencesDataStore()
        DataStoreAutoReconcileSettings(store).setEnabled(false)
        assertFalse(DataStoreAutoReconcileSettings(store).enabled.first())

        DataStoreAutoReconcileSettings(store).setEnabled(true)
        assertTrue(DataStoreAutoReconcileSettings(store).enabled.first())
    }
}
