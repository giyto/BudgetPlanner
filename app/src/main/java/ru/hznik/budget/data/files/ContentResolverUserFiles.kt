package ru.hznik.budget.data.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.hznik.budget.domain.export.UserFileInfo
import ru.hznik.budget.domain.export.UserFiles
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** Файлы через `ContentResolver`: адрес из системного окна выбора, без разрешений на память телефона. */
class ContentResolverUserFiles @Inject constructor(@param:ApplicationContext private val context: Context) : UserFiles {
    override suspend fun writeText(target: String, text: String) = withContext(Dispatchers.IO) {
        // «wt» — заменить содержимое, если пользователь выбрал существующий файл.
        val stream = context.contentResolver.openOutputStream(Uri.parse(target), "wt") ?: throw IOException("Файл не открылся")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    override suspend fun copyTo(target: String, file: File) = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openOutputStream(Uri.parse(target), "wt") ?: throw IOException("Файл не открылся")
        stream.use { out -> file.inputStream().use { it.copyTo(out) } }
        Unit
    }

    override suspend fun delete(target: String) = withContext(Dispatchers.IO) {
        // Провайдер может не уметь удалять: тогда файл остаётся, это не повод для второй ошибки.
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(target)) }
        Unit
    }

    override suspend fun describe(source: String): UserFileInfo = withContext(Dispatchers.IO) {
        val uri = Uri.parse(source)
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: uri.lastPathSegment.orEmpty()
                return@withContext UserFileInfo(name, if (c.isNull(1)) null else c.getLong(1))
            }
        }
        UserFileInfo(uri.lastPathSegment.orEmpty(), null)
    }

    override suspend fun copyFrom(source: String, file: File) = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(Uri.parse(source)) ?: throw IOException("Файл не открылся")
        stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        Unit
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FilesModule {
    @Binds
    abstract fun bindUserFiles(impl: ContentResolverUserFiles): UserFiles
}
