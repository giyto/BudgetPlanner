package ru.hznik.budget.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Настройки помощника (spec ai-assistant): выключен по умолчанию, счётчик токенов только растёт. */
class AiSettingsStoreTest {
    private fun settings() = DataStoreAiSettings(InMemoryPreferencesDataStore())

    @Test
    fun `assistant is off and merchants are sent by default`() = runTest {
        val settings = settings()
        assertFalse(settings.enabled.first())
        assertFalse(settings.hideMerchants.first())
        assertEquals(0L, settings.tokensUsed.first())
    }

    @Test
    fun `switches are saved`() = runTest {
        val settings = settings()
        settings.setEnabled(true)
        settings.setHideMerchants(true)
        assertTrue(settings.enabled.first())
        assertTrue(settings.hideMerchants.first())
    }

    @Test
    fun `tokens are summed and ignore non positive values`() = runTest {
        val settings = settings()
        settings.addTokens(3_000)
        settings.addTokens(1_200)
        settings.addTokens(0)
        settings.addTokens(-5)
        assertEquals(4_200L, settings.tokensUsed.first())
    }
}

/**
 * DataStore в памяти: файловый DataStore в JVM-тестах на Windows не может переименовать временный файл
 * поверх существующего, а на Android такой проблемы нет.
 */
class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        mutex.withLock { transform(state.value).also { state.value = it } }
}
