package app.buddyverse.ui.play

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
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
import app.buddyverse.buddy.Rng
import app.buddyverse.buddy.easeOutBack
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.PillButton
import app.buddyverse.design.Radius
import app.buddyverse.design.Sound
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.Talk
import app.buddyverse.i18n.play
import app.buddyverse.ui.home.Cues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tic-tac-toe / Four in a row against the user's bots, easy → hard ([ladderStrengths]). Beat a bot to unlock the next
 * ([cleared] = how many are beaten, saved through [save]); the brain at the top is unbeatable at tic-tac-toe, so holding
 * it to a draw clears the ladder. The bot visibly thinks (thinking face, eyes on the cells it weighs, ~0.6–1.2 s), places
 * its mark with a little animation and reacts with a face and a speech bubble.
 */
@Composable
internal fun DuelView(g: Game, ladder: List<BuddySpec>, cleared: Int, save: (Int) -> Boolean) {
    val c = Theme.colors
    val p = S.play
    val fb = LocalFeedback.current
    val still = LocalMotion.current == MotionLevel.Off
    val scope = rememberCoroutineScope()
    val paused = rememberUpdatedState(LocalPlayPaused.current)
    val ttt = g == Game.Ttt
    val strengths = remember(ladder.size) { ladderStrengths(ladder.size) }
    val top = ladder.size - 1
    var sel by remember { mutableIntStateOf(min(cleared, top)) }
    val opp = ladder[sel]
    val tier = strengths[sel]
    val rng = remember { Rng(4242) }
    // One live round object for the whole match (see DuelRound): never re-created, so no handler can hold a stale board.
    val m = remember { DuelRound(ttt) }
    val round = m.round
    val board = m.board
    val four = m.four
    val version = m.version
    val turn = m.turn
    val result = m.result
    val winCells = m.winCells
    val lastCell = m.lastCell
    // The bot thinks exactly while it's its turn in a live round: no flag that a cancelled bot turn could leave set.
    val thinking = turn == 2 && result == 0
    LocalPlayProbe.current(m)
    var cue by remember { mutableStateOf<BuddyCue?>(null) }
    var bubble by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var you by remember { mutableIntStateOf(0) }
    var them by remember { mutableIntStateOf(0) }
    val ladderNow by rememberUpdatedState(ladder)
    val clearedNow by rememberUpdatedState(cleared)
    val saveNow by rememberUpdatedState(save)
    var justUnlocked by remember { mutableStateOf(false) }
    var doneAt by remember { mutableStateOf(0L) }
    val gaze = remember { FloatArray(3) }
    val confetti = rememberConfetti()
    val fieldSize = remember { FloatArray(2) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density

    fun say(t: Talk) { val lines = p.talk(strengths[sel], t); bubble = Cues.next() to lines[(rng.next() * lines.size).toInt().coerceIn(0, lines.size - 1)] }
    LaunchedEffect(sel) { delay(250); say(Talk.Hello) }

    fun finish(r: Int) {
        val opp = ladderNow[sel]; val tier = strengths[sel]
        doneAt = app.buddyverse.core.nowMillis()
        when (r) {
            1 -> {
                you++; fb.sound(Sound.Bloop); fb.haptic(Haptic.Confirm); say(Talk.BotLoses); cue = Cues.cue(OneShot.Dizzy)
                confetti.burst(fieldSize[0] / 2, fieldSize[1] * .7f, 40, listOf(c.accent, opp.color, BuddyColors.palette[2], BuddyColors.palette[5]), 620f * density)
            }
            2 -> { them++; fb.sound(Sound.Plonk); fb.haptic(Haptic.Reject); say(Talk.BotWins); cue = Cues.cue(OneShot.Laugh) }
            else -> { fb.haptic(Haptic.Tick); say(Talk.Draw); cue = Cues.cue(OneShot.Startle) }
        }
        // Progress: beating this bot (or holding the unbeatable brain to a draw at tic-tac-toe) unlocks the next.
        val clears = r == 1 || (r == DRAW && ttt && sel == top && tier == 3)
        if (clears && sel >= clearedNow) { saveNow(sel + 1); justUnlocked = sel < top }
        m.result = r
    }

    /** Places [who]'s mark at [at] (a cell for tic-tac-toe, a column for four in a row) and settles the game. */
    fun place(at: Int, who: Int) {
        val out = m.place(at, who)
        if (out != 0) finish(out)
        fb.sound(if (who == 1) Sound.Pop else Sound.Plonk)
    }

    fun human(at: Int) {
        if (!m.canPlay(at)) return
        fb.haptic(Haptic.Tick)
        place(at, 1)
    }

    /** Every new round (rematch, automatic next, a new opponent) goes through here: board, turn and locks reset. */
    fun startRound(r: Int, s: Int) {
        if (s != sel) { you = 0; them = 0 }
        sel = s; gaze[2] = 0f; justUnlocked = false
        m.start(r)
    }

    // The bot's turn: think (face + eyes over a few candidate spots), then move.
    LaunchedEffect(turn, result, round, sel) {
        if (m.turn != 2 || m.result != 0) return@LaunchedEffect
        val started = app.buddyverse.core.nowMillis()
        val move = withContext(Dispatchers.Default) {
            if (ttt) TicTacToe.move(m.board.copyOf(), 2, tier, rng) else ConnectFourAi.move(m.four, 2, tier, rng)
        }
        val total = 600 + (rng.next() * 600).toLong()
        val glances = IntArray(3) { if (it == 2) move else if (ttt) (0..8).filter { c -> board[c] == 0 }.let { f -> f[(rng.next() * f.size).toInt().coerceIn(0, f.size - 1)] } else (rng.next() * ConnectFour.COLS).toInt().coerceIn(0, 6) }
        val left = max(0L, total - (app.buddyverse.core.nowMillis() - started))
        for (k in 0 until 3) {
            val at = glances[k]
            if (ttt) { gaze[0] = ((at % 3) - 1) * .8f; gaze[1] = .25f + (at / 3) * .35f } else { gaze[0] = (at - 3) / 3f * .85f; gaze[1] = .55f }
            gaze[2] = 1f
            delay(left / 3)
            paused.awaitResume()
        }
        gaze[2] = 0f
        cue = Cues.cue(OneShot.Nod)
        place(move, 2)
    }

    fun next() {
        // A tap that was meant for the last move must not skip the result straight away.
        if (app.buddyverse.core.nowMillis() - doneAt < 700) return
        if (justUnlocked) startRound(0, min(top, sel + 1)) else startRound(m.round + 1, sel)
        confetti.clear()
    }

    // A short card (small phone, keyboard, card dragged low) gets slimmer rows, so the board keeps most of the room.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxHeight < 460.dp
    Column(Modifier.fillMaxSize()) {
        // The ladder: faces easy → hard; locked ones dimmed with a lock.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ladder.forEachIndexed { i, s ->
                val open = i <= min(cleared, top)
                Box(
                    Modifier.size(if (compact) 36.dp else 44.dp).clip(CircleShape).then(if (i == sel) Modifier.border(2.dp, c.accent, CircleShape) else Modifier)
                        .squish({ if (open && i != sel && !thinking) startRound(0, i) }, enabled = open, label = s.name),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.graphicsLayer { alpha = if (open) 1f else .35f }) { Buddy(s, Mood.Idle, if (compact) 26.dp else 32.dp, interactive = false) }
                    if (!open) Icon(Icons.lockFilled, c.secondary, 16.dp)
                    if (i < cleared) Box(Modifier.align(Alignment.BottomEnd).size(16.dp).clip(CircleShape).background(c.ground).padding(2.dp).clip(CircleShape).background(c.success), contentAlignment = Alignment.Center) { Icon(Icons.check, Color.White, 10.dp) }
                }
            }
        }
        // The opponent: live face, name and level, what it's doing, its speech bubble.
        Row(Modifier.fillMaxWidth().height(if (compact) 60.dp else 84.dp), verticalAlignment = Alignment.CenterVertically) {
            val mood = when { thinking -> Mood.Thinking; result == 1 -> Mood.Error; turn == 1 && result == 0 -> Mood.Listening; else -> Mood.Idle }
            Buddy(opp, mood, if (compact) 44.dp else 64.dp, cue = cue, gaze = gaze, interactive = false)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Txt(p.level(sel + 1, opp.name), Theme.type.headline, c.text, maxLines = 1)
                // The status line; a speech bubble takes its place while the bot talks.
                Box(Modifier.fillMaxWidth().height(if (compact) 32.dp else 44.dp), contentAlignment = Alignment.CenterStart) {
                    val status = when (result) {
                        1 -> p.youWin; 2 -> p.youLose(opp.name); DRAW -> p.draw
                        else -> if (thinking) p.thinking(opp.name) else if (turn == 1) p.yourTurn else ""
                    }
                    Txt(status, Theme.type.callout, if (result == 1) c.accent else c.secondary, maxLines = 1)
                    SpeechBubble(bubble, opp.color)
                }
            }
        }
        // The board takes whatever the rows leave and sizes from it: square (tic-tac-toe) or 7×7 cells (four in a row).
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            fieldSize[0] = constraints.maxWidth.toFloat(); fieldSize[1] = constraints.maxHeight.toFloat()
            if (ttt) TttBoard(board, version, lastCell, winCells, opp.color, min(maxWidth.value, maxHeight.value), still, result != 0, ::human, ::next, round + sel * 1000)
            else FourBoard(four, version, lastCell, winCells, opp.color, maxWidth.value, maxHeight.value, still, result != 0, ::human, ::next, round + sel * 1000)
            ConfettiLayer(confetti)
        }
        Row(Modifier.fillMaxWidth().height(if (compact) 44.dp else 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(p.score(you, them), Theme.type.caption, c.secondary, Modifier.weight(1f), maxLines = 1)
            if (result != 0) {
                val label = if (justUnlocked) p.next(ladder[min(top, sel + 1)].name) else p.again
                PillButton(label, ::next, height = if (compact) 40.dp else 48.dp, kind = if (justUnlocked) ButtonKind.Primary else ButtonKind.Outline, haptic = Haptic.Tick)
            }
        }
        if (result != 0 && sel == top && cleared > top) Txt(p.cleared, Theme.type.caption, c.accent, maxLines = 2)
    }
    }
}

/** A short line from the bot: grows from its face, stays ~2.6 s. */
@Composable
private fun SpeechBubble(bubble: Pair<Long, String>?, tint: Color) {
    val c = Theme.colors
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(bubble?.first) { val b = bubble ?: return@LaunchedEffect; shown = b.second; delay(2_600); shown = null }
    val last = remember { arrayOfNulls<String>(1) }
    AnimatedVisibility(
        shown != null, Modifier.zIndex(5f),
        enter = scaleIn(spring(dampingRatio = .5f, stiffness = 600f), .6f, TransformOrigin(0f, .5f)) + fadeIn(tween(100)),
        exit = scaleOut(tween(140), .8f, TransformOrigin(0f, .5f)) + fadeOut(tween(140)),
    ) {
        val text = shown ?: last[0] ?: return@AnimatedVisibility
        last[0] = text
        Box(
            Modifier.widthIn(max = 260.dp).heightIn(min = 36.dp).clip(RoundedCornerShape(6.dp, Radius.bubble.dp, Radius.bubble.dp, Radius.bubble.dp))
                .background(BuddyColors.mix(tint, c.surface, if (c.dark) .55f else .7f)).padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) { Txt(text, Theme.type.callout, c.text, maxLines = 1) }
    }
}

// ---------------------------------------------------------------- tic-tac-toe board

@Composable
private fun TttBoard(
    board: IntArray, version: Int, last: Int, win: IntArray?, botColor: Color, side: Float, still: Boolean,
    done: Boolean, onCell: (Int) -> Unit, onNext: () -> Unit, focusKey: Int,
) {
    val c = Theme.colors
    var cursor by remember { mutableIntStateOf(4) }
    var keyed by remember { mutableStateOf(false) }
    val tap by rememberUpdatedState(onCell)
    val next by rememberUpdatedState(onNext)
    val isDone by rememberUpdatedState(done)
    val cell = side / 3f
    val sweep = remember(win) { Animatable(if (win == null || still) 1f else 0f) }
    LaunchedEffect(win) { if (win != null) { delay(250); sweep.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) } }
    Box(
        Modifier.size(side.dp)
            .keys(focusKey) { k ->
                if (isDone) { if (k == ACT) next(); return@keys }
                keyed = true
                when (k) {
                    UP -> cursor = (cursor + 6) % 9; DOWN -> cursor = (cursor + 3) % 9
                    LEFT -> cursor = cursor / 3 * 3 + (cursor % 3 + 2) % 3; RIGHT -> cursor = cursor / 3 * 3 + (cursor % 3 + 1) % 3
                    ACT -> tap(cursor)
                    in DIGIT + 1..DIGIT + 9 -> tap(k - DIGIT - 1)
                }
            }
            .pointerInput(side) {
                detectTapGestures { o ->
                    keyed = false
                    if (isDone) { next(); return@detectTapGestures }
                    val cx = (o.x / (size.width / 3f)).toInt().coerceIn(0, 2); val cy = (o.y / (size.height / 3f)).toInt().coerceIn(0, 2)
                    tap(cy * 3 + cx)
                }
            }
            .drawBehind {
                val w = size.width; val k = w / 3f
                if (keyed && !done) drawRoundRect(c.surface2, Offset((cursor % 3) * k + 6f, (cursor / 3) * k + 6f), Size(k - 12f, k - 12f), CornerRadius(k * .2f))
                val stroke = w * .022f
                for (i in 1..2) {
                    drawLine(c.hairline, Offset(i * k, k * .08f), Offset(i * k, w - k * .08f), stroke, StrokeCap.Round)
                    drawLine(c.hairline, Offset(k * .08f, i * k), Offset(w - k * .08f, i * k), stroke, StrokeCap.Round)
                }
            },
    ) {
        version
        for (i in 0 until 9) {
            val v = board[i]
            if (v == 0) continue
            key(i, focusKey) {
                Box(Modifier.align(AbsoluteAlignment.TopLeft).size(cell.dp).absoluteOffset { IntOffset(((i % 3) * cell * density).roundToInt(), ((i / 3) * cell * density).roundToInt()) }) {
                    Mark(v, if (v == 1) c.accent else botColor, still, i == last)
                }
            }
        }
        if (win != null) {
            val wc = if (board[win[0]] == 1) c.accent else botColor
            Spacer(Modifier.fillMaxSize().zIndex(2f).drawBehind {
                val k = size.width / 3f
                fun at(i: Int) = Offset((i % 3 + .5f) * k, (i / 3 + .5f) * k)
                val a = at(win[0]); val b = at(win[2])
                val d = b - a
                val s = a - d * .18f; val e = b + d * .18f
                drawLine(wc.copy(alpha = .85f), s, s + (e - s) * sweep.value, size.width * .035f, StrokeCap.Round)
            })
        }
    }
}

/** One mark drawing itself in: your X in two strokes, the bot's O as a ring that blinks a face open. */
@Composable
private fun Mark(player: Int, tint: Color, still: Boolean, fresh: Boolean) {
    val k = remember { Animatable(if (still || !fresh) 1f else 0f) }
    LaunchedEffect(Unit) { if (k.value < 1f) k.animateTo(1f, tween(360, easing = FastOutSlowInEasing)) }
    Spacer(Modifier.fillMaxSize().graphicsLayer {
        val s = .7f + .3f * easeOutBack(k.value, 2.4f); scaleX = s; scaleY = s
    }.drawBehind {
        val w = size.width
        val inset = w * .26f
        val stroke = w * .1f
        val v = k.value
        if (player == 1) {
            val a = min(1f, v * 2f); val b = max(0f, v * 2f - 1f)
            drawLine(tint, Offset(inset, inset), Offset(inset + (w - 2 * inset) * a, inset + (w - 2 * inset) * a), stroke, StrokeCap.Round)
            if (b > 0f) drawLine(tint, Offset(w - inset, inset), Offset(w - inset - (w - 2 * inset) * b, inset + (w - 2 * inset) * b), stroke, StrokeCap.Round)
        } else {
            drawArc(tint, -90f, 360f * v, false, Offset(inset, inset), Size(w - 2 * inset, w - 2 * inset), style = Stroke(stroke, cap = StrokeCap.Round))
            if (v > .75f) {
                val a = (v - .75f) * 4f
                val r = w * .045f
                drawCircle(tint.copy(alpha = a), r * 1.25f, Offset(w * .43f, w * .47f))
                drawCircle(tint.copy(alpha = a), r * 1.25f, Offset(w * .57f, w * .47f))
            }
        }
    })
}

// ---------------------------------------------------------------- four in a row board

@Composable
private fun FourBoard(
    g: ConnectFour, version: Int, last: Int, win: IntArray?, botColor: Color, maxW: Float, maxH: Float, still: Boolean,
    done: Boolean, onCol: (Int) -> Unit, onNext: () -> Unit, focusKey: Int,
) {
    val c = Theme.colors
    val cols = ConnectFour.COLS; val rows = ConnectFour.ROWS
    val cell = min(maxW / cols, maxH / (rows + 1))
    var hover by remember { mutableIntStateOf(3) }
    val drop by rememberUpdatedState(onCol)
    val next by rememberUpdatedState(onNext)
    val isDone by rememberUpdatedState(done)
    // The last disc falls from above the board and bounces into its slot.
    val fall = remember { Animatable(1f) }
    LaunchedEffect(version) {
        if (last < 0 || still) { fall.snapTo(1f); return@LaunchedEffect }
        fall.snapTo(0f); fall.animateTo(1f, spring(dampingRatio = .42f, stiffness = 420f))
    }
    val pulse = remember(win) { Animatable(0f) }
    LaunchedEffect(win) { if (win != null && !still) { delay(300); repeat(3) { pulse.animateTo(1f, tween(220)); pulse.animateTo(0f, tween(260)) } } }
    // The winning line's two end cells (sorted along the line), worked out once.
    val ends = remember(win) { win?.sortedBy { (it % cols) * 10 + it / cols }?.let { intArrayOf(it.first(), it.last()) } }
    Spacer(
        Modifier.size((cell * cols).dp, (cell * (rows + 1)).dp)
            .keys(focusKey) { k ->
                if (isDone) { if (k == ACT) next(); return@keys }
                when (k) {
                    LEFT -> hover = (hover + cols - 1) % cols; RIGHT -> hover = (hover + 1) % cols
                    ACT, DOWN -> drop(hover)
                    in DIGIT + 1..DIGIT + cols -> { hover = k - DIGIT - 1; drop(hover) }
                }
            }
            .pointerInput(cell) {
                awaitPointerEventScope {
                    var pressed = false
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull() ?: continue
                        hover = (ch.position.x / (size.width.toFloat() / cols)).toInt().coerceIn(0, cols - 1)
                        when (e.type) {
                            PointerEventType.Press -> pressed = true
                            PointerEventType.Release -> if (pressed) { pressed = false; if (isDone) next() else drop(hover) }
                        }
                    }
                }
            }
            .drawWithCache {
                val k = size.width / cols
                val top = k
                // The board: a rounded slab with holes (even-odd), drawn over the discs so a falling disc shows through them.
                val slab = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRoundRect(RoundRect(Rect(0f, top, size.width, top + k * rows), CornerRadius(k * .35f)))
                    for (r in 0 until rows) for (cc in 0 until cols) addOval(Rect((cc + .12f) * k, top + (r + .12f) * k, (cc + .88f) * k, top + (r + .88f) * k))
                }
                val boardColor = BuddyColors.mix(c.accent, c.surface, if (c.dark) .72f else .8f)
                val radius = k * .38f
                fun androidx.compose.ui.graphics.drawscope.DrawScope.disc(color: Color, cx: Float, cy: Float, scale: Float, face: Boolean) {
                    drawCircle(color, radius * scale, Offset(cx, cy))
                    if (face) {
                        val eye = BuddyColors.eyeOn(color)
                        drawCircle(eye, radius * .14f * scale, Offset(cx - radius * .3f * scale, cy - radius * .08f * scale))
                        drawCircle(eye, radius * .14f * scale, Offset(cx + radius * .3f * scale, cy - radius * .08f * scale))
                    }
                }
                onDrawBehind {
                    version
                    drawRect(c.surface2.copy(alpha = .5f), Offset(0f, top), Size(size.width, k * rows))
                    for (i in 0 until cols * rows) {
                        val v = g.cells[i]
                        if (v == 0) continue
                        val col = i % cols; val row = i / cols
                        val color = if (v == 1) c.accent else botColor
                        val inWin = win != null && i in win
                        val s = if (inWin) 1f + .12f * pulse.value else 1f
                        val cy = if (i == last) {
                            // From above the board (row -1) into its row; the spring overshoots = the bounce.
                            top + (-1f + (row + 1) * fall.value + .5f) * k
                        } else top + (row + .5f) * k
                        disc(color, (col + .5f) * k, cy, s, v == 2)
                    }
                    drawPath(slab, boardColor)
                    if (!isDone && g.canDrop(hover)) disc(c.accent.copy(alpha = .55f), (hover + .5f) * k, k * .5f, .9f, false)
                    if (ends != null) {
                        val a = ends[0]; val b = ends[1]
                        drawLine(
                            Color.White.copy(alpha = .8f * pulse.value.coerceAtLeast(.4f)),
                            Offset((a % cols + .5f) * k, top + (a / cols + .5f) * k), Offset((b % cols + .5f) * k, top + (b / cols + .5f) * k),
                            k * .12f, StrokeCap.Round,
                        )
                    }
                }
            },
    )
}
