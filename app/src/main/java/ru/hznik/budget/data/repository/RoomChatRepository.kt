package ru.hznik.budget.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.hznik.budget.data.local.ChatDao
import ru.hznik.budget.data.local.ChatMessageEntity
import ru.hznik.budget.domain.ai.ChatMessage
import ru.hznik.budget.domain.ai.ChatRepository
import ru.hznik.budget.domain.ai.ChatRole
import java.time.Instant
import javax.inject.Inject

/** Диалог с помощником в зашифрованной базе (spec ai-assistant «История диалога»). */
class RoomChatRepository @Inject constructor(private val dao: ChatDao) : ChatRepository {
    override fun observe(): Flow<List<ChatMessage>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun add(message: ChatMessage): Long = dao.insert(
        ChatMessageEntity(
            role = message.role.name,
            text = message.text,
            tools = message.tools.joinToString(","),
            createdAt = message.createdAt.toEpochMilli(),
        ),
    )

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun clear() = dao.clear()

    private fun ChatMessageEntity.toDomain() = ChatMessage(
        id = id,
        role = ChatRole.entries.firstOrNull { it.name == role } ?: ChatRole.ERROR,
        text = text,
        tools = tools.split(',').filter { it.isNotEmpty() },
        createdAt = Instant.ofEpochMilli(createdAt),
    )
}
