package ru.hznik.budget.notifications

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.notifications.AutoImportSettings
import ru.hznik.budget.domain.notifications.BankNotification
import ru.hznik.budget.domain.notifications.NotificationRepository
import ru.hznik.budget.domain.notifications.NotificationSample
import ru.hznik.budget.domain.notifications.SampleSettings
import ru.hznik.budget.domain.security.DatabaseAccess
import ru.hznik.budget.domain.security.DatabaseState
import ru.hznik.budget.testing.TEST_CLOCK
import java.time.Duration
import java.time.Instant

class FakeNotificationRepository : NotificationRepository {
    val samples = MutableStateFlow<List<NotificationSample>>(emptyList())
    val keys = mutableSetOf<String>()
    val balances = mutableListOf<Triple<String, String?, Money>>()

    override fun observeSamples(since: Instant): Flow<List<NotificationSample>> = samples.map { list -> list.filter { !it.postedAt.isBefore(since) } }

    override suspend fun addSample(bankId: String, postedAt: Instant, text: String) {
        if (samples.value.any { it.bankId == bankId && it.postedAt == postedAt && it.text == text }) return
        samples.value = samples.value + NotificationSample(samples.value.size + 1L, bankId, postedAt, text)
    }

    override suspend fun deleteSamplesBefore(before: Instant) {
        samples.value = samples.value.filter { !it.postedAt.isBefore(before) }
    }

    override suspend fun deleteAllSamples() {
        samples.value = emptyList()
    }

    override suspend fun markSeen(key: String, bankId: String, at: Instant): Boolean = keys.add(key)

    override suspend fun isSeen(key: String): Boolean = key in keys

    override suspend fun deleteKeysBefore(before: Instant) = Unit

    override suspend fun addBalance(bankId: String, cardLast4: String?, balance: Money, observedAt: Instant) {
        balances += Triple(bankId, cardLast4, balance)
    }
}

class FakeAutoImportSettings : AutoImportSettings {
    val names = MutableStateFlow<Set<String>>(emptySet())
    override val wasEnabled: Flow<Boolean> = MutableStateFlow(false)
    override val accessAttempted: Flow<Boolean> = MutableStateFlow(false)
    override val restrictedVisited: Flow<Boolean> = MutableStateFlow(false)
    override val autostartDone: Flow<Boolean> = MutableStateFlow(false)
    override val ownNames: Flow<Set<String>> = names

    override suspend fun setWasEnabled() = Unit

    override suspend fun setAccessAttempted() = Unit

    override suspend fun setRestrictedVisited() = Unit

    override suspend fun setAutostartDone() = Unit

    override suspend fun addOwnName(normalizedName: String) {
        names.value = names.value + normalizedName
    }
}

class FakeSampleSettings(initial: Boolean) : SampleSettings {
    val state = MutableStateFlow(initial)
    override val enabled: Flow<Boolean> = state

    override suspend fun setEnabled(enabled: Boolean) {
        state.value = enabled
    }
}

/** Приём уведомлений (spec notification-import: только банки из списка, режим образцов). */
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
