package app.buddyverse.ui.play

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.LocalMotion
import app.buddyverse.buddy.MotionLevel
import app.buddyverse.design.AppTheme
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Each game driven through the real composable: play a round to its end, continue (play again / next round / next
 * opponent), fold and unfold the card, background and resume the app, let "the chat" take keyboard focus, and the
 * game must still take a tap/swipe and a key.
 */
@OptIn(ExperimentalTestApi::class)
class GamesUiTest {
    private class Owner : LifecycleOwner {
        val reg = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = reg
    }

    private class Harness {
        val owner = Owner()
        val paused = mutableStateOf(false)
        val chat = FocusRequester()
        var live: Any? = null
    }

    private val specs = List(3) { BuddySpec("b$it", "Bot$it", BuddyShape.Squircle, BuddyColors.palette[it + 1]) }

    private fun game(content: @Composable () -> Unit, body: ComposeUiTest.(Harness) -> Unit): Unit = runComposeUiTest {
        mainClock.autoAdvance = false // buddies animate forever
        val h = Harness()
        setContent {
            AppTheme(false) {
                CompositionLocalProvider(
                    LocalLifecycleOwner provides h.owner, LocalPlayPaused provides h.paused.value,
                    LocalMotion provides MotionLevel.Off, LocalPlayProbe provides { h.live = it },
                ) {
                    Column(Modifier.size(420.dp, 720.dp)) {
                        Box(Modifier.size(40.dp).focusRequester(h.chat).focusable()) // stands in for the chat box
                        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
                    }
                }
            }
        }
        tick(300)
        body(h)
    }

    private fun ComposeUiTest.tick(ms: Long = 50) { mainClock.advanceTimeBy(ms); Thread.sleep(2) }

    private fun ComposeUiTest.until(what: String, maxMs: Long = 30_000, cond: () -> Boolean) {
        var t = 0L
        while (!cond()) { check(t < maxMs) { "timed out waiting: $what" }; tick(50); t += 50 }
    }

    private fun ComposeUiTest.shown(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.field() = onNodeWithTag(PLAY_FIELD)

    private fun ComposeUiTest.key(k: Key) { field().performKeyInput { pressKey(k) }; tick() }

    /** Fold → background → resume → unfold, then the field must hold keyboard focus again. */
    private fun ComposeUiTest.foldBackgroundUnfold(h: Harness) {
        h.paused.value = true; tick(600)
        h.owner.reg.currentState = Lifecycle.State.STARTED; tick(600)
        h.owner.reg.currentState = Lifecycle.State.RESUMED; tick()
        h.paused.value = false; tick(100)
        field().assertIsFocused()
    }

    /** The chat box takes the keyboard; the game must not be focused until it's touched. */
    private fun ComposeUiTest.chatTakesFocus(h: Harness) {
        runOnUiThread { h.chat.requestFocus() }; tick()
        field().assertIsNotFocused()
    }

    // ------------------------------------------------------------ Snake

    @Test fun snake() = game({ SnakeView(specs, null) { false } }) { h ->
        onNodeWithText("Start").performClick(); tick()
        until("the snake hits a wall") { shown("Play again") }
        val dead = h.live as SnakeGame
        onNodeWithText("Play again").performClick(); tick()
        val g = h.live as SnakeGame
        assertNotSame(dead, g); assertTrue(!g.over)
        // Folded and in the background, the snake must not move.
        h.paused.value = true; val head = g.body[0]; tick(1000); assertEquals(head, g.body[0], "frozen while folded")
        foldBackgroundUnfold(h)
        tick(250); assertTrue(g.body[0] != head, "moves again after unfold + resume")
        key(Key.DirectionUp); tick(250); assertEquals(UP, g.dir, "key after unfold")
        chatTakesFocus(h)
        field().performTouchInput { swipeLeft() }; tick(250); assertEquals(LEFT, g.dir, "swipe")
        field().assertIsFocused()
        key(Key.DirectionDown); tick(250); assertEquals(DOWN, g.dir, "key after a touch")
        assertTrue(!g.over)
    }

    // ------------------------------------------------------------ 2048

    @Test fun merge2048() = game({ MergeView(specs, null) { false } }) { h ->
        val rnd = Random(3)
        val dirs = listOf(Key.DirectionUp, Key.DirectionRight, Key.DirectionDown, Key.DirectionLeft)
        var n = 0
        while (!(h.live as Game2048).over) { check(n++ < 4000) { "2048 never ended" }; key(dirs[rnd.nextInt(4)]) }
        until("game over card") { shown("Play again") }
        val old = h.live
        onNodeWithText("Play again").performClick(); tick()
        val g = h.live as Game2048
        assertNotSame(old, g); assertEquals(0, g.score)
        foldBackgroundUnfold(h)
        fun moved(act: (Int) -> Unit): Boolean = (0 until 4).any { d ->
            val before = g.cells.copyOf(); act(d); tick(400); !before.contentEquals(g.cells)
        }
        assertTrue(moved { key(dirs[it]) }, "a key moves the tiles after unfold")
        chatTakesFocus(h)
        assertTrue(moved { d -> field().performTouchInput { when (d) { 0 -> swipeUp(); 1 -> swipeRight(); 2 -> swipeDown(); else -> swipeLeft() } } }, "swipe")
        field().assertIsFocused()
        assertTrue(moved { key(dirs[it]) }, "a key after a touch")
    }

    // ------------------------------------------------------------ Bot echo

    @Test fun echo() = game({ EchoView(specs, null) { false } }) { h ->
        val digits = listOf(Key.One, Key.Two, Key.Three, Key.Four)
        onNodeWithText("Start").performClick(); tick()
        until("first show") { shown("Your go") }
        val first = h.live as EchoGame
        key(digits[(first.seq[0] + 1) % 4]) // wrong pad: round over
        until("game over card") { shown("Play again") }
        onNodeWithText("Play again").performClick(); tick()
        val g = h.live as EchoGame
        assertNotSame(first, g)
        foldBackgroundUnfold(h)
        until("show after unfold") { shown("Your go") }
        key(digits[g.seq[0]]); assertEquals(1, g.rounds, "key after unfold")
        until("second show") { shown("Your go") && g.length == 2 }
        chatTakesFocus(h)
        val node = field().fetchSemanticsNode().size
        val side = minOf(node.width, node.height) / 4f
        val pad = g.seq[0]
        field().performTouchInput { click(Offset(center.x + if (pad % 2 == 0) -side else side, center.y + if (pad < 2) -side else side)) }
        tick(); assertEquals(1, g.pos, "tap on a pad")
        field().assertIsFocused()
        key(digits[g.seq[1]]); assertEquals(2, g.rounds, "key after a touch")
    }

    // ------------------------------------------------------------ duels

    @Test fun ticTacToe() = duel(Game.Ttt)

    @Test fun fourInARow() = duel(Game.Four)

    private fun duel(kind: Game) = game({ DuelView(kind, specs, 0) { true } }) { h ->
        val ttt = kind == Game.Ttt
        val m = h.live as DuelRound
        val n = if (ttt) 9 else ConnectFour.COLS
        fun free() = (0 until n).first { m.canPlay(it) }
        fun mine() = if (ttt) m.board.count { it == 1 } else m.four.cells.count { it == 1 }
        val digits = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
        fun tap(at: Int) = field().performTouchInput {
            click(if (ttt) Offset((at % 3 + .5f) * width / 3f, (at / 3 + .5f) * height / 3f) else Offset((at + .5f) * width / n, height / 2f))
        }
        /** Continues a finished round (next round or next opponent) by tapping the board, as a player does. */
        fun next() { Thread.sleep(750); tap(0); tick(); assertEquals(0, m.result, "continued to a new round") }
        fun myTurn() {
            until("my turn") { m.result != 0 || m.turn == 1 }
            if (m.result != 0) { next(); until("my turn") { m.turn == 1 && m.result == 0 } }
        }
        fun play(how: String, act: (Int) -> Unit) {
            myTurn()
            val before = mine(); act(free()); tick()
            assertEquals(before + 1, mine(), how)
        }
        // Play a whole round, then continue.
        var moves = 0
        while (m.result == 0) { check(moves++ < 30); myTurnOrEnd(m); if (m.result == 0) { tap(free()); tick() } }
        next()
        foldBackgroundUnfold(h)
        play("key after unfold") { key(digits[it]) }
        chatTakesFocus(h)
        play("tap after the chat had focus") { tap(it) }
        field().assertIsFocused()
        play("key after a touch") { key(digits[it]) }
    }

    private fun ComposeUiTest.myTurnOrEnd(m: DuelRound) = until("bot move") { m.result != 0 || m.turn == 1 }
}
