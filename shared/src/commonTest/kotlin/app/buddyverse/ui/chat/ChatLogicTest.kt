package app.buddyverse.ui.chat

import app.buddyverse.core.api.historyItems
import app.buddyverse.core.chat.BotItem
import app.buddyverse.core.chat.UserItem
import app.buddyverse.core.chat.WorkItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import app.buddyverse.i18n.EnglishStrings

class ChatLogicTest {
    @Test
    fun fencesSplitProseAndCodeEvenWhileStreaming() {
        val b = mdBlocks("Hi **there**\n```kotlin\nval x = 1\n```\n- one\n```\nopen")
        assertEquals(listOf(Md.Prose("Hi **there**"), Md.Code("val x = 1"), Md.Prose("- one"), Md.Code("open")), b)
    }

    @Test
    fun restRowsBecomeBubblesAndOneFoldedWorkCard() {
        val rows = Json.parseToJsonElement(
            """[{"id":1201,"role":"user","content":"Fix it","timestamp":1761400001.2},
            {"id":1202,"role":"assistant","content":"","reasoning":"hmm","tool_calls":[{"id":"t1","function":{"name":"read_file","arguments":"{}"}}],
             "tool_call_labels":{"t1":[{"kind":"path","text":"src/Login.tsx"}]}},
            {"id":1203,"role":"tool","tool_call_id":"t1","content":"body"},
            {"id":1204,"role":"assistant","content":[{"type":"text","text":"Done."}]},
            {"id":1205,"role":"system","content":"x"}]""",
        ).jsonArray.map { it.jsonObject }
        val items = historyItems(rows)
        assertEquals(3, items.size)
        assertEquals(1201L, (items[0] as UserItem).rowId)
        val w = items[1] as WorkItem
        assertEquals("body", w.steps.single().result)
        assertEquals("src/Login.tsx", w.steps.single().context)
        assertFalse(w.running)
        assertEquals("Done.", (items[2] as BotItem).text)
    }

    @Test
    fun elapsedReadsLikeAClock() {
        assertEquals("5s", elapsedText(5_000, EnglishStrings))
        assertEquals("1m 12s", elapsedText(72_000, EnglishStrings))
    }

    @Test
    fun argumentCompletionReplacesFromTheServersColumn() {
        assertEquals("/personality alice", completeLine("/personality al", 13, "alice"))
        assertEquals("/x y", completeLine("/x ", null, "y"))
    }
}
