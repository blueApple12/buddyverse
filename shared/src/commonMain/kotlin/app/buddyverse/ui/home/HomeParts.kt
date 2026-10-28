package app.buddyverse.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.LocalBuddyClock
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.nowMillis
import app.buddyverse.design.Motion
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerInput
import app.buddyverse.buddy.DragFeel
import app.buddyverse.buddy.LocalMotion
import app.buddyverse.buddy.MotionLevel
import app.buddyverse.design.Haptic
import app.buddyverse.design.LocalFeedback
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Screen-wide cues every home buddy follows: [wakeAt] -1 = link not ready (Offline), 0 = awake, else the moment the team
 * started waking (each buddy wakes 70 ms after the previous, design §3.1 "Connecting"); [down] = engine off (Sleeping).
 */
@Immutable
internal data class HomeCues(val wakeAt: Long = 0L, val down: Boolean = false, val ripple: Ripple? = null)

internal val LocalHomeCues = compositionLocalOf { HomeCues() }

/** A buddy on the home screen: its live mood, plus taps, the third-tap wave and the wake-up stagger. Never interactive itself. */
@Composable
internal fun HomeBuddy(
    spec: BuddySpec, mood: Mood, size: Dp, index: Int, modifier: Modifier = Modifier,
    tap: BuddyCue? = null, talkAt: Long = 0L, talkChars: Int = 0,
) {
    val cues = LocalHomeCues.current
    val order = min(index, 30)
    // Own one-shots: the third-tap wave and the wake-up Arrive (design §3.1 "Connecting": one by one, 70 ms apart).
    var own by remember { mutableStateOf<BuddyCue?>(null) }
    val wakeAt = cues.wakeAt
    var awake by remember { mutableStateOf(wakeAt == 0L || (wakeAt > 0L && nowMillis() >= wakeAt + order * 70L)) }
    LaunchedEffect(wakeAt) {
        if (wakeAt < 0L) { awake = false; return@LaunchedEffect }
        if (wakeAt == 0L) { awake = true; return@LaunchedEffect }
        if (awake) return@LaunchedEffect
        delay((wakeAt + order * 70L - nowMillis()).coerceAtLeast(0L))
        awake = true
        own = Cues.cue(OneShot.Arrive)
    }
    val ripple = cues.ripple
    LaunchedEffect(ripple) {
        if (ripple == null || nowMillis() - ripple.atMs > 2_000) return@LaunchedEffect
        delay(order * ripple.stepMs)
        own = BuddyCue(ripple.serial, ripple.shot)
    }
    val o = own
    val cue = if (tap != null && (o == null || tap.serial > o.serial)) tap else o
    val shown = when { cues.down -> Mood.Sleeping; !awake -> Mood.Offline; else -> mood }
    Buddy(spec, shown, size, modifier, cue = cue, talkAt = talkAt, talkChars = talkChars, interactive = false)
}

/** Where a dragged stage bot is: offset in dp from its home; [lifted] while held or springing back. */
internal fun interface Pull { fun moved(x: Float, y: Float, lifted: Boolean) }

/**
 * A tappable buddy for the stage and the team strip: [touch] ≥ 48 dp squishes with a haptic at touch-down, the buddy hops
 * with a giggle, and [onOpen] runs 120 ms later so the bounce shows first (design §3.1 "Tap a node"). With [pull] it can
 * also be dragged around (a drag never opens): it dangles, laughs when tickled, gets dizzy when shaken, and springs home.
 */
@Composable
internal fun TapBuddy(
    spec: BuddySpec, live: BotLive?, size: Dp, touch: Dp, index: Int, label: String,
    onOpen: () -> Unit, onMenu: (() -> Unit)?, modifier: Modifier = Modifier, pull: Pull? = null,
) {
    var tap by remember { mutableStateOf<BuddyCue?>(null) }
    val scope = rememberCoroutineScope()
    val open by rememberUpdatedState(onOpen)
    val drag = if (pull == null) Modifier else playDrag(pull) { tap = Cues.cue(it) }
    Box(
        modifier.size(touch).then(drag).squish({ tap = Cues.cue(OneShot.Tap); scope.launch { delay(120); open() } }, onLongPress = onMenu, label = label),
        contentAlignment = Alignment.Center,
    ) {
        HomeBuddy(spec, live?.mood ?: Mood.Idle, size, index, tap = tap, talkAt = live?.talkAt ?: 0L, talkChars = live?.talkChars ?: 0)
    }
}

/**
 * Drag-to-play: the pointer input sits before the translation layer, so its coordinates stay put while the bot moves.
 * Release springs it home with an overshoot (Motion off: a quick slide, no bounce) and it lands with [OneShot.Boing].
 */
@Composable
private fun playDrag(pull: Pull, onShot: (OneShot) -> Unit): Modifier {
    val ox = remember { mutableFloatStateOf(0f) }
    val oy = remember { mutableFloatStateOf(0f) }
    val lean = remember { mutableFloatStateOf(0f) }
    val held = remember { mutableStateOf(false) }
    val movedAt = remember { LongArray(1) }
    val feel = remember { DragFeel() }
    val job = remember { arrayOfNulls<Job>(1) }
    val still = LocalMotion.current == MotionLevel.Off
    val clock = LocalBuddyClock.current.fast
    val fb = LocalFeedback.current
    val scope = rememberCoroutineScope()
    val p by rememberUpdatedState(pull)
    val shot by rememberUpdatedState(onShot)
    fun home() {
        job[0] = scope.launch {
            val x0 = ox.floatValue; val y0 = oy.floatValue
            launch { delay(if (still) 0L else 180L); shot(OneShot.Boing) }
            animate(1f, 0f, animationSpec = if (still) tween(140) else spring(dampingRatio = .38f, stiffness = 380f)) { v, _ ->
                ox.floatValue = x0 * v; oy.floatValue = y0 * v; p.moved(ox.floatValue, oy.floatValue, true)
            }
            held.value = false; lean.floatValue = 0f
            p.moved(0f, 0f, false)
        }
    }
    return Modifier.pointerInput(Unit) {
        detectDragGestures(
            onDragStart = { job[0]?.cancel(); feel.reset(); held.value = true; shot(OneShot.Lift); fb.haptic(Haptic.Tick) },
            onDragEnd = { home() },
            onDragCancel = { home() },
        ) { change, d ->
            change.consume()
            val dx = d.x / density; val dy = d.y / density
            ox.floatValue += dx; oy.floatValue += dy
            lean.floatValue = (lean.floatValue * .7f + dx * 1.2f).coerceIn(-30f, 30f)
            movedAt[0] = clock.longValue
            p.moved(ox.floatValue, oy.floatValue, true)
            when (feel.add(dx, dy, nowMillis())) {
                DragFeel.Feel.Tickle -> { shot(OneShot.Laugh); fb.haptic(Haptic.Tick) }
                DragFeel.Feel.Shake -> { shot(OneShot.Dizzy); fb.haptic(Haptic.Reject) }
                null -> Unit
            }
        }
    }.graphicsLayer {
        translationX = ox.floatValue * density; translationY = oy.floatValue * density
        // Leans into the drag and swings upright ~150 ms after the finger stops; the clock is read only while held.
        rotationZ = if (!held.value || still) 0f else lean.floatValue * kotlin.math.exp(-(clock.longValue - movedAt[0]) / 150_000_000f)
    }
}

/** Needs-you "!" (amber 20 dp) or unread dot (accent 12 dp) at the avatar's bottom-end, cut out of the [ground] by a 2.5 dp ring. */
@Composable
internal fun BoxScope.StatusBadge(needs: Boolean, unread: Boolean, ground: Color = Theme.colors.ground) {
    val c = Theme.colors
    val show = needs || unread
    val k by animateFloatAsState(if (show) 1f else 0f, Motion.bouncy())
    if (!show && k < .02f) return
    val d = if (needs) 20.dp else 12.dp
    Box(
        Modifier.align(Alignment.BottomEnd).graphicsLayer { scaleX = k; scaleY = k }.size(d + 5.dp).clip(CircleShape).background(ground)
            .padding(2.5.dp).clip(CircleShape).background(if (needs) c.needsYou else c.accentFill),
        contentAlignment = Alignment.Center,
    ) { if (needs) Txt("!", Theme.type.micro.copy(fontWeight = FontWeight.Bold), Color.White, maxLines = 1) }
}

/** Three dots pulsing in turn: the "working" glyph on line 2. Clock read in draw only. */
@Composable
internal fun ThinkingDots(color: Color, modifier: Modifier = Modifier) {
    val clock = LocalBuddyClock.current.slow
    Box(modifier.size(18.dp, 12.dp).drawBehind {
        val t = seconds(clock.longValue)
        val r = 1.8.dp.toPx()
        for (i in 0 until 3) {
            val k = (sin((t / 1.2f - i / 3f) * 2f * PI.toFloat()) + 1f) / 2f
            drawCircle(color.copy(alpha = .35f + .65f * k), r * (.7f + .3f * k), Offset(size.width * (i + .5f) / 3f, size.height / 2f - 2.dp.toPx() * k))
        }
    })
}

/** 2 dp caret blinking at 530 ms steps after streamed text (design §4.4). */
@Composable
internal fun Caret(color: Color) {
    val clock = LocalBuddyClock.current.slow
    Box(Modifier.padding(start = 2.dp).size(2.dp, 16.dp).drawBehind { if ((clock.longValue / 530_000_000L) % 2L == 0L) drawRect(color) })
}

/** Line 2 of a row by state: amber ask, working glyph + activity, live tail + caret, red error, else the last message. */
@Composable
internal fun LineTwo(live: BotLive, modifier: Modifier = Modifier) {
    val c = Theme.colors
    val t = Theme.type.sub
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        when (live.kind) {
            LineKind.Streaming -> {
                BasicText(live.text, Modifier.weight(1f, fill = false), style = t.copy(color = c.text), overflow = TextOverflow.StartEllipsis, maxLines = 1)
                Caret(c.accent)
            }
            LineKind.Working -> {
                ThinkingDots(c.working)
                Spacer(Modifier.width(6.dp))
                Txt(live.text, t, c.secondary, Modifier.weight(1f), maxLines = 1)
            }
            LineKind.NeedsYou -> Txt(live.text, t, c.needsYouText, Modifier.weight(1f), maxLines = 1)
            LineKind.Error -> Txt(live.text, t, c.errorText, Modifier.weight(1f), maxLines = 1)
            LineKind.Preview -> Txt(live.text, t, c.secondary, Modifier.weight(1f), maxLines = 1)
        }
    }
}
