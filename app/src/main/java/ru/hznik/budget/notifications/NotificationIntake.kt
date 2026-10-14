package ru.hznik.budget.notifications

import dagger.Lazy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.hznik.budget.BuildConfig
import ru.hznik.budget.domain.notifications.BankNotification
import ru.hznik.budget.domain.notifications.NotificationRepository
import ru.hznik.budget.domain.notifications.SampleMasker
import ru.hznik.budget.domain.notifications.SampleSettings
import ru.hznik.budget.domain.notifications.SupportedBanks
import ru.hznik.budget.domain.notifications.parser.BankNotificationParser
import ru.hznik.budget.domain.security.DatabaseAccess
import ru.hznik.budget.domain.security.DatabaseState
import ru.hznik.budget.domain.usecase.ImportNotification
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Приём уведомления банка (design.md add-notification-import, решения 1–2). Сюда попадают только пакеты
 * из [SupportedBanks]; проверка повторяется и здесь, чтобы чужое уведомление не прошло даже при ошибке в сервисе.
 * Текст уведомления никуда не пишется, кроме замаскированного образца в режиме сбора образцов.
 */
@Singleton
class NotificationIntake @Inject constructor(
    private val database: DatabaseAccess,
    // Lazy: репозиторий тянет за собой Room, а базу можно создавать только после database.open() (проверки ключа).
    private val repositoryLazy: Lazy<NotificationRepository>,
    private val sampleSettings: SampleSettings,
    private val parsers: Set<@JvmSuppressWildcards BankNotificationParser>,
    // Lazy по той же причине, что и репозиторий: сценарий тянет за собой Room.
    private val importLazy: Lazy<ImportNotification>,
    private val clock: Clock,
) {
    private val repository: NotificationRepository get() = repositoryLazy.get()
    private val importMutex = Mutex()

    suspend fun accept(notification: BankNotification) {
        val bank = SupportedBanks.byPackage(notification.packageName) ?: return
        val text = notification.fullText
        if (text.isEmpty()) return
        // Сервис может проснуться без открытого приложения: базу сначала нужно проверить и открыть ключом.
        if (database.open() != DatabaseState.READY) return
        val cutoff = purgeExpiredSamples()
        // Режима образцов нет в релизной сборке, даже если флаг остался в настройках.
        if (BuildConfig.DEBUG && sampleSettings.enabled.first() && !notification.postedAt.isBefore(cutoff)) {
            repository.addSample(bank.id, notification.postedAt, SampleMasker.mask(text))
        }
        val parser = parsers.firstOrNull { notification.packageName in it.packageNames } ?: return
        // Непонятный формат — null: уведомление просто пропускается (NFR-02).
        val parsed = parser.parse(notification) ?: return
        // По одному: иначе два уведомления об одной покупке проходят проверку дублей одновременно (решение 5).
        importMutex.withLock { importLazy.get().invoke(notification, parsed) }
    }

    /** Удаляет образцы старше суток, даже если режим выключен; вызывается при каждом уведомлении банка, подключении сервиса и запуске приложения. */
    suspend fun purgeExpired() {
        if (database.open() != DatabaseState.READY) return
        purgeExpiredSamples()
    }

    private suspend fun purgeExpiredSamples() = clock.instant().minus(SAMPLE_LIFETIME).also { repository.deleteSamplesBefore(it) }

    companion object {
        val SAMPLE_LIFETIME: Duration = Duration.ofHours(24)
    }
}
