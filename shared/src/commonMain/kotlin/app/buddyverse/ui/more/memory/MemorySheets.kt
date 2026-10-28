package app.buddyverse.ui.more.memory

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.core.nowMillis
import app.buddyverse.core.str
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Chip
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.Icons
import app.buddyverse.design.PillButton
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.memoryPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.math.min

private class Last<T>(var v: T? = null)

/** Keeps the last non-null value (a plain field, not state) so a sheet's content stays put while it slides away. */
@Composable
private fun <T : Any> lastOf(v: T?): T? {
    val last = remember { Last<T>() }
    if (v != null) last.v = v
    return v ?: last.v
}

/** A per-sheet buddy id, so it has its own rhythm next to the picker's. */
@Composable
private fun sheetBuddy(spec: BuddySpec?, tag: String): BuddySpec? = remember(spec, tag) { spec?.let { it.copy(id = it.id + "/" + tag) } }

/**
 * Read or edit one memory entry or learned skill. Reading: the buddy "says" it (mouth moves along), Edit and Forget below.
 * Editing: the buddy listens and types along with every key; Save is live once the text changed.
 */
@Composable
fun EntrySheet(model: MemoryModel, open: Open?, onDismiss: () -> Unit, onSwitch: (Open) -> Unit, onForget: (Open) -> Unit) {
    val t = S.memoryPage
    val scope = rememberCoroutineScope()
    val o = lastOf(open)
    val title = when {
        o == null -> null
        o.skill -> t.skill
        o.id.split(':').getOrNull(1) == ABOUT -> t.aboutYou
        else -> t.note
    }
    Sheet(open != null, onDismiss, title = title) {
        if (o == null) return@Sheet
        key(o) { EntryBody(model, o, scope, onDismiss, onSwitch, onForget) }
    }
}

@Composable
private fun EntryBody(model: MemoryModel, o: Open, scope: CoroutineScope, onDismiss: () -> Unit, onSwitch: (Open) -> Unit, onForget: (Open) -> Unit) {
    val t = S.memoryPage
    val s = S.current
    val c = Theme.colors
    var full by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf(o.preview) }
    var edited by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var keys by remember { mutableIntStateOf(0) }
    val openedAt = remember { nowMillis() }
    LaunchedEffect(Unit) { model.content(o.id)?.let { full = it; if (!edited) draft = it } }
    val text = full ?: o.preview
    val buddy = sheetBuddy(model.spec, "entry")

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            if (buddy != null) {
                Buddy(
                    buddy, if (o.editing) Mood.Listening else Mood.Idle, 44.dp,
                    talkAt = if (o.editing) keys.toLong() else openedAt, talkChars = if (o.editing) 1 else min(text.length, 90), interactive = false,
                )
                Spacer(Modifier.width(12.dp))
            }
            if (o.editing) {
                Field(
                    draft, { draft = it; edited = true; error = null; keys++ }, t.teachHint, Modifier.weight(1f).heightIn(max = 320.dp),
                    singleLine = false, minHeight = 140.dp,
                )
            } else {
                Txt(text, Theme.type.body, modifier = Modifier.weight(1f).heightIn(max = 380.dp).verticalScroll(rememberScrollState()))
            }
        }
        val hint = error ?: if (o.editing && edited && draft.isBlank()) t.emptyUseForget else null
        if (hint != null) { Spacer(Modifier.height(8.dp)); Txt(hint, Theme.type.sub, c.errorText, maxLines = 4) }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (o.editing) {
                PillButton(s.cancel, { onSwitch(o.copy(editing = false, preview = text)) }, Modifier.weight(1f), kind = ButtonKind.Outline)
                PillButton(
                    if (saving) t.saving else s.save,
                    {
                        saving = true
                        scope.launch {
                            val err = model.save(o.id, draft)
                            saving = false
                            if (err == null) onDismiss() else { error = err }
                        }
                    },
                    Modifier.weight(1f), enabled = !saving && draft.isNotBlank() && draft.trim() != text.trim(),
                )
            } else {
                PillButton(s.edit, { onSwitch(o.copy(editing = true, preview = text)) }, Modifier.weight(1f), kind = ButtonKind.Quiet, icon = Icons.edit)
                PillButton(
                    if (o.skill) t.archive else t.forget, { onForget(o) }, Modifier.weight(1f), kind = ButtonKind.Outline,
                    icon = if (o.skill) Icons.archive else Icons.delete, haptic = Haptic.Reject,
                )
            }
        }
    }
}

/** "Teach": what to remember goes to the bot's chat as a request; the buddy listens and types along. */
@Composable
fun TeachSheet(visible: Boolean, spec: BuddySpec?, botName: String, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    val t = S.memoryPage
    val buddy = sheetBuddy(spec, "teach")
    Sheet(visible, onDismiss, title = t.teachTitle(botName)) {
        var text by remember { mutableStateOf("") }
        var keys by remember { mutableIntStateOf(0) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                if (buddy != null) {
                    Buddy(buddy, Mood.Listening, 56.dp, talkAt = keys.toLong(), talkChars = 1, lookAtComposer = true, interactive = false)
                    Spacer(Modifier.width(12.dp))
                }
                Field(text, { text = it; keys++ }, t.teachHint, Modifier.weight(1f).heightIn(max = 240.dp), singleLine = false, minHeight = 96.dp)
            }
            Spacer(Modifier.height(16.dp))
            PillButton(t.teach, { onSend(text) }, Modifier.fillMaxWidth(), icon = Icons.send, enabled = text.isNotBlank(), haptic = Haptic.Confirm)
        }
    }
}

/** A destructive step: Cancel or the red action (design §5.2: destructive confirm = Reject haptic). */
@Composable
fun ConfirmSheet(confirm: Confirm?, onDismiss: () -> Unit) {
    val s = S.current
    val x = lastOf(confirm)
    Sheet(confirm != null, onDismiss, title = x?.title) {
        if (x == null) return@Sheet
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Txt(x.body, Theme.type.callout, Theme.colors.secondary)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(s.cancel, onDismiss, Modifier.weight(1f), kind = ButtonKind.Outline)
                PillButton(x.action, { onDismiss(); x.run() }, Modifier.weight(1f), kind = ButtonKind.Danger, haptic = Haptic.Reject)
            }
        }
    }
}

/**
 * One provider: status, what it needs (install commands in mono, always LTR), sign-in when it has OAuth, its fields, then one primary
 * action: Install and save (dependencies missing) or Save and use. Stop using when it's the active one.
 */
@Composable
fun ProviderSheet(model: MemoryModel, open: Provider?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val p = lastOf(open)
    Sheet(open != null, onDismiss, title = p?.name?.replaceFirstChar { it.uppercase() }) {
        if (p == null) return@Sheet
        key(p.name) { ProviderBody(model, p, scope, onDismiss) }
    }
}

@Composable
private fun ProviderBody(model: MemoryModel, p: Provider, scope: CoroutineScope, onDismiss: () -> Unit) {
    val t = S.memoryPage
    val s = S.current
    val c = Theme.colors
    var form by remember { mutableStateOf<ProviderForm?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val values = remember { mutableStateMapOf<String, String>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var oauth by remember { mutableStateOf<JsonObject?>(null) }
    var polling by remember { mutableStateOf(false) }
    var loads by remember { mutableIntStateOf(0) }
    val inUse = model.active == p.name

    LaunchedEffect(loads) {
        loadError = null
        try {
            val f = model.form(p.name)
            f.fields.forEach { if (!it.secret) values[it.key] = it.value }
            oauth = f.oauth
            form = f
        } catch (e: CancellationException) { throw e } catch (e: Exception) { loadError = model.describe(e) }
    }
    LaunchedEffect(polling) {
        if (!polling) return@LaunchedEffect
        repeat(90) {
            delay(2_000)
            val st = model.oauth(p.name)
            oauth = st
            when (st.str("state")) {
                "connected" -> { polling = false; model.refresh(); return@LaunchedEffect }
                "error" -> { polling = false; return@LaunchedEffect }
            }
        }
        polling = false
    }

    fun collect(f: ProviderForm): Map<String, String> =
        f.fields.mapNotNull { x -> val v = values[x.key].orEmpty(); if (x.secret && v.isBlank()) null else x.key to v }.toMap()
    fun act(block: suspend () -> String?) {
        busy = true; error = null
        scope.launch {
            val err = block()
            busy = false
            if (err == null) onDismiss() else { error = err }
        }
    }

    Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(if (inUse) t.inUse else statusLabel(p.status, t), if (inUse) c.accent else statusColor(p.status))
        }
        if (p.description.isNotBlank()) { Spacer(Modifier.height(8.dp)); Txt(p.description, Theme.type.sub, c.secondary) }

        if (p.deps.isNotEmpty() || p.env.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Txt(t.needs.uppercase(), Theme.type.caps, c.secondary)
            p.deps.forEach { d ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    Icon(if (p.depsMissing) Icons.warning else Icons.checkCircle, if (p.depsMissing) c.needsYou else c.success, 20.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Txt(d.name, Theme.type.code)
                        if (p.depsMissing && d.install.isNotBlank()) Txt(d.install, Theme.type.code, c.secondary, maxLines = 3)
                    }
                }
            }
            p.env.forEach { k ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.lock, c.tertiary, 20.dp)
                    Spacer(Modifier.width(10.dp))
                    Txt(k, Theme.type.code)
                }
            }
        }

        val auth = oauth
        if (auth != null && auth.str("auth") == "oauth") {
            val state = auth.str("state")
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(
                    when { state == "connected" -> t.connectedTo; polling || state == "pending" -> t.connecting; else -> auth.str("detail")?.let(::unbrand).orEmpty() },
                    Theme.type.sub, if (state == "connected") c.success else c.secondary, Modifier.weight(1f), maxLines = 3,
                )
                Spacer(Modifier.width(8.dp))
                if (state != "connected") PillButton(
                    t.connect,
                    { scope.launch { val e = model.oauthStart(p.name); if (e == null) { polling = true } else { error = e } } },
                    height = 48.dp, kind = ButtonKind.Quiet, icon = Icons.link, enabled = !polling,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        val f = form
        when {
            loadError != null -> ErrorRow(loadError.orEmpty()) { loads++ }
            f == null -> Delayed { LoadingNote() }
            else -> f.fields.forEach { x -> FieldEditor(x, values) }
        }
        error?.let { Spacer(Modifier.height(8.dp)); Txt(it, Theme.type.sub, c.errorText, maxLines = 6) }

        Spacer(Modifier.height(16.dp))
        val requiredOk = f != null && f.fields.all { x -> !x.required || (x.secret && x.isSet) || values[x.key].orEmpty().isNotBlank() }
        if (p.depsMissing) {
            PillButton(
                if (busy) t.installing else t.install, { if (f != null) act { model.install(p.name, collect(f)) } }, Modifier.fillMaxWidth(),
                icon = Icons.build, enabled = !busy && f != null,
            )
        } else {
            PillButton(
                if (busy) t.saving else if (inUse) s.save else t.saveAndUse, { if (f != null) act { model.saveProvider(p.name, f, collect(f)) } },
                Modifier.fillMaxWidth(), enabled = !busy && requiredOk,
            )
        }
        if (inUse) {
            Spacer(Modifier.height(12.dp))
            PillButton(t.stopUsing, { model.use(""); onDismiss() }, Modifier.fillMaxWidth(), kind = ButtonKind.Outline)
        }
    }
}

private fun truthy(v: String?): Boolean = v?.trim()?.lowercase().let { it == "true" || it == "1" || it == "yes" || it == "on" }

/** One config field: label (+ required), then a switch, choice chips, or a text field (secrets masked, never prefilled). */
@Composable
private fun FieldEditor(f: ProviderField, values: MutableMap<String, String>) {
    val t = S.memoryPage
    val c = Theme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (f.toggle) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(f.label, Theme.type.callout, modifier = Modifier.weight(1f), maxLines = 2)
                Toggle(truthy(values[f.key]), { values[f.key] = it.toString() }, f.label)
            }
            return@Column
        }
        Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(f.label, Theme.type.caption, c.secondary, maxLines = 1)
            if (f.required) Txt("  " + t.required, Theme.type.caption, c.needsYouText, maxLines = 1)
        }
        if (f.options.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                f.options.forEach { (v, label) -> Chip(label, values[f.key] == v, { values[f.key] = v }) }
            }
        } else {
            Field(
                values[f.key].orEmpty(), { values[f.key] = it },
                if (f.secret && f.isSet) t.secretSet else f.description.ifBlank { f.placeholder.ifBlank { f.label } },
                password = f.secret, mono = f.secret || f.kind == "json", singleLine = f.kind != "json",
                keyboard = if (f.number) KeyboardType.Number else KeyboardType.Text,
            )
        }
    }
}
