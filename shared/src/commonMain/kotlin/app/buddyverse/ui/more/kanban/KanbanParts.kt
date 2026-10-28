package app.buddyverse.ui.more.kanban

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Chip
import app.buddyverse.design.Dot
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.Radius
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.kanbanPage
import app.buddyverse.ui.ago

fun statusIcon(status: String): ImageVector = when (status) {
    "triage" -> Icons.lightbulb
    "todo" -> Icons.description
    "scheduled" -> Icons.schedule
    "ready" -> Icons.playArrow
    "running" -> Icons.bolt
    "blocked" -> Icons.warning
    "review" -> Icons.visibility
    "done" -> Icons.checkCircle
    "archived" -> Icons.archive
    else -> Icons.viewKanban
}

/** Icon and fill color of a status (amber = needs you, blue = working, design §7). */
@Composable
fun statusColor(status: String): Color = when (status) {
    "running" -> Theme.colors.working
    "blocked" -> Theme.colors.needsYou
    "done" -> Theme.colors.success
    "ready", "review" -> Theme.colors.accent
    else -> Theme.colors.tertiary
}

@Composable
fun statusTextColor(status: String): Color = when (status) {
    "running" -> Theme.colors.working
    "blocked" -> Theme.colors.needsYouText
    "done" -> Theme.colors.success
    "ready", "review" -> Theme.colors.accent
    else -> Theme.colors.secondary
}

/** The assignee's buddy acts out the card: working while it runs, waving for you when stuck, napping when done. */
fun moodOf(task: KTask): Mood = when {
    task.status == "running" -> Mood.Working
    task.status == "blocked" -> Mood.NeedsYou
    task.failures > 0 && task.status != "done" -> Mood.Error
    task.status == "review" -> Mood.Listening
    task.status == "done" -> Mood.Sleeping
    else -> Mood.Idle
}

/** Keeps the last non-null value so a closing sheet animates out with its content. */
@Composable
fun <T> rememberLast(v: T?): T? {
    val box = remember { arrayOfNulls<Any>(1) }
    if (v != null) box[0] = v
    @Suppress("UNCHECKED_CAST")
    return box[0] as T?
}

/** A card without an assignee: its status icon in a tinted circle; it pulses while running. */
@Composable
fun StatusBubble(status: String, size: Dp) {
    val color = statusColor(status)
    val pulse = if (status == "running") rememberInfiniteTransition().animateFloat(1f, .4f, infiniteRepeatable(tween(700), RepeatMode.Reverse)) else null
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
        Icon(statusIcon(status), color, size * .5f, Modifier.graphicsLayer { alpha = pulse?.value ?: 1f })
    }
}

@Composable
fun StatusPill(status: String) {
    val t = S.kanbanPage
    val color = statusTextColor(status)
    Row(
        Modifier.clip(CircleShape).background(statusColor(status).copy(alpha = .14f)).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(statusIcon(status), color, 16.dp)
        Spacer(Modifier.width(6.dp))
        Txt(t.column(status), Theme.type.caption, color, maxLines = 1)
    }
}

/**
 * 76dp card row: the assignee's buddy (or the status), title, who · time · sub-task progress, and one 48dp action
 * (stop / start / done). Tap opens the card; long press = menu (design §3.7).
 */
@Composable
fun TaskRow(task: KTask, spec: BuddySpec?, nowMs: Long, onOpen: () -> Unit, onMenu: () -> Unit, onPrimary: () -> Unit, modifier: Modifier = Modifier) {
    val t = S.kanbanPage
    val s = S.current
    val c = Theme.colors
    val bad = task.failures > 0 && task.status != "done"
    val time = when (task.status) {
        "running" -> task.runStartedAt?.let { t.runFor(ago(it.toDouble(), nowMs, s)) }
        "done" -> task.completedAt?.let { ago(it.toDouble(), nowMs, s) }
        else -> task.createdAt?.let { ago(it.toDouble(), nowMs, s) }
    }
    val sub = listOfNotNull(
        spec?.name ?: t.nobody,
        time?.takeIf { it.isNotBlank() },
        task.totalChildren?.takeIf { it > 0 }?.let { "${task.doneChildren ?: 0}/$it" },
        if (bad) t.failures(task.failures) else null,
    ).joinToString(" · ")
    PressRow(onOpen, modifier, minHeight = 76.dp, onLongPress = onMenu, label = task.title) {
        if (spec != null) Buddy(spec, moodOf(task), 44.dp, interactive = false) else StatusBubble(task.status, 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (task.priority > 0) { Dot(if (task.priority > 1) c.error else c.needsYou, 8.dp); Spacer(Modifier.width(6.dp)) }
                Txt(task.title, Theme.type.headline, if (task.status == "done") c.secondary else c.text, maxLines = 1)
            }
            Txt(sub, Theme.type.sub, if (bad) c.errorText else if (task.status == "running") c.working else c.secondary, maxLines = 1)
        }
        val to = primaryMove(task.status)
        if (to != null && task.status != "done") {
            Spacer(Modifier.width(8.dp))
            val (icon, tint) = when (to) {
                "todo" -> Icons.stop to c.error
                "done" -> Icons.check to c.success
                else -> Icons.playArrow to c.accent
            }
            IconButton(icon, primaryLabel(task.status), onPrimary, tint = tint, fill = c.surface2)
        }
    }
}

/** Words for the one-tap move of a status. */
@Composable
fun primaryLabel(status: String): String {
    val t = S.kanbanPage
    return when (primaryMove(status)) {
        "todo" -> if (status == "running") t.stopWorker else t.reopen
        "done" -> t.markDone
        else -> t.start
    }
}

/** The board's buddy (the team's planner), the board in words, and the boards to switch to, plus a new one. */
@Composable
fun BoardHeader(
    spec: BuddySpec?, mood: Mood, cue: BuddyCue?, title: String, summary: String,
    boards: List<KBoard>, selected: String?, onBuddy: () -> Unit, onPick: (KBoard) -> Unit, onBoardMenu: (KBoard) -> Unit, onNewBoard: () -> Unit,
) {
    val t = S.kanbanPage
    val c = Theme.colors
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (spec != null) { Buddy(spec, mood, 64.dp, cue = cue, onTap = onBuddy); Spacer(Modifier.width(14.dp)) }
            Column(Modifier.weight(1f)) {
                Txt(title, Theme.type.headline, maxLines = 1)
                Txt(summary, Theme.type.sub, c.secondary, maxLines = 2)
            }
        }
        LazyRow(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(boards, key = { "b:" + it.slug }) { b -> BoardChip(b, b.slug == selected, { onPick(b) }, { onBoardMenu(b) }) }
            item(key = "b:+") { IconButton(Icons.add, t.newBoard, onNewBoard, fill = c.surface2, tint = c.text) }
        }
    }
}

@Composable
private fun BoardChip(b: KBoard, selected: Boolean, onClick: () -> Unit, onLongPress: () -> Unit) {
    val c = Theme.colors
    Box(Modifier.heightIn(min = 48.dp).squish(onClick, haptic = Haptic.Tick, onLongPress = onLongPress, label = b.name), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(40.dp).clip(CircleShape).background(if (selected) c.accentFill else c.surface2).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (b.current) { Icon(Icons.pushPin, if (selected) c.onAccent else c.secondary, 16.dp); Spacer(Modifier.width(6.dp)) }
            Txt(b.name, Theme.type.caption.copy(fontSize = Theme.type.sub.fontSize), if (selected) c.onAccent else c.text, maxLines = 1)
            if (b.total > 0) { Spacer(Modifier.width(6.dp)); Txt("${b.total}", Theme.type.micro, if (selected) c.onAccent.copy(alpha = .8f) else c.secondary, maxLines = 1) }
        }
    }
}

/** All, then each column that has cards (and the selected one), with counts. */
@Composable
fun ColumnChips(columns: List<Pair<String, Int>>, selected: String?, onSelect: (String?) -> Unit) {
    val t = S.kanbanPage
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "all") { Chip(t.all, selected == null, { onSelect(null) }) }
        items(columns.filter { it.second > 0 || it.first == selected }, key = { it.first }) { (name, n) ->
            Chip("${t.column(name)} $n", selected == name, { onSelect(name) }, icon = statusIcon(name))
        }
    }
}

/** Empty, error and unsupported states: one line and at most one button; the buddy above carries the feeling. */
@Composable
fun KMessage(text: String, action: String? = null, icon: ImageVector? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(text, Theme.type.title2.copy(textAlign = TextAlign.Center), Theme.colors.secondary)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            PillButton(action, onAction, icon = icon, kind = ButtonKind.Primary)
        }
    }
}

/** 56dp menu row without its own side padding (sheets pad their content). */
@Composable
fun MenuRow(icon: ImageVector, label: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Theme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(Radius.row.dp))
            .squish(onClick, scale = .97f, enabled = enabled, haptic = if (danger) Haptic.Reject else Haptic.Press, label = label)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (!enabled) c.tertiary else if (danger) c.error else c.text
        Icon(icon, tint)
        Spacer(Modifier.width(16.dp))
        Txt(label, Theme.type.body, if (!enabled) c.tertiary else if (danger) c.errorText else c.text, maxLines = 1)
    }
}

/** Menu row in a sheet that pads its own sides. */
@Composable
fun PaddedMenuRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) =
    Box(Modifier.padding(horizontal = 16.dp)) { MenuRow(icon, label, danger, onClick = onClick) }

/** Section caption inside a padded sheet. */
@Composable
fun Head(text: String, color: Color = Theme.colors.secondary) = Txt(text.uppercase(), Theme.type.caps, color, maxLines = 1)

/** Pick a bot (64dp buddies); [allowNobody] puts "Nobody" first. The chosen one grows and perks up. */
@Composable
fun WhoPicker(crew: List<BuddySpec>, selected: String?, allowNobody: Boolean = true, onSelect: (String?) -> Unit) {
    val t = S.kanbanPage
    val c = Theme.colors
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (allowNobody) item(key = "nobody") {
            PickTile(t.nobody, selected == null, { onSelect(null) }) { sel ->
                val size by animateDpAsState(if (sel) 56.dp else 44.dp, Motion.bouncy())
                Box(Modifier.size(size).clip(CircleShape).background(c.surface2), contentAlignment = Alignment.Center) { Icon(Icons.close, c.secondary, 22.dp) }
            }
        }
        items(crew, key = { it.id }) { b ->
            PickTile(b.name, b.id == selected, { onSelect(b.id) }) { sel ->
                val size by animateDpAsState(if (sel) 56.dp else 44.dp, Motion.bouncy())
                Buddy(b, if (sel) Mood.Listening else Mood.Idle, size, interactive = false)
            }
        }
    }
}

@Composable
private fun PickTile(name: String, selected: Boolean, onClick: () -> Unit, face: @Composable (Boolean) -> Unit) {
    val c = Theme.colors
    Column(
        Modifier.width(80.dp).squish(onClick, haptic = Haptic.Tick, label = name)
            .clip(RoundedCornerShape(Radius.row.dp)).background(if (selected) c.surface2 else Color.Transparent).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(60.dp), contentAlignment = Alignment.BottomCenter) { face(selected) }
        Spacer(Modifier.height(4.dp))
        Txt(name, Theme.type.caption, if (selected) c.accent else c.secondary, maxLines = 1)
    }
}

/** Priority is a tiebreaker (higher runs first): Normal 0, High 1, Top 2. */
@Composable
fun PriorityChips(value: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val t = S.kanbanPage
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0 to t.prioNormal, 1 to t.prioHigh, 2 to t.prioTop).forEach { (p, label) ->
            Chip(label, value.coerceIn(0, 2) == p, { onSelect(p) })
        }
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Theme.type.body, modifier = Modifier.weight(1f), maxLines = 2)
        Toggle(checked, onChange, label)
    }
}

/** A linked card (parent or sub-task): tap opens it. */
@Composable
fun LinkRow(link: KLink, onOpen: () -> Unit) {
    val c = Theme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(Radius.row.dp)).squish(onOpen, scale = .97f, label = link.title)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusBubble(link.status, 32.dp)
        Spacer(Modifier.width(12.dp))
        Txt(link.title, Theme.type.callout, if (link.status == "done") c.secondary else c.text, Modifier.weight(1f), maxLines = 1)
        Icon(Icons.chevronRight, c.tertiary, 22.dp, mirror = true)
    }
}
