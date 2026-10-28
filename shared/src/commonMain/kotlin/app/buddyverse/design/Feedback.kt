package app.buddyverse.design

import androidx.compose.runtime.staticCompositionLocalOf
import app.buddyverse.core.nowMillis
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** design §5.2. Mapped to `View.performHapticFeedback` constants on Android; no-op on desktop. */
enum class Haptic { Press, Tick, Confirm, Reject, LongPress }

/** design §6. Tiny synthesized UI sounds; at most one every 700 ms. */
enum class Sound { Bloop, Pop, BoopBoop, Plonk, Bonk }

interface Feedback {
    fun haptic(h: Haptic)
    fun sound(s: Sound)
}

object NoFeedback : Feedback {
    override fun haptic(h: Haptic) = Unit
    override fun sound(s: Sound) = Unit
}

val LocalFeedback = staticCompositionLocalOf<Feedback> { NoFeedback }

/** Gates platform feedback with the user's Sounds / Haptics switches, the 700 ms sound limit, and the foreground (no sound in the background, design §6). */
class GatedFeedback(
    private val platform: Feedback, private val soundsOn: () -> Boolean, private val hapticsOn: () -> Boolean,
    private val foreground: () -> Boolean = { true },
) : Feedback {
    private var lastSound = 0L
    override fun haptic(h: Haptic) { if (hapticsOn() && foreground()) platform.haptic(h) }
    override fun sound(s: Sound) {
        val now = nowMillis()
        if (!soundsOn() || !foreground() || now - lastSound < 700) return
        lastSound = now
        platform.sound(s)
    }
}

/** Renders each sound once into 16-bit mono PCM (design §6 recipes). */
object Synth {
    const val RATE = 22050

    fun render(s: Sound): ShortArray = when (s) {
        Sound.Bloop -> tone(ms = 150, level = db(-18f), attackMs = 5f, decayMs = 140f, harmonic = db(-14f)) { t -> glide(660f, 440f, t, .09f) }
        Sound.Pop -> tone(ms = 60, level = db(-22f), attackMs = 2f, decayMs = 50f) { t -> glide(900f, 1250f, t, .045f) }
        Sound.Plonk -> tone(ms = 130, level = db(-22f), attackMs = 3f, decayMs = 120f) { t -> glide(520f, 600f, t, .07f) }
        Sound.Bonk -> tone(ms = 130, level = db(-20f), attackMs = 3f, decayMs = 100f, triangle = true) { t -> glide(220f, 160f, t, .12f) }
        Sound.BoopBoop -> {
            val a = tone(ms = 80, level = db(-18f), attackMs = 5f, decayMs = 70f, flatDecay = true) { 520f }
            val b = tone(ms = 80, level = db(-18f), attackMs = 5f, decayMs = 70f, flatDecay = true) { 780f }
            a + ShortArray(RATE * 60 / 1000) + b
        }
    }

    private fun db(v: Float) = 10f.pow(v / 20f)

    /** Exponential glide from [f0] to [f1] over [dur] seconds, then hold. */
    private fun glide(f0: Float, f1: Float, t: Float, dur: Float): Float = f0 * exp(ln(f1 / f0) * min(1f, t / dur))

    private inline fun tone(ms: Int, level: Float, attackMs: Float, decayMs: Float, harmonic: Float = 0f, triangle: Boolean = false,
        flatDecay: Boolean = false, freq: (Float) -> Float): ShortArray {
        val n = RATE * ms / 1000
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i / RATE.toFloat()
            phase += 2 * PI * freq(t) / RATE
            val env = (if (t * 1000 < attackMs) t * 1000 / attackMs else 1f) *
                (if (flatDecay) (1f - (t * 1000 / ms).coerceIn(0f, 1f)).pow(.6f) else exp(-(t * 1000 - attackMs).coerceAtLeast(0f) / (decayMs / 4.6f)))
            val wave = if (triangle) (2 / PI * kotlin.math.asin(sin(phase))).toFloat() else sin(phase).toFloat()
            val v = (wave + harmonic * sin(2 * phase).toFloat()) * env * level
            out[i] = (v.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /** Wraps PCM into a WAV file (SoundPool and javax.sound read it directly). */
    fun wav(pcm: ShortArray, rate: Int = RATE): ByteArray {
        val data = pcm.size * 2
        val b = ByteArray(44 + data)
        fun i32(o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte() }
        fun i16(o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
        "RIFF".encodeToByteArray().copyInto(b, 0); i32(4, 36 + data); "WAVE".encodeToByteArray().copyInto(b, 8)
        "fmt ".encodeToByteArray().copyInto(b, 12); i32(16, 16); i16(20, 1); i16(22, 1); i32(24, rate); i32(28, rate * 2); i16(32, 2); i16(34, 16)
        "data".encodeToByteArray().copyInto(b, 36); i32(40, data)
        pcm.forEachIndexed { i, s -> i16(44 + i * 2, s.toInt()) }
        return b
    }

    fun peak(pcm: ShortArray): Float = pcm.maxOf { abs(it.toInt()) } / Short.MAX_VALUE.toFloat()
}
