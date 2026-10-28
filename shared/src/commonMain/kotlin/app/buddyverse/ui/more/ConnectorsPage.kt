package app.buddyverse.ui.more

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Card
import app.buddyverse.design.Chip
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Sound
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.connectorsPage
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.MorePage
import app.buddyverse.ui.PageFrame
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.more.connectors.AppRow
import app.buddyverse.ui.more.connectors.ConnectorSheet
import app.buddyverse.ui.more.connectors.ConnectorsModel
import app.buddyverse.ui.more.connectors.Flash
import app.buddyverse.ui.more.connectors.Pulse
import app.buddyverse.ui.more.connectors.Status
import app.buddyverse.ui.more.connectors.color
import app.buddyverse.ui.more.connectors.label
import app.buddyverse.ui.more.connectors.text

private val FallbackBuddy = BuddySpec("connectors", "", BuddyShape.Pebble, BuddyColors.palette[7])

/**
 * More ▸ Connectors: hermes-api.md §2.6 Connectors. The bot's buddy hosts the page (thinks while loading, works while a
 * sign-in is open, cheers when an app connects); connected apps on top, then the searchable app directory.
 * Tap a row for its sheet (accounts, on/off rule, tools); the row's one visible button connects / reconnects / stops waiting.
 */
@Composable
fun ConnectorsPage() {
    val store = LocalStore.current
    val s = S.connectorsPage
    val base = S.current
    val c = Theme.colors
    val fb = LocalFeedback.current
    val toasts = LocalToasts.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val m = remember(store) { ConnectorsModel(store, scope) }
    val bots by store.bots.collectAsState()
    val open: (String) -> Unit = { url -> runCatching { uri.openUri(url) } }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var cue by remember { mutableStateOf<BuddyCue?>(null) }

    LaunchedEffect(m) { m.load() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { m.wake() }

    val bot = bots.firstOrNull { it.profile == m.profile } ?: bots.firstOrNull { it.isDefault } ?: bots.firstOrNull()
    val spec = bot?.spec ?: FallbackBuddy
    val botName = bot?.name ?: base.brain

    val flash = m.flash
    LaunchedEffect(flash?.n) {
        when (flash) {
            is Flash.Connected -> {
                fb.sound(Sound.Bloop); fb.haptic(Haptic.Confirm)
                cue = BuddyCue(flash.n, OneShot.Done)
                toasts.show(ToastData(s.connectedToast(m.title(flash.slug)), buddy = spec, shot = OneShot.Learned))
            }
            is Flash.NotConnected -> {
                fb.sound(Sound.Bonk); fb.haptic(Haptic.Reject)
                cue = BuddyCue(flash.n, OneShot.Dizzy)
                toasts.show(ToastData(s.didntConnect(m.title(flash.slug))))
            }
            is Flash.Removed -> { cue = BuddyCue(flash.n, OneShot.Nod); toasts.show(ToastData(s.removed)) }
            is Flash.Failed -> {
                fb.sound(Sound.Bonk); fb.haptic(Haptic.Reject)
                cue = BuddyCue(flash.n, OneShot.Startle)
                toasts.show(ToastData(flash.problem.text(s, base)))
            }
            null -> Unit
        }
    }

    val connected = m.connectedSlugs()
    val mood = when {
        m.loading -> Mood.Thinking
        m.ops.isNotEmpty() -> Mood.Working
        !m.available -> Mood.Sleeping
        m.loadProblem != null -> Mood.Error
        else -> Mood.Idle
    }

    Box(Modifier.fillMaxSize()) {
        PageFrame(MorePage.Connectors.label, actions = { IconButton(Icons.refresh, base.retry, { m.load() }) }) {
            item(key = "hero") {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Buddy(spec, mood, 72.dp, cue = cue, onTap = { fb.haptic(Haptic.Press) })
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(s.heroTitle(botName), Theme.type.headline, maxLines = 2)
                            Txt(if (connected.isEmpty()) s.noneConnected else s.connectedCount(connected.size), Theme.type.sub, c.secondary, maxLines = 1)
                        }
                    }
                    if (bots.size > 1) Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        bots.forEach { b -> Chip(b.name, b.profile == bot?.profile, { if (b.profile != bot?.profile) m.load(b.profile) }) }
                    }
                }
            }
            when {
                !m.available -> item(key = "off") { Note(s.unavailable) }
                m.loadProblem != null && m.catalog.isEmpty() && connected.isEmpty() -> item(key = "err") {
                    Note(m.loadProblem!!.text(s, base)) { PillButton(base.retry, { m.load() }, kind = ButtonKind.Quiet, height = 48.dp, icon = Icons.refresh) }
                }
                else -> {
                    if (connected.isNotEmpty()) {
                        item(key = "h-connected") { SectionLabel(s.connectedSection) }
                        items(connected, key = { "c-$it" }) { slug ->
                            val st = m.status(slug)
                            val who = m.accountsOf(slug).joinToString(", ") { it.name }
                            AppRow(
                                slug, m.title(slug), listOfNotNull(st.label(s), who.ifBlank { null }).joinToString(" · "),
                                if (st == Status.Connected) null else st.color(c), st, { sheet = slug },
                                Modifier.animateItem(placementSpec = Motion.smooth()), onLongPress = { sheet = slug },
                            ) {
                                when (st) {
                                    Status.Connecting -> IconButton(Icons.close, s.stopWaiting, { m.stopWaiting(slug) }, tint = c.secondary)
                                    Status.Expired, Status.Broken -> IconButton(Icons.refresh, s.reconnect, {
                                        m.connect(slug, open, reconnect = true, connectionId = m.accountsOf(slug).firstOrNull()?.connectionId)
                                    }, tint = c.needsYouText)
                                    else -> Icon(Icons.chevronRight, c.tertiary, 24.dp, mirror = true)
                                }
                            }
                        }
                    }
                    item(key = "h-apps") { SectionLabel(s.appsSection) }
                    item(key = "search") { Field(query, { query = it }, s.searchApps, Modifier.padding(horizontal = 16.dp)) }
                    val categories = m.catalog.groupingBy { it.category }.eachCount().entries.filter { it.key.isNotBlank() }.sortedByDescending { it.value }.map { it.key }
                    if (categories.size > 1) item(key = "cats") {
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip(base.all, category == null, { category = null })
                            categories.forEach { k -> Chip(k, category == k, { category = if (category == k) null else k }) }
                        }
                    }
                    val q = query.trim()
                    val shown = m.catalog.filter { a ->
                        a.slug !in connected && (category == null || a.category == category) &&
                            (q.isEmpty() || a.name.contains(q, true) || a.description.contains(q, true) || a.slug.contains(q, true))
                    }
                    if (shown.isEmpty() && !m.loading) item(key = "none") { Note(s.noMatches) }
                    if (shown.isEmpty() && m.loading) item(key = "wait") { Row(Modifier.padding(20.dp)) { Pulse(c.working) } }
                    items(shown, key = { "a-${it.slug}" }) { app ->
                        val busy = m.busy[app.slug] == true
                        AppRow(app.slug, app.name, app.description, null, Status.Available, { sheet = app.slug }, Modifier.animateItem(placementSpec = Motion.smooth()), onLongPress = { sheet = app.slug }) {
                            if (busy) Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) { Pulse(c.working) }
                            else IconButton(Icons.add, s.connect, { m.connect(app.slug, open) }, tint = c.accent)
                        }
                    }
                    item(key = "end") { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
        ConnectorSheet(m, sheet, botName, open) { sheet = null }
    }
}

/** A quiet centred line (empty, off, error), with an optional action under it. */
@Composable
private fun Note(text: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Txt(text, Theme.type.callout, Theme.colors.secondary, maxLines = 3)
        action?.invoke()
    }
}
