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
    indices = [Index("local_date"), Index("category_id"), Index("source")],
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
