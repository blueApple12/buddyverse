package app.buddyverse.ui.more.artifacts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.buddyverse.core.api.FileEntry
import app.buddyverse.core.api.artifactFilePath
import app.buddyverse.core.nowMillis
import app.buddyverse.design.Chip
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.Motion
import app.buddyverse.design.PressRow
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.ArtifactsPageStrings
import app.buddyverse.i18n.S
import app.buddyverse.i18n.artifactsPage
import app.buddyverse.ui.ago
import app.buddyverse.ui.more.files.DownloadButton
import app.buddyverse.ui.more.files.FileGlyph
import app.buddyverse.ui.more.files.FileIcons
import app.buddyverse.ui.more.files.kind

/** A path on the PC (not a web address or an inline image). */
internal val Artifact.onPc: Boolean get() = kind != ArtifactKind.Link && kind != ArtifactKind.Made && !value.startsWith("http://") &&
    !value.startsWith("https://") && !value.startsWith("data:")

/** The artifact as a Files entry, for the shared viewer and downloads. */
internal fun Artifact.entry(): FileEntry = FileEntry(label, artifactFilePath(value), isDir = false)

internal fun ArtifactsPageStrings.kindLabel(k: ArtifactKind?): String = when (k) {
    null -> all
    ArtifactKind.Image -> images
    ArtifactKind.File -> files
    ArtifactKind.Link -> links
    ArtifactKind.Made -> made
}

internal fun ArtifactsPageStrings.madeLabel(m: MadeKind?): String = when (m) {
    MadeKind.Html -> page
    MadeKind.Svg -> drawing
    else -> code
}

/** All · Images · Made in chat · Files · Links, each with its count; kinds with nothing in them are left out. */
@Composable
internal fun KindChips(m: ArtifactsModel) {
    val t = S.artifactsPage
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(null, ArtifactKind.Image, ArtifactKind.Made, ArtifactKind.File, ArtifactKind.Link).forEach { k ->
            val n = m.count(k)
            if (k == null || n > 0 || m.kind == k) Chip("${t.kindLabel(k)} $n", m.kind == k, { m.kind = if (m.kind == k) null else k })
        }
    }
}

/** One row of three square image tiles; tiles load (and fade in) as they scroll into view. */
@Composable
internal fun ImageRow(row: List<Artifact>, m: ArtifactsModel, onOpen: (Artifact) -> Unit, onMenu: (Artifact) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        row.forEach { a -> ImageTile(a, m, { onOpen(a) }, { onMenu(a) }, Modifier.weight(1f)) }
        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun ImageTile(a: Artifact, m: ArtifactsModel, onOpen: () -> Unit, onMenu: () -> Unit, modifier: Modifier) {
    val c = Theme.colors
    LaunchedEffect(a.id) { m.loadThumb(a) }
    val img = m.thumbs[a.id]
    val shown by animateFloatAsState(if (img != null) 1f else 0f, Motion.fade())
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(c.surface2)
            .squish(onOpen, scale = .95f, onLongPress = onMenu, label = a.label),
        contentAlignment = Alignment.Center,
    ) {
        if (img != null) Image(img, a.label, Modifier.fillMaxSize().graphicsLayer { alpha = shown }, contentScale = ContentScale.Crop)
        else Icon(Icons.image, if (a.id in m.thumbs) c.tertiary.copy(alpha = .5f) else c.tertiary, 28.dp)
    }
}

@Composable
private fun Glyph(icon: ImageVector) {
    val c = Theme.colors
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
        Icon(icon, c.secondary, 24.dp)
    }
}

/**
 * 64dp row for a file, link or made thing: glyph, name, "in <chat> · 3h". Its one visible control: a file's download (a ring
 * while it runs), a link's copy, a made thing's save. Tap opens, long press = menu.
 */
@Composable
internal fun ArtifactRow(
    a: Artifact,
    progress: Float?,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = S.artifactsPage
    val s = S.current
    val c = Theme.colors
    val title = a.sessionTitle.ifBlank { t.untitled }
    val meta = listOfNotNull(
        if (a.kind == ArtifactKind.Made) "${t.madeLabel(a.made)} · ${t.lines(a.value.count { it == '\n' } + 1)}" else null,
        t.inChat(title),
        ago(a.timestampMs / 1000.0, nowMillis(), s).takeIf { it.isNotEmpty() },
    ).joinToString(" · ")
    PressRow(onOpen, modifier, minHeight = 64.dp, onLongPress = onMenu, label = a.label) {
        when (a.kind) {
            ArtifactKind.File, ArtifactKind.Image -> FileGlyph(a.entry().kind)
            ArtifactKind.Link -> Glyph(Icons.link)
            ArtifactKind.Made -> Glyph(
                when (a.made) {
                    MadeKind.Html -> Icons.language
                    MadeKind.Svg -> Icons.palette
                    else -> FileIcons.code
                },
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(a.label, Theme.type.headline, maxLines = 1)
            Txt(meta, Theme.type.sub, c.secondary, maxLines = 1)
        }
        Spacer(Modifier.width(4.dp))
        when (a.kind) {
            ArtifactKind.File, ArtifactKind.Image -> if (a.onPc) DownloadButton(progress, onAction)
                else IconButton(Icons.contentCopy, t.copyLink, onAction, tint = c.secondary)
            ArtifactKind.Link -> IconButton(Icons.contentCopy, t.copyLink, onAction, tint = c.secondary)
            ArtifactKind.Made -> IconButton(FileIcons.download, t.save, onAction, tint = c.secondary)
        }
    }
}

/** Under the hero while a scan runs: how far it got. */
@Composable
internal fun ScanMeter(done: Int, total: Int) {
    app.buddyverse.ui.more.models.Meter(
        if (total > 0) done.toFloat() / total else null, Theme.colors.working,
        Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}
