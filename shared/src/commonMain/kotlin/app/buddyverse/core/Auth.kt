package app.buddyverse.core

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
data class Tokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_at") val expiresAt: Double = 0.0,
    @SerialName("provider") val provider: String = "basic",
) {
    /** Refresh a few minutes before the 12 h access token runs out. */
    fun expiresSoon(nowMs: Long): Boolean = expiresAt > 0 && nowMs / 1000.0 > expiresAt - 300
}

sealed class AuthError(message: String) : Exception(message) {
    class BadCredentials : AuthError("bad_credentials")
    class RateLimited : AuthError("rate_limited")
    class Unreachable(cause: String) : AuthError("unreachable: $cause")
    class Expired : AuthError("session_expired")
    class Protocol(what: String) : AuthError("protocol: $what")
}

/** A pasted or scanned link becomes `scheme://host[:port]` (BOM, spaces, paths and a missing scheme are tolerated). */
fun normalizeBaseUrl(raw: String): String? {
    var s = raw.replace("﻿", "").trim().trimEnd('/')
    if (s.isEmpty()) return null
    if (!s.contains("://")) s = (if (s.startsWith("127.") || s.startsWith("localhost") || s.startsWith("192.168.")) "http://" else "https://") + s
    val url = runCatching { Url(s) }.getOrNull() ?: return null
    if (url.host.isBlank() || url.protocol.name !in setOf("http", "https")) return null
    val port = if (url.specifiedPort == 0 || url.specifiedPort == url.protocol.defaultPort) "" else ":${url.specifiedPort}"
    return "${url.protocol.name}://${url.host}$port"
}

@OptIn(ExperimentalEncodingApi::class)
internal object Pkce {
    private val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    fun verifier(): String = b64.encode(secureRandomBytes(32))
    fun challenge(verifier: String): String = b64.encode(sha256(verifier.encodeToByteArray()))
}

/** Pull `name=value` pairs out of Set-Cookie headers (attributes dropped) and join them for a Cookie header. */
internal fun cookieHeader(setCookies: List<String>): String =
    setCookies.mapNotNull { it.substringBefore(';').trim().takeIf { p -> '=' in p } }.joinToString("; ")

/**
 * Hermes native bearer sign-in (RFC 8252 brokered by the gateway; hermes-api.md §1.4 flow B).
 * The loopback redirect is never served: the password login returns it in JSON.
 */
class HermesAuth(private val http: HttpClient) {
    suspend fun signIn(base: String, username: String, password: String, provider: String = "basic"): Tokens {
        val verifier = Pkce.verifier()
        val state = uuid()
        val redirect = "http://127.0.0.1:53682/callback"
        val authorize = guard {
            http.get("$base/auth/native/authorize?provider=$provider&code_challenge=${Pkce.challenge(verifier)}" +
                "&code_challenge_method=S256&redirect_uri=${redirect.encodeURLParameter()}&state=$state")
        }
        val pkceCookie = cookieHeader(authorize.headers.getAll(HttpHeaders.SetCookie).orEmpty())
        // A browser (the PWA) hides the redirect (status 0) and keeps the cookie itself: same origin, sent back on its own.
        if (pkceCookie.isEmpty() && authorize.status.value != 0) throw AuthError.Protocol("no pkce cookie (${authorize.status.value})")
        val login = guard {
            http.post("$base/auth/password-login") {
                if (pkceCookie.isNotEmpty()) header(HttpHeaders.Cookie, pkceCookie)
                contentType(ContentType.Application.Json)
                setBody(params("provider" to provider, "username" to username, "password" to password, "next" to "").toString())
            }
        }
        when (login.status.value) {
            401, 403 -> throw AuthError.BadCredentials()
            429 -> throw AuthError.RateLimited()
        }
        if (!login.status.isSuccess()) throw AuthError.Protocol("login ${login.status.value}")
        val next = WireJson.parseToJsonElement(login.bodyAsText()).obj.str("next") ?: throw AuthError.Protocol("no next")
        val nextUrl = runCatching { Url(next) }.getOrNull() ?: throw AuthError.Protocol("bad next")
        if (nextUrl.parameters["state"] != state) throw AuthError.Protocol("state mismatch")
        val code = nextUrl.parameters["code"] ?: throw AuthError.Protocol("no code")
        return tokenCall("$base/auth/native/token", params("code" to code, "code_verifier" to verifier))
    }

    suspend fun refresh(base: String, tokens: Tokens): Tokens =
        tokenCall("$base/auth/native/refresh", params("refresh_token" to tokens.refreshToken, "provider" to tokens.provider))

    private suspend fun tokenCall(url: String, body: JsonObject): Tokens {
        val r = guard {
            http.post(url) { contentType(ContentType.Application.Json); setBody(body.toString()) }
        }
        if (r.status.value == 401) throw AuthError.Expired()
        if (r.status.value == 503) throw AuthError.Unreachable("provider unavailable")
        if (!r.status.isSuccess()) throw AuthError.Protocol("token ${r.status.value}")
        return WireJson.decodeFromString(Tokens.serializer(), r.bodyAsText())
    }

    private suspend fun guard(block: suspend () -> HttpResponse): HttpResponse =
        try { block() } catch (e: AuthError) { throw e } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { throw AuthError.Unreachable(e.message ?: e::class.simpleName.orEmpty()) }
}
