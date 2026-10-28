package app.buddyverse.ui.more.webhooks

import app.buddyverse.core.WireJson
import app.buddyverse.core.api.WebhookRoute
import app.buddyverse.core.api.hookUrl
import app.buddyverse.core.obj
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebhooksTest {
    @Test fun parsesTheListedRoute() {
        val r = WebhookRoute.from(WireJson.parseToJsonElement(
            """{"name":"gh-push","description":"d","events":["push",""],"deliver":"telegram","deliver_only":false,"prompt":"Sum it.",
               "script":"","skills":[],"created_at":"2026-10-26T07:41:02Z","url":"http://localhost:8644/webhooks/gh-push","secret_set":true}""",
        ).obj!!)!!
        assertEquals(listOf("push"), r.events)
        assertEquals("telegram", r.deliver)
        assertTrue(r.enabled)          // only an explicit enabled:false turns a route off
        assertTrue(r.secretSet)
        assertNull(WebhookRoute.from(WireJson.parseToJsonElement("""{"name":""}""").obj!!))
        assertFalse(WebhookRoute.from(WireJson.parseToJsonElement("""{"name":"x","enabled":false}""").obj!!)!!.enabled)
    }

    @Test fun otherBotsGetTheProfileLink() {
        val bare = "http://localhost:8644/webhooks/gh-push"
        assertEquals(bare, hookUrl(bare, "default"))
        assertEquals("http://localhost:8644/p/secondbrain/webhooks/gh-push", hookUrl(bare, "secondbrain"))
        assertEquals("http://localhost:8644/p/secondbrain/webhooks/gh-push", hookUrl(hookUrl(bare, "secondbrain"), "secondbrain"))
    }

    @Test fun formInputLikeTheServerReadsIt() {
        assertEquals("my-hook", webhookName("My Hook"))
        assertEquals(listOf("push", "pull_request"), words(" push, pull_request ,push\n"))
        assertEquals(emptyList<String>(), words(" , "))
    }
}
