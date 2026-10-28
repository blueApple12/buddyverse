package app.buddyverse.ui.more.memory

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.core.nowMillis
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Card
import app.buddyverse.design.Chip
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.Icons
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.Radius
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.MemoryPageStrings
import app.buddyverse.i18n.S
import app.buddyverse.i18n.memoryPage
import app.buddyverse.ui.ago
import kotlinx.coroutines.delay

/** Shows [content] only after [ms], so a quick answer never flashes "loading" (design rule 3). */
@Composable
fun Delayed(ms: Long = 300, content: @Composable () -> Unit) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(ms); show = true }
    if (show) content()
}

@Composable
fun LoadingNote() = Txt(S.current.loading, Theme.type.sub, Theme.colors.secondary, Modifier.padding(horizontal = 20.dp, vertical = 16.dp))

@Composable
fun ErrorRow(text: String, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.error, Theme.colors.error)
        Spacer(Modifier.width(12.dp))
        Txt(text, Theme.type.sub, Theme.colors.errorText, Modifier.weight(1f), maxLines = 3)
        Spacer(Modifier.width(8.dp))
        PillButton(S.current.retry, onRetry, height = 48.dp, kind = ButtonKind.Quiet)
    }
}

@Composable
fun Tag(text: String, color: Color = Theme.colors.secondary) {
    Box(Modifier.clip(CircleShape).background(Theme.colors.surface2).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Txt(text, Theme.type.micro, color, maxLines = 1)
    }
}

/** "5m ago" / "now"; empty when the engine gave no time. */
@Composable
fun agoLabel(epochSeconds: Double): String {
    val s = S.current
    if (epochSeconds <= 0) return ""
    return ago(epochSeconds, nowMillis(), s).let { if (it == s.justNow) it else s.ago(it) }
}

/**
 * Whose memory: one buddy per bot. The chosen one is bigger, lives the page's mood (thinking while loading, working while a write runs,
 * red after a failure) and plays its one-shots; the others idle, breathing and looking around.
 */
@Composable
fun BotPicker(crew: List<BuddySpec>, selected: String, mood: Mood, cue: BuddyCue?, onSelect: (String) -> Unit) {
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(crew, key = { it.id }) { b ->
            val sel = b.id == selected
            val size by animateDpAsState(if (sel) 56.dp else 44.dp, Motion.bouncy())
            Column(
                Modifier.width(80.dp).squish({ onSelect(b.id) }, haptic = Haptic.Tick, label = b.name)
                    .clip(RoundedCornerShape(Radius.row.dp)).background(if (sel) Theme.colors.surface2 else Color.Transparent).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.height(60.dp), contentAlignment = Alignment.BottomCenter) {
                    Buddy(b, if (sel) mood else Mood.Idle, size, cue = if (sel) cue else null, interactive = false)
                }
                Spacer(Modifier.height(4.dp))
                Txt(b.name, Theme.type.caption, if (sel) Theme.colors.accent else Theme.colors.secondary, maxLines = 1)
            }
        }
    }
}

@Composable
fun MemoryTabs(tab: MemoryTab, counts: Map<MemoryTab, Int>, onTab: (MemoryTab) -> Unit) {
    val t = S.memoryPage
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(MemoryTab.entries, key = { it.name }) { x ->
            val n = counts[x]
            Chip(tabLabel(x, t) + if (n != null && n > 0) "  $n" else "", tab == x, { onTab(x) })
        }
    }
}

fun tabLabel(x: MemoryTab, t: MemoryPageStrings): String = when (x) {
    MemoryTab.About -> t.aboutYou
    MemoryTab.Notes -> t.notes
    MemoryTab.Learned -> t.learned
    MemoryTab.Providers -> t.providers
}

/**
 * One built-in file: its on/off switch (once config loaded) and a budget meter that fills with the `smooth` spring and turns amber near
 * the limit, red when full.
 */
@Composable
fun BuiltinCard(title: String, on: Boolean?, onToggle: (Boolean) -> Unit, used: Int, limit: Int) {
    val c = Theme.colors
    val t = S.memoryPage
    val frac = if (limit > 0) used.toFloat() / limit else 0f
    val fill by animateFloatAsState(frac.coerceIn(0f, 1f), Motion.smooth())
    val tint = when { frac >= 1f -> c.error; frac >= .85f -> c.needsYou; else -> c.accentFill }
    Card(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(title, Theme.type.headline, if (on == false) c.secondary else c.text, Modifier.weight(1f), maxLines = 2)
            if (on != null) Toggle(on, onToggle, title)
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(c.surface2)) {
            Box(Modifier.fillMaxWidth(fill).fillMaxHeight().clip(CircleShape).background(tint))
        }
        Spacer(Modifier.height(6.dp))
        Txt(if (frac >= 1f) t.full else t.used(used, limit), Theme.type.caption, if (frac >= 1f) c.errorText else c.secondary, maxLines = 2)
    }
}

/** A memory entry: up to three lines and its age. Tap = read, long press = edit (design §3.7: no buttons on rows). */
@Composable
fun FactRow(f: Fact, onOpen: () -> Unit, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    PressRow(onOpen, modifier, minHeight = 64.dp, onLongPress = onEdit, label = f.text.take(60)) {
        Column(Modifier.weight(1f)) {
            Txt(f.text, Theme.type.callout, maxLines = 3)
            val age = agoLabel(f.time)
            if (age.isNotEmpty()) Txt(age, Theme.type.caption, Theme.colors.secondary, maxLines = 1)
        }
    }
}

/** A learned skill: name, category and how often it's used; a pin mark when pinned. */
@Composable
fun LearnedRow(k: LearnedSkill, onOpen: () -> Unit, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    val c = Theme.colors
    val t = S.memoryPage
    PressRow(onOpen, modifier, minHeight = 64.dp, onLongPress = onEdit, label = k.label) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(k.label, Theme.type.headline, modifier = Modifier.weight(1f, fill = false), maxLines = 1)
                if (k.category.isNotBlank() && k.category != "memory") { Spacer(Modifier.width(8.dp)); Tag(k.category.replace('-', ' ')) }
            }
            Txt(listOf(t.usedTimes(k.uses), agoLabel(k.time)).filter { it.isNotEmpty() }.joinToString(" · "), Theme.type.caption, c.secondary, maxLines = 1)
        }
        if (k.pinned) { Spacer(Modifier.width(8.dp)); Icon(Icons.pushPinFilled, c.tertiary, 20.dp) }
    }
}

fun statusLabel(status: String, t: MemoryPageStrings): String = when (status) {
    "ready" -> t.ready
    "needs_config" -> t.needsSetup
    "unavailable" -> t.unavailable
    "missing" -> t.missing
    else -> status
}

@Composable
fun statusColor(status: String): Color = when (status) {
    "ready" -> Theme.colors.success
    "needs_config" -> Theme.colors.needsYouText
    else -> Theme.colors.secondary
}

/** A provider (or "built-in only" when [p] is null): name, status, one line; a check when it's the one in use. */
@Composable
fun ProviderRow(p: Provider?, inUse: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = Theme.colors
    val t = S.memoryPage
    val name = p?.name?.replaceFirstChar { it.uppercase() } ?: t.builtInOnly
    PressRow(onOpen, modifier, minHeight = 64.dp, label = name) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(name, Theme.type.headline, modifier = Modifier.weight(1f, fill = false), maxLines = 1)
                if (p != null) { Spacer(Modifier.width(8.dp)); Tag(if (inUse) t.inUse else statusLabel(p.status, t), if (inUse) c.accent else statusColor(p.status)) }
            }
            Txt(p?.description ?: t.builtInDesc, Theme.type.sub, c.secondary, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (inUse) Icon(Icons.checkCircleFilled, c.success) else Icon(Icons.chevronRight, c.tertiary, mirror = true)
        }
    }
}

/** Empty list: the bot's buddy, big and alive (tap it), the line, and an optional action. */
@Composable
fun EmptyBuddy(spec: BuddySpec?, text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (spec != null) Buddy(remember(spec) { spec.copy(id = spec.id + "/empty") }, Mood.Idle, 88.dp)
        Spacer(Modifier.height(14.dp))
        Txt(text, Theme.type.callout, Theme.colors.secondary)
        if (action != null) { Spacer(Modifier.height(16.dp)); PillButton(action, onAction, icon = Icons.add) }
    }
}
