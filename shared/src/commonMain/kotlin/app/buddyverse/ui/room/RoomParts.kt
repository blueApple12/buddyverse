package app.buddyverse.ui.room

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.core.Bot
import app.buddyverse.core.api.RoomAction
import app.buddyverse.core.api.RoomFile
import app.buddyverse.core.api.RoomSeat
import app.buddyverse.core.nowMillis
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Dot
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.room
import app.buddyverse.ui.chat.MdText
import app.buddyverse.ui.chat.MediaFiles
import app.buddyverse.ui.chat.splitMedia
import app.buddyverse.ui.chat.Spinner
import app.buddyverse.ui.chat.elapsedText
import app.buddyverse.ui.ltr
import kotlinx.coroutines.delay

// ------------------------------------------------------------------ looks and words

/** A member's look: the bot's own buddy, or a stand-in seeded by its profile (same rule as the chat screen). */
internal fun specOf(profile: String, bots: List<Bot>): BuddySpec =
    bots.firstOrNull { it.profile == profile }?.spec ?: BuddySpec(profile, profile, BuddyShape.parse(null, profile), BuddyColors.forName(profile))

/** A bot's name drawn in its own color, kept readable on the ground. */
internal fun nameColor(body: Color, dark: Boolean): Color = BuddyColors.mix(body, if (dark) Color.White else Color.Black, .3f)

/** A member's live mood: asking you > working > queued (thinking) > failed > listening > idle. */
internal fun RoomModel.moodOf(memberId: String, failed: Set<String>, typing: Boolean): Mood {
    val act = driver?.activity?.get(memberId)
    return when {
        actions.any { it.memberId == memberId && it.kind == "approval" } -> Mood.NeedsYou
        act == "running" || act == "stopping" -> Mood.Working
        act == "queued" -> Mood.Thinking
        memberId in failed -> Mood.Error
        gone -> Mood.Sleeping
        typing -> Mood.Listening
        else -> Mood.Idle
    }
}

internal fun fileSize(b: Long): String = when {
    b >= 1_048_576 -> "${(b * 10 / 1_048_576) / 10.0} MB"
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}

// ------------------------------------------------------------------ crew strip

/** Design §3.3 crew strip (88dp): buddies 52 with names; the busy one is lifted 6dp. Tap = profile, long press = @mention. */
@Composable
internal fun CrewStrip(
    seats: List<RoomSeat>, spec: (RoomSeat) -> BuddySpec, name: (RoomSeat) -> String, mood: (RoomSeat) -> Mood,
    talk: (RoomSeat) -> Pair<Long, Int>, typing: Boolean, onTap: (RoomSeat) -> Unit, onLong: (RoomSeat) -> Unit,
) {
    val c = Theme.colors
    Row(
        Modifier.fillMaxWidth().height(88.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (seat in seats) key(seat.memberId) {
            val m = mood(seat)
            val lift by animateDpAsState(if (m == Mood.Working || m == Mood.NeedsYou) (-6).dp else 0.dp, Motion.bouncy())
            val (at, chars) = talk(seat)
            val sp = spec(seat)
            Column(Modifier.width(76.dp).offset(y = lift), horizontalAlignment = Alignment.CenterHorizontally) {
                Buddy(sp, m, 52.dp, talkAt = at, talkChars = chars, lookAtComposer = typing, onTap = { onTap(seat) }, onLongPress = { onLong(seat) })
                Spacer(Modifier.height(3.dp))
                Txt(name(seat), Theme.type.micro, if (m == Mood.Working) c.working else c.secondary, maxLines = 1)
            }
        }
    }
}

// ------------------------------------------------------------------ transcript rows

/** A member's reply: a cluster label (buddy 28 + name in its color) on the first of a run; fresh replies grow in. */
@Composable
internal fun MemberBubble(line: MemberLine, spec: BuddySpec, fresh: Boolean, model: RoomModel, onLong: () -> Unit, onFile: (String, RoomFile) -> Unit) {
    val c = Theme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val grow = remember(line.key) { Animatable(if (fresh) 0f else 1f) }
    LaunchedEffect(line.key) { if (grow.value < 1f) grow.animateTo(1f, Motion.replyIn()) }
    Column(Modifier.fillMaxWidth().padding(top = if (line.first) 10.dp else 2.dp, bottom = 2.dp)) {
        if (line.first) Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Buddy(spec, Mood.Idle, 28.dp, interactive = false)
            Spacer(Modifier.width(6.dp))
            Txt(line.name, Theme.type.caption, nameColor(spec.color, c.dark), maxLines = 1)
        }
        Column(
            Modifier.padding(start = 34.dp, end = 28.dp).widthIn(max = 620.dp)
                .graphicsLayer {
                    val p = grow.value
                    scaleX = .84f + .16f * p; scaleY = .84f + .16f * p
                    translationY = (1f - p) * 8.dp.toPx()
                    alpha = p
                    transformOrigin = TransformOrigin(if (rtl) 1f else 0f, 0f)
                }
                .clip(RoundedCornerShape(22.dp)).background(c.surface2)
                .squish({}, scale = .97f, haptic = null, onLongPress = onLong)
                .animateContentSize(Motion.smooth())
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            FileList(line.files, line.eventId, model, onAccent = false, onFile = onFile)
            val media = remember(line.text) { splitMedia(line.text) }
            if (media.text.isNotBlank()) MdText(media.text, Theme.type.body, c.text, c.ground)
            MediaFiles(media.paths, line.profile)
        }
    }
}

/** The user's message: accent bubble on the end side; a send still on its way has a dot, a failed one Retry and Delete. */
@Composable
internal fun RoomUserBubble(line: UserLine, model: RoomModel, onLong: () -> Unit, onFile: (String, RoomFile) -> Unit) {
    val c = Theme.colors
    val s = S.current
    val out = line.out
    val failed = out?.failed != null
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalAlignment = Alignment.End) {
        Box(Modifier.fillMaxWidth(.8f), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.clip(RoundedCornerShape(22.dp)).background(if (failed) c.error else c.accentFill)
                    .squish({}, scale = .97f, haptic = null, onLongPress = onLong).padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                if (out != null) {
                    for (a in out.files) Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (a.mime.startsWith("image/")) Icons.image else Icons.attachFile, c.onAccent, 16.dp)
                        Spacer(Modifier.width(4.dp))
                        Txt(a.name, Theme.type.caption, c.onAccent, maxLines = 1)
                    }
                } else FileList(line.files, line.eventId.orEmpty(), model, onAccent = true, onFile = onFile)
                if (line.text.isNotBlank()) MdText(line.text, Theme.type.body, c.onAccent, Color.Black.copy(alpha = .18f))
            }
        }
        when {
            out == null -> Unit
            failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(out.failed.orEmpty(), Theme.type.micro, c.errorText, Modifier.widthIn(max = 220.dp), maxLines = 2)
                IconButton(Icons.refresh, s.retrySend, { model.retry(out) }, tint = c.errorText)
                IconButton(Icons.delete, s.delete, { model.discard(out) }, tint = c.secondary)
            }
            else -> Dot(c.tertiary, 6.dp, Modifier.padding(top = 5.dp, end = 12.dp))
        }
    }
}

/** A notice in the middle: failed, stopped, put off, away, you stopped, closed, PC lost the group, work limit. */
@Composable
internal fun RoomNotice(n: NoticeLine) {
    val c = Theme.colors
    val sr = S.room
    val names = n.names.joinToString(", ").ifBlank { "…" }
    val text = when (n.kind) {
        "turn.failed" -> sr.failedNotice(names)
        "turn.cancelled" -> sr.stoppedNotice(names)
        "turn.deferred" -> sr.deferredNotice(names)
        "member.unavailable" -> sr.awayNotice(names)
        "room.stop_requested" -> sr.stopRequested
        "room.disbanded" -> sr.disbandedNotice
        "authority.lost" -> sr.authorityLost
        else -> sr.bounded
    } + (n.detail?.let { " · $it" } ?: "")
    val tone = when (n.kind) {
        "turn.failed", "member.unavailable", "authority.lost" -> c.errorText
        "turn.deferred" -> c.needsYouText
        else -> c.secondary
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.clip(RoundedCornerShape(16.dp)).background(c.surface2).padding(horizontal = 14.dp, vertical = 8.dp)) {
            Txt(text, Theme.type.caption, tone, maxLines = 3)
        }
    }
}

/** A message's files: small images previewed (≤ 5 MB), the rest as chips; tap saves to Downloads. */
@Composable
private fun ColumnScope.FileList(files: List<RoomFile>, eventId: String, model: RoomModel, onAccent: Boolean, onFile: (String, RoomFile) -> Unit) {
    for (f in files) {
        if (f.kind == "image" && f.size <= 5L * 1024 * 1024 && eventId.isNotEmpty()) RoomImage(f, eventId, model) { onFile(eventId, f) }
        else FileChip(f, onAccent) { onFile(eventId, f) }
    }
}

@Composable
private fun RoomImage(f: RoomFile, eventId: String, model: RoomModel, onClick: () -> Unit) {
    val c = Theme.colors
    var img by remember(f.attachmentId) { mutableStateOf(model.images[f.attachmentId]) }
    var tried by remember(f.attachmentId) { mutableStateOf(img != null) }
    LaunchedEffect(f.attachmentId) { if (img == null) { img = model.preview(eventId, f); tried = true } }
    Box(
        Modifier.padding(vertical = 4.dp).size(220.dp, 160.dp).clip(RoundedCornerShape(16.dp)).background(c.surface)
            .squish(onClick, scale = .97f, label = f.name),
        contentAlignment = Alignment.Center,
    ) {
        val b = img
        when {
            b != null -> Image(b, f.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            tried -> Icon(Icons.image, c.tertiary, 32.dp)
            else -> Spinner(20.dp, c.tertiary)
        }
    }
}

@Composable
private fun FileChip(f: RoomFile, onAccent: Boolean, onClick: () -> Unit) {
    val c = Theme.colors
    val fg = if (onAccent) c.onAccent else c.text
    Row(
        Modifier.padding(vertical = 3.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
            .background(if (onAccent) Color.Black.copy(alpha = .18f) else c.surface)
            .squish(onClick, scale = .97f, label = f.name).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (f.kind == "pdf") Icons.description else Icons.attachFile, fg, 20.dp)
        Spacer(Modifier.width(8.dp))
        Txt(f.name, Theme.type.callout, fg, Modifier.widthIn(max = 200.dp), maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Txt(ltr(fileSize(f.size)), Theme.type.micro, if (onAccent) c.onAccent.copy(alpha = .8f) else c.secondary, maxLines = 1)
    }
}

// ------------------------------------------------------------------ pending actions

/**
 * One pending action of the room (hermes-api.md "Approvals, retry, discard inside a room"), amber leading edge:
 * approval = Deny / Allow (once); deferred turn = Retry; unknown result = Discard after an inline confirm.
 */
@Composable
internal fun ActionCard(a: RoomAction, name: String, spec: BuddySpec, onAllow: () -> Unit, onDeny: () -> Unit, onRetry: () -> Unit, onDiscard: () -> Unit) {
    val c = Theme.colors
    val s = S.current
    val sr = S.room
    var confirm by remember(a.key) { mutableStateOf(false) }
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(20.dp)).background(c.surface2)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(c.needsYou))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Buddy(spec, if (a.kind == "approval") Mood.NeedsYou else Mood.Idle, 28.dp, interactive = false)
                Spacer(Modifier.width(8.dp))
                val title = when (a.kind) {
                    "approval" -> "$name ${s.wantsToRun}"
                    "retry" -> sr.putOff(name)
                    else -> sr.unknownResult(name)
                }
                Txt(title, Theme.type.headline, c.text, Modifier.weight(1f), maxLines = 2)
            }
            when (a.kind) {
                "approval" -> {
                    a.command?.takeIf { it.isNotBlank() }?.let { cmd ->
                        Box(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.ground).horizontalScroll(rememberScrollState()).padding(10.dp)) {
                            Txt(cmd, Theme.type.code, c.text)
                        }
                    }
                    a.description?.takeIf { it.isNotBlank() && it != a.command }?.let { Txt(it, Theme.type.sub, c.secondary, Modifier.padding(top = 6.dp), maxLines = 3) }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(s.deny, onDeny, Modifier.widthIn(min = 120.dp), kind = ButtonKind.Outline, height = 48.dp, haptic = Haptic.Reject)
                        PillButton(s.allow, onAllow, Modifier.widthIn(min = 120.dp), height = 48.dp, haptic = Haptic.Confirm)
                    }
                }
                "retry" -> PillButton(s.retrySend, onRetry, Modifier.padding(top = 10.dp).widthIn(min = 120.dp), kind = ButtonKind.Quiet, height = 48.dp, icon = Icons.refresh)
                else -> if (!confirm) {
                    PillButton(sr.discard, { confirm = true }, Modifier.padding(top = 10.dp).widthIn(min = 120.dp), kind = ButtonKind.Outline, height = 48.dp)
                } else {
                    Txt(sr.discardAsk, Theme.type.sub, c.text, Modifier.padding(top = 8.dp))
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(sr.discard, onDiscard, Modifier.widthIn(min = 120.dp), kind = ButtonKind.Danger, height = 48.dp, haptic = Haptic.Reject)
                        PillButton(s.cancel, { confirm = false }, Modifier.widthIn(min = 120.dp), kind = ButtonKind.Quiet, height = 48.dp)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ presence

/** Design §3.3 presence line: the huddle of busy buddies and "<name> is working · 20s"; tap (or the eye) to watch. */
@Composable
internal fun PresenceLine(
    working: List<RoomSeat>, queued: List<RoomSeat>, teamWorking: Boolean, since: (String) -> Long?,
    spec: (RoomSeat) -> BuddySpec, name: (RoomSeat) -> String, onWatch: () -> Unit,
) {
    val c = Theme.colors
    val sr = S.room
    AnimatedVisibility(
        working.isNotEmpty() || queued.isNotEmpty() || teamWorking,
        enter = fadeIn(Motion.fade()) + expandVertically(Motion.smooth()), exit = fadeOut(Motion.fade()) + shrinkVertically(Motion.smooth()),
    ) {
        val now by produceState(nowMillis()) { while (true) { delay(1_000); value = nowMillis() } }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).squish(onWatch, scale = .98f, enabled = working.isNotEmpty(), label = sr.watch)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                working.ifEmpty { queued }.take(3).forEach { seat ->
                    key(seat.memberId) { Buddy(spec(seat), if (seat in working) Mood.Working else Mood.Thinking, 28.dp, interactive = false) }
                }
            }
            Spacer(Modifier.width(10.dp))
            val text = when {
                working.size == 1 -> sr.isWorking(name(working[0])) + (since(working[0].memberId)?.let { " · " + ltr(elapsedText(now - it, S.current)) } ?: "")
                working.size > 1 -> sr.areWorking(working.joinToString(", ") { name(it) })
                queued.isNotEmpty() -> sr.upNext(name(queued[0]))
                else -> sr.teamWorking
            }
            Txt(text, Theme.type.sub, c.working, Modifier.weight(1f), maxLines = 1)
            if (working.isNotEmpty()) IconButton(Icons.visibility, sr.watch, onWatch, tint = c.secondary)
        }
    }
}
