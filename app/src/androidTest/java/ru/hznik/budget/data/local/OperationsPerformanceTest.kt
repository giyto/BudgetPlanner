package ru.hznik.budget.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.budget.data.repository.RoomOperationRepository
import ru.hznik.budget.data.security.DatabaseEncryption
import ru.hznik.budget.data.security.DatabaseKeyStore
import ru.hznik.budget.domain.budget.BudgetCalculator
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.model.Period
import ru.hznik.budget.domain.repository.OperationFilter
import ru.hznik.budget.domain.search.SearchQuery
import ru.hznik.budget.ui.operations.OperationsViewModel
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.measureTime

/**
 * Скорость на 50 000 операций в зашифрованной базе (раздел 9 ТЗ, задача 7.2): первая выдача списка операций и расчёта
 * главной, обновление списка после вставки и поиск — меньше секунды. Если тест не проходит, нужны индексы (миграция 7→8).
 */
@RunWith(AndroidJUnit4::class)
class OperationsPerformanceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneOffset.ofHours(3)
    private val today = LocalDate.of(2026, 10, 13)
    private val clock = Clock.fixed(today.atTime(12, 0).toInstant(zone), zone)
    private val alias = "test_perf_${System.nanoTime()}"
    private lateinit var dir: File
    private lateinit var db: BudgetDatabase
    private lateinit var operations: RoomOperationRepository

    @Before
    fun setUp() = runBlocking {
        dir = File(context.cacheDir, "perf_test_${System.nanoTime()}").apply { mkdirs() }
        val encryption = DatabaseEncryption(File(dir, "budget.db"), DatabaseKeyStore(File(dir, "db_key.bin"), alias))
        encryption.prepare()
        db = Room.databaseBuilder(context, BudgetDatabase::class.java, File(dir, "budget.db").absolutePath)
            .openHelperFactory(SupportOpenHelperFactory(encryption.passphrase()))
            .addCallback(BudgetDatabase.SeedCallback)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        fill50000Operations(db)
        // Поиск идёт по нормализованному тексту магазина: заполняем его, как при обычной записи операции.
        db.openHelper.writableDatabase.execSQL("UPDATE operations SET search_text = 'магазин ' || ((id - 1) % 300)")
        operations = RoomOperationRepository(db.operationDao(), clock)
    }

    @After
    fun tearDown() {
        db.close()
        DatabaseKeyStore(File(dir, "db_key.bin"), alias).delete()
        dir.deleteRecursively()
    }

    /** Первая порция экрана операций (задача 7.2): экран не читает 50 000 строк разом. */
    private val screenPage = OperationFilter(limit = OperationsViewModel.PAGE_SIZE)

    private fun assertFast(what: String, elapsed: Duration) =
        assertTrue("$what: $elapsed", elapsed.inWholeMilliseconds < LIMIT_MS)

    @Test
    fun firstOperationsListIsFast() = runBlocking {
        val elapsed = measureTime { operations.observe(screenPage).first() }
        assertFast("Первая выдача списка операций", elapsed)
    }

    @Test
    fun homeCalculationIsFast() = runBlocking {
        val period = Period(startDate = LocalDate.of(2026, 9, 25), nextIncomeDate = LocalDate.of(2026, 10, 25), startAmountKopecks = 60_000_00)
        val elapsed = measureTime {
            BudgetCalculator.calculate(period, operations.observeFrom(period.startDate).first(), emptyList(), today)
        }
        assertFast("Расчёт главной", elapsed)
    }

    @Test
    fun listUpdatesQuicklyAfterInsert() = runBlocking {
        val flow = operations.observe(screenPage)
        flow.first()
        val updated = async(Dispatchers.IO) { withTimeout(10_000) { flow.drop(1).first { list -> list.any { it.operation.merchant == "Новая" } } } }
        val elapsed = measureTime {
            operations.insert(
                Operation(
                    type = OperationType.EXPENSE, amount = Money(450_00), categoryId = 1, merchant = "Новая",
                    source = OperationSource.MANUAL, occurredAt = clock.instant().atOffset(zone),
                ),
            )
            updated.await()
        }
        assertFast("Обновление списка после вставки", elapsed)
    }

    @Test
    fun searchIsFast() = runBlocking {
        val elapsed = measureTime {
            val found = operations.observe(OperationFilter(query = SearchQuery("Магазин 42"), limit = OperationsViewModel.PAGE_SIZE)).first()
            assertTrue(found.isNotEmpty())
        }
        assertFast("Поиск", elapsed)
    }

    private companion object {
        const val LIMIT_MS = 1_000
    }
}
