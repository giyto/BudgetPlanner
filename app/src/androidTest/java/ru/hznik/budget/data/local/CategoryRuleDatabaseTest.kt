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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.budget.data.repository.RoomCategoryRuleRepository
import ru.hznik.budget.domain.categorize.CategoryRule
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.repository.DraftConfirmation
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/** Миграция 5 → 6 и правила категорий на настоящей базе (add-auto-categorization, задачи 2.1–2.2). */
@RunWith(AndroidJUnit4::class)
class CategoryRuleDatabaseTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BudgetDatabase::class.java)

    private val zone = ZoneId.of("Europe/Moscow")
    private val now = Instant.parse("2026-10-16T09:00:00Z")
    private lateinit var db: BudgetDatabase
    private lateinit var repo: RoomCategoryRuleRepository

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BudgetDatabase::class.java)
            .addCallback(BudgetDatabase.SeedCallback).build()
        repo = RoomCategoryRuleRepository(db, db.categoryRuleDao(), db.operationDao(), Clock.fixed(now, zone))
    }

    @After
    fun close() = db.close()

    private suspend fun categoryId(key: String): Long = db.categoryDao().observeAll().first().first { it.defaultKey == key }.id

    private fun count(table: String): Int = db.openHelper.readableDatabase.query("SELECT count(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    private suspend fun draft(merchant: String): Long = db.operationDao().insert(
        Operation(
            type = OperationType.EXPENSE, amount = Money(450_00), categoryId = categoryId("other_expense"), merchant = merchant,
            source = OperationSource.NOTIFICATION, occurredAt = OffsetDateTime.ofInstant(now, zone), isDraft = true,
        ).toEntity(createdAt = 0),
    )

    @Test
    fun migrationFrom5AddsRulesTable() {
        val name = "migration-5-6.db"
        migrationHelper.createDatabase(name, 5).use { v5 ->
            v5.execSQL("INSERT INTO reconciliation_accounts (bank_id, card_last4, anchor_kopecks, anchor_at) VALUES ('alfa', '1234', 100, 1)")
        }
        migrationHelper.runMigrationsAndValidate(name, 6, true, MIGRATION_5_6).use { v6 ->
            v6.query("SELECT count(*) FROM reconciliation_accounts").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            v6.query("SELECT count(*) FROM category_rules").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
    }

    @Test
    fun rememberReplacesRuleOfTheSameStoreAndKind() = runTest {
        val products = categoryId("products")
        val cafe = categoryId("cafe")
        repo.confirmAndRemember(emptyList(), listOf(CategoryRule(merchantKey = "pyaterochka", kind = CategoryKind.EXPENSE, categoryId = products, merchantDisplay = "PYATEROCHKA")))
        repo.confirmAndRemember(emptyList(), listOf(CategoryRule(merchantKey = "pyaterochka", kind = CategoryKind.EXPENSE, categoryId = cafe, merchantDisplay = "Пятёрочка")))
        repo.confirmAndRemember(emptyList(), listOf(CategoryRule(merchantKey = "pyaterochka", kind = CategoryKind.INCOME, categoryId = categoryId("gift"), merchantDisplay = "PYATEROCHKA")))
        assertEquals(2, count("category_rules"))
        val expense = repo.find("pyaterochka", CategoryKind.EXPENSE)!!
        assertEquals(cafe, expense.categoryId)
        assertEquals("Пятёрочка", expense.merchantDisplay)
        assertEquals(2, repo.observeAll().first().size)
    }

    @Test
    fun deletingOwnCategoryDeletesItsRules() = runTest {
        val own = db.categoryDao().insert(CategoryEntity(kind = "EXPENSE", name = "Хобби", nameSearch = "хобби", icon = "dots", color = "gray", defaultKey = null, sortOrder = 99, hidden = false))
        repo.confirmAndRemember(emptyList(), listOf(CategoryRule(merchantKey = "лабиринт", kind = CategoryKind.EXPENSE, categoryId = own, merchantDisplay = "Лабиринт")))
        assertEquals(1, count("category_rules"))
        db.categoryDao().delete(own)
        assertEquals(0, count("category_rules"))
    }

    @Test
    fun confirmationAndRulesAreOneTransaction() = runTest {
        val id = draft("Ozon")
        val clothes = categoryId("clothes")
        // Правило ссылается на несуществующую категорию: внешний ключ отклоняет запись, подтверждение откатывается.
        val result = runCatching {
            repo.confirmAndRemember(
                listOf(DraftConfirmation(id, clothes, OperationType.EXPENSE)),
                listOf(CategoryRule(merchantKey = "ozon", kind = CategoryKind.EXPENSE, categoryId = 999_999, merchantDisplay = "Ozon")),
            )
        }
        assertTrue(result.isFailure)
        assertTrue(db.operationDao().get(id)!!.isDraft)
        assertNull(repo.find("ozon", CategoryKind.EXPENSE))
    }

    @Test
    fun deleteAndRestoreKeepTheSameRule() = runTest {
        repo.confirmAndRemember(emptyList(), listOf(CategoryRule(merchantKey = "ozon", kind = CategoryKind.EXPENSE, categoryId = categoryId("clothes"), merchantDisplay = "Ozon")))
        val rule = repo.find("ozon", CategoryKind.EXPENSE)!!
        repo.delete(rule.id)
        assertNull(repo.find("ozon", CategoryKind.EXPENSE))
        repo.restore(rule)
        assertEquals(rule, repo.find("ozon", CategoryKind.EXPENSE))
    }

    @Test
    fun restoreKeepsNewerRuleOfTheSameStore() = runTest {
        repo.confirmAndRemember(emptyList(), listOf(CategoryRule(merchantKey = "ozon", kind = CategoryKind.EXPENSE, categoryId = categoryId("clothes"), merchantDisplay = "Ozon")))
        val old = repo.find("ozon", CategoryKind.EXPENSE)!!
        repo.delete(old.id)
        // Пока шла отмена, тот же магазин запомнили снова — с другой категорией.
        repo.confirmAndRemember(emptyList(), listOf(CategoryRule(merchantKey = "ozon", kind = CategoryKind.EXPENSE, categoryId = categoryId("products"), merchantDisplay = "OZON")))
        repo.restore(old)
        assertEquals(1, count("category_rules"))
        assertEquals(categoryId("products"), repo.find("ozon", CategoryKind.EXPENSE)!!.categoryId)
    }
}
