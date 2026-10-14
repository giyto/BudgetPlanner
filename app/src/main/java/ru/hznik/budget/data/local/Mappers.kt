package ru.hznik.budget.data.local

import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.model.Money
import ru.hznik.budget.domain.model.Operation
import ru.hznik.budget.domain.model.OperationItem
import ru.hznik.budget.domain.model.OperationSource
import ru.hznik.budget.domain.model.OperationType
import ru.hznik.budget.domain.search.normalizeForSearch
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

// Единственное место, где вычисляются local_date, смещение и колонки поиска (design.md, Risks).

fun Operation.toEntity(createdAt: Long): OperationEntity = OperationEntity(
    id = id,
    type = type.name,
    amountKopecks = amount.kopecks,
    categoryId = categoryId,
    merchant = merchant,
    comment = comment,
    searchText = normalizeForSearch(listOfNotNull(merchant, comment).joinToString(" ")),
    source = source.name,
    occurredAt = occurredAt.toInstant().toEpochMilli(),
    utcOffsetSeconds = occurredAt.offset.totalSeconds,
    localDate = occurredAt.toLocalDate().toEpochDay(),
    createdAt = createdAt,
    isDraft = isDraft,
    bankId = bankId,
    cardLast4 = cardLast4,
    notificationKey = notificationKey,
    suggestsNewPeriod = suggestsNewPeriod,
    originalType = originalType?.name,
    counterBankId = counterBankId,
    counterCardLast4 = counterCardLast4,
    counterNotificationKey = counterNotificationKey,
    counterMerchant = counterMerchant,
    counterOccurredAt = counterOccurredAt?.toEpochMilli(),
)

fun OperationEntity.toDomain(): Operation = Operation(
    id = id,
    type = OperationType.valueOf(type),
    amount = Money(amountKopecks),
    categoryId = categoryId,
    merchant = merchant,
    comment = comment,
    source = OperationSource.valueOf(source),
    occurredAt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(occurredAt), ZoneOffset.ofTotalSeconds(utcOffsetSeconds)),
    isDraft = isDraft,
    bankId = bankId,
    cardLast4 = cardLast4,
    notificationKey = notificationKey,
    suggestsNewPeriod = suggestsNewPeriod,
    originalType = originalType?.let(OperationType::valueOf),
    counterBankId = counterBankId,
    counterCardLast4 = counterCardLast4,
    counterNotificationKey = counterNotificationKey,
    counterMerchant = counterMerchant,
    counterOccurredAt = counterOccurredAt?.let(Instant::ofEpochMilli),
)

fun Category.toEntity(): CategoryEntity = CategoryEntity(
    id = id,
    kind = kind.name,
    name = name,
    nameSearch = normalizeForSearch(name),
    icon = icon,
    color = color,
    defaultKey = defaultKey,
    sortOrder = sortOrder,
    hidden = hidden,
)

fun CategoryEntity.toDomain(): Category = Category(
    id = id,
    kind = CategoryKind.valueOf(kind),
    name = name,
    icon = icon,
    color = color,
    defaultKey = defaultKey,
    sortOrder = sortOrder,
    hidden = hidden,
)

fun OperationWithCategory.toDomain(): OperationItem = OperationItem(operation.toDomain(), category.toDomain())

/** Экранирует % и _ для LIKE ... ESCAPE '\'. */
fun escapeLike(value: String): String =
    value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
