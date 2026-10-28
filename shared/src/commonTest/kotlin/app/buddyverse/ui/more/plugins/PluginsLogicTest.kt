package app.buddyverse.ui.more.plugins

import app.buddyverse.core.WireJson
import app.buddyverse.core.api.PluginField
import app.buddyverse.core.api.PluginRow
import app.buddyverse.core.obj
import app.buddyverse.i18n.APP_NAME
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Row shapes and texts from the live gateway (`plugins.manage list`, 2026-10-26). */
class PluginsLogicTest {
    private fun field(key: String, type: String, value: Any?, env: String? = null, choices: List<String> = emptyList()) = PluginField(
        key, type, key, "", false, value?.let { if (it is Boolean) JsonPrimitive(it) else if (it is Number) JsonPrimitive(it) else JsonPrimitive(it.toString()) },
        null, choices, env, false,
    )

    @Test fun onlyChangedValuesAreSentAndTyped() {
        val fields = listOf(field("dir", "string", ""), field("telemetry", "boolean", true), field("n", "number", 3),
            field("units", "enum", "metric", choices = listOf("metric", "imperial")), field("key", "secret", null, env = "DEMO_KEY"))
        val draft = initialDraft(fields) + mapOf("n" to "4.5", "telemetry" to "false", "key" to " sk-1 ")
        val c = collectChanges(fields, draft)
        assertEquals(mapOf("telemetry" to JsonPrimitive(false), "n" to JsonPrimitive(4.5)), c.values)
        assertEquals(mapOf("DEMO_KEY" to "sk-1"), c.secrets)
        assertTrue(collectChanges(fields, initialDraft(fields)).let { it.values.isEmpty() && it.secrets.isEmpty() })
    }

    @Test fun badValuesNameTheirField() {
        val fields = listOf(field("units", "enum", "metric", choices = listOf("metric")), field("n", "number", 1), field("j", "json", null))
        assertEquals(BadKind.Choice, assertFailsWith<BadField> { collectChanges(fields, initialDraft(fields) + ("units" to "x")) }.kind)
        assertEquals(BadKind.Number, assertFailsWith<BadField> { collectChanges(fields, initialDraft(fields) + ("n" to "abc")) }.kind)
        assertEquals(BadKind.Json, assertFailsWith<BadField> { collectChanges(fields, initialDraft(fields) + ("j" to "\"text\"")) }.kind)
    }

    @Test fun namesAndTextCarryNoEngineBrand() {
        assertEquals("Agent importers", pluginTitle("agent-importers"))
        assertEquals("Lang he", pluginTitle("hermes-lang-he"))
        assertEquals("A2A support for $APP_NAME — both directions", brandFree("A2A support for Hermes Agent — both directions"))
        assertEquals("Cloud browser backend.", brandFree("Cloud browser backend. Supports the managed Nous tool gateway."))
        assertEquals("`import-agent`: import a setup into $APP_NAME", brandFree("`hermes import-agent`: import a setup into Hermes"))
    }

    @Test fun pageListsWhatTheUserManages() {
        fun row(json: String) = PluginRow.from(WireJson.parseToJsonElement(json).obj!!)!!
        assertTrue(row("""{"name":"agent-importers","key":"agent-importers","source":"user","status":"enabled","servers":[]}""").listed)
        assertTrue(row("""{"name":"disk-cleanup","key":"disk-cleanup","source":"bundled","status":"not enabled","servers":[]}""").listed)
        assertFalse(row("""{"name":"basic","key":"dashboard_auth/basic","source":"bundled","status":"enabled","servers":[]}""").listed)
        assertFalse(row("""{"name":"telegram-platform","key":"platforms/telegram","source":"user","status":"enabled","servers":[]}""").listed)
        val pinned = row("""{"name":"x","key":"x","source":"git","status":"enabled","servers":[],"update_available":true,
            "update_target":"b5932f1b03ccd0f7738cb4975318df990408f3a3"}""")
        assertEquals("b5932f1b", pinned.updateTo)
        assertTrue(pinned.removable)
    }
}
