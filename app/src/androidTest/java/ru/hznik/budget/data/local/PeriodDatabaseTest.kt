package ru.hznik.budget.data.local

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.budget.data.repository.RoomCategoryRepository
import ru.hznik.budget.data.repository.RoomOperationRepository
import ru.hznik.budget.data.repository.RoomPeriodRepository
import ru.hznik.budget.data.repository.RoomPlannedPaymentRepository
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.PaymentRepeat
import ru.hznik.budget.domain.model.PlannedPayment
import ru.hznik.budget.domain.usecase.MarkPaymentPaid
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class PeriodDatabaseTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val today = LocalDate.of(2026, 10, 13)
    private val clock = Clock.fixed(today.atTime(12, 0).atZone(zone).toInstant(), zone)

    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BudgetDatabase::class.java)

    private lateinit var db: BudgetDatabase
    private lateinit var periods: RoomPeriodRepository
    private lateinit var payments: RoomPlannedPaymentRepository
    private lateinit var operations: RoomOperationRepository
    private lateinit var categories: RoomCategoryRepository

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BudgetDatabase::class.java)
            .addCallback(BudgetDatabase.SeedCallback)
            .build()
        periods = RoomPeriodRepository(db, db.periodDao(), db.plannedPaymentDao())
        payments = RoomPlannedPaymentRepository(db, db.plannedPaymentDao(), db.operationDao(), clock)
        operations = RoomOperationRepository(db.operationDao(), clock)
        categories = RoomCategoryRepository(db.categoryDao())
    }

    @After
    fun close() = db.close()

    private suspend fun otherCategoryId() = categories.observeAll().first().first { it.defaultKey == "other_expense" }.id

    @Test
    fun onlyOneCurrentPeriod() = runTest {
        periods.startNew(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 25), 100)
        periods.startNew(today, LocalDate.of(2026, 11, 25), 200)

        val all = db.periodDao().getAll()
        assertEquals(2, all.size)
        assertEquals(today.toEpochDay(), all.first().endDate)
        assertNull(all.last().endDate)
        assertEquals(200L, periods.getCurrent()!!.startAmountKopecks)
    }

    @Test
    fun markPaidMovesMonthlyAndDeletingExpenseUnpays() = runTest {
        val id = payments.save(
            PlannedPayment(name = "Интернет", amount = Money(65_000), dueDate = LocalDate.of(2026, 10, 18),
                repeat = PaymentRepeat.MONTHLY, categoryId = otherCategoryId()),
        )
        val opId = MarkPaymentPaid(payments, categories, clock)(id)!!

        val afterPay = payments.observeAll().first()
        assertEquals(listOf(LocalDate.of(2026, 10, 18), LocalDate.of(2026, 11, 18)), afterPay.map { it.dueDate })
        assertTrue(afterPay.first().isPaid)
        assertEquals(today, afterPay.first().paidOn)

        operations.delete(opId)
        assertFalse(payments.get(id)!!.isPaid)

        // Повторная оплата не создаёт второй платёж на 18 ноября.
        MarkPaymentPaid(payments, categories, clock)(id)
        assertEquals(listOf(LocalDate.of(2026, 10, 18), LocalDate.of(2026, 11, 18)), payments.observeAll().first().map { it.dueDate })
    }

    @Test
    fun newPeriodSavesPaymentsWithRepeatDay() = runTest {
        val payment = PlannedPayment(name = "Аренда", amount = Money(1_000_000), dueDate = LocalDate.of(2027, 2, 28),
            repeat = PaymentRepeat.MONTHLY, categoryId = otherCategoryId(), repeatDay = 31)
        periods.startNew(today, LocalDate.of(2026, 10, 25), 100, listOf(payment))
        assertEquals(31, payments.observeAll().first().single().repeatDay)
    }

    @Test
    fun categoryOfPaymentIsInUse() = runTest {
        val id = otherCategoryId()
        assertFalse(categories.isUsed(id))
        payments.save(PlannedPayment(name = "Связь", amount = Money(60_000), dueDate = today, repeat = PaymentRepeat.ONCE, categoryId = id))
        assertTrue(categories.isUsed(id))
    }

    @Test
    fun migrationFrom1KeepsOperationsAndCategories() {
        val name = "migration-test.db"
        migrationHelper.createDatabase(name, 1).use { v1 ->
            v1.execSQL(
                "INSERT INTO categories (kind, name, name_search, icon, color, default_key, sort_order, hidden) " +
                    "VALUES ('EXPENSE', 'Спорт', 'спорт', 'dumbbell', 'blue', NULL, 1, 0)",
            )
            v1.execSQL(
                "INSERT INTO operations (type, amount_kopecks, category_id, merchant, comment, search_text, source, occurred_at, " +
                    "utc_offset_seconds, local_date, created_at) VALUES ('EXPENSE', 45000, 1, NULL, 'зал', 'зал', 'MANUAL', 0, 10800, 20374, 0)",
            )
        }
        migrationHelper.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2).use { v2 ->
            v2.query("SELECT COUNT(*) FROM operations").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            v2.query("SELECT name FROM categories WHERE name = 'Спорт'").use { assertTrue(it.moveToFirst()) }
            v2.query("SELECT COUNT(*) FROM periods").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            v2.query("SELECT COUNT(*) FROM planned_payments").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
    }
}
