package app.buddyverse.ui.more.artifacts

import androidx.compose.runtime.Immutable
import app.buddyverse.core.bool
import app.buddyverse.core.child
import app.buddyverse.core.double
import app.buddyverse.core.str
import io.ktor.http.decodeURLPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// A port of the desktop's transcript scanner (apps/desktop/src/app/artifacts/artifact-utils.ts, lib/chat-messages/parts.ts
// `mediaTagValues`, lib/artifact-detect.ts), checked against that source 2026-10-26. Pure: no network, no Compose state.

enum class ArtifactKind { Image, File, Link, Made }

/** What a [ArtifactKind.Made] artifact is: a page, a drawing, or a long piece of code a bot wrote into its reply. */
enum class MadeKind { Html, Svg, Code }

/**
 * Something a bot produced. [value] is the path or URL (for [ArtifactKind.Made], the content itself). [id] is
 * `session:value` (`session:made:slug` for made ones), so the same thing in one chat is listed once.
 */
@Immutable
data class Artifact(
    val id: String,
    val kind: ArtifactKind,
    val value: String,
    val label: String,
    val sessionId: String,
    val profile: String,
    val sessionTitle: String,
    val timestampMs: Long,
    val made: MadeKind? = null,
    val language: String = "",
)

/** One chat to scan (a `/api/profiles/sessions` row). [title] is blank when the chat has neither title nor preview. */
@Immutable
data class ScanSession(val id: String, val profile: String, val title: String, val lastActiveMs: Long?, val startedMs: Long?) {
    companion object {
        fun from(o: JsonObject): ScanSession? = ScanSession(
            id = o.str("id") ?: return null,
            profile = o.str("profile") ?: "default",
            title = o.str("title")?.trim()?.takeIf { it.isNotEmpty() } ?: o.str("preview")?.trim().orEmpty(),
            lastActiveMs = millis(o.double("last_active")),
            startedMs = millis(o.double("started_at")),
        )
    }
}

/** Unix seconds (or already milliseconds above 1e10) → milliseconds; null when unset. */
private fun millis(t: Double?): Long? = t?.takeIf { it > 0 && it.isFinite() }?.let { if (it < 10_000_000_000.0) (it * 1000).toLong() else it.toLong() }

// ---- patterns (desktop regexes, same flags)

private val MD_IMAGE = Regex("""!\[([^\]]*)\]\(([^)\s]+)\)""")
private val MD_LINK = Regex("""\[([^\]]+)\]\(([^)\s]+)\)""")
private val URL = Regex("""https?://[^\s<>"')`]+""")
private val PATH = Regex("""(^|[\s("'`])((?:/|~[\\/]|\.\.?[\\/]|\\\\)[^\s"'`<>]+(?:\.[a-z0-9]{1,8})?)""", RegexOption.IGNORE_CASE)
private val WIN_PATH = Regex("""(^|[\s("'`])([A-Za-z]:[\\/][^\s"'`<>]+(?:\.[a-z0-9]{1,8})?)""", RegexOption.IGNORE_CASE)
private val IMAGE_EXT = Regex("""\.(?:png|jpe?g|gif|webp|svg|bmp)(?:\?.*)?$""", RegexOption.IGNORE_CASE)
private val FILE_EXT = Regex(
    """\.(?:png|jpe?g|gif|webp|svg|bmp|pdf|txt|json|md|csv|xlsx?|docx?|pptx?|html|zip|tar|gz|avi|flac|m4a|mkv|mp3|ogg|opus|wav|webm|mp4|mov)(?:\?.*)?$""",
    RegexOption.IGNORE_CASE,
)
private val FILE_PATH = Regex("""^(?:file:|/|[~.][\\/]|\.\.[\\/]|[a-z]:[\\/]|\\\\)""", RegexOption.IGNORE_CASE)
private val PRODUCER_TOOL = Regex("""(?:^|_)(?:creat(?:e|ion)|download|export|generat(?:e|ion)|render|save|speech|tts|write)(?:_|$)""", RegexOption.IGNORE_CASE)
private val STRONG_KEY = Regex(
    """^(?:artifact_(?:file|image|path|url)|files?_(?:created|modified|written)|generated_(?:file|image|path|url)|media_tag|output_(?:file|path|url)|result_(?:file|path|url)|saved_to|screenshot_path)$""",
    RegexOption.IGNORE_CASE,
)
private val PRODUCER_KEY = Regex(
    """^(?:artifact(?:s|_(?:file|image|path|url))?|attachment(?:s|_(?:file|image|path|url))?|download(?:s|_(?:file|path|url))?|(?:audio|image|video)(?:_(?:file|path|url))?|file_path|local_path|media(?:_(?:file|path|url))?|path)$""",
    RegexOption.IGNORE_CASE,
)
private val SHELL_KEY = Regex("""^(?:output|stdout|path)$""", RegexOption.IGNORE_CASE)
private val SCREENSHOT = Regex("""Screenshot path:\s*([^\r\n<>]+)""", RegexOption.IGNORE_CASE)
private val PIP_CACHE = Regex("""/(?:\.cache/pip|library/caches/pip|appdata/local/pip/cache)/""")
private val WHEEL = Regex("""\.whl(?:\.metadata)?$""")
private val INDEX_DIST = Regex("""/packages/.+\.(?:tar\.gz|tar\.bz2|zip|egg)(?:\.metadata)?$""")
private val UNTRUSTED_OPEN = Regex("""^<untrusted_tool_result\b[^>]*>\s*""")
private val URL_SCHEME = Regex("""^(?:https?|file)://""", RegexOption.IGNORE_CASE)
private val TRAIL_MARKS = Regex("""[`*]+$""")
private val TRAIL_PUNCT = Regex("""[),.;]+$""")
private val TRAIL_QUOTES = Regex("""[`"'*_]{1,3}$""")
private val DOT_EXT = Regex("""\.[^.]""")

/** `MEDIA_DELIVERY_EXTS`, longest first so a shorter one never wins as a prefix. */
private val MEDIA_EXTS = listOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "tiff", "svg", "mp4", "mov", "avi", "mkv", "webm", "3gp", "mp3", "m2a", "wav", "ogg", "opus",
    "m4a", "flac", "pdf", "docx", "doc", "odt", "rtf", "txt", "md", "epub", "xlsx", "xls", "ods", "csv", "tsv", "json", "xml", "yaml", "yml",
    "kmz", "kml", "geojson", "gpx", "pptx", "ppt", "odp", "key", "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "apk", "ipa", "html", "htm",
).sortedByDescending { it.length }.joinToString("|")
private val MEDIA_ANCHORED = """(?:~/|/|[A-Za-z]:[/\\])\S+?(?:[^\S\n]+\S+?)*?\.(?:$MEDIA_EXTS)(?=[\s`"'*_,;:)\]}]|MEDIA:|$)"""
private const val MEDIA_BARE = """[^\s`"]+"""
private val MEDIA_TAG = Regex("""[`"']?MEDIA:\s*(`[^`\n]+`|"[^"\n]+"|'[^'\n]+'|$MEDIA_ANCHORED|$MEDIA_BARE)[`"']?""")
private const val QUOTES = "\"'`"

// ---- value rules

private fun isFilePath(v: String) = FILE_PATH.containsMatchIn(v)
private fun looksLikePathOrUrl(v: String) = v.startsWith("http://") || v.startsWith("https://") || v.startsWith("data:image/") || isFilePath(v)

/** The path part, lowercased, for the pip-noise check. */
private fun pathForFiltering(v: String): String {
    if (URL_SCHEME.containsMatchIn(v)) {
        val rest = v.substringAfter("://")
        val path = "/" + rest.substringAfter('/', "").substringBefore('?').substringBefore('#')
        return runCatching { path.decodeURLPart() }.getOrDefault(path).lowercase()
    }
    return v.replace('\\', '/').lowercase()
}

/** pip's own downloads and cache, not something a bot made (desktop #52972). */
private fun isPipNoise(v: String): Boolean {
    val p = pathForFiltering(v)
    return PIP_CACHE.containsMatchIn(p) || WHEEL.containsMatchIn(p) || (v.startsWith("http") && INDEX_DIST.containsMatchIn(p))
}

internal fun looksLikeArtifact(v: String, explicit: Boolean = false): Boolean {
    if (!explicit && isPipNoise(v)) return false
    if (v.startsWith("http://") || v.startsWith("https://") || v.startsWith("data:image/")) return true
    if (!looksLikePathOrUrl(v)) return false
    if (explicit) return true
    return IMAGE_EXT.containsMatchIn(v) || FILE_EXT.containsMatchIn(v)
}

internal fun kindOf(v: String): ArtifactKind = when {
    v.startsWith("data:image/") || IMAGE_EXT.containsMatchIn(v) -> ArtifactKind.Image
    isFilePath(v) -> ArtifactKind.File
    else -> ArtifactKind.Link
}

/** The last path segment of a URL or path; the whole value when there is none. */
internal fun labelOf(v: String): String {
    if ("://" in v) {
        val path = v.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
        val last = path.split('/').lastOrNull { it.isNotEmpty() } ?: return v
        return runCatching { last.decodeURLPart() }.getOrDefault(last)
    }
    if (v.startsWith("data:")) return v.substringBefore(';').substringAfter("data:")
    return v.split('/', '\\').lastOrNull { it.isNotEmpty() } ?: v
}

private fun stripQuotesOnce(t: String): String? =
    if (t.isNotEmpty() && t[0] in QUOTES && t[0] == t[t.length - 1]) t.substring(1, maxOf(1, t.length - 1)) else null

internal fun normalizeValue(value: String): String {
    var t = value.trim()
    repeat(3) { t = stripQuotesOnce(t)?.trim() ?: return@repeat }
    return t.replace(TRAIL_MARKS, "").replace(TRAIL_PUNCT, "")
}

/** Chat renders file refs as `[label](#media:<encoded path>)`. */
private fun decodeMediaHref(v: String): String =
    if (v.startsWith("#media:")) runCatching { v.removePrefix("#media:").decodeURLPart() }.getOrDefault(v) else v

private fun unquoteMediaValue(v: String): String {
    val t = v.trim()
    return stripQuotesOnce(t) ?: t.replace(TRAIL_QUOTES, "")
}

private fun isPlausibleMediaPath(v: String) = '/' in v || '\\' in v || DOT_EXT.containsMatchIn(v)

private fun unquoteMediaPath(v: String): String {
    val t = v.trim()
    stripQuotesOnce(t)?.let { return it }
    return if (t.endsWith('`') || t.endsWith('"')) t.dropLast(1) else t
}

/** Raw `MEDIA:` values; bare ones shed trailing sentence punctuation, non-paths are dropped. */
internal fun mediaTagValues(text: String): List<String> = MEDIA_TAG.findAll(text).mapNotNull { m ->
    val value = m.groupValues[1]
    val path = unquoteMediaPath(value)
    var end = path.length
    while (end > 0 && path[end - 1] in ".,;:!?" && isPlausibleMediaPath(path.substring(0, end - 1))) end--
    val punct = path.length - end
    if (!isPlausibleMediaPath(path.substring(0, end))) null else if (punct > 0) value.dropLast(punct) else value
}.toList()

// ---- made artifacts (fenced blocks worth their own card)

private val FENCE = Regex("""(?m)^[ \t]*(`{3,})([^\n`]*)\n([\s\S]*?)\n[ \t]*\1[ \t]*$""")
private val HTML_DOC = Regex("""<!doctype\s+html|<html[\s>]|<head[\s>]|<body[\s>]""", RegexOption.IGNORE_CASE)
private val HTML_TAG = Regex("""<[a-z][a-z0-9-]*(\s[^>]*)?>""", RegexOption.IGNORE_CASE)
private val SVG_TAG = Regex("""<svg[\s>]""", RegexOption.IGNORE_CASE)
private val LANG = Regex("""^[a-z0-9][a-z0-9+#-]*$""", RegexOption.IGNORE_CASE)
private val DECLARATION = Regex("""(?:^|\n)\s*(?:export\s+)?(?:default\s+)?(?:async\s+)?(?:function|class|struct|interface|enum|trait|impl|def|fn)\s+([A-Za-z_$][\w$]*)""")
private val FILENAME_COMMENT = Regex("""^\s*(?://|#|--|<!--|/\*)\s*([\w./-]+\.[a-z0-9]{1,8})\b""", RegexOption.IGNORE_CASE)
private val NOT_MADE = setOf(
    "", "console", "diff", "listing", "log", "logs", "markdown", "md", "mermaid", "output", "patch", "plain", "plaintext", "shell-session",
    "stdout", "text", "txt",
)

private fun sanitizeLanguage(tag: String): String {
    val first = tag.trim().split(Regex("\\s")).firstOrNull().orEmpty()
    return if (LANG.matches(first) && first.length <= 16) first.lowercase() else ""
}

private fun titleFromTag(content: String, tag: String): String? =
    Regex("""<$tag[^>]*>([\s\S]*?)</$tag>""", RegexOption.IGNORE_CASE).find(content)?.groupValues?.get(1)
        ?.replace(Regex("<[^>]*>"), " ")?.replace(Regex("\\s+"), " ")?.trim()?.take(80)?.takeIf { it.isNotEmpty() }

/** A fenced block that is a whole page (html), a big drawing (svg) or long code (≥ 48 lines or 3000 chars); else null. */
internal fun detectMade(language: String, code: String): Triple<MadeKind, String, String>? {
    val t = code.trim()
    if (t.isEmpty()) return null
    val lang = sanitizeLanguage(language)
    if (lang == "html" || lang == "htm" || lang == "xhtml") {
        val doc = HTML_DOC.containsMatchIn(t)
        if ((doc && t.length >= 160) || (!doc && t.length >= 1200 && HTML_TAG.containsMatchIn(t))) {
            return Triple(MadeKind.Html, lang, titleFromTag(t, "title") ?: titleFromTag(t, "h1") ?: "HTML")
        }
        return null
    }
    if (lang == "svg") return if (t.length >= 2000 && SVG_TAG.containsMatchIn(t)) Triple(MadeKind.Svg, lang, titleFromTag(t, "title") ?: "SVG") else null
    if (lang in NOT_MADE) return null
    // ponytail: the desktop's "looks like prose" veto is skipped; a long prose fence tagged as code still lists.
    if (t.length < 3000 && t.count { it == '\n' } + 1 < 48) return null
    val head = t.take(2000)
    val title = FILENAME_COMMENT.find(head)?.groupValues?.get(1) ?: DECLARATION.find(head)?.groupValues?.get(1) ?: lang
    return Triple(MadeKind.Code, lang, title)
}

private fun slug(title: String) = title.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), "-").trim('-').take(48).ifEmpty { "untitled" }

private val EXT_BY_LANGUAGE = mapOf(
    "bash" to "sh", "c" to "c", "cpp" to "cpp", "csharp" to "cs", "css" to "css", "go" to "go", "htm" to "html", "html" to "html",
    "java" to "java", "javascript" to "js", "js" to "js", "json" to "json", "jsx" to "jsx", "kotlin" to "kt", "kt" to "kt", "php" to "php",
    "py" to "py", "python" to "py", "rb" to "rb", "rs" to "rs", "ruby" to "rb", "rust" to "rs", "sh" to "sh", "sql" to "sql", "svg" to "svg",
    "swift" to "swift", "toml" to "toml", "ts" to "ts", "tsx" to "tsx", "typescript" to "ts", "xhtml" to "html", "xml" to "xml",
    "yaml" to "yaml", "yml" to "yaml", "ps1" to "ps1", "powershell" to "ps1",
)

/** File name to save a made artifact under: its title when that is already a file name, else `title.<ext>`. */
fun Artifact.fileName(): String {
    val ext = when (made) {
        MadeKind.Html -> "html"
        MadeKind.Svg -> "svg"
        else -> EXT_BY_LANGUAGE[language] ?: "txt"
    }
    if (Regex("""^[\w.-]+\.[a-z0-9]{1,8}$""", RegexOption.IGNORE_CASE).matches(label)) return label
    return "${slug(label).take(40)}.$ext"
}

fun Artifact.mime(): String = when (made) {
    MadeKind.Html -> "text/html"
    MadeKind.Svg -> "image/svg+xml"
    else -> "text/plain"
}

// ---- the scan

private val strictJson = Json

private fun messageText(m: JsonObject): String {
    (m["content"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.let { return it.content }
    m.str("text")?.takeIf { it.isNotBlank() }?.let { return it }
    m.str("context")?.takeIf { it.isNotBlank() }?.let { return it }
    return ""
}

private fun parseJson(text: String): JsonElement? {
    val t = text.trim()
    if (t.isEmpty() || (t[0] != '{' && t[0] != '[')) return null
    return runCatching { strictJson.parseToJsonElement(t) }.getOrNull()
}

/** A tool result wrapped as `<untrusted_tool_result …>header\n\npayload</untrusted_tool_result>` → the payload. */
private fun untrustedPayload(text: String): String? {
    val t = text.trim()
    val open = UNTRUSTED_OPEN.find(t) ?: return null
    val close = t.lastIndexOf("</untrusted_tool_result>")
    if (close <= open.range.last + 1) return null
    val wrapped = t.substring(open.range.last + 1, close).trim()
    val start = wrapped.indexOf("\n\n")
    return (if (start == -1) wrapped else wrapped.substring(start + 2)).trim()
}

private fun walkStrings(e: JsonElement, path: List<String>, out: (String, List<String>) -> Unit) {
    when (e) {
        is JsonPrimitive -> if (e.isString) out(e.content, path)
        is JsonArray -> e.forEach { walkStrings(it, path, out) } // array indices never count as key segments
        is JsonObject -> e.forEach { (k, v) -> walkStrings(v, path + k, out) }
    }
}

private class Collector(val push: (String, Boolean) -> Unit) {
    fun media(text: String) = mediaTagValues(text).forEach { push(unquoteMediaValue(it), true) }

    fun text(text: String) {
        media(text)
        MD_IMAGE.findAll(text).forEach { push(it.groupValues[2], false) }
        MD_LINK.findAll(text).forEach { m ->
            if (m.range.first > 0 && text[m.range.first - 1] == '!') return@forEach
            val v = decodeMediaHref(m.groupValues[2])
            if (looksLikeArtifact(v)) push(v, false)
        }
        URL.findAll(text).forEach { if (looksLikeArtifact(it.value)) push(it.value, false) }
        PATH.findAll(text).forEach { push(it.groupValues[2], false) }
        WIN_PATH.findAll(text).forEach { push(it.groupValues[2], false) }
    }
}

/**
 * Adds what [messages] (one transcript page) produced to [into], keyed by artifact id (first sighting wins; a made artifact
 * the bot rewrites keeps its newest version). Only assistant and tool rows count, like the desktop.
 */
fun scanPage(s: ScanSession, messages: List<JsonObject>, into: LinkedHashMap<String, Artifact>, nowMs: Long) {
    for (m in messages) {
        val role = m.str("role")
        if (role != "assistant" && role != "tool") continue
        val at = millis(m.double("timestamp")) ?: s.lastActiveMs ?: s.startedMs ?: nowMs
        val c = Collector { candidate, explicit ->
            val v = normalizeValue(decodeMediaHref(candidate))
            if (v.isEmpty() || !looksLikeArtifact(v, explicit)) return@Collector
            val id = "${s.id}:$v"
            if (id !in into) into[id] = Artifact(id, kindOf(v), v, labelOf(v), s.id, s.profile, s.title, at)
        }
        val text = messageText(m)
        if (role == "assistant") {
            if (text.isEmpty()) continue
            c.text(text)
            FENCE.findAll(text).forEach { f ->
                val (kind, lang, title) = detectMade(f.groupValues[2], f.groupValues[3]) ?: return@forEach
                val id = "${s.id}:made:${kind.name.lowercase()}:$lang:${slug(title)}"
                into.remove(id)
                into[id] = Artifact(id, ArtifactKind.Made, f.groupValues[3].trim(), title, s.id, s.profile, s.title, at, kind, lang)
            }
            continue
        }
        val name = (m.str("tool_name") ?: m.str("name")).orEmpty().trim().lowercase()
        val producer = PRODUCER_TOOL.containsMatchIn(name) || name.startsWith("bfl_flux3_")
        val terminal = name == "terminal"
        if (text.isNotEmpty() && (producer || terminal)) c.text(text)
        if (name == "browser_vision") SCREENSHOT.findAll(text).forEach { c.push(it.groupValues[1], false) }
        val payloads = listOfNotNull(parseJson(text), untrustedPayload(text)?.let(::parseJson)) + listOfNotNull(
            when (val content = m["content"]) {
                is JsonObject -> if (content.bool("_multimodal") == true) content.child("meta") else content
                is JsonArray -> content
                else -> null
            },
        )
        for (p in payloads) walkStrings(p, listOf("tool_result")) { value, path ->
            if (terminal && path.any { SHELL_KEY.matches(it) }) {
                // a shell result is free text: scan it like prose
                if (value.isNotEmpty()) c.text(value)
            } else if (path.any { STRONG_KEY.matches(it) || (producer && PRODUCER_KEY.matches(it)) }) {
                c.media(value)
                val v = normalizeValue(decodeMediaHref(value))
                if (v.isNotEmpty() && looksLikeArtifact(v)) c.push(v, false)
            }
        }
    }
}
