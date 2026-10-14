package ru.hznik.budget.domain.search

import java.util.Locale

/**
 * Строка поиска по операциям. SQLite меняет регистр только у латиницы, поэтому текст сравнивается
 * с заранее нормализованными колонками базы (design.md, решение 4).
 */
data class SearchQuery(val text: String) {
    /** Нормализованный текст для сравнения через LIKE; пустой — поиск выключен. */
    val normalized: String = normalizeForSearch(text)

    /** Если запрос — целое число, по нему ищутся операции с такой целой частью суммы в рублях. */
    val rubles: Long? = normalized.takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it.length <= 7 }?.toLong()

    val isEmpty: Boolean get() = normalized.isEmpty()

    companion object {
        val EMPTY = SearchQuery("")
    }
}

/** Единая нормализация для записи в базу и для запроса: нижний регистр, «ё» как «е», без крайних пробелов. */
fun normalizeForSearch(value: String): String =
    value.trim().lowercase(Locale.ROOT).replace('ё', 'е')
