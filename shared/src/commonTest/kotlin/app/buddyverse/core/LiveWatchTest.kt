package app.buddyverse.core

import androidx.compose.ui.graphics.Color
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveWatchTest {
    private fun bot(p: String, live: Set<String>) =
        Bot(p, p, "", false, BuddySpec(p, p, BuddyShape.Blob, Color.Red), null, "chat-$p", null, 0.0, BotStatus.Working, false, false, live)

    /** The Bot Chat is watched under the key the chat screen uses; other live sessions by their own id; idle bots not at all. */
    @Test fun watchesEveryLiveSessionOnce() {
        val out = watchTargets(listOf(bot("brain", setOf("chat-brain", "s2")), bot("idle", emptySet())))
        assertEquals(listOf("chat-brain" to ChatTarget("brain"), "s2" to ChatTarget("brain", "s2")), out)
        assertEquals(listOf("brain|botchat", "brain|s2"), out.map { it.second.key })
    }
}
