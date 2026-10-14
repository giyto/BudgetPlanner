package ru.hznik.budget.domain.notifications.parser

import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.notifications.BankNotification
import java.time.Instant

/** Вид операции в уведомлении банка (FR-05). */
enum class NotificationKind { PURCHASE, TRANSFER_OUT, INCOME, REFUND, OWN_TRANSFER }

/** Разобранное уведомление: только поля, без исходного текста. */
data class ParsedNotification(
    val bankId: String,
    val kind: NotificationKind,
    val amount: Money,
    /** Магазин, получатель или отправитель. */
    val merchant: String?,
    val balance: Money?,
    val cardLast4: String?,
    val occurredAt: Instant,
    /** Слова «зарплата», «стипендия», «аванс» в тексте зачисления (сценарий 5.8). */
    val looksLikeSalary: Boolean = false,
)

/** Парсер одного банка; новый банк — новый класс и одна привязка в Hilt (FR-05). */
interface BankNotificationParser {
    val bankId: String
    val packageNames: Set<String>

    /** `null` — реклама, код подтверждения или неизвестный формат: уведомление пропускается. */
    fun parse(notification: BankNotification): ParsedNotification?
}
