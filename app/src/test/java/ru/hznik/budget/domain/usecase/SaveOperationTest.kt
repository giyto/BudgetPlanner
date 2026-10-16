package ru.hznik.budget.domain.usecase

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.testing.CAFE
import ru.hznik.budget.testing.FakeCategoryRepository
import ru.hznik.budget.testing.FakeOperationRepository
import ru.hznik.budget.testing.FakeReconciliationRepository
import ru.hznik.budget.testing.SALARY
import ru.hznik.budget.testing.TEST_CLOCK
import ru.hznik.budget.testing.TODAY
import ru.hznik.budget.testing.TRANSFERS
import ru.hznik.budget.testing.at

class SaveOperationTest {
    private val categories = FakeCategoryRepository()
    private val operations = FakeOperationRepository(categories)
    private val save = SaveOperation(operations, categories, PlaceOperationForAccount(FakeReconciliationRepository(operations)), TEST_CLOCK)

    private fun draft(
        inputType: InputType = InputType.EXPENSE,
        amount: Money? = Money(18_000),
        categoryId: Long? = CAFE.id,
        comment: String = "",
        date: java.time.LocalDate = TODAY,
        id: Long? = null,
    ) = OperationDraft(id = id, inputType = inputType, amount = amount, categoryId = categoryId, comment = comment, occurredAt = at(date))

    private fun saved(result: SaveResult): Operation {
        val id = (result as SaveResult.Saved).id
        return operations.state.value.single { it.id == id }
    }

    @Test
    fun `quick expense is saved as manual expense`() = runTest {
        val op = saved(save(draft()))

        assertEquals(OperationType.EXPENSE, op.type)
        assertEquals(18_000L, op.amount.kopecks)
        assertEquals(OperationSource.MANUAL, op.source)
        assertEquals(TODAY, op.localDate)
    }

    @Test
    fun `missing amount and category are rejected`() = runTest {
        assertEquals(SaveResult.Invalid(SaveError.MISSING_AMOUNT), save(draft(amount = null)))
        assertEquals(SaveResult.Invalid(SaveError.MISSING_CATEGORY), save(draft(categoryId = null)))
        assertEquals(SaveResult.Invalid(SaveError.MISSING_CATEGORY), save(draft(categoryId = 999)))
    }

    @Test
    fun `income needs income category`() = runTest {
        assertEquals(SaveResult.Invalid(SaveError.CATEGORY_KIND_MISMATCH), save(draft(inputType = InputType.INCOME, categoryId = CAFE.id)))
        assertEquals(SaveResult.Invalid(SaveError.CATEGORY_KIND_MISMATCH), save(draft(inputType = InputType.EXPENSE, categoryId = SALARY.id)))
        assertEquals(OperationType.INCOME, saved(save(draft(inputType = InputType.INCOME, categoryId = SALARY.id))).type)
    }

    @Test
    fun `future date is rejected, today and yesterday are accepted`() = runTest {
        assertEquals(SaveResult.Invalid(SaveError.FUTURE_DATE), save(draft(date = TODAY.plusDays(1))))
        assertEquals(TODAY, saved(save(draft(date = TODAY))).localDate)
        assertEquals(TODAY.minusDays(1), saved(save(draft(date = TODAY.minusDays(1)))).localDate)
    }

    @Test
    fun `expense with transfers category becomes transfer`() = runTest {
        assertEquals(OperationType.TRANSFER, saved(save(draft(categoryId = TRANSFERS.id))).type)
    }

    @Test
    fun `refund keeps refund type with expense category`() = runTest {
        assertEquals(OperationType.REFUND, saved(save(draft(inputType = InputType.REFUND))).type)
    }

    @Test
    fun `comment is trimmed, blank becomes null, too long is rejected`() = runTest {
        assertEquals("кофе с собой", saved(save(draft(comment = "  кофе с собой "))).comment)
        assertNull(saved(save(draft(comment = "   "))).comment)
        assertEquals(SaveResult.Invalid(SaveError.COMMENT_TOO_LONG), save(draft(comment = "а".repeat(201))))
    }

    @Test
    fun `editing keeps id, source and merchant`() = runTest {
        val original = Operation(
            type = OperationType.EXPENSE, amount = Money(45_000), categoryId = CAFE.id, merchant = "Пятёрочка",
            source = OperationSource.NOTIFICATION, occurredAt = at(TODAY),
        )
        val id = operations.insert(original)

        save(draft(id = id, amount = Money(21_000)))

        val edited = operations.state.value.single()
        assertEquals(id, edited.id)
        assertEquals(21_000L, edited.amount.kopecks)
        assertEquals(OperationSource.NOTIFICATION, edited.source)
        assertEquals("Пятёрочка", edited.merchant)
    }

    @Test
    fun `editing missing operation is rejected`() = runTest {
        assertEquals(SaveResult.Invalid(SaveError.NOT_FOUND), save(draft(id = 42)))
    }
}
