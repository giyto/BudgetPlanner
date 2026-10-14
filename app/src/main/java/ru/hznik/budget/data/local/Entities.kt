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
