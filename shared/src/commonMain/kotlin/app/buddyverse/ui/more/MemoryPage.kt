package app.buddyverse.ui.more

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.ChatTarget
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Card
import app.buddyverse.design.Field
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.PillButton
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.i18n.MemoryPageStrings
import app.buddyverse.i18n.S
import app.buddyverse.i18n.memoryPage
import app.buddyverse.ui.LocalNav
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.MorePage
import app.buddyverse.ui.PageFrame
import app.buddyverse.ui.Screen
import app.buddyverse.ui.more.memory.ABOUT
import app.buddyverse.ui.more.memory.BotPicker
import app.buddyverse.ui.more.memory.Builtin
import app.buddyverse.ui.more.memory.BuiltinCard
import app.buddyverse.ui.more.memory.Confirm
import app.buddyverse.ui.more.memory.ConfirmSheet
import app.buddyverse.ui.more.memory.Delayed
import app.buddyverse.ui.more.memory.EmptyBuddy
import app.buddyverse.ui.more.memory.EntrySheet
import app.buddyverse.ui.more.memory.ErrorRow
import app.buddyverse.ui.more.memory.FactRow
import app.buddyverse.ui.more.memory.LearnedRow
import app.buddyverse.ui.more.memory.LoadingNote
import app.buddyverse.ui.more.memory.MemoryModel
import app.buddyverse.ui.more.memory.MemoryTab
import app.buddyverse.ui.more.memory.MemoryTabs
import app.buddyverse.ui.more.memory.NOTES
import app.buddyverse.ui.more.memory.Open
import app.buddyverse.ui.more.memory.Provider
import app.buddyverse.ui.more.memory.ProviderRow
import app.buddyverse.ui.more.memory.ProviderSheet
import app.buddyverse.ui.more.memory.TeachSheet

/**
 * More ▸ Memory (hermes-api.md §2.5 Memory + Memory providers, §2.4 Learning), per bot: a buddy picker on top, then four tabs.
 * About you (USER.md) and Notes (MEMORY.md): on/off, a budget meter, the entries (tap = read, long press = edit, Forget with undo),
 * Forget all. Learned: skills the bot made itself (read, edit SKILL.md, archive). Providers: ask-before-saving, built-in only or an
 * external memory plugin (status, what it needs, sign-in, fields, install, use). + = Teach: asks the bot in its chat to remember.
 */
@Composable
fun MemoryPage() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val model = remember(store) { MemoryModel(store, scope) }
    val t = S.memoryPage
    val s = S.current
    val bots by store.bots.collectAsState()
    val crew = bots.filter { !it.hidden || it.profile == model.profile }.map { it.spec }
        .ifEmpty { listOf(model.profile.ifEmpty { "default" }.let { p -> BuddySpec(p, if (p == "default") s.brain else p, BuddyShape.parse(null, p), BuddyColors.forName(p)) }) }
    model.fb = LocalFeedback.current
    model.toasts = LocalToasts.current
    model.t = t
    model.s = s
    model.spec = crew.firstOrNull { it.id == model.profile } ?: crew.first()

    LaunchedEffect(model) {
        if (model.profile.isEmpty()) model.select(bots.firstOrNull { it.isDefault }?.profile ?: bots.firstOrNull()?.profile ?: "default")
        // A turn's background self-review can write memory; a skill folder change can add a learned skill.
        store.gateway.events.collect { if (it.type == "review.summary" || it.type == "skills.changed") model.onChanged() }
    }

    var open by remember { mutableStateOf<Open?>(null) }
    var provider by remember { mutableStateOf<Provider?>(null) }
    var teaching by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    Box(Modifier.fillMaxSize()) {
        PageFrame(MorePage.Memory.label, actions = {
            IconButton(Icons.refresh, t.refresh, { model.play(OneShot.LookAround); model.refresh() })
            IconButton(Icons.add, t.teach, { teaching = true })
        }) {
            item(key = "crew") { BotPicker(crew, model.profile, model.mood, model.cue, model::select) }
            item(key = "tabs") {
                val counts = mapOf(MemoryTab.About to model.facts(ABOUT).size, MemoryTab.Notes to model.facts(NOTES).size, MemoryTab.Learned to model.learned.size)
                MemoryTabs(model.tab, counts) { x -> if (x != model.tab) { model.tab = x; model.query = ""; model.play(OneShot.LookAround) } }
            }
            when (model.tab) {
                MemoryTab.About -> factsTab(model, ABOUT, t, { open = it }, { teaching = true }, { confirm = it })
                MemoryTab.Notes -> factsTab(model, NOTES, t, { open = it }, { teaching = true }, { confirm = it })
                MemoryTab.Learned -> learnedTab(model, t) { open = it }
                MemoryTab.Providers -> providersTab(model, t) { provider = it }
            }
        }
        EntrySheet(model, open, { open = null }, { open = it }, { o -> open = null; model.forget(o.id, o.skill) })
        TeachSheet(teaching, model.spec, model.spec?.name ?: s.brain, { teaching = false }) { text ->
            teaching = false
            model.teach(text)
            nav.push(Screen.Chat(ChatTarget(model.profile)))
        }
        ProviderSheet(model, provider) { provider = null }
        ConfirmSheet(confirm) { confirm = null }
    }
}

private val searchPad = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)

private fun LazyListScope.factsTab(
    model: MemoryModel, source: String, t: MemoryPageStrings, onOpen: (Open) -> Unit, onTeach: () -> Unit, onConfirm: (Confirm) -> Unit,
) {
    val about = source == ABOUT
    item(key = "card:$source") {
        val b = model.builtin
        BuiltinCard(
            if (about) t.aboutYouOn else t.notesOn, if (about) b?.userOn else b?.memoryOn,
            { on -> model.setSwitch(if (about) Builtin.USER_ON else Builtin.MEMORY_ON, on) }, model.used(source), model.limit(source),
        )
    }
    model.loadError?.let { e -> item(key = "error") { ErrorRow(e, model::refresh) } }
    if (!model.loaded) { item(key = "loading") { Delayed { LoadingNote() } }; return }
    val all = model.facts(source)
    if (all.size > 5) item(key = "search") { Field(model.query, { model.query = it }, t.search, searchPad) }
    val q = model.query.trim()
    val shown = if (q.isEmpty()) all else all.filter { it.text.contains(q, ignoreCase = true) }
    if (shown.isEmpty()) {
        if (model.loadError == null) item(key = "empty:$source") {
            if (q.isNotEmpty()) EmptyBuddy(model.spec, t.noMatches) else EmptyBuddy(model.spec, if (about) t.noAbout else t.noNotes, t.teach, onTeach)
        }
        return
    }
    items(shown, key = { "f:" + it.id }) { f ->
        FactRow(f, { onOpen(Open(f.id, f.text, skill = false)) }, { onOpen(Open(f.id, f.text, skill = false, editing = true)) }, Modifier.animateItem())
    }
    item(key = "forget-all:$source") {
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            PillButton(
                t.forgetAll,
                { onConfirm(Confirm(t.forgetAll, if (about) t.forgetAllAbout else t.forgetAllNotes, t.forgetAll) { model.forgetAll(source) }) },
                height = 48.dp, kind = ButtonKind.Outline, icon = Icons.delete,
            )
        }
    }
}

private fun LazyListScope.learnedTab(model: MemoryModel, t: MemoryPageStrings, onOpen: (Open) -> Unit) {
    model.loadError?.let { e -> item(key = "error") { ErrorRow(e, model::refresh) } }
    if (!model.loaded) { item(key = "loading") { Delayed { LoadingNote() } }; return }
    val all = model.learned
    if (all.size > 5) item(key = "search") { Field(model.query, { model.query = it }, t.search, searchPad) }
    val q = model.query.trim()
    val shown = if (q.isEmpty()) all else all.filter { it.label.contains(q, ignoreCase = true) || it.category.contains(q, ignoreCase = true) }
    if (shown.isEmpty()) {
        if (model.loadError == null) item(key = "empty:learned") { EmptyBuddy(model.spec, if (q.isEmpty()) t.noLearned else t.noMatches) }
        return
    }
    items(shown, key = { "k:" + it.id }) { k ->
        LearnedRow(k, { onOpen(Open(k.id, k.label, skill = true)) }, { onOpen(Open(k.id, k.label, skill = true, editing = true)) }, Modifier.animateItem())
    }
}

private fun LazyListScope.providersTab(model: MemoryModel, t: MemoryPageStrings, onOpen: (Provider) -> Unit) {
    item(key = "ask-first") {
        val b = model.builtin ?: return@item
        Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Txt(t.askFirst, Theme.type.headline, modifier = Modifier.weight(1f), maxLines = 2)
                Toggle(b.askFirst, { model.setSwitch(Builtin.ASK_FIRST, it) }, t.askFirst)
            }
        }
    }
    model.providersError?.let { e -> item(key = "p-error") { ErrorRow(e, model::refresh) } }
    item(key = "p-label") { SectionLabel(t.extra) }
    if (!model.providersLoaded) { item(key = "p-loading") { Delayed { LoadingNote() } }; return }
    item(key = "p0:builtin") { ProviderRow(null, model.active.isEmpty(), { model.use("") }, Modifier.animateItem()) }
    items(model.providers, key = { "p:" + it.name }) { p -> ProviderRow(p, model.active == p.name, { onOpen(p) }, Modifier.animateItem()) }
}
