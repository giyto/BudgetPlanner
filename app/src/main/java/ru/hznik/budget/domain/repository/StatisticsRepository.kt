package ru.hznik.budget.domain.repository

import kotlinx.coroutines.flow.Flow
import ru.hznik.budget.domain.stats.DateRange
import ru.hznik.budget.domain.stats.MerchantSpending
import ru.hznik.budget.domain.stats.RangeTotals
import java.time.LocalDate

/**
 * Агрегаты трат за диапазон дней (design.md, решение 2). Трата — «трата» и «перевод» минус возврат.
 * Эти же агрегаты на этапе ИИ-помощника отдадут инструменты `get_spending_by_category` и `get_top_merchants`.
 */
interface StatisticsRepository {
    /** Траты по id категории, возвраты уже вычтены; категория может уйти в минус. */
    fun spendingByCategory(range: DateRange): Flow<Map<Long, Long>>

    /** До [limit] магазинов с наибольшей суммой трат; только операции с указанным магазином. */
    fun topMerchants(range: DateRange, limit: Int): Flow<List<MerchantSpending>>

    /** Траты по дням; дней без операций в ответе нет. */
    fun spendingByDay(range: DateRange): Flow<Map<LocalDate, Long>>

    fun totals(range: DateRange): Flow<RangeTotals>
}
