package app.buddyverse.ui.routines

import app.buddyverse.i18n.RoutinesStrings
import app.buddyverse.i18n.Strings
import app.buddyverse.ui.parseIsoMillis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScheduleTest {
    private val s = Strings()
    private val r = RoutinesStrings()
    private fun job(schedule: String) = Json.parseToJsonElement("""{"schedule":$schedule}""") as JsonObject
    private fun at(iso: String) = parseIsoMillis(iso)!!

    @Test fun editorRoundTrip() {
        val w = parseWhen(job("""{"kind":"cron","expr":"30 7 * * 1-5"}"""))
        assertEquals(When(Kind.Weekdays, 7, 30), w)
        assertEquals("30 7 * * 1-5", w.toSchedule(0, 0))
        assertEquals(Kind.Hourly, parseWhen(job("""{"kind":"cron","expr":"0 * * * *"}""")).kind)
        assertEquals(When(Kind.Custom, text = "every 30m"), parseWhen(job("""{"kind":"interval","minutes":30,"display":"every 30m"}""")))
        assertEquals(When(Kind.Once, 9, 15), parseWhen(job("""{"kind":"once","run_at":"2026-10-27T09:15:00+02:00"}""")))
        assertEquals(When(Kind.Custom, text = "every monday 9am"), parseWhen(job("""{"kind":"cron","expr":"0 9 * * 1","display":"every monday 9am"}""")))
        assertEquals(When(Kind.Daily, 8, 0), parseWhen(null))
    }

    @Test fun scheduleInWords() {
        assertEquals("Mon, Wed · 09:00", cronWords("0 9 * * 1,3", s, r))
        assertEquals("Every day · 07:30", cronWords("30 7 * * *", s, r))
        assertEquals("Weekends · 09:00", cronWords("0 9 * * 0,6", s, r))
        assertEquals("Day 1 of each month · 09:00", cronWords("0 9 1 * *", s, r))
        assertEquals("Every 15 min", cronWords("*/15 * * * *", s, r))
        assertEquals("Every 2 hours", cronWords("0 */2 * * *", s, r))
        assertNull(cronWords("0 9 * 1 *", s, r))
        assertEquals("Every 2 hours", routineWords(job("""{"kind":"interval","minutes":120}"""), s, r))
        assertEquals(s.everyHour, routineWords(job("""{"kind":"interval","minutes":60}"""), s, r))
        assertEquals("Once · 27/10 09:15", routineWords(job("""{"kind":"once","run_at":"2026-10-27T09:15:00+02:00"}"""), s, r))
    }

    @Test fun oneShotIsTheNextWallClockMinute() {
        val now = at("2026-10-26T10:00:00Z") // 12:00 on a UTC+2 PC
        assertEquals("2026-10-27T09:00:00", nextWallIso(9, 0, now, 120))
        assertEquals("2026-10-26T13:00:00", nextWallIso(13, 0, now, 120))
        assertEquals("2026-10-27T12:00:00", nextWallIso(12, 0, now, 120)) // this very minute is too late
        assertEquals("2027-01-01T08:00:00", nextWallIso(8, 0, at("2026-12-31T21:30:00Z"), 120))
        assertEquals("2028-02-29T08:00:00", nextWallIso(8, 0, at("2028-02-28T22:30:00Z"), 120))
        assertEquals("2026-10-25T22:00:00", nextWallIso(22, 0, at("2026-10-26T02:00:00Z"), -300))
    }

    @Test fun offsetsComeFromServerTimestamps() {
        assertEquals(120, isoOffsetMinutes("2026-10-27T09:00:00.551201+02:00"))
        assertEquals(0, isoOffsetMinutes("2026-10-27T09:00:00Z"))
        assertEquals(-330, isoOffsetMinutes("2026-10-27T09:00:00-05:30"))
        assertNull(isoOffsetMinutes("2026-10-27T09:00:00"))
    }
}
