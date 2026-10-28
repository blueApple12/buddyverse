package app.buddyverse.ui.more.connectors

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import app.buddyverse.i18n.ConnectorsPageStrings

fun Status.color(c: Palette): Color? = when (this) {
    Status.Available -> null
    Status.Connecting -> c.working
    Status.Connected -> c.success
    Status.Off -> c.tertiary
    Status.Expired -> c.needsYou
    Status.Broken -> c.error
}

fun Status.label(s: ConnectorsPageStrings): String? = when (this) {
    Status.Available -> null
    Status.Connecting -> s.statusPending
    Status.Connected -> s.statusConnected
    Status.Off -> s.statusOff
    Status.Expired -> s.statusExpired
    Status.Broken -> s.statusFailed
}

/** A soft breathing dot for "in progress" (still when Motion is Off). */
@Composable
fun Pulse(color: Color, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    if (LocalMotion.current == MotionLevel.Off) return Dot(color, size, modifier)
    val t = rememberInfiniteTransition()
    val k by t.animateFloat(.6f, 1f, infiniteRepeatable(tween(700, easing = Motion.emphasized), RepeatMode.Reverse))
    Dot(color, size, modifier.graphicsLayer { scaleX = k; scaleY = k; alpha = .45f + k * .55f })
}

/**
 * The app's mark: a candy tile in a color seeded by its slug with its initial (no remote logos: no image loader, no engine-hosted art).
 * A status dot sits on its end corner; while connecting the tile gently bobs.
 */
@Composable
fun AppMark(slug: String, name: String, status: Status, size: Dp = 44.dp) {
    val c = Theme.colors
    val body = BuddyColors.forName(slug)
    val bob = status == Status.Connecting && LocalMotion.current != MotionLevel.Off
    // Read only in the draw phase: the bob never recomposes the row.
    val lift = if (bob) rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = Motion.emphasized), RepeatMode.Reverse)) else null
    Box(Modifier.size(size)) {
        Box(
            Modifier.size(size).graphicsLayer { val l = lift?.value ?: return@graphicsLayer; translationY = -l * 3.dp.toPx(); rotationZ = (l - .5f) * 6f }
                .clip(RoundedCornerShape(size * .32f)).background(body),
            contentAlignment = Alignment.Center,
        ) {
            Txt(name.trim().take(1).uppercase(), Theme.type.headline.copy(fontSize = (size.value * .44f).sp, fontWeight = FontWeight.Bold), BuddyColors.eyeOn(body), maxLines = 1)
        }
        val dot = status.color(c)
        if (dot != null) Box(
            Modifier.align(Alignment.TopEnd).offset(4.dp, (-4).dp).size(14.dp).clip(CircleShape).background(c.surface),
            contentAlignment = Alignment.Center,
        ) { if (status == Status.Connecting) Pulse(dot, 10.dp) else Dot(dot, 10.dp) }
    }
}

/** A 72dp app row: mark, name, one line of status or description, one trailing slot (design §3.7: one visible button per row). */
@Composable
fun AppRow(
    slug: String,
    name: String,
    subtitle: String?,
    subtitleColor: Color?,
    status: Status,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    PressRow(onOpen, modifier, minHeight = 72.dp, onLongPress = onLongPress, label = name) {
        AppMark(slug, name, status)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(name, Theme.type.headline, maxLines = 1)
            if (!subtitle.isNullOrBlank()) Txt(subtitle, Theme.type.sub, subtitleColor ?: Theme.colors.secondary, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}
