package app.buddyverse.ui.more.checkpoints

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.buddyverse.design.Icon
import app.buddyverse.design.Icons
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt

enum class LineKind { File, Hunk, Add, Del, Ctx, Meta }

/** One display line of a unified diff; [oldNo]/[newNo] are 1-based line numbers on each side. */
@Immutable
data class DiffLine(val kind: LineKind, val text: String, val oldNo: Int? = null, val newNo: Int? = null)

private val HUNK = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@ ?(.*)$""")
private val HIDDEN_HEADERS = listOf("index ", "--- ", "+++ ")

/** The new-side path of a `diff --git a/x b/x` header (quotes dropped). */
fun diffPath(header: String): String {
    val rest = header.removePrefix("diff --git ").replace("\"", "")
    return if (" b/" in rest) rest.substringAfterLast(" b/") else rest.substringAfter(' ').removePrefix("b/")
}

/**
 * Unified diff → display lines. Header lines (`index`, `---`, `+++`) are dropped; mode/rename/binary notes stay as
 * [LineKind.Meta]. Lines before the first `@@` of a file are header, so a removed line reading `-- x` stays a removal.
 */
fun parseDiff(diff: String): List<DiffLine> {
    val out = ArrayList<DiffLine>()
    var header = true
    var old = 0
    var new = 0
    for (raw in diff.lineSequence()) {
        val l = raw.removeSuffix("\r").replace("\t", "    ")
        when {
            l.startsWith("diff --git ") -> { header = true; out += DiffLine(LineKind.File, diffPath(l)) }
            l.startsWith("@@") -> {
                header = false
                val m = HUNK.find(l)
                old = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                new = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
                out += DiffLine(LineKind.Hunk, m?.groupValues?.get(3)?.trim().orEmpty())
            }
            header -> if (l.isNotBlank() && HIDDEN_HEADERS.none { l.startsWith(it) }) out += DiffLine(LineKind.Meta, l)
            l.startsWith("+") -> out += DiffLine(LineKind.Add, l.drop(1), newNo = new++)
            l.startsWith("-") -> out += DiffLine(LineKind.Del, l.drop(1), oldNo = old++)
            l.startsWith(" ") -> out += DiffLine(LineKind.Ctx, l.drop(1), old++, new++)
            l.isEmpty() -> Unit
            else -> out += DiffLine(LineKind.Meta, l)   // "\ No newline at end of file", "# New (untracked) files:" …
        }
    }
    return out
}

/** File paths named by `git diff --stat` rows (` path | 3 ++-`); renames (`a => b`) and cut paths (`.../x`) are skipped. */
fun statFiles(stat: String): List<String> = stat.lineSequence().mapNotNull { l ->
    val i = l.lastIndexOf(" | ")
    if (i < 0) null else l.substring(0, i).trim().takeIf { it.isNotEmpty() && !it.startsWith("...") && " => " !in it }
}.toList()

/** The stat's closing line: " 2 files changed, 3 insertions(+), 1 deletion(-)". */
fun statSummary(stat: String): String? = stat.lineSequence().map { it.trim() }.lastOrNull { "changed" in it && it.first().isDigit() }

/** Every file a checkpoint diff touches, for per-file restore: diff headers first, then stat rows. */
fun touchedFiles(stat: String, lines: List<DiffLine>): List<String> =
    (lines.filter { it.kind == LineKind.File }.map { it.text } + statFiles(stat)).distinct()

/** Last path segment. */
fun fileName(path: String): String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

/** Everything before the last segment, or "" at the root. */
fun fileDir(path: String): String = path.trimEnd('/', '\\').let { p -> p.substring(0, maxOf(p.lastIndexOf('/'), p.lastIndexOf('\\'), 0)) }

/** Diff lines as list items: code is always LTR, wraps instead of scrolling sideways (design §9.5). */
fun LazyListScope.diffLines(lines: List<DiffLine>, keyPrefix: String = "dl") {
    itemsIndexed(lines, key = { i, _ -> "$keyPrefix$i" }, contentType = { _, l -> l.kind }) { _, l -> DiffRow(l) }
}

@Composable
private fun DiffRow(l: DiffLine) {
    val c = Theme.colors
    val code = Theme.type.code
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        when (l.kind) {
            LineKind.File -> Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.description, c.secondary, 18.dp)
                Spacer(Modifier.width(6.dp))
                Txt(l.text, code.copy(fontWeight = FontWeight.SemiBold), c.text, Modifier.weight(1f))
            }
            LineKind.Hunk -> Txt(if (l.text.isBlank()) "⋯" else "⋯  ${l.text}", code, c.tertiary,
                Modifier.fillMaxWidth().background(c.surface2).padding(horizontal = 16.dp, vertical = 4.dp), maxLines = 1)
            LineKind.Meta -> Txt(l.text, code.copy(fontSize = 12.sp), c.secondary, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp))
            else -> {
                val (bg, sign, signColor) = when (l.kind) {
                    LineKind.Add -> Triple(c.success.copy(alpha = if (c.dark) .16f else .12f), "+", c.success)
                    LineKind.Del -> Triple(c.error.copy(alpha = if (c.dark) .16f else .11f), "−", c.error)
                    else -> Triple(androidx.compose.ui.graphics.Color.Transparent, " ", c.tertiary)
                }
                Row(Modifier.fillMaxWidth().background(bg).padding(end = 12.dp)) {
                    Txt((l.newNo ?: l.oldNo)?.toString().orEmpty(), code.copy(fontSize = 12.sp, textAlign = TextAlign.End), c.tertiary,
                        Modifier.width(48.dp).padding(end = 6.dp), maxLines = 1)
                    Txt(sign, code, signColor, Modifier.width(14.dp), maxLines = 1)
                    Txt(l.text, code, c.text, Modifier.weight(1f))
                }
            }
        }
    }
}
