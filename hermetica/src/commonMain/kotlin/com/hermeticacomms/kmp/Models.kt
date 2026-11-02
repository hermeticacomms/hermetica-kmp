package com.hermeticacomms.kmp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** Where the SDK keeps its state. Android: SharedPreferences; iOS: NSUserDefaults. Implementations must be thread-safe. */
interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

/** Forgets everything when the process ends (tests, or when nothing may be written to disk). */
class MemoryStore : KeyValueStore {
    private val m = HashMap<String, String>()
    override fun get(key: String) = m[key]
    override fun set(key: String, value: String) { m[key] = value }
    override fun remove(key: String) { m.remove(key) }
}

/** Optional description of the app and handset, sent with push tokens. */
data class DeviceMetadata(
    val deviceModel: String = "", val osName: String = "", val osVersion: String = "",
    val appName: String = "", val appVersion: String = "", val appBuild: String = "", val appId: String = "",
)

data class Config(
    val batchSize: Int = 20,
    val flushIntervalMs: Long = 2_000,
    val maxQueue: Int = 200,
    val maxRetries: Int = 5,
    val retryBaseMs: Long = 500,
)

/** A device token and what the OS allows, in the server's wire values. [vendor] is apns or fcm. */
data class PushToken(
    val token: String,
    val vendor: String,
    val environment: String = "release",
    val enablementStatus: String = "AUTHORIZED",
    val background: String = "AVAILABLE",
)

/** The moments that can show an in-app message: app open, a screen, or any tracked event. */
sealed class InAppTrigger(internal val type: String) {
    data object AppOpen : InAppTrigger("app_open")
    data class Screen(val name: String) : InAppTrigger("screen_view")
    data class Event(val name: String, val properties: Map<String, Any?> = emptyMap()) : InAppTrigger("event")
}

/** An in-app message the server picked. [content] holds the blocks (heading, text, image, buttons); [style] the colours. */
data class InAppMessage(val id: String, val variant: String, val layout: String, val content: JsonObject, val style: JsonObject) {
    companion object {
        internal fun from(o: JsonObject) = InAppMessage(
            id = o["id"]?.jsonPrimitive?.content ?: "",
            variant = o["variant"]?.jsonPrimitive?.content ?: "a",
            layout = o["layout"]?.jsonPrimitive?.content ?: "modal",
            content = o["content"] as? JsonObject ?: JsonObject(emptyMap()),
            style = o["style"] as? JsonObject ?: JsonObject(emptyMap()),
        )
    }
}

/** Plain Kotlin values (maps, lists, strings, numbers, booleans, null) to JSON; anything else becomes its string form. */
internal fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
    is Iterable<*> -> JsonArray(v.map { toJson(it) })
    is Array<*> -> JsonArray(v.map { toJson(it) })
    else -> JsonPrimitive(v.toString())
}
