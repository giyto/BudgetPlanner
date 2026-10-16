package ru.hznik.budget.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Правила категорий (design.md add-auto-categorization, решение 2). */
@Dao
interface CategoryRuleDao {
    @Query("SELECT * FROM category_rules WHERE merchant_key = :merchantKey AND category_kind = :kind")
    suspend fun find(merchantKey: String, kind: String): CategoryRuleEntity?

    @Query(
        """
        SELECT r.*,
            c.id AS c_id, c.kind AS c_kind, c.name AS c_name, c.name_search AS c_name_search,
            c.icon AS c_icon, c.color AS c_color, c.default_key AS c_default_key,
            c.sort_order AS c_sort_order, c.hidden AS c_hidden
        FROM category_rules r
        JOIN categories c ON c.id = r.category_id
        ORDER BY r.category_kind DESC, r.merchant_key
        """,
    )
    fun observeAll(): Flow<List<CategoryRuleWithCategory>>

    @Insert
    suspend fun insert(rule: CategoryRuleEntity): Long

    @Update
    suspend fun update(rule: CategoryRuleEntity)

    @Query("UPDATE category_rules SET category_id = :categoryId, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateCategory(id: Long, categoryId: Long, updatedAt: Long)

    @Query("DELETE FROM category_rules WHERE id = :id")
    suspend fun delete(id: Long)

    /** Новое правило или замена правила того же магазина и вида (уникальный индекс, а не первичный ключ). */
    @Transaction
    suspend fun remember(rule: CategoryRuleEntity) {
        val existing = find(rule.merchantKey, rule.categoryKind)
        if (existing == null) insert(rule.copy(id = 0)) else update(rule.copy(id = existing.id))
    }
}
