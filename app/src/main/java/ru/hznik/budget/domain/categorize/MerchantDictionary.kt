package ru.hznik.budget.domain.categorize

/**
 * Встроенный словарь популярных магазинов и сервисов (design.md add-auto-categorization, решение 4): подсказка
 * категории для черновика, пока у пользователя нет своего правила. Ключевые фразы записаны в нормализованном виде
 * [MerchantKey] — латиницей и кириллицей. Слова с цифрами нормализация отбрасывает, поэтому их в словаре нет.
 */
object MerchantDictionary {
    private class Entry(val phrase: List<String>, val categoryKey: String)

    // Ключи категорий по умолчанию из DefaultCategorySeed.
    private const val PRODUCTS = "products"
    private const val CAFE = "cafe"
    private const val TRANSPORT = "transport"
    private const val CONNECTION = "connection"
    private const val ENTERTAINMENT = "entertainment"
    private const val CLOTHES = "clothes"
    private const val HEALTH = "health"
    private const val SUBSCRIPTIONS = "subscriptions"

    private val RAW: List<Pair<String, String>> = listOf(
        // Продукты
        "пятерочка" to PRODUCTS, "pyaterochka" to PRODUCTS, "перекресток" to PRODUCTS, "perekrestok" to PRODUCTS,
        "магнит" to PRODUCTS, "magnit" to PRODUCTS, "ашан" to PRODUCTS, "auchan" to PRODUCTS, "лента" to PRODUCTS, "lenta" to PRODUCTS,
        "дикси" to PRODUCTS, "dixy" to PRODUCTS, "вкусвилл" to PRODUCTS, "vkusvill" to PRODUCTS, "глобус" to PRODUCTS, "globus" to PRODUCTS,
        "spar" to PRODUCTS, "спар" to PRODUCTS, "азбука вкуса" to PRODUCTS, "azbuka vkusa" to PRODUCTS, "верный" to PRODUCTS,
        "светофор" to PRODUCTS, "svetofor" to PRODUCTS, "самокат" to PRODUCTS, "samokat" to PRODUCTS,
        "яндекс лавка" to PRODUCTS, "yandex lavka" to PRODUCTS, "магнолия" to PRODUCTS, "okey" to PRODUCTS, "окей" to PRODUCTS,
        // Кафе и доставка еды
        "яндекс еда" to CAFE, "yandex eda" to CAFE, "delivery club" to CAFE, "макдоналдс" to CAFE, "mcdonalds" to CAFE,
        "вкусно и точка" to CAFE, "vkusno i tochka" to CAFE, "kfc" to CAFE, "ростикс" to CAFE, "rostics" to CAFE,
        "бургер кинг" to CAFE, "burger king" to CAFE, "шоколадница" to CAFE, "shokoladnitsa" to CAFE, "starbucks" to CAFE,
        "cofix" to CAFE, "coffee like" to CAFE, "додо пицца" to CAFE, "dodo pizza" to CAFE, "теремок" to CAFE, "teremok" to CAFE,
        // Транспорт и топливо
        "яндекс го" to TRANSPORT, "yandex go" to TRANSPORT, "яндекс такси" to TRANSPORT, "yandex taxi" to TRANSPORT,
        "uber" to TRANSPORT, "ситимобил" to TRANSPORT, "citymobil" to TRANSPORT, "мосметро" to TRANSPORT, "mosmetro" to TRANSPORT,
        "тройка" to TRANSPORT, "troika" to TRANSPORT, "ржд" to TRANSPORT, "rzd" to TRANSPORT, "аэрофлот" to TRANSPORT, "aeroflot" to TRANSPORT,
        "лукойл" to TRANSPORT, "lukoil" to TRANSPORT, "газпромнефть" to TRANSPORT, "gazpromneft" to TRANSPORT,
        "роснефть" to TRANSPORT, "rosneft" to TRANSPORT, "делимобиль" to TRANSPORT, "delimobil" to TRANSPORT,
        "яндекс драйв" to TRANSPORT, "yandex drive" to TRANSPORT, "whoosh" to TRANSPORT, "urent" to TRANSPORT,
        // Связь и интернет
        "мтс" to CONNECTION, "mts" to CONNECTION, "билайн" to CONNECTION, "beeline" to CONNECTION, "мегафон" to CONNECTION,
        "megafon" to CONNECTION, "ростелеком" to CONNECTION, "rostelecom" to CONNECTION, "йота" to CONNECTION, "yota" to CONNECTION,
        // Здоровье
        "аптека" to HEALTH, "apteka" to HEALTH, "ригла" to HEALTH, "rigla" to HEALTH, "горздрав" to HEALTH, "gorzdrav" to HEALTH,
        "еаптека" to HEALTH, "eapteka" to HEALTH, "здравсити" to HEALTH, "zdravcity" to HEALTH, "инвитро" to HEALTH, "invitro" to HEALTH,
        // Одежда
        "lamoda" to CLOTHES, "ламода" to CLOTHES, "gloria jeans" to CLOTHES, "глория джинс" to CLOTHES, "спортмастер" to CLOTHES,
        "sportmaster" to CLOTHES, "ostin" to CLOTHES, "остин" to CLOTHES, "befree" to CLOTHES, "love republic" to CLOTHES, "zolla" to CLOTHES,
        // Подписки
        "яндекс плюс" to SUBSCRIPTIONS, "yandex plus" to SUBSCRIPTIONS, "кинопоиск" to SUBSCRIPTIONS, "kinopoisk" to SUBSCRIPTIONS,
        "ivi" to SUBSCRIPTIONS, "okko" to SUBSCRIPTIONS, "spotify" to SUBSCRIPTIONS, "netflix" to SUBSCRIPTIONS,
        "apple com bill" to SUBSCRIPTIONS, "vk музыка" to SUBSCRIPTIONS, "boosty" to SUBSCRIPTIONS,
        // Развлечения
        "синема парк" to ENTERTAINMENT, "cinema park" to ENTERTAINMENT, "киномакс" to ENTERTAINMENT, "kinomax" to ENTERTAINMENT,
        "каро фильм" to ENTERTAINMENT, "karo film" to ENTERTAINMENT, "steam" to ENTERTAINMENT, "playstation" to ENTERTAINMENT,
    )

    // Длинные фразы проверяются раньше коротких: «яндекс еда» не должна уступить более общему совпадению.
    private val ENTRIES: List<Entry> = RAW.map { (phrase, key) -> Entry(phrase.split(' '), key) }.sortedByDescending { it.phrase.size }

    // Банк с именем бренда («MTS BANK», «Тинькофф Банк») — не магазин из словаря.
    private val STOP_WORDS = setOf("bank", "банк")

    /** Ключ категории по умолчанию для магазина или `null`, если магазина нет в словаре. */
    fun categoryKeyFor(merchant: String?): String? {
        val words = MerchantKey.of(merchant)?.split(' ') ?: return null
        if (words.any { it in STOP_WORDS }) return null
        return ENTRIES.firstOrNull { entry -> matches(words, entry.phrase) }?.categoryKey
    }

    /**
     * Фраза совпадает, если её слова подряд и целиком стоят в названии: «magnit» не находится в «MAGNITOGORSK»,
     * а «яндекс го» — в «Яндекс Город».
     */
    private fun matches(words: List<String>, phrase: List<String>): Boolean {
        if (phrase.size > words.size) return false
        return (0..words.size - phrase.size).any { start -> phrase.indices.all { j -> words[start + j] == phrase[j] } }
    }

    /** Число записей — для теста объёма словаря. */
    val size: Int get() = RAW.size
}
