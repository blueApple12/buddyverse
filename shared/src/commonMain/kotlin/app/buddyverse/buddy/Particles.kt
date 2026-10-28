package app.buddyverse.buddy

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Struct-of-arrays particle pool (design §4.8): 24 per buddy, 120 app-wide, nothing allocated per frame.
 * Positions are in dp relative to the buddy's center; y grows downward.
 */
class Particles {
    val x = FloatArray(MAX); val y = FloatArray(MAX); val vx = FloatArray(MAX); val vy = FloatArray(MAX)
    val life = FloatArray(MAX); val maxLife = FloatArray(MAX); val size = FloatArray(MAX); val rot = FloatArray(MAX)
    val type = IntArray(MAX); val color = IntArray(MAX)
    var count = 0
        private set

    fun emit(kind: Int, n: Int, rng: Rng, buddyDp: Float) {
        repeat(n) {
            if (count >= MAX || live >= GLOBAL_MAX) return
            val i = count++
            live++
            type[i] = kind; color[i] = (rng.next() * 12).toInt(); rot[i] = rng.range(0f, 360f)
            val w = buddyDp
            when (kind) {
                CONFETTI -> {
                    val a = rng.range(-120f, -60f) * PI.toFloat() / 180f
                    val v = rng.range(180f, 320f) * (w / 96f).coerceIn(.5f, 1.2f)
                    x[i] = 0f; y[i] = -w * .3f; vx[i] = cos(a) * v; vy[i] = sin(a) * v; maxLife[i] = 1.1f; size[i] = 4f
                }
                SPARK -> { val a = rng.range(0f, 2f * PI.toFloat()); x[i] = 0f; y[i] = -w * .2f; vx[i] = cos(a) * 120f; vy[i] = sin(a) * 120f; maxLife[i] = .4f; size[i] = 2f }
                HEART -> { x[i] = rng.range(-w * .3f, w * .3f); y[i] = -w * .45f; vx[i] = 0f; vy[i] = -40f; maxLife[i] = .9f; size[i] = 8f }
                Z -> { x[i] = w * .3f; y[i] = -w * .45f; vx[i] = 6f; vy[i] = -w * .3f; maxLife[i] = 2f; size[i] = (w * .16f).coerceIn(6f, 16f) }
                POOF -> { val a = rng.range(0f, 2f * PI.toFloat()); x[i] = 0f; y[i] = 0f; vx[i] = cos(a) * 90f; vy[i] = sin(a) * 90f; maxLife[i] = .5f; size[i] = rng.range(4f, 8f) }
                SWEAT -> { x[i] = w * .32f; y[i] = -w * .25f; vx[i] = 0f; vy[i] = 25f; maxLife[i] = .8f; size[i] = 5f }
            }
            life[i] = maxLife[i]
        }
    }

    fun step(dt: Float) {
        var i = 0
        while (i < count) {
            life[i] -= dt
            if (life[i] <= 0f) { removeAt(i); continue }
            when (type[i]) {
                CONFETTI -> { vy[i] += 900f * dt; vx[i] *= .98f; vy[i] *= .98f; rot[i] += 360f * dt }
                HEART -> vx[i] = 6f * cos(life[i] * 7f)
                POOF -> { vx[i] *= .9f; vy[i] *= .9f }
            }
            x[i] += vx[i] * dt; y[i] += vy[i] * dt
            i++
        }
    }

    /** Release this pool's share of the app-wide cap (buddy left the screen). */
    fun clear() { live -= count; count = 0 }

    fun alpha(i: Int): Float = (life[i] / maxLife[i]).coerceIn(0f, 1f)

    private fun removeAt(i: Int) {
        val last = --count
        live--
        if (i != last) {
            x[i] = x[last]; y[i] = y[last]; vx[i] = vx[last]; vy[i] = vy[last]; life[i] = life[last]; maxLife[i] = maxLife[last]
            size[i] = size[last]; rot[i] = rot[last]; type[i] = type[last]; color[i] = color[last]
        }
    }

    companion object {
        const val MAX = 24
        const val GLOBAL_MAX = 120
        const val CONFETTI = 0; const val SPARK = 1; const val HEART = 2; const val Z = 3; const val POOF = 4; const val SWEAT = 5
        /** Live particles across every buddy (UI thread only). */
        var live = 0
            private set
    }
}
