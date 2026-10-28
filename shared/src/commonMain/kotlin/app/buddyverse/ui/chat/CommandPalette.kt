package app.buddyverse.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.buddyverse.core.contract.CommandsCatalogResult
import app.buddyverse.core.list
import app.buddyverse.core.obj
import app.buddyverse.core.str
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.PressRow
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.chat
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.ltr
import kotlinx.coroutines.delay

/**
 * One palette row: a slash command or a skill. [required] = it can't run without words (goes to the composer);
 * [options] = it takes a pick from a list (the palette shows the choices from `complete.slash`).
 */
private data class Cmd(val name: String, val desc: String, val skill: Boolean, val required: Boolean, val options: Boolean)

/** An argument suggestion from `complete.slash`: [full] is the whole command line it completes to. */
private data class Arg(val full: String, val label: String, val meta: String)

/** Recently used, newest first (kept for the app's lifetime). */
private object Recent {
    val names = mutableStateListOf<String>()
    fun add(n: String) { names.remove(n); names.add(0, n); while (names.size > 6) names.removeAt(names.size - 1) }
}

/** Dispositions a phone can't run (TUI-only or replaced by native UI, like the model sheet). */
private val HIDDEN = setOf("hidden", "terminal", "composer-voice")

private fun toCmds(cat: CommandsCatalogResult): List<Cmd> {
    val desc = cat.pairs.mapNotNull { p -> p.getOrNull(0)?.let { it to p.getOrElse(1) { "" } } }.toMap()
    val skills = cat.skills?.keys.orEmpty()
    fun meta(n: String) = cat.commands?.get(n)?.obj
    fun hidden(n: String): Boolean { val d = meta(n).str("desktop"); return d != null && d in HIDDEN }
    val out = mutableListOf<Cmd>()
    for (n in skills) out += Cmd(n, desc[n].orEmpty(), skill = true, required = true, options = false)
    val ordered = cat.categories.flatMap { it.pairs } + cat.pairs
    for (p in ordered) {
        val n = p.getOrNull(0) ?: continue
        if (n in skills || hidden(n)) continue
        val d = p.getOrElse(1) { "" }
        val options = cat.sub.list(n).isNotEmpty() || meta(n).str("argument_mode") == "options"
        out += Cmd(n, d, skill = false, required = !options && '<' in d, options = options)
    }
    return out.distinctBy { it.name }
}

/** `complete.slash` item → the whole line: keep [typed] up to `replace_from`, then the item ("/personality al" + "alice"). */
internal fun completeLine(typed: String, replaceFrom: Long?, item: String): String =
    typed.substring(0, (replaceFrom ?: typed.length.toLong()).toInt().coerceIn(0, typed.length)) + item

/** Prefix beats substring beats subsequence beats a description hit; 0 = no match. */
private fun score(q: String, c: Cmd): Int {
    val n = c.name.lowercase().removePrefix("/")
    if (n.startsWith(q)) return 100 - n.length
    if (q in n) return 60
    var i = 0
    for (ch in n) if (i < q.length && ch == q[i]) i++
    if (i == q.length) return 40
    return if (q in c.desc.lowercase()) 20 else 0
}

/**
 * Command palette (design §3.9): 70% sheet, search on top, Recent · Skills · Commands from `commands.catalog`.
 * A tap runs a command, puts it in the composer when it needs words (skills, required arguments), or shows its
 * choices when it takes one (`complete.slash` argument stage). Long-press always puts it in the composer.
 */
@Composable
fun CommandPalette(visible: Boolean, profile: String, sessionId: String?, onDismiss: () -> Unit, onRun: (String) -> Unit, onInsert: (String) -> Unit) {
    val store = LocalStore.current
    val fb = LocalFeedback.current
    val s = S.current
    val sc = S.chat
    val c = Theme.colors
    var query by remember { mutableStateOf(TextFieldValue("")) }
    fun setQuery(t: String) { query = TextFieldValue(t, TextRange(t.length)) }
    val focus = remember { FocusRequester() }
    val cmds by produceState<List<Cmd>?>(null, profile, sessionId, visible) {
        if (visible) value = store.catalog(profile, sessionId)?.let(::toCmds) ?: value ?: emptyList()
    }
    LaunchedEffect(visible) {
        if (visible) { setQuery(""); delay(180); runCatching { focus.requestFocus() } }
    }
    val all = cmds.orEmpty()
    val typed = "/" + query.text.trimStart().removePrefix("/")
    val argStage = ' ' in typed
    val q = typed.drop(1).trim().lowercase()
    val ranked = if (q.isEmpty() || argStage) null else all.map { it to score(q, it) }.filter { it.second > 0 }
        .sortedByDescending { it.second }.map { it.first }.take(60)
    // Argument stage ("/personality al"): the gateway's own suggestions, debounced.
    val args by produceState(emptyList<Arg>(), typed, visible) {
        if (!visible || !argStage) { value = emptyList(); return@produceState }
        delay(120)
        val r = runCatching { store.api.completeSlash(typed, profile, sessionId) }.getOrNull() ?: return@produceState
        value = r.items.mapNotNull { i -> i.text?.let { t -> Arg(completeLine(typed, r.replaceFrom, t), i.display ?: t, i.meta.orEmpty()) } }.distinctBy { it.full }
    }

    fun exec(line: String) {
        Recent.add(line.substringBefore(' '))
        onDismiss()
        onRun(line.trim())
    }
    fun pick(cmd: Cmd, run: Boolean) {
        if (run && cmd.options) { setQuery(cmd.name.removePrefix("/") + " "); fb.haptic(Haptic.Tick); return }
        Recent.add(cmd.name)
        onDismiss()
        if (run) onRun(cmd.name) else onInsert(cmd.name + " ")
    }
    fun insert(line: String) { onDismiss(); onInsert(line.trimEnd() + " ") }
    fun runTyped() {
        val first = ranked?.firstOrNull()
        when {
            argStage -> exec(typed)
            first != null -> pick(first, !first.skill && !first.required)
            typed.length > 1 -> exec(typed)
        }
    }

    Sheet(visible, onDismiss, title = sc.commandsAndSkills, heightFraction = .7f) {
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(18.dp)).background(c.surface2)
                .padding(horizontal = 18.dp, vertical = 16.dp).focusRequester(focus),
            textStyle = Theme.type.callout.copy(color = c.text),
            singleLine = true,
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { runTyped() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.text.isEmpty()) Txt(s.searchCommands, Theme.type.callout, c.tertiary, maxLines = 1)
                    inner()
                }
            },
        )
        Spacer(Modifier.padding(top = 4.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            when {
                cmds == null -> item(key = "loading") { Txt(s.loading, Theme.type.sub, c.tertiary, Modifier.padding(20.dp)) }
                argStage -> {
                    item(key = "raw") { CmdRow(Icons.keyboardReturn, typed.trim(), sc.runIt, { exec(typed) }, { insert(typed) }) }
                    items(args, key = { "a-" + it.full }) { a -> CmdRow(Icons.chevronRight, a.label, a.meta, { exec(a.full) }, { insert(a.full) }) }
                }
                ranked != null -> {
                    items(ranked, key = { "q" + it.name }) { cmd -> CmdRow(iconOf(cmd), cmd.name, cmd.desc, { pick(cmd, !cmd.skill && !cmd.required) }, { pick(cmd, false) }) }
                    if (ranked.isEmpty()) item(key = "none") { Txt(s.nothingHere, Theme.type.sub, c.tertiary, Modifier.padding(20.dp)) }
                }
                else -> {
                    val recent = Recent.names.mapNotNull { n -> all.firstOrNull { it.name == n } }
                    section("r", s.recent, recent, Icons.history, ::pick)
                    section("s", s.skills, all.filter { it.skill }, null, ::pick)
                    section("c", s.commands, all.filter { !it.skill }, null, ::pick)
                }
            }
        }
    }
}

private fun iconOf(cmd: Cmd) = if (cmd.skill) Icons.autoAwesome else Icons.bolt

private fun LazyListScope.section(prefix: String, title: String, list: List<Cmd>, icon: ImageVector?, pick: (Cmd, Boolean) -> Unit) {
    if (list.isEmpty()) return
    item(key = "$prefix-title") { SectionLabel(title) }
    items(list, key = { "$prefix-" + it.name }) { cmd ->
        CmdRow(icon ?: iconOf(cmd), cmd.name, cmd.desc, { pick(cmd, !cmd.skill && !cmd.required) }, { pick(cmd, false) })
    }
}

@Composable
private fun CmdRow(icon: ImageVector, name: String, desc: String, onClick: () -> Unit, onLongPress: (() -> Unit)?) {
    val c = Theme.colors
    PressRow(onClick, minHeight = 56.dp, onLongPress = onLongPress, label = name) {
        Icon(icon, c.secondary, 22.dp, mirror = icon == Icons.chevronRight)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(ltr(name), Theme.type.callout, c.text, maxLines = 1)
            if (desc.isNotBlank()) Txt(desc, Theme.type.caption, c.secondary, maxLines = 1)
        }
    }
}
