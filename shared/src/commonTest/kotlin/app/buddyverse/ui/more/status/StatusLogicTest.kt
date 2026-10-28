package app.buddyverse.ui.more.status

import app.buddyverse.buddy.Mood
import app.buddyverse.core.WireJson
import app.buddyverse.core.api.PcHealth
import app.buddyverse.core.decodeAs
import app.buddyverse.i18n.StatusPageStrings
import app.buddyverse.ui.parseIsoMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import app.buddyverse.i18n.EnglishStrings

class StatusLogicTest {
    private fun health(json: String): PcHealth = WireJson.parseToJsonElement(json).decodeAs()

    @Test fun moodFollowsTheEngine() {
        val ok = health("""{"gateway_running":true,"gateway_state":"running","overall":"ok","active_agents":0}""")
        assertEquals(Mood.Idle, ok.mood(reachable = true, acting = false))
        assertEquals(Mood.Working, ok.copy(activeAgents = 2).mood(true, false))
        assertEquals(Mood.NeedsYou, ok.copy(overall = "degraded").mood(true, false))
        assertEquals(Mood.Sleeping, ok.copy(running = false, state = "stopped").mood(true, false))
        assertEquals(Mood.Error, ok.copy(state = "fatal").mood(true, false))
        assertEquals(Mood.Offline, ok.mood(reachable = false, acting = false))
        assertEquals(Mood.Working, ok.mood(true, acting = true))
    }

    @Test fun warningsSayWhatToLookAt() {
        val t = StatusPageStrings()
        // Memory: only under 2 GB free warns; "unknown" pressure (Windows) with plenty free stays calm.
        assertTrue(health("""{"gateway_running":true,"memory":{"pressure":"unknown","system_available_mb":10240},"disk":{"pressure":"unknown"}}""").warnings(t, EnglishStrings).isEmpty())
        assertTrue(health("""{"gateway_running":true,"memory":{"pressure":"unknown"}}""").warnings(t, EnglishStrings).isEmpty())
        val h = health("""{"gateway_running":true,"memory":{"pressure":"high","system_available_mb":1500,"last_boot_suspected_oom":true},
            "components":{"storage":{"status":"degraded","reason":"corrupt"}}}""")
        val w = h.warnings(t, EnglishStrings)
        assertEquals(t.storageCorrupt, w.first())
        assertTrue(t.oomBoot in w && t.memoryPressure in w && t.uncleanBoot !in w)
        assertTrue(health("""{"gateway_running":true,"config_version":6,"latest_config_version":7}""").configBehind)
    }

    @Test fun uptimeAndUnits() {
        val boot = "2026-10-26T10:00:00Z"
        val h = health("""{"gateway_running":true,"memory":{"boot_id":"$boot"}}""")
        assertEquals(3 * 3600L + 12 * 60, h.uptimeSeconds(parseIsoMillis("2026-10-26T13:12:00Z")!!))
        assertEquals("3h 12m", span(3 * 3600L + 12 * 60, EnglishStrings))
        assertEquals("2d 4h", span(2 * 86_400L + 4 * 3600, EnglishStrings))
        assertEquals("16.0 GB", bytes(17_179_869_184))
        assertEquals("512 MB", bytes(536_870_912))
        assertEquals(2, lineLevel("2026-10-26 10:00:00 ERROR gateway: boom"))
        assertEquals(1, lineLevel("2026-10-26 10:00:00 WARNING x"))
        assertEquals(0, lineLevel("2026-10-26 10:00:00 INFO x"))
    }
}
