package ru.hznik.budget.data.backup

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Настройки в резервной копии (spec data-export «Резервная копия», design.md решение 2): только то, что относится
 * к пользователю, а не к телефону. Отметки настройки автоимпорта, разрешения, счётчик токенов «на этом телефоне»,
 * журнал уведомлений, демо-данные и режим образцов не переносятся.
 */
object SettingsSnapshot {
    val KEYS = setOf(
        "income_kind", "income_day",
        "lock_enabled", "lock_timeout", "lock_hide_in_recents", "lock_hide_widget_amount",
        "auto_reconcile",
        "ai_enabled", "ai_hide_merchants",
        "notifications_disabled",
        "own_transfer_names",
    )

    /** Разрешённые ключи с типом значения, в JSON. */
    fun toJson(preferences: Preferences): String {
        val entries = preferences.asMap()
            .filterKeys { it.name in KEYS }
            .mapNotNull { (key, value) -> encode(value)?.let { key.name to it } }
            .toMap()
        return JsonObject(entries).toString()
    }

    /** Записывает в [target] настройки из [json]; неизвестные ключи и типы пропускаются. */
    fun apply(json: String, target: MutablePreferences) {
        Json.parseToJsonElement(json).jsonObject.forEach { (name, element) ->
            if (name !in KEYS) return@forEach
            val entry = element.jsonObject
            val value = entry.getValue(VALUE)
            when (entry.getValue(TYPE).jsonPrimitive.content) {
                BOOLEAN -> target[booleanPreferencesKey(name)] = value.jsonPrimitive.boolean
                INT -> target[intPreferencesKey(name)] = value.jsonPrimitive.int
                LONG -> target[longPreferencesKey(name)] = value.jsonPrimitive.long
                STRING -> target[stringPreferencesKey(name)] = value.jsonPrimitive.content
                STRING_SET -> target[stringSetPreferencesKey(name)] = value.jsonArray.map { it.jsonPrimitive.content }.toSet()
            }
        }
    }

    private fun encode(value: Any): JsonObject? = when (value) {
        is Boolean -> entry(BOOLEAN, JsonPrimitive(value))
        is Int -> entry(INT, JsonPrimitive(value))
        is Long -> entry(LONG, JsonPrimitive(value))
        is String -> entry(STRING, JsonPrimitive(value))
        is Set<*> -> entry(STRING_SET, JsonArray(value.filterIsInstance<String>().map { JsonPrimitive(it) }))
        else -> null
    }

    private fun entry(type: String, value: kotlinx.serialization.json.JsonElement) = JsonObject(mapOf(TYPE to JsonPrimitive(type), VALUE to value))

    private const val TYPE = "type"
    private const val VALUE = "value"
    private const val BOOLEAN = "boolean"
    private const val INT = "int"
    private const val LONG = "long"
    private const val STRING = "string"
    private const val STRING_SET = "string_set"
}
