package ru.hznik.budget.ui.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Передача образцов разработчику (только отладочная сборка): файл `Download/budget-samples.txt`, который
 * можно забрать по USB, и буфер обмена с пометкой «секретно», чтобы система не показывала текст в превью.
 */
class SamplesFile @Inject constructor(@ApplicationContext private val context: Context) {
    /** Перезаписывает файл; прежний файл приложения удаляется, чтобы не копились «budget-samples (1).txt». */
    suspend fun write(text: String) = withContext(Dispatchers.IO) {
        delete()
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "Не удалось создать файл" }
        resolver.openOutputStream(uri)!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** Удаляет файл, созданный приложением; чужие файлы с тем же именем приложению не видны. */
    suspend fun delete() = withContext(Dispatchers.IO) {
        context.contentResolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(FILE_NAME),
        )
        Unit
    }

    fun copyToClipboard(text: String) {
        val clip = ClipData.newPlainText("budget-samples", text)
        clip.description.extras = PersistableBundle().apply {
            val key = if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"
            putBoolean(key, true)
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    companion object {
        const val FILE_NAME = "budget-samples.txt"
    }
}
