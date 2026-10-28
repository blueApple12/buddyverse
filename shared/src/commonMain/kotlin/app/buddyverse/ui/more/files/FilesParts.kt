package app.buddyverse.ui.more.files

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.core.api.FileEntry
import app.buddyverse.core.nowMillis
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Chip
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.Radius
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.filesPage
import app.buddyverse.ui.ago
import kotlinx.coroutines.delay

/** Shows [content] only after [ms]: nothing flashes for quick loads (design rule 3: no spinners under 300 ms). */
@Composable
internal fun Delayed(ms: Long = 300, content: @Composable () -> Unit) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(ms); show = true }
    if (show) content()
}

/** Keeps the last non-null value so a sheet's content stays put while it slides away. */
@Composable
internal fun <T : Any> rememberLast(value: T?): T? {
    val box = remember { arrayOfNulls<Any>(1) }
    if (value != null) box[0] = value
    @Suppress("UNCHECKED_CAST")
    return box[0] as T?
}

/** Whose folder: one buddy per bot; tapping one jumps to that bot's workspace. The picked one shows the page's mood and cues. */
@Composable
internal fun FilesCrew(crew: List<BuddySpec>, selected: String, mood: Mood, cue: BuddyCue?, onSelect: (String) -> Unit) {
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

/** Up button, then the path as tappable crumbs. Paths always read left to right (design §9.5), so the crumbs never mirror. */
@Composable
internal fun PathBar(path: String, canUp: Boolean, onUp: () -> Unit, onOpen: (String) -> Unit) {
    val t = S.filesPage
    val parts = remember(path) { crumbs(path) }
    val state = rememberLazyListState()
    LaunchedEffect(parts.size, path) { if (parts.isNotEmpty()) state.animateScrollToItem(parts.lastIndex) }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(Icons.arrowUpward, t.up, onUp, enabled = canUp)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            LazyRow(Modifier.weight(1f), state = state, verticalAlignment = Alignment.CenterVertically) {
                itemsIndexed(parts, key = { _, p -> p.second }) { i, (label, full) ->
                    val last = i == parts.lastIndex
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (i > 0) Icon(Icons.chevronRight, Theme.colors.tertiary, 16.dp)
                        Box(
                            Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                                .squish({ if (!last) onOpen(full) }, haptic = if (last) null else Haptic.Tick, label = label)
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Txt(
                                label,
                                if (last) Theme.type.callout.copy(fontWeight = FontWeight.SemiBold) else Theme.type.callout,
                                if (last) Theme.colors.text else Theme.colors.secondary, maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Find-in-folder field, and the Hidden chip when this folder has hidden things. */
@Composable
internal fun FindRow(query: String, onQuery: (String) -> Unit, hiddenCount: Int, showHidden: Boolean, onHidden: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Field(query, onQuery, S.filesPage.filter, Modifier.weight(1f))
        if (hiddenCount > 0) {
            Spacer(Modifier.width(8.dp))
            Chip("${S.filesPage.hidden} $hiddenCount", showHidden, onHidden, icon = if (showHidden) Icons.visibility else FileIcons.hidden)
        }
    }
}

internal val Kind.icon: ImageVector
    get() = when (this) {
        Kind.Folder -> Icons.folder
        Kind.Image -> Icons.image
        Kind.Code -> FileIcons.code
        Kind.Pdf -> FileIcons.pdf
        Kind.Video -> FileIcons.movie
        Kind.Audio -> FileIcons.music
        Kind.Text, Kind.Other -> Icons.description
    }

@Composable
internal fun FileGlyph(kind: Kind, size: Int = 44) {
    val c = Theme.colors
    val folder = kind == Kind.Folder
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)).background(if (folder) c.accent.copy(alpha = .14f) else c.surface2),
        contentAlignment = Alignment.Center,
    ) { Icon(kind.icon, if (folder) c.accent else c.secondary, (size * .55f).dp) }
}

/**
 * 64dp row (design §3.7): glyph, name, one line of size and age. Its one visible control: a folder's chevron (the row opens it)
 * or a file's 48dp download, which turns into a progress ring while it runs. Long press = menu.
 */
@Composable
internal fun FileRow(
    e: FileEntry,
    progress: Float?,
    uploading: Boolean,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Theme.colors
    val t = S.filesPage
    val s = S.current
    val meta = when {
        uploading -> t.uploading
        progress != null -> "${t.downloading} ${(progress * 100).toInt()}%"
        e.broken -> t.brokenLink
        else -> listOfNotNull(
            if (e.isDir) null else e.size?.let(::sizeText),
            e.mtime?.let { ago(it, nowMillis(), s) }?.takeIf { it.isNotEmpty() },
        ).joinToString(" · ").ifEmpty { if (e.isDir) t.folder else "" }
    }
    PressRow(onOpen, modifier.graphicsLayer { alpha = if (uploading) .6f else 1f }, minHeight = 64.dp, onLongPress = onMenu, label = e.name) {
        FileGlyph(e.kind)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(e.name, Theme.type.headline, if (e.broken) c.secondary else c.text, maxLines = 1)
            if (meta.isNotEmpty()) Txt(meta, Theme.type.sub, c.secondary, maxLines = 1)
        }
        Spacer(Modifier.width(4.dp))
        when {
            e.isDir -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(Icons.chevronRight, c.tertiary, 24.dp, mirror = true) }
            !e.broken && !uploading -> DownloadButton(progress, onDownload)
        }
    }
}

/** 48dp download; while running, a ring fills around it (a short arc spins until the size is known). */
@Composable
internal fun DownloadButton(progress: Float?, onClick: () -> Unit) {
    val c = Theme.colors
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        IconButton(FileIcons.download, S.filesPage.download, onClick, tint = if (progress != null) c.accent else c.secondary, enabled = progress == null)
        if (progress != null) {
            val p by animateFloatAsState(progress, Motion.smooth())
            val spin by rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart))
            Canvas(Modifier.size(38.dp)) {
                val w = 3.dp.toPx()
                drawArc(c.hairline, 0f, 360f, false, style = Stroke(w))
                if (p < .02f) drawArc(c.accent, spin, 50f, false, style = Stroke(w, cap = StrokeCap.Round))
                else drawArc(c.accent, -90f, 360f * p, false, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
    }
}

/** An empty folder still has someone in it: the bot, alive, under one plain line. */
@Composable
internal fun EmptyFolder(spec: BuddySpec?, mood: Mood, cue: BuddyCue?, text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (spec != null) {
            Buddy(spec, mood, 88.dp, cue = cue)
            Spacer(Modifier.height(14.dp))
        }
        Txt(text, Theme.type.callout, Theme.colors.secondary)
    }
}

@Composable
internal fun ErrorLine(text: String, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.error, Theme.colors.error)
        Spacer(Modifier.width(12.dp))
        Txt(text, Theme.type.sub, Theme.colors.errorText, Modifier.weight(1f), maxLines = 3)
        Spacer(Modifier.width(8.dp))
        PillButton(S.current.retry, onRetry, height = 48.dp, kind = ButtonKind.Quiet)
    }
}

@Composable
internal fun LoadingLine() = Txt(S.current.loading, Theme.type.sub, Theme.colors.secondary, Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
