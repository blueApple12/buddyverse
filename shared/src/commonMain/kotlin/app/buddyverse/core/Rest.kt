package app.buddyverse.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

class RestError(val status: Int, val detail: String) : Exception("HTTP $status: $detail")

/** Same-origin REST with the bearer token. Never sends `Origin` (hermes-api.md §1.2 rule 2). One forced refresh on 401. */
class Rest(private val http: HttpClient, private val tokens: TokenSource) {
    suspend fun get(path: String): JsonElement = send(HttpMethod.Get, path, null)
    /** [timeoutMs] overrides the 30 s default ([Long.MAX_VALUE] = wait as long as the server works). */
    suspend fun post(path: String, body: JsonElement? = null, timeoutMs: Long? = null): JsonElement = send(HttpMethod.Post, path, body, timeoutMs)
    suspend fun put(path: String, body: JsonElement): JsonElement = send(HttpMethod.Put, path, body)
    suspend fun patch(path: String, body: JsonElement): JsonElement = send(HttpMethod.Patch, path, body)
    suspend fun delete(path: String, body: JsonElement? = null): JsonElement = send(HttpMethod.Delete, path, body)

    suspend fun send(method: HttpMethod, path: String, body: JsonElement?, timeoutMs: Long? = null): JsonElement {
        for (force in listOf(false, true)) {
            val token = tokens.accessToken(force) ?: throw SignedOut()
            val r = http.request("${tokens.base}$path") {
                this.method = method
                if (timeoutMs != null) timeout { requestTimeoutMillis = timeoutMs; socketTimeoutMillis = timeoutMs }
                header(HttpHeaders.Authorization, "Bearer $token")
                if (body != null) { contentType(ContentType.Application.Json); setBody(body.toString()) }
            }
            if (r.status.value == 401 && !force) continue
            val text = r.bodyAsText()
            if (r.status.value !in 200..299) throw RestError(r.status.value, detailOf(text))
            return if (text.isBlank()) JsonNull else runCatching { WireJson.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
        }
        throw SignedOut()
    }

    /** Multipart upload (`POST /api/chat/file-upload`, field `file`). */
    suspend fun upload(path: String, name: String, mime: String, bytes: ByteArray): JsonElement {
        val token = tokens.accessToken() ?: throw SignedOut()
        val r = http.request("${tokens.base}$path") {
            method = HttpMethod.Post
            timeout { requestTimeoutMillis = 180_000 } // big files over a slow tunnel
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(MultiPartFormDataContent(formData {
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentType, mime)
                    append(HttpHeaders.ContentDisposition, "filename=\"${name.replace("\"", "")}\"")
                })
            }))
        }
        val text = r.bodyAsText()
        if (r.status.value !in 200..299) throw RestError(r.status.value, detailOf(text))
        return WireJson.parseToJsonElement(text)
    }

    private fun detailOf(text: String): String =
        runCatching { WireJson.parseToJsonElement(text).obj?.get("detail")?.let { (it as? JsonPrimitive)?.content ?: it.toString() } }.getOrNull()
            ?: text.take(200)
}
