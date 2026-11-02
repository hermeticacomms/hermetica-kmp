package com.hermeticacomms.kmp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The offline queue, persisted in the store so nothing is lost when the app is killed or offline. Items keep their order:
 * identify calls go one by one, runs of events go in batches. Only touched from the client's serial dispatcher.
 */
internal class Outbox(private val store: KeyValueStore, private val http: Http, private val cfg: Config, private val newId: () -> String) {
    private val items: MutableList<JsonObject> = load()

    private fun load(): MutableList<JsonObject> = try {
        (Json.parseToJsonElement(store.get(KEY) ?: "[]") as JsonArray).map { it.jsonObject }.toMutableList()
    } catch (_: Exception) {
        mutableListOf()
    }

    private fun save() = store.set(KEY, JsonArray(items).toString())

    val size get() = items.size

    /** The queue length after adding; the oldest items go once the queue is full. */
    fun add(kind: String, body: JsonObject): Int {
        items += JsonObject(mapOf("id" to JsonPrimitive(newId()), "kind" to JsonPrimitive(kind), "body" to body))
        if (items.size > cfg.maxQueue) items.subList(0, items.size - cfg.maxQueue).clear()
        save()
        return items.size
    }

    fun clear() { items.clear(); save() }

    /** Sends everything queued; stops at the first failure and keeps the rest. True when the queue is empty. */
    suspend fun flush(consent: JsonObject?): Boolean {
        while (true) {
            val batch = head() ?: return true
            val first = batch.first()
            val id = first.str("id")
            val code = if (first.str("kind") == IDENTIFY) {
                http.post("/ingest/v1/identify", withConsent(first["body"]!!.jsonObject, consent), id)
            } else {
                // Same head and size means the same items, so a retry after a restart reuses the key.
                http.post("/ingest/v1/batch", withConsent(JsonObject(mapOf("events" to JsonArray(batch.map { it["body"]!! }))), consent), "batch-$id-${batch.size}")
            }
            // 4xx will not get better on retry: drop those items rather than block the queue forever.
            if (code !in 200..499 || code == 429) return false
            val sent = batch.map { it.str("id") }.toSet()
            items.removeAll { it.str("id") in sent }
            save()
        }
    }

    private fun head(): List<JsonObject>? {
        val first = items.firstOrNull() ?: return null
        if (first.str("kind") == IDENTIFY) return listOf(first)
        return items.asSequence().takeWhile { it.str("kind") == EVENT }.take(minOf(cfg.batchSize, SERVER_MAX_BATCH)).toList()
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun withConsent(o: JsonObject, consent: JsonObject?) = if (consent == null) o else JsonObject(o + ("consent" to consent))

    companion object {
        const val KEY = "hm_outbox"
        const val EVENT = "event"
        const val IDENTIFY = "identify"
        const val SERVER_MAX_BATCH = 50
    }
}
