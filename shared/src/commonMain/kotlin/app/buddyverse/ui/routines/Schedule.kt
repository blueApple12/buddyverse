package app.buddyverse.ui.routines

import app.buddyverse.core.child
import app.buddyverse.core.long
import app.buddyverse.core.str
import app.buddyverse.i18n.RoutinesStrings
import app.buddyverse.i18n.Strings
import app.buddyverse.ui.scheduleWords
import kotlinx.serialization.json.JsonObject

/** What the editor's When part can express; anything else is [Kind.Custom] text (cron or Hermes words). */
enum class Kind { Daily, Weekdays, Hourly, Once, Custom }

data class When(val kind: Kind, val hour: Int = 9, val minute: Int = 0, val text: String = "") {
    /** The `schedule` string the server parses (hermes-api.md §2.5 "Schedule formats"). [offsetMin] = the PC's UTC offset. */
    fun toSchedule(nowMs: Long, offsetMin: Int): String = when (kind) {
        Kind.Daily -> "$minute $hour * * *"
        Kind.Weekdays -> "$minute $hour * * 1-5"
        Kind.Hourly -> "0 * * * *"
        Kind.Once -> nextWallIso(hour, minute, nowMs, offsetMin)
        Kind.Custom -> text.trim()
    }

    val valid: Boolean get() = kind != Kind.Custom || text.isNotBlank()
}

/** A job's `schedule` object back into editor terms. */
fun parseWhen(job: JsonObject?): When {
    val sch = job.child("schedule") ?: return When(Kind.Daily, 8, 0)
    val display = sch.str("display") ?: job.str("schedule_display")
    when (sch.str("kind")) {
        "once" -> wallTime(sch.str("run_at"))?.let { (h, m) -> return When(Kind.Once, h, m) }
        "interval" -> return When(Kind.Custom, text = display ?: "every ${sch.long("minutes") ?: 60}m")
    }
    val expr = sch.str("expr")?.trim().orEmpty()
    val p = expr.split(Regex("\\s+"))
    if (p.size == 5) {
        val mi = p[0].toIntOrNull(); val h = p[1].toIntOrNull()
        if (expr == "0 * * * *") return When(Kind.Hourly)
        if (mi != null && h != null && p[2] == "*" && p[3] == "*") when (p[4]) {
            "*" -> return When(Kind.Daily, h, mi)
            "1-5" -> return When(Kind.Weekdays, h, mi)
        }
    }
    return When(Kind.Custom, text = display ?: expr)
}

/** "Every day · 08:00", "Mon, Wed · 09:30", "Every 30 min", "Once · 27/10 09:00": the schedule in words (design §3.6). */
fun routineWords(job: JsonObject, s: Strings, r: RoutinesStrings): String {
    val sch = job.child("schedule")
    val display = sch.str("display") ?: job.str("schedule_display")
    when (sch.str("kind")) {
        "interval" -> {
            val m = sch.long("minutes") ?: return display.orEmpty()
            return if (m >= 60 && m % 60 == 0L) (if (m == 60L) s.everyHour else r.everyHours(m / 60)) else r.everyMinutes(m)
        }
        "once" -> return "${s.once} · ${wallText(sch.str("run_at")) ?: display.orEmpty()}"
    }
    val expr = sch.str("expr")
    return cronWords(expr, s, r) ?: scheduleWords(expr, display, s)
}

internal fun cronWords(expr: String?, s: Strings, r: RoutinesStrings): String? {
    val p = expr?.trim()?.split(Regex("\\s+")) ?: return null
    if (p.size != 5) return null
    val (mi, h, dom, mon, dow) = p
    val m = mi.toIntOrNull()
    val hr = h.toIntOrNull()
    if (mon != "*") return null
    if (m != null && hr != null) {
        val t = hhmm(hr, m)
        if (dom == "*") {
            val days = when (dow) {
                "*" -> s.everyDay
                "1-5" -> s.weekdays
                "0,6", "6,0", "6-7", "6,7" -> r.weekends
                else -> dow.split(',').map { it.toIntOrNull()?.let { d -> r.dayNames.getOrNull(d % 7) } ?: return null }.joinToString(", ")
            }
            return "$days · $t"
        }
        if (dow == "*" && dom.toIntOrNull() != null) return "${r.monthly(dom)} · $t"
        return null
    }
    if (dom != "*" || dow != "*") return null
    if (m != null && h == "*") return s.everyHour
    if (mi.startsWith("*/") && h == "*") return mi.drop(2).toLongOrNull()?.let(r.everyMinutes)
    if (m != null && h.startsWith("*/")) return h.drop(2).toLongOrNull()?.let(r.everyHours)
    return null
}

fun hhmm(h: Int, m: Int) = "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"

private val isoRe = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})""")

/** The wall-clock hour and minute written in an ISO time (the PC's zone; no conversion). */
fun wallTime(iso: String?): Pair<Int, Int>? = isoRe.find(iso ?: return null)?.destructured?.let { (_, _, _, h, m) -> h.toInt() to m.toInt() }

/** "27/10 09:00" from an ISO time, as the PC's clock shows it. */
fun wallText(iso: String?): String? = isoRe.find(iso ?: return null)?.destructured?.let { (_, mo, d, h, m) -> "${d.trimStart('0')}/${mo.trimStart('0')} $h:$m" }

/** UTC offset in minutes from an ISO time's suffix ("+02:00", "Z"); null without one. */
fun isoOffsetMinutes(iso: String?): Int? {
    val t = iso?.trim() ?: return null
    if (t.endsWith("Z")) return 0
    val m = Regex("""([+-])(\d{2}):?(\d{2})$""").find(t) ?: return null
    val (sign, hh, mm) = m.destructured
    return (if (sign == "-") -1 else 1) * (hh.toInt() * 60 + mm.toInt())
}

/** The PC's UTC offset, read off the timestamps the server writes on jobs (Hermes has no per-job zone). */
fun pcOffsetMinutes(jobs: List<JsonObject>): Int? =
    jobs.firstNotNullOfOrNull { j -> listOf("next_run_at", "last_run_at", "created_at").firstNotNullOfOrNull { isoOffsetMinutes(j.str(it)) } }

/** Naive ISO for the next [h]:[m] on the PC's clock (today, or tomorrow if that minute has passed). Naive = Hermes zone. */
fun nextWallIso(h: Int, m: Int, nowMs: Long, offsetMin: Int): String {
    val day = 86_400_000L
    val local = nowMs + offsetMin * 60_000L
    var days = local.floorDiv(day)
    if (h * 3_600_000L + m * 60_000L <= local - days * day + 60_000L) days += 1
    val (y, mo, d) = civil(days)
    return "$y-${mo.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}T${hhmm(h, m)}:00"
}

/** Days since 1970-01-01 → (year, month, day). Hinnant's civil_from_days. */
internal fun civil(z0: Long): Triple<Int, Int, Int> {
    val z = z0 + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
    return Triple(y.toInt(), m.toInt(), d.toInt())
}

/** "41s", "3m", "1h 5m": how long a run took. */
fun duration(seconds: Double, s: Strings): String {
    val t = seconds.toLong().coerceAtLeast(0)
    return when {
        t < 60 -> s.unit(t, "s")
        t < 3600 -> s.unit(t / 60, "m")
        else -> s.unit(t / 3600, "h") + " " + s.unit((t % 3600) / 60, "m")
    }
}
