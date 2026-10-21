package ru.hznik.budget.data.backup

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.budget.R
import ru.hznik.budget.data.local.ALL_MIGRATIONS
import ru.hznik.budget.data.local.BudgetDatabase
import ru.hznik.budget.data.security.DatabaseEncryption
import ru.hznik.budget.data.security.DatabaseKeyStore
import ru.hznik.budget.data.security.EncryptedDatabaseAccess
import ru.hznik.budget.data.settings.DataStoreLockSettingsRepository
import ru.hznik.budget.domain.export.BackupCheck
import ru.hznik.budget.domain.export.UserFileInfo
import ru.hznik.budget.domain.export.UserFiles
import ru.hznik.budget.domain.security.DatabaseState
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Резервная копия и восстановление на настоящей SQLCipher (spec data-export «Резервная копия», «Восстановление
 * из копии», «Удаление всех данных»; задачи 3.1–3.3, 4.1). «Перезапуск» — новый экземпляр DatabaseEncryption.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alias = "test_backup_wrap_${System.nanoTime()}"
    private val clock = Clock.fixed(Instant.parse("2026-10-17T09:00:00Z"), ZoneOffset.ofHours(3))
    private lateinit var dir: File
    private lateinit var dbFile: File
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var encryption: DatabaseEncryption
    private var room: BudgetDatabase? = null

    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BudgetDatabase::class.java)

    /** Адреса файлов в тесте — просто пути. */
    private val files = object : UserFiles {
        override suspend fun writeText(target: String, text: String) = File(target).writeText(text)
        override suspend fun copyTo(target: String, file: File) {
            file.copyTo(File(target), overwrite = true)
        }
        override suspend fun copyFrom(source: String, file: File) {
            File(source).copyTo(file, overwrite = true)
        }
        override suspend fun delete(target: String) {
            File(target).delete()
        }
        override suspend fun describe(source: String) = UserFileInfo(File(source).name, File(source).length())
    }

    private fun keyStore() = DatabaseKeyStore(File(dir, "db_key.bin"), alias)

    private fun newEncryption() = DatabaseEncryption(dbFile, keyStore())

    private fun store() = SqlCipherBackupStore(context, encryption, files, dataStore, clock)

    private fun openRoom(): BudgetDatabase = Room.databaseBuilder(context, BudgetDatabase::class.java, dbFile.absolutePath)
        .openHelperFactory(SupportOpenHelperFactory(encryption.passphrase()))
        .addCallback(BudgetDatabase.SeedCallback)
        .addMigrations(*ALL_MIGRATIONS)
        .build()
        .also { room = it }

    private fun BudgetDatabase.exec(sql: String, vararg args: Any?) = openHelper.writableDatabase.execSQL(sql, args)

    private fun BudgetDatabase.count(table: String): Long =
        openHelper.readableDatabase.query("SELECT count(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }

    private fun BudgetDatabase.addOperations(n: Int) = repeat(n) { i ->
        exec(
            "INSERT INTO operations (type, amount_kopecks, category_id, merchant, comment, search_text, source, occurred_at, " +
                "utc_offset_seconds, local_date, created_at) VALUES ('EXPENSE', ?, 1, 'Пятёрочка', NULL, '', 'MANUAL', 0, 10800, 20740, 0)",
            10_000L + i,
        )
    }

    private val incomeKind = stringPreferencesKey("income_kind")
    private val lockEnabled = booleanPreferencesKey("lock_enabled")
    private val autoImportWasEnabled = booleanPreferencesKey("auto_import_was_enabled")

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "backup_test_${System.nanoTime()}").apply { mkdirs() }
        dbFile = File(dir, "budget.db")
        scope = CoroutineScope(Dispatchers.IO + Job())
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") }
        encryption = newEncryption()
        assertEquals(DatabaseEncryption.Outcome.READY, encryption.prepare())
    }

    @After
    fun tearDown() {
        room?.close()
        scope.cancel()
        keyStore().delete()
        dir.deleteRecursively()
    }

    private fun backupFile() = File(dir, "budget-backup.bpbackup")

    /** Копия с [n] операциями и настройками «регулярный доход, защита включена». */
    private fun createBackup(n: Int, password: String = PASSWORD): File = runBlocking {
        val db = openRoom()
        db.addOperations(n)
        db.exec("INSERT INTO notification_samples (bank_id, posted_at, text) VALUES ('sber', 0, 'образец')")
        dataStore.edit {
            it[incomeKind] = "REGULAR"
            it[lockEnabled] = true
            it[autoImportWasEnabled] = true
        }
        store().create(backupFile().path, password)
        backupFile()
    }

    @Test
    fun backupIsReadableOnlyWithItsPassword() {
        val file = createBackup(120)
        assertTrue(file.length() > 0)
        assertFalse(opens(file, ""))
        assertFalse(opens(file, "неверный пароль"))
        SQLiteDatabase.openDatabase(file.path, PASSWORD.toByteArray(), null, SQLiteDatabase.OPEN_READONLY, null, null).use { db ->
            assertEquals(120L, db.longQuery("SELECT count(*) FROM operations"))
            // Образцы уведомлений в копию не попадают.
            assertEquals(0L, db.longQuery("SELECT count(*) FROM notification_samples"))
            assertEquals(1L, db.longQuery("SELECT count(*) FROM backup_info WHERE key = 'app' AND value = 'ru.hznik.budget'"))
        }
        // Временных файлов копии не остаётся.
        assertFalse(File(context.cacheDir, "backup.tmp").exists())
    }

    @Test
    fun wrongPasswordForeignFileAndNewerBackupChangeNothing() = runBlocking {
        val file = createBackup(3)
        val store = store()
        assertEquals(BackupCheck.WrongPassword, store.check(file.path, "неверный пароль"))

        val random = File(dir, "random.bin").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        assertEquals(BackupCheck.WrongPassword, store.check(random.path, PASSWORD))

        val foreign = File(dir, "foreign.db")
        SQLiteDatabase.openOrCreateDatabase(foreign.path, PASSWORD.toByteArray(), null, null, null).use { it.execSQL("CREATE TABLE notes (text TEXT)") }
        assertEquals(BackupCheck.NotBackup, store.check(foreign.path, PASSWORD))

        SQLiteDatabase.openDatabase(file.path, PASSWORD.toByteArray(), null, SQLiteDatabase.OPEN_READWRITE, null, null).use {
            it.execSQL("UPDATE backup_info SET value = '99' WHERE key = 'schema'")
        }
        assertEquals(BackupCheck.TooNew, store.check(file.path, PASSWORD))

        SQLiteDatabase.openDatabase(file.path, PASSWORD.toByteArray(), null, SQLiteDatabase.OPEN_READWRITE, null, null).use {
            it.execSQL("UPDATE backup_info SET value = '0' WHERE key = 'schema'")
        }
        assertEquals(BackupCheck.NotBackup, store.check(file.path, PASSWORD))

        assertFalse(store.scheduleRestore())
        assertFalse(encryption.pendingRestore.exists())
        assertEquals(3L, room!!.count("operations"))
    }

    @Test
    fun restoreReplacesDataAfterRestartAndKeepsThisPhonesSettings() = runBlocking {
        val file = createBackup(120)
        val db = room!!
        // После копии данные на телефоне изменились.
        db.exec("DELETE FROM operations")
        db.addOperations(5)
        dataStore.edit {
            it[incomeKind] = "IRREGULAR"
            it[lockEnabled] = false
        }
        val store = store()
        val check = store.check(file.path, PASSWORD)
        assertEquals(BackupCheck.Valid(clock.instant(), backupOperations = 120, currentOperations = 5), check)
        assertTrue(store.scheduleRestore())
        assertTrue(encryption.pendingRestore.exists())

        // «Перезапуск»: Room закрыт, новый процесс проверяет базу до открытия Room.
        db.close()
        room = null
        encryption = newEncryption()
        assertEquals(DatabaseState.READY, EncryptedDatabaseAccess(encryption, dataStore).open())
        assertEquals(120L, openRoom().count("operations"))
        val settings = dataStore.data.first()
        assertEquals("REGULAR", settings[incomeKind])
        assertEquals(true, settings[lockEnabled])
        // Защиту из копии читает то же хранилище, что и приложение: на телефоне без блокировки экрана она выключится
        // с сообщением (задача 3.5, ScreenLockRemovedFlowTest).
        assertTrue(DataStoreLockSettingsRepository(dataStore).observe().first().enabled)
        // Настройка этого телефона, которой нет в копии, осталась.
        assertEquals(true, settings[autoImportWasEnabled])
        assertFalse(encryption.pendingRestore.exists())
        assertFalse(encryption.pendingSettings.exists())
    }

    /** Копия с 120 операциями заменила базу с 5 операциями; Room её ещё не открывал. */
    private suspend fun restoreOver5Operations() {
        val file = createBackup(120)
        val db = room!!
        db.exec("DELETE FROM operations")
        db.addOperations(5)
        val store = store()
        assertTrue(store.check(file.path, PASSWORD) is BackupCheck.Valid)
        assertTrue(store.scheduleRestore())
        db.close()
        room = null
        encryption = newEncryption()
        assertEquals(DatabaseState.READY, EncryptedDatabaseAccess(encryption, dataStore).open())
        assertTrue(encryption.restoreInProgress.exists())
    }

    /** Следующий запуск: новый процесс проверяет базу до Room. */
    private suspend fun restart() {
        room?.close()
        room = null
        encryption = newEncryption()
        assertEquals(DatabaseState.READY, EncryptedDatabaseAccess(encryption, dataStore).open())
    }

    @Test
    fun restoreNotConfirmedByRoomRollsBackOnNextStart() = runBlocking {
        restoreOver5Operations()
        // Приложение упало до того, как Room открыл копию (например, на миграции): прежние данные возвращаются.
        restart()
        assertEquals(5L, openRoom().count("operations"))
        assertFalse(encryption.restoreInProgress.exists())
        assertFalse(File(dbFile.path + ".old").exists())
    }

    @Test
    fun restoreConfirmedByRoomDeletesPreviousDatabase() = runBlocking {
        restoreOver5Operations()
        assertEquals(120L, openRoom().count("operations"))
        encryption.confirmOpened()
        assertFalse(File(dbFile.path + ".old").exists())
        restart()
        assertEquals(120L, openRoom().count("operations"))
    }

    @Test
    fun interruptedSwapBringsPreviousDatabaseBack() = runBlocking {
        openRoom().addOperations(7)
        room!!.close()
        room = null
        // Процесс умер посреди подмены: текущая база уже в .old, а на её месте ничего нет.
        for (suffix in listOf("", "-wal", "-shm")) File(dbFile.path + suffix).renameTo(File(dbFile.path + ".old" + suffix))
        restart()
        assertEquals(7L, openRoom().count("operations"))
    }

    @Test
    fun backupFromOlderSchemaIsMigratedOnRestore() = runBlocking {
        // Копия со схемой 6 (до истории помощника): база версии 6 под паролем и с backup_info.
        val name = "v6_${System.nanoTime()}.db"
        migrationHelper.createDatabase(name, 6).use { v6 ->
            v6.execSQL(
                "INSERT INTO operations (type, amount_kopecks, category_id, merchant, comment, search_text, source, occurred_at, " +
                    "utc_offset_seconds, local_date, created_at, is_draft) VALUES ('EXPENSE', 45000, 1, 'Магнит', NULL, '', 'MANUAL', 0, 10800, 20740, 0, 0)",
            )
        }
        val plain = context.getDatabasePath(name)
        val backup = File(dir, "v6.bpbackup")
        val flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY
        SQLiteDatabase.openDatabase(plain.path, "", null, flags, null).use { db ->
            db.execSQL("ATTACH DATABASE ? AS backup KEY ?", arrayOf<Any>(backup.path, PASSWORD))
            db.rawQuery("SELECT sqlcipher_export('backup')", emptyArray<String>()).use { it.moveToFirst() }
            db.execSQL("PRAGMA backup.user_version = 6")
            db.execSQL("CREATE TABLE backup.backup_info (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
            for ((k, v) in mapOf("app" to "ru.hznik.budget", "format" to "1", "schema" to "6", "created_at" to "0", "settings" to "{}")) {
                db.execSQL("INSERT INTO backup.backup_info (key, value) VALUES (?, ?)", arrayOf<Any>(k, v))
            }
            db.execSQL("DETACH DATABASE backup")
        }
        context.deleteDatabase(name)

        // Приложение уже открыто: база с категориями по умолчанию, но без операций.
        assertEquals(0L, openRoom().count("operations"))
        val store = store()
        val result = store.check(backup.path, PASSWORD)
        assertTrue("$result", result is BackupCheck.Valid)
        assertTrue(store.scheduleRestore())
        room!!.close()
        room = null
        encryption = newEncryption()
        assertEquals(DatabaseState.READY, EncryptedDatabaseAccess(encryption, dataStore).open())
        val restored = openRoom()
        assertEquals(1L, restored.count("operations"))
        // Миграция 6→7 добавила таблицу истории помощника.
        assertEquals(0L, restored.count("chat_messages"))
    }

    @Test
    fun damagedPreparedRestoreKeepsCurrentData() = runBlocking {
        openRoom().addOperations(7)
        room!!.close()
        room = null
        encryption.pendingRestore.writeBytes(ByteArray(8192) { 1 })
        encryption.pendingSettings.writeText("{}")
        encryption = newEncryption()
        assertEquals(DatabaseState.READY, EncryptedDatabaseAccess(encryption, dataStore).open())
        assertEquals(7L, openRoom().count("operations"))
        assertFalse(encryption.pendingRestore.exists())
        assertFalse(encryption.pendingSettings.exists())
    }

    @Test
    fun wipeOnNextStartDeletesDatabaseKeyAndSettings() = runBlocking {
        openRoom().addOperations(4)
        dataStore.edit { it[incomeKind] = "REGULAR" }
        val oldKey = encryption.passphrase()
        val notifications = showNotification()
        ScheduledDataWipe(context, encryption).schedule()
        // Показанные уведомления приложения со старыми суммами снимаются сразу.
        assertTrue(notifications.activeNotifications.isEmpty())
        notifications.deleteNotificationChannel(TEST_CHANNEL)
        room!!.close()
        room = null

        encryption = newEncryption()
        assertEquals(DatabaseState.READY, EncryptedDatabaseAccess(encryption, dataStore).open())
        assertFalse(encryption.pendingWipe.exists())
        assertFalse(oldKey.contentEquals(encryption.passphrase()))
        val fresh = openRoom()
        assertEquals(0L, fresh.count("operations"))
        // Категории по умолчанию созданы заново.
        assertTrue(fresh.count("categories") > 0)
        // Нет ни периода, ни расписания дохода: приложение открывается с онбординга.
        assertEquals(0L, fresh.count("periods"))
        assertNull(dataStore.data.first()[incomeKind])
    }

    /** Уведомление приложения на экране, как напоминание или «пришло N ₽». */
    private fun showNotification(): NotificationManager {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(TEST_CHANNEL, "test", NotificationManager.IMPORTANCE_LOW))
        manager.notify(1, Notification.Builder(context, TEST_CHANNEL).setSmallIcon(R.drawable.ic_info).setContentTitle("1 500 ₽").build())
        assertEquals(1, manager.activeNotifications.size)
        return manager
    }

    private fun opens(file: File, password: String): Boolean = runCatching {
        SQLiteDatabase.openDatabase(file.path, password.toByteArray(), null, SQLiteDatabase.OPEN_READONLY, null, null).use {
            it.longQuery("SELECT count(*) FROM sqlite_master")
        }
    }.isSuccess

    private fun SQLiteDatabase.longQuery(sql: String): Long = rawQuery(sql, emptyArray<String>()).use { it.moveToFirst(); it.getLong(0) }

    private companion object {
        const val PASSWORD = "осень2026!"
        const val TEST_CHANNEL = "wipe_test"
    }
}
