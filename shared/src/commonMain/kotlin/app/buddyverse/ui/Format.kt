package app.buddyverse.ui

import app.buddyverse.i18n.Strings
/** Short relative time for rows: "now", "5m", "3h", "2d"; Hebrew units in Hebrew (Western digits, design §9.6). */
fun ago(epochSeconds: Double, nowMs: Long, s: Strings): String {
    if (epochSeconds <= 0) return ""
    val d = (nowMs / 1000.0 - epochSeconds).coerceAtLeast(0.0)
    return when {
        d < 60 -> s.justNow
        d < 3600 -> s.unit((d / 60).toLong(), "m")
        d < 86_400 -> s.unit((d / 3600).toLong(), "h")
        d < 86_400 * 30 -> s.unit((d / 86_400).toLong(), "d")
        else -> s.unit((d / (86_400 * 30)).toLong(), "mo")
    }
}

/** "in 9h", "in 3m" for a future ISO time; null when unparseable. */
fun untilIso(iso: String?, nowMs: Long, s: Strings): String? {
    val t = parseIsoMillis(iso ?: return null) ?: return null
    val d = (t - nowMs) / 1000
    if (d <= 0) return null
    return when {
        d < 3600 -> s.unit(d / 60 + 1, "m")
        d < 86_400 -> s.unit(d / 3600, "h") + " " + s.unit((d % 3600) / 60, "m")
        else -> s.unit(d / 86_400, "d")
    }
}

/** Minimal ISO-8601 with offset → epoch millis (no java.time in commonMain). */
fun parseIsoMillis(iso: String): Long? {
    val m = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|[+-]\d{2}:?\d{2})?""").find(iso) ?: return null
    val (y, mo, d, h, mi, se) = m.destructured
    val days = daysFromCivil(y.toInt(), mo.toInt(), d.toInt())
    var secs = days * 86_400L + h.toInt() * 3600L + mi.toInt() * 60L + se.toInt()
    val off = m.groupValues[7]
    if (off.isNotEmpty() && off != "Z") {
        val sign = if (off[0] == '-') -1 else 1
        val digits = off.drop(1).replace(":", "")
        secs -= sign * (digits.take(2).toInt() * 3600L + digits.drop(2).toInt() * 60L)
    }
    return secs * 1000
}

private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
    val y = if (m <= 2) y0 - 1 else y0
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468
}

/** Cron in words for the common presets; otherwise the job's own display text (design §3.6 "schedule in words"). */
fun scheduleWords(expr: String?, display: String?, s: Strings): String {
    val e = expr?.trim().orEmpty()
    val parts = e.split(Regex("\\s+"))
    if (parts.size == 5) {
        val (mi, h, dom, mon, dow) = parts
        val time = if (h.toIntOrNull() != null && mi.toIntOrNull() != null) "${h.padStart(2, '0')}:${mi.padStart(2, '0')}" else null
        if (time != null && dom == "*" && mon == "*") {
            when (dow) {
                "*" -> return "${s.everyDay} · $time"
                "1-5" -> return "${s.weekdays} · $time"
            }
        }
        if (mi == "0" && h == "*" && dom == "*" && mon == "*" && dow == "*") return s.everyHour
    }
    return display?.takeIf { it.isNotBlank() } ?: e
}

private val toolVerbsEn = mapOf(
    "read_file" to "Read", "write_file" to "Write", "patch" to "Edit", "edit_file" to "Edit", "terminal" to "Run",
    "search_files" to "Search", "web_search" to "Search the web", "web_extract" to "Read page", "browser_navigate" to "Open page",
    "browser_click" to "Click", "browser_type" to "Type", "browser_snapshot" to "Look at page", "todo" to "Update plan",
    "delegate_task" to "Helper", "memory" to "Remember", "skill_view" to "Open skill", "skills_list" to "List skills",
    "clarify" to "Ask", "execute_code" to "Run code", "vision_analyze" to "Look at image", "image_generate" to "Make image",
    "send_message" to "Message", "cronjob" to "Schedule", "process" to "Process", "session_search" to "Search chats",
    "text_to_speech" to "Speak", "message_agent" to "Message bot", "tool_search" to "Find tool",
)
private val toolVerbsHe = mapOf(
    "read_file" to "קריאה", "write_file" to "כתיבה", "patch" to "עריכה", "edit_file" to "עריכה", "terminal" to "הרצה",
    "search_files" to "חיפוש", "web_search" to "חיפוש ברשת", "web_extract" to "קריאת דף", "browser_navigate" to "פתיחת דף",
    "browser_click" to "לחיצה", "browser_type" to "הקלדה", "browser_snapshot" to "מבט בדף", "todo" to "עדכון תוכנית",
    "delegate_task" to "עוזר", "memory" to "זכירה", "skill_view" to "פתיחת מיומנות", "skills_list" to "רשימת מיומנויות",
    "clarify" to "שאלה", "execute_code" to "הרצת קוד", "vision_analyze" to "מבט בתמונה", "image_generate" to "יצירת תמונה",
    "send_message" to "הודעה", "cronjob" to "תזמון", "process" to "תהליך", "session_search" to "חיפוש בצ'אטים",
    "text_to_speech" to "דיבור", "message_agent" to "הודעה לבוט", "tool_search" to "חיפוש כלי",
)

/** A human label ("Read build.gradle"), never the raw tool name; the raw name lives under Details (design §3.2). */
fun toolLabel(tool: String, context: String?, s: Strings): String {
    val verbs = if (s.rtl) toolVerbsHe else toolVerbsEn
    val verb = verbs[tool] ?: verbs.entries.firstOrNull { tool.startsWith(it.key.substringBefore('_')) && it.key.contains('_') && tool.startsWith(it.key) }?.value
        ?: tool.substringAfterLast("__").replace('_', ' ').replaceFirstChar { it.uppercase() }
    val ctx = context?.lineSequence()?.firstOrNull()?.trim()?.let { c ->
        if ((tool == "read_file" || tool == "write_file" || tool == "patch") && ('/' in c || '\\' in c)) c.substringAfterLast('/').substringAfterLast('\\') else c
    }?.take(60)
    return if (ctx.isNullOrBlank()) verb else "$verb $ctx"
}

/** Unicode isolate for LTR tokens inside Hebrew text (design §9.5). */
fun ltr(s: String) = "⁨$s⁩"

private val contextTag = Regex("""\[(\w+)]$""")
private val claudeId = Regex("""^claude-(?:(\d+)(?:[-.](\d+))?-)?([a-z]+)(?:-(\d{1,2})(?:[-.](\d{1,2}))?)?(?:-\d{8})?$""", RegexOption.IGNORE_CASE)

/**
 * A model id as people say it: "anthropic/claude-opus-5-5[1m]" → "Opus 5.5 · 1M", "claude-3-5-sonnet-20241022" → "Sonnet 3.5".
 * Other ids keep their name minus the provider ("openai/gpt-5" → "gpt-5"); a "[128k]" context tag becomes " · 128K".
 */
fun modelLabel(id: String?): String? {
    val raw = id?.substringAfterLast('/')?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val tag = contextTag.find(raw)
    val base = if (tag == null) raw else raw.removeRange(tag.range).trim()
    val name = claudeId.find(base)?.destructured?.let { (oldMajor, oldMinor, family, major, minor) ->
        val version = when {
            major.isNotEmpty() -> if (minor.isNotEmpty()) "$major.$minor" else major
            oldMajor.isNotEmpty() -> if (oldMinor.isNotEmpty()) "$oldMajor.$oldMinor" else oldMajor
            else -> ""
        }
        (family.lowercase().replaceFirstChar { it.uppercase() } + " " + version).trim()
    } ?: base
    return if (tag == null) name else "$name · ${tag.groupValues[1].uppercase()}"
}
