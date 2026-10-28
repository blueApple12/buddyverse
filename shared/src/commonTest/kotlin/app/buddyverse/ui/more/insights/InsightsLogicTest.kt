package app.buddyverse.ui.more.insights

import app.buddyverse.core.WireJson
import app.buddyverse.core.api.epochDay
import app.buddyverse.core.api.isoDay
import app.buddyverse.core.api.merged
import app.buddyverse.core.api.parseInsights
import app.buddyverse.core.api.series
import app.buddyverse.core.obj
import kotlin.test.Test
import kotlin.test.assertEquals

class InsightsLogicTest {
    private val usage = WireJson.parseToJsonElement(
        """{"daily":[{"day":"2026-10-25","input_tokens":1000,"output_tokens":200,"estimated_cost":0.84,"sessions":2,"api_calls":5}],
            "by_model":[{"model":"m1","input_tokens":1000,"output_tokens":200,"estimated_cost":0.8,"sessions":2,"api_calls":5}],
            "totals":{"total_input":1000,"total_output":200,"total_estimated_cost":0.84,"total_sessions":2,"total_api_calls":5},
            "tools":[{"tool":"terminal","count":3},{"tool":"x","count":0}],"skills":{"top_skills":[{"skill":"pdf","view_count":2,"manage_count":1}]}}""",
    ).obj

    @Test fun calendarRoundTrips() {
        val d = epochDay("2026-10-26")!!
        assertEquals("2026-10-26", isoDay(d))
        assertEquals("2024-02-29", isoDay(epochDay("2024-03-01")!! - 1))
        assertEquals(0L, epochDay("1970-01-01"))
    }

    @Test fun seriesFillsEveryDay() {
        val i = parseInsights(7, usage, null, null)
        val s = i.series(epochDay("2026-10-26")!!)
        assertEquals(7, s.size)
        assertEquals("2026-10-20", s.first().day)
        assertEquals(0.84, s.first { it.day == "2026-10-25" }.use.cost)
        assertEquals(1, i.tools.size) // zero-count tools are dropped
    }

    @Test fun allBotsAddUp() {
        val a = parseInsights(7, usage, null, null)
        val m = listOf(a, a).merged()!!
        assertEquals(4L, m.totals.sessions)
        assertEquals(1, m.models.size)
        assertEquals(2400L, m.models.single().use.tokens)
        assertEquals(6L, m.skills.single().total)
    }

    @Test fun numbersReadWell() {
        assertEquals("950", compact(950))
        assertEquals("1.2K", compact(1234))
        assertEquals("46K", compact(45_600))
        assertEquals("1.3M", compact(1_250_000))
        assertEquals("$0", money(0.0))
        assertEquals("<$0.01", money(0.001))
        assertEquals("$4.20", money(4.2))
        assertEquals("$1,240", money(1240.4))
        assertEquals("25/10", shortDay("2026-10-25"))
    }
}
