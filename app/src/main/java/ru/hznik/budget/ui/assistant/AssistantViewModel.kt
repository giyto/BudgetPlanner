package ru.hznik.budget.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.hznik.budget.ai.ChatSession
import ru.hznik.budget.domain.ai.AiSettings
import ru.hznik.budget.domain.ai.ChatMessage
import ru.hznik.budget.domain.ai.ChatRepository
import ru.hznik.budget.domain.ai.LlmProvider
import javax.inject.Inject

data class AssistantUiState(
    /** `null`, пока настройки не прочитаны: виден только фон. */
    val enabled: Boolean? = null,
    /** В сборке есть ключ GigaChat. */
    val configured: Boolean = true,
    val hideMerchants: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val sending: Boolean = false,
    val tokensUsed: Long = 0,
)

/** Раздел «Помощник» (spec ai-assistant, design.md add-ai-assistant, решение 7). */
@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val settings: AiSettings,
    provider: LlmProvider,
    chat: ChatRepository,
    private val session: ChatSession,
) : ViewModel() {
    private val configured = provider.isConfigured

    val state: StateFlow<AssistantUiState> = combine(
        settings.enabled,
        settings.hideMerchants,
        chat.observe(),
        session.sending,
        settings.tokensUsed,
    ) { enabled, hide, messages, sending, tokens ->
        AssistantUiState(enabled, configured, hide, messages, sending, tokens)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssistantUiState(configured = configured))

    /** «Включить помощника»: без ключа в сборке включать нечего. */
    fun enable() {
        if (!configured) return
        viewModelScope.launch { settings.setEnabled(true) }
    }

    fun send(text: String): Boolean = session.ask(text)

    fun retry(errorId: Long) {
        session.retry(errorId)
    }

    fun newDialog() = session.newDialog()
}
