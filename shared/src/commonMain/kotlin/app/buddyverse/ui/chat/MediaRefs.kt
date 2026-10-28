package app.buddyverse.ui.chat

/** A reply split around Hermes's `MEDIA:/absolute/path` file tags: the prose without them, and each path once, in order. */
data class MediaSplit(val text: String, val paths: List<String>)

// Mirrors gateway/platforms/base.py MEDIA_TAG_CLEANUP_RE: optional quote/emphasis wrap, a quoted path or an absolute one
// (`/`, `~/`, `C:\`) ending in an extension; non-greedy so `a.pdf (2 MB)` stops at `.pdf` and `x.tar.gz` runs to `.gz`.
private val MEDIA = Regex(
    """[`"'*_]{0,3}MEDIA:\s*(?:`([^`\n]+)`|"([^"\n]+)"|'([^'\n]+)'|((?:~/|/|[A-Za-z]:[/\\])[^\n]*?\.[A-Za-z0-9]{1,10}(?=[\s`"'*_,;:)\]]|MEDIA:|\.(?:\s|$)|$)))[`"'*_]{0,3}\.?""",
)

/** Pulls the file tags out of [text] (not inside ``` fences); a line left empty by it is dropped. */
fun splitMedia(text: String): MediaSplit {
    if ("MEDIA:" !in text) return MediaSplit(text, emptyList())
    val paths = mutableListOf<String>()
    var fence = false
    val kept = text.lines().mapNotNull { line ->
        if (line.trimStart().startsWith("```")) { fence = !fence; return@mapNotNull line }
        if (fence || "MEDIA:" !in line) return@mapNotNull line
        val rest = MEDIA.replace(line) { m ->
            m.groupValues.drop(1).first { it.isNotEmpty() }.trim().let { if (it !in paths) paths += it }
            ""
        }
        if (rest != line && rest.isBlank()) null else rest.trimEnd()
    }
    return MediaSplit(kept.joinToString("\n").trim(), paths)
}

/** The last segment of a Windows or POSIX path. */
fun fileNameOf(path: String): String = path.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/')
