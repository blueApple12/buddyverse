package app.buddyverse.ui.chats

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.Bot
import app.buddyverse.core.SessionRow
import app.buddyverse.design.Dot
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.PressRow
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.chats
import app.buddyverse.ui.ago
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One session row (design §3.4): buddy 44, title + time, bot · preview, live mark. Swipe toward start closes it
 * (or restores an archived one); the buddy waves when the swipe arms and hops on long press.
 */
@Composable
internal fun SessionItem(
    row: SessionRow,
    bot: Bot?,
    live: Boolean,
    now: Long,
    snippet: String?,
    swipe: Boolean,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onSwipe: () -> Unit,
    modifier: Modifier = Modifier,
    quick: Boolean = false,
) {
    val s = S.current
    val c = Theme.colors
    var cue by remember { mutableStateOf<BuddyCue?>(null) }
    fun play(shot: OneShot) { cue = BuddyCue((cue?.serial ?: 0L) + 1, shot) }
    val title = row.title ?: s.untitled
    SwipeToAct(
        icon = if (row.archived) Icons.unarchive else Icons.close,
        label = if (row.archived) S.chats.restore else s.close,
        color = if (row.archived) c.accentFill else c.error,
        enabled = swipe,
        open = open,
        onOpenChange = onOpenChange,
        onArmed = { play(if (row.archived) OneShot.Hop else OneShot.Wave) },
        onAct = onSwipe,
        modifier = modifier,
    ) {
        PressRow(onClick, Modifier.background(c.ground), minHeight = 72.dp, onLongPress = { play(OneShot.Hop); onLongPress() }, label = title) {
            Buddy(bot?.spec ?: fallbackSpec(row.profile), if (live) Mood.Working else if (row.archived) Mood.Sleeping else Mood.Idle, 44.dp,
                cue = cue, interactive = false)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(title, Theme.type.headline, modifier = Modifier.weight(1f), maxLines = 1)
                    if (row.pinned && !row.archived) { Spacer(Modifier.width(6.dp)); Icon(Icons.pushPinFilled, c.tertiary, 16.dp) }
                    Spacer(Modifier.width(8.dp))
                    Txt(ago(row.lastActive, now, s), Theme.type.caption, c.secondary, maxLines = 1)
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(bot?.name ?: row.profile, Theme.type.sub, c.secondary, Modifier.widthIn(max = 140.dp), maxLines = 1)
                    // A brain chat outside any project (a quick chat): a small bolt "Temp" tag.
                    if (quick) { Spacer(Modifier.width(6.dp)); Icon(Icons.bolt, c.tertiary, 14.dp); Spacer(Modifier.width(2.dp)); Txt(s.generalChat, Theme.type.micro, c.tertiary, maxLines = 1) }
                    val detail = snippet ?: row.preview
                    if (detail != null) {
                        Txt(" · ", Theme.type.sub, c.tertiary, maxLines = 1)
                        Txt(detail, Theme.type.sub, c.secondary, Modifier.weight(1f), maxLines = 1)
                    } else Spacer(Modifier.weight(1f))
                    if (live) LiveMark()
                }
            }
        }
    }
}

/** Pulsing blue dot + "live": a turn is running in this session (`session.active_list`). */
@Composable
private fun LiveMark() {
    val c = Theme.colors
    val a by rememberInfiniteTransition().animateFloat(.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse))
    Spacer(Modifier.width(8.dp))
    Dot(c.working, 8.dp, Modifier.graphicsLayer { alpha = a })
    Spacer(Modifier.width(4.dp))
    Txt(S.chats.liveNow, Theme.type.caption, c.working, maxLines = 1)
}

/**
 * Swipe toward start (left in English, right in Hebrew) reveals [label] at the end edge (design §3.4, §9.7).
 * Haptic ticks at 35% and 50%; let go past half, or fling, and it fires. A short swipe latches open with a 96dp button.
 * Only the content moves; the drag reads on the still container, so velocity and taps stay true.
 */
@Composable
internal fun SwipeToAct(
    icon: ImageVector,
    label: String,
    color: Color,
    enabled: Boolean,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onArmed: () -> Unit,
    onAct: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val fb = LocalFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val reveal = with(density) { REVEAL.toPx() }
    val fling = with(density) { FLING_DP_PER_S.dp.toPx() }
    val scope = rememberCoroutineScope()
    var width by remember { mutableIntStateOf(0) }
    var pos by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val job = remember { arrayOfNulls<Job>(1) }
    val act by rememberUpdatedState(onAct)
    val armedCb by rememberUpdatedState(onArmed)
    val openCb by rememberUpdatedState(onOpenChange)
    val showBack by remember { derivedStateOf { pos > .5f } }
    val armed by remember { derivedStateOf { width > 0 && pos >= width * COMMIT } }
    val pop by animateFloatAsState(if (armed) 1.3f else 1f, Motion.bouncy())

    fun slide(target: Float, velocity: Float = 0f, smooth: Boolean = false, then: suspend () -> Unit = {}) {
        job[0]?.cancel()
        job[0] = scope.launch {
            animate(pos, target, velocity, if (smooth) Motion.smooth<Float>() else Motion.snappy<Float>()) { x, _ -> pos = x }
            then()
        }
    }
    fun fire() = slide(width.toFloat(), smooth = true) {
        openCb(false)
        act()
        // Still here after the action (e.g. a Live row that stays listed)? Slide back in. Gone rows cancel this with their scope.
        delay(400)
        slide(0f)
    }

    LaunchedEffect(open) { if (!open && !dragging && pos > 0f) slide(0f) }

    val drag = rememberDraggableState { dx ->
        val before = pos
        val next = (pos + if (rtl) dx else -dx).coerceIn(0f, width.toFloat())
        pos = next
        if (before < width * LATCH && next >= width * LATCH) fb.haptic(Haptic.Tick)
        if (before < width * COMMIT && next >= width * COMMIT) { fb.haptic(Haptic.Tick); armedCb() }
    }

    Box(
        modifier
            .onSizeChanged { width = it.width }
            .semantics { if (enabled) customActions = listOf(CustomAccessibilityAction(label) { act(); true }) }
            .draggable(
                drag, Orientation.Horizontal, enabled = enabled,
                onDragStarted = { job[0]?.cancel(); dragging = true },
                onDragStopped = { v ->
                    dragging = false
                    val toward = if (rtl) v else -v
                    when (swipeSettle(pos, width.toFloat(), toward, reveal, fling)) {
                        Settle.Commit -> fire()
                        Settle.Open -> { openCb(true); slide(reveal, toward) }
                        Settle.Back -> { openCb(false); slide(0f, toward) }
                    }
                },
            ),
    ) {
        if (showBack) Row(Modifier.matchParentSize().background(color), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(REVEAL).fillMaxHeight().squish({ fire() }, label = label), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, Color.White, 26.dp, Modifier.graphicsLayer { scaleX = pop; scaleY = pop })
                    Txt(label, Theme.type.micro, Color.White, maxLines = 1)
                }
            }
        }
        Box(Modifier.graphicsLayer { translationX = if (rtl) pos else -pos }) { content() }
    }
}

private val REVEAL = 96.dp
private const val FLING_DP_PER_S = 900
private const val LATCH = .35f
private const val COMMIT = .5f

internal enum class Settle { Back, Open, Commit }

/** Where a released swipe goes. [pos] and [velocity] are measured toward start (px, px/s). */
internal fun swipeSettle(pos: Float, width: Float, velocity: Float, reveal: Float, fling: Float): Settle = when {
    width > 0f && pos >= width * COMMIT -> Settle.Commit
    velocity >= fling && pos >= reveal / 2 -> Settle.Commit
    velocity <= -fling -> Settle.Back
    pos >= reveal * .6f -> Settle.Open
    else -> Settle.Back
}

internal enum class Day { Today, Yesterday, Earlier }

/** Calendar bucket of a row in local time; [offsetMs] = the zone's UTC offset. Future times (clock skew) count as today. */
internal fun dayOf(lastActiveSec: Double, nowMs: Long, offsetMs: Long): Day {
    val day = { ms: Long -> (ms + offsetMs).floorDiv(86_400_000L) }
    val d = day((lastActiveSec * 1000).toLong())
    val today = day(nowMs)
    return when {
        d >= today -> Day.Today
        d == today - 1 -> Day.Yesterday
        else -> Day.Earlier
    }
}

/** The local zone's UTC offset at [atMs] (DST-aware). */
internal expect fun utcOffsetMs(atMs: Long): Long

/** Same look a bot gets before the roster loads (Bot.from without ui_meta). */
internal fun fallbackSpec(profile: String) = BuddySpec(profile, profile, BuddyShape.parse(null, profile), BuddyColors.forName(profile))
