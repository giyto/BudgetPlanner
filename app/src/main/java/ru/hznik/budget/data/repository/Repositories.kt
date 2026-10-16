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
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationItem
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.DraftConfirmation
import ru.hznik.budget.domain.repository.OperationFilter
import ru.hznik.budget.domain.repository.OperationRepository
import ru.hznik.budget.domain.search.normalizeForSearch
import java.time.Clock
import java.time.Instant
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
            fromEpochDay = filter.dateRange?.start?.toEpochDay(),
            toEpochDay = filter.dateRange?.endInclusive?.toEpochDay(),
            accountActive = filter.account != null,
            accountBank = filter.account?.account?.bankId,
            accountCard = filter.account?.account?.cardLast4,
            includeNoDigits = filter.account?.includeNoDigits ?: false,
            accountFromMillis = filter.account?.from?.toEpochMilli() ?: 0,
        ).map { rows -> rows.map { it.toDomain() } }

    override fun observeFrom(from: LocalDate): Flow<List<Operation>> =
        dao.observeFrom(from.toEpochDay()).map { rows -> rows.map { it.toDomain() } }

    override fun observeBetween(from: LocalDate, until: LocalDate): Flow<List<Operation>> =
        dao.observeBetween(from.toEpochDay(), until.toEpochDay()).map { rows -> rows.map { it.toDomain() } }

    override fun observeRecent(limit: Int): Flow<List<OperationItem>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

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

    override fun observeDrafts(): Flow<List<OperationItem>> = dao.observeDrafts().map { rows -> rows.map { it.toDomain() } }

    override suspend fun confirmDrafts(confirmations: List<DraftConfirmation>) =
        dao.confirmDrafts(confirmations.map { Triple(it.operationId, it.categoryId, it.type.name) })

    override suspend fun existsByNotificationKey(key: String): Boolean = dao.countByNotificationKey(key) > 0

    override suspend fun existsSimilarImport(bankId: String, type: OperationType, amount: Money, merchant: String?, from: Instant, to: Instant): Boolean =
        dao.importCandidates(bankId, amount.kopecks, from.toEpochMilli(), to.toEpochMilli())
            .map { it.toDomain() }
            .any { op -> op.isSameImport(bankId, type, merchant) }

    override fun observeNewPeriodSuggestion(): Flow<Operation?> = dao.observeNewPeriodSuggestion().map { it?.toDomain() }

    override suspend fun clearNewPeriodSuggestion() = dao.clearNewPeriodSuggestion()

    override suspend fun findTransferCounterpart(otherThanBank: String, type: OperationType, amount: Money, from: Instant, to: Instant): Operation? =
        dao.findTransferCounterpart(otherThanBank, type.name, amount.kopecks, from.toEpochMilli(), to.toEpochMilli())?.toDomain()
}

class RoomCategoryRepository @Inject constructor(private val dao: CategoryDao) : CategoryRepository {
    override fun observeAll(): Flow<List<Category>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun get(id: Long): Category? = dao.get(id)?.toDomain()

    override suspend fun nameExists(kind: CategoryKind, name: String, excludeId: Long?): Boolean =
        dao.countByName(kind.name, normalizeForSearch(name), excludeId) > 0

    override suspend fun isUsed(id: Long): Boolean = dao.isUsed(id)

    override suspend fun nextSortOrder(kind: CategoryKind): Int = dao.maxSortOrder(kind.name) + 1

    override suspend fun insert(category: Category): Long = dao.insert(category.copy(id = 0).toEntity())

    override suspend fun update(category: Category) = dao.update(category.toEntity())

    override suspend fun delete(id: Long) = dao.delete(id)
}

/** Та же операция банка: напрямую, как сторона склеенного перевода себе или как его вторая сторона. */
internal fun Operation.isSameImport(bankId: String, type: OperationType, merchant: String?): Boolean {
    fun same(a: String?) = normalizeForSearch(a.orEmpty()) == normalizeForSearch(merchant.orEmpty())
    val counterType = when (originalType) {
        OperationType.TRANSFER -> OperationType.INCOME
        OperationType.INCOME -> OperationType.TRANSFER
        else -> null
    }
    return when {
        this.bankId == bankId && this.type == type -> same(this.merchant)
        this.bankId == bankId && this.type == OperationType.OWN_TRANSFER && originalType == type -> same(this.merchant)
        counterBankId == bankId && this.type == OperationType.OWN_TRANSFER && counterType == type -> same(counterMerchant)
        else -> false
    }
}
