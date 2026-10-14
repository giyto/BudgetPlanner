package ru.hznik.budget.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.budget.data.repository.RoomCategoryRepository
import ru.hznik.budget.data.repository.RoomStatisticsRepository
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.stats.DateRange
import java.time.LocalDate
import kotlin.time.measureTime

/** Запросы статистики на настоящей SQLite (spec statistics: что считается тратой, скорость на 50 000 операций). */
@RunWith(AndroidJUnit4::class)
class StatisticsDaoTest {
    private lateinit var db: BudgetDatabase
    private lateinit var stats: RoomStatisticsRepository
    private val oct = DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 13))

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BudgetDatabase::class.java)
            .addCallback(BudgetDatabase.SeedCallback)
            .build()
        stats = RoomStatisticsRepository(db.statisticsDao())
    }

    @After
    fun close() = db.close()

    private suspend fun categoryId(key: String) = RoomCategoryRepository(db.categoryDao()).observeAll().first().first { it.defaultKey == key }.id

    private fun insert(type: String, kopecks: Long, categoryId: Long, date: LocalDate, merchant: String? = null) {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO operations (type, amount_kopecks, category_id, merchant, comment, search_text, source, occurred_at, " +
                "utc_offset_seconds, local_date, created_at) VALUES (?, ?, ?, ?, NULL, '', 'MANUAL', 0, 10800, ?, 0)",
            arrayOf<Any?>(type, kopecks, categoryId, merchant, date.toEpochDay()),
        )
    }

    @Test
    fun spendingSubtractsRefundsCountsTransfersAndSkipsIncome() = runTest {
        val clothes = categoryId("clothes")
        val transfers = categoryId("transfers")
        insert("EXPENSE", 300_000, clothes, LocalDate.of(2026, 10, 2))
        insert("REFUND", 100_000, clothes, LocalDate.of(2026, 10, 3))
        insert("TRANSFER", 50_000, transfers, LocalDate.of(2026, 10, 4))
        insert("INCOME", 1_000_000, categoryId("salary"), LocalDate.of(2026, 10, 5))

        assertEquals(mapOf(clothes to 200_000L, transfers to 50_000L), stats.spendingByCategory(oct).first())
        val totals = stats.totals(oct).first()
        assertEquals(1_000_000L, totals.incomeKopecks)
        assertEquals(250_000L, totals.spentKopecks)
    }

    @Test
    fun rangeBoundsAreInclusive() = runTest {
        val products = categoryId("products")
        insert("EXPENSE", 100, products, LocalDate.of(2026, 9, 30))
        insert("EXPENSE", 200, products, LocalDate.of(2026, 10, 1))
        insert("EXPENSE", 300, products, LocalDate.of(2026, 10, 13))
        insert("EXPENSE", 400, products, LocalDate.of(2026, 10, 14))
        assertEquals(mapOf(products to 500L), stats.spendingByCategory(oct).first())
        assertEquals(setOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 13)), stats.spendingByDay(oct).first().keys)
    }

    @Test
    fun topMerchantsIgnoreOperationsWithoutMerchant() = runTest {
        val products = categoryId("products")
        repeat(3) { insert("EXPENSE", 70_000, products, LocalDate.of(2026, 10, 2), "Пятёрочка") }
        insert("EXPENSE", 34_000, categoryId("cafe"), LocalDate.of(2026, 10, 3), "Кофейня")
        insert("EXPENSE", 900_000, products, LocalDate.of(2026, 10, 4))
        val top = stats.topMerchants(oct, 5).first()
        assertEquals(listOf("Пятёрочка", "Кофейня"), top.map { it.merchant })
        assertEquals(3, top.first().count)
        assertEquals(210_000L, top.first().kopecks)
    }

    @LargeTest
    @Test
    fun aggregatesOn50000OperationsAreFast() = runTest {
        val ids = RoomCategoryRepository(db.categoryDao()).observeAll().first().filter { it.kind == CategoryKind.EXPENSE }.map { it.id }
        val start = LocalDate.of(2024, 10, 14)
        val sqlite = db.openHelper.writableDatabase
        sqlite.beginTransaction()
        try {
            val statement = sqlite.compileStatement(
                "INSERT INTO operations (type, amount_kopecks, category_id, merchant, comment, search_text, source, occurred_at, " +
                    "utc_offset_seconds, local_date, created_at) VALUES ('EXPENSE', ?, ?, ?, NULL, '', 'MANUAL', 0, 10800, ?, 0)",
            )
            repeat(50_000) { i ->
                statement.clearBindings()
                statement.bindLong(1, 10_000L + i % 5_000)
                statement.bindLong(2, ids[i % ids.size])
                statement.bindString(3, "Магазин ${i % 300}")
                statement.bindLong(4, start.plusDays((i % 730).toLong()).toEpochDay())
                statement.executeInsert()
            }
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }

        val period = DateRange(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 13))
        val month = DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 13))
        val elapsed = measureTime {
            listOf(period, month).forEach { range ->
                stats.spendingByCategory(range).first()
                stats.topMerchants(range, 5).first()
                stats.spendingByDay(range).first()
                stats.totals(range).first()
            }
        }
        assertTrue("Агрегаты считались $elapsed", elapsed.inWholeMilliseconds < 1_000)
    }
}
