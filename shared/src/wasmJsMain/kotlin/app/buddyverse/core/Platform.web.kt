package app.buddyverse.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets

private fun jsRandom(n: Int): JsAny = js("crypto.getRandomValues(new Int8Array(n))")
internal fun jsLength(a: JsAny): Int = js("a.length")
internal fun jsByteAt(a: JsAny, i: Int): Byte = js("a[i]")
internal fun jsNewBytes(n: Int): JsAny = js("new Int8Array(n)")
internal fun jsSetByte(a: JsAny, i: Int, v: Byte): Unit = js("{ a[i] = v; }")
internal fun JsAny.toByteArray(): ByteArray = ByteArray(jsLength(this)) { jsByteAt(this, it) }
internal fun ByteArray.toJsBytes(): JsAny = jsNewBytes(size).also { a -> forEachIndexed { i, b -> jsSetByte(a, i, b) } }

private fun jsNow(): Double = js("Date.now()")
private fun jsLang(): String = js("(navigator.language || 'en')")

actual fun sha256(bytes: ByteArray): ByteArray = Sha256().apply { update(bytes, bytes.size) }.digest()
actual fun secureRandomBytes(count: Int): ByteArray = jsRandom(count).toByteArray()
actual fun nowMillis(): Long = jsNow().toLong()
actual fun systemLanguage(): String = jsLang().substringBefore('-').lowercase()
actual fun sha256Stream(): Sha256Stream = Sha256()

/** The browser's fetch + WebSocket. Same origin as the gateway (the PWA is served by it), so no CORS. */
actual fun platformHttpClient(): HttpClient = HttpClient(Js) {
    expectSuccess = false
    followRedirects = false
    install(SplitWsProtocols)
    install(WebSockets)
    install(HttpTimeout) { applyDefaults() }
}

/** Ktor-JS hands each Sec-WebSocket-Protocol value to `new WebSocket(url, protocols)` as one protocol: split the joined list. */
private val SplitWsProtocols = createClientPlugin("SplitWsProtocols") {
    onRequest { request, _ ->
        val joined = request.headers[HttpHeaders.SecWebSocketProtocol] ?: return@onRequest
        if (',' !in joined) return@onRequest
        request.headers.remove(HttpHeaders.SecWebSocketProtocol)
        joined.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { request.headers.append(HttpHeaders.SecWebSocketProtocol, it) }
    }
}

private fun lsGet(k: String): String? = js("localStorage.getItem(k)")
private fun lsSet(k: String, v: String): Unit = js("{ localStorage.setItem(k, v); }")
private fun lsDel(k: String): Unit = js("{ localStorage.removeItem(k); }")

/** Settings + tokens in the page's localStorage (a home-screen PWA has its own, kept while installed). */
class LocalStorageStore(private val prefix: String = "buddyverse.") : KeyValueStore {
    override fun get(key: String): String? = lsGet(prefix + key)
    override fun put(key: String, value: String?) = if (value == null) lsDel(prefix + key) else lsSet(prefix + key, value)
}

/** Pure-Kotlin SHA-256: SubtleCrypto is async, the PKCE and update hashes are not. */
private class Sha256 : Sha256Stream {
    private val h = intArrayOf(0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6, 0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19)
    private val buf = ByteArray(64)
    private var fill = 0
    private var total = 0L
    private val w = IntArray(64)

    override fun update(bytes: ByteArray, count: Int) {
        for (i in 0 until count) {
            buf[fill++] = bytes[i]
            if (fill == 64) { block(); fill = 0 }
        }
        total += count
    }

    override fun digest(): ByteArray {
        val bits = total * 8
        update(byteArrayOf(0x80.toByte()), 1)
        while (fill != 56) update(byteArrayOf(0), 1)
        update(ByteArray(8) { (bits ushr (56 - 8 * it)).toByte() }, 8)
        return ByteArray(32) { (h[it / 4] ushr (24 - 8 * (it % 4))).toByte() }
    }

    private fun block() {
        for (i in 0 until 16) w[i] = ((buf[4 * i].toInt() and 0xff) shl 24) or ((buf[4 * i + 1].toInt() and 0xff) shl 16) or
            ((buf[4 * i + 2].toInt() and 0xff) shl 8) or (buf[4 * i + 3].toInt() and 0xff)
        for (i in 16 until 64) {
            val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
            val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]; var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (i in 0 until 64) {
            val t1 = hh + (e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)) + ((e and f) xor (e.inv() and g)) + K[i] + w[i]
            val t2 = (a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)) + ((a and b) xor (a and c) xor (b and c))
            hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
    }

    private companion object {
        val K = longArrayOf(
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
        ).map { it.toInt() }.toIntArray()
    }
}
