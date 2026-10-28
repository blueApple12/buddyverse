package app.buddyverse.core

import io.ktor.client.HttpClient

/** SHA-256 digest (PKCE). */
expect fun sha256(bytes: ByteArray): ByteArray

/** Cryptographically secure random bytes (PKCE verifier, request ids). */
expect fun secureRandomBytes(count: Int): ByteArray

/** The platform HTTP + WebSocket client (OkHttp on Android and desktop). */
expect fun platformHttpClient(): HttpClient

/** REST default: a dead pooled connection fails in 30 s instead of hanging (WebSockets are exempt in Ktor). */
fun io.ktor.client.plugins.HttpTimeoutConfig.applyDefaults() {
    requestTimeoutMillis = 30_000
    socketTimeoutMillis = 30_000
    connectTimeoutMillis = 15_000
}

/** The phone/PC language code ("he"/"iw" for Hebrew). */
expect fun systemLanguage(): String

/** Wall clock in epoch milliseconds. */
expect fun nowMillis(): Long

/** Small key-value store for settings and the sign-in tokens (encrypted at rest where the platform allows). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

fun uuid(): String {
    val b = secureRandomBytes(16)
    b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
    b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
    val h = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
}
