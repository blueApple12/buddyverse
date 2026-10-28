package app.buddyverse.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

actual fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { SecureRandom().nextBytes(it) }
actual fun nowMillis(): Long = System.currentTimeMillis()
actual fun systemLanguage(): String = java.util.Locale.getDefault().language
actual fun platformHttpClient(): HttpClient = HttpClient(OkHttp) {
    expectSuccess = false
    followRedirects = false
    install(WebSockets)
    install(HttpTimeout) { applyDefaults() }
    engine {
        config {
            connectTimeout(15, TimeUnit.SECONDS)
            readTimeout(0, TimeUnit.SECONDS) // the socket; REST gets HttpTimeout's 30 s (cron "Run now" opts out per request)
            writeTimeout(60, TimeUnit.SECONDS)
            followRedirects(false)
            retryOnConnectionFailure(true)
        }
    }
}

private class JvmSha256 : Sha256Stream {
    private val md = MessageDigest.getInstance("SHA-256")
    override fun update(bytes: ByteArray, count: Int) = md.update(bytes, 0, count)
    override fun digest(): ByteArray = md.digest()
}
actual fun sha256Stream(): Sha256Stream = JvmSha256()
