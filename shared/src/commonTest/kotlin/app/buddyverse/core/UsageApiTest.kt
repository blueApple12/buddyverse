package app.buddyverse.core

import app.buddyverse.core.api.CLAUDE_SUB
import app.buddyverse.core.api.parseChatUsage
import app.buddyverse.core.api.parsePlanLimits
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsageApiTest {
    private fun j(s: String) = WireJson.parseToJsonElement(s) as JsonObject

    @Test fun planLimitsShowOnlyTheClaudeSubscription() {
        val o = j("""{"providers":[
            {"slug":"antigravity-oauth","name":"Google Antigravity","usage":{"windows":[{"label":"Gemini 7d","used_percent":1.5,"resets_at":"2026-11-04T07:50:38+00:00"}]}},
            {"slug":"anthropic","name":"Anthropic","usage":{"windows":[{"label":"Current session","used_percent":5.0}]}},
            {"slug":"$CLAUDE_SUB","name":"Claude","usage":{"windows":[{"label":"Current session","used_percent":5.0,"resets_at":"2026-10-28T10:09:59+00:00"},{"label":"Current week","used_percent":84.0}]}},
            {"slug":"nous","name":"Nous"}]}""")
        val l = parsePlanLimits(o)
        assertEquals(listOf(CLAUDE_SUB), l.map { it.provider })
        assertEquals(listOf("Current session", "Current week"), l[0].windows.map { it.label })
        assertEquals(84.0, l[0].windows[1].usedPercent)
        assertNull(l[0].windows[1].resetsAt)
    }

    @Test fun chatUsageMergesRowAndLiveContext() {
        val row = j("""{"model":"claude-opus-5-5[1m]","input_tokens":100,"output_tokens":50,"cache_read_tokens":1000,"cache_write_tokens":20,"api_call_count":3,"estimated_cost_usd":0.42}""")
        val live = j("""{"context_used":50000,"context_max":1000000,"input":1}""")
        val u = parseChatUsage(row, live)
        assertEquals(1170, u.total)
        assertEquals(0.42, u.costUsd)
        assertEquals(0.05f, u.contextFill)
        assertNull(parseChatUsage(row, null).contextFill)
    }
}
