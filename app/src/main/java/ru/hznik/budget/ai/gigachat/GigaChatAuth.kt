package ru.hznik.budget.ai.gigachat

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.hznik.budget.domain.ai.LlmError
import java.time.Clock
import java.util.UUID

/** Ошибка получения токена, уже переведённая в понятный вид. */
internal class GigaChatException(val error: LlmError) : Exception(error.name)

/**
 * Токен доступа GigaChat (раздел 9.3 ТЗ, design.md add-ai-assistant, решение 3): по общему ключу, кэш в памяти
 * до `expires_at` минус минута. Ключ и токен никуда не пишутся.
 */
internal class GigaChatAuth(
    private val client: HttpClient,
    private val authKey: String,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private var token: String? = null
    private var expiresAtMillis = 0L

    /** Токен из кэша или новый; бросает [GigaChatException] при отказе сервера. */
    suspend fun token(): String = mutex.withLock {
        val cached = token
        if (cached != null && clock.millis() < expiresAtMillis - GigaChatConfig.TOKEN_REFRESH_MARGIN_MS) return cached
        val response = client.submitForm(GigaChatConfig.OAUTH_URL, parameters { append("scope", GigaChatConfig.SCOPE) }) {
            header(HttpHeaders.Authorization, "Basic $authKey")
            header("RqUID", UUID.randomUUID().toString())
            header(HttpHeaders.Accept, "application/json")
        }
        when {
            response.status == HttpStatusCode.OK -> Unit
            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                throw GigaChatException(LlmError.BAD_KEY)
            response.status == HttpStatusCode.TooManyRequests -> throw GigaChatException(LlmError.RATE_LIMITED)
            else -> throw GigaChatException(LlmError.UNAVAILABLE)
        }
        val parsed = runCatching { GigaChatJson.decodeFromString<TokenResponse>(response.bodyAsText()) }
            .getOrElse { throw GigaChatException(LlmError.UNAVAILABLE) }
        // В документации миллисекунды; меньшее число — секунды из старых примеров.
        expiresAtMillis = if (parsed.expiresAt < 100_000_000_000L) parsed.expiresAt * 1000 else parsed.expiresAt
        token = parsed.accessToken
        parsed.accessToken
    }

    /** Модель ответила 401: токен больше не годится. */
    suspend fun invalidate() = mutex.withLock {
        token = null
        expiresAtMillis = 0
    }
}
