package ru.hznik.budget.ai.gigachat

/**
 * Адреса и модель GigaChat API (раздел 9.3 ТЗ, design.md add-ai-assistant). Проверено по официальной документации
 * 16.10.2026: с 17.07.2026 целевой адрес модели — api.giga.chat; в бесплатном режиме физлиц больше всего токенов
 * у Lite (250 млн из 365 млн на 12 месяцев), генерация идёт в один поток.
 */
object GigaChatConfig {
    const val OAUTH_URL = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"
    const val CHAT_URL = "https://api.giga.chat/v1/chat/completions"
    const val SCOPE = "GIGACHAT_API_PERS"

    /** Lite: самый большой бесплатный лимит, вызов функций поддерживается. */
    const val MODEL = "GigaChat-2"

    /** Токен живёт 30 минут; обновляем заранее, за минуту до конца. */
    const val TOKEN_REFRESH_MARGIN_MS = 60_000L

    const val REQUEST_TIMEOUT_MS = 60_000L

    const val CONNECT_TIMEOUT_MS = 15_000L
}
