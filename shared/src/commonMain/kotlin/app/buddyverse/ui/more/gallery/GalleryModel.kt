package app.buddyverse.ui.more.gallery

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import app.buddyverse.buddy.BuddyBrain
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.MotionLevel
import app.buddyverse.buddy.Pose
import app.buddyverse.buddy.seedOf
import kotlin.math.abs
import kotlin.math.max

/** Display order of the mood rows: everyday states first, the quiet ones last. */
val galleryMoods = listOf(Mood.Idle, Mood.Listening, Mood.Thinking, Mood.Working, Mood.Talking, Mood.NeedsYou, Mood.Error, Mood.Sleeping, Mood.Offline)

/** One gallery buddy: a shape in a mood, colored so neighbors in a row and a column differ. */
@Immutable
data class Cell(val shape: BuddyShape, val mood: Mood, val color: Int) {
    val spec: BuddySpec = BuddySpec("gallery:${shape.name}:${mood.name}", shape.name, shape, BuddyColors.palette[color])
}

/** 8 shapes × 9 moods (design §4.9.10). Color index walks the palette by 3 per row so no column repeats a color. */
fun galleryCells(): List<Cell> = galleryMoods.flatMapIndexed { mi, m ->
    BuddyShape.entries.mapIndexed { si, s -> Cell(s, m, (si + mi * 3) % BuddyColors.palette.size) }
}

/**
 * How long a pose has gone unchanged (design §13: "no buddy still for more than 4 s"). Reads the same [Pose] the draw code
 * paints, field by field, into a reused array: no allocation per sample.
 */
class StillMeter(private val eps: Float = 1e-4f) {
    private val last = FloatArray(FIELDS)
    private val cur = FloatArray(FIELDS)
    private var since = -1f
    /** The longest still stretch seen so far, seconds. */
    var longest = 0f
        private set

    /** Feed the pose at [t] seconds; returns how long it has been still now. */
    fun sample(p: Pose, t: Float): Float {
        p.into(cur)
        var moved = since < 0f
        if (!moved) for (i in 0 until FIELDS) if (abs(cur[i] - last[i]) > eps) { moved = true; break }
        cur.copyInto(last)
        if (moved) since = t
        val still = t - since
        longest = max(longest, still)
        return still
    }

    private fun Pose.into(a: FloatArray) {
        a[0] = lift; a[1] = sx; a[2] = sy; a[3] = tilt; a[4] = shakeX; a[5] = spin; a[6] = scale
        a[7] = gazeX; a[8] = gazeY; a[9] = eyeOpenL; a[10] = eyeOpenR; a[11] = lidTop; a[12] = lidBottom
        a[13] = lidTilt; a[14] = eyeScale; a[15] = mouthOpen; a[16] = smile
    }

    private companion object { const val FIELDS = 17 }
}

/** p95 of frame intervals in ms; [intervals] is a ring of nanos, [n] how many are filled. */
fun p95Ms(intervals: LongArray, n: Int): Float {
    if (n <= 0) return 0f
    val sorted = intervals.copyOf(n).also { it.sort() }
    return sorted[((n - 1) * 95) / 100] / 1_000_000f
}

/** The alive check's readout; written about once a second, so the page recomposes rarely. */
@Stable
class AliveCheck {
    var running by mutableIntStateOf(0)
    var longest by mutableFloatStateOf(0f)
    var p95 by mutableFloatStateOf(0f)
    val pass: Boolean get() = longest <= 4f
}

/**
 * Twin brains for every cell, stepped off the frame clock at ~10 Hz with the same seed, mood, motion and size as the
 * buddies on screen, so the check measures the very pose function they draw. Also times every frame for the p95.
 * Runs until cancelled (leave the page, change motion or size).
 */
suspend fun runAliveCheck(cells: List<Cell>, motion: MotionLevel, sizeDp: Float, out: AliveCheck) {
    val brains = cells.map { c ->
        BuddyBrain(seedOf(c.spec.id)).apply { requestedMood = c.mood; this.motion = motion; this.sizeDp = sizeDp }
    }
    val meters = cells.map { StillMeter() }
    val ring = LongArray(240)
    var filled = 0
    var head = 0
    var prev = 0L
    var start = 0L
    var lastStep = 0L
    var lastTalk = 0L
    var lastPublish = 0L
    out.running = cells.size
    while (true) withFrameNanos { now ->
        if (start == 0L) { start = now; lastPublish = now }
        if (prev != 0L) { ring[head] = now - prev; head = (head + 1) % ring.size; if (filled < ring.size) filled++ }
        prev = now
        if (now - lastStep >= 100_000_000L) {
            lastStep = now
            val talk = now - lastTalk >= 110_000_000L
            if (talk) lastTalk = now
            val t = (now - start) / 1e9f
            for (i in brains.indices) {
                val b = brains[i]
                if (talk && cells[i].mood == Mood.Talking) b.talk(9)
                meters[i].sample(b.step(now), t)
            }
        }
        if (now - lastPublish >= 1_000_000_000L) {
            lastPublish = now
            out.longest = meters.maxOf { it.longest }
            out.p95 = p95Ms(ring, filled)
        }
    }
}
