package ru.hznik.budget.domain.repository

import kotlinx.coroutines.flow.Flow
import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.CategoryKind

interface CategoryRepository {
    /** Все категории, включая скрытые, в порядке `sortOrder`. */
    fun observeAll(): Flow<List<Category>>

    suspend fun get(id: Long): Category?

    /** Есть ли у категории вида [kind] такое название без учёта регистра (кроме категории [excludeId]). */
    suspend fun nameExists(kind: CategoryKind, name: String, excludeId: Long?): Boolean

    /** Есть ли у категории операции или обязательные платежи: такую нельзя удалить, только скрыть. */
    suspend fun isUsed(id: Long): Boolean

    suspend fun nextSortOrder(kind: CategoryKind): Int

    suspend fun insert(category: Category): Long

    suspend fun update(category: Category)

    suspend fun delete(id: Long)
}
