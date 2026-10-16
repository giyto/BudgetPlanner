package ru.hznik.budget.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import ru.hznik.budget.domain.model.Period
import ru.hznik.budget.domain.stats.DateRange
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Локальный инструмент помощника (FR-08, design.md add-ai-assistant, решение 2): только читает данные и отдаёт
 * итоги. Суммы — в рублях, даты — ISO. В результатах нет номеров карт, остатков банка, текстов уведомлений
 * и комментариев; названия магазинов — только у инструмента с [sendsMerchants].
 */
interface AssistantTool {
    val name: String
    val description: String

    /** JSON-схема параметров в формате GigaChat (`type: object`). */
    val parametersSchema: String

    /** Отдаёт названия магазинов: недоступен при «Не отправлять названия магазинов». */
    val sendsMerchants: Boolean get() = false

    suspend fun run(args: JsonObject): JsonObject
}

/** Ошибка инструмента уходит модели текстом, а не исключением. */
class ToolException(message: String) : Exception(message)

/** Пустая схема для инструментов без параметров. */
internal const val NO_PARAMS = """{"type":"object","properties":{}}"""

/** Схема необязательного интервала дат. */
internal const val RANGE_PROPERTIES =
    """"from":{"type":"string","description":"Первый день, ГГГГ-ММ-ДД. По умолчанию — начало текущего периода"},""" +
        """"to":{"type":"string","description":"Последний день включительно, ГГГГ-ММ-ДД. По умолчанию — сегодня"}"""

/** Сумма в рублях числом: целые без дробной части, иначе с копейками. */
internal fun rubles(kopecks: Long): JsonPrimitive = if (kopecks % 100 == 0L) JsonPrimitive(kopecks / 100) else JsonPrimitive(kopecks / 100.0)

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

internal fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.replace(',', '.')?.replace(" ", "")?.toDoubleOrNull() }

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

/** Самый длинный интервал, который можно запросить: больше года модели не нужно. */
private const val MAX_RANGE_DAYS = 400L

/** Интервал из аргументов `from`/`to`; по умолчанию — с начала текущего периода по сегодня. */
internal fun JsonObject.range(today: LocalDate, period: Period?): DateRange {
    fun date(key: String): LocalDate? = string(key)?.let {
        try {
            LocalDate.parse(it)
        } catch (e: DateTimeParseException) {
            throw ToolException("Дата «$it» не в формате ГГГГ-ММ-ДД")
        }
    }
    val from = date("from") ?: period?.startDate ?: today.withDayOfMonth(1)
    val to = date("to") ?: today
    if (to.isBefore(from)) throw ToolException("Конец интервала раньше начала")
    val range = DateRange(from, to)
    if (range.length > MAX_RANGE_DAYS) throw ToolException("Интервал больше $MAX_RANGE_DAYS дней")
    return range
}

internal fun rangeJson(range: DateRange): Pair<String, JsonElement> = "range" to buildJsonObject {
    put("from", range.start.toString())
    put("to", range.endInclusive.toString())
}

internal fun JsonObject.with(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(this + pairs)

internal fun jsonList(items: List<JsonObject>): JsonArray = JsonArray(items)

/** Имя категории для сравнения: без регистра и «ё». */
internal fun normalizeName(name: String): String = name.trim().lowercase().replace('ё', 'е')
