package app.buddyverse.core.api

import app.buddyverse.core.Hermes
import app.buddyverse.core.RestError
import app.buddyverse.core.SignedOut
import app.buddyverse.core.TokenSource
import app.buddyverse.core.WireJson
import app.buddyverse.core.bool
import app.buddyverse.core.double
import app.buddyverse.core.list
import app.buddyverse.core.long
import app.buddyverse.core.obj
import app.buddyverse.core.params
import app.buddyverse.core.platformHttpClient
import app.buddyverse.core.str
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.encodeURLParameter
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64

// Owner: More ▸ Files (ui/more/FilesPage.kt, ui/more/files). hermes-api.md §2.6 Files tree + Managed files, uploads, downloads, file tickets.
// Verified live 2026-10-26: listing/read/write shapes below; ticket-only download URLs answer 401 `no_cookie` on this gated host.

private fun e(v: String) = v.encodeURLParameter()
private fun amp(profile: String?) = if (profile.isNullOrEmpty()) "" else "&profile=${e(profile)}"
private fun qp(profile: String?) = if (profile.isNullOrEmpty()) "" else "?profile=${e(profile)}"

/** One row of a folder (`ManagedEntry`). [size] is null for folders and broken links; [mtime] is epoch seconds. */
data class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long? = null,
    val mtime: Double? = null,
    val mime: String? = null,
    val broken: Boolean = false,
)

/** `GET /api/files` result. [parent] is null at a drive / filesystem root or the locked root. */
data class FolderListing(val path: String, val parent: String?, val entries: List<FileEntry>, val lockedRoot: String?, val canChangePath: Boolean)

/** `GET /api/fs/read-text` result: preview capped at 512 KiB ([truncated]); [binary] = not text. */
data class TextFile(val path: String, val text: String, val binary: Boolean, val truncated: Boolean, val byteSize: Long, val language: String, val mime: String)

private fun JsonObject.toEntry(): FileEntry? {
    val p = str("path") ?: return null
    return FileEntry(
        name = str("name") ?: p.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/'),
        path = p,
        isDir = bool("is_directory") == true,
        size = long("size"),
        mtime = double("mtime"),
        mime = str("mime_type"),
        broken = bool("broken_link") == true,
    )
}

/** `GET /api/fs/default-cwd`: the bot's workspace folder (where its chats start). */
suspend fun Hermes.workspaceRoot(profile: String): String? = rest.get("/api/fs/default-cwd${qp(profile)}").obj.str("cwd")?.takeIf { it.isNotBlank() }

/** `GET /api/files?path=`: dirs first, then files, by name. Null [path] = the managed root (home when unlocked). 400 relative, 404 missing. */
suspend fun Hermes.listFolder(path: String?): FolderListing {
    val o = rest.get("/api/files" + (path?.let { "?path=${e(it)}" } ?: "")).obj
    return FolderListing(
        path = o.str("path") ?: path.orEmpty(),
        parent = o.str("parent"),
        entries = o.list("entries").mapNotNull { it.obj?.toEntry() },
        lockedRoot = o.str("locked_root"),
        canChangePath = o.bool("can_change_path") ?: true,
    )
}

/** `GET /api/fs/read-text`. 413 when the file is over 64 MiB; sensitive files answer 403. */
suspend fun Hermes.readText(path: String, profile: String?): TextFile {
    val o = rest.get("/api/fs/read-text?path=${e(path)}${amp(profile)}").obj
    return TextFile(
        path = o.str("path") ?: path,
        text = o.str("text").orEmpty(),
        binary = o.bool("binary") == true,
        truncated = o.bool("truncated") == true,
        byteSize = o.long("byteSize") ?: 0,
        language = o.str("language").orEmpty(),
        mime = o.str("mimeType").orEmpty(),
    )
}

/** `POST /api/fs/write-text`: atomic save of a regular file, ≤ 8 MiB, parent must exist. Returns the new byte size. */
suspend fun Hermes.writeText(path: String, content: String, profile: String?): Long? =
    rest.post("/api/fs/write-text${qp(profile)}", params("path" to path, "content" to content)).obj.long("byteSize")

/** `GET /api/fs/read-data-url` decoded to bytes (≤ 16 MiB, 413 above): images for the viewer. */
suspend fun Hermes.readBytes(path: String, profile: String?): ByteArray {
    val url = rest.get("/api/fs/read-data-url?path=${e(path)}${amp(profile)}").obj.str("dataUrl") ?: throw RestError(500, "no data")
    return Base64.decode(url.substringAfter("base64,", ""))
}

/** `POST /api/files/mkdir` (makes parents too; an existing folder is fine, an existing file is 409). */
suspend fun Hermes.makeFolder(path: String): FileEntry? = rest.post("/api/files/mkdir", params("path" to path)).obj?.let { (it["entry"] as? JsonObject)?.toEntry() }

/** `POST /api/files/upload`: writes [bytes] at the full file [path]. 409 when it exists and ![overwrite]; 413 over 100 MiB. */
suspend fun Hermes.putFile(path: String, mime: String, bytes: ByteArray, overwrite: Boolean): FileEntry? =
    rest.post("/api/files/upload", params("path" to path, "data_url" to "data:$mime;base64,${Base64.encode(bytes)}", "overwrite" to overwrite))
        .obj?.let { (it["entry"] as? JsonObject)?.toEntry() }

/** `DELETE /api/files`: a folder needs [recursive] unless empty (else 409). Refuses the locked root and a filesystem root. */
suspend fun Hermes.deletePath(path: String, recursive: Boolean) {
    rest.delete("/api/files", params("path" to path, "recursive" to recursive))
}

/**
 * `POST /api/files/ticket` → a URL ticket for `GET /api/fs/download` ([route] `download`, single use, 60 s) or `/api/files/stream`
 * (`stream`, reusable, 1 h). Only for a player or download manager that can't send headers, and only where the host's gate
 * admits ticket-only requests (the loopback gate). This gated host refuses them, so the app downloads with [downloadFile].
 */
suspend fun Hermes.fileTicket(route: String, path: String, profile: String? = null): String? =
    rest.post("/api/files/ticket", params("route" to route, "path" to path, "profile" to profile)).obj.str("ticket")

private val downloadClient by lazy { platformHttpClient() }

/** `HEAD /api/fs/download`: the file's byte size (null when no length is sent). Non-2xx throws [RestError] (404 = gone). */
suspend fun TokenSource.fileSize(path: String, profile: String?): Long? {
    for (force in listOf(false, true)) {
        val token = accessToken(force) ?: throw SignedOut()
        val r = downloadClient.head("$base/api/fs/download?path=${e(path)}${amp(profile)}") { header(HttpHeaders.Authorization, "Bearer $token") }
        if (r.status.value == 401 && !force) continue
        if (r.status.value !in 200..299) throw RestError(r.status.value, r.status.description)
        return r.contentLength()
    }
    throw SignedOut()
}

/**
 * `GET /api/fs/download` streamed with the bearer header (one forced token refresh on 401, like [app.buddyverse.core.Rest]).
 * [onChunk] gets each chunk (the buffer is reused); [onProgress] 0..1 when the server sends a length. Refuses folders and live databases.
 */
suspend fun TokenSource.downloadFile(path: String, profile: String?, onChunk: (ByteArray, Int) -> Unit, onProgress: (Float) -> Unit) {
    for (force in listOf(false, true)) {
        val token = accessToken(force) ?: throw SignedOut()
        val done = downloadClient.prepareGet("$base/api/fs/download?path=${e(path)}${amp(profile)}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.execute { r ->
            if (r.status.value == 401 && !force) return@execute false
            if (r.status.value !in 200..299) {
                val text = r.bodyAsText()
                throw RestError(r.status.value, runCatching { WireJson.parseToJsonElement(text).obj.str("detail") }.getOrNull() ?: text.take(200))
            }
            val total = r.contentLength() ?: -1L
            val ch = r.bodyAsChannel()
            val buf = ByteArray(64 * 1024)
            var got = 0L
            var shown = -1
            while (true) {
                val n = ch.readAvailable(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                onChunk(buf, n)
                got += n
                if (total > 0) {
                    val pct = (got * 100 / total).toInt()
                    if (pct != shown) { shown = pct; onProgress(pct / 100f) }
                }
            }
            true
        }
        if (done) return
    }
    throw SignedOut()
}
