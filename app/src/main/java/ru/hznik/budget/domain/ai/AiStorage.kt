package ru.hznik.budget.domain.ai

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Настройки помощника (spec ai-assistant): выключен по умолчанию, названия магазинов отправляются, пока не скрыты. */
interface AiSettings {
    val enabled: Flow<Boolean>

    val hideMerchants: Flow<Boolean>

    /** Токены, израсходованные на этом телефоне. */
    val tokensUsed: Flow<Long>

    suspend fun setEnabled(enabled: Boolean)

    suspend fun setHideMerchants(hide: Boolean)

    suspend fun addTokens(tokens: Long)
}

/** Кто написал сообщение. [ERROR] — сообщение об ошибке вместо ответа; в модель не отправляется. */
enum class ChatRole { USER, ASSISTANT, ERROR }

/**
 * Сообщение диалога в истории (design.md, решение 6). [tools] — имена выполненных инструментов для подписи
 * «Посчитал по вашему бюджету»; у ошибки в [text] лежит код [LlmError] или `TOO_MANY_CALLS`.
 */
data class ChatMessage(
    val id: Long = 0,
    val role: ChatRole,
    val text: String,
    val tools: List<String> = emptyList(),
    val createdAt: Instant,
)

/** Один диалог в зашифрованной базе; «Новый диалог» удаляет его целиком. */
interface ChatRepository {
    fun observe(): Flow<List<ChatMessage>>

    suspend fun add(message: ChatMessage): Long

    suspend fun delete(id: Long)

    suspend fun clear()
}
