package ru.hznik.budget.domain.notifications

import java.time.Instant

/** Банк, уведомления которого приложение принимает (FR-05). */
data class Bank(val id: String, val name: String, val packageNames: Set<String>)

/**
 * Банки, чьи уведомления приложение вообще читает. Уведомления остальных пакетов отбрасываются сразу
 * (раздел 10 ТЗ). У каждого банка свой разборщик уведомлений.
 */
object SupportedBanks {
    val SBER = Bank("sber", "Сбер", setOf("ru.sberbankmobile"))
    val TBANK = Bank("tbank", "Т-Банк", setOf("com.idamob.tinkoff.android"))
    val ALFA = Bank("alfa", "Альфа-Банк", setOf("ru.alfabank.mobile.android"))

    val all: List<Bank> = listOf(SBER, TBANK, ALFA)

    private val byPackage: Map<String, Bank> = all.flatMap { bank -> bank.packageNames.map { it to bank } }.toMap()

    fun byPackage(packageName: String): Bank? = byPackage[packageName]
}

/** Уведомление банка из списка, как его видит приложение: только заголовок и текст, без вложений. */
data class BankNotification(
    val packageName: String,
    /** `StatusBarNotification.key`: стабилен при повторной доставке и в `getActiveNotifications`. */
    val key: String,
    val postedAt: Instant,
    val title: String?,
    val text: String?,
) {
    /** Заголовок и текст одной строкой, как их увидит парсер. */
    val fullText: String get() = listOfNotNull(title?.trim(), text?.trim()).filter { it.isNotEmpty() }.joinToString(" / ")
}

/** Замаскированный образец уведомления (режим сбора образцов, spec notification-import). */
data class NotificationSample(val id: Long, val bankId: String, val postedAt: Instant, val text: String)
