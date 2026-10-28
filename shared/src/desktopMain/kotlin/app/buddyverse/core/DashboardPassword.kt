package app.buddyverse.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The Webapp password at rest, in the exact format of Hermes' basic dashboard auth
 * (`plugins/dashboard_auth/basic/__init__.py` `hash_password`): `scrypt$16384$8$1$<salt b64>$<key b64>`, 16-byte salt,
 * 32-byte key, standard base64 with padding. Its `_settings()` reads `HERMES_DASHBOARD_BASIC_AUTH_PASSWORD_HASH` from
 * `.env`, so the plaintext `_PASSWORD` line can go. The plaintext is never logged.
 */
object DashboardPassword {
    const val HASH_KEY = "HERMES_DASHBOARD_BASIC_AUTH_PASSWORD_HASH"
    private const val N = 16384
    private const val R = 8
    private const val P = 1

    fun hash(password: String, salt: ByteArray = java.security.SecureRandom().let { r -> ByteArray(16).also(r::nextBytes) }): String {
        val pw = password.encodeToByteArray()
        try {
            val dk = Scrypt.derive(pw, salt, N, R, P, 32)
            val b64 = Base64.getEncoder()
            return "scrypt\$$N\$$R\$$P\$${b64.encodeToString(salt)}\$${b64.encodeToString(dk)}"
        } finally { pw.fill(0) }
    }

    /** Same check as the plugin's `_verify_password` (any malformed string is false). */
    fun verify(password: String, encoded: String): Boolean = runCatching {
        val f = encoded.split('$')
        if (f.size != 6 || f[0] != "scrypt") return false
        val expected = Base64.getDecoder().decode(f[5])
        val actual = Scrypt.derive(password.encodeToByteArray(), Base64.getDecoder().decode(f[4]), f[1].toInt(), f[2].toInt(), f[3].toInt(), expected.size)
        java.security.MessageDigest.isEqual(actual, expected)
    }.getOrDefault(false)

    /** A non-comment `.env` line that assigns [key] (`KEY=…`, `export KEY=…`, `KEY = …`). */
    private fun defines(line: String, key: String): Boolean {
        val t = line.replace("\uFEFF", "").trim().removePrefix("export ").trim()
        return !t.startsWith("#") && '=' in t && t.substringBefore('=').trim() == key
    }

    /** The value [key] has in `.env` text (last line wins, quotes dropped), or null. */
    fun envValue(env: String, key: String): String? = env.lines().lastOrNull { defines(it, key) }
        ?.substringAfter('=')?.trim()?.let { v -> if (v.length >= 2 && v.first() == v.last() && v.first() in "'\"") v.substring(1, v.length - 1) else v }
        ?.takeIf { it.isNotEmpty() }

    /**
     * `.env` text with the login stored as [hash] only: every plaintext `_PASSWORD` and old `_PASSWORD_HASH` line goes, the
     * hash takes the first one's place (else it's appended). Single-quoted, so dotenv takes the `$`s literally.
     * Everything else, the line endings and a BOM stay as they were.
     */
    fun envWithHash(env: String, hash: String): String {
        require(hash.startsWith("scrypt$") && '\'' !in hash && '\n' !in hash)
        val nl = if ("\r\n" in env) "\r\n" else "\n"
        val lines = if (env.isEmpty()) mutableListOf() else env.split(nl).toMutableList()
        val endsWithNl = lines.isNotEmpty() && lines.last().isEmpty()
        if (endsWithNl) lines.removeAt(lines.lastIndex)
        val out = mutableListOf<String>()
        var placed = false
        val line = "$HASH_KEY='$hash'"
        for (l in lines) {
            if (defines(l, WebappLogin.PASS_KEY) || defines(l, HASH_KEY)) {
                if (!placed) { out += (if (l.startsWith("\uFEFF")) "\uFEFF" else "") + line; placed = true }
            } else out += l
        }
        if (!placed) out += line
        return out.joinToString(nl) + nl
    }

    /** One sign-in against [base] (the tokens are thrown away), to check a password. Null = accepted. */
    suspend fun tryLogin(base: String, user: String, password: String): AuthError? {
        val http = platformHttpClient()
        return try { HermesAuth(http).signIn(base, user, password); null } catch (e: AuthError) { e } finally { http.close() }
    }

    /** Replace [target] atomically (temp file in the same folder, then a move), copying the old one to [backup] first. */
    fun writeAtomic(target: File, text: String, backup: File? = null) {
        if (backup != null && target.isFile) Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { tmp.delete() }
    }
}

/** scrypt (RFC 7914) over the JDK's HMAC-SHA256. */
internal object Scrypt {
    fun derive(password: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int, dkLen: Int): ByteArray {
        require(n > 1 && n and (n - 1) == 0 && r > 0 && p > 0 && dkLen > 0)
        val blockBytes = 128 * r
        val b = pbkdf2(password, salt, p * blockBytes)
        val words = 32 * r
        val v = IntArray(words * n)
        val x = IntArray(words)
        for (i in 0 until p) {
            val off = i * blockBytes
            for (k in 0 until words) x[k] = le32(b, off + 4 * k)
            for (j in 0 until n) { x.copyInto(v, j * words); blockMix(x, r) }
            for (j in 0 until n) {
                val idx = x[(2 * r - 1) * 16] and (n - 1)
                for (k in 0 until words) x[k] = x[k] xor v[idx * words + k]
                blockMix(x, r)
            }
            for (k in 0 until words) putLe32(b, off + 4 * k, x[k])
        }
        return pbkdf2(password, b, dkLen)
    }

    /** PBKDF2-HMAC-SHA256 with one iteration (all scrypt needs). */
    private fun pbkdf2(password: ByteArray, salt: ByteArray, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // SecretKeySpec refuses an empty key; HMAC pads keys with zeros, so one zero byte is the same key.
        mac.init(SecretKeySpec(if (password.isEmpty()) ByteArray(1) else password, "HmacSHA256"))
        val out = ByteArray(len)
        var block = 1
        var off = 0
        while (off < len) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            val t = mac.doFinal()
            t.copyInto(out, off, 0, minOf(t.size, len - off))
            off += t.size; block++
        }
        return out
    }

    private fun blockMix(b: IntArray, r: Int) {
        val y = IntArray(b.size)
        val x = b.copyOfRange((2 * r - 1) * 16, 2 * r * 16)
        for (i in 0 until 2 * r) {
            for (k in 0 until 16) x[k] = x[k] xor b[i * 16 + k]
            salsa8(x)
            x.copyInto(y, (if (i % 2 == 0) i / 2 else r + i / 2) * 16)
        }
        y.copyInto(b)
    }

    private fun rot(a: Int, s: Int) = (a shl s) or (a ushr (32 - s))

    private fun salsa8(b: IntArray) {
        val x = b.copyOf()
        repeat(4) {
            x[4] = x[4] xor rot(x[0] + x[12], 7); x[8] = x[8] xor rot(x[4] + x[0], 9)
            x[12] = x[12] xor rot(x[8] + x[4], 13); x[0] = x[0] xor rot(x[12] + x[8], 18)
            x[9] = x[9] xor rot(x[5] + x[1], 7); x[13] = x[13] xor rot(x[9] + x[5], 9)
            x[1] = x[1] xor rot(x[13] + x[9], 13); x[5] = x[5] xor rot(x[1] + x[13], 18)
            x[14] = x[14] xor rot(x[10] + x[6], 7); x[2] = x[2] xor rot(x[14] + x[10], 9)
            x[6] = x[6] xor rot(x[2] + x[14], 13); x[10] = x[10] xor rot(x[6] + x[2], 18)
            x[3] = x[3] xor rot(x[15] + x[11], 7); x[7] = x[7] xor rot(x[3] + x[15], 9)
            x[11] = x[11] xor rot(x[7] + x[3], 13); x[15] = x[15] xor rot(x[11] + x[7], 18)
            x[1] = x[1] xor rot(x[0] + x[3], 7); x[2] = x[2] xor rot(x[1] + x[0], 9)
            x[3] = x[3] xor rot(x[2] + x[1], 13); x[0] = x[0] xor rot(x[3] + x[2], 18)
            x[6] = x[6] xor rot(x[5] + x[4], 7); x[7] = x[7] xor rot(x[6] + x[5], 9)
            x[4] = x[4] xor rot(x[7] + x[6], 13); x[5] = x[5] xor rot(x[4] + x[7], 18)
            x[11] = x[11] xor rot(x[10] + x[9], 7); x[8] = x[8] xor rot(x[11] + x[10], 9)
            x[9] = x[9] xor rot(x[8] + x[11], 13); x[10] = x[10] xor rot(x[9] + x[8], 18)
            x[12] = x[12] xor rot(x[15] + x[14], 7); x[13] = x[13] xor rot(x[12] + x[15], 9)
            x[14] = x[14] xor rot(x[13] + x[12], 13); x[15] = x[15] xor rot(x[14] + x[13], 18)
        }
        for (i in 0 until 16) b[i] += x[i]
    }

    private fun le32(a: ByteArray, o: Int) =
        (a[o].toInt() and 0xff) or ((a[o + 1].toInt() and 0xff) shl 8) or ((a[o + 2].toInt() and 0xff) shl 16) or ((a[o + 3].toInt() and 0xff) shl 24)

    private fun putLe32(a: ByteArray, o: Int, v: Int) {
        a[o] = v.toByte(); a[o + 1] = (v ushr 8).toByte(); a[o + 2] = (v ushr 16).toByte(); a[o + 3] = (v ushr 24).toByte()
    }
}
