package app.buddyverse.ui.project

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.buddyverse.core.ChatTarget
import app.buddyverse.core.Room
import app.buddyverse.core.RpcError
import app.buddyverse.core.api.LiveSubagent
import app.buddyverse.core.api.withHelperChat
import app.buddyverse.core.api.LivingPage
import app.buddyverse.core.api.Project
import app.buddyverse.core.api.ProjectWork
import app.buddyverse.core.api.groupsRename
import app.buddyverse.core.api.livingPage
import app.buddyverse.core.api.pageSections
import app.buddyverse.core.api.projectAddFolder
import app.buddyverse.core.api.projectArchive
import app.buddyverse.core.api.projectDelete
import app.buddyverse.core.api.projectKey
import app.buddyverse.core.api.projectsOf
import app.buddyverse.core.api.projectRemoveFolder
import app.buddyverse.core.api.projectSessions
import app.buddyverse.core.api.projectSetPrimary
import app.buddyverse.core.api.projectUpdate
import app.buddyverse.core.nowMillis
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.project
import app.buddyverse.ui.LocalNav
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.Screen
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.ago
import app.buddyverse.ui.bot.openCount
import app.buddyverse.ui.chat.MdText
import app.buddyverse.ui.chat.MenuItem
import app.buddyverse.ui.chat.MenuPopup
import app.buddyverse.ui.chat.Spinner
import app.buddyverse.ui.home.folderKey
import app.buddyverse.ui.ltr
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class Ask { None, Archive, Delete }

/** Sessions, tokens and money of a project, read once per opening. */
private fun tokensText(n: Long): String = when {
    n >= 1_000_000 -> "${n / 100_000 / 10.0}M"
    n >= 1_000 -> "${n / 1_000}k"
    else -> "$n"
}

/**
 * Project info sheet (design §3.3): name and what it's about (editable), folders (main, add, remove, copy), the agents and
 * their helpers, subprojects, the living page from the brain (Now and Next, read-only, Open in Obsidian), sessions, and
 * Archive / Delete with a confirm. Shown while [open]; place it last in a full-size Box.
 */
@Composable
internal fun ProjectInfoSheet(
    open: Boolean, project: Project?, room: Room?, agents: List<String>, helpers: Map<String, List<LiveSubagent>>,
    onDismiss: () -> Unit, onChanged: (Project) -> Unit, onGone: () -> Unit,
) {
    val generation = openCount(open)
    Sheet(open, onDismiss, heightFraction = .92f) {
        if (project != null) key(generation, project.id) { InfoBody(project, room, agents, helpers, onDismiss, onChanged, onGone) }
    }
}

@Composable
private fun ColumnScope.InfoBody(
    p: Project, room: Room?, agents: List<String>, helpers: Map<String, List<LiveSubagent>>,
    onDismiss: () -> Unit, onChanged: (Project) -> Unit, onGone: () -> Unit,
) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val toasts = LocalToasts.current
    val fb = LocalFeedback.current
    val uri = LocalUriHandler.current
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val s = S.current
    val sp = S.project
    val c = Theme.colors
    val bots by store.bots.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(p.name) }
    var about by remember { mutableStateOf(p.description.orEmpty()) }
    var newFolder by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var ask by remember { mutableStateOf(Ask.None) }
    var busy by remember { mutableStateOf(false) }

    // (tried, page): the page lookup probes a few paths, so "not found" only shows once it has looked.
    val pageState by produceState<Pair<Boolean, LivingPage?>>(false to null, p.id) { value = true to runCatching { store.api.livingPage(p) }.getOrNull() }
    val work by produceState<ProjectWork?>(null, p.id) { value = runCatching { store.api.projectSessions(p.profile, p.id) }.getOrNull() }
    // Every bot holds only its own project, so subprojects are read across all profiles (one per folder).
    val subprojects by produceState(emptyList<Project>(), p.id, p.primaryPath) {
        val root = p.primaryPath?.let(::folderKey)
        if (root.isNullOrEmpty()) return@produceState
        val all = runCatching { store.api.projectsOf(store.bots.value.map { it.profile }.ifEmpty { listOf(p.profile) }) }.getOrDefault(emptyMap())
        value = all.values.flatten().filter { o ->
            val path = o.primaryPath?.let(::folderKey) ?: return@filter false
            !o.archived && path.startsWith("$root/")
        }.distinctBy { projectKey(it) }.sortedBy { folderKey(it.primaryPath.orEmpty()) }
    }

    fun toast(t: String) = toasts.show(ToastData(t))
    fun why(e: Exception) = (e as? RpcError)?.message ?: e.message ?: s.error

    /** Runs a project write; the sheet shows the answer, the screen gets the new project. */
    fun write(call: suspend () -> Project?) {
        if (busy) return
        busy = true
        store.scope.launch {
            try { call()?.let(onChanged); fb.haptic(Haptic.Confirm) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                fb.haptic(Haptic.Reject); toast(why(e))
            } finally { busy = false }
        }
    }

    fun save() {
        val n = name.trim()
        if (n.isEmpty()) return
        val renamed = n != p.name
        write {
            val np = store.api.projectUpdate(p.profile, p.id, name = n.takeIf { renamed }, description = about.trim().takeIf { it != p.description.orEmpty() })
            // The project's room is found by name: keep them together.
            if (renamed && room != null) runCatching { store.api.groupsRename(room.profile, room.roomId, n) }.onSuccess { store.refreshRooms() }
            editing = false
            toast(sp.saved)
            np
        }
    }

    fun archiveOrDelete(delete: Boolean) {
        ask = Ask.None
        store.scope.launch {
            try {
                if (delete) store.api.projectDelete(p.profile, p.id) else store.api.projectArchive(p.profile, p.id, restore = p.archived)
                fb.haptic(Haptic.Confirm)
                if (delete || !p.archived) {
                    toast(if (delete) sp.deleted else sp.archived)
                    onDismiss(); onGone()
                } else onChanged(p.copy(archived = false))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fb.haptic(Haptic.Reject); toast(why(e)) }
        }
    }

    fun openChat(t: ChatTarget) { onDismiss(); nav.push(Screen.Chat(t)) }

    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        // ---- name and what it's about
        item(key = "head") {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.folderFilled, projectTint(p.color), 28.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Txt(p.name.ifBlank { p.id }, Theme.type.title2, maxLines = 2)
                    if (p.archived) Txt(sp.isArchived, Theme.type.caption, c.secondary, maxLines = 1)
                }
                IconButton(if (editing) Icons.undo else Icons.edit, sp.edit, { editing = !editing; name = p.name; about = p.description.orEmpty() })
                IconButton(Icons.close, s.close, onDismiss)
            }
        }
        if (editing) item(key = "edit") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Field(name, { name = it.take(120) }, sp.name)
                Spacer(Modifier.padding(top = 8.dp))
                Field(about, { about = it.take(2000) }, sp.about, singleLine = false, minHeight = 96.dp)
                PillButton(s.save, ::save, Modifier.fillMaxWidth().padding(top = 12.dp), enabled = !busy && name.isNotBlank(), haptic = Haptic.Confirm)
            }
        } else p.description?.let { d -> item(key = "about") { MdText(d, Theme.type.callout, c.text, c.surface2, Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) } }
        work?.let { w ->
            item(key = "stats") {
                Txt(sp.stats(w.sessionCount, tokensText(w.tokens), w.costUsd.takeIf { it > 0 }?.let { "\$" + ((it * 100).toLong() / 100.0) }),
                    Theme.type.caption, c.secondary, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), maxLines = 1)
            }
        }

        // ---- folders
        item(key = "folders-h") { SectionLabel(sp.folders) }
        val folders = (listOfNotNull(p.primaryPath) + p.folders).distinct()
        items(folders, key = { "f:$it" }) { f ->
            val main = f == p.primaryPath
            Box {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(ltr(f), Theme.type.code, c.text, Modifier.weight(1f), maxLines = 2)
                    if (main) Txt(sp.mainFolder, Theme.type.micro, c.onAccent,
                        Modifier.padding(horizontal = 6.dp).clip(CircleShape).background(c.accentFill).padding(horizontal = 8.dp, vertical = 2.dp), maxLines = 1)
                    IconButton(Icons.moreHoriz, sp.folder, { menuFor = f })
                }
                if (menuFor == f) MenuPopup(sp.folder, alignEnd = true, onDismiss = { menuFor = null }) {
                    MenuItem(Icons.contentCopy, sp.copyPath) { menuFor = null; clipboard.setText(AnnotatedString(f)); toast(sp.copied) }
                    if (!main) {
                        MenuItem(Icons.pushPin, sp.makeMain) { menuFor = null; write { store.api.projectSetPrimary(p.profile, p.id, f) } }
                        MenuItem(Icons.delete, sp.removeFolder, c.errorText) { menuFor = null; write { store.api.projectRemoveFolder(p.profile, p.id, f) } }
                    }
                }
            }
        }
        item(key = "add-folder") {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(newFolder, { newFolder = it }, sp.addFolder, Modifier.weight(1f), mono = true)
                IconButton(Icons.add, sp.addFolder, {
                    val path = newFolder.trim()
                    newFolder = ""
                    write { store.api.projectAddFolder(p.profile, p.id, path) }
                }, enabled = newFolder.isNotBlank() && !busy)
            }
        }

        // ---- agents and their helpers
        item(key = "agents-h") { SectionLabel(sp.agents) }
        items(agents, key = { "a:$it" }) { a ->
            AgentRow(a, bots, helpers[a].orEmpty(), onOpen = { openChat(ChatTarget(a)) }) { sa ->
                store.withHelperChat(a, sa) { child -> openChat(helperTarget(bots.firstOrNull { it.profile == a }, a, sa, child)) }
            }
        }

        // ---- subprojects
        if (subprojects.isNotEmpty()) {
            item(key = "sub-h") { SectionLabel(sp.subprojects) }
            items(subprojects, key = { "sub:" + projectKey(it) }) { o ->
                PressRow({ onDismiss(); nav.push(Screen.Project(o.profile, o.id)) }, minHeight = 56.dp, label = o.name) {
                    Icon(Icons.folder, projectTint(o.color), 24.dp)
                    Spacer(Modifier.width(14.dp))
                    Txt(o.name.ifBlank { o.id }, Theme.type.callout, c.text, Modifier.weight(1f), maxLines = 1)
                    Icon(Icons.chevronRight, c.tertiary, 22.dp, mirror = true)
                }
            }
        }

        // ---- the living page (brain/projects/<page>.md): Now and Next
        item(key = "page-h") { SectionLabel(sp.page) }
        item(key = "page") {
            val (pageTried, pg) = pageState
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                if (pg == null) {
                    if (pageTried) Txt(sp.noPage, Theme.type.sub, c.secondary) else Spinner(20.dp, c.tertiary)
                } else {
                    val sections = remember(pg.text) { pageSections(pg.text) }
                    val shown = sections.filter { (h, _) -> h.startsWith("Now", true) || h.startsWith("Next", true) }.ifEmpty { sections.take(2) }
                    for ((h, body) in shown) {
                        Txt(if (h.startsWith("Now", true)) sp.now else if (h.startsWith("Next", true)) sp.next else h, Theme.type.headline, c.text,
                            Modifier.padding(top = 8.dp, bottom = 4.dp), maxLines = 1)
                        MdText(body.ifBlank { "—" }, Theme.type.sub, c.text, c.surface2)
                    }
                    PillButton(sp.openInObsidian, {
                        runCatching { uri.openUri("obsidian://open?path=" + pg.path.encodeURLParameter()) }.onFailure { toast(s.error) }
                    }, Modifier.padding(top = 12.dp), kind = ButtonKind.Quiet, height = 48.dp, icon = Icons.description)
                }
            }
        }

        // ---- sessions
        item(key = "sessions-h") { SectionLabel(sp.sessions) }
        val rows = work?.sessions.orEmpty()
        if (work != null && rows.isEmpty()) item(key = "no-sessions") { Txt(sp.noSessions, Theme.type.sub, c.secondary, Modifier.padding(horizontal = 20.dp)) }
        items(rows.take(30), key = { "s:" + it.id }) { r ->
            PressRow({ openChat(ChatTarget(r.profile, r.id)) }, minHeight = 64.dp, label = r.title ?: s.untitled) {
                Icon(if (r.active) Icons.bolt else Icons.forum, if (r.active) c.working else c.secondary, 22.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Txt(r.title ?: r.preview ?: s.untitled, Theme.type.callout, c.text, maxLines = 1)
                    val sub = listOfNotNull(
                        ago(r.lastActive, nowMillis(), s).takeIf { it.isNotEmpty() }?.let { if (it == s.justNow) it else s.ago(it) },
                        r.messages.takeIf { it > 0 }?.let { s.messagesCount(it.toInt()) },
                        r.branch?.let(::ltr),
                    ).joinToString(" · ")
                    if (sub.isNotEmpty()) Txt(sub, Theme.type.caption, c.secondary, maxLines = 1)
                }
            }
        }

        // ---- archive / delete
        item(key = "danger") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
                when (ask) {
                    Ask.None -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(if (p.archived) sp.restore else sp.archive, { if (p.archived) archiveOrDelete(false) else ask = Ask.Archive },
                            Modifier.weight(1f), kind = ButtonKind.Outline, height = 48.dp, icon = if (p.archived) Icons.unarchive else Icons.archive)
                        PillButton(sp.delete, { ask = Ask.Delete }, Modifier.weight(1f), kind = ButtonKind.Quiet, height = 48.dp, icon = Icons.delete)
                    }
                    else -> {
                        Txt(if (ask == Ask.Delete) sp.deleteAsk else sp.archiveAsk, Theme.type.body, c.text)
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton(if (ask == Ask.Delete) sp.delete else sp.archive, { archiveOrDelete(ask == Ask.Delete) }, Modifier.weight(1f),
                                kind = ButtonKind.Danger, height = 48.dp, haptic = Haptic.Reject)
                            PillButton(s.cancel, { ask = Ask.None }, Modifier.weight(1f), kind = ButtonKind.Quiet, height = 48.dp)
                        }
                    }
                }
            }
        }
    }
}
