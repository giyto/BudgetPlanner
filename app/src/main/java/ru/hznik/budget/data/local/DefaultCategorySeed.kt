package ru.hznik.budget.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import ru.hznik.budget.domain.model.CategoryKind
import ru.hznik.budget.domain.model.DefaultCategories
import ru.hznik.budget.domain.search.normalizeForSearch

/** Категория по умолчанию: стабильный ключ, название, ключи иконки и цвета из наборов интерфейса. */
data class DefaultCategory(val key: String, val kind: CategoryKind, val name: String, val icon: String, val color: String)

/** Категории по умолчанию (FR-02 и решение по доходам в proposal этапа add-operations-and-categories). */
val DEFAULT_CATEGORIES: List<DefaultCategory> = listOf(
    DefaultCategory("products", CategoryKind.EXPENSE, "Продукты", "cart", "green"),
    DefaultCategory("cafe", CategoryKind.EXPENSE, "Кафе и рестораны", "cup", "orange"),
    DefaultCategory("transport", CategoryKind.EXPENSE, "Транспорт", "bus", "blue"),
    DefaultCategory("connection", CategoryKind.EXPENSE, "Связь и интернет", "phone", "teal"),
    DefaultCategory("entertainment", CategoryKind.EXPENSE, "Развлечения", "film", "pink"),
    DefaultCategory("clothes", CategoryKind.EXPENSE, "Одежда", "shirt", "amber"),
    DefaultCategory("health", CategoryKind.EXPENSE, "Здоровье", "pill", "purple"),
    DefaultCategory("subscriptions", CategoryKind.EXPENSE, "Подписки", "repeat", "blue"),
    DefaultCategory(DefaultCategories.TRANSFERS, CategoryKind.EXPENSE, "Переводы", "transfer", "gray"),
    DefaultCategory("other_expense", CategoryKind.EXPENSE, "Другое", "dots", "gray"),
    DefaultCategory("stipend", CategoryKind.INCOME, "Стипендия", "cap", "green"),
    DefaultCategory("salary", CategoryKind.INCOME, "Зарплата", "briefcase", "green"),
    DefaultCategory("transfer_in", CategoryKind.INCOME, "Перевод", "transfer", "blue"),
    DefaultCategory("gift", CategoryKind.INCOME, "Подарок", "gift", "pink"),
    DefaultCategory("other_income", CategoryKind.INCOME, "Другое", "dots", "gray"),
)

/** Вставляет категории по умолчанию. Вызывается один раз, при создании базы. */
fun SupportSQLiteDatabase.insertDefaultCategories() {
    DEFAULT_CATEGORIES.groupBy { it.kind }.forEach { (_, list) ->
        list.forEachIndexed { index, c ->
            execSQL(
                "INSERT INTO categories (kind, name, name_search, icon, color, default_key, sort_order, hidden) VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
                arrayOf<Any>(c.kind.name, c.name, normalizeForSearch(c.name), c.icon, c.color, c.key, index + 1),
            )
        }
    }
}
