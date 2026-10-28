package app.buddyverse.ui.more.artifacts

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.core.nowMillis
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Haptic
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.PillButton
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.artifactsPage
import app.buddyverse.ui.ago
import app.buddyverse.ui.more.files.Delayed
import app.buddyverse.ui.more.files.FileIcons
import app.buddyverse.ui.more.files.MenuRow
import app.buddyverse.ui.more.files.rememberLast

private fun Artifact.isWeb() = value.startsWith("http://") || value.startsWith("https://")

/** The copy action's words: the source of a made thing, a PC path, or a link. */
@Composable
internal fun copyLabel(a: Artifact): String {
    val t = S.artifactsPage
    return when {
        a.kind == ArtifactKind.Made -> t.copySource
        a.onPc -> t.copyPath
        else -> t.copyLink
    }
}

/** Long-press menu: everything the row or tile doesn't show (design §3.7). */
@Composable
internal fun ArtifactMenu(
    artifact: Artifact?,
    onDismiss: () -> Unit,
    onOpen: (Artifact) -> Unit,
    onDownload: (Artifact) -> Unit,
    onCopy: (Artifact) -> Unit,
    onOpenChat: (Artifact) -> Unit,
) {
    val t = S.artifactsPage
    val s = S.current
    val shown = rememberLast(artifact)
    Sheet(artifact != null, onDismiss, title = shown?.label) {
        val a = shown ?: return@Sheet
        fun act(f: (Artifact) -> Unit): () -> Unit = { onDismiss(); f(a) }
        MenuRow(if (a.kind == ArtifactKind.Link) Icons.link else Icons.visibility, if (a.kind == ArtifactKind.Link) t.openLink else s.open, act(onOpen))
        if (a.onPc) MenuRow(FileIcons.download, t.download, act(onDownload))
        if (a.kind == ArtifactKind.Made) MenuRow(FileIcons.download, t.save, act(onDownload))
        MenuRow(Icons.contentCopy, copyLabel(a), act(onCopy))
        MenuRow(Icons.chat, t.openChat, act(onOpenChat))
    }
}

/** "in <chat> · 3h" under a viewer's content. */
@Composable
private fun Where(a: Artifact) {
    val t = S.artifactsPage
    val s = S.current
    val text = listOfNotNull(t.inChat(a.sessionTitle.ifBlank { t.untitled }), ago(a.timestampMs / 1000.0, nowMillis(), s).takeIf { it.isNotEmpty() })
        .joinToString(" · ")
    Txt(text, Theme.type.caption, Theme.colors.secondary, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), maxLines = 2)
}

/**
 * An image that isn't a file on the PC (a web address or an inline image): shown at viewer size. The bot thinks while it
 * loads and looks upset when it can't be shown; never a spinner, never a frozen frame.
 */
@Composable
internal fun WebImageSheet(
    artifact: Artifact?,
    m: ArtifactsModel,
    spec: BuddySpec?,
    onDismiss: () -> Unit,
    onOpenLink: (Artifact) -> Unit,
    onOpenChat: (Artifact) -> Unit,
) {
    val t = S.artifactsPage
    val c = Theme.colors
    val shown = rememberLast(artifact)
    Sheet(artifact != null, onDismiss, title = shown?.label, heightFraction = .9f) {
        val a = shown ?: return@Sheet
        var img by remember(a.id) { mutableStateOf<ImageBitmap?>(null) }
        var failed by remember(a.id) { mutableStateOf(false) }
        LaunchedEffect(a.id) { m.loadFull(a).onSuccess { img = it }.onFailure { failed = true } }
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(20.dp)).background(c.surface2),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = img
            when {
                bitmap != null -> Image(bitmap, a.label, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
                failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (spec != null) Buddy(spec, Mood.Error, 72.dp)
                    Spacer(Modifier.height(12.dp))
                    Txt(t.cantShow, Theme.type.callout, c.secondary)
                }
                else -> Delayed { if (spec != null) Buddy(spec, Mood.Thinking, 72.dp) }
            }
        }
        Where(a)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (a.isWeb()) PillButton(t.openLink, { onOpenLink(a) }, Modifier.weight(1f), kind = ButtonKind.Outline, icon = Icons.link)
            PillButton(t.openChat, { onOpenChat(a) }, Modifier.weight(1f), kind = ButtonKind.Quiet, icon = Icons.chat)
        }
    }
}

/**
 * A page, drawing or long code a bot wrote into its reply: the source, selectable, with Copy and Save. Saved pages and
 * drawings open in the phone's own browser or viewer (the app never embeds a web view).
 */
@Composable
internal fun MadeSheet(
    artifact: Artifact?,
    onDismiss: () -> Unit,
    onSave: (Artifact) -> Unit,
    onCopy: (Artifact) -> Unit,
    onOpenChat: (Artifact) -> Unit,
) {
    val t = S.artifactsPage
    val c = Theme.colors
    val shown = rememberLast(artifact)
    Sheet(artifact != null, onDismiss, title = shown?.label, heightFraction = .94f) {
        val a = shown ?: return@Sheet
        val chunks = remember(a.id, a.value) { a.value.lines().chunked(60).map { it.joinToString("\n") } }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(
                listOf(t.madeLabel(a.made), a.language, t.lines(a.value.count { it == '\n' } + 1)).filter { it.isNotBlank() }.joinToString(" · "),
                Theme.type.caption, c.secondary, Modifier.weight(1f), maxLines = 1,
            )
            IconButton(Icons.contentCopy, t.copySource, { onCopy(a) })
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(20.dp)).background(c.surface2)) {
            SelectionContainer(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp)) {
                    items(chunks) { Txt(it, Theme.type.code, c.text) }
                }
            }
        }
        Where(a)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(t.openChat, { onOpenChat(a) }, Modifier.weight(1f), kind = ButtonKind.Quiet, icon = Icons.chat)
            PillButton(t.save, { onSave(a) }, Modifier.weight(1f), icon = FileIcons.download, haptic = Haptic.Confirm)
        }
        Spacer(Modifier.height(4.dp))
    }
}
