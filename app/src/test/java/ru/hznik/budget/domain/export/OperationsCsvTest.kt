package ru.hznik.budget.domain.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationItem
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.testing.PRODUCTS
import ru.hznik.budget.testing.SALARY
import ru.hznik.budget.testing.TRANSFERS
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** CSV операций (spec data-export «Экспорт операций в CSV», «Защита от формул в CSV»; задача 2.1). */
class OperationsCsvTest {
    private val moscow = ZoneOffset.ofHours(3)

    private fun item(
        type: OperationType = OperationType.EXPENSE,
        kopecks: Long = 45_000,
        merchant: String? = "Пятёрочка",
        comment: String? = null,
        source: OperationSource = OperationSource.MANUAL,
        draft: Boolean = false,
        bank: String? = null,
        card: String? = null,
        original: OperationType? = null,
        at: OffsetDateTime = OffsetDateTime.of(2026, 10, 17, 9, 5, 0, 0, moscow),
    ) = OperationItem(
        Operation(
            type = type, amount = Money(kopecks), categoryId = 1, merchant = merchant, comment = comment, source = source,
            occurredAt = at, isDraft = draft, bankId = bank, cardLast4 = card, originalType = original,
        ),
        when (type) {
            OperationType.INCOME -> SALARY
            OperationType.OWN_TRANSFER, OperationType.TRANSFER -> TRANSFERS
            else -> PRODUCTS
        },
    )

    @Test
    fun `file starts with BOM and header separated by semicolons`() {
        val csv = OperationsCsv.render(emptyList())
        assertTrue(csv.startsWith("﻿"))
        assertEquals("Дата;Время;Тип;Сумма;Категория;Магазин или описание;Комментарий;Источник;Банк;Черновик\r\n", csv.removePrefix("﻿"))
    }

    @Test
    fun `purchase from notification becomes one row without card digits`() {
        val row = OperationsCsv.row(item(source = OperationSource.NOTIFICATION, draft = true, bank = "sber", card = "1234"))
        assertEquals(listOf("17.10.2026", "09:05", "Трата", "-450,00", "Продукты", "Пятёрочка", "", "Уведомление банка", "Сбер", "да"), row)
        assertFalse(OperationsCsv.render(listOf(item(bank = "sber", card = "1234"))).contains("1234"))
    }

    @Test
    fun `amounts use comma, kopecks and sign by type`() {
        assertEquals("-450,00", OperationsCsv.row(item(type = OperationType.EXPENSE))[3])
        assertEquals("12000,00", OperationsCsv.row(item(type = OperationType.INCOME, kopecks = 1_200_000))[3])
        assertEquals("1234,05", OperationsCsv.row(item(type = OperationType.REFUND, kopecks = 123_405))[3])
        assertEquals("-3000,00", OperationsCsv.row(item(type = OperationType.TRANSFER, kopecks = 300_000))[3])
        // Перевод себе: знак по тому, каким он пришёл из уведомления.
        assertEquals("-500,00", OperationsCsv.row(item(type = OperationType.OWN_TRANSFER, kopecks = 50_000, original = OperationType.TRANSFER))[3])
        assertEquals("500,00", OperationsCsv.row(item(type = OperationType.OWN_TRANSFER, kopecks = 50_000, original = OperationType.INCOME))[3])
        assertEquals("0,07", OperationsCsv.amount(7, outgoing = false))
    }

    @Test
    fun `time is in the operation's own offset`() {
        val row = OperationsCsv.row(item(at = OffsetDateTime.of(2026, 10, 17, 23, 30, 0, 0, moscow)))
        assertEquals("17.10.2026", row[0])
        assertEquals("23:30", row[1])
    }

    @Test
    fun `semicolons, quotes and line breaks are quoted`() {
        val row = OperationsCsv.row(item(merchant = "ООО \"Ромашка\"; филиал", comment = "кофе\nи булка"))
        assertEquals("\"ООО \"\"Ромашка\"\"; филиал\"", row[5])
        assertEquals("\"кофе\nи булка\"", row[6])
    }

    @Test
    fun `text that looks like a formula gets an apostrophe`() {
        assertEquals("'=HYPERLINK(\"x\")", OperationsCsv.row(item(merchant = "=HYPERLINK(\"x\")"))[5].replace("\"\"", "\"").removeSurrounding("\""))
        assertEquals("'+79991234567", OperationsCsv.text("+79991234567"))
        assertEquals("'-дорого", OperationsCsv.text("-дорого"))
        assertEquals("'@кафе", OperationsCsv.text("@кафе"))
        assertEquals("Магнит", OperationsCsv.text("Магнит"))
    }

    @Test
    fun `manual adjustment and own transfer are named`() {
        val adjustment = OperationsCsv.row(item(source = OperationSource.ADJUSTMENT, merchant = null, comment = "Корректировка"))
        assertEquals("Корректировка", adjustment[7])
        assertEquals("Перевод себе", OperationsCsv.row(item(type = OperationType.OWN_TRANSFER, original = OperationType.TRANSFER))[2])
        assertEquals("нет", adjustment[9])
    }
}
