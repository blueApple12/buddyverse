package app.buddyverse.buddy

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** design §4.3. Order = priority when several apply (highest last). */
enum class Mood(val priority: Int) {
    Offline(0), Sleeping(1), Idle(2), Listening(3), Thinking(4), Working(5), Talking(6), Error(7), NeedsYou(8);
    val busy: Boolean get() = this == Thinking || this == Working || this == Talking

    companion object {
        fun strongest(vararg moods: Mood?): Mood = moods.filterNotNull().maxByOrNull { it.priority } ?: Idle
    }
}

enum class MotionLevel { Full, Calm, Off }

enum class OneShot { Tap, Giggle, Dizzy, Arrive, Leave, Done, Nod, Greeting, Wave, Spawn, Return, Startle, Learned, Hop, LookAround, Wiggle, Stretch, Yawn, Pet,
    /** Play: a hopping 360 turn, a little dance, a tickled laugh, carried by a finger, and the bouncy landing after. */
    Spin, Dance, Laugh, Lift, Boing }

/** Twelve face poses (design §4.2). */
enum class Expr(
    val open: Float, val lidTop: Float, val lidBottom: Float, val tilt: Float, val eyeScale: Float,
    val round: Float, val mouth: Float, val smile: Float, val cheeks: Float, val winkRight: Float = 1f,
) {
    Neutral(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f),
    Happy(1f, 0f, .55f, 0f, 1f, 0f, 0f, .6f, 1f),
    Excited(1f, 0f, .35f, 0f, 1.12f, 0f, .5f, .4f, 1f),
    Curious(1f, .15f, 0f, -6f, 1.05f, 0f, 0f, 0f, 0f),
    Focused(.85f, .35f, 0f, 4f, .95f, 0f, 0f, 0f, 0f),
    Surprised(1f, 0f, 0f, 0f, 1.2f, 1f, .35f, 0f, 0f),
    Sleepy(.25f, .6f, 0f, -4f, 1f, 0f, 0f, 0f, 0f),
    Sad(1f, .2f, 0f, -18f, .9f, 0f, 0f, -.5f, 0f),
    Worried(1f, .1f, 0f, -10f, 1.05f, 0f, 0f, -.2f, 0f),
    Angry(1f, .3f, 0f, 22f, .95f, 0f, 0f, -.3f, 0f),
    Proud(.6f, .4f, .2f, 0f, 1f, 0f, 0f, .8f, 1f),
    Wink(1f, 0f, .5f, 0f, 1f, 0f, 0f, .5f, 1f, winkRight = .08f),
}

/** Semi-implicit Euler spring, stepped by hand: no coroutines, no allocation (design §4.9). */
class Spring(var k: Float, var zeta: Float, var x: Float = 0f) {
    var v = 0f
    var target = x
    fun step(dt: Float) {
        val w = sqrt(k)
        v += (-2f * zeta * w * v - k * (x - target)) * dt
        x += v * dt
    }
    fun snap(value: Float) { x = value; v = 0f; target = value }
    fun impulse(dv: Float) { v += dv }
}

/** Seeded xorshift so each bot is deterministic and distinct. */
class Rng(seed: Int) {
    private var s = if (seed == 0) 0x9E3779B9.toInt() else seed
    fun next(): Float {
        s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
        return (s.toLong() and 0xFFFFFFL).toFloat() / 0x1000000.toFloat()
    }
    fun range(a: Float, b: Float) = a + (b - a) * next()
}

/** Everything the draw code needs for one frame. One instance per buddy, reused (no per-frame allocation). */
class Pose {
    var lift = 0f; var sx = 1f; var sy = 1f; var tilt = 0f; var shakeX = 0f; var spin = 0f; var scale = 1f
    var gazeX = 0f; var gazeY = 0f
    var eyeOpenL = 1f; var eyeOpenR = 1f; var lidTop = 0f; var lidBottom = 0f; var lidTilt = 0f; var eyeScale = 1f; var eyeRound = 0f
    var mouthOpen = 0f; var smile = 0f; var mouthRound = 0f; var cheeks = 0f
    var desaturate = 0f; var shadow = 1f
    var thinkingDots = 0f; var orbit = 0f; var orbitPhase = 0f; var dizzy = 0f
    var time = 0f
}

/**
 * The living part of a buddy: mood loop + one-shot + gaze + lids + mouth, all springs (design §4.3–4.7).
 * Pure and platform-free; the composable calls [step] in the draw phase only.
 */
class BuddyBrain(seed: Int) {
    private val rng = Rng(seed)
    private val phase = rng.range(0f, 10f)
    private val jitter = rng.range(.9f, 1.1f)
    val pose = Pose()
    val particles = Particles()
    var motion = MotionLevel.Full
    var sizeDp = 48f
    /** Set by the composable every recomposition; the shown mood follows it with a 400 ms dwell. */
    var requestedMood = Mood.Idle
    var mood = Mood.Idle
        private set
    private var moodSince = 0f
    private var busySince = -1f
    private var startNanos = -1L
    private var lastT = 0f

    // springs
    private val squashX = Spring(500f, .4f, 1f)
    private val squashY = Spring(500f, .4f, 1f)
    private val nod = Spring(500f, .5f)
    private val gx = Spring(900f, .9f)
    private val gy = Spring(900f, .9f)
    private val mouthSpring = Spring(900f, .45f)
    private val expr = Array(9) { Spring(260f, .85f) } // open lidTop lidBottom tilt eyeScale round mouth smile cheeks
    private var exprNow = Expr.Neutral
    private var exprUntil = 0f
    private var exprOverride: Expr? = null
    private var winkR = Spring(260f, .85f, 1f)

    // gaze
    private var sacTarget = floatArrayOf(0f, 0f)
    private var nextSaccade = 0f
    private var pursuitUntil = 0f
    var lookX = 0f; var lookY = 0f
    /** A point to look at in buddy-relative unit space (finger/composer), or NaN. Written by the composable. */
    var focusX = Float.NaN; var focusY = Float.NaN

    // blink
    private var blinkAt = 1f
    private var blinkStart = -1f
    private var doubleBlinkPending = false

    // one-shot
    private var shot: OneShot? = null
    private var shotStart = 0f
    private var hopHeight = 0f
    private var nextFidget = 0f
    private var idleSince = 0f
    private var mouthTarget = 0f
    private var lastTalk = -10f
    private var roundMouth = false
    private var pressedAt = -1f
    private val taps = FloatArray(6) { -10f }
    private var tapIdx = 0
    private var lastZ = 0f
    private var errorAt = -10f
    private var nextHopAt = 0f
    /** Layout direction: "reading direction" gazes flip in RTL; finger gaze never does (design §9.8). */
    var rtl = false

    fun trigger(s: OneShot) {
        if (motion == MotionLevel.Off && s != OneShot.Arrive) return
        shot = s; shotStart = lastT
        when (s) {
            OneShot.Tap -> { hopHeight = .15f; face(Expr.Excited, .7f) } // squish + giggle
            OneShot.Giggle -> { face(Expr.Wink, 1f); burst(Particles.HEART, 3) }
            OneShot.Dizzy -> face(Expr.Surprised, 1.5f)
            OneShot.Arrive -> face(Expr.Excited, .8f)
            OneShot.Leave -> face(Expr.Sleepy, 1.4f)
            OneShot.Done -> { hopHeight = .18f; face(Expr.Happy, 1.2f); burst(Particles.CONFETTI, 12) }
            OneShot.Nod -> face(Expr.Happy, .6f)
            OneShot.Greeting -> { hopHeight = .08f; face(Expr.Happy, .8f); pursuitUntil = lastT + .6f; focusX = 0f; focusY = 0f }
            OneShot.Wave -> face(Expr.Happy, .8f)
            OneShot.Spawn, OneShot.Return -> Unit
            OneShot.Startle -> { hopHeight = .05f; face(Expr.Surprised, .25f) }
            OneShot.Learned -> { face(Expr.Proud, 1f); burst(Particles.SPARK, 6) }
            OneShot.Hop -> hopHeight = .05f
            OneShot.LookAround, OneShot.Wiggle -> Unit
            OneShot.Stretch -> Unit
            OneShot.Yawn -> face(Expr.Sleepy, .9f)
            OneShot.Pet -> { face(Expr.Happy, 3f); nextHeart = lastT; petting = true }
            OneShot.Spin -> { hopHeight = .10f; face(Expr.Happy, .9f) }
            OneShot.Dance -> face(Expr.Happy, 2f)
            OneShot.Laugh -> { face(Expr.Excited, 1.3f); burst(Particles.SPARK, 4) }
            OneShot.Lift -> face(Expr.Excited, 60f)
            OneShot.Boing -> { hopHeight = .12f; face(Expr.Happy, .9f) }
        }
    }

    // Pet (design §4.5): held long press, max 3 s: wiggle ±3° at 6 Hz, a heart every 300 ms, happy with cheeks.
    private var petting = false
    private var nextHeart = 0f
    val isPetting: Boolean get() = petting && shot == OneShot.Pet

    /** Long press began: pet until [endPet] (or 3 s). */
    fun pet() { pressedAt = -1f; squashX.target = 1f; squashY.target = 1f; trigger(OneShot.Pet) }

    fun endPet() { petting = false; if (shot == OneShot.Pet) shot = null; exprUntil = lastT + .4f }

    /** Touch-down squash (design §4.5 Tap). */
    fun press() { pressedAt = lastT; squashX.target = 1.12f; squashY.target = .88f }

    fun release() {
        pressedAt = -1f; squashX.target = 1f; squashY.target = 1f
        taps[tapIdx] = lastT; tapIdx = (tapIdx + 1) % taps.size
        val within = { w: Float -> taps.count { lastT - it <= w } }
        when {
            within(1.5f) >= 5 -> trigger(OneShot.Dizzy)
            within(.9f) >= 3 -> trigger(OneShot.Giggle)
            else -> trigger(OneShot.Tap)
        }
    }

    fun cancelPress() { pressedAt = -1f; squashX.target = 1f; squashY.target = 1f }

    /** A reply chunk arrived (design §4.4). */
    fun talk(chars: Int, text: CharSequence? = null) {
        val energy = min(1f, chars / 12f)
        mouthTarget = .25f + .6f * energy
        nod.impulse(-30f * energy)
        lastTalk = lastT
        if (text != null && text.isNotEmpty()) {
            var round = 0
            for (c in text) if (c == 'a' || c == 'o' || c == 'A' || c == 'O' || c == 'ו' || c == 'ע' || c == 'א') round++
            roundMouth = round * 4 > text.length
        }
    }

    private fun face(e: Expr, seconds: Float) { exprOverride = e; exprUntil = lastT + seconds }

    private fun burst(type: Int, n: Int) { if (motion == MotionLevel.Full && sizeDp >= 40f) particles.emit(type, n, rng, sizeDp) }

    fun step(nanos: Long): Pose {
        if (startNanos < 0) startNanos = nanos
        val t = (nanos - startNanos) / 1e9f + phase
        var dt = t - lastT
        if (dt <= 0f || lastT == 0f) dt = 1f / 60f
        dt = min(dt, .05f) // cap after a pause
        lastT = t
        updateMood(t)
        var remaining = dt
        while (remaining > 0f) { val h = min(remaining, 1f / 240f); integrate(h); remaining -= h }
        compose(t)
        particles.step(dt)
        return pose
    }

    private fun updateMood(t: Float) {
        val want = requestedMood
        if (want != mood && (t - moodSince >= .4f || want == Mood.NeedsYou || mood == Mood.Offline)) {
            val prev = mood
            mood = want; moodSince = t
            onMoodChange(prev, want, t)
        }
        if (mood == Mood.Idle) { if (idleSince == 0f) idleSince = t } else idleSince = 0f
    }

    private fun onMoodChange(prev: Mood, now: Mood, t: Float) {
        if (now.busy && busySince < 0) busySince = t
        when {
            prev == Mood.Offline || prev == Mood.Sleeping -> if (now != Mood.Offline && now != Mood.Sleeping) trigger(OneShot.Arrive)
            prev.busy && (now == Mood.Idle || now == Mood.Listening) -> {
                trigger(if (busySince >= 0 && t - busySince >= 3f) OneShot.Done else OneShot.Nod)
            }
            now == Mood.Error -> { errorAt = t; burst(Particles.SWEAT, 1) }
            now == Mood.NeedsYou -> { trigger(OneShot.Hop); nextHopAt = t + 1.8f }
        }
        if (!now.busy) busySince = -1f
        scheduleBlink(t)
    }

    private fun integrate(h: Float) {
        squashX.step(h); squashY.step(h); nod.step(h); gx.step(h); gy.step(h); winkR.step(h)
        // mouth target decays back to 0 at 12/s
        mouthTarget = max(0f, mouthTarget - 12f * h * mouthTarget.coerceAtLeast(.05f))
        mouthSpring.target = mouthTarget
        mouthSpring.step(h)
        for (s in expr) s.step(h)
    }

    private fun compose(t: Float) {
        val p = pose
        p.time = t
        val calm = motion != MotionLevel.Full
        val still = motion == MotionLevel.Off
        var lift = 0f; var sx = 1f; var sy = 1f; var tilt = 0f; var shake = 0f; var spin = 0f; var scale = 1f
        val m = mood
        // ---- mood loop (design §4.3 table)
        if (!still) when (m) {
            Mood.Idle -> breathe(t, .018f, 3.6f).let { sy = 1 + it; sx = 1 - it / 2 }
            Mood.Listening -> { breathe(t, .012f, 3f).let { sy = 1 + it; sx = 1 - it / 2 }; tilt = 3f }
            Mood.Thinking -> { breathe(t, .010f, 3f).let { sy = 1 + it; sx = 1 - it / 2 }; tilt = 3f * sin(2f * PI.toFloat() * t / (2.4f * jitter)) }
            Mood.Working -> {
                // Busy loops that change every few seconds (bob, typing, shuffle), crossfaded so a switch never snaps.
                if (t >= nextWork) { workPrev = workMode; workMode = (workMode + 1 + (rng.next() * 2f).toInt()) % 3; workAt = t; nextWork = t + rng.range(3f, 6f) }
                workPose(workMode, t, wa); workPose(workPrev, t, wb)
                val k = min(1f, (t - workAt) / .4f)
                lift = lerp(wb[0], wa[0], k); sx = lerp(wb[1], wa[1], k); sy = lerp(wb[2], wa[2], k); tilt = lerp(wb[3], wa[3], k)
                if (!calm && shot == null && t >= nextWorkSpin) { trigger(OneShot.Spin); nextWorkSpin = t + rng.range(9f, 18f) }
            }
            Mood.Talking -> breathe(t, .012f, 3f).let { sy = 1 + it; sx = 1 - it / 2 }
            Mood.NeedsYou -> {
                breathe(t, .014f, 2.4f).let { sy = 1 + it; sx = 1 - it / 2 }
                if (shot == null && t >= nextHopAt) { trigger(OneShot.Hop); nextHopAt = t + if (t - moodSince > 10f) 4f else 1.8f }
            }
            Mood.Error -> {
                val e = t - errorAt
                if (e < .42f) shake = .05f * sin(2f * PI.toFloat() * e / .085f) * (1f - e / .42f)
                // Droop, but keep breathing slowly: a sad buddy is still alive (rule 1).
                val br = breathe(t, .010f, 4.2f)
                sy = .96f + br; sx = 1.02f - br / 2; tilt = 2f * sin(2f * PI.toFloat() * t / (5f * jitter))
            }
            Mood.Sleeping, Mood.Offline -> { breathe(t, .028f, 4.6f).let { sy = 1 + it; sx = 1 - it / 2 }; tilt = 4f }
        }
        if (calm && m == Mood.Working) { lift *= .5f }

        // ---- one-shot
        val s = shot
        if (s != null) {
            val e = t - shotStart
            when (s) {
                OneShot.Tap, OneShot.Done, OneShot.Greeting, OneShot.Startle, OneShot.Hop, OneShot.Spin, OneShot.Boing -> {
                    val hp = hop(e, hopHeight)
                    if (hp == null) { shot = null; squashX.impulse(4f); squashY.impulse(-4f) } else {
                        lift += hp[0]; sx *= hp[1]; sy *= hp[2]
                        if (s == OneShot.Spin) spin = 360f * easeInOut(e / .49f)
                        if (s == OneShot.Tap) tilt += 6f * sin(6f * PI.toFloat() * e / .49f) * (1f - e / .49f) // the giggle wobble
                    }
                }
                OneShot.Dance -> if (e < 1.9f) {
                    // Sway and bounce on the beat (0.6 s), then a twirl to finish.
                    val beat = 2f * PI.toFloat() * e / .6f
                    tilt += 10f * sin(beat); shake += .035f * sin(beat)
                    lift += .045f * abs(sin(beat))
                    if (e > 1.45f) spin = 360f * easeInOut((e - 1.45f) / .45f)
                } else shot = null
                OneShot.Laugh -> if (e < 1.2f) {
                    val fade = 1f - e / 1.2f
                    tilt += 7f * sin(2f * PI.toFloat() * 7f * e) * fade
                    sy *= 1f + .05f * sin(2f * PI.toFloat() * 8f * e) * fade
                } else shot = null
                OneShot.Lift -> if (e < 60f) { sy *= 1.07f; sx *= .94f; tilt += 4f * sin(2f * PI.toFloat() * e / 1.1f) } else shot = null
                OneShot.Giggle -> if (e < .6f) spin = 360f * easeOutBack(e / .6f) else shot = null
                OneShot.Dizzy -> if (e < 1.4f) tilt += 8f * sin(2f * PI.toFloat() * e / .3f) else shot = null
                OneShot.Arrive -> {
                    if (e < .62f) { scale = easeOutBack(min(1f, e / .52f)); spin = 360f * easeOutCubic(e / .62f) } else shot = null
                }
                OneShot.Leave -> if (e < 1.4f) scale = 1f - .95f * easeInOut(e / 1.4f) else { scale = .05f; if (e > 1.45f) { burst(Particles.POOF, 6); shot = null } }
                OneShot.Nod -> if (e < .3f) tilt += 6f * sin(PI.toFloat() * e / .3f) else shot = null
                OneShot.Wave -> if (e < .5f) tilt += 8f * sin(4f * PI.toFloat() * e / .5f) else shot = null
                OneShot.Wiggle -> if (e < .6f) tilt += 4f * sin(6f * PI.toFloat() * e / .6f) else shot = null
                OneShot.Stretch -> if (e < .7f) { val k = sin(PI.toFloat() * min(1f, e / .5f)); sy *= 1f + .08f * k; sx *= 1f - .06f * k } else shot = null
                OneShot.LookAround -> if (e < 1.2f) {
                    pursuitUntil = t + .05f; focusX = if (e < .4f) -.9f else if (e < .8f) .9f else 0f; focusY = 0f
                } else { shot = null; focusX = Float.NaN }
                OneShot.Learned, OneShot.Spawn, OneShot.Return -> if (e > .9f) shot = null
                OneShot.Yawn -> if (e > .9f) shot = null
                OneShot.Pet -> if (petting && e < 3f) {
                    tilt += 3f * sin(2f * PI.toFloat() * 6f * e)
                    if (t >= nextHeart) { burst(Particles.HEART, 1); nextHeart = t + .3f }
                } else { shot = null; petting = false }
            }
        }
        // press squash rides on top
        sx *= squashX.x; sy *= squashY.x
        if (still) { lift = 0f; spin = 0f; shake = 0f; tilt = 0f; sx = squashX.x; sy = squashY.x; scale = if (s == OneShot.Arrive) 1f else scale }
        p.lift = lift; p.sx = sx; p.sy = sy; p.tilt = tilt + nod.x * .2f; p.shakeX = shake; p.spin = spin; p.scale = scale

        // ---- face
        val base = moodExpr(m, t)
        val e = exprOverride?.takeIf { t < exprUntil } ?: base.also { exprOverride = null }
        if (e != exprNow) exprNow = e
        expr[0].target = e.open; expr[1].target = e.lidTop; expr[2].target = e.lidBottom; expr[3].target = e.tilt; expr[4].target = e.eyeScale
        expr[5].target = e.round; expr[6].target = e.mouth; expr[7].target = e.smile; expr[8].target = e.cheeks
        winkR.target = e.winkRight
        var open = expr[0].x
        // blink
        val bl = blinkAmount(t, m)
        open *= 1f - bl * .92f
        p.eyeOpenL = open.coerceIn(.06f, 1.1f); p.eyeOpenR = (open * winkR.x).coerceIn(.06f, 1.1f)
        p.lidTop = expr[1].x.coerceIn(0f, .7f); p.lidBottom = expr[2].x.coerceIn(0f, .6f); p.lidTilt = expr[3].x
        p.eyeScale = expr[4].x * (if (m == Mood.Listening) 1.08f else if (m == Mood.NeedsYou) 1.15f else 1f) * (1f + bl * .1f)
        p.eyeRound = expr[5].x.coerceIn(0f, 1f)
        val talking = t - lastTalk < .4f
        p.mouthOpen = max(expr[6].x, if (talking || m == Mood.Talking) mouthSpring.x.coerceIn(0f, 1f) else 0f) +
            (if (m == Mood.NeedsYou) .2f else 0f) + (if (s == OneShot.Yawn) .6f * sin(PI.toFloat() * min(1f, (t - shotStart) / .9f)) else 0f) +
            (if (s == OneShot.Laugh) .25f * (1f + sin(2f * PI.toFloat() * 8f * (t - shotStart))) else 0f)
        p.mouthRound = if (roundMouth) 1f else 0f
        p.smile = expr[7].x
        p.cheeks = expr[8].x.coerceIn(0f, 1f)
        p.desaturate = when (m) { Mood.Offline -> .6f; Mood.Error -> .35f; else -> 0f }
        p.shadow = 1f - .5f * min(1f, lift / .2f)
        p.thinkingDots = if (m == Mood.Thinking && !still) 1f else 0f
        p.orbit = if (m == Mood.Working && busySince >= 0 && t - busySince > 20f && !calm) 1f else 0f
        p.orbitPhase = t
        p.dizzy = if (s == OneShot.Dizzy) 1f else 0f

        gaze(t, m)
        fidgets(t, m)
        if ((m == Mood.Sleeping) && t - lastZ > 2.4f && !calm) { lastZ = t; burst(Particles.Z, 1) }
    }

    private fun breathe(t: Float, a: Float, period: Float) = a * sin(2f * PI.toFloat() * t / (period * jitter))

    private fun moodExpr(m: Mood, t: Float): Expr = when (m) {
        Mood.Idle -> Expr.Neutral
        Mood.Listening -> Expr.Curious
        Mood.Thinking, Mood.Working -> Expr.Focused
        Mood.Talking -> if (((t * .3f + phase) % 2f) < 1f) Expr.Neutral else Expr.Happy
        Mood.NeedsYou -> if (t - moodSince > 10f) Expr.Worried else Expr.Surprised
        Mood.Error -> Expr.Sad
        Mood.Sleeping, Mood.Offline -> Expr.Sleepy
    }

    /** Hop timeline (design §4.5): anticipate 90 ms, launch 220 ms, fall 180 ms. Returns [lift, sx, sy] or null when landed. */
    private val hopOut = FloatArray(3)
    private fun hop(e: Float, h: Float): FloatArray? {
        when {
            e < .09f -> { val k = easeInQuad(e / .09f); hopOut[0] = 0f; hopOut[1] = 1f + .10f * k; hopOut[2] = 1f - .10f * k }
            e < .31f -> { val k = easeOutCubic((e - .09f) / .22f); hopOut[0] = h * k; hopOut[1] = lerp(1.10f, .92f, min(1f, k * 2)); hopOut[2] = lerp(.90f, 1.10f, min(1f, k * 2)) }
            e < .49f -> { val k = easeInQuad((e - .31f) / .18f); hopOut[0] = h * (1f - k); hopOut[1] = lerp(.92f, 1f, k); hopOut[2] = lerp(1.10f, 1f, k) }
            else -> { squashX.x = 1.12f; squashY.x = .88f; return null }
        }
        return hopOut
    }

    private fun scheduleBlink(t: Float) {
        val factor = when (mood) { Mood.Listening -> .8f; Mood.Thinking -> 1.4f; Mood.Working -> 1.2f; Mood.NeedsYou, Mood.Error -> 1.3f; else -> 1f }
        blinkAt = t + rng.range(2.5f, 6f) * factor
    }

    /** 0 = open, 1 = shut. Close 70 ms, hold 40 ms, open 120 ms; irregular interval, 15% double blinks (design §4.7). */
    private fun blinkAmount(t: Float, m: Mood): Float {
        if (m == Mood.Offline) return 1f
        if (m == Mood.Sleeping) return 0f
        if (blinkStart < 0 && t >= blinkAt) blinkStart = t
        if (blinkStart < 0) return 0f
        val e = t - blinkStart
        val slow = if (m == Mood.Error) 1.6f else 1f
        val v = when {
            e < .07f * slow -> easeInQuad(e / (.07f * slow))
            e < .11f * slow -> 1f
            e < .23f * slow -> 1f - easeOutCubic((e - .11f * slow) / (.12f * slow))
            else -> -1f
        }
        if (v < 0f) {
            blinkStart = -1f
            if (!doubleBlinkPending && rng.next() < .15f) { doubleBlinkPending = true; blinkAt = t + .18f } else { doubleBlinkPending = false; scheduleBlink(t) }
            return 0f
        }
        return if (m == Mood.Sleeping) v * .3f else v
    }

    private fun gaze(t: Float, m: Mood) {
        val rtl = if (this.rtl) -1f else 1f
        var tx: Float; var ty: Float
        val hasFocus = !focusX.isNaN() && t < pursuitUntil
        if (hasFocus) { tx = focusX; ty = focusY; gx.k = 140f; gx.zeta = 1f; gy.k = 140f; gy.zeta = 1f }
        else {
            gx.k = 900f; gx.zeta = .9f; gy.k = 900f; gy.zeta = .9f
            when (m) {
                Mood.Thinking -> { tx = .5f + .1f * sin(t * .9f); ty = -.7f + .1f * cos(t * .7f) }
                Mood.Working -> { tx = .6f * sin(2f * PI.toFloat() * t / 1.6f) * rtl; ty = .2f }
                Mood.Talking -> { tx = .5f * rtl; ty = .3f }
                Mood.NeedsYou -> { tx = rng.range(-.03f, .03f); ty = rng.range(-.03f, .03f) }
                Mood.Error -> { tx = 0f; ty = .5f }
                Mood.Sleeping, Mood.Offline -> { tx = 0f; ty = .2f }
                else -> {
                    if (t >= nextSaccade) {
                        if (rng.next() < .4f) { sacTarget[0] = 0f; sacTarget[1] = 0f }
                        else { val a = rng.range(0f, 2f * PI.toFloat()); val r = rng.range(.3f, .9f); sacTarget[0] = cos(a) * r; sacTarget[1] = sin(a) * r * .7f }
                        nextSaccade = t + rng.range(.8f, 3f)
                    }
                    tx = sacTarget[0]; ty = sacTarget[1]
                }
            }
            if (sizeDp >= 96f) { tx += rng.range(-.02f, .02f); ty += rng.range(-.02f, .02f) }
        }
        gx.target = tx.coerceIn(-1f, 1f); gy.target = ty.coerceIn(-1f, 1f)
        pose.gazeX = gx.x.coerceIn(-1.2f, 1.2f); pose.gazeY = gy.x.coerceIn(-1.2f, 1.2f)
    }

    /** External look (finger / mouse / composer) in unit space; holds for 1.5 s. */
    fun lookAt(x: Float, y: Float, holdSeconds: Float = 1.5f) { focusX = x; focusY = y; pursuitUntil = lastT + holdSeconds }

    private fun fidgets(t: Float, m: Mood) {
        if (m != Mood.Idle || motion != MotionLevel.Full || shot != null) { if (nextFidget < t) nextFidget = t + rng.range(3.5f, 9f); return }
        if (nextFidget == 0f) { nextFidget = t + rng.range(1.5f, 6f); return }
        if (t < nextFidget) return
        nextFidget = t + rng.range(3.5f, 9f)
        val idleLong = idleSince > 0f && t - idleSince > 120f
        var pick = fidgetPick(rng.range(0f, if (idleLong) 11f else 10f))
        if (pick == lastFidget) pick = fidgetPick(rng.range(0f, 10f)) // rarely the same twice in a row
        lastFidget = pick
        trigger(pick)
    }

    private var lastFidget: OneShot? = null
    private fun fidgetPick(r: Float) = when {
        r < 2.4f -> OneShot.LookAround
        r < 3.8f -> OneShot.Hop
        r < 5.2f -> OneShot.Wiggle
        r < 6.6f -> OneShot.Spin
        r < 8f -> OneShot.Dance
        r < 9f -> OneShot.Stretch
        r < 10f -> OneShot.Nod
        else -> OneShot.Yawn
    }

    // Working loops: 0 bob, 1 typing, 2 shuffle. [out] = lift, sx, sy, tilt.
    private var workMode = 0
    private var workPrev = 0
    private var workAt = -10f
    private var nextWork = 0f
    private var nextWorkSpin = 6f
    private val wa = FloatArray(4)
    private val wb = FloatArray(4)
    private fun workPose(mode: Int, t: Float, out: FloatArray) {
        when (mode) {
            0 -> {
                val ph = (t / (.9f * jitter)) % 1f
                val land = exp(-ph * 10f) // squash right after each landing
                out[0] = .04f * abs(sin(PI.toFloat() * ph)); out[1] = 1f + .06f * land; out[2] = 1f - .06f * land; out[3] = 0f
            }
            1 -> {
                val ph = 2f * PI.toFloat() * t / (.28f * jitter)
                out[0] = .012f * abs(sin(ph)); out[1] = 1f + .02f * sin(ph * 2f); out[2] = 1f - .02f * sin(ph * 2f); out[3] = 2.5f * sin(ph)
            }
            else -> {
                val ph = (t / (.55f * jitter)) % 1f
                val land = exp(-ph * 9f)
                out[0] = .03f * abs(sin(PI.toFloat() * ph)); out[1] = 1f + .05f * land; out[2] = 1f - .05f * land
                out[3] = 6f * sin(2f * PI.toFloat() * t / (1.1f * jitter))
            }
        }
    }

    val currentShot: OneShot? get() = shot
}

internal fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k
internal fun easeInQuad(x: Float) = x.coerceIn(0f, 1f).let { it * it }
internal fun easeOutCubic(x: Float) = (1f - (1f - x.coerceIn(0f, 1f)).let { it * it * it })
internal fun easeInOut(x: Float) = x.coerceIn(0f, 1f).let { if (it < .5f) 2 * it * it else 1 - (-2 * it + 2).let { v -> v * v } / 2 }
internal fun easeOutBack(x: Float, c: Float = 1.7f): Float { val k = x.coerceIn(0f, 1f) - 1f; return 1f + (c + 1f) * k * k * k + c * k * k }
