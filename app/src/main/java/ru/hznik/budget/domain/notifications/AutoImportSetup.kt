package ru.hznik.budget.domain.notifications

import kotlinx.coroutines.flow.Flow

/** Шаг включения автоимпорта (design.md add-notification-import, решение 8). */
enum class SetupStep {
    /** Android 13+, приложение установлено файлом: сначала снять «ограниченные настройки». */
    RESTRICTED_SETTINGS,

    NOTIFICATION_ACCESS,

    /** Прошивка выгружает фоновые приложения: автозапуск и батарея. */
    AUTOSTART,

    DONE,
}

/** Что известно о телефоне и о пройденных шагах. */
data class SetupFacts(
    val sdk: Int,
    val manufacturer: String,
    val installedFromFile: Boolean,
    val accessGranted: Boolean,
    /** Пользователь уже открывал экран доступа: после этого Android показывает «Разрешить ограниченные настройки». */
    val accessAttempted: Boolean,
    val restrictedVisited: Boolean,
    val autostartDone: Boolean,
)

object AutoImportSetup {
    /** Прошивки, которые выгружают фоновые приложения без автозапуска или ограничений батареи. */
    private val AGGRESSIVE = setOf("xiaomi", "redmi", "poco", "huawei", "honor", "oppo", "realme", "oneplus", "vivo", "samsung")

    fun needsAutostart(manufacturer: String): Boolean = manufacturer.lowercase() in AGGRESSIVE

    /** Ограниченные настройки появились в Android 13 и касаются только приложений, установленных файлом. */
    fun restrictedSettingsPossible(facts: SetupFacts): Boolean = facts.sdk >= 33 && facts.installedFromFile

    /**
     * «Разрешить ограниченные настройки» появляется в меню «О приложении» только после того, как пользователь
     * попробовал включить доступ и увидел «Ограниченная настройка». Поэтому сначала доступ, потом — если не вышло — снятие ограничения.
     */
    fun nextStep(facts: SetupFacts): SetupStep = when {
        !facts.accessGranted && restrictedSettingsPossible(facts) && facts.accessAttempted && !facts.restrictedVisited -> SetupStep.RESTRICTED_SETTINGS
        !facts.accessGranted -> SetupStep.NOTIFICATION_ACCESS
        needsAutostart(facts.manufacturer) && !facts.autostartDone -> SetupStep.AUTOSTART
        else -> SetupStep.DONE
    }
}

/** Флаги включения автоимпорта в настройках приложения. */
interface AutoImportSettings {
    /** Доступ хоть раз выдавали: только тогда показывается «Автоимпорт остановлен». */
    val wasEnabled: Flow<Boolean>
    val accessAttempted: Flow<Boolean>
    val restrictedVisited: Flow<Boolean>
    val autostartDone: Flow<Boolean>

    suspend fun setWasEnabled()

    suspend fun setAccessAttempted()

    suspend fun setRestrictedVisited()

    suspend fun setAutostartDone()

    /** Имена, которые пользователь отметил как свои: переводы от них и на них — переводы себе (нормализованные). */
    val ownNames: Flow<Set<String>>

    suspend fun addOwnName(normalizedName: String)
}
