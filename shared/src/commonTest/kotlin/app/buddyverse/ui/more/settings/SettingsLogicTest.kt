package app.buddyverse.ui.more.settings

import app.buddyverse.core.WireJson
import app.buddyverse.core.api.at
import app.buddyverse.core.api.withAt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsLogicTest {
    @Test fun dottedKeysReadAndWrite() {
        val cfg = WireJson.parseToJsonElement("""{"sessions":{"auto_archive":true},"model":"x"}""") as JsonObject
        assertEquals(JsonPrimitive(true), cfg.at("sessions.auto_archive"))
        assertNull(cfg.at("sessions.nope.deeper"))
        val patched = JsonObject(emptyMap()).withAt("sessions.auto_archive_days", JsonPrimitive(30))
        assertEquals("""{"sessions":{"auto_archive_days":30}}""", patched.toString())
        assertEquals(JsonPrimitive(true), cfg.withAt("sessions.auto_archive_days", JsonPrimitive(3)).at("sessions.auto_archive"))
    }

    @Test fun editorsParseTheirType() {
        assertEquals(JsonPrimitive(30L), parseFor("30", "number", null))
        assertEquals(JsonPrimitive(.5), parseFor(" 0.5 ", "number", null))
        assertNull(parseFor("thirty", "number", null))
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), parseFor("a\n\n b \n", "list", null))
        assertNull(parseFor("{", "object", null))
        assertEquals("a\nb", textOf(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), "list"))
        assertEquals("", textOf(null, "string"))
    }
}
