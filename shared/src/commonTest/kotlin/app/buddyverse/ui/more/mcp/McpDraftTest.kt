package app.buddyverse.ui.more.mcp

import app.buddyverse.core.WireJson
import app.buddyverse.core.child
import app.buddyverse.core.obj
import app.buddyverse.core.str
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class McpDraftTest {
    private val saved = WireJson.parseToJsonElement(
        """{"command":"node","args":["a.js"],"env":{"TOKEN":"s3cret","MODE":"x"},"tools":{"exclude":["rm"]},"timeout":30}""",
    ).obj!!

    @Test fun editKeepsSecretsAndUnknownKeys() {
        val d = draftOf("mc", saved)
        assertEquals("TOKEN=$MASK\nMODE=$MASK", d.env)
        val e = entryOf(d.copy(env = d.env.replace("MODE=$MASK", "MODE=y"), args = "a.js\n --fast \n"), saved)
        assertEquals("s3cret", e.child("env").str("TOKEN"))
        assertEquals("y", e.child("env").str("MODE"))
        assertEquals("""["a.js","--fast"]""", e["args"].toString())
        assertEquals("30", e["timeout"].toString())
        assertEquals("""{"exclude":["rm"]}""", e["tools"].toString())
    }

    @Test fun switchingToLinkDropsCommandFields() {
        val e = entryOf(draftOf("mc", saved).copy(useUrl = true, url = " https://x/mcp ", auth = McpAuth.OAuth), saved)
        assertEquals("https://x/mcp", e.str("url"))
        assertEquals("oauth", e.str("auth"))
        assertNull(e["command"]); assertNull(e["env"]); assertNull(e["args"])
    }

    @Test fun toolFilter() {
        val s = McpServer("mc", null, "node", emptyList(), emptyList(), null, true, null, listOf("rm"), null)
        val on = s.withTool("rm", true)
        assertEquals(emptyList<String>(), on.exclude)
        assertNull(withToolFilter(saved, on)["tools"])
        val inc = s.copy(include = listOf("a", "b"), exclude = emptyList()).withTool("a", false)
        assertEquals(listOf("b"), inc.include)
        assertEquals(false, inc.toolOn("a"))
        assertEquals("""{"include":["b"]}""", withToolFilter(JsonObject(emptyMap()), inc)["tools"].toString())
    }

    @Test fun states() {
        val s = McpServer("x", "https://x", null, emptyList(), emptyList(), null, true, null, emptyList(), null)
        assertEquals(McpState.Off, mcpStateOf(s.copy(enabled = false), "connected", null))
        assertEquals(McpState.Connecting, mcpStateOf(s, "connected", McpProbe()))
        assertEquals(McpState.NeedsAuth, mcpStateOf(s, null, McpProbe(ok = false, error = "HTTP 401 Unauthorized")))
        assertEquals(McpState.Failed, mcpStateOf(s, null, McpProbe(ok = false, error = "spawn ENOENT")))
        assertEquals(McpState.Connected, mcpStateOf(s, "connected", null))
        assertEquals(McpState.Idle, mcpStateOf(s, "lazy", null))
        assertEquals(mapOf("A" to "1=2"), envMap("A=1=2\nbad line\n=x"))
    }
}
