package ru.hznik.budget.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.budget.ai.tools.FakeAiSettings
import ru.hznik.budget.ai.tools.ToolRegistry
import ru.hznik.budget.domain.ai.ChatMessage
import ru.hznik.budget.domain.ai.ChatRepository
import ru.hznik.budget.domain.ai.ChatRole
import ru.hznik.budget.domain.ai.LlmError
import ru.hznik.budget.domain.ai.LlmMessage
import ru.hznik.budget.domain.ai.LlmProvider
import ru.hznik.budget.domain.ai.LlmResult
import ru.hznik.budget.domain.ai.TokenUsage
import ru.hznik.budget.domain.ai.ToolSpec
import ru.hznik.budget.testing.TEST_CLOCK

class FakeChatRepository : ChatRepository {
    val state = MutableStateFlow<List<ChatMessage>>(emptyList())
    private var nextId = 1L

    override fun observe(): Flow<List<ChatMessage>> = state

    override suspend fun add(message: ChatMessage): Long {
        val id = nextId++
        state.update { it + message.copy(id = id) }
        return id
    }

    override suspend fun delete(id: Long) = state.update { list -> list.filterNot { it.id == id } }

    override suspend fun clear() = state.update { emptyList() }
}

/** Сессия чата (spec ai-assistant «Ошибки», «Помощник выключен по умолчанию»; ревью этапа 10). */
class ChatSessionTest {
    private val chat = FakeChatRepository()
    private val settings = FakeAiSettings()

    private fun session(provider: LlmProvider, scope: CoroutineScope) =
        ChatSession(AssistantEngine(provider, ToolRegistry(emptySet(), settings), settings, TEST_CLOCK), chat, settings, TEST_CLOCK, scope)

    private val roles get() = chat.state.value.map { it.role }

    @Test
    fun `disabled assistant makes no requests`() = runTest {
        settings.enabledState.value = false
        val provider = ScriptedLlmProvider()
        session(provider, CoroutineScope(UnconfinedTestDispatcher(testScheduler))).ask("Привет")
        assertTrue(provider.calls.isEmpty())
    }

    @Test
    fun `retry works only for the last error`() = runTest {
        val provider = ScriptedLlmProvider(
            LlmResult.Failure(LlmError.NO_NETWORK),
            LlmResult.Text("Второй ответ", TokenUsage.NONE),
            LlmResult.Text("Первый ответ", TokenUsage.NONE),
        )
        val s = session(provider, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        s.ask("Первый")
        val errorId = chat.state.value.last().id
        s.ask("Второй")
        assertEquals(listOf(ChatRole.USER, ChatRole.ERROR, ChatRole.USER, ChatRole.ASSISTANT), roles)

        s.retry(errorId)
        assertEquals("старую ошибку не повторяем", 2, provider.calls.size)
        assertEquals(ChatRole.ERROR, chat.state.value[1].role)
    }

    @Test
    fun `retry of the last error asks the same question again`() = runTest {
        val provider = ScriptedLlmProvider(LlmResult.Failure(LlmError.NO_NETWORK), LlmResult.Text("Ответ", TokenUsage.NONE))
        val s = session(provider, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        s.ask("Вопрос")
        s.retry(chat.state.value.last().id)
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), roles)
        assertEquals(LlmMessage.User("Вопрос"), provider.calls[1].first.last())
        assertFalse(s.sending.value)
    }

    @Test
    fun `unexpected exception becomes an error card instead of a crash`() = runTest {
        val broken = object : LlmProvider {
            override val isConfigured = true
            override suspend fun complete(messages: List<LlmMessage>, tools: List<ToolSpec>): LlmResult = throw IllegalStateException("база")
        }
        val s = session(broken, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        s.ask("Вопрос")
        assertEquals(listOf(ChatRole.USER, ChatRole.ERROR), roles)
        assertEquals(LlmError.UNAVAILABLE.name, chat.state.value.last().text)
        assertFalse(s.sending.value)
    }
}
