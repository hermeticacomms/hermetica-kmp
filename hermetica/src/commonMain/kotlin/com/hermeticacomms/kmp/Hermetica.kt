package com.hermeticacomms.kmp

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@OptIn(ExperimentalUuidApi::class)
internal fun uuid(): String = Uuid.random().toString()

/**
 * The Hermetica client for Kotlin Multiplatform (Android, iOS, JVM). Every call returns at once and is safe from any thread:
 * state changes run in order on one background dispatcher. Events are queued in [store], sent in batches, retried with backoff
 * (one Idempotency-Key per batch) and kept while offline. On Android use `hermeticaAndroid(...)`, on iOS `hermeticaIos(...)`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Hermetica(
    siteKey: String,
    baseUrl: String,
    private val store: KeyValueStore,
    /** ios or android: the in-app messages to fetch and the push token's platform. */
    val platform: String,
    private val device: DeviceMetadata = DeviceMetadata(),
    private val cfg: Config = Config(),
    engine: HttpClientEngine? = null,
) {
    init {
        require(siteKey.isNotBlank()) { "siteKey is required" }
        require(!siteKey.startsWith("hm_sk_")) { "use the public site key, never a secret hm_sk_ key, in an app" }
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) { "baseUrl must be an http(s) URL" }
    }

    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serial)
    private val flushing = Mutex()
    @kotlin.concurrent.Volatile private var optedOut = store.get(K_OPT_OUT) == "1"
    private val http = Http(engine?.let { HttpClient(it) } ?: HttpClient(), baseUrl, siteKey, cfg) { optedOut }
    private val outbox = Outbox(store, http, cfg, ::uuid)
    private var timer: Job? = null

    /** Sent with in-app requests so per-session frequency caps hold. */
    val sessionId = "s" + uuid().replace("-", "").take(16)

    /** Runs on [screen] and [track] (on the SDK's thread): moments that can show an in-app message. Fetch and show from here. */
    var onInAppTrigger: ((InAppTrigger) -> Unit)? = null

    init {
        anonymousId()
        if (outbox.size > 0) scope.launch { flushNow() } // whatever was left from the last run
    }

    val isTrackingOptedOut get() = optedOut

    fun anonymousId(): String = store.get(K_AID) ?: uuid().also { store.set(K_AID, it) }

    // ---- identity and events ----

    /** Names the profile (any of external id, email, phone) and links this device's anonymous history to it. */
    fun identify(externalId: String?, email: String? = null, phone: String? = null, traits: Map<String, Any?> = emptyMap()) {
        scope.launch {
            externalId?.takeIf { it.isNotEmpty() }?.let { store.set(K_EXT, it) }
            email?.takeIf { it.isNotEmpty() }?.let { store.set(K_EMAIL, it) }
            phone?.takeIf { it.isNotEmpty() }?.let { store.set(K_PHONE, it) }
            sendIdentify(traits)
            reRegisterPush()
        }
    }

    /** Profile properties; held until the first [identify] when no identifier is known yet. */
    fun setProfileAttributes(traits: Map<String, Any?>) {
        scope.launch { sendIdentify(traits) }
    }

    private fun sendIdentify(traits: Map<String, Any?>) {
        if (optedOut) return
        val pending = store.get(K_TRAITS)?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
        val all = JsonObject(pending + traits.mapValues { toJson(it.value) })
        val ids = buildJsonObject {
            store.get(K_EXT)?.let { put("externalId", it) }
            store.get(K_EMAIL)?.let { put("email", it) }
            store.get(K_PHONE)?.let { put("phone", it) }
        }
        if (ids.isEmpty()) { store.set(K_TRAITS, all.toString()); return }
        store.remove(K_TRAITS)
        outbox.add(Outbox.IDENTIFY, buildJsonObject { put("anonymousId", anonymousId()); put("identifiers", ids); put("traits", all) })
        scope.launch { flushNow() }
    }

    /** [value] is in minor currency units (cents) with an ISO 4217 [currency]. */
    fun track(name: String, properties: Map<String, Any?> = emptyMap(), value: Long? = null, currency: String? = null) {
        scope.launch { enqueue(name, properties, value, currency) }
        onInAppTrigger?.let { f -> scope.launch { f(InAppTrigger.Event(name, properties)) } }
    }

    /** A screen view: a page_view with the screen name, and a screen trigger for in-app messages. */
    fun screen(name: String, properties: Map<String, Any?> = emptyMap()) {
        scope.launch { enqueue("page_view", properties + ("screen" to name), null, null) }
        onInAppTrigger?.let { f -> scope.launch { f(InAppTrigger.Screen(name)) } }
    }

    private fun enqueue(name: String, properties: Map<String, Any?>, value: Long?, currency: String?) {
        if (optedOut) return
        val ev = buildJsonObject {
            put("name", name)
            put("properties", JsonObject((toJson(properties) as JsonObject) + ("hm_sdk" to JsonPrimitive(SDK_VERSION))))
            put("dedupeKey", uuid())
            put("occurredAt", Clock.System.now().toString())
            put("anonymousId", anonymousId())
            if (value != null) put("value", value)
            if (currency != null) put("currency", currency)
        }
        if (outbox.add(Outbox.EVENT, ev) >= cfg.batchSize) scope.launch { flushNow() } else scheduleFlush()
    }

    private fun scheduleFlush() {
        if (timer?.isActive == true) return
        timer = scope.launch { delay(cfg.flushIntervalMs); flushNow() }
    }

    /** Logout: forget the profile and start a new anonymous id. The push token moves to the new anonymous visitor. */
    fun reset() {
        scope.launch {
            for (k in listOf(K_EXT, K_EMAIL, K_PHONE, K_AID, K_TRAITS)) store.remove(k)
            anonymousId()
            reRegisterPush()
        }
    }

    /** Opted out: nothing is tracked or sent and queued events are dropped. Push tokens still register. */
    fun setTrackingOptOut(optOut: Boolean) {
        scope.launch { setOptOut(optOut) }
    }

    private fun setOptOut(out: Boolean) {
        optedOut = out
        store.set(K_OPT_OUT, if (out) "1" else "0")
        if (out) outbox.clear()
    }

    /** For workspaces that require consent. analytics = false is the same as opting out. */
    fun setConsent(analytics: Boolean, marketing: Boolean) {
        scope.launch {
            store.set(K_CONSENT, buildJsonObject { put("analytics", analytics); put("marketing", marketing) }.toString())
            setOptOut(!analytics)
            flushNow()
        }
    }

    /** Sends the queue now (also every few seconds, at the batch size, and when the app goes to the background). */
    suspend fun flush(): Boolean = withContext(serial) { flushNow() }

    private suspend fun flushNow(): Boolean {
        if (optedOut) return true
        return flushing.withLock { outbox.flush(store.get(K_CONSENT)?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }) }
    }

    // ---- push ----

    private fun who(camel: Boolean) = buildJsonObject {
        store.get(K_EXT)?.let { put(if (camel) "externalId" else "external_id", it) }
        store.get(K_EMAIL)?.let { put("email", it) }
        store.get(K_PHONE)?.let { put(if (camel) "phoneNumber" else "phone_number", it) }
        put(if (camel) "anonymousId" else "anonymous_id", anonymousId())
    }

    internal fun pushBody(t: PushToken, type: String) = buildJsonObject {
        put("data", buildJsonObject {
            put("type", type)
            put("attributes", buildJsonObject {
                put("token", t.token); put("platform", platform); put("vendor", t.vendor)
                put("enablement_status", t.enablementStatus); put("background", t.background)
                put("device_metadata", buildJsonObject {
                    put("device_id", anonymousId()); put("sdk_version", SDK_VERSION); put("device_model", device.deviceModel)
                    put("os_name", device.osName.ifEmpty { platform }); put("os_version", device.osVersion); put("app_name", device.appName)
                    put("app_version", device.appVersion); put("app_build", device.appBuild); put("app_id", device.appId); put("environment", t.environment)
                })
                put("profile", buildJsonObject { put("data", buildJsonObject { put("type", "profile"); put("attributes", who(camel = false)) }) })
            })
        })
    }

    /** Registers the device token with the profile; true when the server took it. Re-sent on identify and reset. */
    suspend fun setPushToken(token: PushToken): Boolean = withContext(serial) {
        val ok = http.post("/client/push-tokens", pushBody(token, "push-token"), uuid()) in 200..299
        if (ok) store.set(K_TOKEN, buildJsonObject {
            put("token", token.token); put("vendor", token.vendor); put("environment", token.environment)
            put("enablementStatus", token.enablementStatus); put("background", token.background)
        }.toString())
        ok
    }

    /** Stops pushes to this device (logout, before [reset]). */
    suspend fun unregisterPushToken(): Boolean = withContext(serial) {
        val t = storedToken() ?: return@withContext true
        val ok = http.post("/client/push-token-unregister", pushBody(t, "push-token-unregister"), uuid()) in 200..299
        if (ok) store.remove(K_TOKEN)
        ok
    }

    private fun storedToken(): PushToken? {
        val o = store.get(K_TOKEN)?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
        fun s(k: String) = o[k]?.jsonPrimitive?.content
        return PushToken(s("token") ?: return null, s("vendor") ?: return null, s("environment") ?: "release", s("enablementStatus") ?: "AUTHORIZED", s("background") ?: "AVAILABLE")
    }

    private fun reRegisterPush() {
        val t = storedToken() ?: return
        scope.launch { setPushToken(t) }
    }

    private fun Map<String, Any?>.str(k: String) = (this[k] as? String)?.takeIf { it.isNotEmpty() }

    /** A notification was tapped, with its data: tracks `$opened_push` for Hermetica messages and returns the deep link, or null. */
    fun handlePushOpened(data: Map<String, Any?>): String? {
        data.str("hermetica_message_id")?.let { id -> scope.launch { enqueue("\$opened_push", mapOf("hermetica_message_id" to id), null, null) } }
        return data.str("deep_link") ?: data.str("url") ?: data.str("hermetica_url")
    }

    /** A notification arrived: tracks `$received_push` (delivered). True when it was a Hermetica message. */
    fun handlePushReceived(data: Map<String, Any?>): Boolean {
        val id = data.str("hermetica_message_id") ?: return false
        scope.launch { enqueue("\$received_push", mapOf("hermetica_message_id" to id), null, null) }
        return true
    }

    // ---- in-app ----

    /** The messages that fit this moment, best first. Empty on any failure: a missing message must never break the app. */
    suspend fun fetchInAppMessages(trigger: InAppTrigger): List<InAppMessage> {
        if (optedOut) return emptyList()
        val q = mutableMapOf(
            "platform" to platform, "trigger" to trigger.type, "session_id" to sessionId,
            "name" to when (trigger) { is InAppTrigger.Screen -> trigger.name; is InAppTrigger.Event -> trigger.name; else -> null },
            "properties" to (trigger as? InAppTrigger.Event)?.properties?.takeIf { it.isNotEmpty() }?.let { toJson(it).toString() },
        )
        for ((k, v) in who(camel = false)) q[k] = v.jsonPrimitive.content
        val items = http.get("/client/in-app-messages", q)?.get("items") as? JsonArray ?: return emptyList()
        return items.mapNotNull { (it as? JsonObject)?.let(InAppMessage::from) }
    }

    /** [type] is impression, click or dismiss; [button] is the tapped button's label. */
    suspend fun reportInAppEvent(message: InAppMessage, type: String, button: String? = null): Boolean {
        if (optedOut) return false
        val ev = buildJsonObject {
            put("messageId", message.id); put("variant", message.variant); put("type", type); put("platform", platform); put("sessionId", sessionId)
            if (button != null) put("button", button)
        }
        return http.post("/client/in-app-events", buildJsonObject { put("events", JsonArray(listOf(ev))); put("profile", who(camel = true)) }, uuid()) in 200..299
    }

    /** Stops the background work (tests, or when the app replaces the client). */
    fun close() = scope.cancel()

    private companion object {
        const val K_AID = "hm_aid"
        const val K_EXT = "hm_external_id"
        const val K_EMAIL = "hm_email"
        const val K_PHONE = "hm_phone"
        const val K_TRAITS = "hm_pending_traits"
        const val K_OPT_OUT = "hm_opt_out"
        const val K_CONSENT = "hm_consent"
        const val K_TOKEN = "hm_push_token"
    }
}
