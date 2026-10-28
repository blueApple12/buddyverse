package app.buddyverse.ui.play

import app.buddyverse.buddy.Rng
import kotlin.math.max

/**
 * Pure rules for the mini-games (no Compose). Each game is stepped by its screen and writes into reused arrays
 * (no per-frame allocation). Directions: [UP], [RIGHT], [DOWN], [LEFT].
 */
const val UP = 0
const val RIGHT = 1
const val DOWN = 2
const val LEFT = 3
private val DX = intArrayOf(0, 1, 0, -1)
private val DY = intArrayOf(-1, 0, 1, 0)

/**
 * Bot snake on a [cols]×[rows] grid with walls. Cells are `y * cols + x`; [body] holds the head first. Eating the task
 * dot grows the tail by one (a new bot joins) and speeds the snake up a little.
 */
class SnakeGame(val cols: Int, val rows: Int, seed: Int) {
    companion object {
        const val ATE = 1
        const val DIED = 2
        const val START_LENGTH = 3
        /** Share of a step a new turn skips ahead to, so the snake answers a swipe within half a step. */
        const val TURN_PULL = .5f
    }
    private val rng = Rng(seed)
    private val cells = cols * rows
    val body = IntArray(cells)
    /** Where each segment was before the last step: the screen glides from here to [body]. */
    val prev = IntArray(cells)
    var length = START_LENGTH; private set
    var dir = RIGHT; private set
    var food = -1; private set
    var eaten = 0; private set
    var over = false; private set
    private val queue = IntArray(2)
    private var queued = 0
    private var acc = 0f
    private val taken = BooleanArray(cells)

    init {
        val y = rows / 2
        val x = cols / 2 - 1
        for (i in 0 until length) body[i] = y * cols + x - i
        body.copyInto(prev, 0, 0, length)
        placeFood()
    }

    /** Seconds per cell: quick at once, faster with every task, capped so it stays playable. */
    fun interval(): Float = max(.07f, .17f - eaten * .004f)

    /** 0..1 of the way from [prev] to [body]. */
    val progress: Float get() = if (over) 1f else (acc / interval()).coerceIn(0f, 1f)

    /** Queue a turn (two at most, so a quick double swipe makes a U-turn). Reversing into yourself is ignored. */
    fun turn(d: Int) {
        if (over || queued == queue.size) return
        val last = if (queued > 0) queue[queued - 1] else dir
        if (d == last || d == (last + 2) % 4) return
        queue[queued++] = d
        // Responsive: a fresh turn never waits more than half a step (it pulls the next step closer).
        if (queued == 1) acc = max(acc, interval() * TURN_PULL)
    }

    /** Advance [dt] seconds; returns [ATE] / [DIED] bits. */
    fun update(dt: Float): Int {
        var ev = 0
        acc += dt
        while (!over && acc >= interval()) { acc -= interval(); ev = ev or step() }
        return ev
    }

    internal fun step(): Int {
        if (over) return 0
        if (queued > 0) { dir = queue[0]; queue[0] = queue[1]; queued-- }
        body.copyInto(prev, 0, 0, length)
        val hx = body[0] % cols + DX[dir]
        val hy = body[0] / cols + DY[dir]
        if (hx !in 0 until cols || hy !in 0 until rows) { over = true; return DIED }
        val head = hy * cols + hx
        val grow = head == food
        // The tail moves out of the way this step unless the snake grows.
        for (i in 0 until if (grow) length else length - 1) if (body[i] == head) { over = true; return DIED }
        if (grow) { prev[length] = body[length - 1]; length++ }
        for (i in length - 1 downTo 1) body[i] = body[i - 1]
        body[0] = head
        if (!grow) return 0
        eaten++
        if (length >= cells) { over = true; return ATE or DIED }
        placeFood()
        return ATE
    }

    internal fun putFood(cell: Int) { food = cell }

    private fun placeFood() {
        taken.fill(false)
        for (i in 0 until length) taken[body[i]] = true
        var k = (rng.next() * (cells - length)).toInt().coerceAtMost(cells - length - 1)
        for (c in 0 until cells) if (!taken[c] && k-- == 0) { food = c; return }
    }
}

/**
 * 2048 with bots: a 4×4 board of exponents (0 = empty, 1 = "2", 2 = "4"…). Classic rules, no undo; score = the sum of
 * every merged tile. Every move leaves its animation data: [from] (where each tile slid from), the eaten [ghostId]s
 * (they slide from [ghostFrom] into their partner and vanish), [merged] (pops) and [spawned] (grows in).
 */
class Game2048(seed: Int) {
    companion object { const val N = 4 }
    private val rng = Rng(seed)
    val cells = IntArray(N * N)
    val ids = IntArray(N * N)
    /** For each occupied cell, the cell its tile was in before the last move (itself for the spawned tile), else -1. */
    val from = IntArray(N * N) { it }
    /** Cells that took a merge in the last move (they pop). */
    val merged = BooleanArray(N * N)
    /** The cell of the tile spawned by the last move (it grows in), -1 none. */
    var spawned = -1; private set
    /** Eaten tiles of the last move: id, the cell they slid from, the cell they slid into, their exponent. */
    val ghostId = IntArray(N * N); val ghostFrom = IntArray(N * N); val ghostTo = IntArray(N * N); val ghostVal = IntArray(N * N)
    var ghosts = 0; private set
    /** The direction of the last move (tiles stretch along it while sliding). */
    var lastDir = LEFT; private set
    var score = 0; private set
    /** Points scored by the last move. */
    var gained = 0; private set
    /** Highest exponent on the board. */
    var top = 0; private set
    private var nextId = 1
    private val line = IntArray(N); private val outV = IntArray(N); private val outId = IntArray(N); private val outFrom = IntArray(N)
    private val outM = BooleanArray(N)

    init { spawn(); spawn(); spawned = -1; for (c in 0 until N * N) from[c] = if (cells[c] == 0) -1 else c }

    val over: Boolean get() {
        for (i in 0 until N * N) {
            if (cells[i] == 0) return false
            if (i % N < N - 1 && cells[i] == cells[i + 1]) return false
            if (i / N < N - 1 && cells[i] == cells[i + N]) return false
        }
        return true
    }

    /** Free cells left. */
    val free: Int get() { var n = 0; for (v in cells) if (v == 0) n++; return n }

    /** Slide everything toward [dir]. False (and nothing changes) when no tile can move. */
    fun move(dir: Int): Boolean {
        if (!canMove(dir)) return false
        ghosts = 0; gained = 0; merged.fill(false); lastDir = dir
        for (l in 0 until N) {
            lineOf(dir, l)
            var w = 0
            var open = false // the last placed tile may still take a merge
            for (k in 0 until N) {
                val c = line[k]
                val v = cells[c]
                if (v == 0) continue
                if (open && outV[w - 1] == v) {
                    outV[w - 1] = v + 1; outM[w - 1] = true; open = false
                    ghostId[ghosts] = ids[c]; ghostFrom[ghosts] = c; ghostTo[ghosts] = line[w - 1]; ghostVal[ghosts] = v; ghosts++
                    gained += 1 shl (v + 1)
                } else { outV[w] = v; outId[w] = ids[c]; outFrom[w] = c; outM[w] = false; open = true; w++ }
            }
            for (k in 0 until N) {
                val c = line[k]
                val has = k < w
                cells[c] = if (has) outV[k] else 0; ids[c] = if (has) outId[k] else 0
                from[c] = if (has) outFrom[k] else -1; merged[c] = has && outM[k]
                if (cells[c] > top) top = cells[c]
            }
        }
        score += gained
        spawn()
        return true
    }

    private fun lineOf(dir: Int, l: Int) {
        for (k in 0 until N) line[k] = when (dir) {
            LEFT -> l * N + k
            RIGHT -> l * N + (N - 1 - k)
            UP -> k * N + l
            else -> (N - 1 - k) * N + l
        }
    }

    /** A tile can slide into a gap or onto an equal neighbour along [dir]. */
    private fun canMove(dir: Int): Boolean {
        for (l in 0 until N) {
            lineOf(dir, l)
            for (k in 1 until N) {
                val v = cells[line[k]]
                if (v == 0) continue
                val before = cells[line[k - 1]]
                if (before == 0 || before == v) return true
            }
        }
        return false
    }

    /** A new "2" (one in ten: "4") on a random empty cell. */
    private fun spawn() {
        val free = free
        if (free == 0) { spawned = -1; return }
        var k = (rng.next() * free).toInt().coerceAtMost(free - 1)
        for (c in cells.indices) if (cells[c] == 0 && k-- == 0) {
            cells[c] = if (rng.next() < .1f) 2 else 1; ids[c] = nextId++; spawned = c; from[c] = c
            if (cells[c] > top) top = cells[c]
            return
        }
    }

    /** Test hook: set the board (exponents), fresh ids. */
    internal fun load(vararg v: Int) {
        for (i in 0 until N * N) { cells[i] = v.getOrElse(i) { 0 }; ids[i] = if (cells[i] == 0) 0 else nextId++; from[i] = if (cells[i] == 0) -1 else i }
        top = cells.max()
    }
}

/** Bot echo (Simon): four bots chirp a growing sequence; repeat it. */
class EchoGame(seed: Int) {
    companion object { const val PADS = 4; const val OK = 1; const val ROUND = 2; const val WRONG = 4; private const val MAX = 512 }
    private val rng = Rng(seed)
    val seq = IntArray(MAX)
    var length = 0; private set
    /** How many of the sequence the player has repeated this round. */
    var pos = 0; private set
    var over = false; private set
    /** Rounds fully repeated. */
    var rounds = 0; private set

    init { extend() }

    /** Lit time per step: quicker as the sequence grows. */
    fun stepMs(): Long = max(230, 520 - length * 18).toLong()

    fun extend() { if (length < MAX) seq[length++] = (rng.next() * PADS).toInt().coerceAtMost(PADS - 1); pos = 0 }

    fun press(pad: Int): Int {
        if (over || pos >= length) return 0
        if (seq[pos] != pad) { over = true; return WRONG }
        pos++
        if (pos < length) return OK
        rounds++
        return OK or ROUND
    }
}
