package ru.hznik.budget.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

private const val SPENDING =
    "CASE type WHEN 'EXPENSE' THEN amount_kopecks WHEN 'TRANSFER' THEN amount_kopecks WHEN 'REFUND' THEN -amount_kopecks ELSE 0 END"

/**
 * Агрегаты статистики запросами с группировкой: операции в память не загружаются (spec statistics, NFR-01).
 * Трата — «трата» и «перевод» минус возврат, как в расчёте периода (FR-03); диапазон — по `local_date` включительно.
 */
@Dao
interface StatisticsDao {
    @Query(
        "SELECT category_id, SUM($SPENDING) AS total FROM operations " +
            "WHERE local_date BETWEEN :fromEpochDay AND :toEpochDay AND type != 'INCOME' GROUP BY category_id",
    )
    fun spendingByCategory(fromEpochDay: Long, toEpochDay: Long): Flow<List<CategoryTotalRow>>

    @Query(
        "SELECT merchant, COUNT(*) AS count, SUM($SPENDING) AS total FROM operations " +
            "WHERE local_date BETWEEN :fromEpochDay AND :toEpochDay AND type != 'INCOME' AND merchant IS NOT NULL AND merchant != '' " +
            "GROUP BY merchant HAVING total > 0 ORDER BY total DESC LIMIT :limit",
    )
    fun spendingByMerchant(fromEpochDay: Long, toEpochDay: Long, limit: Int): Flow<List<MerchantTotalRow>>

    @Query(
        "SELECT local_date, SUM($SPENDING) AS total FROM operations " +
            "WHERE local_date BETWEEN :fromEpochDay AND :toEpochDay AND type != 'INCOME' GROUP BY local_date",
    )
    fun spendingByDay(fromEpochDay: Long, toEpochDay: Long): Flow<List<DayTotalRow>>

    @Query(
        "SELECT COALESCE(SUM(CASE type WHEN 'INCOME' THEN amount_kopecks ELSE 0 END), 0) AS income, " +
            "COALESCE(SUM($SPENDING), 0) AS spent FROM operations WHERE local_date BETWEEN :fromEpochDay AND :toEpochDay",
    )
    fun totals(fromEpochDay: Long, toEpochDay: Long): Flow<TotalsRow>
}

data class CategoryTotalRow(@ColumnInfo(name = "category_id") val categoryId: Long, val total: Long)

data class MerchantTotalRow(val merchant: String, val count: Int, val total: Long)

data class DayTotalRow(@ColumnInfo(name = "local_date") val localDate: Long, val total: Long)

data class TotalsRow(val income: Long, val spent: Long)
