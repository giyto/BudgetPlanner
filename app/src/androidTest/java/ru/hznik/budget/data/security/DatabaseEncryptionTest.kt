package ru.hznik.budget.data.security

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.budget.data.local.ALL_MIGRATIONS
import ru.hznik.budget.data.local.BudgetDatabase
import ru.hznik.budget.data.local.fill50000Operations
import ru.hznik.budget.data.local.measureAggregates
import ru.hznik.budget.data.repository.RoomStatisticsRepository
import java.io.File

/**
 * Ключ в Keystore, шифрование, перенос открытой базы и потеря ключа на настоящих файлах
 * (spec data-encryption, задачи 2.2, 3.3, 3.4). У каждого теста свои папка и имя ключа Keystore.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dir: File
    private lateinit var dbFile: File
    private lateinit var keyFile: File
    private val alias = "test_db_wrap_${System.nanoTime()}"

    private fun keyStore() = DatabaseKeyStore(keyFile, alias)

    private fun encryption() = DatabaseEncryption(dbFile, keyStore())

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "encryption_test_${System.nanoTime()}").apply { mkdirs() }
        dbFile = File(dir, "budget.db")
        keyFile = File(dir, "db_key.bin")
        DatabaseEncryption.loadNativeLibrary()
    }

    @After
    fun tearDown() {
        keyStore().delete()
        dir.deleteRecursively()
    }

    private fun room(factory: SupportSQLiteOpenHelper.Factory): BudgetDatabase =
        Room.databaseBuilder(context, BudgetDatabase::class.java, dbFile.absolutePath)
            .openHelperFactory(factory)
            .addCallback(BudgetDatabase.SeedCallback)
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    private fun BudgetDatabase.exec(sql: String, vararg args: Any?) = openHelper.writableDatabase.execSQL(sql, args)

    private fun BudgetDatabase.count(table: String): Long =
        openHelper.readableDatabase.query("SELECT count(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }

    /** Данные «как у пользователя»: 120 операций, два периода и платёж «Интернет». */
    private fun BudgetDatabase.fillUserData() {
        repeat(120) { i ->
            exec(
                "INSERT INTO operations (type, amount_kopecks, category_id, merchant, comment, search_text, source, occurred_at, " +
                    "utc_offset_seconds, local_date, created_at) VALUES ('EXPENSE', ?, 1, NULL, NULL, '', 'MANUAL', 0, 10800, ?, 0)",
                10_000L + i, 20_700L + i % 30,
            )
        }
        exec("INSERT INTO periods (start_date, next_income_date, end_date, start_amount_kopecks) VALUES (20690, 20720, 20720, 1800000)")
        exec("INSERT INTO periods (start_date, next_income_date, end_date, start_amount_kopecks) VALUES (20720, 20751, NULL, 2000000)")
        exec(
            "INSERT INTO planned_payments (name, amount_kopecks, due_date, repeat, repeat_day, category_id, paid_operation_id) " +
                "VALUES ('Интернет', 65000, 20740, 'MONTHLY', 18, 1, NULL)",
        )
    }

    // --- Ключ ---

    @Test
    fun keyIsCreatedOnceAndReadBack() {
        assertEquals(DatabaseKeyStore.Result.Missing, keyStore().read())
        val key = keyStore().create()
        assertEquals(32, key.size)
        val read = keyStore().read() as DatabaseKeyStore.Result.Present
        assertArrayEquals(key, read.key)
        assertFalse("ключ не лежит в файле открытым", keyFile.readBytes().toList().windowed(key.size).any { it == key.toList() })
    }

    @Test
    fun corruptedFileOrLostKeystoreKeyIsUnreadableNotACrash() {
        keyStore().create()
        keyFile.writeBytes(keyFile.readBytes().also { it[it.size - 1] = (it.last() + 1).toByte() })
        assertEquals(DatabaseKeyStore.Result.Unreadable, keyStore().read())

        keyStore().create()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        assertEquals(DatabaseKeyStore.Result.Unreadable, keyStore().read())
    }

    // --- Новая установка ---

    @Test
    fun freshInstallCreatesEncryptedDatabase() {
        val encryption = encryption()
        assertEquals(DatabaseEncryption.Outcome.READY, encryption.prepare())
        room(SupportOpenHelperFactory(encryption.passphrase())).apply {
            fillUserData()
            assertEquals(120, count("operations"))
            close()
        }
        assertFalse("заголовок не SQLite format 3", DatabaseEncryption.isPlaintext(dbFile))
        // Повторный запуск: тот же ключ открывает базу.
        val again = encryption()
        assertEquals(DatabaseEncryption.Outcome.READY, again.prepare())
        room(SupportOpenHelperFactory(again.passphrase())).apply {
            assertEquals(120, count("operations"))
            close()
        }
    }

    // --- Перенос ---

    @Test
    fun plaintextDatabaseIsMigratedWithAllRows() {
        room(FrameworkSQLiteOpenHelperFactory()).apply {
            fillUserData()
            close()
        }
        assertTrue(DatabaseEncryption.isPlaintext(dbFile))

        val encryption = encryption()
        assertEquals(DatabaseEncryption.Outcome.MIGRATED, encryption.prepare())
        assertFalse(DatabaseEncryption.isPlaintext(dbFile))
        assertFalse(File(dbFile.path + ".enc").exists())

        // Room открывает без миграций: версия схемы перенесена, строки на месте.
        room(SupportOpenHelperFactory(encryption.passphrase())).apply {
            assertEquals(120, count("operations"))
            assertEquals(2, count("periods"))
            assertEquals(1, count("planned_payments"))
            assertTrue(count("categories") > 0)
            assertEquals(2, openHelper.readableDatabase.version)
            close()
        }
    }

    @Test
    fun failedMigrationKeepsPlaintextAndRetriesNextLaunch() {
        room(FrameworkSQLiteOpenHelperFactory()).apply {
            fillUserData()
            close()
        }
        // Папка на месте временного файла: ATTACH не сможет создать зашифрованную копию.
        val blocker = File(dbFile.path + ".enc").apply { mkdirs(); File(this, "x").writeText("x") }
        val first = encryption()
        assertEquals(DatabaseEncryption.Outcome.MIGRATION_FAILED, first.prepare())
        assertTrue(DatabaseEncryption.isPlaintext(dbFile))
        room(SupportOpenHelperFactory(first.passphrase())).apply {
            assertEquals(120, count("operations"))
            close()
        }

        blocker.deleteRecursively()
        assertEquals(DatabaseEncryption.Outcome.MIGRATED, encryption().prepare())
        assertFalse(DatabaseEncryption.isPlaintext(dbFile))
    }

    // --- Потеря ключа ---

    @Test
    fun missingOrWrongKeyIsKeyLostAndResetStartsOver() {
        val encryption = encryption()
        encryption.prepare()
        room(SupportOpenHelperFactory(encryption.passphrase())).apply {
            fillUserData()
            close()
        }

        // Чужой ключ: другой файл ключа и другой ключ Keystore.
        val otherAlias = "${alias}_other"
        val wrong = DatabaseKeyStore(File(dir, "other_key.bin"), otherAlias).also { it.create() }
        assertEquals(DatabaseEncryption.Outcome.KEY_LOST, DatabaseEncryption(dbFile, wrong).prepare())
        wrong.delete()

        keyStore().delete()
        val lost = encryption()
        assertEquals(DatabaseEncryption.Outcome.KEY_LOST, lost.prepare())
        assertTrue("без подтверждения файл остаётся", dbFile.exists())

        lost.reset()
        assertFalse(dbFile.exists())
        assertEquals(DatabaseEncryption.Outcome.READY, lost.prepare())
        room(SupportOpenHelperFactory(lost.passphrase())).apply {
            assertEquals(0, count("operations"))
            close()
        }
        assertEquals(DatabaseEncryption.Outcome.READY, encryption().prepare())
    }

    // --- Процесс убит при открытой базе: остались непустые -wal и -shm ---

    /** Копия файлов базы, пока Room её держит открытой: так файлы выглядят после того, как система убила процесс. */
    private fun BudgetDatabase.copyFilesWhileOpen(to: File) {
        openHelper.writableDatabase.query("PRAGMA journal_mode").use { it.moveToFirst() }
        for (suffix in listOf("", "-wal", "-shm")) {
            val source = File(dbFile.path + suffix)
            if (source.exists()) source.copyTo(File(to.path + suffix), overwrite = true)
        }
        close()
    }

    @Test
    fun plaintextDatabaseWithUncheckpointedWalIsMigrated() {
        val killed = File(dir, "killed.db")
        room(FrameworkSQLiteOpenHelperFactory()).apply {
            fillUserData()
            assertTrue("данные в WAL, а не в основном файле", File(dbFile.path + "-wal").length() > 0)
            copyFilesWhileOpen(killed)
        }
        val encryption = DatabaseEncryption(killed, keyStore())
        assertEquals(DatabaseEncryption.Outcome.MIGRATED, encryption.prepare())
        Room.databaseBuilder(context, BudgetDatabase::class.java, killed.absolutePath)
            .openHelperFactory(SupportOpenHelperFactory(encryption.passphrase()))
            .build()
            .apply {
                assertEquals(120, count("operations"))
                assertEquals(1, count("planned_payments"))
                close()
            }
    }

    @Test
    fun encryptedDatabaseWithUncheckpointedWalIsReadyNotKeyLost() {
        val first = encryption()
        first.prepare()
        val killed = File(dir, "killed.db")
        room(SupportOpenHelperFactory(first.passphrase())).apply {
            fillUserData()
            copyFilesWhileOpen(killed)
        }
        val encryption = DatabaseEncryption(killed, keyStore())
        assertEquals(DatabaseEncryption.Outcome.READY, encryption.prepare())
        Room.databaseBuilder(context, BudgetDatabase::class.java, killed.absolutePath)
            .openHelperFactory(SupportOpenHelperFactory(encryption.passphrase()))
            .build()
            .apply {
                assertEquals(120, count("operations"))
                close()
            }
    }

    // --- Скорость ---

    @Test
    fun aggregatesOn50000OperationsInEncryptedDatabaseAreFast() = runTest {
        val encryption = encryption()
        encryption.prepare()
        val db = room(SupportOpenHelperFactory(encryption.passphrase()))
        try {
            fill50000Operations(db)
            val elapsed = measureAggregates(RoomStatisticsRepository(db.statisticsDao()))
            assertTrue("Агрегаты в зашифрованной базе считались $elapsed", elapsed.inWholeMilliseconds < 1_000)
        } finally {
            db.close()
        }
    }
}
