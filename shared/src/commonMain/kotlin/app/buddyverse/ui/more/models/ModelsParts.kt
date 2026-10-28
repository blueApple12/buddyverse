package app.buddyverse.ui.more.models

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.Bot
import app.buddyverse.core.nowMillis
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Dot
import app.buddyverse.design.Icon
import app.buddyverse.design.Icons
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.ui.ltr

/** Only cues younger than 2 s play (serials are timestamps), so a freshly composed buddy never replays an old one. */
fun BuddyCue?.fresh(): BuddyCue? = this?.takeIf { nowMillis() - it.serial < 2_000 }

/** The chosen bot as a living buddy with a one-line summary, plus the other bots to switch to (models belong to a bot). */
@Composable
fun BotStrip(bots: List<Bot>, selected: String, mood: Mood, cue: BuddyCue?, summary: String, onPick: (String) -> Unit) {
    val c = Theme.colors
    val me = bots.firstOrNull { it.profile == selected }
    val others = bots.filter { it.profile != selected && !it.hidden }
    var hop by remember { mutableStateOf<BuddyCue?>(null) }
    val shown = listOfNotNull(cue, hop).maxByOrNull { it.serial }.fresh()
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (me != null) { Buddy(me.spec, mood, 64.dp, cue = shown); Spacer(Modifier.width(14.dp)) }
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

/** Loading inside a sheet: a living buddy, never a spinner (design rule 1). */
@Composable
fun Thinking(bot: Bot?, modifier: Modifier = Modifier, size: Dp = 72.dp) {
    Box(modifier.fillMaxWidth().heightIn(min = 140.dp), contentAlignment = Alignment.Center) {
        if (bot != null) Buddy(bot.spec, Mood.Thinking, size)
    }
}

/** Empty, error and unsupported states: one line and at most one button; the buddy above carries the feeling. */
@Composable
fun PageMessage(text: String, action: String? = null, icon: ImageVector? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(text, Theme.type.title2.copy(textAlign = TextAlign.Center), Theme.colors.secondary)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            PillButton(action, onAction, icon = icon, kind = ButtonKind.Primary)
        }
    }
}

/** "+ Add …" row at the end of a section. */
@Composable
fun AddRow(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector = Icons.add) {
    val c = Theme.colors
    PressRow(onClick, modifier, label = text) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.surface2), contentAlignment = Alignment.Center) { Icon(icon, c.accent, 22.dp) }
        Spacer(Modifier.width(14.dp))
        Txt(text, Theme.type.headline, c.accent, Modifier.weight(1f), maxLines = 1)
    }
}

/** Status dot that breathes while [busy]. */
@Composable
fun LiveDot(color: Color, busy: Boolean = false, size: Dp = 12.dp) {
    val tint by animateColorAsState(color, Motion.fade())
    val pulse = if (busy) rememberInfiniteTransition().animateFloat(1f, .35f, infiniteRepeatable(tween(600), RepeatMode.Reverse)) else null
    Dot(tint, size, Modifier.graphicsLayer { alpha = pulse?.value ?: 1f })
}

/** Small capsule label (In use, Recommended, Loaded…). */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(color.copy(alpha = .16f)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Txt(text, Theme.type.micro, color, maxLines = 1)
    }
}

/** Springy progress bar; null [fraction] = unknown, the bar breathes instead. */
@Composable
fun Meter(fraction: Float?, color: Color, modifier: Modifier = Modifier) {
    val c = Theme.colors
    val f by animateFloatAsState((fraction ?: .35f).coerceIn(0f, 1f), Motion.smooth())
    val pulse = if (fraction == null) rememberInfiniteTransition().animateFloat(1f, .4f, infiniteRepeatable(tween(700), RepeatMode.Reverse)) else null
    Box(modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.surface2)) {
        Box(Modifier.fillMaxWidth(f).height(8.dp).clip(CircleShape).background(color).graphicsLayer { alpha = pulse?.value ?: 1f })
    }
}

/** One quota window (e.g. "Current week · 40%"); amber from 80%. */
@Composable
fun UsageLine(label: String, percent: Int, resets: String?) {
    val c = Theme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(label, Theme.type.sub, modifier = Modifier.weight(1f), maxLines = 1)
            Txt(ltr("$percent%"), Theme.type.caption, if (percent >= 80) c.needsYouText else c.secondary, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        Meter(percent / 100f, if (percent >= 80) c.needsYou else c.working)
        if (resets != null) Txt(resets, Theme.type.micro, c.secondary, Modifier.padding(top = 2.dp), maxLines = 1)
    }
}

/** A two-line row: [title] (mono when it's a model or key id), [sub] under it, one trailing slot. */
@Composable
fun InfoRow(
    title: String,
    sub: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    lead: (@Composable () -> Unit)? = null,
    subColor: Color = Theme.colors.secondary,
    onLongPress: (() -> Unit)? = null,
    trail: (@Composable () -> Unit)? = null,
) {
    PressRow(onClick, modifier, minHeight = 64.dp, onLongPress = onLongPress, label = title) {
        if (lead != null) { lead(); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Txt(if (mono) ltr(title) else title, if (mono) Theme.type.code.copy(fontSize = Theme.type.sub.fontSize) else Theme.type.headline, maxLines = 1)
            if (!sub.isNullOrBlank()) Txt(sub, Theme.type.sub, subColor, maxLines = 2)
        }
        if (trail != null) { Spacer(Modifier.width(8.dp)); trail() }
    }
}
