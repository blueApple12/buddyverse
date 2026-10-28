package app.buddyverse.ui.more

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import app.buddyverse.core.api.FileEntry
import app.buddyverse.design.BackHandler
import app.buddyverse.design.Haptic
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.i18n.S
import app.buddyverse.i18n.filesPage
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.MorePage
import app.buddyverse.ui.PageFrame
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.chat.DeviceError
import app.buddyverse.ui.chat.PickKind
import app.buddyverse.ui.chat.rememberChatDevice
import app.buddyverse.ui.more.files.AddSheet
import app.buddyverse.ui.more.files.Ask
import app.buddyverse.ui.more.files.AskSheet
import app.buddyverse.ui.more.files.Delayed
import app.buddyverse.ui.more.files.EmptyFolder
import app.buddyverse.ui.more.files.ErrorLine
import app.buddyverse.ui.more.files.FileMenu
import app.buddyverse.ui.more.files.FileRow
import app.buddyverse.ui.more.files.FileViewer
import app.buddyverse.ui.more.files.FilesCrew
import app.buddyverse.ui.more.files.FilesModel
import app.buddyverse.ui.more.files.FindRow
import app.buddyverse.ui.more.files.LoadingLine
import app.buddyverse.ui.more.files.NameSheet
import app.buddyverse.ui.more.files.PathBar
import app.buddyverse.ui.more.files.rememberFileSaver

/**
 * More ▸ Files (hermes-api.md §2.6 Files tree + Managed files): the PC's folders from a bot's workspace. Browse with crumbs
 * and Up, find in a folder, show hidden things; open text (read, edit, save) and images; download to this device; upload
 * from it; new folder, new text file, copy path, delete. System back walks back through the folders first.
 */
@Composable
fun FilesPage() {
    val store = LocalStore.current
    val t = S.filesPage
    val s = S.current
    val toasts = LocalToasts.current
    val fb = LocalFeedback.current
    val scope = rememberCoroutineScope()
    val m = remember(store) { FilesModel(store, scope) }
    val bots by store.bots.collectAsState()
    val first = bots.firstOrNull { it.isDefault }?.profile ?: bots.firstOrNull()?.profile ?: "default"
    val profile = m.profile.ifEmpty { first }
    val spec = bots.firstOrNull { it.profile == profile }?.spec
    m.fb = fb; m.toasts = toasts; m.t = t; m.s = s; m.spec = spec

    LaunchedEffect(m, first) { if (m.profile.isEmpty()) m.select(first) }
    // No fs watcher over the gateway: an agent tool finishing may have touched this folder (hermes-api.md §2.6 Events).
    LaunchedEffect(m) { store.gateway.events.collect { if (it.type == "tool.complete") m.changedOutside() } }

    val saver = rememberFileSaver()
    @Suppress("DEPRECATION") val clip = LocalClipboardManager.current
    val device = rememberChatDevice(
        onPicked = { if (it.isNotEmpty()) { fb.haptic(Haptic.Confirm); m.upload(it) } },
        onError = { e ->
            fb.haptic(Haptic.Reject)
            toasts.show(ToastData(if (e == DeviceError.TooBig) t.tooBigUpload else t.failed))
        },
    )

    var menu by remember { mutableStateOf<FileEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<Boolean?>(null) }
    var ask by remember { mutableStateOf<Ask?>(null) }

    BackHandler(m.history.isNotEmpty() && m.viewer == null) { m.goBack() }

    fun askDelete(e: FileEntry) {
        ask = Ask(t.deleteTitle(e.name), if (e.isDir) t.deleteFolder else t.deleteFile, s.delete, run = { ask = null; m.delete(e) })
    }

    Box(Modifier.fillMaxSize()) {
        PageFrame(MorePage.Files.label, actions = {
            IconButton(Icons.refresh, t.refresh, { fb.haptic(Haptic.Tick); m.refresh() }, enabled = m.folder != null)
            IconButton(Icons.add, t.add, { adding = true }, enabled = m.listing != null)
        }) {
            item(key = "crew") { FilesCrew(bots.map { it.spec }, profile, m.mood, m.cue) { p -> if (p != m.profile) m.select(p) } }
            val path = m.folder
            if (path != null) item(key = "path") { PathBar(path, m.listing?.parent != null, m::up, m::open) }
            val listing = m.listing
            when {
                listing == null && m.loadError != null -> item(key = "err") { ErrorLine(m.loadError.orEmpty(), m::refresh) }
                listing == null -> item(key = "wait") { Delayed { LoadingLine() } }
                else -> {
                    item(key = "find") {
                        FindRow(m.query, { m.query = it }, m.hiddenCount, m.showHidden) { m.showHidden = !m.showHidden }
                    }
                    m.loadError?.let { err -> item(key = "err") { ErrorLine(err, m::refresh) } }
                    val shown = m.shown
                    if (shown.isEmpty()) item(key = "empty") {
                        EmptyFolder(spec, m.mood, m.cue, if (m.query.isBlank()) t.empty else t.noMatches)
                    }
                    items(shown, key = { it.path }) { e ->
                        FileRow(
                            e, m.downloads[e.path], e.path in m.uploads,
                            onOpen = { if (e.isDir) m.open(e.path) else m.view(e) },
                            onMenu = { menu = e },
                            onDownload = { m.download(e, saver) },
                            modifier = Modifier.animateItem(fadeInSpec = Motion.fade(), placementSpec = Motion.smooth(), fadeOutSpec = Motion.fade()),
                        )
                    }
                }
            }
        }

        FileViewer(m, spec, m.viewer?.entry?.path?.let { m.downloads[it] }) { m.download(it, saver) }
        FileMenu(
            menu, { menu = null },
            onOpen = { e -> if (e.isDir) m.open(e.path) else m.view(e) },
            onDownload = { m.download(it, saver) },
            onCopyPath = { e -> clip.setText(AnnotatedString(e.path)); fb.haptic(Haptic.Confirm); toasts.show(ToastData(t.pathCopied)) },
            onDelete = ::askDelete,
        )
        AddSheet(
            adding, { adding = false },
            onUpload = { adding = false; device.pick(PickKind.File) },
            onNewFolder = { adding = false; naming = true },
            onNewFile = { adding = false; naming = false },
        )
        NameSheet(naming, m.taken, { naming = null }) { name -> val dir = naming == true; naming = null; m.create(name, dir) }

        // One question at a time, in one sheet: an unsaved edit, then an upload whose name is taken, then a delete.
        val v = m.viewer
        val conflict = m.conflicts.firstOrNull()
        val question: Ask?
        val dismiss: () -> Unit
        when {
            v != null && v.askDiscard -> {
                question = Ask(t.unsavedTitle, t.unsavedBody, t.discard, run = m::discard, alt = t.keepEditing, runAlt = m::keepEditing)
                dismiss = m::keepEditing
            }
            conflict != null -> {
                val name = conflict.path.substringAfterLast('\\').substringAfterLast('/')
                question = Ask(t.replaceTitle(name), t.replaceBody, t.replace, run = { m.resolve(conflict, replace = true) },
                    alt = t.keepBoth, runAlt = { m.resolve(conflict, replace = false) })
                dismiss = { m.skip(conflict) }
            }
            else -> {
                question = ask
                dismiss = { ask = null }
            }
        }
        AskSheet(question, dismiss)
    }
}
