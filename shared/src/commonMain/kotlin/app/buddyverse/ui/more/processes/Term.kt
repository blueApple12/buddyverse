package app.buddyverse.ui.more.processes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * A small terminal screen for raw PTY / process output: scrollback lines, a cursor, carriage return, backspace, tab,
 * auto-wrap at [cols], and the cursor / erase sequences ConPTY and shells emit (CUP, CUU/CUD/CUF/CUB, CHA, EL, ED, ECH).
 * Colors, modes and titles are dropped.
 * ponytail: no SGR colors, no alt screen, no insert/delete line; full-screen TUIs (vim, htop) render garbled. Add a cell grid if they matter.
 */
class TermBuffer(var cols: Int = Int.MAX_VALUE, var rows: Int = 50, private val maxLines: Int = 2000) {
    private val lines = arrayListOf(StringBuilder())
    private var row = 0
    private var col = 0
    private val pending = StringBuilder()

    /** Bumps after every [feed] / [reset]: read it in composition to redraw. */
    var rev by mutableIntStateOf(0)
        private set

    private val screenTop: Int get() = maxOf(0, lines.size - rows)

    fun reset() {
        lines.clear(); lines += StringBuilder(); row = 0; col = 0; pending.clear(); rev++
    }

    fun feed(chunk: String) {
        if (chunk.isEmpty()) return
        val s = if (pending.isEmpty()) chunk else pending.toString() + chunk
        pending.clear()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\u001b') {
                val end = escapeEnd(s, i)
                if (end < 0) {
                    // An escape split across chunks waits for the rest; a runaway one (> 4 KB) is dropped.
                    if (s.length - i < 4096) pending.append(s.substring(i))
                    break
                }
                control(s, i, end)
                i = end
                continue
            }
            when (ch) {
                '\r' -> col = 0
                // A bare LF also returns the carriage: process output is no PTY, and ConPTY sends CRLF anyway.
                '\n', '\u000b', '\u000c' -> { col = 0; lineFeed() }
                '\b' -> if (col > 0) col--
                '\t' -> repeat(8 - col % 8) { put(' ') }
                else -> if (ch >= ' ' && ch != '\u007f') put(ch)
            }
            i++
        }
        trim()
        rev++
    }

    /** Lines to show: everything up to the cursor or the last written line, whichever is lower. */
    fun snapshot(): List<String> {
        var last = lines.size - 1
        while (last > row && lines[last].isBlank()) last--
        return List(last + 1) { lines[it].toString().trimEnd() }
    }

    fun plain(): String = snapshot().joinToString("\n").trimEnd()

    fun lastLine(): String? = snapshot().lastOrNull { it.isNotBlank() }?.trim()

    private fun put(ch: Char) {
        if (col >= cols) { lineFeed(); col = 0 }
        val line = lines[row]
        while (line.length < col) line.append(' ')
        if (col < line.length) line[col] = ch else line.append(ch)
        col++
    }

    private fun lineFeed() {
        row++
        while (lines.size <= row) lines += StringBuilder()
    }

    private fun moveTo(r: Int, c: Int) {
        row = screenTop + (r - 1).coerceAtLeast(0)
        while (lines.size <= row) lines += StringBuilder()
        col = (c - 1).coerceAtLeast(0)
    }

    private fun trim() {
        val extra = lines.size - maxLines
        if (extra <= 0) return
        repeat(extra) { lines.removeAt(0) }
        row = (row - extra).coerceAtLeast(0)
    }

    private fun control(s: String, start: Int, end: Int) {
        val kind = s[start + 1]
        when {
            kind == 'c' -> { lines.clear(); lines += StringBuilder(); row = 0; col = 0 }
            kind != '[' -> Unit
            else -> {
                val final = s[end - 1]
                val body = s.substring(start + 2, end - 1)
                if (body.startsWith('?') || body.startsWith('>') || body.startsWith('=')) return
                val args = body.split(';').map { it.toIntOrNull() }
                val n = (args.firstOrNull() ?: 0).coerceAtLeast(1)
                val line = lines[row]
                when (final) {
                    'H', 'f' -> moveTo(args.getOrNull(0) ?: 1, args.getOrNull(1) ?: 1)
                    'A' -> row = maxOf(screenTop, row - n)
                    'B' -> repeat(n) { lineFeed() }
                    'C' -> col += n
                    'D' -> col = maxOf(0, col - n)
                    'G' -> col = n - 1
                    'd' -> moveTo(n, col + 1)
                    'K' -> when (args.firstOrNull() ?: 0) {
                        0 -> if (col < line.length) line.setLength(col)
                        1 -> for (k in 0 until minOf(col + 1, line.length)) line[k] = ' '
                        else -> line.setLength(0)
                    }
                    'J' -> when (args.firstOrNull() ?: 0) {
                        0 -> { if (col < line.length) line.setLength(col); while (lines.size > row + 1) lines.removeAt(lines.size - 1) }
                        else -> { lines.clear(); lines += StringBuilder(); row = 0; col = 0 }
                    }
                    'X' -> for (k in col until minOf(col + n, line.length)) line[k] = ' '
                    'P' -> if (col < line.length) line.deleteRange(col, minOf(line.length, col + n))
                    else -> Unit // m (colors), h/l (modes), r, s/u, t …
                }
            }
        }
    }

    companion object {
        /** Index just past the escape sequence starting at [i], or -1 when it is not complete yet. */
        fun escapeEnd(s: String, i: Int): Int {
            if (i + 1 >= s.length) return -1
            return when (s[i + 1]) {
                '[' -> {
                    var j = i + 2
                    while (j < s.length && s[j].code in 0x20..0x3F) j++
                    if (j >= s.length) -1 else j + 1
                }
                ']', 'P', '_', '^' -> {
                    var j = i + 2
                    while (j < s.length) {
                        if (s[j] == '\u0007') return j + 1
                        if (s[j] == '\u001b' && j + 1 < s.length && s[j + 1] == '\\') return j + 2
                        j++
                    }
                    -1
                }
                '(', ')', '*', '+', '#', '%' -> if (i + 2 < s.length) i + 3 else -1
                else -> i + 2
            }
        }
    }
}

/** Bytes `[0, n)` hold whole UTF-8 characters; the rest is a character split across frames that waits for the next one. */
fun utf8Complete(b: ByteArray): Int {
    var i = b.size - 1
    var back = 0
    while (i >= 0 && back < 3 && (b[i].toInt() and 0xC0) == 0x80) { i--; back++ }
    if (i < 0) return b.size
    val lead = b[i].toInt() and 0xFF
    val need = when {
        lead < 0x80 -> 1
        lead >= 0xF0 -> 4
        lead >= 0xE0 -> 3
        lead >= 0xC0 -> 2
        else -> 1
    }
    return if (b.size - i >= need) b.size else i
}
