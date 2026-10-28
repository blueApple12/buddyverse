package app.buddyverse.ui.play

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.buddyverse.buddy.Rng
import kotlin.math.max
import kotlin.math.min

/**
 * Pure rules and opponents for the duel games (tic-tac-toe, connect four). Players are 1 (you) and 2 (the bot);
 * [DRAW] ends a full board. Each opponent is one of the user's bots with a strength 0..3: 0 plays near-random with
 * blunders, 1 wins and blocks, 2 is mostly optimal, 3 (the brain, last) never loses at tic-tac-toe.
 */
const val DRAW = 3

/** Strengths for an [n]-bot ladder, easy first: the last (the brain) is 3, the rest spread over 0..2. */
fun ladderStrengths(n: Int): IntArray = IntArray(n) { i -> if (i == n - 1) 3 else min(2, i * 3 / max(1, n - 1)) }

private fun Rng.pick(n: Int) = (next() * n).toInt().coerceIn(0, n - 1)

object TicTacToe {
    val LINES = arrayOf(
        intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
        intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
        intArrayOf(0, 4, 8), intArrayOf(2, 4, 6),
    )

    /** 0 = still playing, 1 / 2 = that player won, [DRAW] = full board. */
    fun winner(b: IntArray): Int {
        val l = winLine(b)
        if (l >= 0) return b[LINES[l][0]]
        return if (b.all { it != 0 }) DRAW else 0
    }

    /** Index into [LINES] of the winning line, -1 none. */
    fun winLine(b: IntArray): Int {
        for (i in LINES.indices) { val l = LINES[i]; val v = b[l[0]]; if (v != 0 && v == b[l[1]] && v == b[l[2]]) return i }
        return -1
    }

    /** The bot's move as [me] at [strength] (0..3). The board must have a free cell. */
    fun move(b: IntArray, me: Int, strength: Int, rng: Rng): Int {
        val free = (0 until 9).filter { b[it] == 0 }
        val win = finishing(b, me)
        return when (strength) {
            0 -> if (win >= 0 && rng.next() < .5f) win else free[rng.pick(free.size)]
            1 -> sensible(b, me, rng, free)
            2 -> if (rng.next() < .22f) sensible(b, me, rng, free) else perfect(b, me, rng)
            else -> perfect(b, me, rng)
        }
    }

    /** Win if it can, else block, else the center, else anything. */
    private fun sensible(b: IntArray, me: Int, rng: Rng, free: List<Int>): Int {
        val win = finishing(b, me); if (win >= 0) return win
        val block = finishing(b, 3 - me); if (block >= 0) return block
        if (b[4] == 0 && rng.next() < .6f) return 4
        return free[rng.pick(free.size)]
    }

    /** A cell that completes a line for [p], -1 none. */
    fun finishing(b: IntArray, p: Int): Int {
        for (l in LINES) {
            var mine = 0; var empty = -1
            for (c in l) if (b[c] == p) mine++ else if (b[c] == 0) empty = c
            if (mine == 2 && empty >= 0) return empty
        }
        return -1
    }

    /** Minimax: a random one of the best moves (fastest win, slowest loss). */
    fun perfect(b: IntArray, me: Int, rng: Rng): Int {
        val work = b.copyOf()
        var best = Int.MIN_VALUE
        val bests = ArrayList<Int>(9)
        for (c in 0 until 9) {
            if (work[c] != 0) continue
            work[c] = me
            val s = -negamax(work, 3 - me, 1)
            work[c] = 0
            if (s > best) { best = s; bests.clear() }
            if (s == best) bests += c
        }
        return bests[rng.pick(bests.size)]
    }

    /** Score for [p] to move: +(10 - depth) for a win, 0 for a draw. */
    private fun negamax(b: IntArray, p: Int, depth: Int): Int {
        val l = winLine(b)
        if (l >= 0) return if (b[LINES[l][0]] == p) 10 - depth else depth - 10
        var best = Int.MIN_VALUE
        for (c in 0 until 9) {
            if (b[c] != 0) continue
            b[c] = p
            best = max(best, -negamax(b, 3 - p, depth + 1))
            b[c] = 0
        }
        return if (best == Int.MIN_VALUE) 0 else best
    }
}

/** Connect four on a 7×6 board; cells are `row * COLS + col`, row 0 at the top. */
class ConnectFour {
    companion object {
        const val COLS = 7
        const val ROWS = 6
        /** Every 4-cell window on the board (69), for win checks and the AI's evaluation. */
        val WINDOWS: Array<IntArray> = buildList {
            for (r in 0 until ROWS) for (c in 0 until COLS) {
                for ((dr, dc) in arrayOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)) {
                    val er = r + dr * 3; val ec = c + dc * 3
                    if (er in 0 until ROWS && ec in 0 until COLS) add(IntArray(4) { (r + dr * it) * COLS + c + dc * it })
                }
            }
        }.toTypedArray()
    }
    val cells = IntArray(COLS * ROWS)
    val heights = IntArray(COLS)
    var moves = 0; private set

    fun canDrop(col: Int) = col in 0 until COLS && heights[col] < ROWS

    /** Drops [p]'s disc in [col]; returns its cell. */
    fun drop(col: Int, p: Int): Int {
        val cell = (ROWS - 1 - heights[col]) * COLS + col
        cells[cell] = p; heights[col]++; moves++
        return cell
    }

    fun undo(col: Int) { heights[col]--; cells[(ROWS - 1 - heights[col]) * COLS + col] = 0; moves-- }

    val full: Boolean get() = moves == COLS * ROWS

    /** The window of four through [cell] owned by its player, null none. */
    fun winAt(cell: Int): IntArray? {
        val p = cells[cell]
        if (p == 0) return null
        for (w in WINDOWS) if (cell in w && w.all { cells[it] == p }) return w
        return null
    }

    fun copy(): ConnectFour = ConnectFour().also { o -> cells.copyInto(o.cells); heights.copyInto(o.heights); o.moves = moves }
}

object ConnectFourAi {
    private val ORDER = intArrayOf(3, 2, 4, 1, 5, 0, 6)

    /** The bot's column as [me] at [strength] 0..3 (work happens on a copy). */
    fun move(board: ConnectFour, me: Int, strength: Int, rng: Rng): Int {
        val g = board.copy()
        val open = (0 until ConnectFour.COLS).filter { g.canDrop(it) }
        val win = winning(g, me)
        return when (strength) {
            0 -> if (win >= 0 && rng.next() < .5f) win else open[rng.pick(open.size)]
            1 -> sensible(g, me, rng, open)
            2 -> if (rng.next() < .15f) sensible(g, me, rng, open) else search(g, me, 4, rng)
            else -> search(g, me, 6, rng)
        }
    }

    /** A column that wins at once for [p], -1 none. */
    fun winning(g: ConnectFour, p: Int): Int {
        for (c in ORDER) if (g.canDrop(c)) { val cell = g.drop(c, p); val w = g.winAt(cell) != null; g.undo(c); if (w) return c }
        return -1
    }

    /** Win, else block, else a center-leaning column that doesn't hand over a win right above it. */
    private fun sensible(g: ConnectFour, me: Int, rng: Rng, open: List<Int>): Int {
        val win = winning(g, me); if (win >= 0) return win
        val block = winning(g, 3 - me); if (block >= 0) return block
        val safe = open.filter { c -> g.drop(c, me); val bad = winning(g, 3 - me) >= 0; g.undo(c); !bad }.ifEmpty { open }
        // Weighted toward the middle: 4 - distance from center.
        var total = 0
        for (c in safe) total += 4 - kotlin.math.abs(c - 3)
        var k = rng.next() * total
        for (c in safe) { k -= 4 - kotlin.math.abs(c - 3); if (k < 0) return c }
        return safe.last()
    }

    private fun search(g: ConnectFour, me: Int, depth: Int, rng: Rng): Int {
        var best = Int.MIN_VALUE
        val bests = ArrayList<Int>(7)
        for (c in ORDER) {
            if (!g.canDrop(c)) continue
            val cell = g.drop(c, me)
            val s = if (g.winAt(cell) != null) 100_000 + depth else -negamax(g, 3 - me, depth - 1, -1_000_000, 1_000_000)
            g.undo(c)
            if (s > best) { best = s; bests.clear() }
            if (s == best) bests += c
        }
        return bests[rng.pick(bests.size)]
    }

    private fun negamax(g: ConnectFour, p: Int, depth: Int, alpha0: Int, beta: Int): Int {
        if (g.full) return 0
        if (depth == 0) return eval(g, p)
        var alpha = alpha0
        var best = -1_000_000
        for (c in ORDER) {
            if (!g.canDrop(c)) continue
            val cell = g.drop(c, p)
            val s = if (g.winAt(cell) != null) 100_000 + depth else -negamax(g, 3 - p, depth - 1, -beta, -alpha)
            g.undo(c)
            if (s > best) best = s
            if (best > alpha) alpha = best
            if (alpha >= beta) break
        }
        return best
    }

    /** Heuristic for [p]: open threes and twos, minus the opponent's, plus the center column. */
    internal fun eval(g: ConnectFour, p: Int): Int {
        var s = 0
        for (r in 0 until ConnectFour.ROWS) { val v = g.cells[r * ConnectFour.COLS + 3]; if (v == p) s += 3 else if (v != 0) s -= 3 }
        for (w in ConnectFour.WINDOWS) {
            var mine = 0; var theirs = 0
            for (cell in w) { val v = g.cells[cell]; if (v == p) mine++ else if (v != 0) theirs++ }
            if (theirs == 0) s += when (mine) { 3 -> 5; 2 -> 2; else -> 0 }
            else if (mine == 0) s -= when (theirs) { 3 -> 6; 2 -> 2; else -> 0 }
        }
        return s
    }
}

/**
 * One duel's live round: ONE object for the whole match, reset in place by [start]. Taps, keys and the bot's turn all
 * reach this same object, so a handler made in an earlier round (an old closure) can never hold a finished board and
 * lock the input after a rematch, an automatic next round or a new opponent.
 */
class DuelRound(val ttt: Boolean) {
    val board = IntArray(9)
    var four by androidx.compose.runtime.mutableStateOf(ConnectFour())
    var turn by androidx.compose.runtime.mutableIntStateOf(1)
    var result by androidx.compose.runtime.mutableIntStateOf(0)
    var winCells by androidx.compose.runtime.mutableStateOf<IntArray?>(null)
    var lastCell by androidx.compose.runtime.mutableIntStateOf(-1)
    var version by androidx.compose.runtime.mutableIntStateOf(0) // bumps on every placed mark and every new round
    var round by androidx.compose.runtime.mutableIntStateOf(0)

    /** A fresh board for round [r]: you start the even rounds, the bot the odd ones. */
    fun start(r: Int) {
        round = r; board.fill(0); four = ConnectFour(); turn = if (r % 2 == 0) 1 else 2
        result = 0; winCells = null; lastCell = -1; version++
    }

    /** Whether your mark can go at [at] right now (a cell for tic-tac-toe, a column for four in a row). */
    fun canPlay(at: Int): Boolean =
        result == 0 && turn == 1 && (if (ttt) at in 0..8 && board[at] == 0 else four.canDrop(at))

    /** Places [who]'s mark; returns the outcome (0 = play on, 1/2 = that player won, [DRAW]) and sets [winCells]. */
    fun place(at: Int, who: Int): Int {
        val out: Int
        if (ttt) {
            board[at] = who; lastCell = at
            val l = TicTacToe.winLine(board)
            out = if (l >= 0) { winCells = TicTacToe.LINES[l]; who } else if (board.all { it != 0 }) DRAW else 0
        } else {
            val cell = four.drop(at, who); lastCell = cell
            val w = four.winAt(cell)
            out = if (w != null) { winCells = w; who } else if (four.full) DRAW else 0
        }
        version++
        if (out == 0) turn = 3 - who
        return out
    }
}
