package app.buddyverse.ui.play

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.LocalMotion
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.MotionLevel
import app.buddyverse.buddy.Rng
import app.buddyverse.design.BackHandler
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.PillButton
import app.buddyverse.design.Radius
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.APP_NAME
import app.buddyverse.i18n.S
import app.buddyverse.i18n.play
import app.buddyverse.ui.LocalStore
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal enum class Game(val id: String) { Ttt("ttt"), Four("c4"), Merge("2048"), Echo("echo"), Snake("snake") }
internal enum class Phase { Ready, Playing, Over }

private val fallback = BuddySpec("home", APP_NAME, BuddyShape.Squircle, BuddyColors.palette[8])

/** True while the games card is folded to a pill: frame loops and bot timers wait. */
internal val LocalPlayPaused = compositionLocalOf { false }

/**
 * The games, starring the user's own bots: Tic-tac-toe and Four in a row against a ladder of them, 2048 bots, Bot
 * echo and Bot snake. Lives in a panel (docked beside the chat on a wide window, a floating card on a phone), never
 * full screen. Best score / ladder progress per game in Prefs.best. [onFold] null = docked (no fold button).
 */
@Composable
internal fun GamesPanel(modifier: Modifier, onFold: (() -> Unit)?, onClose: () -> Unit, header: Modifier = Modifier) {
    val store = LocalStore.current
    val p = S.play
    val c = Theme.colors
    val bots by store.bots.collectAsState()
    // The brain (default bot) first: it leads the snake and is the biggest 2048 tile.
    val specs = remember(bots) { bots.filter { !it.hidden }.sortedByDescending { it.isDefault }.map { it.spec }.ifEmpty { listOf(fallback) } }
    // Opponents easy → hard: his other bots in their order, the brain last (unbeatable).
    val ladder = remember(specs) { if (specs.size > 1) specs.drop(1) + specs[0] else specs }
    val prefs by store.prefs.collectAsState()
    var game by remember { mutableStateOf<Game?>(null) }
    // Docked on the PC, Esc belongs to the chat; the floating card takes Back (game → list → fold).
    BackHandler(game != null && onFold != null) { game = null }
    fun record(g: Game, score: Int): Boolean {
        val now = store.prefs.value
        val old = now.best[g.id]
        val better = score > 0 && (old == null || score > old)
        if (better) store.setPrefs(now.copy(best = now.best + (g.id to score)))
        return better
    }
    Column(modifier.background(c.ground)) {
        Row(header.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (game != null) IconButton(Icons.arrowBack, p.back, { game = null }, mirror = true) else Spacer(Modifier.width(12.dp))
            Txt(game?.let { name(it) } ?: p.title, Theme.type.headline, c.text, Modifier.weight(1f), maxLines = 1)
            if (onFold != null) IconButton(Icons.expandMore, p.collapse, onFold, tint = c.secondary)
            IconButton(Icons.close, p.close, onClose, tint = c.secondary)
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            AnimatedContent(game, transitionSpec = { (fadeIn(tween(160)) + scaleIn(tween(220), .96f)) togetherWith fadeOut(tween(90)) }, label = "game") { g ->
                when (g) {
                    null -> Picker(specs, ladder.size, prefs.best) { game = it }
                    Game.Snake -> SnakeView(specs, prefs.best[g.id]) { record(g, it) }
                    Game.Merge -> MergeView(specs, prefs.best[g.id]) { record(g, it) }
                    Game.Echo -> EchoView(specs, prefs.best[g.id]) { record(g, it) }
                    Game.Ttt, Game.Four -> DuelView(g, ladder, prefs.best[g.id] ?: 0) { record(g, it) }
                }
            }
        }
    }
}

@Composable
internal fun name(g: Game) = when (g) {
    Game.Snake -> S.play.snakeName; Game.Merge -> S.play.mergeName; Game.Ttt -> S.play.tttName
    Game.Four -> S.play.c4Name; Game.Echo -> S.play.echoName
}

@Composable
private fun hint(g: Game) = when (g) {
    Game.Snake -> S.play.snakeHint; Game.Merge -> S.play.mergeHint; Game.Ttt -> S.play.tttHint
    Game.Four -> S.play.c4Hint; Game.Echo -> S.play.echoHint
}

@Composable
private fun Picker(specs: List<BuddySpec>, ladderSize: Int, best: Map<String, Int>, onPick: (Game) -> Unit) {
    val c = Theme.colors
    val p = S.play
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Game.entries.forEachIndexed { i, g ->
            val score = best[g.id]
            val duel = g == Game.Ttt || g == Game.Four
            Row(
                Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(Radius.card.dp)).background(c.surface)
                    .squish({ onPick(g) }, label = name(g)).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Buddy(specs[i % specs.size], Mood.Idle, 48.dp, interactive = false)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Txt(name(g), Theme.type.headline, c.text, maxLines = 1)
                    val sub = when {
                        duel && score != null && score > 0 -> p.beaten(min(score, ladderSize), ladderSize)
                        !duel && score != null -> p.best(score)
                        else -> hint(g)
                    }
                    Txt(sub, Theme.type.caption, c.secondary, maxLines = 2)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- shared bits

/** Tests only: each game hands its live round object here on every recomposition (DuelRound, SnakeGame, ...). */
internal val LocalPlayProbe = androidx.compose.runtime.staticCompositionLocalOf<(Any) -> Unit> { {} }

/** Waits while the panel is folded (bot timers, the echo show). */
internal suspend fun State<Boolean>.awaitResume() { while (value) delay(80) }

/**
 * Calls [onFrame] with dt (s, capped) every frame while [running], the panel is unfolded and the app is resumed; a
 * pause never jumps. The frame callback is built once, so a running game allocates nothing per frame here.
 */
@Composable
internal fun FrameLoop(running: Boolean, onFrame: (Float) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val paused = LocalPlayPaused.current
    val f by rememberUpdatedState(onFrame)
    LaunchedEffect(running && !paused, lifecycle) {
        if (!running || paused) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val last = LongArray(1) { -1L }
            val tick: (Long) -> Unit = { now -> if (last[0] > 0) f(min(.05f, (now - last[0]) / 1e9f)); last[0] = now }
            while (true) withFrameNanos(tick)
        }
    }
}

/**
 * Swipes on a game field → [UP]/[RIGHT]/[DOWN]/[LEFT]. With [repeat] a finger kept down can turn again (snake);
 * otherwise one move per swipe (2048). The handler is read fresh each time, so a new round never talks to the old one.
 */
@Composable
internal fun Modifier.swipes(repeat: Boolean, onSwipe: (Int) -> Unit): Modifier {
    val cb by rememberUpdatedState(onSwipe)
    return this.keepSwipes().pointerInput(repeat) {
        // Snake (repeat) turns on a shorter drag, so a quick flick turns at once.
        val th = (if (repeat) 12 else 20).dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var ox = down.position.x
            var oy = down.position.y
            var fired = false
            while (true) {
                val e = awaitPointerEvent()
                val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) break
                val dx = ch.position.x - ox
                val dy = ch.position.y - oy
                if ((repeat || !fired) && max(abs(dx), abs(dy)) > th) {
                    cb(if (abs(dx) > abs(dy)) (if (dx > 0) RIGHT else LEFT) else (if (dy > 0) DOWN else UP))
                    fired = true; ox = ch.position.x; oy = ch.position.y
                }
                if (fired) ch.consume()
            }
        }
    }
}

/** Key codes beyond the directions: Space / Enter. Digits 1–9 arrive as [DIGIT] + n. */
internal const val ACT = -1
internal const val DIGIT = 100
/** Test tag of every game's field (the node that takes keys, taps and swipes). */
internal const val PLAY_FIELD = "play-field"

/**
 * PC: arrow keys / WASD → a direction, Space / Enter → [ACT], 1–9 → [DIGIT]+n. The field takes keyboard focus on a
 * new round ([focusKey] changes), when the card unfolds, and on any press inside it, so keys never stay with the
 * chat box, a button or a folded card. [onKey] is read fresh each time: it always reaches the current round.
 */
@Composable
internal fun Modifier.keys(focusKey: Any, onKey: (Int) -> Unit): Modifier {
    val fr = remember { FocusRequester() }
    val cb by rememberUpdatedState(onKey)
    val paused = LocalPlayPaused.current
    LaunchedEffect(focusKey, paused) { if (!paused) runCatching { fr.requestFocus() } }
    return this.pointerInput(fr) {
        // Initial pass, nothing consumed: the game's own taps and swipes still get the press.
        awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); runCatching { fr.requestFocus() } }
    }.testTag(PLAY_FIELD).onKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
        val d = when (e.key) {
            Key.DirectionUp, Key.W -> UP
            Key.DirectionRight, Key.D -> RIGHT
            Key.DirectionDown, Key.S -> DOWN
            Key.DirectionLeft, Key.A -> LEFT
            Key.Spacebar, Key.Enter, Key.NumPadEnter -> ACT
            Key.One, Key.NumPad1 -> DIGIT + 1
            Key.Two, Key.NumPad2 -> DIGIT + 2
            Key.Three, Key.NumPad3 -> DIGIT + 3
            Key.Four, Key.NumPad4 -> DIGIT + 4
            Key.Five, Key.NumPad5 -> DIGIT + 5
            Key.Six, Key.NumPad6 -> DIGIT + 6
            Key.Seven, Key.NumPad7 -> DIGIT + 7
            Key.Eight, Key.NumPad8 -> DIGIT + 8
            Key.Nine, Key.NumPad9 -> DIGIT + 9
            else -> return@onKeyEvent false
        }
        cb(d); true
    }.focusRequester(fr).focusable()
}

/** The field every game plays in: a rounded surface. */
internal fun Modifier.field(c: Color) = this.fillMaxSize().clip(RoundedCornerShape(Radius.card.dp)).background(c)

/** Score (and the best) above a field, with an optional reacting bot at the start. */
@Composable
internal fun ScoreRow(score: Int, best: Int?, host: (@Composable () -> Unit)? = null, end: @Composable () -> Unit = {}) {
    val c = Theme.colors
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (host != null) { host(); Spacer(Modifier.width(10.dp)) }
        AnimatedContent(score, transitionSpec = { (fadeIn(tween(140)) + scaleIn(tween(200), .7f)) togetherWith fadeOut(tween(80)) }, label = "score") {
            Txt("$it", Theme.type.title, c.text, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        if (best != null) Txt(S.play.best(max(best, score)), Theme.type.caption, c.secondary, Modifier.weight(1f), maxLines = 1)
        else Spacer(Modifier.weight(1f))
        end()
    }
}

/** Start / game-over card over the field. */
@Composable
internal fun Overlay(phase: Phase, hint: String, result: String?, best: String?, newBest: Boolean, onGo: () -> Unit) {
    val c = Theme.colors
    val p = S.play
    Box(Modifier.fillMaxSize().zIndex(3f).background(c.ground.copy(alpha = .55f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(16.dp).widthIn(max = 320.dp).clip(RoundedCornerShape(Radius.card.dp)).background(c.surface).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (phase == Phase.Over && result != null) Txt(result, Theme.type.display, c.text, maxLines = 1)
            if (phase == Phase.Over && newBest) Txt(p.newBest, Theme.type.headline, c.accent, maxLines = 1)
            else if (best != null) Txt(best, Theme.type.caption, c.secondary, maxLines = 1)
            if (phase == Phase.Ready) Txt(hint, Theme.type.callout, c.secondary)
            PillButton(if (phase == Phase.Over) p.again else p.start, onGo)
        }
    }
}

/**
 * Confetti and sparks in field pixels: a fixed pool (struct of arrays), gravity, spin. [burst] does nothing with motion
 * off. Draw with [ConfettiLayer]; it ticks only while pieces are alive.
 */
internal class Confetti {
    companion object { const val MAX = 96 }
    val x = FloatArray(MAX); val y = FloatArray(MAX); val vx = FloatArray(MAX); val vy = FloatArray(MAX)
    val rot = FloatArray(MAX); val vr = FloatArray(MAX); val life = FloatArray(MAX); val size = FloatArray(MAX)
    val color = arrayOfNulls<Color>(MAX)
    /** 0 = paper (falls, spins), 1 = spark (no gravity, fades). */
    val kind = IntArray(MAX)
    var n = 0
    var off = false
    val serial = mutableIntStateOf(0)
    val frame = mutableLongStateOf(0L)
    private val rng = Rng(7)

    /** A party popper from (cx, cy) upward. */
    fun burst(cx: Float, cy: Float, count: Int, tints: List<Color>, power: Float) {
        if (off) return
        repeat(count) {
            if (n >= MAX) return@repeat
            val a = rng.range(-2.6f, -.54f)
            val v = power * rng.range(.55f, 1f)
            set(cx, cy, cos(a) * v, sin(a) * v, tints[it % tints.size], 0, rng.range(1.1f, 1.7f), rng.range(5f, 9f))
        }
        serial.intValue++
    }

    /** A ring of sparks (a perfect move, a merge). */
    fun sparks(cx: Float, cy: Float, count: Int, tint: Color, power: Float) {
        if (off) return
        repeat(count) {
            if (n >= MAX) return@repeat
            val a = rng.range(0f, 6.283f)
            val v = power * rng.range(.6f, 1f)
            set(cx, cy, cos(a) * v, sin(a) * v, tint, 1, rng.range(.3f, .5f), rng.range(3f, 5f))
        }
        serial.intValue++
    }

    private fun set(cx: Float, cy: Float, vxx: Float, vyy: Float, c: Color, k: Int, l: Float, s: Float) {
        val i = n++
        x[i] = cx; y[i] = cy; vx[i] = vxx; vy[i] = vyy; color[i] = c; kind[i] = k; life[i] = l; size[i] = s
        rot[i] = rng.range(0f, 360f); vr[i] = rng.range(-540f, 540f)
    }

    fun step(dt: Float, density: Float) {
        var i = 0
        while (i < n) {
            life[i] -= dt
            if (life[i] <= 0f) {
                n--; x[i] = x[n]; y[i] = y[n]; vx[i] = vx[n]; vy[i] = vy[n]; rot[i] = rot[n]; vr[i] = vr[n]
                life[i] = life[n]; size[i] = size[n]; color[i] = color[n]; kind[i] = kind[n]; continue
            }
            if (kind[i] == 0) { vy[i] += 900f * density * dt; vx[i] *= .985f; vy[i] *= .985f } else { vx[i] *= .9f; vy[i] *= .9f }
            x[i] += vx[i] * dt; y[i] += vy[i] * dt; rot[i] += vr[i] * dt
            i++
        }
    }

    fun clear() { n = 0 }
}

@Composable
internal fun rememberConfetti(): Confetti {
    val still = LocalMotion.current == MotionLevel.Off
    return remember { Confetti() }.also { it.off = still }
}

/** Draws [c] over its parent; ticks only while pieces are alive. */
@Composable
internal fun ConfettiLayer(c: Confetti, modifier: Modifier = Modifier) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    LaunchedEffect(c.serial.intValue) {
        var last = -1L
        while (c.n > 0) withFrameNanos { now ->
            if (last > 0) c.step(min(.05f, (now - last) / 1e9f), density)
            last = now; c.frame.longValue++
        }
        c.frame.longValue++
    }
    Spacer(modifier.fillMaxSize().zIndex(4f).drawBehind {
        c.frame.longValue
        for (i in 0 until c.n) {
            val col = c.color[i] ?: Color.White
            val s = c.size[i] * density
            if (c.kind[i] == 1) {
                drawCircle(col.copy(alpha = (c.life[i] / .5f).coerceIn(0f, 1f)), s * .5f, Offset(c.x[i], c.y[i]))
            } else rotate(c.rot[i], Offset(c.x[i], c.y[i])) {
                drawRect(col, Offset(c.x[i] - s / 2, c.y[i] - s * .3f), Size(s, s * .6f))
            }
        }
    })
}
