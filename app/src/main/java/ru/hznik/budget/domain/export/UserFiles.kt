package ru.hznik.budget.domain.export

import java.io.File

/**
 * Файлы, которые пользователь выбрал в системном окне (экспорт, резервная копия). Адрес — строка `Uri` из окна выбора:
 * приложение не просит доступа к памяти телефона (spec data-export).
 */
interface UserFiles {
    /** Записывает [text] в UTF-8, заменяя содержимое файла. */
    suspend fun writeText(target: String, text: String)

    /** Копирует файл приложения [file] в [target], заменяя содержимое. */
    suspend fun copyTo(target: String, file: File)

    /** Копирует [source] в файл приложения [file]. */
    suspend fun copyFrom(source: String, file: File)

    /** Удаляет файл, созданный в окне сохранения, если записать его не удалось: пустой файл не остаётся. */
    suspend fun delete(target: String)

    /** Имя и размер выбранного файла — для экрана восстановления. */
    suspend fun describe(source: String): UserFileInfo
}

/** Имя файла и размер в байтах (`null`, если неизвестен). */
data class UserFileInfo(val name: String, val sizeBytes: Long?)
