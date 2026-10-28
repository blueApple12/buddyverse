package app.buddyverse.ui.more.checkpoints

import app.buddyverse.core.api.ReviewFile
import app.buddyverse.core.api.Workspace
import app.buddyverse.i18n.CheckpointsPageStrings
import app.buddyverse.i18n.EnglishStrings
import kotlin.test.Test
import kotlin.test.assertEquals

/** Diff parsing, stat reading, labels and the chat list of More ▸ Checkpoints. */
class CheckpointsLogicTest {
    private val diff = """
        diff --git a/src/app.ts b/src/app.ts
        index 1111111..2222222 100644
        --- a/src/app.ts
        +++ b/src/app.ts
        @@ -1,3 +1,3 @@ fun main
         keep
        -old
        +new
        -- not a header
        \ No newline at end of file
    """.trimIndent()

    @Test
    fun parsesUnifiedDiff() {
        val lines = parseDiff(diff)
        assertEquals(listOf(LineKind.File, LineKind.Hunk, LineKind.Ctx, LineKind.Del, LineKind.Add, LineKind.Del, LineKind.Meta), lines.map { it.kind })
        assertEquals("src/app.ts", lines[0].text)
        assertEquals("fun main", lines[1].text)
        assertEquals(1, lines[2].oldNo); assertEquals(1, lines[2].newNo)
        assertEquals(2, lines[3].oldNo)
        assertEquals(2, lines[4].newNo)
        assertEquals("- not a header", lines[5].text) // a removed line that starts with "-" stays a removal
    }

    @Test
    fun readsStat() {
        val stat = " src/app.ts | 2 +-\n docs/a => docs/b | 0\n .../deep/x.kt | 4 ++--\n 3 files changed, 3 insertions(+), 3 deletions(-)"
        assertEquals(listOf("src/app.ts"), statFiles(stat))
        assertEquals("3 files changed, 3 insertions(+), 3 deletions(-)", statSummary(stat))
        assertEquals(listOf("src/app.ts", "b.txt"), touchedFiles(" b.txt | 1 +", parseDiff(diff)))
        assertEquals("x.kt", fileName("a/b/x.kt")); assertEquals("a/b", fileDir("a/b/x.kt")); assertEquals("", fileDir("x.kt"))
        assertEquals("buddyverse\\app", shortPath("C:\\Users\\alex\\buddyverse\\app"))
    }

    @Test
    fun labelsWithoutToolNames() {
        val t = CheckpointsPageStrings()
        assertEquals(t.beforeGoingBack("abc123"), checkpointLabel("pre-rollback snapshot (restoring to abc123)", EnglishStrings, t))
        assertEquals(t.autoSnapshot, checkpointLabel("auto", EnglishStrings, t))
        assertEquals("made by hand", checkpointLabel("made by hand", EnglishStrings, t))
    }

    @Test
    fun chatListAndOptimisticFiles() {
        fun w(id: String, last: Double, cwd: String? = "/x", main: Boolean = false) = Workspace(id, "default", null, cwd, null, null, last, main)
        val main = w("m", 1.0, main = true)
        val merged = mergeWorkspaces(listOf(main), listOf(w("a", 5.0), w("m", 9.0), w("b", 7.0, cwd = null)))
        assertEquals(listOf("m", "b", "a"), merged.map { it.sessionId })
        assertEquals("b", defaultWorkspace(merged.filter { it.cwd == null } + w("c", 2.0, cwd = null))?.sessionId)
        assertEquals("a", defaultWorkspace(listOf(w("a", 5.0), w("z", 1.0)))?.sessionId)

        val files = listOf(ReviewFile("a", 1, 0, "M", false), ReviewFile("b", 0, 1, "D", true))
        val shown = visibleFiles(files, mapOf("a" to true), setOf("b"))
        assertEquals(listOf(ReviewFile("a", 1, 0, "M", true)), shown)
    }
}
