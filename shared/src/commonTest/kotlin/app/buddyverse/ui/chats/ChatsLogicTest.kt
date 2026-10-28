package app.buddyverse.ui.chats

import app.buddyverse.core.api.searchHit
import app.buddyverse.core.api.stripFts
import app.buddyverse.core.params
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatsLogicTest {
    private val h = 3_600_000L
    private val dayMs = 86_400_000L
    // 10:00 local in a UTC+3 zone (07:00Z) on day D.
    private val utcMidnight = 20_000 * dayMs
    private val now = utcMidnight + 7 * h
    private fun sec(ms: Long) = ms / 1000.0

    @Test fun daysFollowLocalMidnight() {
        val off = 3 * h
        assertEquals(Day.Today, dayOf(sec(now - h), now, off))
        assertEquals(Day.Today, dayOf(sec(utcMidnight - 2 * h), now, off)) // 01:00 local today, previous UTC day
        assertEquals(Day.Yesterday, dayOf(sec(utcMidnight - 4 * h), now, off)) // 23:00 local yesterday
        assertEquals(Day.Earlier, dayOf(sec(now - 3 * dayMs), now, off))
        assertEquals(Day.Today, dayOf(sec(now + h), now, off)) // clock skew
    }

    @Test fun swipeSettles() {
        val w = 1000f; val reveal = 260f; val fling = 2500f
        assertEquals(Settle.Commit, swipeSettle(600f, w, 0f, reveal, fling))
        assertEquals(Settle.Commit, swipeSettle(200f, w, 3000f, reveal, fling))
        assertEquals(Settle.Back, swipeSettle(400f, w, -3000f, reveal, fling))
        assertEquals(Settle.Open, swipeSettle(200f, w, 0f, reveal, fling))
        assertEquals(Settle.Back, swipeSettle(100f, w, 0f, reveal, fling))
    }

    @Test fun searchHitResumesTheTip() {
        val hit = searchHit(params("id" to "old", "session_id" to "tip", "profile" to "nova", "title" to "Login fix",
            "snippet" to "fix the >>>login<<<\n button", "last_active" to 5.0, "archived" to true))!!
        assertEquals("tip", hit.row.id)
        assertEquals("nova", hit.row.profile)
        assertEquals(true, hit.row.archived)
        assertEquals("fix the login button", hit.snippet)
        assertEquals("a b", stripFts(" >>>a<<<  b "))
    }
}
