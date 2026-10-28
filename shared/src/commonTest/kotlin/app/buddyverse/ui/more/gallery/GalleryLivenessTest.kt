package app.buddyverse.ui.more.gallery

import app.buddyverse.buddy.BuddyBrain
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.MotionLevel
import app.buddyverse.buddy.Pose
import app.buddyverse.buddy.seedOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** design §13 acceptance, as a unit test: under Full and Calm motion, no gallery buddy holds a pose for more than 4 s. */
class GalleryLivenessTest {
    private val frame = 16_666_667L

    @Test fun everyShapeInEveryMoodKeepsMoving() {
        val cells = galleryCells()
        assertEquals(72, cells.size)
        for (motion in listOf(MotionLevel.Full, MotionLevel.Calm)) for (cell in cells) {
            val b = BuddyBrain(seedOf(cell.spec.id)).apply { requestedMood = cell.mood; this.motion = motion; sizeDp = 48f }
            val m = StillMeter()
            var n = 1L
            repeat(60 * 20) { f ->
                if (cell.mood == Mood.Talking && f % 7 == 0) b.talk(9)
                m.sample(b.step(n), n / 1e9f)
                n += frame
            }
            assertTrue(m.longest <= 4f, "${cell.shape} ${cell.mood} ($motion) stood still ${m.longest}s")
        }
    }

    @Test fun meterCountsStillnessAndResetsOnMotion() {
        val p = Pose()
        val m = StillMeter()
        assertEquals(0f, m.sample(p, 0f))
        assertEquals(2f, m.sample(p, 2f))
        p.sy = 1.01f
        assertEquals(0f, m.sample(p, 3f))
        assertEquals(2f, m.longest)
    }

    @Test fun p95OfFrameIntervals() {
        val ring = LongArray(100) { if (it < 94) 8_000_000L else 30_000_000L }
        assertEquals(30f, p95Ms(ring, 100))
        assertEquals(0f, p95Ms(ring, 0))
    }
}
