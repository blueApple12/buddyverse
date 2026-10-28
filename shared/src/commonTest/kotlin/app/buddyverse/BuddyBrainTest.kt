package app.buddyverse

import app.buddyverse.buddy.BuddyBrain
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.MotionLevel
import app.buddyverse.buddy.OneShot
import app.buddyverse.buddy.Particles
import app.buddyverse.buddy.Rng
import app.buddyverse.buddy.Spring
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuddyBrainTest {
    private val frame = 16_666_667L

    /** Run [seconds] at 60 fps, calling [each] with (t, brain) after every frame. */
    private fun BuddyBrain.run(seconds: Float, startNanos: Long = 1L, each: (Float, BuddyBrain) -> Unit = { _, _ -> }): Long {
        var n = startNanos
        val frames = (seconds * 60).toInt()
        repeat(frames) { i -> n += frame; step(n); each(i / 60f, this) }
        return n
    }

    @Test fun priorityOrder() {
        assertEquals(Mood.NeedsYou, Mood.strongest(Mood.Working, Mood.NeedsYou, Mood.Talking))
        assertEquals(Mood.Error, Mood.strongest(Mood.Talking, Mood.Error))
        assertEquals(Mood.Talking, Mood.strongest(Mood.Thinking, Mood.Talking, Mood.Working))
        assertEquals(Mood.Idle, Mood.strongest())
    }

    /** Acceptance (design §13): no buddy is still for more than 4 s, in any mood. */
    @Test fun neverStillForFourSeconds() {
        for (mood in Mood.entries) {
            val b = BuddyBrain(mood.ordinal + 11).apply { requestedMood = mood }
            val samples = mutableListOf<FloatArray>()
            b.run(12f) { _, br -> val p = br.pose; samples += floatArrayOf(p.sx, p.sy, p.lift, p.tilt, p.gazeX, p.gazeY, p.eyeOpenL) }
            for (start in 0 until samples.size - 240 step 30) {
                val window = samples.subList(start, start + 240)
                val moved = (0 until 7).any { k -> window.maxOf { it[k] } - window.minOf { it[k] } > 1e-3f }
                assertTrue(moved, "$mood still for 4 s at frame $start")
            }
        }
    }

    @Test fun blinksAreIrregular() {
        val b = BuddyBrain(42).apply { requestedMood = Mood.Idle }
        val closes = mutableListOf<Float>()
        var wasClosed = false
        b.run(90f) { t, br -> val closed = br.pose.eyeOpenL < .3f; if (closed && !wasClosed) closes += t; wasClosed = closed }
        assertTrue(closes.size in 12..45, "blinks in 90 s: ${closes.size}")
        val gaps = closes.zipWithNext { a, c -> c - a }
        assertTrue(gaps.max() - gaps.min() > 1.5f, "intervals must vary: $gaps")
        assertTrue(gaps.all { it >= .15f && it <= 6.5f }, "intervals within 2.5–6 s (or a 180 ms double blink): $gaps")
    }

    @Test fun moodDwellsAtLeast400ms() {
        val b = BuddyBrain(3).apply { requestedMood = Mood.Idle }
        var n = b.run(1f)
        b.requestedMood = Mood.Working; n += frame; b.step(n)
        assertEquals(Mood.Working, b.mood)
        b.requestedMood = Mood.Thinking
        repeat(12) { n += frame; b.step(n) } // 200 ms
        assertEquals(Mood.Working, b.mood, "a short flicker must not switch the mood")
        repeat(20) { n += frame; b.step(n) }
        assertEquals(Mood.Thinking, b.mood)
        b.requestedMood = Mood.NeedsYou; n += frame; b.step(n)
        assertEquals(Mood.NeedsYou, b.mood, "needs-you never waits")
    }

    @Test fun longTurnEndsWithDoneShortWithNod() {
        val b = BuddyBrain(5).apply { requestedMood = Mood.Working }
        var n = b.run(4f)
        b.requestedMood = Mood.Idle; n += frame; b.step(n)
        assertEquals(OneShot.Done, b.currentShot)
        val c = BuddyBrain(6).apply { requestedMood = Mood.Working }
        var m = c.run(1f)
        c.requestedMood = Mood.Idle; m += frame; c.step(m)
        assertEquals(OneShot.Nod, c.currentShot)
    }

    @Test fun hopLeavesTheGroundAndLands() {
        val b = BuddyBrain(8).apply { requestedMood = Mood.Idle; motion = MotionLevel.Calm }
        var n = b.run(.5f)
        b.press(); b.release()
        var peak = 0f
        repeat(60) { n += frame; b.step(n); peak = maxOf(peak, b.pose.lift) }
        assertTrue(peak > .1f, "tap hop height ${peak}")
        assertTrue(b.pose.lift < .01f, "landed")
    }

    @Test fun tripleTapGiggles() {
        val b = BuddyBrain(9)
        var n = b.run(.2f)
        repeat(3) { b.press(); b.release(); n += frame * 6; b.step(n) }
        assertEquals(OneShot.Giggle, b.currentShot)
    }

    @Test fun motionOffKeepsBodyStillButStillBlinks() {
        val b = BuddyBrain(10).apply { requestedMood = Mood.Working; motion = MotionLevel.Off }
        var maxLift = 0f; var blinked = false
        b.run(10f) { _, br -> maxLift = maxOf(maxLift, abs(br.pose.lift)); if (br.pose.eyeOpenL < .3f) blinked = true }
        assertEquals(0f, maxLift)
        assertTrue(blinked)
    }

    @Test fun talkingOpensTheMouth() {
        val b = BuddyBrain(12).apply { requestedMood = Mood.Talking }
        var n = b.run(.6f)
        b.talk(12, "hello there")
        var maxMouth = 0f
        repeat(10) { n += frame; b.step(n); maxMouth = maxOf(maxMouth, b.pose.mouthOpen) }
        assertTrue(maxMouth > .3f, "mouth $maxMouth")
    }

    @Test fun particlePoolIsCapped() {
        val p = Particles()
        p.emit(Particles.CONFETTI, 100, Rng(1), 96f)
        assertEquals(Particles.MAX, p.count)
        repeat(200) { p.step(1f / 60f) }
        assertEquals(0, p.count)
        p.clear()
        assertTrue(Particles.live >= 0)
    }

    @Test fun springSettles() {
        val s = Spring(500f, .4f, 0f).apply { target = 1f }
        repeat(600) { s.step(1f / 240f) }
        assertTrue(abs(s.x - 1f) < .01f)
    }

    @Test fun teamsDoNotMoveInSync() {
        val a = BuddyBrain(100); val b = BuddyBrain(200)
        val sa = mutableListOf<Float>(); val sb = mutableListOf<Float>()
        a.run(3f) { _, br -> sa += br.pose.sy }
        b.run(3f) { _, br -> sb += br.pose.sy }
        assertTrue(sa.zip(sb).any { (x, y) -> abs(x - y) > 1e-3f })
    }
}
