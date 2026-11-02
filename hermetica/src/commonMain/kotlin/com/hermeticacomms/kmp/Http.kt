package com.hermeticacomms.kmp

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class Http(private val client: HttpClient, baseUrl: String, private val siteKey: String, private val cfg: Config, private val optedOut: () -> Boolean) {
    private val base = baseUrl.trimEnd('/')

    private fun url(path: String) = base + path

    // The /client endpoints take the public key as company_id too (Klaviyo-compatible).
    private fun io.ktor.client.request.HttpRequestBuilder.common(path: String) {
        header("Authorization", "Bearer $siteKey")
        header("Accept", "application/json")
        if (optedOut()) header("Sec-GPC", "1") // the server drops everything from a visitor who sends Global Privacy Control
        if (path.startsWith("/client/")) parameter("company_id", siteKey)
    }

    /** The 2xx JSON object, or null on any failure. */
    suspend fun get(path: String, query: Map<String, String?>): JsonObject? = try {
        val res = client.get(url(path)) {
            common(path)
            for ((k, v) in query) if (!v.isNullOrEmpty()) parameter(k, v)
        }
        if (res.status.value in 200..299) Json.parseToJsonElement(res.bodyAsText()) as? JsonObject else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Retries network errors, 429 and 5xx with backoff, always with the same Idempotency-Key. The last status, or -1 offline. */
    suspend fun post(path: String, body: JsonObject, idempotencyKey: String): Int {
        val text = body.toString()
        var code = -1
        for (attempt in 0 until cfg.maxRetries) {
            code = try {
                client.post(url(path)) {
                    common(path)
                    header("Idempotency-Key", idempotencyKey)
                    contentType(ContentType.Application.Json)
                    setBody(text)
                }.status.value
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                -1
            }
            if (code in 200..499 && code != 429) return code
            if (attempt < cfg.maxRetries - 1) delay(cfg.retryBaseMs * (1L shl attempt) + Random.nextLong(cfg.retryBaseMs + 1))
        }
        return code
    }
}
