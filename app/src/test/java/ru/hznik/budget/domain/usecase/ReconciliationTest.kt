package ru.hznik.budget.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.domain.budget.BalanceReconciler
import ru.hznik.budget.domain.budget.BudgetCalculator
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.model.Period
import ru.hznik.budget.domain.reconcile.BankBalance
import ru.hznik.budget.domain.reconcile.Mismatch
import ru.hznik.budget.domain.reconcile.ReconAccount
import ru.hznik.budget.domain.reconcile.ReconState
import ru.hznik.budget.testing.CAFE
import ru.hznik.budget.testing.FakeAutoReconcileSettings
import ru.hznik.budget.testing.FakeCategoryRepository
import ru.hznik.budget.testing.FakeOperationRepository
import ru.hznik.budget.testing.FakeReconciliationRepository
import ru.hznik.budget.testing.PRODUCTS
import ru.hznik.budget.testing.SALARY
import ru.hznik.budget.testing.TEST_CLOCK
import ru.hznik.budget.testing.TRANSFERS
import ru.hznik.budget.testing.category
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/** Сверка с банком (spec balance-reconciliation): правила, решения, ручная сверка, настройка. */
class ReconciliationTest {
    private val otherExpense = category(30, "Другое", defaultKey = "other_expense")
    private val otherIncome = category(31, "Другие доходы", kind = CategoryKind.INCOME, defaultKey = "other_income")
    private val categories = FakeCategoryRepository(listOf(PRODUCTS, CAFE, TRANSFERS, SALARY, otherExpense, otherIncome))
    private val operations = FakeOperationRepository(categories)
    private val repo = FakeReconciliationRepository(operations)
    private val settings = FakeAutoReconcileSettings()
    private val expected = ExpectedBalance(repo)
    private val lock = ReconciliationLock()
    private val reconcile = ReconcileBalance(repo, settings, expected, lock)
    private val recheck = RecheckMismatch(repo, expected, lock)
    private val resolve = ResolveMismatch(repo, categories, lock, TEST_CLOCK)
    private val manual = ManualReconcile(repo, expected, lock, TEST_CLOCK)
    private val save = SaveOperation(operations, categories, PlaceOperationForAccount(repo), TEST_CLOCK)
    private val now: Instant = TEST_CLOCK.instant()
    private val alfa = ReconAccount("alfa", "1234")
    private val sber = ReconAccount("sber", "5678")

    private fun rub(r: Long) = r * 100

    private suspend fun op(
        type: OperationType,
        rubles: Long,
        at: Instant,
        account: ReconAccount? = alfa,
        source: OperationSource = OperationSource.NOTIFICATION,
        draft: Boolean = false,
    ): Long = operations.insert(
        Operation(
            type = type, amount = Money(rub(rubles)), categoryId = if (type == OperationType.INCOME) SALARY.id else PRODUCTS.id,
            source = source, occurredAt = OffsetDateTime.ofInstant(at, TEST_CLOCK.zone), isDraft = draft,
            bankId = account?.bankId, cardLast4 = account?.cardLast4,
        ),
    )

    private suspend fun anchor(account: ReconAccount, rubles: Long, at: Instant = now.minus(Duration.ofHours(2))) =
        repo.save(ReconState(account, rub(rubles), at))

    @Test
    fun `threshold is strictly more than one ruble`() {
        assertTrue(BalanceReconciler.evaluate(rub(5_000), 0, rub(5_000) - 100) is BalanceReconciler.Result.Matched)
        assertTrue(BalanceReconciler.evaluate(rub(5_000), 0, rub(5_000) + 100) is BalanceReconciler.Result.Matched)
        assertTrue(BalanceReconciler.evaluate(rub(5_000), 0, rub(5_000) - 80) is BalanceReconciler.Result.Matched)
        val missing = BalanceReconciler.evaluate(rub(12_750), 0, rub(12_300)) as BalanceReconciler.Result.Mismatched
        assertEquals(rub(-450), missing.diffKopecks)
        val more = BalanceReconciler.evaluate(rub(4_000), rub(-500), rub(4_500)) as BalanceReconciler.Result.Mismatched
        assertEquals(rub(1_000), more.diffKopecks)
        assertTrue(BalanceReconciler.evaluate(rub(5_000), 0, rub(5_000) - 101) is BalanceReconciler.Result.Mismatched)
    }

    @Test
    fun `operations without card digits go to the only card of the bank`() {
        assertTrue(BalanceReconciler.includeNoDigits(alfa, setOf("1234")))
        assertFalse(BalanceReconciler.includeNoDigits(alfa, setOf("1234", "9999")))
        assertFalse(BalanceReconciler.includeNoDigits(ReconAccount("alfa", null), emptySet()))
        assertFalse(BalanceReconciler.includeNoDigits(ReconAccount.MANUAL, emptySet()))
    }

    @Test
    fun `income without digits counts for the only card of the bank`() = runTest {
        anchor(alfa, 1_000)
        op(OperationType.INCOME, 10, now.minus(Duration.ofHours(1)), account = ReconAccount("alfa", null))
        reconcile(alfa, rub(1_010), now)
        assertNull(repo.get(alfa)!!.pending)
    }

    @Test
    fun `cards of different banks are reconciled separately`() = runTest {
        anchor(alfa, 4_710)
        anchor(sber, 12_300)
        reconcile(alfa, rub(4_410), now)
        reconcile(sber, rub(12_300), now)
        assertEquals(rub(-300), repo.get(alfa)!!.pending!!.diffKopecks)
        assertNull(repo.get(sber)!!.pending)
    }

    @Test
    fun `drafts and own transfers between banks are counted`() = runTest {
        anchor(alfa, 1_000)
        anchor(sber, 5_000)
        op(OperationType.EXPENSE, 700, now.minus(Duration.ofMinutes(90)), draft = true)
        operations.insert(
            Operation(
                type = OperationType.OWN_TRANSFER, amount = Money(rub(3_000)), categoryId = TRANSFERS.id, source = OperationSource.NOTIFICATION,
                occurredAt = OffsetDateTime.ofInstant(now.minus(Duration.ofHours(1)), TEST_CLOCK.zone), bankId = "sber", cardLast4 = "5678",
                originalType = OperationType.TRANSFER, counterBankId = "alfa", counterCardLast4 = "1234", counterOccurredAt = now.minus(Duration.ofMinutes(59)),
            ),
        )
        reconcile(alfa, rub(3_300), now)
        reconcile(sber, rub(2_000), now)
        assertNull(repo.get(alfa)!!.pending)
        assertNull(repo.get(sber)!!.pending)
    }

    @Test
    fun `manual operations are only in the account without auto import`() = runTest {
        anchor(alfa, 1_000)
        op(OperationType.EXPENSE, 200, now.minus(Duration.ofHours(1)), account = null, source = OperationSource.MANUAL)
        reconcile(alfa, rub(1_000), now)
        assertNull(repo.get(alfa)!!.pending)
    }

    @Test
    fun `adjustment when bank shows less is an expense in Other`() = runTest {
        repo.save(ReconState(alfa, rub(12_750), now.minus(Duration.ofHours(2)), Mismatch(rub(12_300), rub(12_750), now.minus(Duration.ofMinutes(10)))))
        resolve(alfa, Resolution.ADJUST)
        val adjustment = operations.state.value.single()
        assertEquals(OperationType.EXPENSE, adjustment.type)
        assertEquals(Money(rub(450)), adjustment.amount)
        assertEquals(otherExpense.id, adjustment.categoryId)
        assertEquals(OperationSource.ADJUSTMENT, adjustment.source)
        assertEquals("alfa", adjustment.bankId)
        assertEquals("1234", adjustment.cardLast4)
        val state = repo.get(alfa)!!
        assertNull(state.pending)
        assertEquals(rub(12_300), state.anchorKopecks)
        assertEquals(now.minus(Duration.ofMinutes(10)), state.anchorAt)
    }

    @Test
    fun `adjustment when bank shows more is income and is not counted twice`() = runTest {
        repo.save(ReconState(alfa, rub(4_000), now.minus(Duration.ofHours(2)), Mismatch(rub(5_000), rub(4_000), now.minus(Duration.ofMinutes(10)))))
        resolve(alfa, Resolution.ADJUST)
        val adjustment = operations.state.value.single()
        assertEquals(OperationType.INCOME, adjustment.type)
        assertEquals(otherIncome.id, adjustment.categoryId)
        // Корректировка не входит в ожидаемый остаток: следующий остаток банка сходится.
        reconcile(alfa, rub(5_000), now)
        assertNull(repo.get(alfa)!!.pending)
    }

    @Test
    fun `skip moves the anchor without operations`() = runTest {
        repo.save(ReconState(alfa, rub(12_750), now.minus(Duration.ofHours(2)), Mismatch(rub(12_300), rub(12_750), now.minus(Duration.ofMinutes(10)))))
        resolve(alfa, Resolution.SKIP)
        assertTrue(operations.state.value.isEmpty())
        assertEquals(rub(12_300), repo.get(alfa)!!.anchorKopecks)
        assertNull(repo.get(alfa)!!.pending)
    }

    @Test
    fun `failed adjustment leaves the mismatch and no operation`() = runTest {
        val state = ReconState(alfa, rub(12_750), now.minus(Duration.ofHours(2)), Mismatch(rub(12_300), rub(12_750), now.minus(Duration.ofMinutes(10))))
        repo.save(state)
        repo.failAdjustment = true
        runCatching { resolve(alfa, Resolution.ADJUST) }
        assertTrue(operations.state.value.isEmpty())
        assertEquals(state, repo.get(alfa))
    }

    @Test
    fun `missing purchase added from the filtered list resolves the mismatch`() = runTest {
        val balanceAt = now.minus(Duration.ofMinutes(30))
        repo.save(ReconState(alfa, rub(12_750), now.minus(Duration.ofHours(2)), Mismatch(rub(12_300), rub(12_750), balanceAt)))
        // Пользователь добавляет трату «сейчас», позже остатка банка: время сдвигается до остатка.
        val result = save(OperationDraft(inputType = InputType.EXPENSE, amount = Money(rub(450)), categoryId = PRODUCTS.id, comment = "", occurredAt = OffsetDateTime.now(TEST_CLOCK), account = alfa))
        assertTrue(result is SaveResult.Saved)
        val added = operations.state.value.single()
        assertEquals("alfa", added.bankId)
        assertEquals(OperationSource.MANUAL, added.source)
        assertEquals(balanceAt.minus(Duration.ofMinutes(1)), added.occurredAt.toInstant())
        recheck(alfa)
        val state = repo.get(alfa)!!
        assertNull(state.pending)
        assertEquals(rub(12_300), state.anchorKopecks)
        // Следующая сверка не учитывает эту трату второй раз.
        reconcile(alfa, rub(12_300), now)
        assertNull(repo.get(alfa)!!.pending)
    }

    @Test
    fun `missing purchase stays after the anchor when the balance came less than a minute later`() = runTest {
        val balanceAt = now.minus(Duration.ofMinutes(30))
        repo.save(ReconState(alfa, rub(4_710), balanceAt.minusSeconds(20), Mismatch(rub(4_310), rub(4_610), balanceAt)))
        op(OperationType.EXPENSE, 100, balanceAt)
        save(OperationDraft(inputType = InputType.EXPENSE, amount = Money(rub(300)), categoryId = PRODUCTS.id, comment = "", occurredAt = OffsetDateTime.now(TEST_CLOCK), account = alfa))
        assertEquals(balanceAt, operations.state.value.last().occurredAt.toInstant())
        recheck(alfa)
        assertNull(repo.get(alfa)!!.pending)
    }

    @Test
    fun `first manual reconciliation anchors the account`() = runTest {
        assertEquals(ManualResult.Anchored, manual(ReconAccount.MANUAL, rub(15_000)))
        val state = repo.get(ReconAccount.MANUAL)!!
        assertEquals(rub(15_000), state.anchorKopecks)
        assertEquals(now, state.lastReconciledAt)
    }

    @Test
    fun `manual reconciliation finds a mismatch even when auto is off`() = runTest {
        settings.state.value = false
        anchor(ReconAccount.MANUAL, 15_000)
        op(OperationType.EXPENSE, 2_000, now.minus(Duration.ofHours(1)), account = null, source = OperationSource.MANUAL)
        val result = manual(ReconAccount.MANUAL, rub(12_500)) as ManualResult.Mismatched
        assertEquals(rub(-500), result.mismatch.diffKopecks)
        assertEquals(rub(13_000), result.mismatch.expectedKopecks)
    }

    @Test
    fun `mismatches are hidden while auto reconciliation is off`() = runTest {
        repo.save(ReconState(alfa, rub(12_750), now.minus(Duration.ofHours(2)), Mismatch(rub(12_300), rub(12_750), now)))
        val observe = ObserveMismatches(repo, settings)
        assertEquals(1, observe().first().size)
        settings.state.value = false
        assertTrue(observe().first().isEmpty())
    }

    @Test
    fun `turning auto back on rebases to the latest balances`() = runTest {
        settings.state.value = false
        repo.save(ReconState(alfa, rub(12_750), now.minus(Duration.ofHours(5)), Mismatch(rub(12_300), rub(12_750), now.minus(Duration.ofHours(4)))))
        repo.balances += BankBalance(alfa, rub(9_000), now.minus(Duration.ofHours(3)))
        repo.balances += BankBalance(alfa, rub(8_000), now.minus(Duration.ofHours(1)))
        SetAutoReconcile(repo, settings, expected, lock)(true)
        assertTrue(settings.state.value)
        val state = repo.get(alfa)!!
        assertNull(state.pending)
        assertEquals(rub(8_000), state.anchorKopecks)
        assertEquals(now.minus(Duration.ofHours(1)), state.anchorAt)
    }

    @Test
    fun `last reconciliation is the latest of all accounts`() = runTest {
        repo.save(ReconState(alfa, 0, now, lastReconciledAt = now.minus(Duration.ofHours(3))))
        repo.save(ReconState(sber, 0, now, lastReconciledAt = now.minus(Duration.ofHours(1))))
        assertEquals(sber, ObserveLastReconciliation(repo)().first()!!.account)
    }

    @Test
    fun `reconcilable accounts list cards and the manual account last`() = runTest {
        anchor(alfa, 1_000)
        op(OperationType.EXPENSE, 100, now.minus(Duration.ofHours(1)), account = sber)
        val accounts = ReconcilableAccounts(repo, expected, TEST_CLOCK)()
        assertEquals(listOf(alfa, sber, ReconAccount.MANUAL), accounts.map { it.account })
        assertEquals(rub(1_000), accounts[0].expectedKopecks)
        assertNull(accounts[1].expectedKopecks)
    }

    @Test
    fun `scenario 5_9 - adjustment lowers can spend today by 450`() = runTest {
        val period = Period(startDate = LocalDate.of(2026, 10, 1), nextIncomeDate = LocalDate.of(2026, 10, 25), startAmountKopecks = rub(18_000))
        val today = LocalDate.of(2026, 10, 13)
        anchor(alfa, 13_200)
        // «Покупка 450 ₽, Пятёрочка. Баланс 12 300 ₽»: по расчёту 12 750 ₽, одна трата в приложение не попала.
        op(OperationType.EXPENSE, 450, now)
        reconcile(alfa, rub(12_300), now)
        val pending = repo.get(alfa)!!.pending!!
        assertEquals(rub(12_750), pending.expectedKopecks)
        assertEquals(rub(-450), pending.diffKopecks)
        val before = BudgetCalculator.calculate(period, operations.state.value, emptyList(), today).canSpendToday
        resolve(alfa, Resolution.ADJUST)
        val after = BudgetCalculator.calculate(period, operations.state.value, emptyList(), today).canSpendToday
        assertEquals(rub(450), before - after)
        assertNull(repo.get(alfa)!!.pending)
    }

    @Test
    fun `second resolve of the same mismatch writes no second adjustment`() = runTest {
        repo.save(ReconState(alfa, rub(12_750), now.minus(Duration.ofHours(2)), Mismatch(rub(12_300), rub(12_750), now.minus(Duration.ofMinutes(10)))))
        kotlinx.coroutines.coroutineScope {
            launch { resolve(alfa, Resolution.ADJUST) }
            launch { resolve(alfa, Resolution.ADJUST) }
        }
        assertEquals(1, operations.state.value.count { it.source == OperationSource.ADJUSTMENT })
    }

    @Test
    fun `balance without card digits goes to the only card of the bank`() = runTest {
        op(OperationType.EXPENSE, 100, now.minus(Duration.ofHours(3)))
        anchor(alfa, 1_000)
        op(OperationType.INCOME, 10, now.minus(Duration.ofHours(1)), account = ReconAccount("alfa", null))
        reconcile(ReconAccount("alfa", null), rub(1_010), now)
        assertNull(repo.get(ReconAccount("alfa", null)))
        assertEquals(rub(1_010), repo.get(alfa)!!.anchorKopecks)
    }

    @Test
    fun `balance without digits is skipped when the bank has several cards`() = runTest {
        anchor(alfa, 1_000)
        anchor(ReconAccount("alfa", "9999"), 500)
        reconcile(ReconAccount("alfa", null), rub(7_777), now)
        assertNull(repo.get(ReconAccount("alfa", null)))
        assertNull(repo.get(alfa)!!.pending)
    }

    @Test
    fun `transfer inside the bank keeps an open mismatch with its difference`() = runTest {
        repo.save(ReconState(alfa, rub(12_750), now.minus(Duration.ofHours(2)), Mismatch(rub(12_300), rub(12_750), now.minus(Duration.ofHours(1)))))
        reconcile(alfa, rub(9_300), now, rebase = true)
        val state = repo.get(alfa)!!
        assertEquals(rub(9_300), state.anchorKopecks)
        assertEquals(rub(-450), state.pending!!.diffKopecks)
        assertEquals(rub(9_300), state.pending!!.bankKopecks)
    }

    @Test
    fun `turning auto back on also clears mismatches of manual reconciliation`() = runTest {
        settings.state.value = false
        repo.save(ReconState(ReconAccount.MANUAL, rub(15_000), now.minus(Duration.ofHours(2)), Mismatch(rub(12_500), rub(13_000), now)))
        SetAutoReconcile(repo, settings, expected, lock)(true)
        assertNull(repo.get(ReconAccount.MANUAL)!!.pending)
    }

    @Test
    fun `bank without digits is not offered next to its card`() = runTest {
        op(OperationType.EXPENSE, 100, now.minus(Duration.ofHours(1)))
        op(OperationType.INCOME, 10, now.minus(Duration.ofHours(1)), account = ReconAccount("alfa", null))
        op(OperationType.EXPENSE, 50, now.minus(Duration.ofHours(1)), account = ReconAccount("tbank", null))
        val accounts = ReconcilableAccounts(repo, expected, TEST_CLOCK)().map { it.account }
        assertEquals(listOf(alfa, ReconAccount("tbank", null), ReconAccount.MANUAL), accounts)
    }
}
