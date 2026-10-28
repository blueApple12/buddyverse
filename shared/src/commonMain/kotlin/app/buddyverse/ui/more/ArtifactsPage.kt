package app.buddyverse.ui.more

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.ChatTarget
import app.buddyverse.core.LinkState
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Sound
import app.buddyverse.i18n.S
import app.buddyverse.i18n.artifactsPage
import app.buddyverse.i18n.filesPage
import app.buddyverse.ui.LocalNav
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.MorePage
import app.buddyverse.ui.PageFrame
import app.buddyverse.ui.Screen
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.more.artifacts.Artifact
import app.buddyverse.ui.more.artifacts.ArtifactKind
import app.buddyverse.ui.more.artifacts.ArtifactMenu
import app.buddyverse.ui.more.artifacts.ArtifactRow
import app.buddyverse.ui.more.artifacts.ArtifactsModel
import app.buddyverse.ui.more.artifacts.ImageRow
import app.buddyverse.ui.more.artifacts.KindChips
import app.buddyverse.ui.more.artifacts.MadeSheet
import app.buddyverse.ui.more.artifacts.SCAN_CHATS
import app.buddyverse.ui.more.artifacts.ScanMeter
import app.buddyverse.ui.more.artifacts.WebImageSheet
import app.buddyverse.ui.more.artifacts.entry
import app.buddyverse.ui.more.artifacts.fileName
import app.buddyverse.ui.more.artifacts.kindLabel
import app.buddyverse.ui.more.artifacts.mime
import app.buddyverse.ui.more.artifacts.onPc
import app.buddyverse.ui.more.files.EmptyFolder
import app.buddyverse.ui.more.files.FileViewer
import app.buddyverse.ui.more.files.FilesModel
import app.buddyverse.ui.more.files.rememberFileSaver
import app.buddyverse.ui.more.insights.BotPicker
import app.buddyverse.ui.more.insights.InsightsHero
import app.buddyverse.ui.more.insights.hopNow
import app.buddyverse.ui.more.models.PageMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * More ▸ Artifacts (hermes-api.md §2.6 Artifacts): what the bots produced in their last [SCAN_CHATS] chats: images (a grid),
 * pages, drawings and long code written into replies, files on the PC and links. Filter by kind and bot, find by name, path
 * or chat. Open (files in the shared viewer, images full size, links in the browser), download, save, copy, or jump to the
 * chat it came from. The scan shows how far it got and lists things as each chat finishes.
 */
@Composable
fun ArtifactsPage() {
    val store = LocalStore.current
    val t = S.artifactsPage
    val s = S.current
    val fb = LocalFeedback.current
    val toasts = LocalToasts.current
    val nav = LocalNav.current
    val uri = LocalUriHandler.current
    @Suppress("DEPRECATION") val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val m = remember(store) { ArtifactsModel(store, scope) }
    // The Files page's viewer and downloads, reused for artifacts that are files on the PC.
    val files = remember(store) { FilesModel(store, scope) }
    val saver = rememberFileSaver()
    val bots by store.bots.collectAsState()
    val link by store.link.collectAsState()
    val visible = bots.filter { !it.hidden }
    val bot = m.bot?.let { p -> bots.firstOrNull { it.profile == p } }
    val spec = (bot ?: bots.firstOrNull { it.isDefault } ?: bots.firstOrNull())?.spec
    m.fb = fb
    files.fb = fb; files.toasts = toasts; files.t = S.filesPage; files.s = s; files.spec = spec

    var hop by remember { mutableStateOf<BuddyCue?>(null) }
    var menu by remember { mutableStateOf<Artifact?>(null) }
    var webImage by remember { mutableStateOf<Artifact?>(null) }
    var made by remember { mutableStateOf<Artifact?>(null) }
    val ready = link is LinkState.Ready
    LaunchedEffect(m, ready) { if (ready) m.scan() }

    fun chat(a: Artifact) { nav.push(Screen.Chat(ChatTarget(a.profile, a.sessionId, botChat = false))) }
    fun web(href: String) {
        if (runCatching { uri.openUri(href) }.isFailure) { fb.haptic(Haptic.Reject); toasts.show(ToastData(t.openFailed)) }
    }
    fun copy(a: Artifact) {
        clip.setText(AnnotatedString(if (a.kind == ArtifactKind.Made || !a.onPc) a.value else a.entry().path))
        fb.haptic(Haptic.Confirm)
        toasts.show(ToastData(t.copied))
    }
    fun open(a: Artifact) {
        when {
            a.kind == ArtifactKind.Made -> made = a
            a.kind == ArtifactKind.Link -> web(a.value)
            a.onPc -> files.viewAs(a.profile, a.entry())
            else -> webImage = a
        }
    }
    fun saveMade(a: Artifact) {
        val out = saver.create(a.fileName(), a.mime())
        if (out == null) { fb.haptic(Haptic.Reject); toasts.show(ToastData(t.cantSave)); return }
        scope.launch {
            val bytes = a.value.encodeToByteArray()
            val ok = withContext(Dispatchers.Default) {
                if (runCatching { out.write(bytes, bytes.size) }.isSuccess) out.finish() else { out.abort(); false }
            }
            if (!ok) { fb.haptic(Haptic.Reject); toasts.show(ToastData(t.cantSave)); return@launch }
            fb.haptic(Haptic.Confirm); fb.sound(Sound.Bloop)
            toasts.show(ToastData(t.saved, s.open, onAction = { if (!out.open()) toasts.show(ToastData(t.noApp)) }, buddy = spec, shot = OneShot.Done))
        }
    }
    fun action(a: Artifact) {
        when {
            a.kind == ArtifactKind.Made -> saveMade(a)
            a.onPc -> files.downloadAs(a.profile, a.entry(), saver)
            else -> copy(a)
        }
    }

    val cue = listOfNotNull(m.cue, hop).maxByOrNull { it.serial }
    val headline = when {
        m.scanning && m.total == 0 -> t.starting
        m.scanning -> t.scanning((m.done + 1).coerceAtMost(m.total), m.total)
        m.failed && m.items.isEmpty() -> t.failed
        m.items.isEmpty() -> t.nothing
        else -> t.found(m.count(null), if (m.total > 0) m.total else SCAN_CHATS)
    }
    val sub = if (m.skipped > 0) t.skipped(m.skipped) else null

    Box(Modifier.fillMaxSize()) {
        PageFrame(MorePage.Artifacts.label, actions = {
            IconButton(Icons.refresh, t.refresh, { fb.haptic(Haptic.Tick); m.scan(loud = true) }, enabled = ready && !m.scanning)
        }) {
            item(key = "hero") { InsightsHero(bot, if (bot == null) visible.take(3) else emptyList(), m.mood, cue, m.talkAt, headline, sub) }
            if (m.scanning) item(key = "meter") { ScanMeter(m.done, m.total) }
            if (visible.size > 1) item(key = "bots") {
                BotPicker(visible, m.bot, cue) { p -> if (p != m.bot) { m.bot = p; hop = hopNow() } }
            }
            val all = m.items
            if (all.isNotEmpty()) {
                item(key = "kinds") { KindChips(m) }
                item(key = "find") { Field(m.query, { m.query = it }, t.search, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
            }
            val shown = m.shown
            when {
                all.isEmpty() && m.failed -> item(key = "msg") { PageMessage(t.failed, s.retry, Icons.refresh) { m.scan(loud = true) } }
                all.isEmpty() && m.scanning -> Unit
                all.isEmpty() -> {
                    item(key = "empty") { EmptyFolder(spec, m.mood, cue, t.nothing) }
                }
                shown.isEmpty() -> item(key = "msg") { PageMessage(t.noMatches) }
                else -> listOf(ArtifactKind.Image, ArtifactKind.Made, ArtifactKind.File, ArtifactKind.Link).forEach { k ->
                    val group = shown.filter { it.kind == k }
                    if (group.isEmpty()) return@forEach
                    if (m.kind == null) item(key = "h:$k") { SectionLabel(t.kindLabel(k)) }
                    if (k == ArtifactKind.Image) {
                        items(group.chunked(3), key = { "row:" + it.first().id }) { row ->
                            ImageRow(row, m, ::open, { menu = it },
                                Modifier.animateItem(fadeInSpec = Motion.fade(), placementSpec = Motion.smooth(), fadeOutSpec = Motion.fade()))
                        }
                    } else {
                        items(group, key = { "a:" + it.id }) { a ->
                            ArtifactRow(
                                a, files.downloads[a.entry().path], onOpen = { open(a) }, onMenu = { menu = a }, onAction = { action(a) },
                                modifier = Modifier.animateItem(fadeInSpec = Motion.fade(), placementSpec = Motion.smooth(), fadeOutSpec = Motion.fade()),
                            )
                        }
                    }
                }
            }
        }

        FileViewer(files, spec, files.viewer?.entry?.path?.let { files.downloads[it] }) { files.download(it, saver) }
        WebImageSheet(webImage, m, spec, { webImage = null }, onOpenLink = { web(it.value) }, onOpenChat = { webImage = null; chat(it) })
        MadeSheet(made, { made = null }, onSave = ::saveMade, onCopy = ::copy, onOpenChat = { made = null; chat(it) })
        ArtifactMenu(menu, { menu = null }, onOpen = ::open, onDownload = ::action, onCopy = ::copy, onOpenChat = ::chat)
    }
}
