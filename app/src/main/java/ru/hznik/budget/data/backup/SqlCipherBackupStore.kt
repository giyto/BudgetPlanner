package ru.hznik.budget.data.backup

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteException
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SQLiteDatabase
import ru.hznik.budget.data.local.BudgetDatabase
import ru.hznik.budget.data.security.DatabaseEncryption
import ru.hznik.budget.domain.export.AppRestarter
import ru.hznik.budget.domain.export.BackupCheck
import ru.hznik.budget.domain.export.BackupStore
import ru.hznik.budget.domain.export.DataWipe
import ru.hznik.budget.domain.export.UserFiles
import java.io.File
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Резервная копия — база SQLCipher под паролем пользователя (design.md harden-and-release-v1, решение 2): SQLCipher
 * сам выводит ключ из пароля (PBKDF2, случайная соль), без пароля файл не читается. Создание и проверка идут через
 * отдельное соединение с ключом телефона и `sqlcipher_export`; Room при этом работает как обычно.
 */
@Singleton
class SqlCipherBackupStore @Inject constructor(
    @param:ApplicationContext context: Context,
    private val encryption: DatabaseEncryption,
    private val files: UserFiles,
    private val dataStore: DataStore<Preferences>,
    private val clock: Clock,
) : BackupStore {
    private val cacheDir: File = context.cacheDir
    private val mutex = Mutex()

    /** Проверенная копия, уже переписанная ключом телефона, и её настройки — до подтверждения замены. */
    private var checked: Pair<File, String>? = null

    override suspend fun create(target: String, password: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(password.length >= MIN_PASSWORD_LENGTH) { "Пароль короче $MIN_PASSWORD_LENGTH символов" }
            val temp = File(cacheDir, BACKUP_TEMP)
            try {
                deleteWithJournal(temp)
                val settings = SettingsSnapshot.toJson(dataStore.data.first())
                openMain().use { db ->
                    val version = db.version
                    db.execSQL("ATTACH DATABASE ? AS backup KEY ?", arrayOf<Any>(temp.path, password))
                    // Одна транзакция: автоимпорт посреди копирования не даёт операций без их категорий в копии.
                    db.beginTransaction()
                    try {
                        exportTo(db, version, settings)
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                    db.execSQL("DETACH DATABASE backup")
                }
                files.copyTo(target, temp)
            } catch (e: Exception) {
                // Файл в выбранном месте уже создан окном сохранения: пустой или неполный не оставляем.
                files.delete(target)
                throw e
            } finally {
                deleteWithJournal(temp)
            }
        }
    }

    /** Данные и сведения о копии в присоединённую базу `backup`: без образцов уведомлений, с версией схемы и настройками. */
    private fun exportTo(db: SQLiteDatabase, version: Int, settings: String) {
        db.rawQuery("SELECT sqlcipher_export('backup')", emptyArray<String>()).use { it.moveToFirst() }
        // sqlcipher_export не переносит номер версии схемы: по нему восстановление запустит миграции.
        db.execSQL("PRAGMA backup.user_version = $version")
        db.execSQL("DELETE FROM backup.notification_samples")
        db.execSQL("CREATE TABLE backup.$INFO_TABLE (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
        val info = mapOf(
            APP to APP_ID,
            FORMAT to FORMAT_VERSION,
            SCHEMA to version.toString(),
            CREATED to clock.millis().toString(),
            SETTINGS to settings,
        )
        info.forEach { (key, value) -> db.execSQL("INSERT INTO backup.$INFO_TABLE (key, value) VALUES (?, ?)", arrayOf<Any>(key, value)) }
    }

    override suspend fun check(source: String, password: String): BackupCheck = withContext(Dispatchers.IO) {
        mutex.withLock {
            discardLocked()
            val temp = File(cacheDir, CHECK_TEMP)
            val prepared = File(cacheDir, PREPARED)
            try {
                files.copyFrom(source, temp)
                val currentOperations = currentOperations()
                val flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY
                SQLiteDatabase.openDatabase(temp.path, password.toByteArray(Charsets.UTF_8), null, flags, null, null).use { db ->
                    val info = readInfo(db) ?: return@withLock BackupCheck.NotBackup
                    if (info[APP] != APP_ID) return@withLock BackupCheck.NotBackup
                    // Схема меньше 1 — не копия приложения: Room принял бы базу за пустую и упал на создании таблиц.
                    val schema = info[SCHEMA]?.toIntOrNull()?.takeIf { it >= 1 } ?: return@withLock BackupCheck.NotBackup
                    if (schema > BudgetDatabase.VERSION || (info[FORMAT]?.toIntOrNull() ?: Int.MAX_VALUE) > FORMAT_VERSION.toInt()) {
                        return@withLock BackupCheck.TooNew
                    }
                    val backupOperations = count(db, "operations")
                    // Сразу в ключ телефона: пароль дальше не нужен и в памяти не держится.
                    deleteWithJournal(prepared)
                    db.execSQL("ATTACH DATABASE ? AS restore KEY ?", arrayOf<Any>(prepared.path, String(encryption.passphrase(), Charsets.US_ASCII)))
                    db.rawQuery("SELECT sqlcipher_export('restore')", emptyArray<String>()).use { it.moveToFirst() }
                    db.execSQL("PRAGMA restore.user_version = $schema")
                    db.execSQL("DROP TABLE restore.$INFO_TABLE")
                    db.execSQL("DETACH DATABASE restore")
                    checked = prepared to info[SETTINGS].orEmpty().ifEmpty { "{}" }
                    BackupCheck.Valid(Instant.ofEpochMilli(info[CREATED]?.toLongOrNull() ?: 0), backupOperations, currentOperations)
                }
            } catch (e: SQLiteException) {
                // Неверный пароль, повреждённый или вовсе не SQLite-файл — по зашифрованному файлу их не различить.
                deleteWithJournal(prepared)
                BackupCheck.WrongPassword
            } finally {
                deleteWithJournal(temp)
            }
        }
    }

    override suspend fun scheduleRestore(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (prepared, settings) = checked ?: return@withLock false
            checked = null
            encryption.scheduleRestore(prepared, settings)
            true
        }
    }

    override suspend fun discard() = withContext(Dispatchers.IO) { mutex.withLock { discardLocked() } }

    private fun discardLocked() {
        checked?.first?.let(::deleteWithJournal)
        checked = null
    }

    /** Операций на телефоне сейчас — для подтверждения замены; базы или таблицы ещё может не быть. */
    private fun currentOperations(): Int {
        if (!encryption.dbFile.exists()) return 0
        return SQLiteDatabase.openDatabase(encryption.dbFile.path, encryption.passphrase(), null, SQLiteDatabase.OPEN_READONLY, null, null).use { db ->
            if (hasTable(db, "operations")) count(db, "operations") else 0
        }
    }

    /**
     * Отдельное соединение с текущей базой: Room держит своё, режим WAL позволяет читать одновременно. Флаг WAL
     * обязателен: без него соединение пытается вернуть журнал в DELETE и пишет в журнал «database is locked».
     */
    private fun openMain(): SQLiteDatabase {
        val flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
        return SQLiteDatabase.openDatabase(encryption.dbFile.path, encryption.passphrase(), null, flags, null, null)
    }

    private fun readInfo(db: SQLiteDatabase): Map<String, String>? {
        if (!hasTable(db, INFO_TABLE)) return null
        return db.rawQuery("SELECT key, value FROM $INFO_TABLE", emptyArray<String>()).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }
    }

    private fun hasTable(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)).use { c ->
            c.moveToFirst() && c.getInt(0) > 0
        }

    private fun count(db: SQLiteDatabase, table: String): Int =
        db.rawQuery("SELECT count(*) FROM $table", emptyArray<String>()).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun deleteWithJournal(file: File) {
        for (suffix in listOf("", "-journal", "-wal", "-shm")) File(file.path + suffix).delete()
    }

    companion object {
        const val MIN_PASSWORD_LENGTH = 8
        private const val BACKUP_TEMP = "backup.tmp"
        private const val CHECK_TEMP = "restore-check.tmp"
        private const val PREPARED = "restore-prepared.db"
        private const val INFO_TABLE = "backup_info"
        private const val APP = "app"
        private const val APP_ID = "ru.hznik.budget"
        private const val FORMAT = "format"
        private const val FORMAT_VERSION = "1"
        private const val SCHEMA = "schema"
        private const val CREATED = "created_at"
        private const val SETTINGS = "settings"
    }
}

/** Отметка «удалить все данные»: само удаление — при следующем запуске до открытия Room (DatabaseEncryption). */
class ScheduledDataWipe @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val encryption: DatabaseEncryption,
) : DataWipe {
    override suspend fun schedule() = withContext(Dispatchers.IO) {
        NotificationManagerCompat.from(context).cancelAll()
        encryption.scheduleWipe()
    }
}

/**
 * Перезапуск процесса: стартовый экран выбирается заново, в памяти не остаётся прежнего состояния (защита входа,
 * буфер отмены).
 */
class ProcessRestarter @Inject constructor(@param:ApplicationContext private val context: Context) : AppRestarter {
    override fun restart() {
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}

/** Отдельный модуль: в UI-тестах копия, удаление и перезапуск подменяются. */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackupModule {
    @Binds
    abstract fun bindBackupStore(impl: SqlCipherBackupStore): BackupStore

    @Binds
    abstract fun bindDataWipe(impl: ScheduledDataWipe): DataWipe

    @Binds
    abstract fun bindAppRestarter(impl: ProcessRestarter): AppRestarter
}
