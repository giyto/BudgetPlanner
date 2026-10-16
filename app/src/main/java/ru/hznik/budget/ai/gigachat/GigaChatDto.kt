package ru.hznik.budget.ai.gigachat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** JSON для GigaChat API: неизвестные поля пропускаются, `null` не отправляется, значения по умолчанию отправляются. */
internal val GigaChatJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
internal data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    /** Момент окончания токена: по документации в миллисекундах, в старых примерах — в секундах. */
    @SerialName("expires_at") val expiresAt: Long,
)

@Serializable
internal data class ChatRequest(
    val model: String,
    val messages: List<MessageDto>,
    val functions: List<FunctionDto>? = null,
    /** `"auto"`: модель сама решает, вызывать ли функцию. */
    @SerialName("function_call") val functionCall: String? = null,
)

@Serializable
internal data class MessageDto(
    val role: String,
    val content: String = "",
    @SerialName("function_call") val functionCall: FunctionCallDto? = null,
    @SerialName("functions_state_id") val functionsStateId: String? = null,
    /** Имя функции у сообщения с ролью `function`. */
    val name: String? = null,
)

@Serializable
internal data class FunctionCallDto(
    val name: String,
    /** Аргументы — JSON-объект; на случай строки разбор в клиенте принимает и её. */
    val arguments: JsonElement? = null,
)

@Serializable
internal data class FunctionDto(val name: String, val description: String, val parameters: JsonElement)

@Serializable
internal data class ChatResponse(val choices: List<ChoiceDto> = emptyList(), val usage: UsageDto? = null)

@Serializable
internal data class ChoiceDto(val message: MessageDto, @SerialName("finish_reason") val finishReason: String? = null)

@Serializable
internal data class UsageDto(@SerialName("total_tokens") val totalTokens: Long = 0)
