package app.buddyverse.ui.more.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.core.api.CatalogPlugin
import app.buddyverse.core.api.PluginField
import app.buddyverse.core.api.PluginRow
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Chip
import app.buddyverse.design.Dot
import app.buddyverse.design.Field
import app.buddyverse.design.Hairline
import app.buddyverse.design.Icons
import app.buddyverse.design.PillButton
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.pluginsPage
import app.buddyverse.ui.ltr

// Every sheet here closes three ways (back, scrim, its 48dp ×) and is placed last in the page's full-size Box (design rule 4).

/** Keeps the last non-null value so a sheet still has content while it slides away. */
@Composable
private fun <T : Any> rememberLast(v: T?): T? {
    val last = remember { arrayOfNulls<Any>(1) }
    if (v != null) last[0] = v
    @Suppress("UNCHECKED_CAST")
    return last[0] as T?
}

/** The engine vendor's own name as a maintainer is not shown (design §0). */
private val BRAND = listOf("nous", "hermes")

@Composable
private fun Caps(text: String) = Txt(text.uppercase(), Theme.type.caps, Theme.colors.secondary, maxLines = 1)

/** Names that are code (tools, keys): mono, always LTR (design §9.5). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodeTags(names: List<String>) = FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    names.forEach {
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Theme.colors.surface2).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Txt(it, Theme.type.code.copy(fontSize = 12.sp, lineHeight = 16.sp), Theme.colors.text, maxLines = 1)
        }
    }
}

/** Scrollable body of a sheet: sized to its content, never taller than the screen. */
@Composable
private fun ColumnScope.Body(content: @Composable () -> Unit) = Column(
    Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
) { content() }

@Composable
private fun Buttons(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)

/** One installed plugin: its switch, update, what it brings (tools, app-backed servers), its settings, and Remove. */
@Composable
fun PluginSheet(m: PluginsModel, row: PluginRow?, worker: BuddySpec?, mood: Mood, onDismiss: () -> Unit, onRemove: (PluginRow) -> Unit) {
    val shown = rememberLast(row)
    Sheet(row != null, onDismiss, title = shown?.let { pluginTitle(it.name) }) {
        val r = shown?.let { m.row(it.id) ?: it } ?: return@Sheet
        val t = S.pluginsPage
        val c = Theme.colors
        val busy = r.id in m.busy
        val updating = r.id in m.updating
        Body {
            // The bot's buddy rides along: it works while this plugin updates and reacts to every result.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (worker != null) { Buddy(worker, if (busy) Mood.Working else mood, 48.dp, cue = freshCue(m.cue, r.id)); Spacer(Modifier.width(12.dp)) }
                Column(Modifier.weight(1f)) {
                    Txt(t.on, Theme.type.headline, maxLines = 1)
                    Txt(listOfNotNull(r.version.ifBlank { null }?.let { ltr("v$it") }, originLabel(r, t)).joinToString(" · "), Theme.type.caption, c.secondary, maxLines = 1)
                }
                if (r.toggleable) Toggle(r.on, { m.toggle(r, it) }, pluginTitle(r.name), enabled = !updating)
                else Txt(if (r.on) t.fixedOn else S.current.disabled, Theme.type.sub, c.secondary)
            }
            if (r.updateAvailable) PillButton(
                if (updating) t.updating else r.updateTo?.let { t.updateTo(ltr(it)) } ?: t.update,
                { m.update(r) }, Modifier.fillMaxWidth(), icon = Icons.refresh, enabled = !busy,
            )
            Hairline()
            val d = remember(r.description) { brandFree(r.description) }
            if (d.isNotBlank()) Txt(d, Theme.type.sub)
            if (r.servers.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Caps(t.apps)
                r.servers.forEach { sv ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (sv.state == "connected") c.success else c.needsYou)
                        Spacer(Modifier.width(10.dp))
                        Txt("${sv.name} · ${t.serverState(sv.state)}", Theme.type.callout, maxLines = 1)
                    }
                    if (sv.sentence.isNotBlank() && sv.state != "connected") Txt(brandFree(sv.sentence), Theme.type.sub, c.needsYouText)
                }
            }
            if (r.tools.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Caps(t.tools); CodeTags(r.tools) }
            if (r.settings.isNotEmpty() && r.key.isNotBlank()) SettingsForm(m, r, busy)
        }
        if (r.removable) Buttons {
            PillButton(t.remove, { onRemove(r) }, Modifier.weight(1f), kind = ButtonKind.Outline, icon = Icons.delete, enabled = !busy)
        }
    }
}

/**
 * The manifest's `config_schema` as a form. Values are edited as text and typed on save; secrets start empty and go only to the
 * bot's `.env` (a saved one shows "Saved · type to replace").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsForm(m: PluginsModel, r: PluginRow, busy: Boolean) {
    val t = S.pluginsPage
    val c = Theme.colors
    val seed = remember(r.settings) { initialDraft(r.settings) }
    var draft by remember(r.id, seed) { mutableStateOf(seed) }
    var err by remember(r.id) { mutableStateOf<String?>(null) }
    fun set(f: PluginField, v: String) { draft = draft + (f.key to v); err = null }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(4.dp))
        Caps(t.settings)
        r.settings.forEach { f ->
            val raw = draft[f.key].orEmpty()
            val name = brandFree(f.label).ifBlank { f.key }
            val label = if (f.required) name else "$name · ${t.optional}"
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (f.type) {
                    "boolean" -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Txt(label, Theme.type.callout, modifier = Modifier.weight(1f))
                        Toggle(raw == "true", { set(f, if (it) "true" else "false") }, f.label, enabled = !busy)
                    }
                    "enum" -> {
                        Txt(label, Theme.type.callout)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { f.choices.forEach { ch -> Chip(ch, raw == ch, { set(f, ch) }) } }
                    }
                    "secret" -> {
                        Txt(label, Theme.type.callout)
                        Field(raw, { set(f, it) }, if (f.hasValue) t.secretSet else f.env ?: f.label, password = true)
                    }
                    "json" -> {
                        Txt(label, Theme.type.callout)
                        Field(raw, { set(f, it) }, "{ }", singleLine = false, mono = true, minHeight = 96.dp)
                    }
                    "number" -> {
                        Txt(label, Theme.type.callout)
                        Field(raw, { set(f, it) }, f.default?.toString().orEmpty(), keyboard = KeyboardType.Decimal)
                    }
                    else -> {
                        Txt(label, Theme.type.callout)
                        Field(raw, { set(f, it) }, f.default?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty())
                    }
                }
                if (f.description.isNotBlank()) Txt(brandFree(f.description), Theme.type.caption, c.secondary, maxLines = 4)
            }
        }
        err?.let { Txt(it, Theme.type.sub, c.errorText) }
        PillButton(S.current.save, {
            m.saveSettings(r, draft) { e ->
                err = e
                // Secrets are never echoed back: clear them so the placeholder says "saved".
                if (e == null) draft = draft.mapValues { (k, v) -> if (r.settings.any { it.key == k && it.type == "secret" }) "" else v }
            }
        }, Modifier.fillMaxWidth(), enabled = !busy && draft != seed)
    }
}

/** Long-press menu of a row (design §3.7: the row shows one control, the rest live here). */
@Composable
fun PluginMenu(row: PluginRow?, onDismiss: () -> Unit, onView: (PluginRow) -> Unit, onToggle: (PluginRow) -> Unit, onUpdate: (PluginRow) -> Unit,
    onRemove: (PluginRow) -> Unit) {
    val shown = rememberLast(row)
    Sheet(row != null, onDismiss, title = shown?.let { pluginTitle(it.name) }) {
        val r = shown ?: return@Sheet
        val t = S.pluginsPage
        MenuRow(Icons.visibility, t.details) { onDismiss(); onView(r) }
        if (r.updateAvailable) MenuRow(Icons.refresh, r.updateTo?.let { t.updateTo(ltr(it)) } ?: t.update) { onDismiss(); onUpdate(r) }
        if (r.toggleable) MenuRow(if (r.on) Icons.pause else Icons.playArrow, if (r.on) t.turnOff else t.turnOn) { onDismiss(); onToggle(r) }
        if (r.removable) MenuRow(Icons.delete, t.remove, danger = true) { onDismiss(); onRemove(r) }
    }
}

/** One catalog entry: who made it, what it brings and needs, what this PC lacks for it, and Install. */
@Composable
fun CatalogSheet(m: PluginsModel, entry: CatalogPlugin?, worker: BuddySpec?, onDismiss: () -> Unit) {
    val shown = rememberLast(entry)
    Sheet(entry != null, onDismiss, title = shown?.let { pluginTitle(it.name) }) {
        val e = shown ?: return@Sheet
        val t = S.pluginsPage
        val c = Theme.colors
        val uri = LocalUriHandler.current
        val installed = m.installed(e.name)
        val busy = e.name in m.busy
        var lacks by remember(e.name, m.profile) { mutableStateOf<String?>(null) }
        LaunchedEffect(e.name, m.profile) { lacks = m.presence(e.name) }
        Body {
            val meta = listOfNotNull(
                tierLabel(e.tier, t), e.maintainer.takeIf { it.isNotBlank() && BRAND.none { b -> b in it.lowercase() } }?.let(t.by), e.version.ifBlank { null }?.let { ltr("v$it") },
                e.stars?.takeIf { it > 0 }?.let { ltr("★ $it") },
            )
            if (meta.isNotEmpty()) Txt(meta.joinToString(" · "), Theme.type.caption, tierColor(e.tier))
            lacks?.let { Txt(it, Theme.type.callout, c.needsYouText) }
            val d = remember(e.description) { brandFree(e.description) }
            if (d.isNotBlank()) Txt(d, Theme.type.sub)
            if (e.requiresEnv.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Caps(t.needsKeys); CodeTags(e.requiresEnv) }
            if (e.tools.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Caps(t.tools); CodeTags(e.tools) }
            if (e.platforms.isNotEmpty()) Txt("${t.runsOn}: ${e.platforms.joinToString(", ")}", Theme.type.caption, c.secondary)
        }
        Buttons {
            if (worker != null) Buddy(worker, if (busy) Mood.Working else Mood.Idle, 48.dp, cue = freshCue(m.cue, e.name))
            when {
                busy -> PillButton(t.installing, {}, Modifier.weight(1f), enabled = false)
                installed -> PillButton(t.installed, {}, Modifier.weight(1f), kind = ButtonKind.Quiet, icon = Icons.checkCircle, enabled = false)
                else -> PillButton(t.install, { m.install(e.name, e.name, pluginTitle(e.name)) }, Modifier.weight(1f), icon = Icons.add)
            }
            val link = e.repo ?: e.docsUrl
            if (link != null) PillButton(t.source, { runCatching { uri.openUri(link) } }, kind = ButtonKind.Outline, icon = Icons.link)
        }
    }
}

/** Install from a git link or `owner/repo` (`plugins.manage install {identifier}`), or by a catalog name (`plugins.catalog.lookup` first). */
@Composable
fun LinkSheet(visible: Boolean, m: PluginsModel, worker: BuddySpec?, onDismiss: () -> Unit) {
    val t = S.pluginsPage
    var link by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    Sheet(visible, onDismiss, title = t.linkTitle) {
        val id = link.trim()
        val busy = id.isNotEmpty() && id in m.busy
        Body {
            Field(link, { link = it; err = null }, t.linkHint, mono = true, keyboard = KeyboardType.Uri)
            err?.let { Txt(it, Theme.type.sub, Theme.colors.errorText, maxLines = 6) }
        }
        Buttons {
            if (busy && worker != null) Buddy(remember(worker) { worker.copy(id = worker.id + "/link") }, Mood.Working, 48.dp, interactive = false)
            PillButton(if (busy) t.installing else t.install, {
                if (id.isEmpty()) { err = t.needLink; return@PillButton }
                m.installTyped(id, onDone = { link = ""; onDismiss() }, onError = { err = it })
            }, Modifier.weight(1f), icon = Icons.add, enabled = !busy)
        }
    }
}

/** The engine held an update back: a wider pin needs consent, or the safety scan flagged the new code. */
@Composable
fun AskSheet(m: PluginsModel) {
    val a = rememberLast(m.ask)
    val t = S.pluginsPage
    val title = a?.let { pluginTitle(it.row.name) }.orEmpty()
    Sheet(m.ask != null, { m.ask = null }, title = a?.let { if (it.scan) t.scanTitle(title) else t.consentTitle(title) }) {
        val ask = a ?: return@Sheet
        val c = Theme.colors
        Body {
            Txt(if (!ask.scan) t.consentBody else if (ask.canGo) t.scanCaution else t.scanBlocked, Theme.type.callout,
                if (ask.scan && !ask.canGo) c.errorText else c.text)
            ask.lines.forEach { Txt(it, Theme.type.code, c.secondary, maxLines = 3) }
        }
        Buttons {
            PillButton(if (ask.canGo) S.current.cancel else S.current.done, { m.ask = null }, Modifier.weight(1f), kind = ButtonKind.Quiet)
            if (ask.canGo) PillButton(if (ask.scan) t.updateAnyway else t.update, { m.go(ask) }, Modifier.weight(1f),
                kind = if (ask.scan) ButtonKind.Danger else ButtonKind.Primary)
        }
    }
}

/** After an install that needs something: the keys it is missing (to the bot's `.env`), its notes, a restart. */
@Composable
fun ResultSheet(m: PluginsModel) {
    val r = rememberLast(m.result)
    val t = S.pluginsPage
    Sheet(m.result != null, { m.result = null }, title = r?.let { if (it.missingEnv.isNotEmpty()) t.keysTitle(it.title) else t.installedToast(it.title) }) {
        val res = r ?: return@Sheet
        val c = Theme.colors
        var keys by remember(res) { mutableStateOf(res.missingEnv.associateWith { "" }) }
        var err by remember(res) { mutableStateOf<String?>(null) }
        Body {
            res.note?.let { Txt(it, Theme.type.callout, c.needsYouText) }
            res.missingEnv.forEach { k ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt(k, Theme.type.code, c.text)
                    Field(keys[k].orEmpty(), { keys = keys + (k to it); err = null }, t.keyValue, password = true)
                }
            }
            if (res.notes.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Caps(t.notes)
                res.notes.forEach { Txt(it, Theme.type.sub, c.secondary) }
            }
            err?.let { Txt(it, Theme.type.sub, c.errorText) }
        }
        Buttons {
            if (res.missingEnv.isEmpty()) PillButton(S.current.done, { m.result = null }, Modifier.weight(1f), kind = ButtonKind.Quiet)
            else {
                PillButton(t.later, { m.result = null }, Modifier.weight(1f), kind = ButtonKind.Quiet)
                PillButton(t.saveKeys, { m.saveKeys(keys) { e -> err = e; if (e == null) m.result = null } }, Modifier.weight(1f),
                    enabled = keys.values.any { it.isNotBlank() })
            }
        }
    }
}
