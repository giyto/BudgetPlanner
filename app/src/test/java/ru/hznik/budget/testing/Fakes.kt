package ru.hznik.budget.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.model.DefaultCategories
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationItem
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.OperationFilter
import ru.hznik.budget.domain.repository.OperationRepository
import ru.hznik.budget.domain.search.normalizeForSearch
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")

/** 13 октября 2026, 12:00 по Москве — «сегодня» во всех тестах. */
val TEST_CLOCK: Clock = Clock.fixed(LocalDateTime.of(2026, 10, 13, 12, 0).atZone(MOSCOW).toInstant(), MOSCOW)

fun at(date: LocalDate, hour: Int = 12, minute: Int = 0): OffsetDateTime =
    date.atTime(hour, minute).atZone(MOSCOW).toOffsetDateTime()

val TODAY: LocalDate = LocalDate.of(2026, 10, 13)

fun category(
    id: Long,
    name: String,
    kind: CategoryKind = CategoryKind.EXPENSE,
    sortOrder: Int = id.toInt(),
    defaultKey: String? = null,
    hidden: Boolean = false,
) = Category(id = id, kind = kind, name = name, icon = "other", color = "other", defaultKey = defaultKey, sortOrder = sortOrder, hidden = hidden)

val PRODUCTS = category(1, "Продукты", defaultKey = "products")
val CAFE = category(2, "Кафе и рестораны", defaultKey = "cafe")
val TRANSPORT = category(3, "Транспорт", defaultKey = "transport")
val TRANSFERS = category(9, "Переводы", defaultKey = DefaultCategories.TRANSFERS)
val SALARY = category(20, "Зарплата", kind = CategoryKind.INCOME, defaultKey = "salary")

class FakeCategoryRepository(initial: List<Category> = listOf(PRODUCTS, CAFE, TRANSPORT, TRANSFERS, SALARY)) : CategoryRepository {
    val state = MutableStateFlow(initial)
    var operationsProvider: () -> List<Operation> = { emptyList() }

    override fun observeAll(): Flow<List<Category>> = state.map { list -> list.sortedBy { it.sortOrder } }

    override suspend fun get(id: Long): Category? = state.value.firstOrNull { it.id == id }

    override suspend fun nameExists(kind: CategoryKind, name: String, excludeId: Long?): Boolean =
        state.value.any { it.kind == kind && it.id != excludeId && normalizeForSearch(it.name) == normalizeForSearch(name) }

    override suspend fun hasOperations(id: Long): Boolean = operationsProvider().any { it.categoryId == id }

    override suspend fun nextSortOrder(kind: CategoryKind): Int = (state.value.filter { it.kind == kind }.maxOfOrNull { it.sortOrder } ?: 0) + 1

    override suspend fun insert(category: Category): Long {
        val id = (state.value.maxOfOrNull { it.id } ?: 0) + 1
        state.update { it + category.copy(id = id) }
        return id
    }

    override suspend fun update(category: Category) = state.update { list -> list.map { if (it.id == category.id) category else it } }

    override suspend fun delete(id: Long) = state.update { list -> list.filterNot { it.id == id } }
}

/** Фейк с той же семантикой фильтров, что и DAO: поиск по магазину, комментарию, категории и целым рублям. */
class FakeOperationRepository(private val categories: FakeCategoryRepository) : OperationRepository {
    val state = MutableStateFlow<List<Operation>>(emptyList())
    private var nextId = 1L

    init {
        categories.operationsProvider = { state.value }
    }

    override fun observe(filter: OperationFilter): Flow<List<OperationItem>> =
        combine(state, categories.state) { ops, cats ->
            val byId = cats.associateBy { it.id }
            ops.mapNotNull { op -> byId[op.categoryId]?.let { OperationItem(op, it) } }
                .filter { filter.source == null || it.operation.source == filter.source }
                .filter { filter.categoryId == null || it.operation.categoryId == filter.categoryId }
                .filter { item ->
                    val q = filter.query
                    q.isEmpty ||
                        listOfNotNull(item.operation.merchant, item.operation.comment, item.category.name)
                            .any { normalizeForSearch(it).contains(q.normalized) } ||
                        (q.rubles != null && item.operation.amount.rubles == q.rubles)
                }
                .sortedByDescending { it.operation.occurredAt.toInstant() }
        }

    override fun observeCategoryUsage(since: LocalDate): Flow<Map<Long, Int>> =
        state.map { ops -> ops.filter { !it.localDate.isBefore(since) }.groupingBy { it.categoryId }.eachCount() }

    override suspend fun get(id: Long): Operation? = state.value.firstOrNull { it.id == id }

    override suspend fun insert(operation: Operation): Long {
        val id = nextId++
        state.update { it + operation.copy(id = id) }
        return id
    }

    override suspend fun update(operation: Operation) = state.update { list -> list.map { if (it.id == operation.id) operation else it } }

    override suspend fun delete(id: Long) = state.update { list -> list.filterNot { it.id == id } }

    override suspend fun restore(operation: Operation) = state.update { it + operation }
}
