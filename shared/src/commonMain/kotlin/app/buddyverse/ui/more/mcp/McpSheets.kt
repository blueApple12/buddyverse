package app.buddyverse.ui.more.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.Mood
import app.buddyverse.core.Bot
import app.buddyverse.core.bool
import app.buddyverse.core.list
import app.buddyverse.core.nowMillis
import app.buddyverse.core.obj
import app.buddyverse.core.str
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Chip
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.i18n.McpPageStrings
import app.buddyverse.i18n.S
import app.buddyverse.i18n.mcpPage
import kotlinx.serialization.json.JsonObject

private val SECRET_KEY = Regex("KEY|TOKEN|SECRET|PASSWORD", RegexOption.IGNORE_CASE)

fun statusText(st: McpState, tools: Int?, t: McpPageStrings): String = when (st) {
    McpState.Connected -> listOfNotNull(t.connected, tools?.let(t.tools)).joinToString(" · ")
    McpState.Failed -> t.failed
    McpState.Connecting -> t.connecting
    McpState.NeedsAuth -> t.needsSignIn
    McpState.Idle -> tools?.let(t.tools) ?: t.idle
    McpState.Off -> t.off
}

@Composable
fun statusColor(st: McpState): Color = when (st) {
    McpState.Connected -> Theme.colors.success
    McpState.Failed -> Theme.colors.errorText
    McpState.NeedsAuth -> Theme.colors.needsYouText
    McpState.Connecting -> Theme.colors.working
    McpState.Idle, McpState.Off -> Theme.colors.secondary
}

/** A small loading state that is a living buddy, never a spinner (design rule 1). */
@Composable
private fun Thinking(bot: Bot?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 140.dp), contentAlignment = Alignment.Center) {
        if (bot != null) Buddy(bot.spec, Mood.Thinking, 72.dp)
    }
}

/** One server: status, probe, tools on/off, API key, sign-in, edit, delete. Shown while [name] != null. */
@Composable
fun ServerSheet(m: McpModel, name: String?, bot: Bot?, mood: Mood, onEdit: (McpServer) -> Unit, onDismiss: () -> Unit) {
    val t = S.mcpPage
    val s = S.current
    val c = Theme.colors
    val uri = LocalUriHandler.current
    val last = remember { arrayOfNulls<String>(1) }
    if (name != null) last[0] = name
    val shown = last[0]
    val srv = m.servers.firstOrNull { it.name == shown }
    Sheet(name != null, onDismiss, title = shown, heightFraction = null) {
        if (srv == null) { Thinking(bot); return@Sheet }
        val st = m.stateOf(srv)
        val probe = m.probes[srv.name]
        val testing = probe != null && probe.ok == null
        val signingIn = srv.name in m.oauth
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (bot != null) { Buddy(bot.spec, if (testing || signingIn) Mood.Working else mood, 56.dp, cue = m.cue?.takeIf { nowMillis() - it.serial < 2_000 }); Spacer(Modifier.width(14.dp)) }
                Column(Modifier.weight(1f)) {
                    Txt(statusText(st, m.toolCount(srv), t), Theme.type.headline, statusColor(st), maxLines = 1)
                    Txt(srv.target, Theme.type.code, c.secondary, maxLines = 3)
                }
            }
            srv.plugin?.let { Txt(t.fromPlugin(it), Theme.type.sub, c.secondary) }
            if (srv.envKeys.isNotEmpty()) Column {
                Txt(t.keys.uppercase(), Theme.type.caps, c.secondary, maxLines = 1)
                Txt(srv.envKeys.joinToString("  "), Theme.type.code, c.text)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(if (testing) t.testing else t.test, { m.test(srv) }, Modifier.weight(1f), icon = Icons.playArrow, enabled = !testing && srv.enabled)
                val canSignIn = srv.isUrl && !srv.readOnly && (srv.auth == "oauth" || st == McpState.NeedsAuth)
                if (canSignIn) PillButton(t.signIn, { m.signIn(srv) { u -> runCatching { uri.openUri(u) } } }, Modifier.weight(1f),
                    kind = ButtonKind.Outline, icon = Icons.lock, enabled = !signingIn)
            }
            if (signingIn) Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(t.finishInBrowser, Theme.type.sub, c.needsYouText, Modifier.weight(1f))
                PillButton(s.cancel, { m.cancelSignIn(srv) }, height = 48.dp, kind = ButtonKind.Quiet)
            }
            val probeError = probe?.takeIf { it.ok == false }?.error
            if (probeError != null) Txt(probeError, Theme.type.sub, c.errorText, maxLines = 6)
            val tools = probe?.takeIf { it.ok == true }?.tools.orEmpty()
            if (tools.isNotEmpty()) {
                Txt("${t.toolsHeader} · ${tools.count { srv.toolOn(it.name) }}/${tools.size}".uppercase(), Theme.type.caps, c.secondary, maxLines = 1)
                tools.forEach { tool ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Txt(tool.name, Theme.type.code, maxLines = 1)
                            if (tool.description.isNotBlank()) Txt(tool.description, Theme.type.sub, c.secondary, maxLines = 2)
                        }
                        Toggle(srv.toolOn(tool.name), { on -> m.setTool(srv, tool.name, on) }, tool.name, enabled = !srv.readOnly)
                    }
                }
            }
            if (srv.isUrl && srv.auth != "oauth" && !srv.readOnly) {
                var key by remember(srv.name) { mutableStateOf("") }
                Field(key, { key = it }, t.apiKey, password = true)
                if (key.isNotBlank()) PillButton(t.saveKey, { m.saveKey(srv, key) { key = "" } }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet, icon = Icons.check)
            }
            if (!srv.readOnly) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(s.edit, { onEdit(srv) }, Modifier.weight(1f), kind = ButtonKind.Outline, icon = Icons.edit)
                PillButton(s.delete, { onDismiss(); m.delete(srv) }, Modifier.weight(1f), kind = ButtonKind.Danger, icon = Icons.delete, haptic = Haptic.Reject)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** Edit a saved server. Shown while [editing] != null. */
@Composable
fun EditSheet(m: McpModel, editing: McpServer?, bot: Bot?, onDismiss: () -> Unit) {
    val last = remember { arrayOfNulls<McpServer>(1) }
    if (editing != null) last[0] = editing
    val shown = last[0]
    Sheet(editing != null, onDismiss, title = shown?.name ?: S.mcpPage.editServer) {
        if (shown != null) EditorBody(m, shown, bot) { onDismiss() }
    }
}

/** Add a server: from the catalog or your own. [onAdded] gets the new server's name. */
@Composable
fun AddSheet(m: McpModel, open: Boolean, bot: Bot?, onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    val t = S.mcpPage
    var tab by remember { mutableStateOf(0) }
    Sheet(open, onDismiss, title = t.addServer, heightFraction = .92f) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(t.catalog, tab == 0, { tab = 0 }, icon = Icons.apps)
            Chip(t.custom, tab == 1, { tab = 1 }, icon = Icons.build)
        }
        if (tab == 0) CatalogBody(m, bot, onAdded) else EditorBody(m, null, bot, onAdded)
    }
}

@Composable
private fun ColumnScope.EditorBody(m: McpModel, editing: McpServer?, bot: Bot?, onSaved: (String) -> Unit) {
    val t = S.mcpPage
    val c = Theme.colors
    var d by remember(editing?.name) { mutableStateOf(if (editing == null) McpDraft() else null) }
    LaunchedEffect(editing?.name) { if (editing != null) { d = m.draftFor(editing) ?: McpDraft(name = editing.name) } }
    var saving by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val draft = d
    if (draft == null) { Thinking(bot); return }
    fun edit(f: (McpDraft) -> McpDraft) { d = d?.let(f); err = null }
    Column(
        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (editing == null) Field(draft.name, { v -> edit { it.copy(name = v) } }, t.name, mono = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(t.kindLink, draft.useUrl, { edit { it.copy(useUrl = true) } }, icon = Icons.link)
            Chip(t.kindCommand, !draft.useUrl, { edit { it.copy(useUrl = false) } }, icon = Icons.terminal)
        }
        if (draft.useUrl) {
            Field(draft.url, { v -> edit { it.copy(url = v) } }, t.link, mono = true, keyboard = KeyboardType.Uri)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(t.authNone, draft.auth == McpAuth.None, { edit { it.copy(auth = McpAuth.None) } })
                Chip(t.authKey, draft.auth == McpAuth.Key, { edit { it.copy(auth = McpAuth.Key) } })
                Chip(t.authOAuth, draft.auth == McpAuth.OAuth, { edit { it.copy(auth = McpAuth.OAuth) } })
            }
            if (draft.auth == McpAuth.Key) Field(draft.key, { v -> edit { it.copy(key = v) } }, if (editing != null) t.keepKey else t.apiKey, password = true)
        } else {
            Field(draft.command, { v -> edit { it.copy(command = v) } }, t.command, mono = true)
            Field(draft.args, { v -> edit { it.copy(args = v) } }, t.args, singleLine = false, mono = true, minHeight = 96.dp)
            Field(draft.env, { v -> edit { it.copy(env = v) } }, t.env, singleLine = false, mono = true, minHeight = 96.dp)
        }
        err?.let { Txt(it, Theme.type.sub, c.errorText) }
    }
    val label = when {
        editing != null -> S.current.save
        saving -> t.adding
        else -> t.add
    }
    PillButton(
        label,
        {
            saving = true; err = null
            m.save(draft, editing) { e ->
                saving = false
                if (e == null) onSaved(editing?.name ?: draft.name.trim()) else { err = e }
            }
        },
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        icon = if (editing == null) Icons.add else Icons.check, enabled = !saving,
    )
}

@Composable
private fun ColumnScope.CatalogBody(m: McpModel, bot: Bot?, onAdded: (String) -> Unit) {
    val t = S.mcpPage
    val c = Theme.colors
    LaunchedEffect(m.profile) { m.loadCatalog() }
    var q by remember { mutableStateOf("") }
    var pick by remember { mutableStateOf<JsonObject?>(null) }
    val picked = pick
    if (picked != null) { InstallForm(m, picked, { pick = null }, onAdded); return }
    Field(q, { q = it }, S.current.search, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    val all = m.catalog
    val err = m.catalogError
    if (all == null) {
        if (err != null) Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Txt(err, Theme.type.sub, c.errorText)
            PillButton(S.current.retry, { m.loadCatalog() }, kind = ButtonKind.Quiet, icon = Icons.refresh)
        } else Thinking(bot, Modifier.weight(1f))
        return
    }
    val shown = remember(all, q) {
        all.filter { e -> q.isBlank() || listOf("name", "description").any { e.str(it).orEmpty().contains(q.trim(), ignoreCase = true) } }
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        if (shown.isEmpty()) item { Txt(t.noMatches, Theme.type.sub, c.secondary, Modifier.padding(20.dp)) }
        items(shown, key = { it.str("name").orEmpty() }) { e ->
            val name = e.str("name").orEmpty()
            val installed = e.bool("installed") == true
            val busy = name in m.installing
            PressRow({
                when {
                    installed -> onAdded(name)
                    e.list("required_env").isNotEmpty() -> { pick = e }
                    else -> m.install(e, emptyMap()) { x -> if (x == null) onAdded(name) }
                }
            }, Modifier.animateItem(), minHeight = 72.dp, label = name) {
                Column(Modifier.weight(1f)) {
                    Txt(name, Theme.type.headline, maxLines = 1)
                    e.str("description")?.let { Txt(it, Theme.type.sub, c.secondary, maxLines = 2) }
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                    when {
                        installed -> Icon(Icons.checkCircleFilled, c.success)
                        busy && bot != null -> Buddy(bot.spec, Mood.Working, 36.dp, interactive = false)
                        else -> Icon(Icons.add, c.accent)
                    }
                }
            }
        }
    }
}

/** A catalog entry that needs keys before it can be added. */
@Composable
private fun ColumnScope.InstallForm(m: McpModel, e: JsonObject, onBack: () -> Unit, onAdded: (String) -> Unit) {
    val t = S.mcpPage
    val c = Theme.colors
    val name = e.str("name").orEmpty()
    val req = remember(e) { e.list("required_env").mapNotNull { it.obj } }
    val values = remember(e) { mutableStateMapOf<String, String>() }
    var err by remember(e) { mutableStateOf<String?>(null) }
    Column(
        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 20.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(Icons.arrowBack, S.current.back, onBack, mirror = true)
            Txt(name, Theme.type.title2, modifier = Modifier.weight(1f), maxLines = 1)
        }
        Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            e.str("description")?.let { Txt(it, Theme.type.sub, c.secondary) }
            req.forEach { r ->
                val k = r.str("name") ?: return@forEach
                Field(values[k].orEmpty(), { values[k] = it; err = null }, r.str("prompt") ?: k, password = SECRET_KEY.containsMatchIn(k), mono = true)
            }
            err?.let { Txt(it, Theme.type.sub, c.errorText) }
        }
    }
    val busy = name in m.installing
    PillButton(if (busy) t.adding else t.add, {
        val missing = req.any { r -> r.bool("required") != false && r.str("name")?.let { values[it] }.isNullOrBlank() }
        if (missing) { err = t.needValue }
        else m.install(e, values.filterValues { it.isNotBlank() }) { x -> if (x == null) onAdded(name) else { err = x } }
    }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), icon = Icons.add, enabled = !busy)
}
