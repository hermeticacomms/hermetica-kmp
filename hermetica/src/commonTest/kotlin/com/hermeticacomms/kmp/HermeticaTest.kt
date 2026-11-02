package com.hermeticacomms.kmp

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HermeticaTest {
    private class Server {
        val seen = mutableListOf<HttpRequestData>()
        val statuses = ArrayDeque<Int>()
        var reply = "{}"
        val engine = MockEngine { r ->
            seen += r
            respond(reply, HttpStatusCode.fromValue(statuses.removeFirstOrNull() ?: 200))
        }
        fun to(path: String) = seen.filter { it.url.encodedPath == path }
        fun body(r: HttpRequestData) = Json.parseToJsonElement((r.body as TextContent).text).jsonObject
    }

    private fun start(server: Server, store: KeyValueStore = MemoryStore()) =
        Hermetica("pk_test", "https://api.test/", store, "ios", DeviceMetadata(appName = "shop"), Config(retryBaseMs = 0, flushIntervalMs = 3_600_000), server.engine)

    private fun JsonObject.at(vararg path: String): String = path.fold(this as kotlinx.serialization.json.JsonElement) { e, k -> e.jsonObject[k]!! }.jsonPrimitive.content

    @Test
    fun batchesEventsAndRetriesWithTheSameIdempotencyKey() = runTest {
        val server = Server()
        val hm = start(server)
        hm.track("added_to_cart", mapOf("sku" to "A1"), 1999, "USD")
        hm.screen("cart")
        server.statuses.addAll(listOf(503, 429))
        assertTrue(hm.flush())
        val sent = server.to("/ingest/v1/batch")
        assertEquals(3, sent.size)
        assertEquals(1, sent.map { it.headers["Idempotency-Key"] }.toSet().size)
        val events = server.body(sent.last())["events"]!!.jsonArray
        assertEquals(listOf("added_to_cart", "page_view"), events.map { it.jsonObject.at("name") })
        assertEquals("cart", events[1].jsonObject.at("properties", "screen"))
        assertEquals("1999", events[0].jsonObject.at("value"))
        hm.close()
    }

    @Test
    fun keepsTheQueueOfflineAndOptOutDropsIt() = runTest {
        val server = Server()
        val store = MemoryStore()
        val hm = start(server, store)
        repeat(5) { server.statuses.add(503) }
        hm.track("a")
        assertFalse(hm.flush())
        hm.setTrackingOptOut(true)
        hm.track("b")
        assertTrue(hm.flush())
        assertEquals("[]", store.get("hm_outbox"))
        hm.close()
    }

    @Test
    fun identifyWaitsForAnIdAndThePushTokenFollowsTheProfile() = runTest {
        val server = Server()
        val hm = start(server)
        hm.setProfileAttributes(mapOf("plan" to "pro"))
        assertTrue(hm.setPushToken(PushToken("apns-1", "apns", environment = "debug")))
        hm.identify("user-1", email = "a@b.co")
        hm.flush()
        // the re-registration runs in the background, on real threads (runTest's delay would be virtual)
        repeat(200) { if (server.to("/client/push-tokens").size < 2) withContext(Dispatchers.Default) { delay(10) } }
        val id = server.body(server.to("/ingest/v1/identify").single())
        assertEquals("user-1", id.at("identifiers", "externalId"))
        assertEquals("pro", id.at("traits", "plan"))
        val push = server.to("/client/push-tokens")
        assertEquals(2, push.size)
        assertEquals("user-1", server.body(push[1]).at("data", "attributes", "profile", "data", "attributes", "external_id"))
        assertEquals("pk_test", push[0].url.parameters["company_id"])
        assertTrue(hm.unregisterPushToken())
        assertEquals(1, server.to("/client/push-token-unregister").size)
        hm.close()
    }

    @Test
    fun inAppMessagesAndPushOpens() = runTest {
        val server = Server()
        val hm = start(server)
        server.reply = """{"items":[{"id":"m1","variant":"b","layout":"banner_top","content":{"blocks":[]},"style":{}}]}"""
        val items = hm.fetchInAppMessages(InAppTrigger.Screen("cart"))
        assertEquals("m1", items.single().id)
        val q = server.to("/client/in-app-messages").single().url.parameters
        assertEquals("screen_view", q["trigger"])
        assertEquals("ios", q["platform"])
        server.reply = "{}"
        assertTrue(hm.reportInAppEvent(items[0], "click", "Shop"))
        assertEquals("shop://sale", hm.handlePushOpened(mapOf("hermetica_message_id" to "p1", "deep_link" to "shop://sale")))
        hm.flush()
        assertEquals("\$opened_push", server.body(server.to("/ingest/v1/batch").single())["events"]!!.jsonArray[0].jsonObject.at("name"))
        hm.close()
    }

    @Test
    fun refusesASecretKey() {
        assertFailsWith<IllegalArgumentException> { Hermetica("hm_sk_live", "https://api.test", MemoryStore(), "ios") }
    }
}
