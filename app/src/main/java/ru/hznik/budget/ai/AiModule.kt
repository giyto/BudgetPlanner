package ru.hznik.budget.ai

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import ru.hznik.budget.BuildConfig
import ru.hznik.budget.ai.gigachat.GigaChatClient
import ru.hznik.budget.ai.gigachat.GigaChatConfig
import ru.hznik.budget.domain.ai.LlmProvider
import java.time.Clock
import javax.inject.Singleton

/**
 * Модель ИИ: GigaChat на Ktor с движком OkHttp — он учитывает `network_security_config` с сертификатом
 * Минцифры. Отдельный модуль: в UI-тестах подменяется заранее заданными ответами без сети.
 */
@Module
@InstallIn(SingletonComponent::class)
object AiModule {
    @Provides
    @Singleton
    fun provideLlmProvider(clock: Clock): LlmProvider {
        // Без ключа клиент не делает запросов: complete() сразу возвращает NOT_CONFIGURED (spec ai-assistant).
        val client = HttpClient(OkHttp) {
            expectSuccess = false
            // Ответ не потоковый: байты приходят, когда модель закончила, поэтому ждать чтения тоже до 60 с.
            install(HttpTimeout) {
                requestTimeoutMillis = GigaChatConfig.REQUEST_TIMEOUT_MS
                socketTimeoutMillis = GigaChatConfig.REQUEST_TIMEOUT_MS
                connectTimeoutMillis = GigaChatConfig.CONNECT_TIMEOUT_MS
            }
        }
        return GigaChatClient(client, BuildConfig.GIGACHAT_AUTH_KEY, clock)
    }
}
