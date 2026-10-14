package ru.hznik.budget.data.security

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException
import ru.hznik.budget.domain.security.DatabaseAccess
import ru.hznik.budget.domain.security.DatabaseState
import java.io.File
import javax.inject.Inject

/**
 * Проверка и перенос базы до первого обращения к Room (design.md add-data-protection, решения 2–4).
 * После [prepare] ключ живёт только в памяти процесса: его забирает фабрика Room через [passphrase].
 */
class DatabaseEncryption(
    private val dbFile: File,
    private val keyStore: DatabaseKeyStore,
) {
    enum class Outcome {
        /** Ключ есть, база открывается этим ключом или её ещё нет. */
        READY,

        /** Была открытая база, данные перенесены в зашифрованную. */
        MIGRATED,

        /** Перенос не удался: база осталась открытой, повтор при следующем запуске. */
        MIGRATION_FAILED,

        /** Файл зашифрован, а ключа нет или он не подходит. */
        KEY_LOST,

        /** Временный сбой (Keystore, диск, база занята): данные, возможно, целы, нужно повторить. */
        ERROR,
    }

    @Volatile
    private var passphrase: ByteArray? = null

    @Synchronized
    fun prepare(): Outcome {
        if (passphrase != null) return Outcome.READY
        return try {
            prepareOnce()
        } catch (e: Exception) {
            // Например, нет места для файла ключа. Открытая база остаётся рабочей, иначе — повторить.
            Log.w(TAG, "Подготовка базы не удалась: ${e.javaClass.simpleName}")
            if (runCatching { dbFile.exists() && isPlaintext(dbFile) }.getOrDefault(false)) usePlaintext() else Outcome.ERROR
        }
    }

    private fun prepareOnce(): Outcome {
        loadNativeLibrary()
        if (dbFile.exists() && dbFile.length() == 0L) deleteDatabaseFiles()
        val exists = dbFile.exists()
        val plaintext = exists && isPlaintext(dbFile)
        val key = when (val stored = keyStore.read()) {
            is DatabaseKeyStore.Result.Present -> stored.key
            DatabaseKeyStore.Result.Missing, DatabaseKeyStore.Result.Unreadable ->
                if (exists && !plaintext) return Outcome.KEY_LOST else keyStore.create()
            // Ключ, возможно, цел: новый не создаётся, чтобы не затереть его.
            DatabaseKeyStore.Result.Failed -> return if (plaintext) usePlaintext() else Outcome.ERROR
        }
        val raw = rawKey(key)
        if (!exists) {
            passphrase = raw
            return Outcome.READY
        }
        if (plaintext) {
            return if (migrate(raw)) {
                passphrase = raw
                Outcome.MIGRATED
            } else {
                usePlaintext()
            }
        }
        return when (opensWith(dbFile, raw)) {
            OpenCheck.OK -> {
                passphrase = raw
                Outcome.READY
            }
            OpenCheck.WRONG_KEY -> Outcome.KEY_LOST
            OpenCheck.FAILED -> Outcome.ERROR
        }
    }

    /** Пустой пароль: SQLCipher открывает обычную базу SQLite, приложение работает с прежними данными до следующего запуска. */
    private fun usePlaintext(): Outcome {
        passphrase = ByteArray(0)
        return Outcome.MIGRATION_FAILED
    }

    /** Пароль для `SupportOpenHelperFactory`; до [prepare] обращаться к базе нельзя. */
    fun passphrase(): ByteArray = checkNotNull(passphrase) { "База открыта до проверки ключа" }.copyOf()

    /** «Начать заново»: удаляет базу и ключ. Новый ключ создаст следующий [prepare]. */
    @Synchronized
    fun reset() {
        passphrase = null
        deleteDatabaseFiles()
        keyStore.delete()
    }

    private fun migrate(raw: ByteArray): Boolean {
        val encrypted = File(dbFile.path + ENCRYPTED_SUFFIX)
        encrypted.delete()
        return try {
            val expected: Map<String, Long>
            val version: Int
            // CREATE_IF_NECESSARY: ATTACH наследует флаги соединения и без него не создаст зашифрованный файл.
            val flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY
            SQLiteDatabase.openDatabase(dbFile.path, "", null, flags, null).use { plain ->
                // Всё из WAL — в основной файл: после переноса старые -wal и -shm удаляются.
                plain.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray<String>()).use { it.moveToFirst() }
                version = plain.version
                expected = rowCounts(plain)
                plain.execSQL("ATTACH DATABASE ? AS encrypted KEY ?", arrayOf<Any>(encrypted.path, String(raw)))
                plain.rawQuery("SELECT sqlcipher_export('encrypted')", emptyArray<String>()).use { it.moveToFirst() }
                // sqlcipher_export не переносит номер версии схемы, без него Room запустил бы миграции заново.
                plain.execSQL("PRAGMA encrypted.user_version = $version")
                plain.execSQL("DETACH DATABASE encrypted")
            }
            val ok = SQLiteDatabase.openDatabase(encrypted.path, raw, null, SQLiteDatabase.OPEN_READONLY, null, null).use { db ->
                db.version == version && rowCounts(db) == expected
            }
            check(ok) { "Число строк после переноса не совпало" }
            for (suffix in listOf("-wal", "-shm", "-journal")) File(dbFile.path + suffix).delete()
            check(encrypted.renameTo(dbFile)) { "Не удалось заменить файл базы" }
            true
        } catch (e: Exception) {
            encrypted.delete()
            File(encrypted.path + "-journal").delete()
            // Только тип ошибки: в сообщениях SQLite могут оказаться данные.
            Log.w(TAG, "Перенос базы не удался: ${e.javaClass.simpleName}")
            false
        }
    }

    private enum class OpenCheck { OK, WRONG_KEY, FAILED }

    /**
     * Неверный ключ SQLCipher сообщает как SQLITE_NOTADB («file is not a database»): это [SQLiteNotADatabaseException].
     * Остальные ошибки (занята, не открывается) — не повод говорить о потере ключа.
     */
    private fun opensWith(file: File, raw: ByteArray): OpenCheck = try {
        SQLiteDatabase.openDatabase(file.path, raw, null, SQLiteDatabase.OPEN_READWRITE, null, null).use { db ->
            db.rawQuery("SELECT count(*) FROM sqlite_master", emptyArray<String>()).use { it.moveToFirst() }
        }
        OpenCheck.OK
    } catch (e: SQLiteNotADatabaseException) {
        OpenCheck.WRONG_KEY
    } catch (e: Exception) {
        Log.w(TAG, "Проверка базы не удалась: ${e.javaClass.simpleName}")
        OpenCheck.FAILED
    }

    private fun rowCounts(db: SQLiteDatabase): Map<String, Long> {
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'", emptyArray<String>()).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return tables.associateWith { name ->
            db.rawQuery("SELECT count(*) FROM \"$name\"", emptyArray<String>()).use { c -> c.moveToFirst(); c.getLong(0) }
        }
    }

    private fun deleteDatabaseFiles() {
        for (suffix in listOf("", "-wal", "-shm", "-journal", ENCRYPTED_SUFFIX)) File(dbFile.path + suffix).delete()
    }

    companion object {
        private const val TAG = "DatabaseEncryption"
        private const val ENCRYPTED_SUFFIX = ".enc"
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        private val nativeLoaded by lazy { System.loadLibrary("sqlcipher") }

        fun loadNativeLibrary() = nativeLoaded

        /** Заголовок обычной базы SQLite; у SQLCipher первые байты зашифрованы. */
        fun isPlaintext(file: File): Boolean {
            val header = ByteArray(SQLITE_HEADER.size)
            val read = file.inputStream().use { it.read(header) }
            return read == header.size && header.contentEquals(SQLITE_HEADER)
        }

        /** Сырой ключ `x'…'`: SQLCipher не прогоняет его через PBKDF2, ключ и так случайный. */
        fun rawKey(key: ByteArray): ByteArray =
            ("x'" + key.joinToString("") { "%02X".format(it) } + "'").toByteArray(Charsets.US_ASCII)
    }
}

class EncryptedDatabaseAccess @Inject constructor(
    private val encryption: DatabaseEncryption,
    private val dataStore: DataStore<Preferences>,
) : DatabaseAccess {
    override suspend fun open(): DatabaseState = withContext(Dispatchers.IO) {
        when (encryption.prepare()) {
            DatabaseEncryption.Outcome.KEY_LOST -> DatabaseState.KEY_LOST
            DatabaseEncryption.Outcome.ERROR -> DatabaseState.ERROR
            else -> DatabaseState.READY
        }
    }

    override suspend fun wipe() = withContext(Dispatchers.IO) {
        encryption.reset()
        // Файл DataStore открыт синглтоном, поэтому настройки очищаются, а не удаляются.
        runCatching { dataStore.edit { it.clear() } }
        Unit
    }
}
