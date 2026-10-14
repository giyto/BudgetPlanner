package ru.hznik.budget.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.hznik.budget.data.local.StatisticsDao
import ru.hznik.budget.domain.repository.StatisticsRepository
import ru.hznik.budget.domain.stats.DateRange
import ru.hznik.budget.domain.stats.MerchantSpending
import ru.hznik.budget.domain.stats.RangeTotals
import java.time.LocalDate
import javax.inject.Inject

class RoomStatisticsRepository @Inject constructor(private val dao: StatisticsDao) : StatisticsRepository {
    override fun spendingByCategory(range: DateRange): Flow<Map<Long, Long>> =
        dao.spendingByCategory(range.from, range.to).map { rows -> rows.associate { it.categoryId to it.total } }

    override fun topMerchants(range: DateRange, limit: Int): Flow<List<MerchantSpending>> =
        dao.spendingByMerchant(range.from, range.to, limit).map { rows -> rows.map { MerchantSpending(it.merchant, it.count, it.total) } }

    override fun spendingByDay(range: DateRange): Flow<Map<LocalDate, Long>> =
        dao.spendingByDay(range.from, range.to).map { rows -> rows.associate { LocalDate.ofEpochDay(it.localDate) to it.total } }

    override fun totals(range: DateRange): Flow<RangeTotals> =
        dao.totals(range.from, range.to).map { RangeTotals(it.income, it.spent) }

    private val DateRange.from: Long get() = start.toEpochDay()
    private val DateRange.to: Long get() = endInclusive.toEpochDay()
}
