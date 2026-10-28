package app.buddyverse.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddyGroup
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.Bot
import app.buddyverse.core.Room
import app.buddyverse.core.api.LiveSubagent
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Card
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.Radius
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.bot
import app.buddyverse.i18n.home
import app.buddyverse.ui.ago
import app.buddyverse.ui.chat.PromptCard
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Roster row (design §3.1): 76 dp, buddy 52 + badge, name + time, live line 2; swipe toward start = Hide, long press = menu. */
@Composable
internal fun BotRow(
    bot: Bot, live: BotLive, index: Int, nowMs: Long, dimmed: Boolean,
    onOpen: () -> Unit, onMenu: () -> Unit, onHide: (() -> Unit)?, modifier: Modifier = Modifier,
) {
    val c = Theme.colors
    var tap by remember { mutableStateOf<BuddyCue?>(null) }
    SwipeToHide(onHide, modifier.graphicsLayer { alpha = if (dimmed) .5f else 1f }) {
        PressRow({ tap = Cues.cue(OneShot.Tap); onOpen() }, minHeight = 76.dp, onLongPress = onMenu, label = bot.name) {
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                HomeBuddy(bot.spec, live.mood, 52.dp, index, tap = tap, talkAt = live.talkAt, talkChars = live.talkChars)
                StatusBadge(live.mood == Mood.NeedsYou, live.unread)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Txt(bot.name, Theme.type.headline, modifier = Modifier.weight(1f, fill = false), maxLines = 1)
                        if (bot.isDefault) { Spacer(Modifier.width(4.dp)); Icon(Icons.autoAwesomeFilled, c.accent, 16.dp) }
                        if (bot.pinned) { Spacer(Modifier.width(4.dp)); Icon(Icons.pushPinFilled, c.tertiary, 14.dp) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Txt(ago(bot.lastActive, nowMs, S.current), Theme.type.caption, c.secondary, maxLines = 1)
                }
                LineTwo(live)
            }
        }
    }
}

/** A group chat in the same list: stacked member buddies, name, members (design §3.1 "group/project row"). */
@Composable
internal fun RoomRow(room: Room, members: List<Bot>, live: Map<String, BotLive>, nowMs: Long, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val s = S.current
    val c = Theme.colors
    val cues = LocalHomeCues.current
    val name = room.name.ifBlank { s.groupOf(room.members.size) }
    PressRow(onOpen, modifier, minHeight = 76.dp, label = name) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            BuddyGroup(
                members.map { it.spec },
                members.map { when { cues.down -> Mood.Sleeping; cues.wakeAt < 0L -> Mood.Offline; else -> live[it.profile]?.mood ?: Mood.Idle } },
                52.dp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(name, Theme.type.headline, modifier = Modifier.weight(1f), maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Txt(ago(room.updatedAt, nowMs, s), Theme.type.caption, c.secondary, maxLines = 1)
            }
            val lead = roomLead(members, live)
            if (lead != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(lead.name + ": ", Theme.type.sub, c.secondary, maxLines = 1)
                LineTwo(live.getValue(lead.profile), Modifier.weight(1f))
            } else Txt(members.joinToString(", ") { it.name }.ifEmpty { s.groupOf(room.members.size) }, Theme.type.sub, c.secondary, maxLines = 1)
        }
    }
}

/**
 * Swipe toward start reveals Hide (design §3.1; by edge, not side, §9.7): a tick when crossing 35%, release past it hides.
 * Offsets are physical (absolute) so they follow the finger in both directions of text.
 */
@Composable
private fun SwipeToHide(onHide: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (onHide == null) { Box(modifier) { content() }; return }
    val c = Theme.colors
    val fb = LocalFeedback.current
    val toStart = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else -1f
    val scope = rememberCoroutineScope()
    val dx = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(1) }
    val hide by rememberUpdatedState<() -> Unit>(onHide)
    Box(modifier.fillMaxWidth().onSizeChanged { width = max(1, it.width) }) {
        Row(
            Modifier.matchParentSize().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(Radius.row.dp)).background(c.surface2)
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.visibility, c.secondary, 22.dp)
            Spacer(Modifier.width(8.dp))
            Txt(S.home.hide, Theme.type.callout, c.secondary, maxLines = 1)
        }
        Box(
            Modifier.absoluteOffset { IntOffset(dx.value.roundToInt(), 0) }.background(c.ground).pointerInput(toStart) {
                var cur = 0f
                val armedAt = { width * .35f }
                detectHorizontalDragGestures(
                    onDragStart = { cur = dx.value },
                    onDragEnd = {
                        val armed = abs(cur) > armedAt()
                        scope.launch {
                            if (armed) { dx.animateTo(toStart * width, Motion.snappy()); hide(); dx.snapTo(0f) }
                            else dx.animateTo(0f, Motion.snappy())
                        }
                    },
                    onDragCancel = { scope.launch { dx.animateTo(0f, Motion.snappy()) } },
                ) { change, amount ->
                    val next = (cur + amount).let { if (it * toStart < 0f) 0f else it }
                    if ((abs(cur) > armedAt()) != (abs(next) > armedAt())) fb.haptic(Haptic.Tick)
                    cur = next
                    change.consume()
                    scope.launch { dx.snapTo(next) }
                }
            },
        ) { content() }
    }
}

/**
 * Needs-you card (design §3.1, min 120): the chat's own prompt card with the asking buddy (approve, answer, or a masked
 * secret field), answered right here; while the prompt is not loaded yet, the buddy and an Open button instead.
 */
@Composable
internal fun AskCard(ask: Ask, index: Int, onAnswer: (String) -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val prompt = ask.prompt
    if (prompt != null) { PromptCard(prompt, onAnswer, modifier.padding(horizontal = 16.dp), bot = ask.bot); return }
    val s = S.current
    val c = Theme.colors
    Card(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HomeBuddy(ask.bot.spec, Mood.NeedsYou, 48.dp, index)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(ask.bot.name, Theme.type.headline, maxLines = 1)
                Txt(s.needsYou, Theme.type.sub, c.needsYouText, maxLines = 2)
            }
        }
        Spacer(Modifier.height(12.dp))
        PillButton(s.open, onOpen, Modifier.align(Alignment.End).widthIn(min = 120.dp), height = 48.dp)
    }
}

/** The floating tab bar is drawn over tab screens (AppRoot), so sheets opened from a tab end above it (as Chats does). */
private val TabBarClearance = 84.dp

internal enum class MenuAction { Message, Profile, Pin, EditLook, NewSession, MarkRead, Hide, Delete }

/**
 * Long-press menu and stage peek in one sheet (design §3.1): live buddy, name and line 2 on top, then Message, Profile, Pin,
 * Edit look, New session, Mark read, Hide, Delete. Closes by back, scrim or ×.
 */
@Composable
internal fun BotMenu(
    bot: Bot?, live: BotLive?, helpers: List<LiveSubagent>, onDismiss: () -> Unit, onAction: (Bot, MenuAction) -> Unit, onHelper: (Bot, LiveSubagent) -> Unit,
) {
    val last = remember { arrayOfNulls<Bot>(1) }
    val lastLive = remember { arrayOfNulls<BotLive>(1) }
    val lastHelpers = remember { arrayOfNulls<List<LiveSubagent>>(1) }
    if (bot != null) { last[0] = bot; lastLive[0] = live; lastHelpers[0] = helpers }
    val s = S.current
    val h = S.home
    val c = Theme.colors
    Sheet(bot != null, onDismiss) {
        val b = last[0] ?: return@Sheet
        val l = lastLive[0]
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            HomeBuddy(b.spec, l?.mood ?: Mood.Idle, 72.dp, 0, talkAt = l?.talkAt ?: 0L, talkChars = l?.talkChars ?: 0)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(b.name, Theme.type.title2, maxLines = 1)
                if (l != null) LineTwo(l)
            }
            IconButton(Icons.close, s.close, onDismiss)
        }
        // Scrolls when a short window can't fit every row. Its live helpers first: each opens the chat it streams into, to watch it work.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            lastHelpers[0].orEmpty().take(3).forEachIndexed { j, sa ->
                val label = sa.goal ?: h.helper
                PressRow({ onHelper(b, sa) }, minHeight = 56.dp, label = label) {
                    Buddy(helperSpec(b.spec, j, sa.goal), Mood.Working, 28.dp, interactive = false)
                    Spacer(Modifier.width(12.dp))
                    Txt(label, Theme.type.callout, c.text, Modifier.weight(1f), maxLines = 1)
                }
            }
            val items = buildList {
                add(Triple(MenuAction.Message, Icons.chat, h.messageAction))
                add(Triple(MenuAction.Profile, Icons.info, h.profile))
                add(Triple(MenuAction.Pin, Icons.pushPin, if (b.pinned) s.unpin else s.pin))
                add(Triple(MenuAction.EditLook, Icons.palette, S.bot.editLook))
                add(Triple(MenuAction.NewSession, Icons.addComment, s.newSession))
                if (l?.unread == true) add(Triple(MenuAction.MarkRead, Icons.check, h.markRead))
                if (!b.isDefault) add(Triple(MenuAction.Hide, Icons.visibility, if (b.hidden) h.show else h.hide))
                if (!b.isDefault) add(Triple(MenuAction.Delete, Icons.delete, s.delete))
            }
            for ((a, icon, label) in items) {
                val danger = a == MenuAction.Delete
                PressRow({ onAction(b, a) }, minHeight = 56.dp, label = label) {
                    Icon(icon, if (danger) c.error else c.text, 24.dp)
                    Spacer(Modifier.width(16.dp))
                    Txt(label, Theme.type.callout, if (danger) c.errorText else c.text, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(TabBarClearance))
    }
}

/** Delete asks first (design rule: destructive confirm sheet); Delete gives the reject haptic. */
@Composable
internal fun DeleteBotSheet(bot: Bot?, onDismiss: () -> Unit, onConfirm: (Bot) -> Unit) {
    val last = remember { arrayOfNulls<Bot>(1) }
    if (bot != null) last[0] = bot
    val b = last[0]
    val s = S.current
    Sheet(bot != null, onDismiss, title = b?.let { S.home.deleteTitle(it.name) }) {
        Txt(S.home.deleteBody, Theme.type.body, Theme.colors.secondary, Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(s.cancel, onDismiss, Modifier.weight(1f), kind = ButtonKind.Quiet)
            PillButton(s.deleteForGood, { b?.let(onConfirm) }, Modifier.weight(1f), kind = ButtonKind.Danger, haptic = Haptic.Reject)
        }
        Spacer(Modifier.height(TabBarClearance))
    }
}

@Composable
internal fun HiddenFooter(count: Int, showing: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val c = Theme.colors
    val text = if (showing) S.home.tuckHidden else S.home.showHidden(count)
    PressRow(onToggle, modifier, minHeight = 56.dp, label = text) {
        Icon(Icons.visibility, c.secondary, 22.dp)
        Spacer(Modifier.width(12.dp))
        Txt(text, Theme.type.callout, c.secondary, maxLines = 1)
    }
}

/** One big buddy (160 dp) and, when there is something to do, a 56 dp button (design §3.1 "Empty", §3.10). */
@Composable
internal fun Hero(spec: BuddySpec, mood: Mood, action: String?, onAction: () -> Unit) {
    var tap by remember { mutableStateOf<BuddyCue?>(null) }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(176.dp).squish({ tap = Cues.cue(OneShot.Tap) }, label = spec.name), contentAlignment = Alignment.Center) {
            HomeBuddy(spec, mood, 160.dp, 0, tap = tap)
        }
        Spacer(Modifier.height(28.dp))
        if (action != null) PillButton(action, onAction, Modifier.widthIn(min = 240.dp))
    }
}

/** First load: the stage's brain buddy thinking above a few soft placeholder rows, not the sleepy empty state. */
@Composable
internal fun RosterSkeleton(spec: BuddySpec) {
    val c = app.buddyverse.design.Theme.colors
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton")
        .animateFloat(.45f, .9f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { HomeBuddy(spec, Mood.Thinking, 96.dp, 0) }
        repeat(4) { i ->
            Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 20.dp).graphicsLayer { alpha = pulse.value }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(c.surface2))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(if (i % 2 == 0) .45f else .6f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(c.surface2))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth(.8f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(c.surface2))
                }
            }
        }
    }
}
