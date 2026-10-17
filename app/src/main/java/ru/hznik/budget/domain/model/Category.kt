package ru.hznik.budget.domain.model

enum class CategoryKind { EXPENSE, INCOME }

/**
 * Категория операций. [icon] и [color] — ключи из наборов интерфейса, домен их не трактует.
 * У категорий по умолчанию есть стабильный [defaultKey], у своих он `null`.
 */
data class Category(
    val id: Long = 0,
    val kind: CategoryKind,
    val name: String,
    val icon: String,
    val color: String,
    val defaultKey: String? = null,
    val sortOrder: Int,
    val hidden: Boolean = false,
) {
    val isDefault: Boolean get() = defaultKey != null

    val isTransfers: Boolean get() = defaultKey == DefaultCategories.TRANSFERS

    companion object {
        const val MAX_NAME_LENGTH = 30
    }
}

/** Ключи категорий по умолчанию, на которые опирается логика. */
object DefaultCategories {
    const val TRANSFERS = "transfers"

    /** «Другое» среди трат: предварительная категория черновиков и корректировок. */
    const val OTHER_EXPENSE = "other_expense"

    /** «Другое» среди доходов. */
    const val OTHER_INCOME = "other_income"
}

/** Категория по умолчанию с ключом [key] из списка или `null`. */
fun List<Category>.byDefaultKey(key: String): Category? = firstOrNull { it.defaultKey == key }
