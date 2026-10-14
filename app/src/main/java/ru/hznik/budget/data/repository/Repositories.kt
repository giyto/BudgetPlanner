package ru.hznik.budget.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.hznik.budget.data.local.CategoryDao
import ru.hznik.budget.data.local.OperationDao
import ru.hznik.budget.data.local.escapeLike
import ru.hznik.budget.data.local.toDomain
import ru.hznik.budget.data.local.toEntity
import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationItem
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.OperationFilter
import ru.hznik.budget.domain.repository.OperationRepository
import ru.hznik.budget.domain.search.normalizeForSearch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

class RoomOperationRepository @Inject constructor(
    private val dao: OperationDao,
    private val clock: Clock,
) : OperationRepository {
    override fun observe(filter: OperationFilter): Flow<List<OperationItem>> =
        dao.observe(
            query = escapeLike(filter.query.normalized),
            rubles = filter.query.rubles,
            source = filter.source?.name,
            categoryId = filter.categoryId,
        ).map { rows -> rows.map { it.toDomain() } }

    override fun observeCategoryUsage(since: LocalDate): Flow<Map<Long, Int>> =
        dao.observeCategoryUsage(since.toEpochDay()).map { rows -> rows.associate { it.categoryId to it.count } }

    override suspend fun get(id: Long): Operation? = dao.get(id)?.toDomain()

    override suspend fun insert(operation: Operation): Long =
        dao.insert(operation.copy(id = 0).toEntity(createdAt = clock.millis()))

    override suspend fun update(operation: Operation) {
        val createdAt = dao.get(operation.id)?.createdAt ?: clock.millis()
        dao.update(operation.toEntity(createdAt))
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun restore(operation: Operation) {
        dao.insert(operation.toEntity(createdAt = clock.millis()))
    }
}

class RoomCategoryRepository @Inject constructor(private val dao: CategoryDao) : CategoryRepository {
    override fun observeAll(): Flow<List<Category>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun get(id: Long): Category? = dao.get(id)?.toDomain()

    override suspend fun nameExists(kind: CategoryKind, name: String, excludeId: Long?): Boolean =
        dao.countByName(kind.name, normalizeForSearch(name), excludeId) > 0

    override suspend fun hasOperations(id: Long): Boolean = dao.hasOperations(id)

    override suspend fun nextSortOrder(kind: CategoryKind): Int = dao.maxSortOrder(kind.name) + 1

    override suspend fun insert(category: Category): Long = dao.insert(category.copy(id = 0).toEntity())

    override suspend fun update(category: Category) = dao.update(category.toEntity())

    override suspend fun delete(id: Long) = dao.delete(id)
}
