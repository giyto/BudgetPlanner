package ru.hznik.budget.devtools

/**
 * Файлы вне базы, которые создают инструменты разработки (файл образцов в «Загрузках»). «Удалить все данные» убирает
 * их вместе с данными (spec data-export). Реализация своя у каждой сборки: в src/debug, в src/release — пустая
 * (задача 9.2 harden-and-release-v1).
 */
interface DevFiles {
    suspend fun deleteAll()
}
