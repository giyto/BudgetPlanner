package ru.hznik.budget.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import ru.hznik.budget.data.repository.isSameImport
import ru.hznik.budget.domain.categorize.CategoryRule
import ru.hznik.budget.domain.categorize.CategoryRuleItem
import ru.hznik.budget.domain.lock.LockSettings
import ru.hznik.budget.domain.lock.LockSettingsRepository
import ru.hznik.budget.domain.lock.LockTimeout
import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.model.DefaultCategories
import ru.hznik.budget.domain.model.IncomeSchedule
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationItem
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.model.Period
import ru.hznik.budget.domain.model.PlannedPayment
import ru.hznik.budget.domain.reconcile.AutoReconcileSettings
import ru.hznik.budget.domain.reconcile.BankBalance
import ru.hznik.budget.domain.reconcile.ReconAccount
import ru.hznik.budget.domain.reconcile.ReconState
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.CategoryRuleRepository
import ru.hznik.budget.domain.repository.DraftConfirmation
import ru.hznik.budget.domain.repository.IncomeScheduleRepository
import ru.hznik.budget.domain.repository.OperationFilter
import ru.hznik.budget.domain.repository.OperationRepository
import ru.hznik.budget.domain.repository.PeriodRepository
import ru.hznik.budget.domain.repository.PlannedPaymentRepository
import ru.hznik.budget.domain.repository.ReconciliationRepository
import ru.hznik.budget.domain.repository.StatisticsRepository
import ru.hznik.budget.domain.search.normalizeForSearch
import ru.hznik.budget.domain.stats.DateRange
import ru.hznik.budget.domain.stats.MerchantSpending
import ru.hznik.budget.domain.stats.RangeTotals
import java.time.Clock
import java.time.Instant
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
    var paymentsProvider: () -> List<PlannedPayment> = { emptyList() }

    override fun observeAll(): Flow<List<Category>> = state.map { list -> list.sortedBy { it.sortOrder } }

    override suspend fun get(id: Long): Category? = state.value.firstOrNull { it.id == id }

    override suspend fun nameExists(kind: CategoryKind, name: String, excludeId: Long?): Boolean =
        state.value.any { it.kind == kind && it.id != excludeId && normalizeForSearch(it.name) == normalizeForSearch(name) }

    override suspend fun isUsed(id: Long): Boolean =
        operationsProvider().any { it.categoryId == id } || paymentsProvider().any { it.categoryId == id }

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
class FakeOperationRepository(val categories: FakeCategoryRepository) : OperationRepository {
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
                .filter { filter.dateRange == null || it.operation.localDate in filter.dateRange }
                .filter { item -> filter.account?.let { item.operation.matchesAccount(it) } ?: true }
                .filter { item ->
                    val q = filter.query
                    q.isEmpty ||
                        listOfNotNull(item.operation.merchant, item.operation.comment, item.category.name)
                            .any { normalizeForSearch(it).contains(q.normalized) } ||
                        (q.rubles != null && item.operation.amount.rubles == q.rubles)
                }
                .sortedByDescending { it.operation.occurredAt.toInstant() }
        }

    override fun observeFrom(from: LocalDate): Flow<List<Operation>> =
        state.map { ops -> ops.filter { !it.localDate.isBefore(from) }.sortedBy { it.occurredAt.toInstant() } }

    override fun observeBetween(from: LocalDate, until: LocalDate): Flow<List<Operation>> =
        state.map { ops -> ops.filter { !it.localDate.isBefore(from) && it.localDate.isBefore(until) }.sortedBy { it.occurredAt.toInstant() } }

    override fun observeRecent(limit: Int): Flow<List<OperationItem>> = observe(OperationFilter()).map { it.take(limit) }

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

    override fun observeDrafts(): Flow<List<OperationItem>> = observe(OperationFilter()).map { list -> list.filter { it.operation.isDraft } }

    override suspend fun confirmDrafts(confirmations: List<DraftConfirmation>) = state.update { list ->
        list.map { op ->
            val c = confirmations.firstOrNull { it.operationId == op.id && op.isDraft } ?: return@map op
            op.copy(categoryId = c.categoryId, type = c.type, isDraft = false)
        }
    }

    override suspend fun existsByNotificationKey(key: String): Boolean = state.value.any { it.notificationKey == key || it.counterNotificationKey == key }

    override suspend fun existsSimilarImport(bankId: String, type: OperationType, amount: Money, merchant: String?, from: Instant, to: Instant): Boolean =
        state.value.any {
            (it.bankId == bankId || it.counterBankId == bankId) && it.amount == amount &&
                !it.occurredAt.toInstant().isBefore(from) && !it.occurredAt.toInstant().isAfter(to) &&
                it.isSameImport(bankId, type, merchant)
        }

    override fun observeNewPeriodSuggestion(): Flow<Operation?> = state.map { list -> list.filter { it.suggestsNewPeriod }.maxByOrNull { it.occurredAt } }

    override suspend fun clearNewPeriodSuggestion() = state.update { list -> list.map { it.copy(suggestsNewPeriod = false) } }

    override suspend fun findTransferCounterpart(otherThanBank: String, type: OperationType, amount: Money, from: Instant, to: Instant): Operation? =
        state.value.firstOrNull {
            it.bankId != null && it.bankId != otherThanBank && it.amount == amount &&
                !it.occurredAt.toInstant().isBefore(from) && !it.occurredAt.toInstant().isAfter(to) &&
                ((it.type == type && it.isDraft) || (it.originalType == type && it.counterBankId == null))
        }
}

/** Платежи нового периода сохраняются в [payments], как в одной транзакции у Room. */
class FakePeriodRepository(private val payments: FakePlannedPaymentRepository? = null) : PeriodRepository {
    val state = MutableStateFlow<List<Period>>(emptyList())

    override fun observeCurrent(): Flow<Period?> = state.map { list -> list.lastOrNull { it.endDate == null } }

    override suspend fun getCurrent(): Period? = state.value.lastOrNull { it.endDate == null }

    override fun observeFinished(): Flow<List<Period>> =
        state.map { list -> list.filter { it.endDate != null && it.endDate.isAfter(it.startDate) }.sortedByDescending { it.startDate } }

    override suspend fun startNew(start: LocalDate, nextIncome: LocalDate, startAmountKopecks: Long, payments: List<PlannedPayment>): Long {
        if (payments.isNotEmpty()) requireNotNull(this.payments) { "FakePeriodRepository создан без платежей" }
        payments.forEach { this.payments?.save(it.copy(id = 0)) }
        val id = (state.value.maxOfOrNull { it.id } ?: 0) + 1
        state.update { list ->
            list.map { if (it.endDate == null) it.copy(endDate = start) else it } +
                Period(id = id, startDate = start, nextIncomeDate = nextIncome, startAmountKopecks = startAmountKopecks)
        }
        return id
    }

    override suspend fun update(period: Period) = state.update { list -> list.map { if (it.id == period.id) period else it } }
}

/** Фейк с той же семантикой, что у Room: день оплаты берётся у траты, удаление траты снимает отметку об оплате. */
class FakePlannedPaymentRepository(private val operations: FakeOperationRepository) : PlannedPaymentRepository {
    val state = MutableStateFlow<List<PlannedPayment>>(emptyList())

    init {
        operations.categories.paymentsProvider = { state.value }
    }

    override fun observeAll(): Flow<List<PlannedPayment>> = combine(state, operations.state) { pays, ops ->
        val byId = ops.associateBy { it.id }
        pays.map { p ->
            val op = p.paidOperationId?.let(byId::get)
            p.copy(paidOperationId = op?.id, paidOn = op?.localDate)
        }.sortedWith(compareBy({ it.dueDate }, { it.id }))
    }

    override suspend fun get(id: Long): PlannedPayment? = observeAll().first().firstOrNull { it.id == id }

    override suspend fun save(payment: PlannedPayment): Long {
        if (payment.id != 0L) {
            state.update { list -> list.map { if (it.id == payment.id) payment else it } }
            return payment.id
        }
        val id = (state.value.maxOfOrNull { it.id } ?: 0) + 1
        state.update { it + payment.copy(id = id) }
        return id
    }

    override suspend fun delete(id: Long) = state.update { list -> list.filterNot { it.id == id } }

    override suspend fun markPaid(id: Long, operation: Operation, nextDueDate: LocalDate?): Long? {
        val payment = get(id) ?: return null
        if (payment.isPaid) return null
        val opId = operations.insert(operation)
        save(payment.copy(paidOperationId = opId))
        val duplicate = state.value.any {
            it.paidOperationId == null && it.name == payment.name && it.categoryId == payment.categoryId &&
                it.dueDate == nextDueDate && it.repeat == payment.repeat
        }
        if (nextDueDate != null && !duplicate) save(payment.copy(id = 0, dueDate = nextDueDate, paidOperationId = null, paidOn = null))
        return opId
    }
}

class FakeIncomeScheduleRepository(initial: IncomeSchedule? = null) : IncomeScheduleRepository {
    val state = MutableStateFlow(initial)

    override fun observe(): Flow<IncomeSchedule?> = state

    override suspend fun set(schedule: IncomeSchedule) {
        state.value = schedule
    }
}

/** Агрегаты по операциям фейка — та же семантика, что у запросов Room: трата и перевод минус возврат, по `local_date`. */
class FakeStatisticsRepository(private val operations: FakeOperationRepository) : StatisticsRepository {
    private fun inRange(range: DateRange) = operations.state.map { ops -> ops.filter { it.localDate in range && it.type != OperationType.INCOME } }

    private fun Operation.spending(): Long = when (type) {
        OperationType.EXPENSE, OperationType.TRANSFER -> amount.kopecks
        OperationType.REFUND -> -amount.kopecks
        OperationType.INCOME, OperationType.OWN_TRANSFER -> 0
    }

    override fun spendingByCategory(range: DateRange): Flow<Map<Long, Long>> =
        inRange(range).map { ops -> ops.groupBy { it.categoryId }.mapValues { (_, list) -> list.sumOf { it.spending() } } }

    override fun topMerchants(range: DateRange, limit: Int): Flow<List<MerchantSpending>> = inRange(range).map { ops ->
        ops.filter { !it.merchant.isNullOrEmpty() }.groupBy { it.merchant!! }
            .map { (name, list) -> MerchantSpending(name, list.size, list.sumOf { it.spending() }) }
            .filter { it.kopecks > 0 }.sortedByDescending { it.kopecks }.take(limit)
    }

    override fun spendingByDay(range: DateRange): Flow<Map<LocalDate, Long>> =
        inRange(range).map { ops -> ops.groupBy { it.localDate }.mapValues { (_, list) -> list.sumOf { it.spending() } } }

    override fun totals(range: DateRange): Flow<RangeTotals> = operations.state.map { ops ->
        val inside = ops.filter { it.localDate in range }
        RangeTotals(
            incomeKopecks = inside.filter { it.type == OperationType.INCOME }.sumOf { it.amount.kopecks },
            spentKopecks = inside.sumOf { it.spending() },
        )
    }
}

class FakeLockSettingsRepository(initial: LockSettings = LockSettings()) : LockSettingsRepository {
    val state = MutableStateFlow(initial)

    override fun observe(): Flow<LockSettings> = state

    override suspend fun setEnabled(enabled: Boolean) = state.update { it.copy(enabled = enabled) }

    override suspend fun setTimeout(timeout: LockTimeout) = state.update { it.copy(timeout = timeout) }

    override suspend fun setHideInRecents(hide: Boolean) = state.update { it.copy(hideInRecents = hide) }

    override suspend fun setHideWidgetAmount(hide: Boolean) = state.update { it.copy(hideWidgetAmount = hide) }
}

/** Сверка в памяти; ожидаемый остаток считается по операциям [operations] по тем же правилам, что и SQL. */
class FakeReconciliationRepository(private val operations: FakeOperationRepository) : ReconciliationRepository {
    val states = MutableStateFlow<Map<ReconAccount, ReconState>>(emptyMap())
    val balances = mutableListOf<BankBalance>()

    /** Сбой при записи корректировки — для проверки транзакционности. */
    var failAdjustment = false

    override fun observeStates(): Flow<List<ReconState>> = states.map { it.values.toList() }

    override suspend fun get(account: ReconAccount): ReconState? = states.value[account]

    override suspend fun save(state: ReconState) {
        states.value = states.value + (state.account to state)
    }

    override suspend fun netChange(account: ReconAccount, includeNoDigits: Boolean, from: Instant, to: Instant): Long {
        fun matches(bank: String?, card: String?) = bank == account.bankId && (card == account.cardLast4 || (includeNoDigits && card == null))
        fun inWindow(at: Instant) = at.isAfter(from) && !at.isAfter(to)
        var sum = 0L
        for (op in operations.state.value) {
            if (op.source != OperationSource.ADJUSTMENT && matches(op.bankId, op.cardLast4) && inWindow(op.occurredAt.toInstant())) {
                val sign = when (op.type) {
                    OperationType.INCOME, OperationType.REFUND -> 1
                    OperationType.EXPENSE, OperationType.TRANSFER -> -1
                    OperationType.OWN_TRANSFER -> if (op.originalType == OperationType.INCOME) 1 else -1
                }
                sum += sign * op.amount.kopecks
            }
            val counterAt = op.counterOccurredAt
            if (op.type == OperationType.OWN_TRANSFER && op.counterBankId != null && counterAt != null &&
                matches(op.counterBankId, op.counterCardLast4) && inWindow(counterAt)
            ) {
                sum += (if (op.originalType == OperationType.INCOME) -1 else 1) * op.amount.kopecks
            }
        }
        return sum
    }

    override suspend fun knownAccounts(): List<ReconAccount> {
        val ops = operations.state.value
        return (
            ops.filter { it.bankId != null && it.source != OperationSource.ADJUSTMENT }.map { ReconAccount(it.bankId, it.cardLast4) } +
                ops.filter { it.counterBankId != null }.map { ReconAccount(it.counterBankId, it.counterCardLast4) } +
                balances.map { it.account } + states.value.keys.filter { !it.isManual }
            ).distinct()
    }

    override suspend fun latestBalances(): List<BankBalance> =
        balances.groupBy { it.account }.values.map { list -> list.maxBy { it.at } }

    override suspend fun saveWithAdjustment(adjustment: Operation, state: ReconState): Boolean {
        if (states.value[state.account]?.pending == null) return false
        val id = operations.insert(adjustment)
        if (failAdjustment) {
            operations.delete(id)
            error("Сбой записи")
        }
        save(state)
        return true
    }
}

class FakeAutoReconcileSettings(initial: Boolean = true) : AutoReconcileSettings {
    val state = MutableStateFlow(initial)
    override val enabled: Flow<Boolean> = state

    override suspend fun setEnabled(enabled: Boolean) {
        state.value = enabled
    }
}

private fun Operation.matchesAccount(f: ru.hznik.budget.domain.repository.AccountFilter): Boolean {
    fun same(bank: String?, card: String?) = bank == f.account.bankId && (card == f.account.cardLast4 || (f.includeNoDigits && card == null))
    val counterAt = counterOccurredAt
    return (same(bankId, cardLast4) && !occurredAt.toInstant().isBefore(f.from)) ||
        (counterBankId != null && counterAt != null && same(counterBankId, counterCardLast4) && !counterAt.isBefore(f.from))
}

/** Правила категорий в памяти; подтверждение черновиков — через [operations]. */
class FakeCategoryRuleRepository(private val operations: FakeOperationRepository, private val categories: FakeCategoryRepository) : CategoryRuleRepository {
    val rules = MutableStateFlow<List<CategoryRule>>(emptyList())
    private var nextId = 1L

    override suspend fun find(merchantKey: String, kind: CategoryKind): CategoryRule? =
        rules.value.firstOrNull { it.merchantKey == merchantKey && it.kind == kind }

    override fun observeAll(): Flow<List<CategoryRuleItem>> = combine(rules, categories.state) { list, cats ->
        list.mapNotNull { rule -> cats.firstOrNull { it.id == rule.categoryId }?.let { CategoryRuleItem(rule, it) } }
            .sortedWith(compareByDescending<CategoryRuleItem> { it.rule.kind.name }.thenBy { it.rule.merchantKey })
    }

    override suspend fun updateCategory(ruleId: Long, categoryId: Long) {
        rules.update { list -> list.map { if (it.id == ruleId) it.copy(categoryId = categoryId) else it } }
    }

    override suspend fun delete(ruleId: Long) {
        rules.update { list -> list.filterNot { it.id == ruleId } }
    }

    override suspend fun restore(rule: CategoryRule) {
        rules.update { it + rule }
    }

    override suspend fun confirmAndRemember(confirmations: List<DraftConfirmation>, rules: List<CategoryRule>) {
        operations.confirmDrafts(confirmations)
        rules.forEach { rule ->
            val existing = find(rule.merchantKey, rule.kind)
            this.rules.update { list ->
                if (existing == null) list + rule.copy(id = nextId++) else list.map { if (it.id == existing.id) rule.copy(id = existing.id) else it }
            }
        }
    }
}
