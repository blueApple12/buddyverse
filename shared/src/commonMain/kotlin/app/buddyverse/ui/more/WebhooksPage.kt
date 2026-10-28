package app.buddyverse.ui.more

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.Bot
import app.buddyverse.core.api.WebhookRoute
import app.buddyverse.core.nowMillis
import app.buddyverse.design.BackHandler
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Card
import app.buddyverse.design.Dot
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.Radius
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.webhooksPage
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.MorePage
import app.buddyverse.ui.PageFrame
import app.buddyverse.ui.more.webhooks.CreateSheet
import app.buddyverse.ui.more.webhooks.HookMenu
import app.buddyverse.ui.more.webhooks.HookSheet
import app.buddyverse.ui.more.webhooks.WebhooksModel
import app.buddyverse.ui.more.webhooks.deliverLine
import app.buddyverse.ui.more.webhooks.eventsLine
import app.buddyverse.ui.more.webhooks.freshCue
import kotlinx.coroutines.delay

/**
 * More ▸ Webhooks (hermes-api.md §2.5): per bot, the inbound event routes with an on/off switch; a sheet per route (link to
 * copy, delivery, events, skills, prompt, delete with undo); a form to add one that shows its secret once. Turning the
 * webhook listener on restarts the engine; when the server can't do that itself the page offers a confirmed restart.
 */
@Composable
fun WebhooksPage() {
    val store = LocalStore.current
    val t = S.webhooksPage
    val s = S.current
    val toasts = LocalToasts.current
    val fb = LocalFeedback.current
    val m = remember(store) { WebhooksModel(store, toasts, fb) }
    m.t = t
    m.undoLabel = s.undo
    val bots by store.bots.collectAsState()
    var picked by remember { mutableStateOf<String?>(null) }
    val profile = picked ?: bots.firstOrNull { it.isDefault }?.profile ?: bots.firstOrNull()?.profile ?: "default"
    val bot = bots.firstOrNull { it.profile == profile }
    LaunchedEffect(profile) {
        m.load(profile)
        while (true) { delay(15_000); m.poll() }
    }
    var hurt by remember { mutableStateOf(false) }
    LaunchedEffect(m.failedAt) { if (m.failedAt > 0) { hurt = true; delay(2_500); hurt = false } }
    val rows = m.visible
    val mood = when {
        m.unsupported -> Mood.Sleeping
        !m.loaded -> Mood.Thinking
        hurt || (m.error != null && rows.isEmpty()) -> Mood.Error
        m.enabling || m.restarting || m.creating -> Mood.Working
        m.restartNeeded != null -> Mood.NeedsYou
        !m.platformOn -> Mood.Sleeping
        else -> Mood.Idle
    }
    var open by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val canAdd = m.loaded && m.platformOn && !m.unsupported && !m.restarting

    Box(Modifier.fillMaxSize()) {
        PageFrame(MorePage.Webhooks.label, actions = { IconButton(Icons.add, t.newWebhook, { adding = true }, enabled = canAdd) }) {
            item(key = "bots") {
                HookBotStrip(bots, profile, mood, freshCue(m), t.countOf(bot?.name ?: profile, rows.size)) { p ->
                    if (p != profile) { picked = p; open = null; menu = null }
                }
            }
            if (m.loaded && !m.unsupported && m.error == null) {
                if (!m.platformOn || m.enabling) item(key = "platform") {
                    PlatformCard(m, Modifier.animateItem(fadeInSpec = Motion.fade(), placementSpec = Motion.smooth(), fadeOutSpec = Motion.fade()))
                }
                if (m.restarting || m.restartNeeded != null) item(key = "restart") {
                    RestartCard(m, Modifier.animateItem(fadeInSpec = Motion.fade(), placementSpec = Motion.smooth(), fadeOutSpec = Motion.fade()))
                }
            }
            when {
                m.unsupported -> item(key = "msg") { HookMessage(s.unsupported) }
                !m.loaded -> Unit
                m.error != null && rows.isEmpty() -> item(key = "msg") { HookMessage(m.error.orEmpty(), s.retry, Icons.refresh) { m.load(profile) } }
                rows.isEmpty() && m.platformOn -> item(key = "msg") { HookMessage(t.noWebhooks, t.newWebhook, Icons.add) { adding = true } }
                else -> items(rows, key = { "hook:" + it.name }) { r ->
                    HookRow(m, r, Modifier.animateItem(fadeInSpec = Motion.fade(), placementSpec = Motion.smooth(), fadeOutSpec = Motion.fade()),
                        onOpen = { open = r.name }, onMenu = { menu = r.name })
                }
            }
        }
        HookSheet(m, open?.takeIf { it !in m.hidden }, bot, mood, onDismiss = { open = null })
        HookMenu(m, menu?.takeIf { it !in m.hidden }, onDismiss = { menu = null }, onOpen = { open = it })
        CreateSheet(m, adding, bot, onDismiss = { adding = false })
    }
}

/** The chosen bot as a living buddy, plus the other bots to switch to (webhooks belong to a bot). */
@Composable
private fun HookBotStrip(bots: List<Bot>, selected: String, mood: Mood, cue: BuddyCue?, summary: String, onPick: (String) -> Unit) {
    val c = Theme.colors
    val me = bots.firstOrNull { it.profile == selected }
    val others = bots.filter { it.profile != selected && !it.hidden }
    var hop by remember { mutableStateOf<BuddyCue?>(null) }
    // Serials are timestamps: show the newest, and never replay an old one on a freshly composed buddy.
    val shownCue = listOfNotNull(cue, hop).maxByOrNull { it.serial }?.takeIf { nowMillis() - it.serial < 2_000 }
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (me != null) { Buddy(me.spec, mood, 64.dp, cue = shownCue); Spacer(Modifier.width(14.dp)) }
            Txt(summary, Theme.type.headline, modifier = Modifier.weight(1f), maxLines = 2)
        }
        if (others.isNotEmpty()) Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            others.forEach { b ->
                Column(
                    Modifier.width(64.dp).squish({ hop = BuddyCue(nowMillis(), OneShot.Hop); onPick(b.profile) }, label = b.name).padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Buddy(b.spec, Mood.Idle, 40.dp, interactive = false)
                    Txt(b.name, Theme.type.micro, c.secondary, maxLines = 1)
                }
            }
        }
    }
}

/** 76dp row: status dot, name, where it delivers and which events; one control (the switch); the rest on long-press. */
@Composable
private fun HookRow(m: WebhooksModel, r: WebhookRoute, modifier: Modifier, onOpen: () -> Unit, onMenu: () -> Unit) {
    val t = S.webhooksPage
    val c = Theme.colors
    val dot by animateColorAsState(if (r.enabled) c.success else c.tertiary, Motion.fade())
    PressRow(onOpen, modifier, minHeight = 76.dp, onLongPress = onMenu, label = r.name) {
        Dot(dot, 12.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(r.name, Theme.type.headline, if (r.enabled) c.text else c.secondary, maxLines = 1)
            Txt("${deliverLine(r, t)} · ${eventsLine(r, t)}", Theme.type.sub, c.secondary, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Toggle(r.enabled, { on -> m.toggle(r, on) }, r.name)
    }
}

/** The webhook listener is off for this bot: one line on what turning it on does, one button. */
@Composable
private fun PlatformCard(m: WebhooksModel, modifier: Modifier) {
    val t = S.webhooksPage
    val c = Theme.colors
    Card(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.link, c.secondary, 28.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(t.platformOff, Theme.type.headline, maxLines = 2)
                Txt(t.platformOffBody, Theme.type.sub, c.secondary)
            }
        }
        Spacer(Modifier.height(14.dp))
        PillButton(if (m.enabling) t.turningOn else t.turnOn, m::enable, Modifier.fillMaxWidth(), icon = Icons.powerSettingsNew,
            enabled = !m.enabling && !m.restarting)
    }
}

/** Engine restart: in progress, or needed (with a confirm step, since a forced restart stops live chats on every bot). */
@Composable
private fun RestartCard(m: WebhooksModel, modifier: Modifier) {
    val t = S.webhooksPage
    val s = S.current
    val c = Theme.colors
    var asking by remember { mutableStateOf(false) }
    BackHandler(asking && !m.restarting) { asking = false }
    Card(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        .border(1.5.dp, if (m.restarting) c.working else c.needsYou, RoundedCornerShape(Radius.card.dp))) {
        AnimatedContent(
            when { m.restarting -> 0; asking -> 2; else -> 1 },
            transitionSpec = { fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade()) },
        ) { step ->
            Column {
                when (step) {
                    0 -> Txt(t.restarting, Theme.type.headline, c.working, maxLines = 2)
                    1 -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.warning, c.needsYou, 24.dp)
                            Spacer(Modifier.width(12.dp))
                            Txt(m.restartNeeded.orEmpty(), Theme.type.headline, c.needsYouText, Modifier.weight(1f), maxLines = 4)
                        }
                        Spacer(Modifier.height(14.dp))
                        PillButton(t.restartEngine, { asking = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Outline, icon = Icons.refresh)
                    }
                    else -> {
                        Txt(t.restartWarn, Theme.type.headline, maxLines = 3)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton(t.restart, { asking = false; m.restart() }, Modifier.weight(1f), kind = ButtonKind.Danger,
                                icon = Icons.powerSettingsNew, haptic = Haptic.Reject)
                            PillButton(s.cancel, { asking = false }, Modifier.weight(1f), kind = ButtonKind.Quiet)
                        }
                    }
                }
            }
        }
    }
}

/** Empty, error and unsupported states: one line and at most one button; the buddy above carries the feeling. */
@Composable
private fun HookMessage(text: String, action: String? = null, icon: ImageVector? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(text, Theme.type.title2.copy(textAlign = TextAlign.Center), Theme.colors.secondary)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            PillButton(action, onAction, icon = icon, kind = ButtonKind.Primary)
        }
    }
}
