package ru.hznik.budget.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.hznik.budget.domain.ai.AiSettings
import ru.hznik.budget.domain.ai.ChatMessage
import ru.hznik.budget.domain.ai.ChatRepository
import ru.hznik.budget.domain.ai.ChatRole
import ru.hznik.budget.domain.ai.LlmError
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Вопросы к помощнику на уровне приложения (design.md add-ai-assistant, решение 7): ответ сохраняется в историю,
 * даже если экран закрыли, и одновременно обрабатывается только один вопрос.
 */
@Singleton
class ChatSession internal constructor(
    private val engine: AssistantEngine,
    private val chat: ChatRepository,
    private val settings: AiSettings,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(engine: AssistantEngine, chat: ChatRepository, settings: AiSettings, clock: Clock) :
        this(engine, chat, settings, clock, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val _sending = MutableStateFlow(false)

    /** Идёт ответ: кнопка отправки недоступна, виден индикатор. */
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    /** Задать вопрос; `false`, если пустой или ещё идёт прошлый. */
    fun ask(question: String): Boolean {
        val text = question.trim()
        if (text.isEmpty() || !start()) return false
        scope.launch {
            guarded {
                val history = chat.observe().first()
                chat.add(ChatMessage(role = ChatRole.USER, text = text, createdAt = clock.instant()))
                answer(history, text)
            }
        }
        return true
    }

    /**
     * «Повторить» под последней ошибкой: ошибка удаляется, тот же вопрос задаётся снова без повтора в истории.
     * Ошибку не в конце диалога не повторяем: ответ встал бы после более поздних сообщений.
     */
    fun retry(errorId: Long): Boolean {
        if (!start()) return false
        scope.launch {
            guarded {
                val all = chat.observe().first()
                val last = all.lastOrNull()
                if (last == null || last.id != errorId || last.role != ChatRole.ERROR) return@guarded
                val questionIndex = all.indexOfLast { it.role == ChatRole.USER }
                chat.delete(errorId)
                if (questionIndex < 0) return@guarded
                answer(all.subList(0, questionIndex), all[questionIndex].text)
            }
        }
        return true
    }

    /** Ошибка базы или настроек не должна ронять приложение: вместо ответа - «Помощник временно недоступен». */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { chat.add(ChatMessage(role = ChatRole.ERROR, text = LlmError.UNAVAILABLE.name, createdAt = clock.instant())) }
        } finally {
            _sending.value = false
        }
    }

    /** «Новый диалог»: история удаляется с телефона. */
    fun newDialog() {
        scope.launch { chat.clear() }
    }

    private fun start(): Boolean = _sending.compareAndSet(expect = false, update = true)

    private suspend fun answer(history: List<ChatMessage>, question: String) {
        // Помощника выключили, пока вопрос ждал очереди: в сеть не идём (spec ai-assistant).
        if (!settings.enabled.first()) return
        val message = when (val answer = engine.ask(history, question)) {
            is AssistantAnswer.Text -> ChatMessage(role = ChatRole.ASSISTANT, text = answer.text, tools = answer.tools, createdAt = clock.instant())
            is AssistantAnswer.Failure -> ChatMessage(role = ChatRole.ERROR, text = answer.error.name, createdAt = clock.instant())
            AssistantAnswer.TooManyCalls -> ChatMessage(role = ChatRole.ERROR, text = TOO_MANY_CALLS, createdAt = clock.instant())
        }
        chat.add(message)
    }

    companion object {
        /** Код ошибки «Не получилось посчитать» в истории. */
        const val TOO_MANY_CALLS = "TOO_MANY_CALLS"
    }
}
