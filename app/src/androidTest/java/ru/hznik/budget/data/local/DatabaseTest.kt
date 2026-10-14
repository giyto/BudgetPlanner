package ru.hznik.budget.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.budget.data.repository.RoomCategoryRepository
import ru.hznik.budget.data.repository.RoomOperationRepository
import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.repository.OperationFilter
import ru.hznik.budget.domain.search.SearchQuery
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val today = LocalDate.of(2026, 10, 13)
    private val clock = Clock.fixed(today.atTime(12, 0).atZone(zone).toInstant(), zone)

    private lateinit var db: BudgetDatabase
    private lateinit var operations: RoomOperationRepository
    private lateinit var categories: RoomCategoryRepository

    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BudgetDatabase::class.java)

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BudgetDatabase::class.java)
            .addCallback(BudgetDatabase.SeedCallback)
            .build()
        operations = RoomOperationRepository(db.operationDao(), clock)
        categories = RoomCategoryRepository(db.categoryDao())
    }

    @After
    fun close() = db.close()

    private suspend fun categoryNamed(name: String, kind: CategoryKind = CategoryKind.EXPENSE): Category =
        categories.observeAll().first().first { it.name == name && it.kind == kind }

    private suspend fun add(
        category: Category,
        rubles: Long,
        kopecks: Long = 0,
        merchant: String? = null,
        comment: String? = null,
        source: OperationSource = OperationSource.MANUAL,
        date: LocalDate = today,
    ): Long = operations.insert(
        Operation(
            type = if (category.kind == CategoryKind.INCOME) OperationType.INCOME else OperationType.EXPENSE,
            amount = Money(rubles * 100 + kopecks), categoryId = category.id, merchant = merchant, comment = comment,
            source = source, occurredAt = LocalDateTime.of(date, java.time.LocalTime.NOON).atZone(zone).toOffsetDateTime(),
        ),
    )

    private suspend fun search(text: String, source: OperationSource? = null, categoryId: Long? = null) =
        operations.observe(OperationFilter(SearchQuery(text), source, categoryId)).first()

    @Test
    fun defaultCategoriesAreSeeded() = runTest {
        val all = categories.observeAll().first()
        assertEquals(10, all.count { it.kind == CategoryKind.EXPENSE })
        assertEquals(5, all.count { it.kind == CategoryKind.INCOME })
        assertTrue(all.single { it.name == "Переводы" }.isTransfers)
    }

    @Test
    fun cyrillicSearchIgnoresCase() = runTest {
        val cafe = categoryNamed("Кафе и рестораны")
        val products = categoryNamed("Продукты")
        add(cafe, 240, merchant = "Кофейня «Зерно»")
        add(products, 450, merchant = "Пятёрочка")
        add(products, 120, comment = "Зашёл в кафе за водой")

        assertEquals(setOf(240L * 100, 120L * 100), search("КАФЕ").map { it.operation.amount.kopecks }.toSet())
        assertEquals(listOf("Пятёрочка"), search("пятер").map { it.operation.merchant })
    }

    @Test
    fun numberSearchMatchesWholeRubles() = runTest {
        val products = categoryNamed("Продукты")
        add(products, 450)
        add(products, 450, kopecks = 90)
        add(products, 45)

        assertEquals(2, search("450").size)
    }

    @Test
    fun likeWildcardsAreLiteral() = runTest {
        val products = categoryNamed("Продукты")
        add(products, 100, comment = "скидка 50%")
        add(products, 100, comment = "скидка 500")

        assertEquals(listOf("скидка 50%"), search("50%").map { it.operation.comment })
    }

    @Test
    fun filtersCombineWithSearch() = runTest {
        val products = categoryNamed("Продукты")
        val cafe = categoryNamed("Кафе и рестораны")
        add(products, 1, merchant = "Пятёрочка")
        add(products, 2, merchant = "Пятёрочка", source = OperationSource.NOTIFICATION)
        add(cafe, 3, merchant = "Пятёрочка кафе")
        add(products, 4, merchant = "ВкусВилл")

        val result = search("пят", source = OperationSource.MANUAL, categoryId = products.id)
        assertEquals(listOf(100L), result.map { it.operation.amount.kopecks })
    }

    @Test
    fun newestFirst() = runTest {
        val products = categoryNamed("Продукты")
        add(products, 1, date = today.minusDays(2))
        add(products, 2, date = today)
        add(products, 3, date = today.minusDays(1))

        assertEquals(listOf(200L, 300L, 100L), search("").map { it.operation.amount.kopecks })
    }

    @Test
    fun usageCountsOnlyWindow() = runTest {
        val products = categoryNamed("Продукты")
        val cafe = categoryNamed("Кафе и рестораны")
        add(products, 1, date = today.minusDays(29))
        add(products, 1, date = today)
        add(cafe, 1, date = today.minusDays(30))

        assertEquals(mapOf(products.id to 2), operations.observeCategoryUsage(today.minusDays(29)).first())
    }

    @Test
    fun deleteAndRestoreKeepsId() = runTest {
        val products = categoryNamed("Продукты")
        val id = add(products, 450, merchant = "Пятёрочка")
        val original = operations.get(id)!!

        operations.delete(id)
        assertEquals(0, search("").size)
        operations.restore(original)

        assertEquals(original, operations.get(id))
    }

    @Test(expected = SQLiteConstraintException::class)
    fun categoryWithOperationsCannotBeDeleted() = runTest {
        val products = categoryNamed("Продукты")
        add(products, 1)
        assertTrue(categories.hasOperations(products.id))
        categories.delete(products.id)
    }

    @Test
    fun duplicateNameCheckIgnoresCaseWithinKind() = runTest {
        assertTrue(categories.nameExists(CategoryKind.EXPENSE, "ПРОДУКТЫ", excludeId = null))
        assertEquals(false, categories.nameExists(CategoryKind.INCOME, "Продукты", excludeId = null))
        val products = categoryNamed("Продукты")
        assertEquals(false, categories.nameExists(CategoryKind.EXPENSE, "продукты", excludeId = products.id))
    }

    @Test
    fun defaultsAreCreatedOnlyOnce() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "seed-once-test.db"
        context.deleteDatabase(name)
        repeat(2) {
            val fileDb = Room.databaseBuilder(context, BudgetDatabase::class.java, name)
                .addCallback(BudgetDatabase.SeedCallback)
                .build()
            val count = fileDb.openHelper.readableDatabase.query("SELECT COUNT(*) FROM categories").use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(15, count)
            fileDb.close()
        }
        context.deleteDatabase(name)
    }

    @Test
    fun exportedSchemaMatchesDatabase() {
        val name = "schema-test.db"
        migrationHelper.createDatabase(name, 1).close()
        migrationHelper.runMigrationsAndValidate(name, 1, true).close()
    }
}
