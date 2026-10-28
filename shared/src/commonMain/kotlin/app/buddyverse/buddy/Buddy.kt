package app.buddyverse.buddy

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.withFrameNanos
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One clock for every buddy: `fast` each frame, `slow` about every 33 ms; stops when the app is not resumed (design §4.9). */
@Stable
class BuddyClock {
    val fast = mutableLongStateOf(0L)
    val slow = mutableLongStateOf(0L)
    /** [fast] time a busy buddy last drew: while one is on screen the clock never idles. Plain field, never state. */
    var busyAt = 0L
}

/** The last finger/mouse position in window px and when (design §4.6 priority 1). Plain fields: never state. */
object LookTarget {
    var x = 0f; var y = 0f; var atNanos = 0L
    /** Wall-clock ms of the last touch, mouse or key: 90 s without one (and nothing busy on screen) slows the clock. */
    var inputAtMs = 0L
}

val LocalBuddyClock = staticCompositionLocalOf { BuddyClock() }
val LocalMotion = staticCompositionLocalOf { MotionLevel.Full }

@Composable
fun ProvideBuddyClock(motion: MotionLevel, content: @Composable () -> Unit) {
    val clock = remember { BuddyClock() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val level = rememberUpdatedState(motion)
    LaunchedEffect(lifecycle) {
        // RESUMED only: stops in the background, minimized, and (PC) while the window is not focused.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            LookTarget.inputAtMs = app.buddyverse.core.nowMillis() // coming back counts as input
            while (true) {
                withFrameNanos { now ->
                    clock.fast.longValue = now
                    if (now - clock.slow.longValue >= 33_000_000L) clock.slow.longValue = now
                }
                // Nobody touching it and nothing busy on screen: ~10 fps is plenty for breathing. Motion off: ~15 fps for blinks.
                val idle = app.buddyverse.core.nowMillis() - LookTarget.inputAtMs > 90_000L && clock.fast.longValue - clock.busyAt > 1_000_000_000L
                if (idle) kotlinx.coroutines.delay(100) else if (level.value == MotionLevel.Off) kotlinx.coroutines.delay(50)
            }
        }
    }
    CompositionLocalProvider(LocalBuddyClock provides clock, LocalMotion provides motion, content = content)
}

/** What a buddy looks like. [id] seeds its rhythm so a team never moves in sync. */
data class BuddySpec(val id: String, val name: String, val shape: BuddyShape, val color: Color)

/** Something happened that the buddy should react to once; a new [serial] fires it. */
data class BuddyCue(val serial: Long, val shot: OneShot)

/**
 * A living character: breathes, looks around, blinks irregularly, reacts to touch and to agent events.
 * The clock is read only in the draw phase, so animation never recomposes (design §4.9).
 */
@Composable
fun Buddy(
    spec: BuddySpec,
    mood: Mood,
    size: Dp,
    modifier: Modifier = Modifier,
    cue: BuddyCue? = null,
    talkAt: Long = 0L,
    talkChars: Int = 0,
    lookAtComposer: Boolean = false,
    onTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    interactive: Boolean = true,
    /** Games: `[x, y, on]`, look direction in -1..1 while `on > 0` (beats the finger). Read each frame, never recomposes. */
    gaze: FloatArray? = null,
) {
    val clock = LocalBuddyClock.current
    val motion = LocalMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val brain = remember(spec.id) { BuddyBrain(seedOf(spec.id)) }
    val density = LocalDensity.current
    brain.requestedMood = mood
    brain.motion = motion
    brain.sizeDp = size.value
    brain.rtl = rtl
    LaunchedEffect(cue?.serial) { cue?.let { brain.trigger(it.shot) } }
    LaunchedEffect(talkAt) { if (talkAt > 0) brain.talk(talkChars) }
    DisposableEffect(brain) { onDispose { brain.particles.clear() } }
    val tap = rememberUpdatedState(onTap)
    val fb = app.buddyverse.design.LocalFeedback.current
    val purrScope = androidx.compose.runtime.rememberCoroutineScope()
    val purr = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    val longPress = rememberUpdatedState(onLongPress)
    val center = remember { FloatArray(2) }
    val composerLook = rememberUpdatedState(lookAtComposer)
    val gazeAt = rememberUpdatedState(gaze)
    val tier = when {
        mood.busy -> clock.fast
        size >= 48.dp -> clock.fast
        else -> clock.slow
    }
    val dp = density.density
    Spacer(
        modifier
            .size(size)
            .onPlaced { c -> val p = c.positionInWindow(); center[0] = p.x + c.size.width / 2f; center[1] = p.y + c.size.height / 2f }
            .then(if (!interactive) Modifier else Modifier.pointerInput(spec.id) {
                detectTapGestures(
                    onPress = {
                        brain.press()
                        val up = tryAwaitRelease()
                        // After a long press the finger lifting ends the petting; it is not a tap (no hop).
                        when { brain.isPetting -> brain.endPet(); up -> brain.release(); else -> brain.cancelPress() }
                        purr[0]?.cancel()
                    },
                    onTap = { tap.value?.invoke() },
                    onLongPress = {
                        brain.pet()
                        purr[0] = purrScope.launch { while (brain.isPetting) { fb.haptic(app.buddyverse.design.Haptic.Tick); kotlinx.coroutines.delay(120) } }
                        longPress.value?.invoke()
                    },
                )
            })
            .semantics { contentDescription = spec.name }
            .drawWithCache {
                val w = this.size.width
                val body = BuddyPaths.scaled(spec.shape, w)
                val ground = BuddyPaths.groundY(spec.shape) / 1000f * w
                val light = BuddyColors.mix(spec.color, Color.White, .35f)
                val dark = BuddyColors.mix(spec.color, Color.Black, .25f)
                val eye = BuddyColors.eyeOn(spec.color)
                val rim = spec.color.let { with(BuddyColors) { it.luminance() } < .22f }
                val mouthPath = Path()
                val arc = Path()
                var brushFor = 0f
                var brush = Brush.linearGradient(listOf(light, spec.color, dark), Offset.Zero, Offset(w, w))
                onDrawBehind {
                    val now = tier.longValue
                    // Gaze: finger/mouse within 1.5 s wins, then the composer while listening (design §4.6).
                    val g = gazeAt.value
                    if (g != null && g[2] > 0f) brain.lookAt(g[0], g[1], .2f)
                    else if (now - LookTarget.atNanos < 1_500_000_000L && LookTarget.atNanos > 0) {
                        val dx = LookTarget.x - center[0]; val dy = LookTarget.y - center[1]
                        val reach = max(sqrt(dx * dx + dy * dy), 160f * dp)
                        brain.lookAt(dx / reach, dy / reach, .2f)
                    } else if (composerLook.value) brain.lookAt(0f, 1f, .2f)
                    val p = brain.step(now)
                    if (brain.mood.busy) clock.busyAt = now
                    if (p.desaturate != brushFor) {
                        brushFor = p.desaturate
                        val c = BuddyColors.desaturate(spec.color, p.desaturate)
                        brush = Brush.linearGradient(listOf(BuddyColors.mix(c, Color.White, .35f), c, BuddyColors.mix(c, Color.Black, .25f)), Offset.Zero, Offset(w, w))
                    }
                    drawBuddy(spec, p, brain, body, brush, ground, eye, rim, mouthPath, arc, dp)
                }
            },
    )
}

internal fun DrawScope.drawBuddy(
    spec: BuddySpec, p: Pose, brain: BuddyBrain, body: Path, brush: Brush, ground: Float, eye: Color, rim: Boolean,
    mouth: Path, arc: Path, dp: Float,
) {
    val w = size.width
    val cx = w / 2f
    // Ground shadow shrinks and fades while airborne.
    val sw = w * .7f * p.shadow * p.scale
    drawOval(Color.Black.copy(alpha = .12f * (.7f + .3f * p.shadow)), Offset(cx - sw / 2, ground - w * .04f), Size(sw, w * .08f))
    withTransform({
        translate(p.shakeX * w, -p.lift * w)
        scale(p.sx * p.scale, p.sy * p.scale, Offset(cx, ground))
        rotate(p.tilt + p.spin, Offset(cx, ground - w * .45f))
    }) {
        drawPath(body, brush)
        if (rim) drawPath(body, Color.White.copy(alpha = .08f), style = Stroke(1.5f * dp))
        if (w >= 32f * dp) {
            rotate(-20f, Offset(w * .30f, w * .22f)) {
                drawOval(Color.White.copy(alpha = .30f), Offset(w * .30f - w * .11f, w * .22f - w * .06f), Size(w * .22f, w * .12f))
            }
        }
        drawFace(spec, p, w, eye, mouth, arc, dp)
        if (p.thinkingDots > 0f) thinkingDots(p.time, w)
        if (p.orbit > 0f) orbit(p.orbitPhase, w, spec.color)
    }
    drawParticles(brain.particles, cx, w * .5f, spec.color, dp)
}

private fun DrawScope.drawFace(spec: BuddySpec, p: Pose, w: Float, eye: Color, mouth: Path, arc: Path, dp: Float) {
    val fx = spec.shape.faceX * w + p.gazeX * w * .07f
    val fy = spec.shape.faceY * w + p.gazeY * w * .05f
    val ew = w * .11f * p.eyeScale
    val eh = w * .20f * p.eyeScale * (1f - p.eyeRound) + ew * 1.2f * p.eyeRound
    val gap = w * .15f
    if (p.cheeks > .01f) {
        val blush = Color(0xFFFF7AA8).copy(alpha = .35f * p.cheeks)
        drawOval(blush, Offset(fx - gap - w * .09f - w * .045f, fy + w * .08f), Size(w * .09f, w * .05f))
        drawOval(blush, Offset(fx + gap + w * .09f - w * .045f, fy + w * .08f), Size(w * .09f, w * .05f))
    }
    if (p.dizzy > 0f) {
        for (side in intArrayOf(-1, 1)) {
            val ex = fx + side * gap
            rotate(p.time * 540f * side, Offset(ex, fy)) {
                drawArc(eye, 0f, 270f, false, Offset(ex - ew * .7f, fy - ew * .7f), Size(ew * 1.4f, ew * 1.4f), style = Stroke(w * .025f, cap = StrokeCap.Round))
            }
        }
    } else for (side in intArrayOf(-1, 1)) {
        val ex = fx + side * gap
        val open = if (side < 0) p.eyeOpenL else p.eyeOpenR
        val h = eh * open
        val top = fy - h / 2f
        val cutTop = top + h * p.lidTop
        val cutBottom = top + h - h * p.lidBottom
        rotate(p.lidTilt * -side, Offset(ex, fy)) {
            clipRect(ex - ew, cutTop, ex + ew, max(cutTop + 1f, cutBottom)) {
                rotate(p.lidTilt * side, Offset(ex, fy)) {
                    drawRoundRect(eye, Offset(ex - ew / 2f, top), Size(ew, h), CornerRadius(ew / 2f))
                }
            }
        }
        if (w >= 48f * dp && open > .5f) drawCircle(Color.White.copy(alpha = if (eye == Color.White) 0f else .7f), w * .015f, Offset(ex - ew * .2f, top + h * .2f))
        if (w >= 48f * dp && open > .5f && eye == Color.White) drawCircle(Color.Black.copy(alpha = .10f), w * .015f, Offset(ex + ew * .15f, top + h * .25f))
    }
    // Mouth: hidden unless talking or reacting.
    val my = spec.shape.faceY * w + w * .17f + p.gazeY * w * .03f
    val mw = w * .16f
    val ink = if (eye == Color.White) Color(0xFF1C1A1F).copy(alpha = .78f) else eye
    if (p.mouthOpen > .03f) {
        val ow = mw * (if (p.mouthRound > .5f) .8f else 1f) * (.6f + .4f * p.mouthOpen)
        val oh = w * .10f * p.mouthOpen * (if (p.mouthRound > .5f) 1.2f else 1f)
        drawOval(ink, Offset(fx - ow / 2f, my - oh / 2f), Size(ow, max(oh, 1f)))
    } else if (kotlin.math.abs(p.smile) > .05f && w >= 24f * dp) {
        arc.rewind()
        arc.moveTo(fx - mw / 2f, my)
        arc.quadraticTo(fx, my + p.smile * w * .09f, fx + mw / 2f, my)
        drawPath(arc, ink, style = Stroke(w * .025f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.thinkingDots(t: Float, w: Float) {
    val c = Offset(w * .5f, w * .02f)
    for (i in 0 until 3) {
        val a = (t / 1.2f) * 2f * PI.toFloat() + i * 2f * PI.toFloat() / 3f
        val pulse = .6f + .4f * ((sin(t * 2f * PI.toFloat() / 1.2f - i * 2f) + 1f) / 2f)
        drawCircle(Color(0xFF8F8A96).copy(alpha = .9f), w * .03f * pulse, Offset(c.x + cos(a) * w * .18f, c.y + sin(a) * w * .06f))
    }
}

private fun DrawScope.orbit(t: Float, w: Float, color: Color) {
    val c = Offset(w * .5f, w * .5f)
    for (i in 0 until 2) {
        val a = t / 2.2f * 2f * PI.toFloat() + i * PI.toFloat()
        drawCircle(color.copy(alpha = .8f), w * .035f, Offset(c.x + cos(a) * w * .62f, c.y + sin(a) * w * .22f))
    }
}

private val confettiColors = listOf(Color(0xFF6E56FF), Color(0xFFFFB224), Color(0xFF3DD68C), Color(0xFFFF309B), Color(0xFF1084FE))

private fun DrawScope.drawParticles(ps: Particles, cx: Float, cy: Float, color: Color, dp: Float) {
    for (i in 0 until ps.count) {
        val a = ps.alpha(i)
        val x = cx + ps.x[i] * dp; val y = cy + ps.y[i] * dp
        val s = ps.size[i] * dp
        when (ps.type[i]) {
            Particles.CONFETTI -> rotate(ps.rot[i], Offset(x, y)) {
                drawRect(if (ps.color[i] % 6 == 0) confettiColors[0] else if (ps.color[i] % 5 == 0) confettiColors[ps.color[i] % 5] else color, Offset(x - s * .4f, y - s * .6f), Size(s * .75f, s * 1.25f), alpha = a)
            }
            Particles.SPARK -> drawCircle(Color(0xFFFFC766), s, Offset(x, y), alpha = a)
            Particles.HEART -> heart(x, y, s, Color(0xFFFF4F8B).copy(alpha = a))
            Particles.Z -> {
                val z = s * (.6f + .4f * (1f - a))
                val k = Color(0xFF8F8A96).copy(alpha = a)
                val st = Stroke(max(1.5f * dp, z * .14f), cap = StrokeCap.Round)
                val path = Path().apply { moveTo(x, y); lineTo(x + z, y); lineTo(x, y + z); lineTo(x + z, y + z) }
                drawPath(path, k, style = st)
            }
            Particles.POOF -> drawCircle(Color(0xFFD9D4DF), s * a, Offset(x, y), alpha = a)
            Particles.SWEAT -> drawOval(Color(0xFF7CC4FF), Offset(x - s * .35f, y - s * .5f), Size(s * .7f, s), alpha = a)
        }
    }
}

private fun DrawScope.heart(x: Float, y: Float, s: Float, c: Color) {
    drawCircle(c, s * .28f, Offset(x - s * .22f, y - s * .1f))
    drawCircle(c, s * .28f, Offset(x + s * .22f, y - s * .1f))
    val p = Path().apply { moveTo(x - s * .48f, y); lineTo(x, y + s * .5f); lineTo(x + s * .48f, y); close() }
    drawPath(p, c)
}

/** Small stack of members for group rows (design §4.1 group layouts). */
@Composable
fun BuddyGroup(members: List<BuddySpec>, moods: List<Mood>, size: Dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(modifier.size(size)) {
        val n = min(members.size, 3)
        val frac = when (n) { 1 -> 1f; 2 -> .66f; else -> .55f }
        members.take(3).forEachIndexed { i, m ->
            val align = when {
                n == 1 -> androidx.compose.ui.Alignment.Center
                n == 2 -> if (i == 0) androidx.compose.ui.Alignment.BottomStart else androidx.compose.ui.Alignment.TopEnd
                else -> listOf(androidx.compose.ui.Alignment.TopCenter, androidx.compose.ui.Alignment.BottomStart, androidx.compose.ui.Alignment.BottomEnd)[i]
            }
            Buddy(m, moods.getOrElse(i) { Mood.Idle }, size * frac, Modifier.align(align), interactive = false)
        }
    }
}
