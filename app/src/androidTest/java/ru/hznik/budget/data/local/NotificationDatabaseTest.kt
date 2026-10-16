package ru.hznik.budget.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.budget.data.notifications.DataStoreSampleSettings
import ru.hznik.budget.data.notifications.RoomNotificationRepository
import ru.hznik.budget.domain.notifications.BankNotification
import ru.hznik.budget.domain.security.DatabaseAccess
import ru.hznik.budget.domain.security.DatabaseState
import ru.hznik.budget.notifications.NotificationIntake
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Миграция 2 → 3 и приём уведомлений на настоящей базе (spec notification-import, задачи 1.1 и 2.3).
 */
@RunWith(AndroidJUnit4::class)
class NotificationDatabaseTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BudgetDatabase::class.java)

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val now = Instant.parse("2026-10-14T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("Europe/Moscow"))
    private lateinit var db: BudgetDatabase
    private lateinit var settingsFile: File

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, BudgetDatabase::class.java).addCallback(BudgetDatabase.SeedCallback).build()
        settingsFile = File(context.cacheDir, "notification_test_${System.nanoTime()}.preferences_pb")
    }

    @After
    fun close() {
        db.close()
        settingsFile.delete()
    }

    private fun intake(samplesOn: Boolean): NotificationIntake {
        val settings = DataStoreSampleSettings(PreferenceDataStoreFactory.create(produceFile = { settingsFile }))
        kotlinx.coroutines.runBlocking { settings.setEnabled(samplesOn) }
        val ready = object : DatabaseAccess {
            override suspend fun open() = DatabaseState.READY
            override suspend fun wipe() = Unit
        }
        return NotificationIntake(ready, { RoomNotificationRepository(db.notificationDao()) }, settings, emptySet(), { error("импорт не ожидается") }, { error("проверка не ожидается") }, clock)
    }

    private fun count(table: String): Int = db.openHelper.readableDatabase.query("SELECT count(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    private fun notification(packageName: String, text: String) =
        BankNotification(packageName, key = "0|$packageName|7|null|10100", postedAt = now, title = "Уведомление", text = text)

    @Test
    fun migrationFrom2KeepsDataAndAddsNotificationTables() {
        val name = "migration-2-3.db"
        migrationHelper.createDatabase(name, 2).use { v2 ->
            v2.execSQL(
                "INSERT INTO operations (type, amount_kopecks, category_id, merchant, comment, search_text, source, occurred_at, " +
                    "utc_offset_seconds, local_date, created_at) VALUES ('EXPENSE', 45000, 1, 'Пятёрочка', NULL, 'пятёрочка', 'MANUAL', 0, 10800, 20740, 0)",
            )
            v2.execSQL("INSERT INTO periods (start_date, next_income_date, end_date, start_amount_kopecks) VALUES (20730, 20751, NULL, 1800000)")
            v2.execSQL(
                "INSERT INTO planned_payments (name, amount_kopecks, due_date, repeat, repeat_day, category_id, paid_operation_id) " +
                    "VALUES ('Интернет', 65000, 20744, 'MONTHLY', 18, 1, NULL)",
            )
        }
        migrationHelper.runMigrationsAndValidate(name, 3, true, MIGRATION_2_3).use { v3 ->
            v3.query("SELECT is_draft, bank_id, notification_key, suggests_new_period, merchant FROM operations").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
                assertTrue(it.isNull(1))
                assertTrue(it.isNull(2))
                assertEquals(0, it.getInt(3))
                assertEquals("Пятёрочка", it.getString(4))
            }
            v3.query("SELECT count(*) FROM periods").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            v3.query("SELECT count(*) FROM planned_payments").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            v3.query("SELECT count(*) FROM notification_samples").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
    }

    @Test
    fun migrationFrom3AddsOwnTransferColumns() {
        val name = "migration-3-4.db"
        migrationHelper.createDatabase(name, 3).use { v3 ->
            v3.execSQL(
                "INSERT INTO operations (type, amount_kopecks, category_id, merchant, comment, search_text, source, occurred_at, " +
                    "utc_offset_seconds, local_date, created_at, is_draft, bank_id) VALUES ('TRANSFER', 500000, 1, NULL, NULL, '', 'NOTIFICATION', 0, 10800, 20740, 0, 1, 'alfa')",
            )
        }
        migrationHelper.runMigrationsAndValidate(name, 4, true, MIGRATION_3_4).use { v4 ->
            v4.query("SELECT bank_id, is_draft, original_type, counter_bank_id FROM operations").use {
                assertTrue(it.moveToFirst())
                assertEquals("alfa", it.getString(0))
                assertEquals(1, it.getInt(1))
                assertTrue(it.isNull(2))
                assertTrue(it.isNull(3))
            }
        }
    }

    /** 13.2: чужое уведомление не попадает ни в одну таблицу, даже при включённом режиме образцов. */
    @Test
    fun messengerNotificationLeavesNoTraceInAnyTable() = runTest {
        val before = listOf("operations", "bank_balances", "notification_keys", "notification_samples").associateWith(::count)
        intake(samplesOn = true).accept(notification("org.telegram.messenger", "Олег: перевёл тебе 500 ₽, карта 1234"))
        val after = listOf("operations", "bank_balances", "notification_keys", "notification_samples").associateWith(::count)
        assertEquals(before, after)
    }

    @Test
    fun sberNotificationIsStoredMaskedOnceWhenSamplesAreOn() = runTest {
        val intake = intake(samplesOn = true)
        intake.accept(notification("ru.sberbankmobile", "Покупка 450 ₽ Пятёрочка. Карта *1234"))
        intake.accept(notification("ru.sberbankmobile", "Покупка 450 ₽ Пятёрочка. Карта *1234"))
        val samples = RoomNotificationRepository(db.notificationDao()).observeSamples(now.minusSeconds(60)).first()
        assertEquals(listOf("Уведомление / Покупка 450 ₽ Пятёрочка. Карта *XXXX"), samples.map { it.text })
        assertFalse("текст с цифрами карты не сохранён", samples.single().text.contains("1234"))
    }

    @Test
    fun keysAreRememberedOnce() = runTest {
        val repository = RoomNotificationRepository(db.notificationDao())
        assertTrue(repository.markSeen("k1", "sber", now))
        assertFalse(repository.markSeen("k1", "sber", now))
    }
}
