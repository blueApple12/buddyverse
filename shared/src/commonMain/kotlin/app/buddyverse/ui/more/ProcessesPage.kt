package app.buddyverse.ui.more

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.Bot
import app.buddyverse.core.ChatTarget
import app.buddyverse.core.LinkState
import app.buddyverse.core.nowMillis
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.processesPage
import app.buddyverse.ui.LocalNav
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.PageFrame
import app.buddyverse.ui.Screen
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.more.processes.HostShell
import app.buddyverse.ui.more.processes.Proc
import app.buddyverse.ui.more.processes.ProcBuddy
import app.buddyverse.ui.more.processes.ProcessSheet
import app.buddyverse.ui.more.processes.ProcessesModel
import app.buddyverse.ui.more.processes.ShellSheet
import app.buddyverse.ui.more.processes.StopControl
import app.buddyverse.ui.more.processes.fresh
import app.buddyverse.ui.more.processes.metaLine

/**
 * More ▸ Processes (hermes-api.md §2.6): every background process the bots run (`process.list` per session), live output
 * (`agent.terminal.output`), Stop (`process.kill`) and Stop all in a chat (`process.stop`), plus a shell on the PC
 * (the Webapp's terminal rail over `/api/host-terminal`).
 */
@Composable
fun ProcessesPage() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val toasts = LocalToasts.current
    val fb = LocalFeedback.current
    @Suppress("DEPRECATION") val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val s = S.current
    val t = S.processesPage
    val m = remember(store) { ProcessesModel(store, toasts, fb) }
    m.t = t
    m.errorText = s.error
    val shell = remember(store) { HostShell(store) }
    DisposableEffect(shell) { onDispose { shell.close() } }
    LaunchedEffect(m) { m.poll() }
    LaunchedEffect(m) { store.gateway.events.collect { m.onEvent(it) } }
    val bots by store.bots.collectAsState()
    val link by store.link.collectAsState()
    val botsLoaded by store.botsLoaded.collectAsState()
    LaunchedEffect(link, botsLoaded) { if (link is LinkState.Ready && botsLoaded) m.kick() }
    var open by remember { mutableStateOf<String?>(null) }
    var shellOpen by remember { mutableStateOf(false) }
    val brain = bots.firstOrNull { it.isDefault } ?: bots.firstOrNull()
    val running = m.procs.filter { it.running }
    val finished = m.procs.filterNot { it.running }
    val error = m.error

    Box(Modifier.fillMaxSize()) {
        PageFrame(s.processes, actions = { IconButton(Icons.terminal, t.shell, { shellOpen = true }) }) {
            when {
                m.unsupported -> item(key = "msg") { ProcMessage(brain, Mood.Sleeping, s.unsupported) }
                !m.loaded -> item(key = "msg") { ProcMessage(brain, Mood.Thinking, if (link is LinkState.Ready) s.loading else s.reconnecting) }
                error != null && m.procs.isEmpty() -> item(key = "msg") { ProcMessage(brain, Mood.Error, error, s.retry, Icons.refresh) { m.kick() } }
                m.procs.isEmpty() -> item(key = "msg") {
                    ProcMessage(brain, Mood.Sleeping, t.nothingRunning, t.openShell, Icons.terminal) { shellOpen = true }
                }
                else -> {
                    if (running.isNotEmpty()) {
                        item(key = "h:run") { SectionLabel("${t.runningHeader} · ${running.size}") }
                        items(running, key = { "p:" + it.id }) { p ->
                            ProcRow(m, p, bots.firstOrNull { it.profile == p.profile }, Modifier.animateItem(fadeInSpec = Motion.fade(), placementSpec = Motion.smooth(), fadeOutSpec = Motion.fade())) { open = p.id }
                        }
                    }
                    if (finished.isNotEmpty()) {
                        item(key = "h:done") { SectionLabel(t.finishedHeader) }
                        items(finished, key = { "p:" + it.id }) { p ->
                            ProcRow(m, p, bots.firstOrNull { it.profile == p.profile }, Modifier.animateItem(fadeInSpec = Motion.fade(), placementSpec = Motion.smooth(), fadeOutSpec = Motion.fade())) { open = p.id }
                        }
                    }
                }
            }
        }
        ProcessSheet(
            m, open, bots,
            onDismiss = { open = null },
            onOpenChat = { p -> open = null; nav.push(Screen.Chat(chatTarget(p, bots))) },
            onCopy = { text -> clip.setText(AnnotatedString(text)); toasts.show(ToastData(t.copied)) },
        )
        ShellSheet(shell, shellOpen, brain?.profile ?: "default", brain?.spec) { shell.close(); shellOpen = false }
    }
}

/** The bot's main chat opens as its Bot Chat; any other chat opens as that session. */
private fun chatTarget(p: Proc, bots: List<Bot>): ChatTarget =
    if (bots.firstOrNull { it.profile == p.profile }?.botChatId == p.sessionId) ChatTarget(p.profile)
    else ChatTarget(p.profile, p.sessionId, botChat = false)

/** 76dp row: the owning bot's buddy with a status dot, the command, who / where / how long, the last output line; one Stop. */
@Composable
private fun ProcRow(m: ProcessesModel, p: Proc, bot: Bot?, modifier: Modifier, onOpen: () -> Unit) {
    val s = S.current
    val t = S.processesPage
    val c = Theme.colors
    var hop by remember { mutableStateOf<BuddyCue?>(null) }
    val stopping = p.id in m.stopping
    PressRow({ hop = BuddyCue(nowMillis(), OneShot.Hop); onOpen() }, modifier, minHeight = 76.dp, label = p.title) {
        ProcBuddy(p, bot, stopping, fresh(m.cues[p.id], hop), 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(p.title, Theme.type.code, maxLines = 1)
            Txt(metaLine(p, bot, s, t, nowMillis()), Theme.type.sub, if (p.failed) c.errorText else c.secondary, maxLines = 1)
            if (p.running) {
                val buf = m.buffer(p.id)
                val last = remember(buf, buf.rev) { buf.lastLine() }
                if (last != null) Txt(last, Theme.type.code.copy(fontSize = Theme.type.micro.fontSize), c.tertiary, maxLines = 1)
            }
        }
        if (p.running) {
            Spacer(Modifier.width(8.dp))
            StopControl(stopping, { m.kill(p) })
        }
    }
}

/** Loading, empty, error and unsupported: a living buddy (160dp), one line, at most one button (design §3.1 empty). */
@Composable
private fun ProcMessage(brain: Bot?, mood: Mood, text: String, action: String? = null, icon: ImageVector? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (brain != null) {
            Buddy(brain.spec, mood, 160.dp)
            Spacer(Modifier.height(20.dp))
        }
        Txt(text, Theme.type.title2.copy(textAlign = TextAlign.Center), Theme.colors.secondary)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            PillButton(action, onAction, icon = icon, kind = ButtonKind.Primary)
        }
    }
}
