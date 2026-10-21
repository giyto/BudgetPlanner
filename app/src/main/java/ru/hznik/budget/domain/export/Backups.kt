package ru.hznik.budget.domain.export

import java.time.Instant

/**
 * Резервная копия всех данных под паролем пользователя и восстановление из неё (spec data-export «Резервная копия»,
 * «Восстановление из копии»). Адреса файлов — строки `Uri` из системного окна выбора.
 */
interface BackupStore {
    /** Сохраняет копию в [target]. Пароль нигде не сохраняется. */
    suspend fun create(target: String, password: String)

    /** Читает копию из [source] и проверяет пароль, признак приложения и версию; данные телефона не меняются. */
    suspend fun check(source: String, password: String): BackupCheck

    /**
     * Готовит замену данных копией, проверенной последним [check]: она выполнится при следующем запуске приложения.
     * `false` — проверенной копии нет (например, приложение перезапускалось между проверкой и подтверждением).
     */
    suspend fun scheduleRestore(): Boolean

    /** Удаляет временные файлы проверенной копии, если восстановление отменили. */
    suspend fun discard()
}

/** Итог проверки файла копии. */
sealed interface BackupCheck {
    /** Копию можно восстановить: дата создания и сколько операций в копии и сейчас на телефоне. */
    data class Valid(val createdAt: Instant, val backupOperations: Int, val currentOperations: Int) : BackupCheck

    /** Неверный пароль или повреждённый файл — по зашифрованному файлу их не различить. */
    data object WrongPassword : BackupCheck

    /** Файл открылся, но это не копия BudgetPlanner. */
    data object NotBackup : BackupCheck

    /** Копию создала более новая версия приложения. */
    data object TooNew : BackupCheck
}

/** «Удалить все данные» (spec data-export «Удаление всех данных»): база, ключ и настройки удаляются при следующем запуске. */
interface DataWipe {
    /** Ставит отметку и убирает показанные уведомления приложения; удаление — при перезапуске. */
    suspend fun schedule()
}

/** Перезапуск приложения после восстановления или удаления данных (design.md harden-and-release-v1, решение 1). */
interface AppRestarter {
    fun restart()
}
