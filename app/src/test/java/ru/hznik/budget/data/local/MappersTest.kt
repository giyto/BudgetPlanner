package ru.hznik.budget.data.local

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class MappersTest {
    private fun operation(at: LocalDateTime, zone: String, merchant: String? = null, comment: String? = null) = Operation(
        type = OperationType.EXPENSE, amount = Money(45_000), categoryId = 1, merchant = merchant, comment = comment,
        source = OperationSource.MANUAL, occurredAt = at.atZone(ZoneId.of(zone)).toOffsetDateTime(),
    )

    @Test
    fun `local date is the day in the operation time zone, at the edge of the day`() {
        val lateEvening = operation(LocalDateTime.of(2026, 10, 12, 23, 59), "Europe/Moscow")
        val afterMidnight = operation(LocalDateTime.of(2026, 10, 13, 0, 1), "Europe/Moscow")

        assertEquals(LocalDate.of(2026, 10, 12).toEpochDay(), lateEvening.toEntity(0).localDate)
        assertEquals(LocalDate.of(2026, 10, 13).toEpochDay(), afterMidnight.toEntity(0).localDate)
    }

    @Test
    fun `day does not move after the phone changes time zone`() {
        // 23:30 по Москве — это уже 13 октября во Владивостоке, но операция остаётся 12 октября.
        val entity = operation(LocalDateTime.of(2026, 10, 12, 23, 30), "Europe/Moscow").toEntity(0)

        val restored = entity.toDomain()
        assertEquals(LocalDate.of(2026, 10, 12), restored.localDate)
        assertEquals(3 * 3600, entity.utcOffsetSeconds)
    }

    @Test
    fun `round trip keeps all fields`() {
        val original = operation(LocalDateTime.of(2026, 10, 13, 12, 40), "Europe/Moscow", merchant = "Пятёрочка", comment = "молоко")

        assertEquals(original, original.toEntity(0).toDomain())
    }

    @Test
    fun `search text is normalized merchant and comment`() {
        val entity = operation(LocalDateTime.of(2026, 10, 13, 12, 0), "Europe/Moscow", merchant = "Пятёрочка", comment = "Молоко").toEntity(0)

        assertEquals("пятерочка молоко", entity.searchText)
    }

    @Test
    fun `like special characters are escaped`() {
        assertEquals("50\\%", escapeLike("50%"))
        assertEquals("a\\_b", escapeLike("a_b"))
    }
}
