package app.buddyverse.buddy

import kotlin.math.abs

/**
 * Reads a drag as play: quick small back-and-forth strokes are a tickle, quick big ones a shake.
 * Feed it each drag delta in dp with its time; it answers once per gesture it recognizes. No allocation per event.
 */
class DragFeel {
    enum class Feel { Tickle, Shake }

    private val dir = IntArray(2)       // current stroke direction per axis: -1, 0, 1
    private val run = FloatArray(2)     // distance of the current stroke per axis
    private val back = FloatArray(2)    // movement against the stroke, until it counts as a turn
    private val turnAt = LongArray(4)
    private val turnLen = FloatArray(4)
    private var turns = 0
    private var coolUntil = 0L

    fun reset() { dir.fill(0); run.fill(0f); back.fill(0f); turns = 0 }

    fun add(dx: Float, dy: Float, tMs: Long): Feel? {
        axis(0, dx, tMs); axis(1, dy, tMs)
        if (turns < 3 || tMs < coolUntil) return null
        // Three turns inside 900 ms: a wiggle. Its stroke length tells a tickle from a shake.
        var n = 0; var len = 0f
        for (k in 0 until min(turns, turnAt.size)) if (tMs - turnAt[k] <= 900) { n++; len += turnLen[k] }
        if (n < 3) return null
        turns = 0; coolUntil = tMs + 700
        return if (len / n >= SHAKE_DP) Feel.Shake else Feel.Tickle
    }

    private fun axis(a: Int, d: Float, tMs: Long) {
        if (d == 0f) return
        val s = if (d > 0) 1 else -1
        if (dir[a] == 0 || s == dir[a]) { dir[a] = s; run[a] += abs(d); back[a] = 0f; return }
        back[a] += abs(d)
        if (back[a] < TURN_DP) return
        if (run[a] >= MIN_STROKE_DP) { val k = turns % turnAt.size; turnAt[k] = tMs; turnLen[k] = run[a]; turns++ }
        dir[a] = s; run[a] = back[a]; back[a] = 0f
    }

    private fun min(a: Int, b: Int) = if (a < b) a else b

    companion object {
        const val TURN_DP = 3f
        const val MIN_STROKE_DP = 6f
        const val SHAKE_DP = 40f
    }
}
