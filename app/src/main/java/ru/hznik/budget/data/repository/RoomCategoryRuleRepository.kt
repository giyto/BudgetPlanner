package ru.hznik.budget.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.hznik.budget.data.local.BudgetDatabase
import ru.hznik.budget.data.local.CategoryRuleDao
import ru.hznik.budget.data.local.CategoryRuleEntity
import ru.hznik.budget.data.local.OperationDao
import ru.hznik.budget.data.local.toDomain
import ru.hznik.budget.domain.categorize.CategoryRule
import ru.hznik.budget.domain.categorize.CategoryRuleItem
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.repository.CategoryRuleRepository
import ru.hznik.budget.domain.repository.DraftConfirmation
import java.time.Clock
import javax.inject.Inject

class RoomCategoryRuleRepository @Inject constructor(
    private val db: BudgetDatabase,
    private val dao: CategoryRuleDao,
    private val operations: OperationDao,
    private val clock: Clock,
) : CategoryRuleRepository {
    override suspend fun find(merchantKey: String, kind: CategoryKind): CategoryRule? = dao.find(merchantKey, kind.name)?.toDomain()

    override fun observeAll(): Flow<List<CategoryRuleItem>> =
        dao.observeAll().map { rows -> rows.map { CategoryRuleItem(it.rule.toDomain(), it.category.toDomain()) } }

    override suspend fun updateCategory(ruleId: Long, categoryId: Long) = dao.updateCategory(ruleId, categoryId, clock.millis())

    override suspend fun delete(ruleId: Long) = dao.delete(ruleId)

    override suspend fun restore(rule: CategoryRule) {
        dao.insert(rule.toEntity(clock.millis()))
    }

    override suspend fun confirmAndRemember(confirmations: List<DraftConfirmation>, rules: List<CategoryRule>) {
        db.withTransaction {
            operations.confirmDrafts(confirmations.map { Triple(it.operationId, it.categoryId, it.type.name) })
            rules.forEach { dao.remember(it.toEntity(clock.millis())) }
        }
    }
}

private fun CategoryRuleEntity.toDomain() = CategoryRule(id, merchantKey, CategoryKind.valueOf(categoryKind), categoryId, merchantDisplay)

private fun CategoryRule.toEntity(updatedAt: Long) = CategoryRuleEntity(
    id = id,
    merchantKey = merchantKey,
    categoryKind = kind.name,
    categoryId = categoryId,
    merchantDisplay = merchantDisplay,
    updatedAt = updatedAt,
)
