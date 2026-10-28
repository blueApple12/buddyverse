package app.buddyverse.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.buddyverse.design.Theme

/** A reply split into prose, fenced code, tables and quotes. Small on purpose: headings, bullets, `code`, **bold**, [links](…). */
sealed interface Md {
    data class Prose(val text: String) : Md
    data class Code(val code: String) : Md
    data class Table(val rows: List<List<String>>) : Md
    data class Quote(val text: String) : Md
}

private val tableSep = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")
private fun cells(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

fun mdBlocks(text: String): List<Md> {
    val out = mutableListOf<Md>()
    val prose = StringBuilder()
    var code: StringBuilder? = null
    fun flushProse() { if (prose.isNotBlank()) out += Md.Prose(prose.toString().trim('\n')); prose.clear() }
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val fence = line.trimStart().startsWith("```")
        val c = code
        when {
            fence && c == null -> { flushProse(); code = StringBuilder() }
            fence && c != null -> { out += Md.Code(c.toString().trimEnd('\n')); code = null }
            c != null -> c.append(line).append('\n')
            // | a | b |  then  |---|---|  then rows: a table
            line.trimStart().startsWith("|") && i + 1 < lines.size && tableSep.matches(lines[i + 1]) -> {
                flushProse()
                val rows = mutableListOf(cells(line))
                i += 2
                while (i < lines.size && lines[i].trimStart().startsWith("|")) { rows += cells(lines[i]); i++ }
                out += Md.Table(rows)
                continue
            }
            line.trimStart().startsWith(">") -> {
                flushProse()
                val q = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) { q.append(lines[i].trimStart().removePrefix(">").removePrefix(" ")).append('\n'); i++ }
                if (q.isNotBlank()) out += Md.Quote(q.toString().trim('\n'))
                continue
            }
            else -> prose.append(line).append('\n')
        }
        i++
    }
    flushProse()
    code?.let { out += Md.Code(it.toString().trimEnd('\n')) } // still streaming
    return out
}

private val heading = Regex("^#{1,6}\\s+")
private val bullet = Regex("^(\\s*)[-*+]\\s+")
private const val ESCAPABLE = "\\`*_{}[]()#+-.!|>~<"

fun mdInline(src: String, mono: FontFamily, codeBg: Color): AnnotatedString = buildAnnotatedString {
    src.lines().forEachIndexed { n, raw ->
        if (n > 0) append('\n')
        var line = raw
        val h = heading.find(line)
        if (h != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { spans(line.substring(h.range.last + 1), mono, codeBg) }
            return@forEachIndexed
        }
        bullet.find(line)?.let { m -> append(m.groupValues[1]); append("• "); line = line.substring(m.range.last + 1) }
        spans(line, mono, codeBg)
    }
}

private fun AnnotatedString.Builder.spans(s: String, mono: FontFamily, codeBg: Color) {
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        // \# \* \_ … are escapes: show the character, not the backslash.
        if (ch == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE) { append(s[i + 1]); i += 2; continue }
        if (ch == '`') {
            val end = s.indexOf('`', i + 1)
            if (end > i + 1) { withStyle(SpanStyle(fontFamily = mono, background = codeBg)) { append(s.substring(i + 1, end)) }; i = end + 1; continue }
        }
        if (ch == '*' && s.startsWith("**", i)) {
            val end = s.indexOf("**", i + 2)
            if (end > i + 2) { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }; i = end + 2; continue }
        }
        if (ch == '[') {
            val mid = s.indexOf("](", i + 1)
            val end = if (mid > 0) s.indexOf(')', mid + 2) else -1
            if (mid > i && end > mid && mid - i < 200) {
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(s.substring(i + 1, mid)) }
                i = end + 1; continue
            }
        }
        append(ch); i++
    }
}

/** The message's own direction (its first strong letter): lines with no letters follow it, not the UI's direction. */
internal fun rtlText(t: String): Boolean {
    for (ch in t) {
        if (ch in '֐'..'ࣿ' || ch in 'יִ'..'﷿' || ch in 'ﹰ'..'﻿') return true
        if (ch.isLetter()) return false
    }
    return false
}

/** Renders a reply (or any markdown-ish text) in [style]/[color]; code blocks and tables are LTR-safe and side-scrolling. */
@Composable
fun MdText(text: String, style: TextStyle, color: Color, codeBg: Color, modifier: Modifier = Modifier) {
    val mono = Theme.type.mono
    val blocks = remember(text) { mdBlocks(text) }
    val rtl = remember(text) { rtlText(text) }
    val base = style.copy(color = color, textDirection = if (rtl) TextDirection.ContentOrRtl else TextDirection.ContentOrLtr)
    // The message lays out in its own direction (an English table or quote in the Hebrew UI keeps its columns and bar in place).
    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) { MdColumn(blocks, base, color, codeBg, mono, modifier) }
}

@Composable
private fun MdColumn(blocks: List<Md>, base: TextStyle, color: Color, codeBg: Color, mono: FontFamily, modifier: Modifier) {
    Column(modifier) {
        blocks.forEachIndexed { i, b ->
            val top = if (i > 0) 8.dp else 0.dp
            when (b) {
                is Md.Prose -> BasicText(remember(b.text, codeBg) { mdInline(b.text, mono, codeBg) }, Modifier.padding(top = top), base)
                is Md.Code -> CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Box(Modifier.padding(top = top).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(codeBg).horizontalScroll(rememberScrollState()).padding(12.dp)) {
                        BasicText(b.code, style = Theme.type.code.copy(color = color))
                    }
                }
                is Md.Quote -> Row(Modifier.padding(top = top).height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(color.copy(alpha = .35f)))
                    BasicText(remember(b.text, codeBg) { mdInline(b.text, mono, codeBg) }, Modifier.padding(start = 10.dp), base.copy(color = color.copy(alpha = .8f)))
                }
                is Md.Table -> MdTable(b.rows, base, mono, codeBg, Modifier.padding(top = top))
            }
        }
    }
}

/** Columns side by side so every cell lines up; one line per cell, and the table scrolls sideways. */
@Composable
private fun MdTable(rows: List<List<String>>, style: TextStyle, mono: FontFamily, codeBg: Color, modifier: Modifier) {
    val cols = rows.maxOf { it.size }
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(codeBg).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 6.dp)) {
        Row {
            for (c in 0 until cols) Column(Modifier.padding(horizontal = 8.dp)) {
                rows.forEachIndexed { r, row ->
                    val cell = row.getOrNull(c).orEmpty()
                    BasicText(
                        remember(cell) { mdInline(cell, mono, codeBg) }, Modifier.padding(vertical = 3.dp),
                        if (r == 0) style.copy(fontWeight = FontWeight.Bold) else style, maxLines = 1,
                    )
                }
            }
        }
    }
}
