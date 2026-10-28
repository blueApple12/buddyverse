package app.buddyverse.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Lenient wire JSON: a newer gateway may add keys or events at any time (hermes-api.md §5). */
val WireJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

inline fun <reified T> JsonElement.decodeAs(): T = WireJson.decodeFromJsonElement(this)
inline fun <reified T> T.toJson(): JsonElement = WireJson.encodeToJsonElement(this)

val JsonElement?.obj: JsonObject? get() = this as? JsonObject
val JsonElement?.arr: JsonArray? get() = this as? JsonArray
val JsonElement?.num: Double? get() = (this as? JsonPrimitive)?.doubleOrNull
fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
fun JsonObject?.long(key: String): Long? = (this?.get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
fun JsonObject?.double(key: String): Double? = (this?.get(key) as? JsonPrimitive)?.doubleOrNull
fun JsonObject?.bool(key: String): Boolean? = (this?.get(key) as? JsonPrimitive)?.booleanOrNull
fun JsonObject?.child(key: String): JsonObject? = this?.get(key) as? JsonObject
fun JsonObject?.list(key: String): List<JsonElement> = (this?.get(key) as? JsonArray).orEmpty()

/** Build params without nulls (canonical handlers refuse unknown or null-typed keys). */
fun params(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(buildMap {
    for ((k, v) in pairs) if (v != null) put(k, v.toJsonValue())
})

fun Any?.toJsonValue(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonValue() })
    is Iterable<*> -> JsonArray(map { it.toJsonValue() })
    else -> JsonPrimitive(toString())
}
