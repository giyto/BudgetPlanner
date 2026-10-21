package ru.hznik.budget.notifications

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.notifications.AutoImportSettings
import ru.hznik.budget.domain.notifications.NotificationRepository
import ru.hznik.budget.domain.notifications.NotificationSample
import ru.hznik.budget.domain.notifications.SampleSettings
import java.time.Instant

// Заменители хранилищ уведомлений для unit-тестов приёма, импорта и проверок.
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
