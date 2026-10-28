package app.buddyverse.ui.more.models

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.Mood
import app.buddyverse.core.Bot
import app.buddyverse.core.api.OwnEndpointDraft
import app.buddyverse.core.api.EnvKey
import app.buddyverse.core.nowMillis
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Chip
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.PillButton
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.ModelsPageStrings
import app.buddyverse.i18n.S
import app.buddyverse.i18n.modelsPage
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.ltr
import app.buddyverse.ui.untilIso
import kotlinx.coroutines.launch

fun statusOf(e: ProviderEntry, t: ModelsPageStrings): String = when {
    e.provider?.current == true -> t.inUse
    e.connected -> e.provider?.models?.size?.takeIf { it > 0 }?.let(t.modelsCount) ?: t.connectedWord
    e.access == Access.Key -> t.needsKey
    e.access == Access.Device -> t.signInHere
    e.access == Access.External -> t.signInOnPcShort
    else -> t.setUpOnPcShort
}

@Composable
fun statusColor(e: ProviderEntry): Color = when {
    e.provider?.current == true -> Theme.colors.accent
    e.connected -> Theme.colors.success
    else -> Theme.colors.tertiary
}

/** Every provider (connected first) with a search; tapping one opens its sheet. */
@Composable
fun ProvidersSheet(m: ModelsModel, open: Boolean, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val t = S.modelsPage
    var query by remember(open) { mutableStateOf("") }
    Sheet(open, onDismiss, title = t.addProvider, heightFraction = .92f) {
        Field(query, { query = it }, t.searchProviders, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        val q = query.trim().lowercase()
        val rows = m.entries.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.slug.contains(q) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (rows.isEmpty()) item(key = "none") { PageMessage(t.noMatches) }
            items(rows, key = { "p:" + it.slug }) { e ->
                InfoRow(e.name, statusOf(e, t), { onOpen(e.slug) }, lead = { LiveDot(statusColor(e)) },
                    subColor = if (e.connected) Theme.colors.success else Theme.colors.secondary)
            }
        }
    }
}

/** One provider: its quota, how to connect (phone sign-in, a command on the PC, or keys), and picking one of its models. */
@Composable
fun ProviderSheet(m: ModelsModel, slug: String?, bot: Bot?, mood: Mood, onPickModel: (String) -> Unit, onDismiss: () -> Unit) {
    val t = S.modelsPage
    val c = Theme.colors
    val shown = rememberLast(slug)
    val e = m.entries.firstOrNull { it.slug == shown }
    Sheet(slug != null, onDismiss, title = e?.name ?: shown) {
        if (e == null) { Thinking(bot); return@Sheet }
        val signing = m.flow?.id == e.oauth?.id && m.flow != null
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (bot != null) {
                    Buddy(bot.spec, if (signing || m.busy.any { it.endsWith(e.slug) }) Mood.Working else mood, 56.dp, cue = m.cue.fresh())
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Txt(statusOf(e, t), Theme.type.headline, statusColor(e).takeIf { e.connected } ?: c.secondary, maxLines = 1)
                    e.oauth?.account?.let { Txt(ltr(it), Theme.type.sub, c.secondary, maxLines = 1) }
                }
            }
            e.provider?.usage?.forEach { w ->
                UsageLine(w.label, w.usedPercent, untilIso(w.resetsAt, nowMillis(), S.current)?.let(t.resetsIn))
            }
            when (e.access) {
                Access.Device -> DeviceBlock(m, e)
                Access.External -> ExternalBlock(m, e)
                Access.Key -> KeyBlock(m, e, onDismiss)
                Access.Pc -> Txt(t.setUpOnPc, Theme.type.body, c.secondary)
            }
            if (e.connected && e.provider?.models?.isNotEmpty() == true) {
                PillButton(t.pickModel, { onPickModel(e.slug) }, Modifier.fillMaxWidth(), kind = ButtonKind.Outline, icon = Icons.tune)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun DeviceBlock(m: ModelsModel, e: ProviderEntry) {
    val t = S.modelsPage
    val s = S.current
    val c = Theme.colors
    val uri = LocalUriHandler.current
    @Suppress("DEPRECATION") val clip = LocalClipboardManager.current
    val toasts = LocalToasts.current
    val o = e.oauth ?: return
    val f = m.flow?.takeIf { it.id == o.id }
    when {
        f != null -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Txt(t.enterCode.uppercase(), Theme.type.caps, c.secondary, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(ltr(f.code), Theme.type.display.copy(fontFamily = Theme.type.mono), modifier = Modifier.weight(1f), maxLines = 1)
                IconButton(Icons.contentCopy, s.copy, { clip.setText(AnnotatedString(f.code)); toasts.show(ToastData(t.copied)) })
            }
            PillButton(t.openPage, {
                clip.setText(AnnotatedString(f.code))
                runCatching { uri.openUri(f.url) }
            }, Modifier.fillMaxWidth(), icon = Icons.link)
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveDot(c.working, busy = true)
                Spacer(Modifier.width(10.dp))
                Txt(t.waiting, Theme.type.sub, c.secondary, Modifier.weight(1f))
                PillButton(s.cancel, m::cancelSignIn, height = 48.dp, kind = ButtonKind.Quiet)
            }
        }
        o.loggedIn -> if (o.disconnectable) PillButton(t.signOut, { m.signOut(e) }, Modifier.fillMaxWidth(), kind = ButtonKind.Outline,
            icon = Icons.logout, haptic = Haptic.Reject, enabled = "signout:${o.id}" !in m.busy)
        else -> PillButton(t.signIn, { m.signIn(e) }, Modifier.fillMaxWidth(), icon = Icons.lock,
            enabled = m.flow == null && "signin:${o.id}" !in m.busy)
    }
}

@Composable
private fun ExternalBlock(m: ModelsModel, e: ProviderEntry) {
    val t = S.modelsPage
    val c = Theme.colors
    @Suppress("DEPRECATION") val clip = LocalClipboardManager.current
    val toasts = LocalToasts.current
    val o = e.oauth ?: return
    if (!e.connected) Txt(t.signInOnPc, Theme.type.body, c.secondary)
    o.cliCommand?.let { cmd ->
        if (!e.connected) PillButton(t.copyCommand, { clip.setText(AnnotatedString(cmd)); toasts.show(ToastData(t.copied)) }, Modifier.fillMaxWidth(),
            kind = ButtonKind.Quiet, icon = Icons.terminal)
    }
    if (o.loggedIn && o.disconnectable) PillButton(t.signOut, { m.signOut(e) }, Modifier.fillMaxWidth(), kind = ButtonKind.Outline,
        icon = Icons.logout, haptic = Haptic.Reject, enabled = "signout:${o.id}" !in m.busy)
}

/** The provider's keys: paste, test, save; the main key can be removed (with undo). */
@Composable
private fun KeyBlock(m: ModelsModel, e: ProviderEntry, onDismiss: () -> Unit) {
    val t = S.modelsPage
    val c = Theme.colors
    val keys = m.keysFor(e.slug)
    val values = remember(e.slug) { mutableStateMapOf<String, String>() }
    var err by remember(e.slug) { mutableStateOf<String?>(null) }
    val saving = "keys:${e.slug}" in m.busy
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        keys.forEach { v -> KeyInput(v, values[v.key].orEmpty()) { values[v.key] = it; err = null } }
        err?.let { Txt(it, Theme.type.sub, c.errorText, maxLines = 6) }
        PillButton(if (saving) t.checking else t.connect, { m.saveKeys(e, values.toMap()) { r -> err = r; if (r == null) values.clear() } },
            Modifier.fillMaxWidth(), icon = Icons.check, enabled = !saving && values.values.any { it.isNotBlank() }, haptic = Haptic.Confirm)
        keys.firstOrNull { it.isPassword && it.isSet }?.let { main ->
            PillButton(t.removeKey, { onDismiss(); m.removeKey(main) }, Modifier.fillMaxWidth(), kind = ButtonKind.Danger, icon = Icons.delete, haptic = Haptic.Reject)
        }
    }
}

/** One `.env` entry as a field, with its name and a "get a key" link. */
@Composable
private fun KeyInput(v: EnvKey, value: String, onChange: (String) -> Unit) {
    val t = S.modelsPage
    val c = Theme.colors
    val uri = LocalUriHandler.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(ltr(v.label), Theme.type.code, c.secondary, Modifier.weight(1f), maxLines = 1)
            if (v.isSet) Pill(t.saved, c.success)
            v.url?.let { u -> IconButton(Icons.link, t.getKey, { runCatching { uri.openUri(u) } }, tint = c.accent) }
        }
        Field(value, onChange, when {
            v.isPassword && v.isSet -> t.replaceKey
            v.isPassword -> t.pasteKey
            else -> t.optional
        }, password = v.isPassword, mono = true, keyboard = if (v.isPassword) KeyboardType.Password else KeyboardType.Uri)
    }
}

private val API_MODES = listOf("", "chat_completions", "codex_responses", "anthropic_messages")

/** Add or edit your own OpenAI-compatible address (a local server, a LAN box, any compatible API). */
@Composable
fun EndpointSheet(m: ModelsModel, draft: OwnEndpointDraft?, onDismiss: () -> Unit) {
    val t = S.modelsPage
    val s = S.current
    val c = Theme.colors
    val fb = LocalFeedback.current
    val scope = rememberCoroutineScope()
    val start = rememberLast(draft)
    val opened = draft != null
    var d by remember(start, opened) { mutableStateOf(start ?: OwnEndpointDraft()) }
    var msg by remember(start, opened) { mutableStateOf<Pair<Boolean, String>?>(null) }
    var found by remember(start, opened) { mutableStateOf<List<String>>(emptyList()) }
    var checking by remember(start, opened) { mutableStateOf(false) }
    val existing = m.endpoints.firstOrNull { it.id == d.id && d.id.isNotBlank() }
    val saving = "ep" in m.busy
    fun edit(f: (OwnEndpointDraft) -> OwnEndpointDraft) { d = f(d); msg = null }
    Sheet(draft != null, onDismiss, title = existing?.name ?: t.addEndpoint, heightFraction = .92f) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Field(d.name, { v -> edit { it.copy(name = v) } }, t.name)
            Field(d.baseUrl, { v -> edit { it.copy(baseUrl = v) } }, t.address, mono = true, keyboard = KeyboardType.Uri)
            Field(d.model, { v -> edit { it.copy(model = v) } }, t.modelId, mono = true)
            if (found.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                found.take(30).forEach { id -> Chip(id, d.model == id, { edit { it.copy(model = id) } }) }
            }
            Field(d.apiKey, { v -> edit { it.copy(apiKey = v) } }, if (existing?.hasKey == true) t.keepKey else t.keyOptional, password = true, mono = true)
            Field(d.contextLength, { v -> edit { it.copy(contextLength = v.filter(Char::isDigit).take(9)) } }, t.contextOptional, mono = true, keyboard = KeyboardType.Number)
            Txt(t.apiFormat.uppercase(), Theme.type.caps, c.secondary, maxLines = 1)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                API_MODES.forEach { mode -> Chip(t.apiMode(mode), d.apiMode == mode, { edit { it.copy(apiMode = mode) } }) }
            }
            msg?.let { (ok, text) -> Txt(text, Theme.type.sub, if (ok) c.success else c.errorText, maxLines = 6) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(if (checking) t.checking else t.check, {
                    checking = true
                    scope.launch {
                        m.checkEndpoint(d).onSuccess { r ->
                            found = r.models
                            msg = r.ok to (r.message.ifBlank { if (r.ok) t.works(r.models.size) else t.cantReach })
                            if (r.ok) { fb.haptic(Haptic.Confirm); if (d.model.isBlank() && r.models.isNotEmpty()) d = d.copy(model = r.models.first()) }
                            else fb.haptic(Haptic.Reject)
                        }.onFailure { x -> msg = false to m.errorOf(x); fb.haptic(Haptic.Reject) }
                        checking = false
                    }
                }, Modifier.weight(1f), kind = ButtonKind.Outline, icon = Icons.playArrow, enabled = !checking && d.baseUrl.isNotBlank())
                PillButton(if (saving) t.saving else s.save, { m.saveEndpoint(d) { r -> if (r == null) onDismiss() else msg = false to r } },
                    Modifier.weight(1f), icon = Icons.check, enabled = !saving, haptic = Haptic.Confirm)
            }
            if (existing != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!existing.current) PillButton(t.useIt, { m.useEndpoint(existing); onDismiss() }, Modifier.weight(1f), kind = ButtonKind.Outline, icon = Icons.checkCircle)
                PillButton(s.delete, { onDismiss(); m.deleteEndpoint(existing) }, Modifier.weight(1f), kind = ButtonKind.Danger, icon = Icons.delete, haptic = Haptic.Reject)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

enum class KeyKind { Models, Tools, Skills, Messaging, Other }

fun kindOf(v: EnvKey): KeyKind = when (v.category) {
    "provider" -> KeyKind.Models
    "tool" -> KeyKind.Tools
    "skill" -> KeyKind.Skills
    "messaging" -> KeyKind.Messaging
    else -> KeyKind.Other
}

/** Every key and setting in the bot's `.env`, by kind, with a search. */
@Composable
fun KeysSheet(m: ModelsModel, open: Boolean, onOpenKey: (String) -> Unit, onDismiss: () -> Unit) {
    val t = S.modelsPage
    val c = Theme.colors
    var kind by remember { mutableStateOf(KeyKind.Models) }
    var query by remember(open) { mutableStateOf("") }
    var onlySet by remember { mutableStateOf(false) }
    Sheet(open, onDismiss, title = t.keys, heightFraction = .92f) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(t.setOnly, onlySet, { onlySet = !onlySet }, icon = Icons.check)
            KeyKind.entries.forEach { k -> Chip(t.keyKind(k.ordinal), kind == k, { kind = k }) }
        }
        Field(query, { query = it }, t.searchKeys, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), mono = true)
        val q = query.trim().lowercase()
        val rows = m.env.filter { v ->
            "env:${v.key}" !in m.hidden && (q.isNotEmpty() || kindOf(v) == kind) && (!onlySet || v.isSet) &&
                (q.isEmpty() || v.key.lowercase().contains(q) || v.description.lowercase().contains(q))
        }.sortedWith(compareByDescending<EnvKey> { it.isSet }.thenBy { it.advanced }.thenBy { it.key })
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (rows.isEmpty()) item(key = "none") { PageMessage(t.noMatches) }
            items(rows, key = { "k:" + it.key }) { v ->
                InfoRow(v.label, v.description.ifBlank { null }, { onOpenKey(v.key) }, mono = true,
                    lead = { LiveDot(if (v.isSet) c.success else c.hairline) })
            }
        }
    }
}

/** One key: what it's for, where to get it, set or replace it, peek at it, remove it. */
@Composable
fun KeySheet(m: ModelsModel, key: String?, onDismiss: () -> Unit) {
    val t = S.modelsPage
    val s = S.current
    val c = Theme.colors
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val shown = rememberLast(key)
    val v = m.env.firstOrNull { it.key == shown }
    var value by remember(shown, key != null) { mutableStateOf("") }
    var err by remember(shown, key != null) { mutableStateOf<String?>(null) }
    var peek by remember(shown, key != null) { mutableStateOf<String?>(null) }
    Sheet(key != null, onDismiss, title = v?.label) {
        if (v == null) return@Sheet
        val saving = m.busy.any { it == "keys:${v.key}" || it == "keys:${v.provider}" }
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (v.description.isNotBlank()) Txt(v.description, Theme.type.body, c.secondary)
            if (v.isSet) Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(ltr(peek ?: v.preview ?: "•••"), Theme.type.code, modifier = Modifier.weight(1f), maxLines = 3)
                if (v.isPassword) IconButton(Icons.visibility, t.show, { scope.launch { peek = if (peek == null) m.reveal(v) else null } })
            }
            Field(value, { value = it; err = null }, if (v.isSet) t.replaceKey else if (v.isPassword) t.pasteKey else t.value,
                password = v.isPassword, mono = true, keyboard = if (v.isPassword) KeyboardType.Password else KeyboardType.Text)
            err?.let { Txt(it, Theme.type.sub, c.errorText, maxLines = 6) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                v.url?.let { u -> PillButton(t.getKey, { runCatching { uri.openUri(u) } }, Modifier.weight(1f), kind = ButtonKind.Outline, icon = Icons.link) }
                PillButton(if (saving) t.checking else s.save, {
                    val entry = m.entries.firstOrNull { it.slug == v.provider }?.takeIf { v.category == "provider" }
                    m.saveKeys(entry, mapOf(v.key to value)) { r -> err = r; if (r == null) { value = ""; peek = null } }
                }, Modifier.weight(1f), icon = Icons.check, enabled = !saving && value.isNotBlank(), haptic = Haptic.Confirm)
            }
            if (v.isSet) PillButton(s.delete, { onDismiss(); m.removeKey(v) }, Modifier.fillMaxWidth(), kind = ButtonKind.Danger, icon = Icons.delete, haptic = Haptic.Reject)
            Spacer(Modifier.height(4.dp))
        }
    }
}
