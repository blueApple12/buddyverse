package app.buddyverse.ui.play

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.LocalMotion
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.MotionLevel
import app.buddyverse.buddy.OneShot
import app.buddyverse.buddy.buddyBitmap
import app.buddyverse.buddy.easeOutBack
import app.buddyverse.buddy.easeOutCubic
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Haptic
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.PillButton
import app.buddyverse.design.Radius
import app.buddyverse.design.Sound
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.play
import app.buddyverse.ui.home.Cues
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------------------------------------------------------------- Bot snake

/** Space (dp) between the snake grid and the field's rounded edge. */
private const val SNAKE_MARGIN = 6f

@Composable
internal fun SnakeView(specs: List<BuddySpec>, best: Int?, record: (Int) -> Boolean) {
    val c = Theme.colors
    val p = S.play
    val fb = LocalFeedback.current
    val still = LocalMotion.current == MotionLevel.Off
    val scope = rememberCoroutineScope()
    val brain = specs[0]
    val crew = remember(specs) { if (specs.size > 1) specs.drop(1) else specs }
    var round by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(Phase.Ready) }
    var score by remember { mutableIntStateOf(0) }
    var newBest by remember { mutableStateOf(false) }
    var cue by remember { mutableStateOf<BuddyCue?>(null) }
    val frame = remember { mutableLongStateOf(0L) }
    val confetti = rememberConfetti()
    val gulp = remember { FloatArray(1) { 99f } } // seconds since the last bite: a bulge runs down the body
    val shake = remember { Animatable(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    // The round's grid is fixed when it starts: resizing the card mid-round scales the field, never restarts the game.
    var game by remember { mutableStateOf(SnakeGame(13, 13, 17)) }
    LocalPlayProbe.current(game)
    Column(Modifier.fillMaxSize()) {
        ScoreRow(score, best)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().graphicsLayer { translationX = shake.value * density }.field(c.surface)) {
            // The grid is fitted to the measured field (inside a small margin, so the rounded corners never cut a cell):
            // ~30dp cells across, as many rows as fit, the whole grid centered.
            val inW = max(1f, maxWidth.value - 2 * SNAKE_MARGIN); val inH = max(1f, maxHeight.value - 2 * SNAKE_MARGIN)
            val fitCols = (inW / 30f).roundToInt().coerceIn(9, 15)
            val fitRows = max(8, (inH / (inW / fitCols)).toInt())
            LaunchedEffect(fitCols, fitRows) { if (phase == Phase.Ready) game = SnakeGame(fitCols, fitRows, 17) }
            fun start() {
                round++; game = SnakeGame(fitCols, fitRows, round * 7919 + 17)
                score = 0; newBest = false; gulp[0] = 99f; confetti.clear(); phase = Phase.Playing
            }
            val cols = game.cols
            val rows = game.rows
            val cellDp = min(inW / cols, inH / rows)
            val padX = (maxWidth.value - cols * cellDp) / 2f
            val padY = (maxHeight.value - rows * cellDp) / 2f
            val px = (cellDp * 1.15f * 3f).roundToInt().coerceIn(24, 160) // bitmap size: crisp up to ~3x density
            val faces = remember(crew, px) { crew.map { buddyBitmap(it, Mood.Idle, px) } }
            val tints = remember(crew) { crew.map { it.color } }
            FrameLoop(phase == Phase.Playing) { dt ->
                val ev = game.update(dt)
                gulp[0] += dt
                if (ev and SnakeGame.ATE != 0) {
                    score = game.eaten
                    gulp[0] = 0f
                    cue = Cues.cue(if (game.eaten % 10 == 0) OneShot.Spin else OneShot.Hop)
                    fb.haptic(Haptic.Tick)
                    val h = game.body[0]
                    val hx = (padX + ((h % cols) + .5f) * cellDp) * density; val hy = (padY + ((h / cols) + .5f) * cellDp) * density
                    confetti.sparks(hx, hy, 10, tints[(game.eaten - 1) % tints.size], cellDp * 9f * density)
                    if (game.eaten % 10 == 0) { fb.sound(Sound.Bloop); confetti.burst(hx, hy, 30, tints, 520f * density) }
                }
                if (ev and SnakeGame.DIED != 0) {
                    fb.haptic(Haptic.Reject); fb.sound(Sound.Bonk)
                    cue = Cues.cue(OneShot.Dizzy)
                    phase = Phase.Over; newBest = record(game.eaten)
                    if (!still) scope.launch { for (k in listOf(10f, -8f, 6f, -4f, 0f)) shake.animateTo(k, tween(45)) }
                }
                frame.longValue++
            }
            Spacer(Modifier.fillMaxSize().swipes(true) { if (phase == Phase.Playing) game.turn(it) }
                .keys(phase) { if (phase == Phase.Playing) { if (it in UP..LEFT) game.turn(it) } else if (it == ACT) start() }
                .drawWithCache {
                    val cell = cellDp * density
                    val top = padY * density
                    val left = padX * density
                    // The checkerboard is built once per size: one path drawn per frame.
                    val checks = Path().apply {
                        for (y in 0 until rows) for (x in 0 until cols) if ((x + y) % 2 == 0) addRect(androidx.compose.ui.geometry.Rect(left + x * cell, top + y * cell, left + (x + 1) * cell, top + (y + 1) * cell))
                    }
                    val tick = Path()
                    val checkColor = c.surface2.copy(alpha = .45f)
                    onDrawBehind {
                        frame.longValue
                        val g = game
                        val kk = if (still) 1f else g.progress
                        drawPath(checks, checkColor)
                        // The task dot: in the color of the bot it brings, a check mark on it, breathing.
                        val f = g.food
                        if (f >= 0) {
                            val tint = tints[g.eaten % tints.size]
                            val pulse = if (still) 1f else 1f + .08f * sin(frame.longValue * .12f)
                            val s = cell * .62f * pulse
                            val fx = left + (f % cols + .5f) * cell; val fy = top + (f / cols + .5f) * cell
                            drawRoundRect(tint, Offset(fx - s / 2, fy - s / 2), Size(s, s), CornerRadius(s * .3f))
                            tick.rewind(); tick.moveTo(fx - s * .22f, fy); tick.lineTo(fx - s * .05f, fy + s * .18f); tick.lineTo(fx + s * .25f, fy - s * .18f)
                            drawPath(tick, BuddyColors.eyeOn(tint), style = Stroke(s * .12f, cap = StrokeCap.Round))
                        }
                        // The crew, tail first, gliding cell to cell; a bulge runs down after each bite.
                        var lx = 0f; var ly = 0f
                        for (i in g.length - 1 downTo 0) {
                            val a = g.prev[i]; val b = g.body[i]
                            val x = left + ((a % cols) + ((b % cols) - (a % cols)) * kk + .5f) * cell
                            val y = top + ((a / cols) + ((b / cols) - (a / cols)) * kk + .5f) * cell
                            if (i < g.length - 1) drawLine(tints[i % tints.size].copy(alpha = .35f), Offset(lx, ly), Offset(x, y), cell * .38f, StrokeCap.Round)
                            lx = x; ly = y
                            if (i == 0) continue // the head is a live buddy, drawn above
                            val bulge = if (still) 1f else 1f + .28f * max(0f, 1f - abs(i - gulp[0] * 16f) / 1.6f)
                            val s = (cell * 1.02f * bulge).roundToInt()
                            val img = faces[(i - 1) % faces.size]
                            drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset((x - s / 2f).roundToInt(), (y - s / 2f).roundToInt()), IntSize(s, s), filterQuality = FilterQuality.Medium)
                        }
                    }
                })
            // The brain leads, as a live buddy (blinks, hops when it eats), bigger than the crew and on a ring so it stands
            // out; its eyes look the way it's heading. Kept inside the field even at a wall.
            val head = cellDp * 1.5f
            val fieldW = maxWidth.value; val fieldH = maxHeight.value
            val look = remember { FloatArray(3) }
            Box(Modifier.align(AbsoluteAlignment.TopLeft).size(head.dp).absoluteOffset {
                frame.longValue
                val d = game.dir // the eyes follow the heading (read per frame here, not in composition)
                look[0] = if (d == RIGHT) 1f else if (d == LEFT) -1f else 0f; look[1] = if (d == DOWN) 1f else if (d == UP) -1f else 0f
                look[2] = if (phase == Phase.Playing) 1f else 0f
                val kk = if (still) 1f else game.progress
                val a = game.prev[0]; val b = game.body[0]
                val x = (a % cols) + ((b % cols) - (a % cols)) * kk
                val y = (a / cols) + ((b / cols) - (a / cols)) * kk
                val ox = (padX + (x + .5f) * cellDp - head / 2).coerceIn(0f, max(0f, fieldW - head))
                val oy = (padY + (y + .5f) * cellDp - head / 2).coerceIn(0f, max(0f, fieldH - head))
                IntOffset((ox * density).roundToInt(), (oy * density).roundToInt())
            }.zIndex(1f), contentAlignment = Alignment.Center) {
                // No ring: the head stands out by size alone (the owner asked for no circle around it).
                Buddy(brain, if (phase == Phase.Over) Mood.Error else if (phase == Phase.Playing) Mood.Working else Mood.Idle, (head * .92f).dp, gaze = look, cue = cue, interactive = false)
            }
            ConfettiLayer(confetti)
            if (phase != Phase.Playing) Overlay(phase, p.snakeHint, "$score", best?.let(p.best), newBest, ::start)
        }
    }
}

// ---------------------------------------------------------------- 2048 bots

/** Share of a move's animation spent sliding; the rest pops merges and grows the new tile in. */
private const val SLIDE = .5f
private const val MOVE_MS = 300

@Composable
internal fun MergeView(specs: List<BuddySpec>, best: Int?, record: (Int) -> Boolean) {
    val c = Theme.colors
    val p = S.play
    val fb = LocalFeedback.current
    val still = LocalMotion.current == MotionLevel.Off
    val scope = rememberCoroutineScope()
    // Small tiles are the crew, the brain is the big one at the end of each cycle.
    val order = remember(specs) { if (specs.size > 1) specs.drop(1) + specs[0] else specs }
    var round by remember { mutableIntStateOf(0) }
    var game by remember { mutableStateOf(Game2048(5)) } // replaced (not re-remembered) on a new round: see EchoView
    var version by remember { mutableIntStateOf(0) }
    var over by remember { mutableStateOf(false) }
    var newBest by remember { mutableStateOf(false) }
    var hostCue by remember { mutableStateOf<BuddyCue?>(null) }
    // One clock per move: 0 → 1 over MOVE_MS. Every tile's slide, stretch, pop and spawn is a function of it.
    val anim = remember { Animatable(1f) }
    val animState = anim.asState()
    val popped by remember { derivedStateOf { anim.value >= SLIDE } }
    val nudge = remember { Animatable(0f) }
    val confetti = rememberConfetti()
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val geo = remember { FloatArray(2) } // gap, cell (dp) for confetti positions
    LocalPlayProbe.current(game)
    fun newRound() { round++; game = Game2048(round * 31337 + 5); version++; over = false; newBest = false; confetti.clear(); scope.launch { anim.snapTo(1f) } }
    fun move(d: Int) {
        if (over && d == ACT) return newRound()
        if (over || d !in UP..LEFT) return
        val top = game.top
        if (!game.move(d)) {
            // Nothing can slide that way: the board leans and springs back instead of doing nothing.
            if (!still) scope.launch { nudge.snapTo(0f); nudge.animateTo(1f, tween(60)); nudge.animateTo(0f, spring(dampingRatio = .4f, stiffness = 900f)) }
            return
        }
        version++
        scope.launch { if (still) anim.snapTo(1f) else { anim.snapTo(0f); anim.animateTo(1f, tween(MOVE_MS, easing = LinearEasing)) } }
        if (game.top > top && game.top >= 6) {
            fb.haptic(Haptic.Confirm); fb.sound(Sound.Bloop)
            hostCue = Cues.cue(if (game.top >= 8) OneShot.Done else OneShot.Laugh)
            // Confetti from the new record tile.
            val at = (0 until 16).firstOrNull { game.cells[it] == game.top && game.merged[it] } ?: 0
            val (gap, cell) = geo[0] to geo[1]
            confetti.burst((gap + (at % 4) * (cell + gap) + cell / 2) * density, (gap + (at / 4) * (cell + gap) + cell / 2) * density, 28, order.map { it.color }, 560f * density)
        } else if (game.gained > 0) { fb.haptic(Haptic.Tick); if (game.gained >= 32) hostCue = Cues.cue(OneShot.Hop) }
        if (game.over) {
            over = true; fb.haptic(Haptic.Reject); fb.sound(Sound.Bonk)
            hostCue = Cues.cue(OneShot.Dizzy); newBest = record(game.score)
        }
    }
    Column(Modifier.fillMaxSize()) {
        val crowded = remember(version) { game.free <= 2 }
        ScoreRow(game.score.also { version }, best, host = {
            Buddy(specs[0], if (over) Mood.Error else if (crowded) Mood.NeedsYou else Mood.Idle, 40.dp, cue = hostCue, interactive = false)
        }) {
            PillButton(p.newGame, ::newRound, height = 40.dp, kind = ButtonKind.Quiet, haptic = Haptic.Tick)
        }
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().swipes(false, ::move).keys(round, ::move), contentAlignment = Alignment.TopCenter) {
            val side = min(maxWidth.value, maxHeight.value)
            val gap = side * .028f
            val cell = (side - gap * 5) / 4
            geo[0] = gap; geo[1] = cell
            val dir = game.lastDir.also { version }
            Box(Modifier.size(side.dp).graphicsLayer {
                val k = nudge.value * 6f * density
                translationX = if (dir == LEFT) -k else if (dir == RIGHT) k else 0f
                translationY = if (dir == UP) -k else if (dir == DOWN) k else 0f
            }.clip(RoundedCornerShape(Radius.card.dp)).background(c.surface).drawBehind {
                val r = CornerRadius(cell * .2f * density)
                for (i in 0 until 16) {
                    val x = (gap + (i % 4) * (cell + gap)) * density; val y = (gap + (i / 4) * (cell + gap)) * density
                    drawRoundRect(c.surface2, Offset(x, y), Size(cell * density, cell * density), r)
                }
            }, contentAlignment = AbsoluteAlignment.TopLeft) {
                version
                // One keyed loop for tiles and the eaten ghosts, so a tile that just got eaten keeps its slide.
                for (j in 0 until 16 + game.ghosts) {
                    val ghost = j >= 16
                    val g = j - 16
                    val v = if (ghost) game.ghostVal[g] else game.cells[j]
                    if (v == 0) continue
                    val to = if (ghost) game.ghostTo[g] else j
                    val from = if (ghost) game.ghostFrom[g] else game.from[j].let { if (it < 0) j else it }
                    val merged = !ghost && game.merged[j]
                    val spawned = !ghost && j == game.spawned
                    // A merged tile shows its old value until the partner arrives, then pops into the new bot.
                    val shown = if (merged && !popped) v - 1 else v
                    key(if (ghost) game.ghostId[g] else game.ids[j]) {
                        Tile(order, shown, from, to, gap, cell, dir, animState, merged, spawned, ghost, version, popped)
                    }
                }
            }
            ConfettiLayer(confetti, Modifier.size(side.dp))
            if (over) Overlay(Phase.Over, "", "${game.score}", best?.let(p.best), newBest, ::newRound)
        }
    }
}

/**
 * One 2048 tile: a bot that grows with its value. Everything moves off the shared move clock [t]: it slides from
 * [from] to [to] (eased, stretched along [dir]), a merged tile pops, a spawned one grows in with an overshoot, a ghost
 * (the eaten partner) slides under its partner and vanishes. Reads [t] only in layout/draw: no recomposition per frame.
 */
@Composable
private fun Tile(
    order: List<BuddySpec>, value: Int, from: Int, to: Int, gap: Float, cell: Float, dir: Int, t: State<Float>,
    merged: Boolean, spawned: Boolean, ghost: Boolean, version: Int, popped: Boolean,
) {
    val c = Theme.colors
    val spec = order[(value - 1) % order.size]
    val fx = gap + (from % 4) * (cell + gap); val fy = gap + (from / 4) * (cell + gap)
    val tx = gap + (to % 4) * (cell + gap); val ty = gap + (to / 4) * (cell + gap)
    val horizontal = dir == LEFT || dir == RIGHT
    val moves = from != to
    val cue = remember(version, popped) { if (merged && popped) Cues.cue(if (value >= 7) OneShot.Spin else OneShot.Hop) else null }
    // 2 → a small bot; it grows with every doubling, nearly filling the tile by 2048.
    val frac = (.4f + .045f * (value - 1)).coerceAtMost(.86f)
    Box(
        Modifier.size(cell.dp).absoluteOffset {
            val k = easeOutCubic(t.value / SLIDE)
            IntOffset(((fx + (tx - fx) * k) * density).roundToInt(), ((fy + (ty - fy) * k) * density).roundToInt())
        }.zIndex(if (ghost) 0f else if (merged) 2f else 1f).graphicsLayer {
            val v = t.value
            var sx = 1f; var sy = 1f
            if (moves && v < SLIDE) {
                // Squash and stretch: long along the slide at mid-flight, back to square on arrival.
                val s = .14f * sin(PI.toFloat() * easeOutCubic(v / SLIDE))
                if (horizontal) { sx += s; sy -= s * .6f } else { sy += s; sx -= s * .6f }
            }
            if (merged && v >= SLIDE) { val q = (v - SLIDE) / (1f - SLIDE); val b = .22f * sin(PI.toFloat() * q); sx += b; sy += b }
            if (spawned) { val q = ((v - .45f) / .55f).coerceIn(0f, 1f); val s = if (v < .45f) 0f else easeOutBack(q, 2.2f); sx = s; sy = s }
            scaleX = sx; scaleY = sy
            alpha = if (ghost && v >= SLIDE) 0f else 1f
            transformOrigin = TransformOrigin.Center
        }.clip(RoundedCornerShape((cell * .2f).dp)).background(BuddyColors.mix(spec.color, c.surface, if (c.dark) .7f else .78f)),
        contentAlignment = Alignment.Center,
    ) {
        Buddy(spec, Mood.Idle, (cell * frac).dp, Modifier.padding(bottom = (cell * .08f).dp), cue = cue, interactive = false)
        Txt("${1 shl value}", Theme.type.micro, c.text.copy(alpha = .7f), Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp), maxLines = 1)
    }
}

// ---------------------------------------------------------------- Bot echo

private val padSounds = arrayOf(Sound.Pop, Sound.Bloop, Sound.Plonk, Sound.BoopBoop)

@Composable
internal fun EchoView(specs: List<BuddySpec>, best: Int?, record: (Int) -> Boolean) {
    val c = Theme.colors
    val p = S.play
    val fb = LocalFeedback.current
    val still = LocalMotion.current == MotionLevel.Off
    val scope = rememberCoroutineScope()
    val paused = rememberUpdatedState(LocalPlayPaused.current)
    // Four distinct bots: his own first; with fewer than four, the rest are recolored copies.
    val pads = remember(specs) {
        List(EchoGame.PADS) { i ->
            val s = specs[i % specs.size]
            if (i < specs.size) s else s.copy(id = s.id + "#$i", color = BuddyColors.palette[(i * 3 + 1) % BuddyColors.palette.size])
        }
    }
    var round by remember { mutableIntStateOf(0) }
    // Held in state and replaced on a new round, so every handler (old closures too) reaches the current game.
    var game by remember { mutableStateOf(EchoGame(11)) }
    var phase by remember { mutableStateOf(Phase.Ready) }
    var listening by remember { mutableStateOf(false) } // the player's turn
    var lit by remember { mutableIntStateOf(-1) }
    var rounds by remember { mutableIntStateOf(0) }
    var newBest by remember { mutableStateOf(false) }
    val cues = remember { mutableStateOf(arrayOfNulls<BuddyCue>(EchoGame.PADS)) }
    var show by remember { mutableIntStateOf(0) } // bumps to play the sequence
    val shake = remember { Animatable(0f) }
    val confetti = rememberConfetti()
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val size = remember { FloatArray(2) }
    LocalPlayProbe.current(game)
    fun start() { round++; game = EchoGame(round * 6151 + 11); rounds = 0; newBest = false; lit = -1; listening = false; confetti.clear(); phase = Phase.Playing; show++ }
    fun cue(i: Int, s: OneShot) { cues.value = cues.value.copyOf().also { it[i] = Cues.cue(s) } }
    fun all(s: OneShot) { cues.value = Array(EchoGame.PADS) { Cues.cue(s) } }
    fun chirp(i: Int) { lit = i; cue(i, OneShot.Boing); fb.sound(padSounds[i]); fb.haptic(Haptic.Tick) }
    LaunchedEffect(show) {
        if (phase != Phase.Playing) return@LaunchedEffect
        listening = false
        delay(650)
        for (k in 0 until game.length) {
            paused.awaitResume()
            chirp(game.seq[k]); delay(game.stepMs()); lit = -1; delay(game.stepMs() * 2 / 5)
        }
        listening = true
    }
    fun press(i: Int) {
        if (phase != Phase.Playing || !listening || i !in 0 until EchoGame.PADS) return
        chirp(i)
        scope.launch { delay(170); if (lit == i) lit = -1 }
        val ev = game.press(i)
        if (ev and EchoGame.WRONG != 0) {
            listening = false; fb.sound(Sound.Bonk); fb.haptic(Haptic.Reject); all(OneShot.Dizzy)
            phase = Phase.Over; newBest = record(game.rounds)
            if (!still) scope.launch { for (k in listOf(10f, -8f, 6f, -4f, 0f)) shake.animateTo(k, tween(45)) }
        } else if (ev and EchoGame.ROUND != 0) {
            listening = false; rounds = game.rounds
            scope.launch {
                delay(300); all(if (rounds % 5 == 0) OneShot.Dance else OneShot.Nod)
                if (rounds % 5 == 0) { fb.sound(Sound.Bloop); confetti.burst(size[0] / 2, size[1] / 2, 36, pads.map { it.color }, 600f * density) }
                game.extend(); show++
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScoreRow(rounds, best) {
            if (phase == Phase.Playing) Txt(if (listening) p.yourGo else p.watch, Theme.type.callout, if (listening) c.accent else c.secondary, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().graphicsLayer { translationX = shake.value * density }
                .keys(round) { k ->
                    when (k) {
                        ACT -> if (phase != Phase.Playing) start()
                        UP -> press(0); RIGHT -> press(1); LEFT -> press(2); DOWN -> press(3)
                        in DIGIT + 1..DIGIT + 4 -> press(k - DIGIT - 1)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            size[0] = constraints.maxWidth.toFloat(); size[1] = constraints.maxHeight.toFloat()
            val side = min(maxWidth.value, maxHeight.value)
            val pad = (side - 12f) / 2f
            Column(Modifier.size(side.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (r in 0 until 2) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (col in 0 until 2) {
                        val i = r * 2 + col
                        val on by animateFloatAsState(if (lit == i) 1f else 0f, if (still) snap() else if (lit == i) tween(70) else tween(260))
                        val spec = pads[i]
                        Box(
                            Modifier.size(pad.dp).graphicsLayer { val s = 1f + .06f * on; scaleX = s; scaleY = s }
                                .clip(RoundedCornerShape(Radius.card.dp))
                                .background(BuddyColors.mix(spec.color, c.surface, .82f - .5f * on))
                                .squish({ press(i) }, scale = .95f, haptic = null, label = spec.name),
                            contentAlignment = Alignment.Center,
                        ) {
                            Buddy(spec, if (phase == Phase.Over) Mood.Error else if (listening) Mood.Listening else Mood.Idle, (pad * .56f).dp, cue = cues.value[i], interactive = false)
                        }
                    }
                }
            }
            ConfettiLayer(confetti)
            if (phase != Phase.Playing) Overlay(phase, p.echoHint, "$rounds", best?.let(p.best), newBest, ::start)
        }
    }
}
