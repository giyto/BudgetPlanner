package ru.hznik.budget.domain.export

import kotlinx.coroutines.flow.Flow
import ru.hznik.budget.domain.repository.OperationRepository
import ru.hznik.budget.domain.stats.DateRange
import javax.inject.Inject

/** Экспорт операций за даты в CSV (spec data-export «Экспорт операций в CSV»). */
class ExportOperations @Inject constructor(private val operations: OperationRepository) {
    /** Текст файла или `null`, если за даты операций нет — тогда файл не создаётся («Нет операций за эти даты»). */
    suspend operator fun invoke(range: DateRange): String? {
        val items = operations.getForExport(range)
        return if (items.isEmpty()) null else OperationsCsv.render(items)
    }

    /** Сколько операций попадёт в файл — число на экране экспорта. */
    fun observeCount(range: DateRange): Flow<Int> = operations.observeCount(range)
}
