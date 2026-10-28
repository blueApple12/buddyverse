package app.buddyverse.ui.play

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A finished round must never lock the next one: rematch, automatic next round, or a new opponent. */
class DuelRoundTest {
    private fun winTicTacToe(m: DuelRound) {
        // You: 0, 1, 2 (top row); the bot: 3, 4.
        assertEquals(0, m.place(0, 1)); assertEquals(0, m.place(3, 2))
        assertEquals(0, m.place(1, 1)); assertEquals(0, m.place(4, 2))
        assertEquals(1, m.place(2, 1))
    }

    @Test fun autoNextRoundAcceptsInput() {
        val m = DuelRound(ttt = true)
        m.start(0)
        winTicTacToe(m)
        m.result = 1
        assertFalse(m.canPlay(5), "a finished round takes no more marks")
        // Next round (odd): the bot starts; once it has moved, your tap must land.
        m.start(1)
        assertEquals(0, m.result); assertEquals(2, m.turn); assertTrue(m.board.all { it == 0 })
        assertFalse(m.canPlay(0), "the bot's turn")
        assertEquals(0, m.place(4, 2))
        assertTrue(m.canPlay(0), "input unlocks after the bot's first move")
        // The round after that (even): you start straight away.
        m.result = DRAW
        m.start(2)
        assertTrue(m.canPlay(4))
    }

    @Test fun newOpponentAndFourInARowUnlock() {
        val m = DuelRound(ttt = false)
        m.start(0)
        repeat(3) { m.place(0, 1); m.place(1, 2) }
        assertEquals(1, m.place(0, 1)) // four in column 0
        m.result = 1
        assertFalse(m.canPlay(3))
        m.start(0) // the next opponent starts at round 0
        assertTrue(m.canPlay(3))
        assertTrue(m.four.canDrop(0), "a fresh board, not the old full column")
    }

    @Test fun sameObjectAcrossRounds() {
        val m = DuelRound(ttt = true)
        val board = m.board
        m.start(0); m.place(0, 1); m.start(1)
        assertTrue(board === m.board && board.all { it == 0 }, "old handlers see the reset board")
    }
}
