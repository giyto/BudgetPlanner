package ru.hznik.budget.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Повреждённый файл настроек не роняет приложение: он заменяется умолчаниями, а защита входа остаётся включённой. */
@RunWith(AndroidJUnit4::class)
class SettingsCorruptionTest {
    @Test
    fun corruptedSettingsFileIsReplacedAndProtectionStaysOn() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "corrupted_settings_test.preferences_pb")
        // Не protobuf: тип поля 7 не существует, чтение бросает CorruptionException.
        file.writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03, 0x04))
        val scope = CoroutineScope(Dispatchers.IO + Job())
        try {
            val store = createSettingsDataStore(scope) { file }
            assertTrue(DataStoreLockSettingsRepository(store).observe().first().enabled)
        } finally {
            scope.cancel()
            file.delete()
        }
    }
}
