package app.buddyverse.ui.more.files

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.core.api.FileEntry
import app.buddyverse.core.nowMillis
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.filesPage
import app.buddyverse.ui.ago

/** A question with one action (Danger when [danger]); [alt] replaces Cancel with a second real choice. */
internal class Ask(
    val title: String,
    val body: String,
    val action: String,
    val run: () -> Unit,
    val danger: Boolean = true,
    val alt: String? = null,
    val runAlt: (() -> Unit)? = null,
)

/** Closes three ways like every sheet; closing = Cancel ([onDismiss]). */
@Composable
internal fun AskSheet(ask: Ask?, onDismiss: () -> Unit) {
    val shown = rememberLast(ask)
    Sheet(ask != null, onDismiss, title = shown?.title) {
        val a = shown ?: return@Sheet
        Txt(a.body, Theme.type.callout, Theme.colors.secondary, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val alt = a.alt
            val runAlt = a.runAlt
            if (alt != null && runAlt != null) PillButton(alt, { runAlt() }, Modifier.weight(1f), kind = ButtonKind.Quiet)
            else PillButton(S.current.cancel, onDismiss, Modifier.weight(1f), kind = ButtonKind.Quiet)
            PillButton(
                a.action, { a.run() }, Modifier.weight(1f),
                kind = if (a.danger) ButtonKind.Danger else ButtonKind.Primary, haptic = if (a.danger) Haptic.Reject else Haptic.Press,
            )
        }
    }
}

@Composable
internal fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = Theme.colors.text) {
    PressRow(onClick, minHeight = 60.dp, label = label) {
        Icon(icon, tint)
        Spacer(Modifier.width(16.dp))
        Txt(label, Theme.type.callout, tint, Modifier.weight(1f), maxLines = 1)
    }
}

/** Long-press menu of a row: everything the row doesn't show (design §3.7). */
@Composable
internal fun FileMenu(
    entry: FileEntry?,
    onDismiss: () -> Unit,
    onOpen: (FileEntry) -> Unit,
    onDownload: (FileEntry) -> Unit,
    onCopyPath: (FileEntry) -> Unit,
    onDelete: (FileEntry) -> Unit,
) {
    val t = S.filesPage
    val s = S.current
    val shown = rememberLast(entry)
    Sheet(entry != null, onDismiss, title = shown?.name) {
        val e = shown ?: return@Sheet
        fun act(f: (FileEntry) -> Unit): () -> Unit = { onDismiss(); f(e) }
        MenuRow(if (e.isDir) Icons.folder else Icons.visibility, s.open, act(onOpen))
        if (!e.isDir && !e.broken) MenuRow(FileIcons.download, t.download, act(onDownload))
        MenuRow(Icons.contentCopy, t.copyPath, act(onCopyPath))
        MenuRow(Icons.delete, s.delete, act(onDelete), Theme.colors.errorText)
    }
}

/** The + sheet: upload from this device, new folder, new text file. */
@Composable
internal fun AddSheet(visible: Boolean, onDismiss: () -> Unit, onUpload: () -> Unit, onNewFolder: () -> Unit, onNewFile: () -> Unit) {
    val t = S.filesPage
    Sheet(visible, onDismiss, title = t.add) {
        MenuRow(FileIcons.upload, t.upload, onUpload)
        MenuRow(FileIcons.newFolder, t.newFolder, onNewFolder)
        MenuRow(FileIcons.newFile, t.newFile, onNewFile)
    }
}

/** Name a new folder ([folder] true) or text file (false); null = closed. [taken] = lowercase names already here. */
@Composable
internal fun NameSheet(folder: Boolean?, taken: Set<String>, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    val t = S.filesPage
    val shown = rememberLast(folder)
    Sheet(folder != null, onDismiss, title = if (shown == true) t.newFolder else t.newFile) {
        var name by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        val n = name.trim()
        val problem = when {
            n.isEmpty() -> null
            !validName(n) -> t.badName
            n.lowercase() in taken -> t.exists
            else -> null
        }
        Field(name, { name = it }, if (shown == true) t.folderName else t.fileName, Modifier.padding(horizontal = 20.dp, vertical = 8.dp).focusRequester(focus))
        if (problem != null) Txt(problem, Theme.type.caption, Theme.colors.errorText, Modifier.padding(horizontal = 24.dp))
        PillButton(
            S.current.create, { onCreate(n) },
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
            enabled = n.isNotEmpty() && problem == null,
        )
    }
}

/** The open file: text (read, select, edit, save), an image, or a plain "no preview" with Download. */
@Composable
internal fun FileViewer(model: FilesModel, spec: BuddySpec?, downloading: Float?, onDownload: (FileEntry) -> Unit) {
    val v = model.viewer
    val shown = rememberLast(v)
    Sheet(v != null, model::closeViewer, title = shown?.entry?.name, heightFraction = .94f) {
        val cur = shown ?: return@Sheet
        ViewerBody(cur, model, spec, downloading, onDownload)
    }
}

@Composable
private fun ColumnScope.ViewerBody(v: Viewer, model: FilesModel, spec: BuddySpec?, downloading: Float?, onDownload: (FileEntry) -> Unit) {
    val c = Theme.colors
    val t = S.filesPage
    val s = S.current
    val e = v.entry
    val editing = v.draft != null
    val meta = listOfNotNull(
        (v.text?.byteSize ?: e.size)?.let(::sizeText),
        v.text?.language?.takeIf { it.isNotBlank() && it != "text" },
        e.mtime?.let { ago(it, nowMillis(), s) }?.takeIf { it.isNotEmpty() },
    ).joinToString(" · ")

    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(meta, Theme.type.caption, c.secondary, Modifier.weight(1f), maxLines = 1)
        if (!editing) {
            if (v.canEdit) IconButton(Icons.edit, s.edit, model::startEdit)
            DownloadButton(downloading) { onDownload(e) }
        }
    }
    if (v.text?.truncated == true && !editing) Txt(t.startOnly, Theme.type.caption, c.secondary, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))

    Box(
        Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(20.dp)).background(c.surface2),
        contentAlignment = Alignment.Center,
    ) {
        when (val b = v.body) {
            Body.Loading -> Delayed { Waiting(spec, Mood.Thinking, null) }
            is Body.Text -> {
                val style = if (e.kind == Kind.Code) Theme.type.code else Theme.type.code.copy(textDirection = TextDirection.Content)
                val draft = v.draft
                if (draft != null) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    BasicTextField(
                        draft, { v.draft = it },
                        Modifier.fillMaxSize().padding(14.dp).focusRequester(focus),
                        textStyle = style.copy(color = c.text),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    )
                } else {
                    SelectionContainer(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp)) {
                            items(b.chunks) { Txt(it, style, c.text) }
                        }
                    }
                }
            }
            is Body.Picture -> Image(b.image, e.name, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
            is Body.NoPreview -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                Waiting(spec, Mood.Idle, b.why)
                Spacer(Modifier.height(16.dp))
                PillButton(t.download, { onDownload(e) }, icon = FileIcons.download, enabled = downloading == null)
            }
            is Body.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Waiting(spec, Mood.Error, null)
                ErrorLine(b.why, model::retryView)
            }
        }
    }

    if (editing) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(s.cancel, model::stopEdit, Modifier.weight(1f), kind = ButtonKind.Quiet)
            PillButton(if (v.saving) t.saving else s.save, model::save, Modifier.weight(1f), enabled = v.dirty && !v.saving)
        }
    }
}

/** The bot stays on screen while a file loads or can't be shown: thinking, idle or upset, never frozen. */
@Composable
private fun Waiting(spec: BuddySpec?, mood: Mood, text: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (spec != null) Buddy(spec, mood, 72.dp)
        if (text != null) {
            Spacer(Modifier.height(12.dp))
            Txt(text, Theme.type.callout, Theme.colors.secondary)
        }
    }
}
