package app.buddyverse.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties
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
            readTimeout(0, TimeUnit.SECONDS)
            writeTimeout(60, TimeUnit.SECONDS)
            followRedirects(false)
        }
    }
}

/** Desktop settings file under the user profile. ponytail: plain file; move tokens to Windows DPAPI when the desktop app ships. */
/** The PC app's own settings + sign-in tokens: `~\.buddyverse\settings.properties` (with BUDDYVERSE_HOME set: in that folder). */
class FileStore(private val file: File = System.getenv("BUDDYVERSE_HOME")?.takeIf { it.isNotBlank() }?.let { File(it, "settings.properties") }
    ?: File(System.getProperty("user.home"), ".buddyverse/settings.properties")) : KeyValueStore {
    private val props = Properties().apply { if (file.isFile) file.inputStream().use(::load) }
    override fun get(key: String): String? = props.getProperty(key)
    @Synchronized override fun put(key: String, value: String?) {
        if (value == null) props.remove(key) else props.setProperty(key, value)
        // Write a temp file, then swap it in: a crash mid-write never leaves a truncated settings file (and lost tokens).
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { props.store(it, null); it.fd.sync() }
        try { Files.move(tmp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(tmp.toPath(), file.toPath(), REPLACE_EXISTING) }
    }
}

private class JvmSha256 : Sha256Stream {
    private val md = MessageDigest.getInstance("SHA-256")
    override fun update(bytes: ByteArray, count: Int) = md.update(bytes, 0, count)
    override fun digest(): ByteArray = md.digest()
}
actual fun sha256Stream(): Sha256Stream = JvmSha256()
