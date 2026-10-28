package app.buddyverse.ui.more

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.Mood
import app.buddyverse.core.Bot
import app.buddyverse.core.nowMillis
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Dot
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.mcpPage
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.PageFrame
import app.buddyverse.ui.more.mcp.AddSheet
import app.buddyverse.ui.more.mcp.EditSheet
import app.buddyverse.ui.more.mcp.McpModel
import app.buddyverse.ui.more.mcp.McpServer
import app.buddyverse.ui.more.mcp.McpState
import app.buddyverse.ui.more.mcp.ServerSheet
import app.buddyverse.ui.more.mcp.statusColor
import app.buddyverse.ui.more.mcp.statusText
import kotlinx.coroutines.delay

/**
 * More ▸ MCP servers (hermes-api.md §2.6): one list shared by all bots (read from the default bot, every change written to each bot),
 * with live status and an on/off switch; a server sheet to test it,
 * switch single tools, set an API key, sign in (OAuth), edit and delete (with undo); add from the catalog or your own.
 */
@Composable
fun McpPage() {
    val store = LocalStore.current
    val t = S.mcpPage
    val s = S.current
    val toasts = LocalToasts.current
    val fb = LocalFeedback.current
    val m = remember(store) { McpModel(store, toasts, fb) }
    m.t = t
    m.undoLabel = s.undo
    val bots by store.bots.collectAsState()
    val bot = bots.firstOrNull { it.isDefault } ?: bots.firstOrNull()
    val profile = bot?.profile ?: "default"
    LaunchedEffect(profile) {
        m.load(profile)
        while (true) { delay(8_000); m.refreshStatus() }
    }
    var hurt by remember { mutableStateOf(false) }
    LaunchedEffect(m.failedAt) { if (m.failedAt > 0) { hurt = true; delay(2_500); hurt = false } }
    val mood = when {
        m.unsupported -> Mood.Sleeping
        !m.loaded -> Mood.Thinking
        hurt || (m.error != null && m.servers.isEmpty()) -> Mood.Error
        m.busy -> Mood.Working
        else -> Mood.Idle
    }
    var open by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<McpServer?>(null) }
    var adding by remember { mutableStateOf(false) }
    val rows = m.servers.filter { it.name !in m.hidden }

    Box(Modifier.fillMaxSize()) {
        PageFrame(s.mcp, actions = { IconButton(Icons.add, t.addServer, { adding = true }, enabled = !m.unsupported) }) {
            item(key = "bots") { McpHeader(bot, mood, m.cue, t.serversAll(rows.size)) }
            when {
                m.unsupported -> item(key = "msg") { McpMessage(s.unsupported) }
                !m.loaded -> Unit
                m.error != null && rows.isEmpty() -> item(key = "msg") {
                    McpMessage(m.error.orEmpty(), s.retry, Icons.refresh) { m.load(profile) }
                }
                rows.isEmpty() -> item(key = "msg") { McpMessage(t.noServers, t.addServer, Icons.add) { adding = true } }
                else -> items(rows, key = { "server:" + it.name }) { srv ->
                    McpServerRow(m, srv, Modifier.animateItem(fadeInSpec = Motion.fade(), placementSpec = Motion.smooth(), fadeOutSpec = Motion.fade())) {
                        open = srv.name
                    }
                }
            }
        }
        ServerSheet(m, open?.takeIf { it !in m.hidden }, bot, mood, onEdit = { editing = it; open = null }, onDismiss = { open = null })
        EditSheet(m, editing, bot) { editing = null }
        AddSheet(m, adding, bot, onDismiss = { adding = false }) { name -> adding = false; open = name }
    }
}

/** The default bot as a living buddy and the count (the list is every bot's). */
@Composable
private fun McpHeader(bot: Bot?, mood: Mood, cue: BuddyCue?, summary: String) {
    // Serials are timestamps: never replay an old one on a freshly composed buddy.
    val shownCue = cue?.takeIf { nowMillis() - it.serial < 2_000 }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (bot != null) { Buddy(bot.spec, mood, 64.dp, cue = shownCue); Spacer(Modifier.width(14.dp)) }
        Txt(summary, Theme.type.headline, modifier = Modifier.weight(1f), maxLines = 2)
    }
}

@Composable
private fun McpServerRow(m: McpModel, srv: McpServer, modifier: Modifier, onOpen: () -> Unit) {
    val t = S.mcpPage
    val c = Theme.colors
    val st = m.stateOf(srv)
    PressRow(onOpen, modifier, minHeight = 76.dp, label = srv.name) {
        McpStatusDot(st)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(srv.name, Theme.type.headline, maxLines = 1)
            Txt(statusText(st, m.toolCount(srv), t), Theme.type.sub, statusColor(st), maxLines = 1)
            Txt(srv.target, Theme.type.code, c.secondary, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        if (srv.readOnly) Box(Modifier.size(56.dp, 48.dp), contentAlignment = Alignment.Center) { Icon(Icons.lock, c.tertiary, 22.dp) }
        else Toggle(srv.enabled, { on -> m.toggle(srv, on) }, srv.name)
    }
}

/** Colored status dot; it breathes while connecting. */
@Composable
private fun McpStatusDot(st: McpState) {
    val c = Theme.colors
    val color by animateColorAsState(
        when (st) {
            McpState.Connected -> c.success
            McpState.Failed -> c.error
            McpState.NeedsAuth -> c.needsYou
            McpState.Connecting -> c.working
            McpState.Idle, McpState.Off -> c.tertiary
        },
        Motion.fade(),
    )
    val pulse = if (st == McpState.Connecting) rememberInfiniteTransition().animateFloat(1f, .35f, infiniteRepeatable(tween(600), RepeatMode.Reverse)) else null
    Dot(color, 12.dp, Modifier.graphicsLayer { alpha = pulse?.value ?: 1f })
}

/** Empty, error and unsupported states: one line and at most one button; the buddy above carries the feeling. */
@Composable
private fun McpMessage(text: String, action: String? = null, icon: ImageVector? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(text, Theme.type.title2.copy(textAlign = TextAlign.Center), Theme.colors.secondary)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            PillButton(action, onAction, icon = icon, kind = ButtonKind.Primary)
        }
    }
}
