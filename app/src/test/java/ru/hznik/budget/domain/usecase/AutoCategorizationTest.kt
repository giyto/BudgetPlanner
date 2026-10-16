package ru.hznik.budget.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.domain.categorize.AutoCategorize
import ru.hznik.budget.domain.categorize.CategoryRule
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.OperationItem
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.notifications.BankNotification
import ru.hznik.budget.domain.notifications.parser.NotificationKind
import ru.hznik.budget.domain.notifications.parser.ParsedNotification
import ru.hznik.budget.domain.search.normalizeForSearch
import ru.hznik.budget.notifications.FakeAutoImportSettings
import ru.hznik.budget.notifications.FakeNotificationRepository
import ru.hznik.budget.testing.CAFE
import ru.hznik.budget.testing.FakeAutoReconcileSettings
import ru.hznik.budget.testing.FakeCategoryRepository
import ru.hznik.budget.testing.FakeCategoryRuleRepository
import ru.hznik.budget.testing.FakeOperationRepository
import ru.hznik.budget.testing.FakePeriodRepository
import ru.hznik.budget.testing.FakeReconciliationRepository
import ru.hznik.budget.testing.PRODUCTS
import ru.hznik.budget.testing.SALARY
import ru.hznik.budget.testing.TEST_CLOCK
import ru.hznik.budget.testing.TRANSFERS
import ru.hznik.budget.testing.TRANSPORT
import ru.hznik.budget.testing.category
import java.time.Instant

/** Правила «магазин → категория» и словарь при импорте и подтверждении (spec auto-categorization, drafts). */
class AutoCategorizationTest {
    private val otherExpense = category(30, "Другое", defaultKey = "other_expense")
    private val otherIncome = category(31, "Другое", kind = CategoryKind.INCOME, defaultKey = "other_income")
    private val clothes = category(5, "Одежда", defaultKey = "clothes")
    private val gift = category(21, "Подарок", kind = CategoryKind.INCOME, defaultKey = "gift")
    private val categories = FakeCategoryRepository(listOf(PRODUCTS, CAFE, TRANSPORT, TRANSFERS, SALARY, clothes, gift, otherExpense, otherIncome))
    private val operations = FakeOperationRepository(categories)
    private val rules = FakeCategoryRuleRepository(operations, categories)
    private val autoImport = FakeAutoImportSettings()
    private val recon = FakeReconciliationRepository(operations)
    private val import = ImportNotification(
        operations, categories, FakeNotificationRepository(), FakePeriodRepository(), autoImport,
        ReconcileBalance(recon, FakeAutoReconcileSettings(), ExpectedBalance(recon), ReconciliationLock()), AutoCategorize(rules), TEST_CLOCK,
    )
    private val confirm = ConfirmDrafts(operations, categories, rules)
    private val now: Instant = TEST_CLOCK.instant()
    private var nextKey = 1

    private suspend fun receive(
        merchant: String?,
        kind: NotificationKind = NotificationKind.PURCHASE,
        rubles: Long = 450,
        bank: String = "sber",
        salary: Boolean = false,
        at: Instant = now.plusSeconds(nextKey * 600L),
    ): ImportResult {
        val key = "key-${nextKey++}"
        return import(BankNotification("ru.sberbankmobile", key, at, "СберБанк", "текст"), ParsedNotification(bank, kind, Money(rubles * 100), merchant, null, "1234", at, salary))
    }

    private fun rule(merchant: String, categoryId: Long, kind: CategoryKind = CategoryKind.EXPENSE) {
        rules.rules.value = rules.rules.value + CategoryRule(id = rules.rules.value.size + 1L, merchantKey = ru.hznik.budget.domain.categorize.MerchantKey.of(merchant)!!, kind = kind, categoryId = categoryId, merchantDisplay = merchant)
    }

    private fun last() = operations.state.value.maxBy { it.id }

    private suspend fun drafts(): List<OperationItem> = operations.observeDrafts().first()

    // --- Импорт ---

    @Test
    fun `known store is saved right away in the rule category`() = runTest {
        rule("Пятёрочка", PRODUCTS.id)
        receive("PYATEROCHKA 1234")
        // «Пятёрочка» и «PYATEROCHKA» — разные написания; правило по латинскому написанию.
        assertTrue(last().isDraft)
        rule("PYATEROCHKA 9999", PRODUCTS.id)
        receive("PYATEROCHKA 5678")
        val op = last()
        assertFalse(op.isDraft)
        assertEquals(PRODUCTS.id, op.categoryId)
        assertEquals(OperationType.EXPENSE, op.type)
    }

    @Test
    fun `transfer by rule into a normal category becomes an expense`() = runTest {
        rule("Олег К.", CAFE.id)
        receive("Олег К.", kind = NotificationKind.TRANSFER_OUT, rubles = 500)
        val op = last()
        assertFalse(op.isDraft)
        assertEquals(OperationType.EXPENSE, op.type)
        assertEquals(CAFE.id, op.categoryId)
    }

    @Test
    fun `expense rule into Transfers makes a transfer`() = runTest {
        rule("Олег К.", TRANSFERS.id)
        receive("Олег К.", kind = NotificationKind.PURCHASE, rubles = 500)
        assertEquals(OperationType.TRANSFER, last().type)
    }

    @Test
    fun `refund keeps its type under an expense rule`() = runTest {
        rule("Ozon", clothes.id)
        receive("Ozon", kind = NotificationKind.REFUND, rubles = 1_249)
        val op = last()
        assertEquals(OperationType.REFUND, op.type)
        assertEquals(clothes.id, op.categoryId)
        assertFalse(op.isDraft)
    }

    @Test
    fun `income uses income rules and keeps the salary hint`() = runTest {
        rule("Мама М.", gift.id, CategoryKind.INCOME)
        rule("Мама М.", CAFE.id, CategoryKind.EXPENSE)
        receive("Мама М.", kind = NotificationKind.INCOME, rubles = 2_000, salary = true)
        val op = last()
        assertEquals(gift.id, op.categoryId)
        assertFalse(op.isDraft)
        assertTrue(op.suggestsNewPeriod)
    }

    @Test
    fun `rule to a hidden category gives a draft`() = runTest {
        categories.state.value = categories.state.value.map { if (it.id == CAFE.id) it.copy(hidden = true) else it }
        rule("Кофейня Зерно", CAFE.id)
        receive("Кофейня «Зерно»")
        assertTrue(last().isDraft)
        assertEquals(otherExpense.id, last().categoryId)
    }

    @Test
    fun `own name wins over a rule`() = runTest {
        rule("ИВАН П.", gift.id, CategoryKind.INCOME)
        autoImport.addOwnName(normalizeForSearch("ИВАН П."))
        receive("ИВАН П.", kind = NotificationKind.INCOME, rubles = 10)
        assertEquals(OperationType.OWN_TRANSFER, last().type)
    }

    @Test
    fun `own transfer pair still merges when the first side matched a rule`() = runTest {
        rule("ИВАН П.", TRANSFERS.id)
        // Исходящий перевод по правилу «Переводы» больше не черновик — пара со второго банка не должна сломаться.
        receive("ИВАН П.", kind = NotificationKind.TRANSFER_OUT, rubles = 3_000, bank = "sber", at = now)
        receive("ИВАН П.", kind = NotificationKind.INCOME, rubles = 3_000, bank = "alfa", at = now.plusSeconds(60))
        val pair = operations.state.value.single()
        assertEquals(OperationType.OWN_TRANSFER, pair.type)
        assertEquals(OperationType.TRANSFER, pair.originalType)
        assertEquals("alfa", pair.counterBankId)
    }

    @Test
    fun `own transfer pair merges when a rule turned the first side into an expense`() = runTest {
        rule("ИВАН П.", CAFE.id)
        receive("ИВАН П.", kind = NotificationKind.TRANSFER_OUT, rubles = 3_000, bank = "sber", at = now)
        assertEquals(OperationType.EXPENSE, last().type)
        receive("ИВАН П.", kind = NotificationKind.INCOME, rubles = 3_000, bank = "alfa", at = now.plusSeconds(60))
        val pair = operations.state.value.single()
        assertEquals(OperationType.OWN_TRANSFER, pair.type)
        assertEquals(OperationType.TRANSFER, pair.originalType)
    }

    @Test
    fun `dictionary gives a draft hint for a purchase without a rule`() = runTest {
        receive("YANDEX*GO")
        val op = last()
        assertTrue(op.isDraft)
        assertEquals(TRANSPORT.id, op.categoryId)
        assertTrue(DraftHint.isKnownStore(op, categories.state.value))
    }

    @Test
    fun `rule wins over the dictionary`() = runTest {
        rule("PYATEROCHKA", CAFE.id)
        receive("PYATEROCHKA")
        assertEquals(CAFE.id, last().categoryId)
        assertFalse(last().isDraft)
    }

    @Test
    fun `unknown store stays in Other without a hint`() = runTest {
        receive("ИП Смирнова А. В.")
        assertEquals(otherExpense.id, last().categoryId)
        assertFalse(DraftHint.isKnownStore(last(), categories.state.value))
    }

    @Test
    fun `dictionary is not used for transfers`() = runTest {
        receive("YANDEX GO", kind = NotificationKind.TRANSFER_OUT)
        assertEquals(TRANSFERS.id, last().categoryId)
    }

    // --- Подтверждение ---

    @Test
    fun `remember creates a rule and the next purchase skips drafts`() = runTest {
        receive("Ozon")
        val item = drafts().single()
        confirm(mapOf(item to clothes.id), remember = setOf(item.operation.id))
        val rule = rules.rules.value.single()
        assertEquals("ozon", rule.merchantKey)
        assertEquals(clothes.id, rule.categoryId)
        receive("OZON")
        assertFalse(last().isDraft)
        assertEquals(clothes.id, last().categoryId)
    }

    @Test
    fun `remember replaces the old rule`() = runTest {
        rule("Ozon", PRODUCTS.id)
        categories.state.value = categories.state.value // правило уже есть, но черновик пришёл до него
        operations.insert(ru.hznik.budget.domain.model.Operation(type = OperationType.EXPENSE, amount = Money(100_00), categoryId = otherExpense.id, merchant = "Ozon",
            source = ru.hznik.budget.domain.model.OperationSource.NOTIFICATION, occurredAt = java.time.OffsetDateTime.now(TEST_CLOCK), isDraft = true))
        val item = drafts().single()
        confirm(mapOf(item to clothes.id), remember = setOf(item.operation.id))
        assertEquals(clothes.id, rules.rules.value.single().categoryId)
    }

    @Test
    fun `other drafts of the same store are confirmed too`() = runTest {
        receive("PYATEROCHKA 1234")
        receive("PYATEROCHKA 5678", rubles = 120)
        receive("ИП Смирнова А. В.", rubles = 350)
        val first = drafts().first { it.operation.amount == Money(450_00) }
        confirm(mapOf(first to PRODUCTS.id), remember = setOf(first.operation.id))
        val left = drafts()
        assertEquals(listOf("ИП Смирнова А. В."), left.map { it.operation.merchant })
        assertTrue(operations.state.value.filter { it.merchant?.startsWith("PYATEROCHKA") == true }.all { it.categoryId == PRODUCTS.id && !it.isDraft })
    }

    @Test
    fun `waiting transfers of the same name are not confirmed by a new rule`() = runTest {
        receive("Олег К.", kind = NotificationKind.TRANSFER_OUT, rubles = 500)
        receive("Олег К.", kind = NotificationKind.TRANSFER_OUT, rubles = 700)
        val first = drafts().first { it.operation.amount == Money(500_00) }
        confirm(mapOf(first to TRANSFERS.id), remember = setOf(first.operation.id))
        // Второй перевод ещё может оказаться переводом себе в другой банк — остаётся черновиком.
        assertEquals(listOf(Money(700_00)), drafts().map { it.operation.amount })
    }

    @Test
    fun `without remember no rule is stored`() = runTest {
        receive("Ozon")
        confirm(mapOf(drafts().single() to clothes.id))
        assertTrue(rules.rules.value.isEmpty())
    }

    @Test
    fun `name without letters gives no rule`() = runTest {
        receive("796*1234", kind = NotificationKind.TRANSFER_OUT)
        val item = drafts().single()
        assertFalse(confirm.canRemember(item))
        confirm(mapOf(item to TRANSFERS.id), remember = setOf(item.operation.id))
        assertTrue(rules.rules.value.isEmpty())
        assertFalse(last().isDraft)
    }

    // --- Экран правил ---

    @Test
    fun `rule category changes only to a visible category of the same kind`() = runTest {
        rule("Ozon", PRODUCTS.id)
        val r = rules.rules.value.single()
        assertFalse(ChangeRuleCategory(rules, categories)(r, gift.id))
        assertTrue(ChangeRuleCategory(rules, categories)(r, clothes.id))
        assertEquals(clothes.id, rules.rules.value.single().categoryId)
    }

    @Test
    fun `deleted rule can be restored`() = runTest {
        rule("Ozon", PRODUCTS.id)
        val r = rules.rules.value.single()
        val deleted = DeleteRule(rules)(r)
        assertNull(rules.find("ozon", CategoryKind.EXPENSE))
        RestoreRule(rules)(deleted)
        assertEquals(r, rules.find("ozon", CategoryKind.EXPENSE))
    }
}
