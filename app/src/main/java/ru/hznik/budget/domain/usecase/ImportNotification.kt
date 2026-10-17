package ru.hznik.budget.domain.usecase

import kotlinx.coroutines.flow.first
import ru.hznik.budget.domain.categorize.AutoCategorize
import ru.hznik.budget.domain.categorize.CategoryRule
import ru.hznik.budget.domain.categorize.MerchantKey
import ru.hznik.budget.domain.model.DefaultCategories
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationItem
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.notifications.AutoImportSettings
import ru.hznik.budget.domain.notifications.BankNotification
import ru.hznik.budget.domain.notifications.NotificationRepository
import ru.hznik.budget.domain.notifications.parser.NotificationKind
import ru.hznik.budget.domain.notifications.parser.ParsedNotification
import ru.hznik.budget.domain.reconcile.ReconAccount
import ru.hznik.budget.domain.repository.CategoryRepository
import ru.hznik.budget.domain.repository.CategoryRuleRepository
import ru.hznik.budget.domain.repository.DraftConfirmation
import ru.hznik.budget.domain.repository.OperationRepository
import ru.hznik.budget.domain.repository.PeriodRepository
import ru.hznik.budget.domain.search.normalizeForSearch
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlin.math.abs
import ru.hznik.budget.domain.model.byDefaultKey

/** [EXPIRED] — уведомление старше срока, пока помнятся обработанные ключи ([ImportNotification.KEY_LIFETIME]). */
enum class ImportResult { IMPORTED, DUPLICATE, IGNORED, EXPIRED }

/**
 * Операция из разобранного уведомления банка (design.md add-notification-import, решения 4–7): черновик с
 * предварительной категорией, остаток для сверки (add-balance-reconciliation), защита от дублей и признак «похоже на стипендию».
 */
class ImportNotification @Inject constructor(
    private val operations: OperationRepository,
    private val categories: CategoryRepository,
    private val notifications: NotificationRepository,
    private val periods: PeriodRepository,
    private val autoImport: AutoImportSettings,
    private val reconcileBalance: ReconcileBalance,
    private val autoCategorize: AutoCategorize,
    private val clock: Clock,
) {
    suspend operator fun invoke(notification: BankNotification, parsed: ParsedNotification): ImportResult {
        val now = clock.instant()
        notifications.deleteKeysBefore(now.minus(KEY_LIFETIME))
        // Ключ такого уведомления уже забыт: висящее в шторке больше недели уведомление после перезагрузки
        // вернуло бы удалённую пользователем операцию, поэтому оно не импортируется.
        if (notification.postedAt.isBefore(now.minus(KEY_LIFETIME))) return ImportResult.EXPIRED
        if (notifications.isSeen(notification.key)) return ImportResult.DUPLICATE
        val result = importNew(notification, parsed)
        // Ключ отмечается после обработки: если запись сорвалась, повторная доставка уведомления её восстановит,
        // а удалённая пользователем операция не вернётся.
        notifications.markSeen(notification.key, parsed.bankId, now)
        if (result != ImportResult.DUPLICATE) {
            parsed.balance?.let { balance ->
                notifications.addBalance(parsed.bankId, parsed.cardLast4, balance, parsed.occurredAt)
                // Перевод между своими счетами внутри банка не записывается операцией: остаток — новая точка отсчёта без сравнения.
                reconcileBalance(ReconAccount(parsed.bankId, parsed.cardLast4), balance.kopecks, parsed.occurredAt, rebase = result == ImportResult.IGNORED)
            }
        }
        return result
    }

    private suspend fun importNew(notification: BankNotification, parsed: ParsedNotification): ImportResult {
        val (type, categoryKey) = when (parsed.kind) {
            NotificationKind.PURCHASE -> OperationType.EXPENSE to DefaultCategories.OTHER_EXPENSE
            NotificationKind.TRANSFER_OUT -> OperationType.TRANSFER to DefaultCategories.TRANSFERS
            NotificationKind.INCOME -> OperationType.INCOME to DefaultCategories.OTHER_INCOME
            NotificationKind.REFUND -> OperationType.REFUND to DefaultCategories.OTHER_EXPENSE
            NotificationKind.OWN_TRANSFER -> return ImportResult.IGNORED
        }
        if (operations.existsByNotificationKey(notification.key)) return ImportResult.DUPLICATE
        val from = parsed.occurredAt.minus(DUPLICATE_WINDOW)
        val to = parsed.occurredAt.plus(DUPLICATE_WINDOW)
        if (operations.existsSimilarImport(parsed.bankId, type, parsed.amount, parsed.merchant, from, to)) return ImportResult.DUPLICATE
        val all = categories.observeAll().first()
        val transfers = all.byDefaultKey(DefaultCategories.TRANSFERS) ?: error("Нет категории «Переводы»")
        if (type == OperationType.TRANSFER || type == OperationType.INCOME) {
            // Вторая сторона уже пришла из другого банка: склеить в один перевод себе (design.md, решение 11).
            // Проверяется раньше запомненного имени, иначе вторая сторона осталась бы тратой или доходом.
            val opposite = if (type == OperationType.TRANSFER) OperationType.INCOME else OperationType.TRANSFER
            val counterpart = operations.findTransferCounterpart(
                parsed.bankId, opposite, parsed.amount, parsed.occurredAt.minus(PAIR_WINDOW), parsed.occurredAt.plus(PAIR_WINDOW),
            )
            if (counterpart != null) {
                val alreadyOwn = counterpart.type == OperationType.OWN_TRANSFER
                operations.update(
                    counterpart.copy(
                        type = OperationType.OWN_TRANSFER,
                        // У перевода, сохранённого по правилу, исходный тип уже записан: правило могло сделать его тратой.
                        originalType = counterpart.originalType ?: counterpart.type,
                        categoryId = transfers.id,
                        // Перевод себе, уже подтверждённый по имени, остаётся подтверждённым.
                        isDraft = !alreadyOwn,
                        suggestsNewPeriod = false,
                        counterBankId = parsed.bankId,
                        counterCardLast4 = parsed.cardLast4,
                        counterNotificationKey = notification.key,
                        counterMerchant = parsed.merchant,
                        counterOccurredAt = parsed.occurredAt,
                    ),
                )
                return ImportResult.IMPORTED
            }
            // Перевод себе по запомненному имени: сразу, без черновика.
            val merchantKey = parsed.merchant?.let(::normalizeForSearch)
            if (merchantKey != null && merchantKey in autoImport.ownNames.first()) {
                operations.insert(newOperation(notification, parsed, OperationType.OWN_TRANSFER, transfers.id, isDraft = false, originalType = type))
                return ImportResult.IMPORTED
            }
        }
        val provisional = all.byDefaultKey(categoryKey) ?: error("Нет категории по умолчанию $categoryKey")
        // Правило пользователя или подсказка словаря — после переводов себе (add-auto-categorization, решение 3).
        val categorized = autoCategorize(type, parsed.merchant, provisional, all)
        operations.insert(
            Operation(
                type = categorized.type,
                amount = parsed.amount,
                categoryId = categorized.categoryId,
                merchant = parsed.merchant,
                source = OperationSource.NOTIFICATION,
                occurredAt = parsed.occurredAt.atZone(clock.zone).toOffsetDateTime(),
                isDraft = categorized.isDraft,
                originalType = categorized.originalType,
                bankId = parsed.bankId,
                cardLast4 = parsed.cardLast4,
                notificationKey = notification.key,
                suggestsNewPeriod = type == OperationType.INCOME && looksLikeIncome(parsed),
            ),
        )
        return ImportResult.IMPORTED
    }

    /** Сценарий 5.8: рядом с датой дохода, не меньше половины стартовой суммы или со словами «зарплата», «стипендия». */
    private suspend fun looksLikeIncome(parsed: ParsedNotification): Boolean {
        if (parsed.looksLikeSalary) return true
        val period = periods.getCurrent() ?: return false
        val day = parsed.occurredAt.atZone(clock.zone).toLocalDate()
        if (abs(ChronoUnit.DAYS.between(day, period.nextIncomeDate)) <= NEAR_INCOME_DAYS) return true
        return period.startAmountKopecks > 0 && parsed.amount.kopecks * 2 >= period.startAmountKopecks
    }

    private fun newOperation(
        notification: BankNotification,
        parsed: ParsedNotification,
        type: OperationType,
        categoryId: Long,
        isDraft: Boolean,
        originalType: OperationType? = null,
    ) = Operation(
        type = type,
        amount = parsed.amount,
        categoryId = categoryId,
        merchant = parsed.merchant,
        source = OperationSource.NOTIFICATION,
        occurredAt = parsed.occurredAt.atZone(clock.zone).toOffsetDateTime(),
        isDraft = isDraft,
        bankId = parsed.bankId,
        cardLast4 = parsed.cardLast4,
        notificationKey = notification.key,
        originalType = originalType,
    )

    companion object {
        /** Окно, в котором «ушло» из одного банка и «пришло» в другой считаются одним переводом себе. */
        val PAIR_WINDOW: Duration = Duration.ofMinutes(10)
        val DUPLICATE_WINDOW: Duration = Duration.ofMinutes(2)
        val KEY_LIFETIME: Duration = Duration.ofDays(7)
        const val NEAR_INCOME_DAYS = 3L
    }
}

/** Черновики для экрана «Черновики» и плашки на главной (spec drafts). */
class ObserveDrafts @Inject constructor(private val operations: OperationRepository) {
    operator fun invoke() = operations.observeDrafts()
}

/**
 * Сохраняет категории, выбранные пользователем; тип становится переводом, если выбрана категория «Переводы».
 * Для черновиков из [remember] запоминается правило «магазин → категория», и другие черновики того же магазина того же
 * вида подтверждаются с той же категорией (spec drafts, design.md add-auto-categorization, решение 6).
 */
class ConfirmDrafts @Inject constructor(
    private val operations: OperationRepository,
    private val categories: CategoryRepository,
    private val rules: CategoryRuleRepository,
) {
    suspend operator fun invoke(choices: Map<OperationItem, Long>, remember: Set<Long> = emptySet()) {
        if (choices.isEmpty()) return
        val confirmations = mutableListOf<DraftConfirmation>()
        val newRules = mutableListOf<CategoryRule>()
        for ((item, categoryId) in choices) {
            val category = categories.get(categoryId) ?: continue
            // У перевода себе категория всегда «Переводы»: старый выбор, сделанный до склейки, не переносится.
            if (item.operation.type == OperationType.OWN_TRANSFER) {
                confirmations += DraftConfirmation(item.operation.id, item.operation.categoryId, OperationType.OWN_TRANSFER)
                continue
            }
            val type = AutoCategorize.typeFor(item.operation.type, category)
            if (category.kind != type.categoryKind) continue
            confirmations += DraftConfirmation(item.operation.id, categoryId, type)
            val key = MerchantKey.of(item.operation.merchant)
            if (item.operation.id in remember && key != null) {
                newRules += CategoryRule(merchantKey = key, kind = category.kind, categoryId = category.id, merchantDisplay = item.operation.merchant!!.trim())
            }
        }
        if (newRules.isEmpty()) {
            operations.confirmDrafts(confirmations)
            return
        }
        // Другие черновики того же магазина и вида — по запомненному правилу.
        val chosenIds = confirmations.map { it.operationId }.toSet()
        val byRule = newRules.associateBy { it.merchantKey to it.kind }
        operations.observeDrafts().first().forEach { draft ->
            val op = draft.operation
            if (op.id in chosenIds || op.type !in AUTO_CONFIRMED_TYPES) return@forEach
            val rule = MerchantKey.of(op.merchant)?.let { byRule[it to op.type.categoryKind] } ?: return@forEach
            val category = categories.get(rule.categoryId) ?: return@forEach
            confirmations += DraftConfirmation(op.id, category.id, AutoCategorize.typeFor(op.type, category))
        }
        rules.confirmAndRemember(confirmations, newRules)
    }

    private companion object {
        /** Переводы и зачисления ждут вторую сторону перевода себе, поэтому по новому правилу не подтверждаются. */
        val AUTO_CONFIRMED_TYPES = setOf(OperationType.EXPENSE, OperationType.REFUND)
    }

    /** Название пригодно для правила: после нормализации в нём остались буквы (spec auto-categorization). */
    fun canRemember(item: OperationItem): Boolean =
        item.operation.type != OperationType.OWN_TRANSFER && MerchantKey.of(item.operation.merchant) != null
}

/** «Это перевод себе» у черновика, где видна одна сторона; можно запомнить отправителя или получателя как себя. */
class MarkOwnTransfer @Inject constructor(
    private val operations: OperationRepository,
    private val categories: CategoryRepository,
    private val autoImport: AutoImportSettings,
) {
    suspend operator fun invoke(operationId: Long, rememberName: Boolean) {
        val op = operations.get(operationId) ?: return
        if (op.type == OperationType.OWN_TRANSFER) return
        val transfers = categories.observeAll().first().byDefaultKey(DefaultCategories.TRANSFERS) ?: return
        // Сначала имя: следующее уведомление от него, пришедшее сразу после, уже станет переводом себе.
        if (rememberName) op.merchant?.let { autoImport.addOwnName(normalizeForSearch(it)) }
        operations.update(op.copy(type = OperationType.OWN_TRANSFER, originalType = op.type, categoryId = transfers.id, isDraft = false, suggestsNewPeriod = false))
    }
}

/** «Это не перевод себе» у склеенной пары: две обычные операции снова, обе черновиками. */
class SplitOwnTransfer @Inject constructor(
    private val operations: OperationRepository,
    private val categories: CategoryRepository,
) {
    suspend operator fun invoke(operationId: Long) {
        val op = operations.get(operationId) ?: return
        val original = op.originalType ?: return
        if (op.type != OperationType.OWN_TRANSFER) return
        val all = categories.observeAll().first()
        fun provisional(type: OperationType) = all.byDefaultKey(
            when (type) {
                OperationType.TRANSFER -> DefaultCategories.TRANSFERS
                OperationType.INCOME -> DefaultCategories.OTHER_INCOME
                else -> DefaultCategories.OTHER_EXPENSE
            },
        )?.id ?: op.categoryId
        val updated = op.copy(
            type = original, originalType = null, categoryId = provisional(original), isDraft = true,
            counterBankId = null, counterCardLast4 = null, counterNotificationKey = null, counterMerchant = null, counterOccurredAt = null,
        )
        val counterpart = op.counterBankId?.let { counterBank ->
            val counterType = if (original == OperationType.TRANSFER) OperationType.INCOME else OperationType.TRANSFER
            Operation(
                type = counterType,
                amount = op.amount,
                categoryId = provisional(counterType),
                merchant = op.counterMerchant,
                source = OperationSource.NOTIFICATION,
                // Своё время второго уведомления: рядом с полуночью пара может попасть на разные дни.
                occurredAt = op.counterOccurredAt?.atOffset(op.occurredAt.offset) ?: op.occurredAt,
                isDraft = true,
                bankId = counterBank,
                cardLast4 = op.counterCardLast4,
                notificationKey = op.counterNotificationKey,
            )
        }
        // Одной транзакцией: у обновлённой операции поля второй стороны уже очищены.
        operations.updateAndInsert(updated, counterpart)
    }
}

/** Последнее зачисление, после которого стоит предложить новый период, и отказ от предложения (сценарий 5.8). */
class IncomePeriodSuggestion @Inject constructor(private val operations: OperationRepository) {
    fun observe() = operations.observeNewPeriodSuggestion()

    suspend fun dismiss() = operations.clearNewPeriodSuggestion()
}
