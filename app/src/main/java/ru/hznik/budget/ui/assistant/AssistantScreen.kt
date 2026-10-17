package ru.hznik.budget.ui.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.hznik.budget.R
import ru.hznik.budget.ai.ChatSession
import ru.hznik.budget.domain.ai.ChatMessage
import ru.hznik.budget.domain.ai.ChatRole
import ru.hznik.budget.domain.ai.LlmError
import ru.hznik.budget.ui.format.MoneyFormat
import ru.hznik.budget.ui.format.SpokenMoney
import ru.hznik.budget.ui.components.Badge
import ru.hznik.budget.ui.components.PointsCard
import ru.hznik.budget.ui.components.PrimaryButton
import ru.hznik.budget.ui.components.pressScale
import ru.hznik.budget.ui.theme.BudgetTheme
import ru.hznik.budget.ui.theme.TextRoles
import ru.hznik.budget.ui.theme.LocalReducedMotion
import ru.hznik.budget.ui.components.itemMotion
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.StartOffset
import ru.hznik.budget.ui.components.AppearFromBelow

const val ASSISTANT_INPUT_TAG = "assistant_input"
const val ASSISTANT_SEND_TAG = "assistant_send"
const val ASSISTANT_MESSAGES_TAG = "assistant_messages"

@Composable
fun AssistantScreen(modifier: Modifier = Modifier, viewModel: AssistantViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = BudgetTheme.colors
    when (state.enabled) {
        null -> Box(modifier.fillMaxSize().background(colors.background))
        false -> AssistantIntro(state, onEnable = viewModel::enable, modifier = modifier)
        true -> AssistantChat(state, onSend = viewModel::send, onRetry = viewModel::retry, onNewDialog = viewModel::newDialog, modifier = modifier)
    }
}

@Composable
private fun AssistantHeader(subtitle: String, tokens: Long?, onNewDialog: (() -> Unit)?) {
    val colors = BudgetTheme.colors
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 12.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.assistant_title), style = MaterialTheme.typography.titleLarge, color = colors.ink, modifier = Modifier.semantics { heading() })
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Icon(painterResource(R.drawable.ic_shield), contentDescription = null, tint = colors.muted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(subtitle, fontSize = 14.sp, color = colors.muted)
            }
            tokens?.let {
                Text(
                    stringResource(R.string.assistant_tokens, MoneyFormat.groupDigits(it), SpokenMoney.plural(it, "токен", "токена", "токенов")),
                    fontSize = 12.sp,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 8.dp).background(colors.card2, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        onNewDialog?.let {
            IconButton(onClick = it, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_message_plus), contentDescription = stringResource(R.string.assistant_new_dialog), tint = colors.ink)
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
}

/** Помощник выключен: что умеет и какие данные уходят в модель (макет AssistantIntro). */
@Composable
private fun AssistantIntro(state: AssistantUiState, onEnable: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetTheme.colors
    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        AssistantHeader(stringResource(R.string.assistant_subtitle), tokens = null, onNewDialog = null)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Badge(painterResource(R.drawable.ic_nav_assistant), container = colors.primarySoft, content = colors.primaryText)
            Text(
                stringResource(R.string.assistant_intro_title),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp).semantics { heading() },
            )
            Text(
                stringResource(R.string.assistant_intro_text),
                fontSize = 15.sp,
                lineHeight = 21.sp,
                color = colors.sub,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
            )
            SectionLabel(stringResource(R.string.assistant_sent_header))
            PointsCard(
                listOf(
                    R.drawable.ic_cat_wallet to stringResource(R.string.assistant_sent_totals),
                    R.drawable.ic_nav_stats to stringResource(R.string.assistant_sent_categories),
                    R.drawable.ic_cat_cart to stringResource(if (state.hideMerchants) R.string.assistant_sent_merchants_off else R.string.assistant_sent_merchants),
                ),
                tileContainer = colors.primarySoft,
                tileContent = colors.primaryText,
            )
            SectionLabel(stringResource(R.string.assistant_kept_header))
            PointsCard(
                listOf(R.drawable.ic_lock to stringResource(R.string.assistant_kept)),
                tileContainer = colors.positiveSoft,
                tileContent = colors.positive,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.configured) {
                PrimaryButton(stringResource(R.string.assistant_enable), onClick = onEnable)
                Text(stringResource(R.string.assistant_via_gigachat), fontSize = 13.sp, color = colors.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
            } else {
                Text(
                    stringResource(R.string.assistant_not_configured),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.warn,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().background(colors.warnSoft, RoundedCornerShape(18.dp)).padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = BudgetTheme.colors.muted,
        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 18.dp, bottom = 6.dp),
    )
}

/** Чат по макетам Assistant, AssistantEmpty и AssistantStates. */
@Composable
private fun AssistantChat(
    state: AssistantUiState,
    onSend: (String) -> Boolean,
    onRetry: (Long) -> Unit,
    onNewDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BudgetTheme.colors
    var input by rememberSaveable { mutableStateOf("") }
    var askNewDialog by rememberSaveable { mutableStateOf(false) }
    val send: (String) -> Unit = { text -> if (onSend(text) && text == input) input = "" }
    // Сообщения, которые уже были при открытии раздела, показываются сразу; анимируются только новые.
    val historyIds = remember { state.messages.map { it.id }.toSet() }
    val suggestions = listOf(
        stringResource(R.string.assistant_suggest_headphones),
        stringResource(R.string.assistant_suggest_top),
        stringResource(R.string.assistant_suggest_cafe),
        stringResource(R.string.assistant_suggest_survive),
    )
    Column(modifier = modifier.fillMaxSize().background(colors.background).imePadding()) {
        AssistantHeader(
            stringResource(R.string.assistant_subtitle_short),
            tokens = state.tokensUsed,
            onNewDialog = { askNewDialog = true }.takeIf { state.messages.isNotEmpty() && !state.sending },
        )
        if (state.messages.isEmpty() && !state.sending) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                Text(stringResource(R.string.assistant_empty_title), style = MaterialTheme.typography.titleMedium, color = colors.ink, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.assistant_empty_text), fontSize = 14.sp, color = colors.muted, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
                suggestions.forEach { text ->
                    Surface(
                        onClick = { send(text) },
                        shape = RoundedCornerShape(16.dp),
                        color = colors.card,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).pressScale().heightIn(min = 48.dp),
                    ) {
                        Text(text, fontSize = 15.sp, color = colors.ink, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                }
            }
        } else {
            val listState = rememberLazyListState()
            val count = state.messages.size + if (state.sending) 1 else 0
            LaunchedEffect(count) { if (count > 0) listState.animateScrollToItem(count - 1) }
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().testTag(ASSISTANT_MESSAGES_TAG),
            ) {
                // Новые сообщения въезжают снизу с проявлением; история при открытии показывается сразу.
                items(state.messages, key = { it.id }) { message ->
                    Box(itemMotion(fadeIn = false)) {
                        AppearFromBelow(animate = message.id !in historyIds) {
                            when (message.role) {
                                ChatRole.USER -> UserBubble(message.text)
                                ChatRole.ASSISTANT -> AssistantBubble(message)
                                ChatRole.ERROR -> ErrorCard(message, retryEnabled = !state.sending && message == state.messages.last(), onRetry = { onRetry(message.id) })
                            }
                        }
                    }
                }
                if (state.sending) item(key = "thinking") { Box(itemMotion(fadeIn = false)) { AppearFromBelow(animate = true) { Thinking() } } }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                (listOf(stringResource(R.string.assistant_suggest_overpay)) + suggestions.drop(2)).forEach { text ->
                    Surface(
                        onClick = { send(text) },
                        enabled = !state.sending,
                        shape = RoundedCornerShape(16.dp),
                        color = colors.background,
                        border = BorderStroke(1.dp, colors.chipBorder),
                        modifier = Modifier.pressScale().heightIn(min = 44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(text, fontSize = 15.sp, color = colors.ink, maxLines = 1)
                        }
                    }
                }
            }
        }
        InputRow(input, onInput = { input = it }, sending = state.sending, onSend = { send(input) })
    }
    if (askNewDialog) {
        AlertDialog(
            onDismissRequest = { askNewDialog = false },
            title = { Text(stringResource(R.string.assistant_new_dialog_title)) },
            text = { Text(stringResource(R.string.assistant_new_dialog_text)) },
            confirmButton = { TextButton(onClick = { askNewDialog = false; onNewDialog() }) { Text(stringResource(R.string.assistant_new_dialog_confirm)) } },
            dismissButton = { TextButton(onClick = { askNewDialog = false }) { Text(stringResource(R.string.assistant_cancel)) } },
        )
    }
}

@Composable
private fun InputRow(input: String, onInput: (String) -> Unit, sending: Boolean, onSend: () -> Unit) {
    val colors = BudgetTheme.colors
    val canSend = input.isNotBlank() && !sending
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp)) {
        BasicTextField(
            value = input,
            onValueChange = onInput,
            textStyle = TextStyle(fontSize = 16.sp, color = colors.ink),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
            maxLines = 4,
            modifier = Modifier.weight(1f).testTag(ASSISTANT_INPUT_TAG),
            decorationBox = { field ->
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .heightIn(min = 52.dp)
                        .border(1.dp, colors.chipBorder, RoundedCornerShape(26.dp))
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    if (input.isEmpty()) Text(stringResource(R.string.assistant_input_hint), fontSize = 16.sp, color = colors.muted)
                    field()
                }
            },
        )
        Spacer(Modifier.width(10.dp))
        IconButton(
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier
                .size(52.dp)
                .background(if (canSend) colors.primary else colors.card2, CircleShape)
                .testTag(ASSISTANT_SEND_TAG),
        ) {
            Icon(
                painterResource(R.drawable.ic_send),
                contentDescription = stringResource(R.string.assistant_send),
                tint = if (canSend) colors.onPrimary else colors.muted,
            )
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    val colors = BudgetTheme.colors
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            color = colors.onPrimary,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(colors.primary, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun AssistantBubble(message: ChatMessage) {
    val colors = BudgetTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        captions(message.tools).forEach { caption ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.background(colors.card2, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = colors.sub, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(caption), fontSize = 13.sp, color = colors.sub)
            }
        }
        Text(
            withBold(message.text),
            fontSize = 16.sp,
            lineHeight = 23.sp,
            color = colors.ink,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(colors.card, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 6.dp, bottomEnd = 22.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

/** Подписи о расчёте по выполненным инструментам, без повторов. */
private fun captions(tools: List<String>): List<Int> = tools.map {
    when (it) {
        "get_top_merchants" -> R.string.assistant_caption_merchants
        "get_spending", "get_spending_by_category", "compare_periods" -> R.string.assistant_caption_categories
        else -> R.string.assistant_caption_budget
    }
}.distinct()

/** `**жирный**` из ответа модели — жирным, остальная разметка не нужна. */
private fun withBold(text: String): AnnotatedString = buildAnnotatedString {
    val parts = text.split("**")
    parts.forEachIndexed { i, part -> if (i % 2 == 1 && i < parts.lastIndex) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part) } else append(part) }
}

@Composable
private fun ErrorCard(message: ChatMessage, retryEnabled: Boolean, onRetry: () -> Unit) {
    val colors = BudgetTheme.colors
    val (title, sub) = when (message.text) {
        LlmError.NO_NETWORK.name -> R.string.assistant_error_network to R.string.assistant_error_network_sub
        LlmError.RATE_LIMITED.name -> R.string.assistant_error_rate to R.string.assistant_error_sub
        LlmError.TOKENS_EXHAUSTED.name -> R.string.assistant_error_tokens to R.string.assistant_error_sub
        LlmError.NOT_CONFIGURED.name -> R.string.assistant_not_configured to R.string.assistant_error_sub
        ChatSession.TOO_MANY_CALLS -> R.string.assistant_error_too_many_calls to R.string.assistant_error_sub
        else -> R.string.assistant_error_unavailable to R.string.assistant_error_sub
    }
    Row(modifier = Modifier.fillMaxWidth().background(colors.warnSoft, RoundedCornerShape(22.dp)).padding(16.dp)) {
        Icon(painterResource(R.drawable.ic_alert), contentDescription = null, tint = colors.warn, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(stringResource(title), style = TextRoles.itemTitle, color = colors.warn)
            Text(stringResource(sub), fontSize = 13.sp, color = colors.sub, modifier = Modifier.padding(top = 2.dp))
            Surface(
                onClick = onRetry,
                enabled = retryEnabled,
                shape = RoundedCornerShape(14.dp),
                color = colors.card,
                modifier = Modifier.padding(top = 10.dp).heightIn(min = 44.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, tint = colors.ink, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.assistant_retry), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                }
            }
        }
    }
}

private const val DOT_CYCLE = 1_200
private const val DOT_DIM = 0.3f

@Composable
private fun Thinking() {
    val colors = BudgetTheme.colors
    val label = stringResource(R.string.assistant_thinking)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(colors.card, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 6.dp, bottomEnd = 22.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { contentDescription = label },
    ) {
        // Точки мигают по очереди, каждая раз в 1,2 с; при выключенных анимациях неподвижны (spec ui-motion).
        val reduced = LocalReducedMotion.current
        val blinking = rememberInfiniteTransition(label = "thinking")
        repeat(3) { index ->
            val alpha = if (reduced) {
                1f
            } else {
                blinking.animateFloat(
                    initialValue = DOT_DIM,
                    targetValue = DOT_DIM,
                    animationSpec = infiniteRepeatable(
                        animation = keyframes {
                            durationMillis = DOT_CYCLE
                            DOT_DIM at 0
                            1f at DOT_CYCLE / 4
                            DOT_DIM at DOT_CYCLE / 2
                        },
                        initialStartOffset = StartOffset(index * DOT_CYCLE / 6),
                    ),
                    label = "dot $index",
                ).value
            }
            Box(Modifier.size(8.dp).graphicsLayer { this.alpha = alpha }.background(colors.primaryText, CircleShape))
            Spacer(Modifier.width(6.dp))
        }
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 15.sp, color = colors.muted)
    }
}
