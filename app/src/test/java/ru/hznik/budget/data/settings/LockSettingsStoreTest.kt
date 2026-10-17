package ru.hznik.budget.data.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.domain.lock.LockSettings
import ru.hznik.budget.domain.lock.LockTimeout

/**
 * Настройки защиты входа в DataStore (spec app-lock, «Раздел „Защита входа“ в настройках»; FR-10).
 * По умолчанию защита выключена, блокировка через 1 минуту, сумма на виджете видна; изменения сохраняются после перезапуска.
 */
class LockSettingsStoreTest {
    @Test
    fun `protection is off with one minute timeout and nothing hidden by default`() = runTest {
        val settings = DataStoreLockSettingsRepository(InMemoryPreferencesDataStore()).observe().first()

        assertEquals(LockSettings(), settings)
        assertFalse(settings.enabled)
        assertEquals(LockTimeout.ONE_MINUTE, settings.timeout)
        assertFalse(settings.hideInRecents)
        assertFalse(settings.hideWidgetAmount)
        assertFalse(settings.widgetAmountHidden)
    }

    @Test
    fun `written values are read back by a new repository over the same store`() = runTest {
        val store = InMemoryPreferencesDataStore()
        val repository = DataStoreLockSettingsRepository(store)
        repository.setEnabled(true)
        repository.setTimeout(LockTimeout.FIVE_MINUTES)
        repository.setHideInRecents(true)
        repository.setHideWidgetAmount(true)

        // Новый экземпляр над тем же хранилищем — как после перезапуска приложения.
        val restored = DataStoreLockSettingsRepository(store).observe().first()

        assertEquals(LockSettings(enabled = true, timeout = LockTimeout.FIVE_MINUTES, hideInRecents = true, hideWidgetAmount = true), restored)
        assertTrue(restored.widgetAmountHidden)
    }

    @Test
    fun `every timeout survives a round trip`() = runTest {
        val repository = DataStoreLockSettingsRepository(InMemoryPreferencesDataStore())

        LockTimeout.entries.forEach { timeout ->
            repository.setTimeout(timeout)
            assertEquals(timeout, repository.observe().first().timeout)
        }
    }

    @Test
    fun `turning protection off keeps other choices but shows the widget amount`() = runTest {
        val repository = DataStoreLockSettingsRepository(InMemoryPreferencesDataStore())
        repository.setEnabled(true)
        repository.setHideWidgetAmount(true)

        repository.setEnabled(false)
        val settings = repository.observe().first()

        assertFalse(settings.enabled)
        assertTrue(settings.hideWidgetAmount)
        // «Прятать сумму на виджете» действует только при включённой защите (spec home-widget).
        assertFalse(settings.widgetAmountHidden)
    }

    @Test
    fun `unknown stored timeout falls back to one minute`() = runTest {
        val store = InMemoryPreferencesDataStore()
        // Значение, которого нет в текущей версии (например, после отката обновления), не должно ронять чтение настроек.
        store.edit { it[stringPreferencesKey("lock_timeout")] = "TEN_MINUTES" }

        assertEquals(LockTimeout.ONE_MINUTE, DataStoreLockSettingsRepository(store).observe().first().timeout)
    }
}
