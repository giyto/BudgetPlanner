package ru.hznik.budget.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.budget.data.repository.RoomReconciliationRepository
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.reconcile.Mismatch
import ru.hznik.budget.domain.reconcile.ReconAccount
import ru.hznik.budget.domain.reconcile.ReconState
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/** Миграция 4 → 5 и расчёт ожидаемого остатка на настоящей базе (add-balance-reconciliation, задачи 1.2–1.4). */
@RunWith(AndroidJUnit4::class)
class ReconciliationDatabaseTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BudgetDatabase::class.java)

    private val zone = ZoneId.of("Europe/Moscow")
    private val anchorAt = Instant.parse("2026-10-14T09:00:00Z")
    private val to = anchorAt.plus(Duration.ofHours(5))
    private lateinit var db: BudgetDatabase
    private lateinit var repo: RoomReconciliationRepository
    private val alfa = ReconAccount("alfa", "1234")

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BudgetDatabase::class.java)
            .addCallback(BudgetDatabase.SeedCallback).build()
        repo = RoomReconciliationRepository(db.reconciliationDao(), Clock.fixed(anchorAt, zone))
    }

    @After
    fun close() = db.close()

    private suspend fun op(
        type: OperationType,
        rubles: Long,
        minutesAfterAnchor: Long,
        bank: String? = "alfa",
        card: String? = "1234",
        source: OperationSource = OperationSource.NOTIFICATION,
        draft: Boolean = false,
        original: OperationType? = null,
        counterBank: String? = null,
        counterCard: String? = null,
        counterMinutes: Long? = null,
    ) {
        val operation = Operation(
            type = type, amount = Money(rubles * 100), categoryId = 1, source = source,
            occurredAt = OffsetDateTime.ofInstant(anchorAt.plus(Duration.ofMinutes(minutesAfterAnchor)), zone),
            isDraft = draft, bankId = bank, cardLast4 = card, originalType = original,
            counterBankId = counterBank, counterCardLast4 = counterCard,
            counterOccurredAt = counterMinutes?.let { anchorAt.plus(Duration.ofMinutes(it)) },
        )
        db.operationDao().insert(operation.toEntity(createdAt = 0))
    }

    private fun count(table: String): Int = db.openHelper.readableDatabase.query("SELECT count(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun migrationFrom4AddsReconciliationTableAndKeepsBalances() {
        val name = "migration-4-5.db"
        migrationHelper.createDatabase(name, 4).use { v4 ->
            v4.execSQL("INSERT INTO bank_balances (bank_id, card_last4, balance_kopecks, observed_at) VALUES ('alfa', '1234', 800000, 1000)")
        }
        migrationHelper.runMigrationsAndValidate(name, 5, true, MIGRATION_4_5).use { v5 ->
            v5.query("SELECT bank_id, balance_kopecks FROM bank_balances").use {
                assertTrue(it.moveToFirst())
                assertEquals("alfa", it.getString(0))
                assertEquals(800000L, it.getLong(1))
            }
            v5.query("SELECT count(*) FROM reconciliation_accounts").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
    }

    @Test
    fun netChangeFollowsTheRulesOfExpectedBalance() = runTest {
        op(OperationType.EXPENSE, 450, 0) // пришла вместе с остатком точки отсчёта — не входит
        op(OperationType.EXPENSE, 100, 1)
        op(OperationType.INCOME, 50, 2, draft = true)
        op(OperationType.REFUND, 30, 3)
        op(OperationType.TRANSFER, 200, 4)
        op(OperationType.EXPENSE, 999, 5, source = OperationSource.ADJUSTMENT)
        op(OperationType.OWN_TRANSFER, 300, 6, original = OperationType.TRANSFER)
        op(OperationType.OWN_TRANSFER, 400, 7, bank = "sber", card = "5678", original = OperationType.TRANSFER, counterBank = "alfa", counterCard = "1234", counterMinutes = 8)
        op(OperationType.EXPENSE, 10, 300) // ровно в момент нового остатка — входит
        op(OperationType.EXPENSE, 5, 301) // после остатка — не входит
        op(OperationType.EXPENSE, 77, 9, card = "9999") // другая карта
        op(OperationType.INCOME, 20, 10, card = null) // без цифр
        op(OperationType.EXPENSE, 70, 11, bank = null, card = null, source = OperationSource.MANUAL)

        val base = -100L + 50 + 30 - 200 - 300 + 400 - 10
        assertEquals(base * 100, repo.netChange(alfa, includeNoDigits = false, from = anchorAt, to = to))
        assertEquals((base + 20) * 100, repo.netChange(alfa, includeNoDigits = true, from = anchorAt, to = to))
        assertEquals(-70_00L, repo.netChange(ReconAccount.MANUAL, includeNoDigits = false, from = anchorAt, to = to))
        assertEquals(-400_00L, repo.netChange(ReconAccount("sber", "5678"), includeNoDigits = false, from = anchorAt, to = to))
        assertEquals(20_00L, repo.netChange(ReconAccount("alfa", null), includeNoDigits = false, from = anchorAt, to = to))
    }

    @Test
    fun knownAccountsAndLatestBalances() = runTest {
        op(OperationType.EXPENSE, 100, 1)
        op(OperationType.OWN_TRANSFER, 400, 2, bank = "sber", card = "5678", original = OperationType.TRANSFER, counterBank = "tbank", counterCard = null, counterMinutes = 3)
        op(OperationType.EXPENSE, 70, 4, bank = null, card = null, source = OperationSource.MANUAL)
        db.notificationDao().insertBalance(BankBalanceEntity(bankId = "alfa", cardLast4 = "1234", balanceKopecks = 900_00, observedAt = 1_000))
        db.notificationDao().insertBalance(BankBalanceEntity(bankId = "alfa", cardLast4 = "1234", balanceKopecks = 800_00, observedAt = 2_000))
        db.notificationDao().insertBalance(BankBalanceEntity(bankId = "alfa", cardLast4 = "4321", balanceKopecks = 10_00, observedAt = 1_500))

        assertEquals(
            setOf(alfa, ReconAccount("sber", "5678"), ReconAccount("tbank", null), ReconAccount("alfa", "4321")),
            repo.knownAccounts().toSet(),
        )
        val latest = repo.latestBalances().associateBy { it.account }
        assertEquals(800_00L, latest.getValue(alfa).kopecks)
        assertEquals(Instant.ofEpochMilli(2_000), latest.getValue(alfa).at)
        assertEquals(10_00L, latest.getValue(ReconAccount("alfa", "4321")).kopecks)
    }

    @Test
    fun stateRoundTripAndManualAccount() = runTest {
        val pending = Mismatch(12_300_00, 12_750_00, anchorAt.plusSeconds(60))
        repo.save(ReconState(alfa, 12_750_00, anchorAt, pending, lastReconciledAt = anchorAt))
        repo.save(ReconState(ReconAccount.MANUAL, 15_000_00, anchorAt))
        assertEquals(pending, repo.get(alfa)!!.pending)
        assertEquals(15_000_00L, repo.get(ReconAccount.MANUAL)!!.anchorKopecks)
        // Повторное сохранение того же счёта заменяет строку, а не добавляет вторую.
        repo.save(ReconState(alfa, 12_300_00, anchorAt.plusSeconds(60)))
        assertNull(repo.get(alfa)!!.pending)
        assertEquals(2, count("reconciliation_accounts"))
        // «Счёт без автоимпорта» не попадает в список карт банков.
        assertTrue(ReconAccount.MANUAL !in repo.knownAccounts())
    }

    @Test
    fun failedTransactionLeavesNoAdjustment() = runTest {
        repo.save(ReconState(alfa, 12_750_00, anchorAt, Mismatch(12_300_00, 12_750_00, anchorAt.plusSeconds(60))))
        val adjustment = Operation(
            type = OperationType.EXPENSE, amount = Money(450_00), categoryId = 1, merchant = "Корректировка",
            source = OperationSource.ADJUSTMENT, occurredAt = OffsetDateTime.ofInstant(anchorAt, zone), bankId = "alfa", cardLast4 = "1234",
        )
        // Сбой на второй половине транзакции: запись состояния отклоняется триггером уже после вставки корректировки.
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_update BEFORE UPDATE ON reconciliation_accounts BEGIN SELECT RAISE(ABORT, 'test'); END")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_insert BEFORE INSERT ON reconciliation_accounts BEGIN SELECT RAISE(ABORT, 'test'); END")
        val result = runCatching { repo.saveWithAdjustment(adjustment, ReconState(alfa, 12_300_00, anchorAt.plusSeconds(60))) }
        assertTrue(result.isFailure)
        assertEquals(0, count("operations"))
    }

    @Test
    fun adjustmentIsNotWrittenTwice() = runTest {
        val pending = ReconState(alfa, 12_750_00, anchorAt, Mismatch(12_300_00, 12_750_00, anchorAt.plusSeconds(60)))
        repo.save(pending)
        val adjustment = Operation(
            type = OperationType.EXPENSE, amount = Money(450_00), categoryId = 1, merchant = "Корректировка",
            source = OperationSource.ADJUSTMENT, occurredAt = OffsetDateTime.ofInstant(anchorAt, zone), bankId = "alfa", cardLast4 = "1234",
        )
        val resolved = ReconState(alfa, 12_300_00, anchorAt.plusSeconds(60))
        assertTrue(repo.saveWithAdjustment(adjustment, resolved))
        assertEquals(false, repo.saveWithAdjustment(adjustment, resolved))
        assertEquals(1, count("operations"))
    }
}
