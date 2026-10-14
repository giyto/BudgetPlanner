package ru.hznik.budget.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.hznik.budget.domain.model.IncomeSchedule

/**
 * Запись и чтение расписания дохода. DataStore здесь в памяти: настоящий файловый на Windows
 * не может заменить файл, пока его держит открытым другой процесс (например, антивирус), а сохранность файла — забота библиотеки.
 */
class IncomeScheduleStoreTest {
    private class InMemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test
    fun `schedule is empty until set, then survives as written`() = runTest {
        val repository = DataStoreIncomeScheduleRepository(InMemoryDataStore())

        assertNull(repository.observe().first())
        repository.set(IncomeSchedule.Regular(25))
        assertEquals(IncomeSchedule.Regular(25), repository.observe().first())
        repository.set(IncomeSchedule.Irregular)
        assertEquals(IncomeSchedule.Irregular, repository.observe().first())
        repository.set(IncomeSchedule.Regular(31))
        assertEquals(IncomeSchedule.Regular(31), repository.observe().first())
    }
}
