package app.buddyverse.ui.more.processes

import app.buddyverse.core.WireJson
import app.buddyverse.core.obj
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.buddyverse.i18n.EnglishStrings

class ProcessesLogicTest {
    private fun term(vararg chunks: String, cols: Int = Int.MAX_VALUE, rows: Int = 50) =
        TermBuffer(cols, rows).apply { chunks.forEach(::feed) }

    @Test fun linesColorsAndCarriageReturn() {
        val b = term("\u001b[32mready\u001b[0m on :5173\r\n", "50%\r100%\r\n", "abc\b\bX\n")
        assertEquals(listOf("ready on :5173", "100%", "aXc", ""), b.snapshot())
        assertEquals("aXc", b.lastLine())
    }

    @Test fun escapeSplitAcrossChunksWaits() {
        val b = term("a\u001b[3", "1mb\u001b]0;title", "\u0007c")
        assertEquals("abc", b.plain())
    }

    @Test fun eraseAndCursorMoves() {
        assertEquals("hello", term("hello world\u001b[6G\u001b[K").plain())
        assertEquals("ab  e", term("abcde\u001b[3G\u001b[2X").plain())
        // ConPTY-style repaint: home, write, then position on row 2.
        assertEquals("one\ntwo", term("xxx\r\nyyy\u001b[H\u001b[2J", "one\u001b[2;1Htwo").plain())
        assertEquals("AB\ncd", term("ab\r\ncd\u001b[1A\u001b[1GAB").plain())
    }

    @Test fun wrapsAtColsAndCapsScrollback() {
        assertEquals(listOf("abcd", "ef"), term("abcdef", cols = 4).snapshot())
        val b = TermBuffer(maxLines = 3).apply { feed("1\n2\n3\n4\n5") }
        assertEquals(listOf("3", "4", "5"), b.snapshot())
    }

    @Test fun utf8SplitKeepsTheTail() {
        val bytes = "שלום".encodeToByteArray()
        val cut = bytes.copyOfRange(0, 3) // one whole letter + the first byte of the next
        assertEquals(2, utf8Complete(cut))
        assertEquals(bytes.size, utf8Complete(bytes))
        assertEquals(1, utf8Complete("a".encodeToByteArray() + byteArrayOf(0xE2.toByte(), 0x82.toByte())))
    }

    @Test fun procParsesEntryAndOrders() {
        val o = WireJson.parseToJsonElement(
            """{"session_id":"proc_9f2c","command":"npm run dev\n--port 1","cwd":"/a","pid":41233,"uptime_seconds":312,"status":"running","output_preview":"ready","exit_code":null}""",
        ).obj!!
        val p = Proc.from(o, "s1", "default", null)!!
        assertEquals("proc_9f2c", p.id)
        assertEquals("npm run dev", p.title)
        assertTrue(p.running)
        assertNull(p.exitCode)
        val failed = p.copy(id = "x", status = "exited", exitCode = 2, exitedAt = 10.0)
        val ok = p.copy(id = "y", status = "exited", exitCode = 0, exitedAt = 20.0)
        assertTrue(failed.failed)
        assertEquals(listOf("proc_9f2c", "y", "x"), listOf(failed, ok, p).sortedWith(Proc.order).map { it.id })
        assertEquals("5m", dur(312, EnglishStrings))
        assertEquals("2h 3m", dur(7_380, EnglishStrings))
    }

    @Test fun closeCodes() {
        assertEquals(ShellEnd.Exited, shellEndOf(4410))
        assertEquals(ShellEnd.Busy, shellEndOf(1013))
        assertEquals(ShellEnd.Denied, shellEndOf(4403))
        assertEquals(ShellEnd.Failed, shellEndOf(null))
    }
}
