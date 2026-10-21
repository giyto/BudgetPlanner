package ru.hznik.budget.notifications

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.domain.notifications.BankNotification
import ru.hznik.budget.domain.security.DatabaseAccess
import ru.hznik.budget.domain.security.DatabaseState
import ru.hznik.budget.testing.TEST_CLOCK
import java.time.Duration
import java.time.Instant

/**
 * Приём уведомлений (spec notification-import: только банки из списка, режим образцов). В `testDebug`: запись
 * образцов есть только в отладочной сборке (задача 9.2 harden-and-release-v1).
 */
class NotificationIntakeTest {
    private val repository = FakeNotificationRepository()
    private val settings = FakeSampleSettings(initial = true)
    private val database = object : DatabaseAccess {
        var state = DatabaseState.READY
        override suspend fun open() = state
        override suspend fun wipe() = Unit
    }
    private val intake = NotificationIntake(database, { repository }, settings, emptySet(), { error("импорт не ожидается") }, { error("проверка не ожидается") }, TEST_CLOCK)
    private val now = TEST_CLOCK.instant()

    private fun notification(packageName: String = "ru.sberbankmobile", text: String = "Покупка 450 ₽ Пятёрочка. Карта *1234", at: Instant = now) =
        BankNotification(packageName, key = "0|$packageName|1|null|10001", postedAt = at, title = "СберБанк", text = text)

    @Test
    fun `messenger notification leaves no trace`() = runTest {
        intake.accept(notification(packageName = "org.telegram.messenger", text = "Перевёл тебе 500 ₽"))
        assertTrue(repository.samples.value.isEmpty())
        assertTrue(repository.keys.isEmpty())
        assertTrue(repository.balances.isEmpty())
    }

    @Test
    fun `bank notification becomes a masked sample once`() = runTest {
        intake.accept(notification())
        intake.accept(notification()) // повтор после переподключения сервиса
        assertEquals(listOf("СберБанк / Покупка 450 ₽ Пятёрочка. Карта *XXXX"), repository.samples.value.map { it.text })
        assertEquals("sber", repository.samples.value.single().bankId)
    }

    @Test
    fun `nothing is stored when sample mode is off`() = runTest {
        settings.state.value = false
        intake.accept(notification())
        assertTrue(repository.samples.value.isEmpty())
    }

    @Test
    fun `samples older than a day are removed`() = runTest {
        repository.addSample("sber", now.minus(Duration.ofHours(25)), "старый")
        intake.accept(notification())
        assertEquals(listOf("СберБанк / Покупка 450 ₽ Пятёрочка. Карта *XXXX"), repository.samples.value.map { it.text })
    }

    @Test
    fun `old samples are removed even when sample mode is off`() = runTest {
        settings.state.value = false
        repository.addSample("sber", now.minus(Duration.ofHours(25)), "старый")
        intake.accept(notification())
        assertTrue(repository.samples.value.isEmpty())
    }

    @Test
    fun `notification older than a day still in the shade is not stored`() = runTest {
        intake.accept(notification(at = now.minus(Duration.ofHours(30))))
        assertTrue(repository.samples.value.isEmpty())
    }

    @Test
    fun `nothing happens while the database key is lost`() = runTest {
        database.state = DatabaseState.KEY_LOST
        intake.accept(notification())
        assertTrue(repository.samples.value.isEmpty())
    }

    @Test
    fun `other supported banks are collected too`() = runTest {
        intake.accept(notification(packageName = "com.idamob.tinkoff.android", text = "Покупка 100 ₽"))
        assertEquals("tbank", repository.samples.value.single().bankId)
    }
}
