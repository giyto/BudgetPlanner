package ru.hznik.budget.data.demo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ru.hznik.budget.data.local.PeriodDao
import ru.hznik.budget.data.local.PeriodEntity
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.OperationRepository
import ru.hznik.budget.domain.time.today
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Заполнение и удаление демо-данных (только отладочная сборка, раздел «Для разработки» в настройках).
 * id добавленных операций и периодов запоминаются, поэтому удаление не трогает настоящие данные.
 */
@Singleton
class DemoData @Inject constructor(
    private val categories: CategoryRepository,
    private val operations: OperationRepository,
    private val periodDao: PeriodDao,
    private val dataStore: DataStore<Preferences>,
    private val clock: Clock,
) {
    val hasDemo: Flow<Boolean> = dataStore.data.map { !it[OPERATION_IDS].isNullOrEmpty() || !it[PERIOD_IDS].isNullOrEmpty() }

    sealed interface Result {
        data class Filled(val operations: Int, val periods: Int) : Result
        data object NoPeriod : Result
        data object AlreadyFilled : Result
    }

    suspend fun fill(): Result {
        if (hasDemo.first()) return Result.AlreadyFilled
        val current = periodDao.getCurrent() ?: return Result.NoPeriod
        val earliest = periodDao.getAll().minOf { it.startDate }
        val plan = DemoDataPlan.generate(LocalDate.ofEpochDay(current.startDate), LocalDate.ofEpochDay(earliest), clock.today())

        val byKey = categories.observeAll().first().filter { it.defaultKey != null }.associateBy { it.defaultKey }
        val periodIds = plan.periods.map { p ->
            periodDao.insert(
                PeriodEntity(startDate = p.start.toEpochDay(), nextIncomeDate = p.end.toEpochDay(), endDate = p.end.toEpochDay(), startAmountKopecks = p.startAmountKopecks),
            )
        }
        val operationIds = plan.operations.mapNotNull { op ->
            val category = byKey[op.categoryKey] ?: return@mapNotNull null
            operations.insert(
                Operation(
                    type = op.type,
                    amount = Money(op.kopecks),
                    categoryId = category.id,
                    merchant = op.merchant,
                    comment = op.comment,
                    source = OperationSource.MANUAL,
                    occurredAt = op.at.atZone(clock.zone).toOffsetDateTime(),
                ),
            )
        }
        dataStore.edit {
            it[OPERATION_IDS] = operationIds.map(Long::toString).toSet()
            it[PERIOD_IDS] = periodIds.map(Long::toString).toSet()
        }
        return Result.Filled(operationIds.size, periodIds.size)
    }

    /** Удаляет только запомненные демо-записи; возвращает число удалённых операций. */
    suspend fun clear(): Int {
        val prefs = dataStore.data.first()
        val operationIds = prefs[OPERATION_IDS].orEmpty().mapNotNull(String::toLongOrNull)
        operationIds.forEach { operations.delete(it) }
        prefs[PERIOD_IDS].orEmpty().mapNotNull(String::toLongOrNull).forEach { periodDao.delete(it) }
        dataStore.edit {
            it.remove(OPERATION_IDS)
            it.remove(PERIOD_IDS)
        }
        return operationIds.size
    }

    private companion object {
        val OPERATION_IDS = stringSetPreferencesKey("demo_operation_ids")
        val PERIOD_IDS = stringSetPreferencesKey("demo_period_ids")
    }
}
