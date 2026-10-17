package ru.hznik.budget.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.notifications.BankNotification
import ru.hznik.budget.domain.notifications.parser.NotificationKind
import ru.hznik.budget.domain.notifications.parser.ParsedNotification
import ru.hznik.budget.notifications.FakeAutoImportSettings
import ru.hznik.budget.notifications.FakeNotificationRepository
import ru.hznik.budget.testing.CAFE
import ru.hznik.budget.testing.FakeCategoryRepository
import ru.hznik.budget.testing.FakeAutoReconcileSettings
import ru.hznik.budget.testing.FakeCategoryRuleRepository
import ru.hznik.budget.testing.FakeOperationRepository
import ru.hznik.budget.testing.FakeReconciliationRepository
import ru.hznik.budget.testing.FakePeriodRepository
import ru.hznik.budget.testing.PRODUCTS
import ru.hznik.budget.testing.SALARY
import ru.hznik.budget.testing.TEST_CLOCK
import ru.hznik.budget.testing.TRANSFERS
import ru.hznik.budget.testing.TRANSPORT
import ru.hznik.budget.testing.TODAY
import ru.hznik.budget.testing.category
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import ru.hznik.budget.domain.categorize.AutoCategorize
import ru.hznik.budget.domain.reconcile.ReconAccount
import ru.hznik.budget.domain.reconcile.ReconState
import org.junit.Assert.assertNull

/** Приём операций из уведомлений (spec notification-import, drafts, home-screen «Предложение нового периода»). */
class ImportNotificationTest {
    private val otherExpense = category(30, "Другое", defaultKey = "other_expense")
    private val otherIncome = category(31, "Другое", kind = CategoryKind.INCOME, defaultKey = "other_income")
    private val categories = FakeCategoryRepository(listOf(PRODUCTS, CAFE, TRANSPORT, TRANSFERS, SALARY, otherExpense, otherIncome))
    private val operations = FakeOperationRepository(categories)
    private val notifications = FakeNotificationRepository()
    private val periods = FakePeriodRepository()
    private val autoImport = FakeAutoImportSettings()
    private val recon = FakeReconciliationRepository(operations)
    private val reconSettings = FakeAutoReconcileSettings()
    private val rules = FakeCategoryRuleRepository(operations, categories)
    private val import = ImportNotification(
        operations, categories, notifications, periods, autoImport, ReconcileBalance(recon, reconSettings, ExpectedBalance(recon), ReconciliationLock()),
        AutoCategorize(rules), TEST_CLOCK,
    )
    private val confirm = ConfirmDrafts(operations, categories, rules)
    private val markOwn = MarkOwnTransfer(operations, categories, autoImport)
    private val split = SplitOwnTransfer(operations, categories)
    private val now: Instant = TEST_CLOCK.instant()

    private fun notification(key: String = "key-1") = BankNotification("ru.sberbankmobile", key, now, "СберБанк", "текст")

    private fun parsed(
        kind: NotificationKind = NotificationKind.PURCHASE,
        bank: String = "sber",
        rubles: Long = 450,
        merchant: String? = "Пятёрочка",
        balance: Long? = null,
        at: Instant = now,
        salary: Boolean = false,
    ) = ParsedNotification(bank, kind, Money(rubles * 100), merchant, balance?.let { Money(it * 100) }, "1234", at, salary)

    private suspend fun all() = operations.state.value

    @Test
    fun `purchase becomes a draft in Other that already counts`() = runTest {
        // Магазин не из словаря: у «Пятёрочки» была бы подсказка «Продукты» (add-auto-categorization).
        assertEquals(ImportResult.IMPORTED, import(notification(), parsed(balance = 12_300, merchant = "ИП Смирнова А. В.")))
        val op = all().single()
        assertEquals(OperationType.EXPENSE, op.type)
        assertEquals(otherExpense.id, op.categoryId)
        assertTrue(op.isDraft)
        assertEquals(OperationSource.NOTIFICATION, op.source)
        assertEquals("sber", op.bankId)
        assertEquals("1234", op.cardLast4)
        assertEquals(listOf(Triple("sber", "1234", Money(12_300_00))), notifications.balances)
        assertEquals(1, operations.observeDrafts().first().size)
    }

    @Test
    fun `kinds map to types and provisional categories`() = runTest {
        import(notification("t"), parsed(NotificationKind.TRANSFER_OUT, merchant = "Олег К."))
        import(notification("i"), parsed(NotificationKind.INCOME, rubles = 500, merchant = "Мама М."))
        import(notification("r"), parsed(NotificationKind.REFUND, rubles = 100))
        assertEquals(
            listOf(OperationType.TRANSFER to TRANSFERS.id, OperationType.INCOME to otherIncome.id, OperationType.REFUND to otherExpense.id),
            all().map { it.type to it.categoryId },
        )
    }

    @Test
    fun `own transfer is ignored but its key is remembered`() = runTest {
        assertEquals(ImportResult.IGNORED, import(notification(), parsed(NotificationKind.OWN_TRANSFER)))
        assertTrue(all().isEmpty())
        assertTrue("key-1" in notifications.keys)
    }

    @Test
    fun `same notification delivered again is a duplicate`() = runTest {
        import(notification(), parsed())
        assertEquals(ImportResult.DUPLICATE, import(notification(), parsed()))
        assertEquals(1, all().size)
    }

    @Test
    fun `notification left in the shade longer than a week is not imported again`() = runTest {
        // Ключ помнится неделю: после перезагрузки висящее уведомление вернуло бы удалённую операцию.
        val posted = now.minus(ImportNotification.KEY_LIFETIME).minusSeconds(60)
        val old = BankNotification("ru.sberbankmobile", "key-old", posted, "СберБанк", "текст")
        assertEquals(ImportResult.EXPIRED, import(old, parsed(at = posted, balance = 12_300)))
        assertEquals(0, all().size)
        assertEquals(null, recon.get(ReconAccount("sber", "1234")))
    }

    @Test
    fun `notification exactly a week old is still imported`() = runTest {
        // Ключи старше недели удаляются, а ровно недельный ещё помнится: граница не теряет уведомлений.
        val posted = now.minus(ImportNotification.KEY_LIFETIME)
        val edge = BankNotification("ru.sberbankmobile", "key-edge", posted, "СберБанк", "текст")
        assertEquals(ImportResult.IMPORTED, import(edge, parsed(at = posted)))
        assertEquals(ImportResult.DUPLICATE, import(edge, parsed(at = posted)))
        assertEquals(1, all().size)
    }

    @Test
    fun `two notifications for one purchase within two minutes give one operation`() = runTest {
        import(notification("a"), parsed())
        assertEquals(ImportResult.DUPLICATE, import(notification("b"), parsed(merchant = "ПЯТЁРОЧКА", at = now.plusSeconds(30))))
        assertEquals(1, all().size)
    }

    @Test
    fun `same purchase three minutes later is a new operation`() = runTest {
        import(notification("a"), parsed())
        assertEquals(ImportResult.IMPORTED, import(notification("b"), parsed(at = now.plus(Duration.ofMinutes(3)))))
        assertEquals(2, all().size)
    }

    @Test
    fun `deleted operation does not come back`() = runTest {
        import(notification(), parsed())
        operations.delete(all().single().id)
        assertEquals(ImportResult.DUPLICATE, import(notification(), parsed()))
        assertTrue(all().isEmpty())
    }

    @Test
    fun `stipend near the income date suggests a new period`() = runTest {
        periods.startNew(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), 18_000_00)
        import(notification(), parsed(NotificationKind.INCOME, rubles = 9_000, merchant = null))
        assertTrue(all().single().suggestsNewPeriod)
    }

    @Test
    fun `small transfer from a friend does not suggest a new period`() = runTest {
        periods.startNew(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 25), 18_000_00)
        import(notification(), parsed(NotificationKind.INCOME, rubles = 500, merchant = "Олег К."))
        assertFalse(all().single().suggestsNewPeriod)
    }

    @Test
    fun `big income or salary words suggest a new period`() = runTest {
        periods.startNew(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 25), 18_000_00)
        import(notification("big"), parsed(NotificationKind.INCOME, rubles = 9_000, merchant = null))
        import(notification("word"), parsed(NotificationKind.INCOME, rubles = 1_000, merchant = null, salary = true, at = now.plusSeconds(600)))
        assertEquals(listOf(true, true), all().map { it.suggestsNewPeriod })
    }

    // --- Переводы себе (design.md, решение 11) ---

    @Test
    fun `outgoing in one bank and incoming in another become one own transfer`() = runTest {
        import(notification("sber-out"), parsed(NotificationKind.TRANSFER_OUT, rubles = 5_000, merchant = null))
        import(notification("alfa-in"), parsed(NotificationKind.INCOME, bank = "alfa", rubles = 5_000, merchant = "Иван П.", at = now.plusSeconds(90)))
        val op = all().single()
        assertEquals(OperationType.OWN_TRANSFER, op.type)
        assertEquals(OperationType.TRANSFER, op.originalType)
        assertEquals("alfa", op.counterBankId)
        assertEquals(TRANSFERS.id, op.categoryId)
        assertTrue("пару подтверждает пользователь", op.isDraft)
        assertTrue("вторая сторона не вернётся", "alfa-in" in notifications.keys)
    }

    @Test
    fun `same amount in the same bank or too late is not a pair`() = runTest {
        import(notification("a"), parsed(NotificationKind.TRANSFER_OUT, rubles = 500, merchant = "Олег К."))
        import(notification("b"), parsed(NotificationKind.INCOME, rubles = 500, merchant = "Мама М.", at = now.plusSeconds(60)))
        import(notification("c"), parsed(NotificationKind.INCOME, bank = "alfa", rubles = 500, merchant = null, at = now.plus(Duration.ofMinutes(30))))
        assertEquals(listOf(OperationType.TRANSFER, OperationType.INCOME, OperationType.INCOME), all().map { it.type })
    }

    @Test
    fun `splitting a wrong pair gives two drafts back`() = runTest {
        import(notification("sber-out"), parsed(NotificationKind.TRANSFER_OUT, rubles = 5_000, merchant = null))
        import(notification("alfa-in"), parsed(NotificationKind.INCOME, bank = "alfa", rubles = 5_000, merchant = null, at = now.plusSeconds(90)))
        split(all().single().id)
        val ops = all().sortedBy { it.id }
        assertEquals(listOf(OperationType.TRANSFER, OperationType.INCOME), ops.map { it.type })
        assertEquals(listOf("sber", "alfa"), ops.map { it.bankId })
        assertEquals(listOf(TRANSFERS.id, otherIncome.id), ops.map { it.categoryId })
        assertTrue(ops.all { it.isDraft })
        assertEquals("alfa-in", ops[1].notificationKey)
    }

    @Test
    fun `marking one side as own transfer and remembering the name`() = runTest {
        import(notification("in-1"), parsed(NotificationKind.INCOME, bank = "alfa", rubles = 10, merchant = "ИВАН П."))
        markOwn(all().single().id, rememberName = true)
        val first = all().single()
        assertEquals(OperationType.OWN_TRANSFER, first.type)
        assertFalse(first.isDraft)

        // Следующий перевод от того же имени сразу становится переводом себе, без черновика.
        import(notification("in-2"), parsed(NotificationKind.INCOME, bank = "alfa", rubles = 300, merchant = "Иван П.", at = now.plusSeconds(3_600)))
        val second = all().single { it.notificationKey == "in-2" }
        assertEquals(OperationType.OWN_TRANSFER, second.type)
        assertFalse(second.isDraft)
    }

    @Test
    fun `remembered name still pairs with the other bank side`() = runTest {
        autoImport.addOwnName("иван п.")
        import(notification("alfa-in"), parsed(NotificationKind.INCOME, bank = "alfa", rubles = 5_000, merchant = "ИВАН П."))
        import(notification("sber-out"), parsed(NotificationKind.TRANSFER_OUT, rubles = 5_000, merchant = null, at = now.plusSeconds(60)))
        val op = all().single()
        assertEquals(OperationType.OWN_TRANSFER, op.type)
        assertEquals("sber", op.counterBankId)
        assertFalse("подтверждён по имени — остаётся подтверждённым", op.isDraft)
    }

    @Test
    fun `repeat of either side of a merged pair is a duplicate`() = runTest {
        import(notification("sber-out"), parsed(NotificationKind.TRANSFER_OUT, rubles = 5_000, merchant = null))
        import(notification("alfa-in"), parsed(NotificationKind.INCOME, bank = "alfa", rubles = 5_000, merchant = "ИВАН П.", at = now.plusSeconds(60)))
        notifications.keys.clear() // ключи истекли, уведомления ещё в шторке
        assertEquals(ImportResult.DUPLICATE, import(notification("alfa-in"), parsed(NotificationKind.INCOME, bank = "alfa", rubles = 5_000, merchant = "ИВАН П.", at = now.plusSeconds(60))))
        assertEquals(ImportResult.DUPLICATE, import(notification("alfa-other-key"), parsed(NotificationKind.INCOME, bank = "alfa", rubles = 5_000, merchant = "ИВАН П.", at = now.plusSeconds(70))))
        assertEquals(ImportResult.DUPLICATE, import(notification("sber-other-key"), parsed(NotificationKind.TRANSFER_OUT, rubles = 5_000, merchant = null, at = now.plusSeconds(20))))
        assertEquals(1, all().size)
    }

    @Test
    fun `confirmed operation is not taken into a pair`() = runTest {
        import(notification("sber-out"), parsed(NotificationKind.TRANSFER_OUT, rubles = 5_000, merchant = "Олег К."))
        confirm(mapOf(operations.observeDrafts().first().single() to CAFE.id))
        import(notification("alfa-in"), parsed(NotificationKind.INCOME, bank = "alfa", rubles = 5_000, merchant = null, at = now.plusSeconds(60)))
        assertEquals(listOf(OperationType.EXPENSE, OperationType.INCOME), all().sortedBy { it.id }.map { it.type })
    }

    @Test
    fun `split keeps the second side name and time`() = runTest {
        import(notification("sber-out"), parsed(NotificationKind.TRANSFER_OUT, rubles = 5_000, merchant = null))
        import(notification("alfa-in"), parsed(NotificationKind.INCOME, bank = "alfa", rubles = 5_000, merchant = "ИВАН П.", at = now.plusSeconds(300)))
        split(all().single().id)
        val incoming = all().single { it.bankId == "alfa" }
        assertEquals("ИВАН П.", incoming.merchant)
        assertEquals(now.plusSeconds(300), incoming.occurredAt.toInstant())
    }

    @Test
    fun `own transfer does not change the budget`() = runTest {
        periods.startNew(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 25), 18_000_00)
        import(notification("in"), parsed(NotificationKind.INCOME, bank = "alfa", rubles = 9_000, merchant = "Иван П."))
        assertTrue("крупное зачисление предлагает новый период", all().single().suggestsNewPeriod)
        markOwn(all().single().id, rememberName = false)
        assertFalse("перевод себе — не доход и не повод начинать период", all().single().suggestsNewPeriod)
    }

    @Test
    fun `confirming drafts sets category and type and keeps the rest`() = runTest {
        import(notification("a"), parsed(merchant = "Ozon"))
        import(notification("b"), parsed(NotificationKind.TRANSFER_OUT, rubles = 500, merchant = "Олег К."))
        import(notification("c"), parsed(rubles = 350, merchant = "ИП Смирнова"))
        val drafts = operations.observeDrafts().first()
        confirm(mapOf(drafts.first { it.operation.merchant == "Ozon" } to PRODUCTS.id, drafts.first { it.operation.merchant == "Олег К." } to CAFE.id))
        val byMerchant = all().associateBy { it.merchant }
        assertEquals(PRODUCTS.id, byMerchant["Ozon"]!!.categoryId)
        assertFalse(byMerchant["Ozon"]!!.isDraft)
        assertEquals("перевод с обычной категорией становится тратой", OperationType.EXPENSE, byMerchant["Олег К."]!!.type)
        assertTrue(byMerchant["ИП Смирнова"]!!.isDraft)
        assertNull(all().firstOrNull { it.localDate != TODAY })
    }

    // --- Сверка с банком (spec balance-reconciliation) ---

    private val card = ReconAccount("sber", "1234")

    @Test
    fun `first balance of a card becomes the anchor`() = runTest {
        import(notification(), parsed(balance = 8_000))
        val state = recon.get(card)!!
        assertEquals(8_000_00L, state.anchorKopecks)
        assertEquals(now, state.anchorAt)
        assertNull(state.pending)
    }

    @Test
    fun `purchase with matching balance moves the anchor`() = runTest {
        recon.save(ReconState(card, 12_750_00, now.minusSeconds(3600)))
        import(notification(), parsed(rubles = 450, balance = 12_300))
        val state = recon.get(card)!!
        assertNull(state.pending)
        assertEquals(12_300_00L, state.anchorKopecks)
        assertEquals(now, state.lastReconciledAt)
    }

    @Test
    fun `balance not matching the calculation is a mismatch and the next one is compared from the same anchor`() = runTest {
        recon.save(ReconState(card, 13_200_00, now.minusSeconds(3600)))
        import(notification(), parsed(rubles = 450, balance = 12_300))
        assertEquals(-450_00L, recon.get(card)!!.pending!!.diffKopecks)
        import(notification("key-2"), parsed(rubles = 100, merchant = "Кофейня", balance = 12_200, at = now.plusSeconds(60)))
        val state = recon.get(card)!!
        assertEquals(13_200_00L, state.anchorKopecks)
        assertEquals(12_200_00L, state.pending!!.bankKopecks)
        assertEquals(-450_00L, state.pending!!.diffKopecks)
    }

    @Test
    fun `duplicate and balance-less notifications are not reconciled`() = runTest {
        recon.save(ReconState(card, 13_200_00, now.minusSeconds(3600)))
        import(notification(), parsed(rubles = 450))
        assertNull(recon.get(card)!!.pending)
        import(notification(), parsed(rubles = 450, balance = 1_000))
        assertNull(recon.get(card)!!.pending)
        assertEquals(13_200_00L, recon.get(card)!!.anchorKopecks)
    }

    @Test
    fun `with auto reconciliation off the balance is only stored`() = runTest {
        reconSettings.state.value = false
        recon.save(ReconState(card, 13_200_00, now.minusSeconds(3600)))
        import(notification(), parsed(rubles = 450, balance = 1_000))
        assertNull(recon.get(card)!!.pending)
        assertEquals(13_200_00L, recon.get(card)!!.anchorKopecks)
        assertEquals(1, notifications.balances.size)
    }

    @Test
    fun `transfer between own accounts inside the bank rebases without comparing`() = runTest {
        recon.save(ReconState(card, 13_200_00, now.minusSeconds(3600)))
        assertEquals(ImportResult.IGNORED, import(notification(), parsed(kind = NotificationKind.OWN_TRANSFER, rubles = 3_000, merchant = null, balance = 10_200)))
        val state = recon.get(card)!!
        assertNull(state.pending)
        assertEquals(10_200_00L, state.anchorKopecks)
    }
}
