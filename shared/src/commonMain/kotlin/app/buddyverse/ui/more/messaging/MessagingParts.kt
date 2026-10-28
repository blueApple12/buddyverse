package app.buddyverse.ui.more.messaging

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.LocalMotion
import app.buddyverse.buddy.MotionLevel
import app.buddyverse.design.Dot
import app.buddyverse.design.Motion
import app.buddyverse.design.Palette
import app.buddyverse.design.PressRow
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt

fun Tone.color(c: Palette): Color = when (this) {
    Tone.Good -> c.success
    Tone.Muted -> c.tertiary
    Tone.Bad -> c.error
    Tone.Warn -> c.needsYou
}

/** The apps' own colors, so a row is recognised at a glance (no remote logos: no image loader, no engine-hosted art). */
private val BRAND = mapOf(
    "telegram" to 0xFF2AABEE, "whatsapp" to 0xFF25D366, "whatsapp_cloud" to 0xFF25D366, "discord" to 0xFF5865F2,
    "slack" to 0xFF611F69, "signal" to 0xFF3A76F0, "matrix" to 0xFF0DBD8B, "mattermost" to 0xFF1E325C, "email" to 0xFF6B6F80,
    "sms" to 0xFFF22F46, "teams" to 0xFF5B5FC7, "google_chat" to 0xFF00AC47, "line" to 0xFF06C755, "bluebubbles" to 0xFF1A8CFF,
    "photon" to 0xFF1A8CFF, "feishu" to 0xFF3370FF, "dingtalk" to 0xFF1E89FF, "irc" to 0xFF4B4B4B, "ntfy" to 0xFF338574,
)

fun platformColor(id: String): Color = BRAND[id]?.let { Color(it) } ?: BuddyColors.forName(id)

/** A soft breathing dot for "in progress" (still when Motion is Off). */
@Composable
fun Pulse(color: Color, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    if (LocalMotion.current == MotionLevel.Off) return Dot(color, size, modifier)
    val t = rememberInfiniteTransition()
    val k by t.animateFloat(.6f, 1f, infiniteRepeatable(tween(700, easing = Motion.emphasized), RepeatMode.Reverse))
    Dot(color, size, modifier.graphicsLayer { scaleX = k; scaleY = k; alpha = .45f + k * .55f })
}

/**
 * The app's mark: a candy tile in its own color with its initial, a status dot on the end corner. It bobs while [busy]
 * and pops when it turns connected. Animation values are read in the draw phase only.
 */
@Composable
fun PlatformMark(id: String, name: String, tone: Tone?, busy: Boolean = false, size: Dp = 44.dp) {
    val c = Theme.colors
    val body = platformColor(id)
    val moving = LocalMotion.current != MotionLevel.Off
    val lift = if (busy && moving) rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = Motion.emphasized), RepeatMode.Reverse)) else null
    val pop = remember { Animatable(1f) }
    val last = remember { arrayOf(tone) }
    LaunchedEffect(tone) {
        if (moving && tone == Tone.Good && last[0] != null && last[0] != Tone.Good) { pop.snapTo(.72f); pop.animateTo(1f, Motion.bouncy()) }
        last[0] = tone
    }
    Box(Modifier.size(size).graphicsLayer {
        val l = lift?.value ?: 0f
        translationY = -l * 3.dp.toPx(); rotationZ = if (lift != null) (l - .5f) * 6f else 0f
        scaleX = pop.value; scaleY = pop.value
    }) {
        Box(Modifier.size(size).clip(RoundedCornerShape(size * .32f)).background(body), contentAlignment = Alignment.Center) {
            Txt(name.trim().take(1).uppercase(), Theme.type.headline.copy(fontSize = (size.value * .44f).sp, fontWeight = FontWeight.Bold), BuddyColors.eyeOn(body), maxLines = 1)
        }
        val dot = tone?.color(c)
        if (dot != null) Box(
            Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp).size(14.dp).clip(CircleShape).background(c.surface),
            contentAlignment = Alignment.Center,
        ) { if (busy || tone == Tone.Warn) Pulse(dot, 10.dp) else Dot(dot, 10.dp) }
    }
}

/** A 72dp platform row: mark, name, one line of status, one trailing slot (design §3.7: one visible button per row). */
@Composable
fun PlatformRow(
    id: String,
    name: String,
    subtitle: String?,
    subtitleColor: Color?,
    tone: Tone?,
    busy: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    PressRow(onOpen, modifier, minHeight = 72.dp, label = name) {
        PlatformMark(id, name, tone, busy)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(name, Theme.type.headline, maxLines = 1)
            if (!subtitle.isNullOrBlank()) Txt(subtitle, Theme.type.sub, subtitleColor ?: Theme.colors.secondary, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

/** A 64dp person row: initial in their own color, name, one line under it, one trailing slot. */
@Composable
fun PersonRow(name: String, subtitle: String?, seed: String, onOpen: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val body = BuddyColors.forName(seed)
    PressRow(onOpen, modifier, label = name) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(body), contentAlignment = Alignment.Center) {
            Txt(name.trim().take(1).uppercase(), Theme.type.callout.copy(fontWeight = FontWeight.Bold), BuddyColors.eyeOn(body), maxLines = 1)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(name, Theme.type.headline, maxLines = 1)
            if (!subtitle.isNullOrBlank()) Txt(subtitle, Theme.type.sub, Theme.colors.secondary, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

/** A QR code on a white tile with a quiet zone; never mirrored in RTL (canvas coordinates are physical). Pops when it changes. */
@Composable
fun QrImage(payload: String, side: Dp, label: String, modifier: Modifier = Modifier) {
    val matrix = remember(payload) { QrCode.encode(payload) }
    val pop = remember { Animatable(1f) }
    val moving = LocalMotion.current != MotionLevel.Off
    LaunchedEffect(payload) { if (moving) { pop.snapTo(.94f); pop.animateTo(1f, Motion.bouncy()) } }
    Box(
        modifier.size(side).graphicsLayer { scaleX = pop.value; scaleY = pop.value }.clip(RoundedCornerShape(20.dp)).background(Color.White)
            .padding(side * .07f).semantics { contentDescription = label },
    ) {
        if (matrix != null) Canvas(Modifier.fillMaxSize()) {
            val n = matrix.size
            val cell = this.size.minDimension / n
            for (y in 0 until n) {
                val row = matrix[y]
                for (x in 0 until n) if (row[x]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + .5f, cell + .5f))
            }
        }
    }
}

/** A quiet centred line (empty, error), with an optional action under it. */
@Composable
fun Note(text: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Txt(text, Theme.type.callout, Theme.colors.secondary, maxLines = 3)
        action?.invoke()
    }
}
