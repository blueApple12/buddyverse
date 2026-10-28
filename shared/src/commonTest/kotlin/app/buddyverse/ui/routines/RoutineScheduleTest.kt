package app.buddyverse.ui.routines

import app.buddyverse.i18n.EnglishStrings
import app.buddyverse.i18n.RoutinesStrings
import app.buddyverse.ui.parseIsoMillis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutineScheduleTest {
    private val s = EnglishStrings
    private val r = RoutinesStrings()
    private fun job(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test fun civilDates() {
        assertEquals(Triple(1970, 1, 1), civil(0))
        assertEquals(Triple(2026, 10, 26), civil(20_752))
        assertEquals(Triple(2000, 2, 29), civil(11_016))
        assertEquals(Triple(1969, 12, 31), civil(-1))
    }

    @Test fun onceIsNextWallClockTimeOnThePc() {
        val now = parseIsoMillis("2026-10-26T10:00:00Z")!! // 12:00 on a +02:00 PC
        assertEquals("2026-10-26T13:30:00", nextWallIso(13, 30, now, 120))
        assertEquals("2026-10-27T09:00:00", nextWallIso(9, 0, now, 120))
        assertEquals("2026-10-27T00:30:00", nextWallIso(0, 30, parseIsoMillis("2026-10-26T23:00:00+02:00")!!, 120))
    }

    @Test fun offsets() {
        assertEquals(120, isoOffsetMinutes("2026-10-27T09:00:00+02:00"))
        assertEquals(-330, isoOffsetMinutes("2026-10-27T09:00:00.12-05:30"))
        assertEquals(0, isoOffsetMinutes("2026-10-26T10:00:05Z"))
        assertEquals(null, isoOffsetMinutes("2026-10-27T09:00:00"))
    }

    @Test fun editorRoundTrip() {
        val w = parseWhen(job("""{"schedule":{"kind":"cron","expr":"30 7 * * 1-5","display":"weekdays at 7:30am"}}"""))
        assertEquals(When(Kind.Weekdays, 7, 30), w)
        assertEquals("30 7 * * 1-5", w.toSchedule(0, 0))
        assertEquals(When(Kind.Once, 9, 0), parseWhen(job("""{"schedule":{"kind":"once","run_at":"2026-10-27T09:00:00+02:00"}}""")))
        assertEquals(When(Kind.Custom, text = "every 30m"), parseWhen(job("""{"schedule":{"kind":"interval","minutes":30,"display":"every 30m"}}""")))
        assertEquals(When(Kind.Daily, 8, 0), parseWhen(null))
        assertEquals(false, When(Kind.Custom, text = " ").valid)
    }

    @Test fun words() {
        assertEquals("Every day · 08:00", cronWords("0 8 * * *", s, r))
        assertEquals("Mon, Wed · 09:30", cronWords("30 9 * * 1,3", s, r))
        assertEquals("Weekends · 10:00", cronWords("0 10 * * 0,6", s, r))
        assertEquals("Every 15 min", cronWords("*/15 * * * *", s, r))
        assertEquals("Every 2 hours", cronWords("0 */2 * * *", s, r))
        assertEquals(null, cronWords("0 9 * 1 *", s, r))
        assertEquals("Once · 27/10 09:00", routineWords(job("""{"schedule":{"kind":"once","run_at":"2026-10-27T09:00:00+02:00"}}"""), s, r))
        assertEquals("Every 2 hours", routineWords(job("""{"schedule":{"kind":"interval","minutes":120}}"""), s, r))
    }
}
