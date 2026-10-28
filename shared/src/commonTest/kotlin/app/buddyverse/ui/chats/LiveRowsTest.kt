package app.buddyverse.ui.chats

import androidx.compose.ui.graphics.Color
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.core.Bot
import app.buddyverse.core.BotStatus
import app.buddyverse.core.SessionRow
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveRowsTest {
    private fun bot(p: String, last: Double, live: Set<String>) =
        Bot(p, "Bot $p", "", false, BuddySpec(p, p, BuddyShape.Blob, Color.Red), null, "chat-$p", "hi", last, BotStatus.Working, false, false, live)
    private fun row(id: String, p: String, last: Double) = SessionRow(id, p, "t-$id", null, last, true, false, false, "desktop", 3)

    @Test fun hiddenBotChatsAndUnlistedSessionsStillShow() {
        // secondbrain's Bot Chat is hidden (never listed); default runs a listed chat and an unlisted one.
        val bots = listOf(bot("default", 10.0, setOf("s1", "s2")), bot("secondbrain", 20.0, setOf("chat-secondbrain")))
        val rows = liveRows(bots, listOf(row("s1", "default", 30.0), row("other", "default", 40.0)))
        assertEquals(listOf("s1", "chat-secondbrain", "s2"), rows.map { it.id })
        assertEquals("t-s1", rows[0].title) // a listed row is kept as is
        assertEquals("Bot secondbrain", rows[1].title) // a Bot Chat is named after its bot
        assertEquals("secondbrain", rows[1].profile)
    }

    @Test fun nothingLiveIsEmpty() {
        assertEquals(emptyList(), liveRows(listOf(bot("default", 1.0, emptySet())), listOf(row("s1", "default", 1.0))))
    }
}
