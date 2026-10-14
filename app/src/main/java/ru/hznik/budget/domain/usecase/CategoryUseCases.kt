package ru.hznik.budget.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.OperationRepository
import ru.hznik.budget.domain.time.today
import java.time.Clock
import javax.inject.Inject

/** Категории для экрана ввода: без скрытых, сначала частые за последние 30 дней, затем по порядку по умолчанию. */
class ObserveInputCategories @Inject constructor(
    private val categories: CategoryRepository,
    private val operations: OperationRepository,
    private val clock: Clock,
) {
    operator fun invoke(kind: CategoryKind): Flow<List<Category>> {
        val since = clock.today().minusDays(USAGE_WINDOW_DAYS - 1)
        return combine(categories.observeAll(), operations.observeCategoryUsage(since)) { all, usage ->
            all.filter { it.kind == kind && !it.hidden }
                .sortedWith(compareByDescending<Category> { usage[it.id] ?: 0 }.thenBy { it.sortOrder })
        }
    }

    companion object {
        const val USAGE_WINDOW_DAYS = 30L
    }
}

sealed interface CategorySaveResult {
    data class Saved(val id: Long) : CategorySaveResult
    data object EmptyName : CategorySaveResult
    data object NameTooLong : CategorySaveResult
    data object DuplicateName : CategorySaveResult
    data object NotFound : CategorySaveResult
}

/** Создаёт новую категорию ([Category.id] = 0) или изменяет название, иконку и цвет существующей. */
class SaveCategory @Inject constructor(private val categories: CategoryRepository) {
    suspend operator fun invoke(category: Category): CategorySaveResult {
        val name = category.name.trim()
        if (name.isEmpty()) return CategorySaveResult.EmptyName
        if (name.length > Category.MAX_NAME_LENGTH) return CategorySaveResult.NameTooLong
        val excludeId = category.id.takeIf { it != 0L }
        if (categories.nameExists(category.kind, name, excludeId)) return CategorySaveResult.DuplicateName

        if (excludeId == null) {
            val created = category.copy(name = name, defaultKey = null, sortOrder = categories.nextSortOrder(category.kind))
            return CategorySaveResult.Saved(categories.insert(created))
        }
        // Вид, ключ по умолчанию и порядок не меняются при редактировании.
        val existing = categories.get(excludeId) ?: return CategorySaveResult.NotFound
        categories.update(existing.copy(name = name, icon = category.icon, color = category.color))
        return CategorySaveResult.Saved(excludeId)
    }
}

class SetCategoryHidden @Inject constructor(private val categories: CategoryRepository) {
    suspend operator fun invoke(id: Long, hidden: Boolean) {
        val category = categories.get(id) ?: return
        categories.update(category.copy(hidden = hidden))
    }
}

enum class CategoryDeleteResult { DELETED, MUST_HIDE, NOT_FOUND }

/** Удаляет только свою категорию без операций; категорию по умолчанию или с операциями можно лишь скрыть. */
class DeleteCategory @Inject constructor(private val categories: CategoryRepository) {
    suspend operator fun invoke(id: Long): CategoryDeleteResult {
        val category = categories.get(id) ?: return CategoryDeleteResult.NOT_FOUND
        if (category.isDefault || categories.hasOperations(id)) return CategoryDeleteResult.MUST_HIDE
        categories.delete(id)
        return CategoryDeleteResult.DELETED
    }
}
