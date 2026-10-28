package app.buddyverse.ui.play

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GamesTest {
    private fun SnakeGame.head() = body[0] % cols to body[0] / cols

    @Test fun snakeMovesTurnsAndIgnoresReversal() {
        val g = SnakeGame(10, 10, 1)
        g.putFood(0)
        val (x, y) = g.head()
        g.step()
        assertEquals(x + 1 to y, g.head(), "starts heading right")
        g.turn(LEFT) // straight back into itself: ignored
        g.step()
        assertEquals(x + 2 to y, g.head())
        g.turn(DOWN)
        g.step()
        assertEquals(x + 2 to y + 1, g.head())
        assertEquals(SnakeGame.START_LENGTH, g.length)
        // Two queued turns run on two steps (a quick U-turn).
        g.turn(LEFT); g.turn(UP)
        g.step(); g.step()
        assertEquals(x + 1 to y, g.head())
    }

    @Test fun snakeGrowsOnFoodAndSpeedsUp() {
        val g = SnakeGame(10, 10, 2)
        val slow = g.interval()
        g.putFood(g.body[0] + 1)
        val ev = g.step()
        assertTrue(ev and SnakeGame.ATE != 0)
        assertEquals(SnakeGame.START_LENGTH + 1, g.length)
        assertEquals(1, g.eaten)
        assertTrue(g.interval() < slow, "speeds up")
        assertTrue(g.food >= 0 && (0 until g.length).none { g.body[it] == g.food }, "new food lands on a free cell")
        // The new tail segment starts where the old tail was.
        assertEquals(g.body[g.length - 1], g.prev[g.length - 1])
    }

    @Test fun snakeDiesOnWallsAndItself() {
        val w = SnakeGame(6, 6, 3)
        w.putFood(35)
        var ev = 0
        repeat(10) { ev = ev or w.step() }
        assertTrue(w.over && ev and SnakeGame.DIED != 0, "the right wall")
        assertEquals(0, w.step(), "nothing moves after game over")

        val s = SnakeGame(12, 12, 4)
        // Grow to 5, then curl into the body.
        repeat(2) { s.putFood(s.body[0] + 1); s.step() }
        s.putFood(0)
        assertEquals(5, s.length)
        s.turn(DOWN); s.step(); s.turn(LEFT); s.step(); s.turn(UP)
        assertTrue(s.step() and SnakeGame.DIED != 0, "bit itself")
    }

    @Test fun snakeMayFollowItsOwnTail() {
        val s = SnakeGame(12, 12, 5)
        s.putFood(s.body[0] + 1); s.step()
        s.putFood(0)
        // Length 4 in a tight loop: the head steps into the cell the tail just left.
        s.turn(DOWN); s.step(); s.turn(LEFT); s.step(); s.turn(UP); s.step()
        assertFalse(s.over)
    }

    @Test fun mergesLikeClassic2048() {
        val g = Game2048(1)
        g.load(1, 1, 1, 1, 1, 1, 2, 0, 2, 1, 1, 0, 0, 0, 0, 0)
        assertTrue(g.move(LEFT))
        val sp = g.spawned
        fun at(i: Int) = if (i == sp) 0 else g.cells[i]
        assertEquals(listOf(2, 2, 0, 0), (0..3).map(::at), "2 2 2 2 -> 4 4")
        assertEquals(listOf(2, 2, 0, 0), (4..7).map(::at), "2 2 4 -> 4 4, not 8")
        assertEquals(listOf(2, 2, 0, 0), (8..11).map(::at), "4 2 2 -> 4 4")
        assertEquals(4 + 4 + 4 + 4, g.score, "score = sum of merged tiles")
        assertEquals(4, g.ghosts)
        assertTrue(g.merged[0] && g.merged[1] && g.merged[4] && !g.merged[5])
    }

    @Test fun spawnsOnlyAfterARealMove() {
        val g = Game2048(2)
        g.load(1, 2, 0, 0)
        assertFalse(g.move(LEFT), "nothing can move left")
        assertEquals(-1, g.spawned)
        assertEquals(2, g.cells.count { it != 0 })
        assertTrue(g.move(RIGHT))
        assertEquals(3, g.cells.count { it != 0 }, "one new tile")
        assertTrue(g.cells[g.spawned] in 1..2)
    }

    @Test fun gameOverWhenNoMoveIsLeft() {
        val g = Game2048(3)
        g.load(1, 2, 1, 2, 2, 1, 2, 1, 1, 2, 1, 2, 2, 1, 2, 1)
        assertTrue(g.over)
        g.load(1, 2, 1, 2, 2, 1, 2, 1, 1, 2, 1, 2, 2, 1, 2, 2)
        assertFalse(g.over, "two equal neighbours can still merge")
        g.load(1, 2, 1, 2, 2, 1, 2, 1, 1, 2, 1, 2, 2, 1, 2, 0)
        assertFalse(g.over, "an empty cell")
    }

    @Test fun slideDataTellsWhereEachTileCameFrom() {
        val g = Game2048(4)
        // Row 0: _ 2 _ 2  → 4 _ _ _ : the survivor came from cell 1, the eaten one slid from 3 into 0.
        // Row 1: 4 _ _ 8  → 4 8 _ _ : the 4 stays (from 4), the 8 slides 7 → 5.
        g.load(0, 1, 0, 1, 2, 0, 0, 3)
        assertTrue(g.move(LEFT))
        assertEquals(LEFT, g.lastDir)
        assertEquals(1, g.from[0]); assertTrue(g.merged[0])
        assertEquals(1, g.ghosts); assertEquals(3, g.ghostFrom[0]); assertEquals(0, g.ghostTo[0]); assertEquals(1, g.ghostVal[0])
        assertEquals(4, g.from[4]); assertEquals(7, g.from[5])
        assertEquals(g.spawned, g.from[g.spawned], "the new tile grows in place")
        for (c in 0 until 16) if (g.cells[c] == 0) assertEquals(-1, g.from[c], "empty cells have no source")
    }

    @Test fun ladderEndsWithTheBrain() {
        assertContentEquals(intArrayOf(3), ladderStrengths(1))
        assertContentEquals(intArrayOf(0, 3), ladderStrengths(2))
        assertContentEquals(intArrayOf(0, 0, 1, 2, 3), ladderStrengths(5))
    }

    /** Every game the human could play against [strength] (all their choices), bot to play [botFirst] or second. */
    private fun tttOutcomes(strength: Int, botFirst: Boolean, seed: Int): IntArray {
        val counts = IntArray(4)
        val rng = app.buddyverse.buddy.Rng(seed)
        fun play(b: IntArray, botTurn: Boolean) {
            val w = TicTacToe.winner(b)
            if (w != 0) { counts[w]++; return }
            if (botTurn) { val m = TicTacToe.move(b, 2, strength, rng); b[m] = 2; play(b, false); b[m] = 0 }
            else for (c in 0 until 9) if (b[c] == 0) { b[c] = 1; play(b, true); b[c] = 0 }
        }
        play(IntArray(9), botFirst)
        return counts
    }

    @Test fun brainNeverLosesTicTacToe() {
        for (seed in 1..4) for (first in listOf(true, false)) {
            val o = tttOutcomes(3, first, seed)
            assertEquals(0, o[1], "the human found a win (seed $seed, bot first $first)")
            assertTrue(o[2] > 0)
        }
    }

    @Test fun weakerBotsAreBeatable() {
        // Over every line of play, the easy bot loses far more often than the medium one, and the medium one can be beaten.
        val easy = tttOutcomes(0, false, 7); val medium = tttOutcomes(1, false, 7)
        assertTrue(easy[1] > 0 && medium[1] > 0)
        assertTrue(easy[1].toFloat() / easy.sum() > medium[1].toFloat() / medium.sum())
    }

    @Test fun mediumBotWinsAndBlocks() {
        val rng = app.buddyverse.buddy.Rng(1)
        // Bot (2) can win at 2; human (1) threatens 6.
        assertEquals(2, TicTacToe.move(intArrayOf(2, 2, 0, 1, 1, 0, 0, 0, 0), 2, 1, rng), "winning beats blocking")
        assertEquals(2, TicTacToe.move(intArrayOf(2, 2, 0, 1, 0, 0, 0, 1, 0), 2, 1, rng))
        repeat(20) { assertEquals(6, TicTacToe.move(intArrayOf(1, 2, 0, 1, 0, 0, 0, 0, 2), 2, 1, rng), "blocks the column") }
    }

    @Test fun connectFourFindsEveryKindOfLine() {
        fun board(vararg moves: Pair<Int, Int>) = ConnectFour().also { g -> moves.forEach { (c, p) -> g.drop(c, p) } }
        val row = board(0 to 1, 0 to 2, 1 to 1, 1 to 2, 2 to 1, 2 to 2)
        val cell = row.drop(3, 1)
        assertContentEquals(intArrayOf(35, 36, 37, 38), row.winAt(cell)!!.sortedArray(), "bottom row")
        val col = board(4 to 2, 4 to 2, 4 to 2)
        assertTrue(col.winAt(col.drop(4, 2)) != null, "column")
        // Rising diagonal: 0,1 1,2 2,3 3,4 heights.
        val d = board(1 to 2, 2 to 2, 2 to 2, 3 to 2, 3 to 2, 3 to 2)
        d.drop(0, 1); d.drop(1, 1); d.drop(2, 1)
        assertTrue(d.winAt(d.drop(3, 1)) != null, "diagonal")
        val no = board(0 to 1, 1 to 1, 2 to 1)
        assertNull(no.winAt(no.drop(4, 1)), "a gap is not a line")
        assertTrue(ConnectFour.WINDOWS.size == 69)
        no.undo(4)
        assertEquals(0, no.cells[38]); assertEquals(0, no.heights[4])
    }

    @Test fun connectFourBotsTakeWinsAndBlock() {
        val rng = app.buddyverse.buddy.Rng(3)
        val g = ConnectFour().apply { drop(0, 1); drop(0, 1); drop(0, 1); drop(6, 2); drop(6, 2); drop(6, 2) }
        for (s in 1..3) assertEquals(6, ConnectFourAi.move(g, 2, s, rng), "strength $s takes its win")
        val h = ConnectFour().apply { drop(2, 1); drop(3, 1); drop(4, 1); drop(1, 2); drop(6, 2) }
        // Human threatens 5 (three on the bottom row); every sensible bot blocks it.
        for (s in 1..3) assertEquals(5, ConnectFourAi.move(h, 2, s, rng), "strength $s blocks")
        assertEquals(h.moves, 5, "the AI works on a copy")
    }

    @Test fun echoRepeatsAndGrows() {
        val g = EchoGame(5)
        assertEquals(1, g.length)
        assertEquals(EchoGame.OK or EchoGame.ROUND, g.press(g.seq[0]))
        assertEquals(1, g.rounds)
        g.extend()
        assertEquals(2, g.length)
        assertEquals(EchoGame.OK, g.press(g.seq[0]))
        assertEquals(EchoGame.WRONG, g.press((g.seq[1] + 1) % EchoGame.PADS))
        assertTrue(g.over)
        assertTrue(g.stepMs() < 520)
    }
    @Test fun snakeTurnLandsWithinHalfAStep() {
        val g = SnakeGame(13, 13, 5)
        g.update(.001f) // just after a step: a lazy loop would make the turn wait a whole step
        val head = g.body[0]
        g.turn(UP)
        g.update(g.interval() * .5f + .001f)
        assertEquals(head - 13, g.body[0], "turned up within half a step")
    }
}
