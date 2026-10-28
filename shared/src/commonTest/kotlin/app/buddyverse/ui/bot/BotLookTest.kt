package app.buddyverse.ui.bot

import androidx.compose.ui.graphics.Color
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BotLookTest {
    @Test fun handles() {
        assertEquals("research-buddy", handleFor("Research Buddy!", emptySet()))
        assertEquals("bot", handleFor("נובה", emptySet()))
        assertEquals("bot-3", handleFor("נובה", setOf("bot", "bot-2")))
        assertEquals("default-2", freeHandle("default", setOf("default")))
        assertEquals("test-2", handleFor("Test", emptySet())) // reserved
        val long = handleFor("a".repeat(80), setOf("a".repeat(56)))
        assertTrue(long.length <= 64 && HANDLE.matches(long), long)
        listOf("Hi", "--x--", "_lead", "Ünïcode 9").forEach { assertTrue(HANDLE.matches(handleFor(it, emptySet())), it) }
    }

    @Test fun lookRoundTrips() {
        BuddyShape.entries.forEach { assertEquals(it, BuddyShape.parse(it.wire(), "seed")) }
        BuddyColors.palette.forEach { assertEquals(it, BuddyColors.parse(it.css())) }
        assertEquals("#ff263c", Color(0xFFFF263C).css())
    }
}
