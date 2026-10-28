package app.buddyverse.core

import androidx.compose.ui.graphics.Color
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TempChatsTest {
    private fun bot(p: String, default: Boolean) =
        Bot(p, p, "", default, BuddySpec(p, p, BuddyShape.Blob, Color.Red), null, "chat-$p", null, 0.0, BotStatus.Idle, false, false)
    private val bots = listOf(bot("default", true), bot("work", false))
    private fun row(id: String, profile: String, title: String? = null) = SessionRow(id, profile, title, null, 0.0, false, false, false, "gui", 0)

    @Test fun quickIsABrainChatThatIsNotItsBotChat() {
        assertTrue(TempChats.isQuick(row("s1", "default"), bots))
        assertTrue(TempChats.isQuick("default", null, bots)) // fresh, not created yet
        assertFalse(TempChats.isQuick(row("chat-default", "default"), bots))
        assertFalse(TempChats.isQuick(row("s2", "default", Hermes.BOT_CHAT), bots))
        assertFalse(TempChats.isQuick(row("s3", "work"), bots)) // a project bot's chat
        assertFalse(TempChats.isQuick(row("s1", "default"), emptyList())) // roster not loaded
    }

    @Test fun tempKeyIsPerChatAndStable() {
        val t = ChatTarget("default", null, botChat = false, nonce = 7, temp = true)
        assertEquals(t.key, t.copy(sessionId = "s1").key)
        assertTrue(t.key != t.copy(nonce = 8).key)
        assertTrue(t.key != ChatTarget("default", null, botChat = false, nonce = 7).key)
    }
}
