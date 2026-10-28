package app.buddyverse.ui.more.connectors

import app.buddyverse.core.RpcError
import app.buddyverse.core.WireJson
import app.buddyverse.core.api.CONNECTOR_ALIAS
import app.buddyverse.core.api.ConnectionOp
import app.buddyverse.core.api.aliasDraft
import app.buddyverse.core.api.connectorTitle
import app.buddyverse.core.api.debrand
import app.buddyverse.core.api.parsePolicy
import app.buddyverse.core.decodeAs
import app.buddyverse.core.obj
import app.buddyverse.core.params
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectorsTest {
    @Test fun debrandsCatalogCopy() {
        assertEquals("Use Ahrefs", debrand("Use Ahrefs through Hermes"))
        assertEquals("Read and edit bases", debrand("Read and edit bases"))
        assertFalse(debrand("Powered by Nous Research tools").contains("Nous", ignoreCase = true))
    }

    @Test fun aliasRules() {
        assertEquals("my-work", aliasDraft("My Work"))
        assertTrue(CONNECTOR_ALIAS.matches("work-2"))
        assertFalse(CONNECTOR_ALIAS.matches("-work"))
        assertFalse(CONNECTOR_ALIAS.matches("a".repeat(33)))
        assertEquals("Google Drive", connectorTitle("google_drive_mcp"))
    }

    @Test fun policyLayers() {
        val p = parsePolicy(WireJson.parseToJsonElement("""{"layers":[
            {"kind":"org","revision":"R0","body":{"mode":"deny","disabled_connectors":["slack"],"tools":{"gmail":["gmail_delete"]}}},
            {"kind":"member","revision":"R1","body":{"mode":"deny","disabled_connectors":["notion"],"tools":{"gmail":["gmail_send"]}}}
        ],"effective":{"version":1,"revision":"R1","issued_at_ms":0,"mode":"deny"}}""").obj)
        assertEquals("R1", p.revision)
        assertFalse(p.enabled("notion")); assertTrue(p.enabled("gmail"))
        assertTrue(p.orgLocked("slack")); assertFalse(p.orgLocked("gmail"))
        assertEquals(listOf("gmail_send"), p.disabledTools("gmail"))
        assertEquals(setOf("gmail_delete"), p.lockedTools("gmail"))
        assertNull(parsePolicy(params()).revision)
    }

    @Test fun operationEnds() {
        val open = WireJson.parseToJsonElement("""{"op_id":"op_1","seq":1,"deadline_at":1.0,"settled":false,
            "targets":[{"name":"gmail","kind":"connector","action":"connect","state":"initiated","connect_url":"https://x","tools":null}]}""").decodeAs<ConnectionOp>()
        assertFalse(open.done); assertEquals("https://x", open.connectUrl)
        val done = WireJson.parseToJsonElement("""{"op_id":"op_1","seq":2,"deadline_at":1.0,"settled":false,
            "targets":[{"name":"gmail","kind":"connector","action":"connect","state":"connected","resolved":true}]}""").decodeAs<ConnectionOp>()
        assertTrue(done.done); assertTrue(done.connected)
    }

    @Test fun problems() {
        assertEquals(Problem.NameTaken, problemOf(RpcError(4000, "taken", params("reason" to "ALIAS_TAKEN"))))
        assertEquals(Problem.Unavailable, problemOf(RpcError(4031, "off", params("reason" to "CONNECTORS_UNAVAILABLE"))))
        assertEquals(Problem.Other, problemOf(IllegalStateException()))
    }
}
