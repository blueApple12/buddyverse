package app.buddyverse.ui.more

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import app.buddyverse.design.Radius
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Sound
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.messagingPage
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.MorePage
import app.buddyverse.ui.PageFrame
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.more.messaging.AskingRow
import app.buddyverse.ui.more.messaging.Flash
import app.buddyverse.ui.more.messaging.MessagingModel
import app.buddyverse.ui.more.messaging.Note
import app.buddyverse.ui.more.messaging.PlatformRow
import app.buddyverse.ui.more.messaging.PlatformSheet
import app.buddyverse.ui.more.messaging.Pulse
import app.buddyverse.ui.more.messaging.RestartSheet
import app.buddyverse.ui.more.messaging.Setup
import app.buddyverse.ui.more.messaging.Tone
import app.buddyverse.ui.more.messaging.plain
import app.buddyverse.ui.more.messaging.stateLabel
import app.buddyverse.ui.more.messaging.text
import app.buddyverse.ui.more.messaging.tone

private val FallbackBuddy = BuddySpec("messaging", "", BuddyShape.Cloud, BuddyColors.palette[6])

/**
 * More ▸ Messaging: hermes-api.md §2.6 Messaging platforms. The bot's buddy hosts the page (thinks while loading, works while
 * a QR setup or a restart runs, needs you while someone asks to chat, cheers when an app connects). People asking to chat on
 * top, then the connected apps with their on/off switch, then every other app. Tap a row for its sheet (settings, test, QR setup).
 */
@Composable
fun MessagingPage() {
    val store = LocalStore.current
    val s = S.messagingPage
    val base = S.current
    val c = Theme.colors
    val fb = LocalFeedback.current
    val toasts = LocalToasts.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val m = remember(store) { MessagingModel(store, scope) }
    val bots by store.bots.collectAsState()
    val open: (String) -> Unit = { url -> runCatching { uri.openUri(url) } }
    var query by remember { mutableStateOf("") }
    var sheet by remember { mutableStateOf<String?>(null) }
    var confirmRestart by remember { mutableStateOf(false) }
    var cue by remember { mutableStateOf<BuddyCue?>(null) }

    LaunchedEffect(m) { m.run(bots.firstOrNull { it.isDefault }?.profile ?: "default") }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (!m.loading) m.load() }

    val bot = bots.firstOrNull { it.profile == m.profile } ?: bots.firstOrNull { it.isDefault } ?: bots.firstOrNull()
    val spec = bot?.spec ?: FallbackBuddy
    val botName = bot?.name ?: base.brain

    val flash = m.flash
    LaunchedEffect(flash?.n) {
        when (flash) {
            is Flash.Connected -> {
                fb.sound(Sound.Bloop); fb.haptic(Haptic.Confirm)
                cue = BuddyCue(flash.n, OneShot.Done)
                val name = m.platform(flash.platform)?.name ?: flash.platform
                toasts.show(ToastData(s.connectedToast(plain(name, botName)), buddy = spec, shot = OneShot.Learned))
            }
            is Flash.Approved -> {
                fb.sound(Sound.Bloop); fb.haptic(Haptic.Confirm)
                cue = BuddyCue(flash.n, OneShot.Greeting)
                toasts.show(ToastData(s.approved(flash.name), buddy = spec, shot = OneShot.Wave))
            }
            is Flash.Saved -> { cue = BuddyCue(flash.n, OneShot.Nod); toasts.show(ToastData(s.saved)) }
            is Flash.Removed -> { cue = BuddyCue(flash.n, OneShot.Nod); toasts.show(ToastData(s.removedPerson)) }
            is Flash.Cleared -> { cue = BuddyCue(flash.n, OneShot.Nod); toasts.show(ToastData(s.requestsCleared)) }
            is Flash.Restarted -> if (flash.ok) {
                fb.sound(Sound.Bloop)
                cue = BuddyCue(flash.n, OneShot.Stretch)
                toasts.show(ToastData(s.restarted))
            } else {
                fb.sound(Sound.Bonk); fb.haptic(Haptic.Reject)
                cue = BuddyCue(flash.n, OneShot.Dizzy)
                toasts.show(ToastData(s.restartFailed))
            }
            is Flash.Failed -> {
                fb.sound(Sound.Bonk); fb.haptic(Haptic.Reject)
                cue = BuddyCue(flash.n, OneShot.Startle)
                toasts.show(ToastData(flash.problem.text(s, base, flash.detail, botName)))
            }
            null -> Unit
        }
    }

    val connected = m.connected()
    val stage = m.setup?.stage
    val mood = when {
        m.loading && m.platforms.isEmpty() -> Mood.Thinking
        m.restarting || stage == Setup.Stage.Waiting || stage == Setup.Stage.Applying -> Mood.Working
        m.pending.isNotEmpty() -> Mood.NeedsYou
        m.loadProblem != null && m.platforms.isEmpty() -> Mood.Error
        connected.any { it.tone == Tone.Bad } -> Mood.Error
        else -> Mood.Idle
    }

    Box(Modifier.fillMaxSize()) {
        PageFrame(MorePage.Messaging.label, actions = { IconButton(Icons.refresh, base.retry, { m.load() }) }) {
            item(key = "hero") {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Buddy(spec, mood, 72.dp, cue = cue)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(s.heroTitle(botName), Theme.type.headline, maxLines = 2)
                            val live = connected.count { it.tone == Tone.Good }
                            Txt(if (live == 0) s.noneConnected else s.connectedCount(live), Theme.type.sub, c.secondary, maxLines = 1)
                        }
                    }
                    if (bots.size > 1) Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        bots.forEach { b -> Chip(b.name, b.profile == m.profile, { if (b.profile != m.profile) m.load(b.profile) }) }
                    }
                }
            }
            if (m.restarting || m.restartNeeded) item(key = "restart") {
                RestartBanner(m.restarting, m.restartBusy, { confirmRestart = true }, Modifier.animateItem(placementSpec = Motion.smooth()))
            }
            when {
                m.loadProblem != null && m.platforms.isEmpty() -> item(key = "err") {
                    Note(m.loadProblem!!.text(s, base, null, botName)) { PillButton(base.retry, { m.load() }, kind = ButtonKind.Quiet, height = 48.dp, icon = Icons.refresh) }
                }
                m.platforms.isEmpty() -> item(key = "wait") { Row(Modifier.padding(20.dp)) { Pulse(c.working) } }
                else -> {
                    if (m.pending.isNotEmpty()) {
                        item(key = "h-ask") {
                            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                SectionLabel(s.askingSection, Modifier.weight(1f), color = c.needsYouText)
                                IconButton(Icons.close, s.clearRequests, { m.clearPending() }, tint = c.secondary)
                            }
                        }
                        items(m.pending, key = { "ask-${it.platform}-${it.requestId ?: it.userId}" }) { u ->
                            val pName = m.platform(u.platform)?.name?.let { plain(it, botName) } ?: u.platform
                            AskingRow(m, u, pName, { sheet = u.platform }, Modifier.animateItem(placementSpec = Motion.smooth()))
                        }
                    }
                    if (connected.isNotEmpty()) {
                        item(key = "h-on") { SectionLabel(s.connectedSection) }
                        items(connected, key = { "on-${it.id}" }) { p ->
                            val name = plain(p.name, botName)
                            val home = p.homeChannel?.let { h -> h.name?.takeIf { it.isNotBlank() } ?: h.chatId }
                            val sub = listOfNotNull(stateLabel(p.state, s), home?.let(s.homeChat)).joinToString(" · ")
                            PlatformRow(
                                p.id, name, sub, when (p.tone) { Tone.Good -> null; Tone.Muted -> c.secondary; Tone.Warn -> c.needsYouText; Tone.Bad -> c.errorText },
                                p.tone, busy = p.state == "connecting" || m.setup?.platform == p.id, onOpen = { sheet = p.id },
                                modifier = Modifier.animateItem(placementSpec = Motion.smooth()),
                            ) { Toggle(m.enabled(p), { m.setEnabled(p.id, it) }, s.onFor(botName)) }
                        }
                    }
                    item(key = "h-add") { SectionLabel(s.addSection) }
                    val rest = m.available()
                    if (rest.size > 8) item(key = "search") { Field(query, { query = it }, s.searchApps, Modifier.padding(horizontal = 16.dp)) }
                    val q = query.trim()
                    val shown = if (q.isEmpty()) rest else rest.filter { it.name.contains(q, true) || it.id.contains(q, true) || it.description.contains(q, true) }
                    if (shown.isEmpty()) item(key = "none") { Note(s.noMatches) }
                    items(shown, key = { "add-${it.id}" }) { p ->
                        val name = plain(p.name, botName)
                        PlatformRow(p.id, name, plain(p.description, botName), null, null, busy = m.setup?.platform == p.id, onOpen = { sheet = p.id },
                            modifier = Modifier.animateItem(placementSpec = Motion.smooth())) {
                            Icon(Icons.chevronRight, c.tertiary, 24.dp, mirror = true)
                        }
                    }
                    item(key = "end") { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
        PlatformSheet(m, sheet, botName, open) {
            // Closing the sheet mid-setup drops the setup (rule 4: everything that opens can be closed).
            if (m.setup?.platform == sheet) m.cancelSetup()
            sheet = null
        }
        RestartSheet(confirmRestart, { m.restart() }) { confirmRestart = false }
    }
}

/** Amber card: settings wait for a restart; one 56dp button (it asks once before restarting). */
@Composable
private fun RestartBanner(restarting: Boolean, busy: Boolean, onRestart: () -> Unit, modifier: Modifier = Modifier) {
    val s = S.messagingPage
    val c = Theme.colors
    Card(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).border(1.5.dp, c.needsYou, RoundedCornerShape(Radius.card.dp))) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (restarting) Pulse(c.working, 12.dp) else Icon(Icons.refresh, c.needsYouText, 24.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(if (restarting) s.restarting else s.restartTitle, Theme.type.headline, maxLines = 2)
                if (!restarting) Txt(if (busy) s.restartBusy else s.restartBody, Theme.type.sub, c.secondary, maxLines = 2)
            }
        }
        if (!restarting) PillButton(s.restartNow, onRestart, Modifier.fillMaxWidth().padding(top = 12.dp), icon = Icons.refresh)
    }
}
