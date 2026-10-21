package ru.hznik.budget.domain.export

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.stats.DateRange
import ru.hznik.budget.testing.FakeCategoryRepository
import ru.hznik.budget.testing.FakeOperationRepository
import ru.hznik.budget.testing.PRODUCTS
import java.time.LocalDate
import java.time.ZoneOffset

/** Сценарий экспорта (spec data-export «Экспорт операций в CSV», «Нет операций за даты»; задача 2.2). */
class ExportOperationsTest {
    private val operations = FakeOperationRepository(FakeCategoryRepository())
    private val export = ExportOperations(operations)
    private val october = DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 25))

    private suspend fun add(day: Int, hour: Int, merchant: String) = operations.insert(
        Operation(
            type = OperationType.EXPENSE, amount = Money(10_000), categoryId = PRODUCTS.id, merchant = merchant,
            source = OperationSource.MANUAL, occurredAt = LocalDate.of(2026, 10, day).atTime(hour, 0).atOffset(ZoneOffset.ofHours(3)),
        ),
    )

    @Test
    fun `operations inside the dates go to the file from early to late`() = runTest {
        add(26, 10, "После периода")
        add(5, 18, "Вечер")
        add(5, 9, "Утро")
        add(1, 0, "Первый день")
        add(25, 23, "Последний день")
        val lines = export(october)!!.removePrefix(OperationsCsv.BOM).trimEnd().split("\r\n")
        assertEquals(listOf("Первый день", "Утро", "Вечер", "Последний день"), lines.drop(1).map { it.split(";")[5] })
        assertEquals(4, export.observeCount(october).first())
    }

    @Test
    fun `no operations gives no file`() = runTest {
        add(26, 10, "После периода")
        assertNull(export(october))
        assertEquals(0, export.observeCount(october).first())
    }
}
