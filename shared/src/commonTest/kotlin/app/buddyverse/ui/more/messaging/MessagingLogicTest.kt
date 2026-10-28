package app.buddyverse.ui.more.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessagingLogicTest {
    /** Finder pattern (7×7 ring, 3×3 core) at a corner; the encoder was also round-tripped through zxing for versions 1–39. */
    private fun finderAt(m: Array<BooleanArray>, x0: Int, y0: Int): Boolean = (0 until 7).all { y ->
        (0 until 7).all { x -> m[y0 + y][x0 + x] == (maxOf(kotlin.math.abs(x - 3), kotlin.math.abs(y - 3)) != 2) }
    }

    @Test fun qrPicksVersionAndDrawsFinders() {
        val small = QrCode.encode("hello")!!
        assertEquals(21, small.size)
        val n = small.size
        assertTrue(finderAt(small, 0, 0) && finderAt(small, n - 7, 0) && finderAt(small, 0, n - 7))
        assertEquals(57, QrCode.encode("A".repeat(213))!!.size) // version 10 holds 213 bytes at error correction M
        assertEquals(61, QrCode.encode("A".repeat(214))!!.size)
        assertNull(QrCode.encode("x".repeat(2332)))
    }

    @Test fun serverCopyLosesTheEngineName() {
        assertEquals("Run brain from Telegram DMs, groups, and topics.", plain("Run Hermes from Telegram DMs, groups, and topics.", "brain"))
        assertEquals("Matrix user ID (e.g. @bot:example.org)", plain("Matrix user ID (e.g. @hermes:example.org)", "brain"))
        assertEquals("Use $1 here", plain("Use Hermes Agent here", "$1"))
        assertFalse(plain("by Nous Research", "brain").contains("Nous"))
        assertNull(ownLink("https://hermes-agent.nousresearch.com/docs/user-guide/messaging/"))
        assertEquals("https://t.me/BotFather", ownLink("https://t.me/BotFather"))
    }
}
