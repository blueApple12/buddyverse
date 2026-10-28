package app.buddyverse.ui.bot

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.Bot
import app.buddyverse.core.ChatTarget
import app.buddyverse.core.RestError
import app.buddyverse.core.api.BotCap
import app.buddyverse.core.api.BotDetails
import app.buddyverse.core.api.ModelChoice
import app.buddyverse.core.api.configureBot
import app.buddyverse.core.api.deleteBot
import app.buddyverse.core.api.describeBot
import app.buddyverse.core.api.duplicateBot
import app.buddyverse.core.api.initBot
import app.buddyverse.core.api.modelChoices
import app.buddyverse.core.api.renameBot
import app.buddyverse.core.api.setBotModel
import app.buddyverse.core.nowMillis
import app.buddyverse.design.BackHandler
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Card
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.bot
import app.buddyverse.ui.LocalNav
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.Nav
import app.buddyverse.ui.Screen
import app.buddyverse.ui.Tab
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.ltr
import app.buddyverse.ui.modelLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Page { Main, Menu, Persona, Model, Tools, Handle, Delete }

/**
 * Bot profile sheet, read view (design §3.8): live buddy, name, role, model, tools summary and `Message`.
 * Its pages edit the persona, model and toolsets (skills and MCP servers are global: More ▸ Skills / MCP); the ⋯ menu has Edit look, Duplicate, Routines, Change handle and Delete.
 * Shown while [profile] != null; place it last in a full-size Box. Called from Bots home, the chat header and the room crew strip: keep this signature.
 */
@Composable
fun BotProfileSheet(profile: String?, onDismiss: () -> Unit) {
    val held = remember { arrayOfNulls<String>(1) }
    if (profile != null) held[0] = profile
    val shown = held[0]
    var studio by remember { mutableStateOf(false) }
    LaunchedEffect(profile) { if (profile == null) studio = false }
    val generation = openCount(profile != null)
    Sheet(profile != null, onDismiss, heightFraction = .92f) {
        if (shown != null) key(generation, shown) { ProfileBody(shown, onDismiss) { studio = true } }
    }
    AvatarStudio(studio && profile != null, shown, onDismiss = { studio = false })
}

@Composable
private fun ColumnScope.ProfileBody(profile: String, onDismiss: () -> Unit, onEditLook: () -> Unit) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val toasts = LocalToasts.current
    val fb = LocalFeedback.current
    val s = S.current
    val b = S.bot
    val c = Theme.colors

    val bots by store.bots.collectAsState()
    val heldBot = remember { arrayOfNulls<Bot>(1) }
    bots.firstOrNull { it.profile == profile }?.let { heldBot[0] = it.edited() }
    val bot = heldBot[0]
    val title = bot?.name ?: profile
    val spec = bot?.spec ?: BuddySpec(profile, profile, BuddyShape.parse(null, profile), BuddyColors.forName(profile))

    var page by remember { mutableStateOf(Page.Main) }
    var details by remember { mutableStateOf<BotDetails?>(null) }
    var failed by remember { mutableStateOf(false) }
    val saves = remember { mutableMapOf<String, Job>() }
    LaunchedEffect(profile) {
        try { details = store.api.describeBot(profile) } catch (e: CancellationException) { throw e } catch (e: Exception) { failed = true }
    }
    BackHandler(page != Page.Main) { page = Page.Main }

    fun toast(text: String) = toasts.show(ToastData(text))
    val errorText = s.error

    /** Toolsets save with replace semantics; quick toggles coalesce into one write (hermes-api.md §2.4). */
    fun toggle(cap: BotCap, on: Boolean) {
        val d = details ?: return
        // A composite preset pin: any write here would replace it with a partial list. Point to More > Tools instead.
        if (d.toolsPreset) { fb.haptic(Haptic.Reject); toast(b.toolsPreset); return }
        // "No toolsets" can't be said on the wire (an empty pin = all on), so the last one stays on.
        if (!on && d.toolsets.count { it.enabled } <= 1) { fb.haptic(Haptic.Reject); return }
        val next = d.copy(toolsets = d.toolsets.map { if (it.name == cap.name) it.copy(enabled = on) else it })
        details = next
        // All on = no pin (Desktop sends [] then).
        val field = "enabled_toolsets"
        val value = next.toolsets.filter { it.enabled }.map { it.name }.let { if (it.size == next.toolsets.size) emptyList() else it }
        saves[field]?.cancel()
        saves[field] = store.scope.launch {
            delay(500)
            val ok = try { store.api.configureBot(profile, mapOf(field to value)) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            // A refused write: say so and show what the server really has.
            if (!ok) { toast(errorText); runCatching { store.api.describeBot(profile) }.onSuccess { details = it } }
        }
    }

    fun duplicate() {
        val from = bot ?: return
        val handle = freeHandle(profile, bots.map { it.profile }.toSet())
        val copyTitle = b.copyOf(from.name)
        val made = b.made(copyTitle)
        page = Page.Main
        fb.haptic(Haptic.Confirm)
        store.scope.launch {
            try {
                store.api.duplicateBot(profile, handle, from.description.ifBlank { null })
                runCatching { store.api.initBot(handle, look(copyTitle, from.spec.shape, from.spec.color) + ("created" to nowMillis())) }
                runCatching { store.refreshRoster() }
                toasts.show(ToastData(made, buddy = from.spec.copy(id = handle, name = copyTitle), shot = OneShot.Arrive))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: errorText) }
        }
    }

    // Optimistic: the row vanishes and the buddy poofs in the toast now; a failed delete says so (Home's intent, HomeApi.kt).
    fun delete() {
        fb.haptic(Haptic.Reject)
        onDismiss()
        nav.closeChatsOf(profile)
        toasts.show(ToastData(b.deleted(title), buddy = spec, shot = OneShot.Leave))
        store.deleteBot(profile) { toast(it ?: errorText) }
    }

    val pageTitle = when (page) {
        Page.Main -> ""
        Page.Menu -> title
        Page.Persona -> b.persona
        Page.Model -> b.model
        Page.Tools -> s.tools
        Page.Handle -> b.changeHandle
        Page.Delete -> ""
    }
    // Header: ×/back · title · ⋯/× (48 each).
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (page == Page.Main) IconButton(Icons.close, s.close, onDismiss)
        else IconButton(Icons.arrowBack, s.back, { page = Page.Main }, mirror = true)
        Txt(pageTitle, Theme.type.headline, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1)
        if (page == Page.Main) IconButton(Icons.moreHoriz, s.more, { page = Page.Menu })
        else IconButton(Icons.close, s.close, onDismiss)
    }

    Crossfade(page, Modifier.weight(1f).fillMaxWidth(), animationSpec = Motion.fade()) { p ->
        when (p) {
            Page.Main -> MainPage(profile, bot, spec, title, details, failed, { page = it }) {
                onDismiss(); nav.push(Screen.Chat(ChatTarget(profile)))
            }
            Page.Menu -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                MenuRow(Icons.palette, b.editLook) { page = Page.Main; onEditLook() }
                MenuRow(Icons.contentCopy, b.duplicate) { duplicate() }
                MenuRow(Icons.schedule, s.routines) { onDismiss(); nav.closeAll(); nav.select(Tab.Routines) }
                if (bot?.isDefault != true) {
                    MenuRow(Icons.edit, b.changeHandle) { page = Page.Handle }
                    MenuRow(Icons.delete, s.delete, c.errorText) { page = Page.Delete }
                }
            }
            Page.Persona -> PersonaPage(details?.soul.orEmpty()) { text ->
                page = Page.Main
                val before = details
                if (text == before?.soul) return@PersonaPage
                details = before?.copy(soul = text)
                val savedText = b.saved
                store.scope.launch {
                    val ok = try { store.api.configureBot(profile, mapOf("soul" to text)) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                    if (ok) toast(savedText) else { details = before; toast(errorText) }
                }
            }
            Page.Model -> ModelPage(profile, details) { details = it; page = Page.Main }
            Page.Tools -> CapsPage(details?.toolsets.orEmpty(), ::toggle)
            Page.Handle -> HandlePage(profile, bots.map { it.profile }.toSet()) { newName ->
                onDismiss()
                nav.closeChatsOf(profile)
                toast(b.renamed(ltr("@$newName")))
            }
            Page.Delete -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(12.dp))
                Buddy(spec, Mood.Error, 112.dp)
                Spacer(Modifier.height(16.dp))
                Txt(b.deleteAsk(title), Theme.type.title2.copy(textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Txt(b.deleteBody, Theme.type.callout.copy(textAlign = TextAlign.Center), c.secondary, Modifier.fillMaxWidth())
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(s.cancel, { page = Page.Main }, Modifier.weight(1f), kind = ButtonKind.Quiet)
                    PillButton(s.delete, ::delete, Modifier.weight(1f), kind = ButtonKind.Danger, haptic = Haptic.Reject)
                }
            }
        }
    }
}

@Composable
private fun MainPage(
    profile: String, bot: Bot?, spec: BuddySpec, title: String, details: BotDetails?, failed: Boolean,
    open: (Page) -> Unit, message: () -> Unit,
) {
    val s = S.current
    val b = S.bot
    val c = Theme.colors
    val cues = remember { Cues() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Buddy(spec, bot?.status?.mood() ?: Mood.Idle, 120.dp, cue = cues.cue, onTap = cues::next)
        Spacer(Modifier.height(8.dp))
        Txt(title, Theme.type.title.copy(textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), maxLines = 2)
        Txt(ltr("@$profile"), Theme.type.caption, c.secondary, maxLines = 1)
        bot?.description?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Txt(it, Theme.type.callout.copy(textAlign = TextAlign.Center), c.secondary, Modifier.fillMaxWidth().padding(horizontal = 24.dp), maxLines = 4)
        }
        Spacer(Modifier.height(20.dp))
        PillButton(b.messageBot, message, Modifier.fillMaxWidth().padding(horizontal = 20.dp), icon = Icons.chat)
        Spacer(Modifier.height(12.dp))
        if (failed && details == null) {
            Txt(s.unsupported, Theme.type.sub.copy(textAlign = TextAlign.Center), c.secondary, Modifier.fillMaxWidth().padding(20.dp))
            return@Column
        }
        val d = details
        fun count(list: List<BotCap>?) = list?.let { l -> b.onOf(l.count { it.enabled }, l.size) }.orEmpty()
        InfoRow(Icons.psychology, b.persona, null, d != null) { open(Page.Persona) }
        InfoRow(Icons.memory, b.model, if (d == null) "" else d.model?.let { ltr(modelLabel(it) ?: it) } ?: b.defaultModel, d != null) { open(Page.Model) }
        InfoRow(Icons.build, s.tools, count(d?.toolsets), d != null) { open(Page.Tools) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String?, ready: Boolean, mono: Boolean = false, onClick: () -> Unit) {
    val c = Theme.colors
    PressRow({ if (ready) onClick() }, label = label) {
        Icon(icon, c.secondary, 24.dp)
        Spacer(Modifier.width(16.dp))
        Txt(label, Theme.type.body, modifier = Modifier.weight(1f), maxLines = 1)
        if (!value.isNullOrEmpty()) {
            Txt(value, if (mono) Theme.type.code else Theme.type.sub, c.secondary, Modifier.padding(start = 8.dp).weight(1f, fill = false), maxLines = 1)
        }
        Icon(Icons.chevronRight, c.tertiary, 22.dp, mirror = true)
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, tint: Color = Theme.colors.text, onClick: () -> Unit) =
    PressRow(onClick, label = label) {
        Icon(icon, tint, 24.dp)
        Spacer(Modifier.width(16.dp))
        Txt(label, Theme.type.body, tint, Modifier.weight(1f), maxLines = 1)
    }

@Composable
private fun PersonaPage(initial: String, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Field(text, { text = it }, S.bot.persona, Modifier.weight(1f), singleLine = false, minHeight = 160.dp)
        Spacer(Modifier.height(12.dp))
        PillButton(S.current.save, { onSave(text) }, Modifier.fillMaxWidth(), haptic = Haptic.Confirm)
    }
}

@Composable
private fun ModelPage(profile: String, details: BotDetails?, onPicked: (BotDetails?) -> Unit) {
    val store = LocalStore.current
    val toasts = LocalToasts.current
    val fb = LocalFeedback.current
    val s = S.current
    val b = S.bot
    val c = Theme.colors
    var choices by remember { mutableStateOf<List<ModelChoice>?>(null) }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(details?.let { it.provider to it.model }) }
    var ask by remember { mutableStateOf<Pair<ModelChoice, String>?>(null) }
    LaunchedEffect(profile) { choices = runCatching { store.api.modelChoices(profile) }.getOrElse { emptyList() } }

    fun pick(m: ModelChoice, confirmed: Boolean = false) {
        val before = picked
        picked = m.provider to m.model
        ask = null
        val savedText = b.saved
        val errorText = s.error
        store.scope.launch {
            try {
                val warning = store.api.setBotModel(profile, m.model, m.provider, confirmed)
                if (warning != null) { picked = before; ask = m to warning; fb.haptic(Haptic.Confirm) }
                else { toasts.show(ToastData(savedText)); onPicked(details?.copy(model = m.model, provider = m.provider)) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { picked = before; toasts.show(ToastData(e.message ?: errorText)) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Field(query, { query = it }, b.searchModels, Modifier.padding(horizontal = 16.dp))
        ask?.let { (m, warning) ->
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Txt(m.model, Theme.type.code, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Txt(warning, Theme.type.sub, c.needsYouText)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(s.cancel, { ask = null }, Modifier.weight(1f), kind = ButtonKind.Quiet, height = 48.dp)
                    PillButton(b.confirm, { pick(m, confirmed = true) }, Modifier.weight(1f), height = 48.dp, haptic = Haptic.Confirm)
                }
            }
        }
        val all = choices
        if (all == null) { Thinking(); return@Column }
        val q = query.trim()
        val shown = if (q.isEmpty()) all else all.filter { it.model.contains(q, true) || it.providerName.contains(q, true) }
        if (shown.isEmpty()) { Empty(); return@Column }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(shown, key = { "${it.provider}/${it.model}" }) { m ->
                val on = picked == (m.provider to m.model)
                PressRow({ if (!on) pick(m) }, label = m.model) {
                    Column(Modifier.weight(1f)) {
                        Txt(m.model, Theme.type.code, maxLines = 1)
                        Txt(m.providerName, Theme.type.caption, c.secondary, maxLines = 1)
                    }
                    if (on) Icon(Icons.check, c.accent, 24.dp)
                }
            }
        }
    }
}

@Composable
private fun CapsPage(items: List<BotCap>, onToggle: (BotCap, Boolean) -> Unit) {
    val c = Theme.colors
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        if (items.size > 8) Field(query, { query = it }, S.bot.filter, Modifier.padding(horizontal = 16.dp))
        val q = query.trim()
        val shown = if (q.isEmpty()) items else items.filter { it.label.contains(q, true) || it.name.contains(q, true) || it.note?.contains(q, true) == true }
        if (shown.isEmpty()) { Empty(); return@Column }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(shown, key = { it.name }) { cap ->
                PressRow({ onToggle(cap, !cap.enabled) }, label = cap.label) {
                    Column(Modifier.weight(1f)) {
                        Txt(cap.label, Theme.type.callout, maxLines = 1)
                        cap.note?.let { Txt(it, Theme.type.caption, c.secondary, maxLines = 2) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Toggle(cap.enabled, { onToggle(cap, it) }, cap.label)
                }
            }
        }
    }
}

@Composable
private fun HandlePage(profile: String, taken: Set<String>, onDone: (String) -> Unit) {
    val store = LocalStore.current
    val fb = LocalFeedback.current
    val s = S.current
    val b = S.bot
    val c = Theme.colors
    var handle by remember { mutableStateOf(profile) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val clash = handle != profile && handle in taken
    val valid = HANDLE.matches(handle) && handle !in RESERVED && !clash
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Field(handle, { handle = it.lowercase().trim(); error = null }, profile, mono = true)
        Txt(error ?: if (clash) b.handleTaken else b.handleRule, Theme.type.caption, if (error != null || !valid) c.errorText else c.secondary,
            Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        Spacer(Modifier.weight(1f))
        PillButton(s.save, {
            if (busy) return@PillButton
            if (!valid || handle == profile) { fb.haptic(Haptic.Reject); return@PillButton }
            busy = true
            val next = handle
            store.scope.launch {
                try {
                    val name = store.api.renameBot(profile, next)
                    runCatching { store.refreshRoster() }
                    onDone(name)
                } catch (e: CancellationException) { throw e } catch (e: RestError) { busy = false; error = e.detail } catch (e: Exception) { busy = false; error = e.message ?: s.error }
            }
        }, Modifier.fillMaxWidth(), haptic = Haptic.Confirm)
    }
}

@Composable
private fun Thinking() = Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
    Buddy(BuddySpec("bot-sheet-wait", "", BuddyShape.Pebble, Theme.colors.tertiary), Mood.Thinking, 64.dp, interactive = false)
}

@Composable
private fun Empty() = Txt(S.bot.nothingHere, Theme.type.sub.copy(textAlign = TextAlign.Center), Theme.colors.secondary, Modifier.fillMaxWidth().padding(32.dp))

/** Slide away every pushed screen. */
private fun Nav.closeAll() = stack.forEach { it.leaving = true }

/** Slide away chats with a bot that no longer exists under that name. */
private fun Nav.closeChatsOf(profile: String) = stack.filter { (it.screen as? Screen.Chat)?.target?.profile == profile }.forEach { it.leaving = true }
