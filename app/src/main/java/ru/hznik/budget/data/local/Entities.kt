package ru.hznik.budget.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Перечисления хранятся строками (имя константы): так схема не зависит от порядка констант в коде.

@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["kind", "name_search"], unique = true),
        Index(value = ["default_key"], unique = true),
    ],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val name: String,
    /** Название в нормализованном виде для поиска и проверки повторов без учёта регистра. */
    @ColumnInfo(name = "name_search") val nameSearch: String,
    val icon: String,
    val color: String,
    @ColumnInfo(name = "default_key") val defaultKey: String?,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    val hidden: Boolean,
)

@Entity(
    tableName = "operations",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("local_date"), Index("category_id"), Index("source"), Index("is_draft"), Index("notification_key")],
)
data class OperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    @ColumnInfo(name = "amount_kopecks") val amountKopecks: Long,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    val merchant: String?,
    val comment: String?,
    /** Магазин и комментарий в нормализованном виде для поиска. */
    @ColumnInfo(name = "search_text") val searchText: String,
    val source: String,
    /** Момент операции, мс UTC. */
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    /** Смещение часового пояса в момент операции. */
    @ColumnInfo(name = "utc_offset_seconds") val utcOffsetSeconds: Int,
    /** День операции по её часовому поясу, номер дня от эпохи. Вычисляется только в OperationMapper. */
    @ColumnInfo(name = "local_date") val localDate: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** Черновик из уведомления банка: в расчёте, но категория не подтверждена (spec drafts). */
    @ColumnInfo(name = "is_draft", defaultValue = "0") val isDraft: Boolean = false,
    @ColumnInfo(name = "bank_id") val bankId: String? = null,
    @ColumnInfo(name = "card_last4") val cardLast4: String? = null,
    /** Ключ уведомления, по которому создана операция (дедупликация). */
    @ColumnInfo(name = "notification_key") val notificationKey: String? = null,
    /** Зачисление похоже на стипендию или зарплату: на главной предложить новый период (сценарий 5.8). */
    @ColumnInfo(name = "suggests_new_period", defaultValue = "0") val suggestsNewPeriod: Boolean = false,
    /** Перевод себе: исходный тип и вторая сторона пары (этап add-notification-import, решение 11). */
    @ColumnInfo(name = "original_type") val originalType: String? = null,
    @ColumnInfo(name = "counter_bank_id") val counterBankId: String? = null,
    @ColumnInfo(name = "counter_card_last4") val counterCardLast4: String? = null,
    @ColumnInfo(name = "counter_notification_key") val counterNotificationKey: String? = null,
    @ColumnInfo(name = "counter_merchant") val counterMerchant: String? = null,
    /** Время уведомления второй стороны, мс UTC. */
    @ColumnInfo(name = "counter_occurred_at") val counterOccurredAt: Long? = null,
)

/** Строка списка: операция и её категория (колонки категории с префиксом c_). */
data class OperationWithCategory(
    @Embedded val operation: OperationEntity,
    @Embedded(prefix = "c_") val category: CategoryEntity,
)

data class CategoryUsage(
    @ColumnInfo(name = "category_id") val categoryId: Long,
    val count: Int,
)

/** Период бюджета. Текущий — единственный с `end_date IS NULL`; это обеспечивает PeriodRepository. */
@Entity(tableName = "periods", indices = [Index("end_date")])
data class PeriodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Даты — номер дня от эпохи, как local_date у операций. */
    @ColumnInfo(name = "start_date") val startDate: Long,
    @ColumnInfo(name = "next_income_date") val nextIncomeDate: Long,
    @ColumnInfo(name = "end_date") val endDate: Long?,
    @ColumnInfo(name = "start_amount_kopecks") val startAmountKopecks: Long,
)

@Entity(
    tableName = "planned_payments",
    foreignKeys = [
        ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.RESTRICT),
        // Удаление траты оплаты возвращает платёж в неоплаченные (spec planned-payments).
        ForeignKey(entity = OperationEntity::class, parentColumns = ["id"], childColumns = ["paid_operation_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("category_id"), Index("paid_operation_id"), Index("due_date")],
)
data class PlannedPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "amount_kopecks") val amountKopecks: Long,
    @ColumnInfo(name = "due_date") val dueDate: Long,
    val repeat: String,
    @ColumnInfo(name = "repeat_day") val repeatDay: Int,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "paid_operation_id") val paidOperationId: Long?,
)

/** Платёж вместе с днём траты оплаты (если оплачен). */
data class PlannedPaymentWithPaidDate(
    @Embedded val payment: PlannedPaymentEntity,
    @ColumnInfo(name = "paid_local_date") val paidLocalDate: Long?,
)

/** Остаток из уведомления банка для сверки (FR-13). */
@Entity(tableName = "bank_balances", indices = [Index(value = ["bank_id", "card_last4", "observed_at"])])
data class BankBalanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "bank_id") val bankId: String,
    @ColumnInfo(name = "card_last4") val cardLast4: String?,
    @ColumnInfo(name = "balance_kopecks") val balanceKopecks: Long,
    /** Время уведомления, мс UTC. */
    @ColumnInfo(name = "observed_at") val observedAt: Long,
)

/** Ключ уже обработанного уведомления банка: удалённая операция не возвращается при повторной доставке. */
@Entity(tableName = "notification_keys", indices = [Index("seen_at")])
data class NotificationKeyEntity(
    @PrimaryKey val key: String,
    @ColumnInfo(name = "bank_id") val bankId: String,
    @ColumnInfo(name = "seen_at") val seenAt: Long,
)

/** Замаскированный текст уведомления банка в режиме сбора образцов; живёт 24 часа. */
@Entity(
    tableName = "notification_samples",
    indices = [Index("posted_at"), Index(value = ["bank_id", "posted_at", "text"], unique = true)],
)
data class NotificationSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "bank_id") val bankId: String,
    @ColumnInfo(name = "posted_at") val postedAt: Long,
    val text: String,
)

/**
 * Состояние сверки счёта с банком (FR-13). Счёт — банк и последние 4 цифры карты; пустые строки вместо `null`,
 * чтобы уникальный индекс работал: `''` в `bank_id` — «Счёт без автоимпорта» (design.md add-balance-reconciliation, 1–2).
 */
@Entity(tableName = "reconciliation_accounts", indices = [Index(value = ["bank_id", "card_last4"], unique = true)])
data class ReconciliationAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "bank_id") val bankId: String,
    @ColumnInfo(name = "card_last4") val cardLast4: String,
    @ColumnInfo(name = "anchor_kopecks") val anchorKopecks: Long,
    /** Момент точки отсчёта, мс UTC. */
    @ColumnInfo(name = "anchor_at") val anchorAt: Long,
    /** Расхождение, ждущее решения: остаток банка, ожидаемый остаток и момент остатка. */
    @ColumnInfo(name = "pending_bank_kopecks") val pendingBankKopecks: Long? = null,
    @ColumnInfo(name = "pending_expected_kopecks") val pendingExpectedKopecks: Long? = null,
    @ColumnInfo(name = "pending_at") val pendingAt: Long? = null,
    @ColumnInfo(name = "last_reconciled_at") val lastReconciledAt: Long? = null,
)

/** Счёт банка, встреченный в операциях или остатках. */
data class AccountRow(
    @ColumnInfo(name = "bank_id") val bankId: String,
    @ColumnInfo(name = "card_last4") val cardLast4: String?,
)

/** Последний остаток по счёту. */
data class LatestBalanceRow(
    @ColumnInfo(name = "bank_id") val bankId: String,
    @ColumnInfo(name = "card_last4") val cardLast4: String?,
    @ColumnInfo(name = "balance_kopecks") val balanceKopecks: Long,
    @ColumnInfo(name = "observed_at") val observedAt: Long,
)
