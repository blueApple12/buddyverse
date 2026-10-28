package app.buddyverse.core.api

import app.buddyverse.core.Hermes
import app.buddyverse.core.RestError
import app.buddyverse.core.child
import app.buddyverse.core.list
import app.buddyverse.core.long
import app.buddyverse.core.obj
import app.buddyverse.core.platformHttpClient
import app.buddyverse.core.str
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.contentLength
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlin.io.encoding.Base64

// Owner: More ▸ Artifacts (ui/more/ArtifactsPage.kt, ui/more/artifacts). hermes-api.md §2.6 Artifacts: there is no artifact
// backend; the app scans recent transcripts itself (ui/more/artifacts/ArtifactScan.kt), like the desktop's artifact-utils.ts.

private fun q(v: String) = v.encodeURLParameter()

/** Desktop pages transcripts 100 rows at a time while scanning. */
const val ARTIFACT_PAGE = 100

/** `GET /api/profiles/sessions`: the most recent chats of every bot (archived left out), as the desktop's Artifacts page reads them. */
suspend fun Hermes.artifactSessions(limit: Int = 30): List<JsonObject> =
    rest.get("/api/profiles/sessions?limit=$limit&offset=0&min_messages=1&archived=exclude&order=recent&profile=all").obj
        .list("sessions").mapNotNull { it.obj }

/** One transcript page, oldest first. [pageLimit] is null on an engine that ignores paging (it sent everything). */
class TranscriptPage(val messages: List<JsonObject>, val pageLimit: Int?)

/**
 * `GET /api/sessions/{id}/messages`, oldest first, compacted history included. `inline_images=false` turns data-URI images
 * into `[image]`, which keeps a page small on the phone. ponytail: data-URI images in replies are not listed; drop the flag if wanted.
 */
suspend fun Hermes.transcriptPage(sessionId: String, profile: String, offset: Int, limit: Int = ARTIFACT_PAGE): TranscriptPage {
    val o = rest.get("/api/sessions/${q(sessionId)}/messages?profile=${q(profile)}&limit=$limit&offset=$offset&order=oldest" +
        "&include_compacted=true&inline_images=false").obj
    return TranscriptPage(o.list("messages").mapNotNull { it.obj }, o.child("pagination").long("limit")?.toInt())
}

/** `data:<mime>;base64,<…>` → bytes (null when it isn't base64). */
fun dataUrlBytes(url: String): ByteArray? =
    if (!url.startsWith("data:") || "base64," !in url) null else runCatching { Base64.decode(url.substringAfter("base64,")) }.getOrNull()

/** `GET /api/media/proxy?url=`: the PC fetches an image from its allowlisted generators (fal, Google storage), ≤ 25 MiB. */
suspend fun Hermes.proxiedImage(url: String): ByteArray? {
    val o = rest.get("/api/media/proxy?url=${q(url)}").obj
    return (o.str("data_url") ?: o.str("dataUrl"))?.let(::dataUrlBytes)
}

private val webClient by lazy { platformHttpClient().config { followRedirects = true } }

/** A plain web image, fetched by the phone itself (no PC credentials go with it). */
suspend fun webImage(url: String, maxBytes: Long = 25L shl 20): ByteArray = withTimeout(20_000) {
    val r = webClient.get(url)
    if (r.status.value !in 200..299) throw RestError(r.status.value, "image")
    if ((r.contentLength() ?: 0L) > maxBytes) throw RestError(413, "image")
    r.bodyAsBytes()
}

/** `file:///C:/out/a.png` → `C:\out\a.png`, `file:///home/u/a.png` → `/home/u/a.png`; anything else unchanged. */
fun artifactFilePath(value: String): String {
    if (!value.startsWith("file://", ignoreCase = true)) return value
    val raw = value.substring(7).removePrefix("localhost")
    val p = runCatching { raw.decodeURLPart() }.getOrDefault(raw)
    return if (Regex("""^/[A-Za-z]:/""").containsMatchIn(p)) p.drop(1).replace('/', '\\') else p
}

/** An image an artifact points at: a data URL, a file on the PC, or a web address (the PC's proxy first, then directly). */
suspend fun Hermes.artifactImageBytes(value: String, profile: String?): ByteArray = when {
    value.startsWith("data:") -> dataUrlBytes(value) ?: throw RestError(415, "image")
    value.startsWith("http://") || value.startsWith("https://") ->
        runCatching { proxiedImage(value) }.getOrNull() ?: webImage(value)
    else -> readBytes(artifactFilePath(value), profile)
}
