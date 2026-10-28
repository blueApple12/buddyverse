package app.buddyverse.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.core.Bot
import app.buddyverse.core.LinkState
import app.buddyverse.core.api.Project
import app.buddyverse.core.nowMillis
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Chip
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.projectsPage
import app.buddyverse.ui.LocalNav
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.PageFrame
import app.buddyverse.ui.Screen
import app.buddyverse.ui.more.projects.DeleteProjectSheet
import app.buddyverse.ui.more.projects.EditorTarget
import app.buddyverse.ui.more.projects.ProjectBlob
import app.buddyverse.ui.more.projects.ProjectEditor
import app.buddyverse.ui.more.projects.ProjectFilter
import app.buddyverse.ui.more.projects.ProjectMenuSheet
import app.buddyverse.ui.more.projects.ProjectsModel
import app.buddyverse.ui.more.projects.key
import app.buddyverse.ui.more.projects.projectColor
import app.buddyverse.ui.more.projects.title
import app.buddyverse.ui.more.projects.toMood
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter

private val FALLBACK = BuddySpec("projects", "brain", BuddyShape.Squircle, BuddyColors.palette[8])

/**
 * More ▸ Projects (hermes-api.md §2.5 "Projects and workspaces"): every bot's projects in one list (each Hermes profile
 * has its own projects.db), Active / Archived. A row opens the project; its one button archives (or restores) with undo;
 * long press: Open, Edit (name, description, color, folders), start new chats here, Archive, Delete (confirm).
 * `+` makes a new one, from a typed folder or a repo the PC already knows. Live: refetches on `projects.changed`.
 */
@Composable
fun ProjectsPage() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val s = S.current
    val t = S.projectsPage
    val toasts = LocalToasts.current
    val fb = LocalFeedback.current
    val m = remember(store) { ProjectsModel(store, toasts, fb) }
    m.t = t
    m.undoLabel = s.undo
    val bots by store.bots.collectAsState()
    val link by store.link.collectAsState()
    val ready = link is LinkState.Ready
    val profiles = bots.map { it.profile }
    LaunchedEffect(profiles, ready) {
        if (!ready) return@LaunchedEffect
        m.refresh(profiles)
        // Change signal (payload {}): refetch. collectLatest = a burst of signals costs one listing.
        store.gateway.events.filter { it.type == "projects.changed" }.collectLatest { delay(250); m.refresh(profiles) }
    }

    var hurt by remember { mutableStateOf(false) }
    LaunchedEffect(m.failedAt) { if (m.failedAt > 0) { hurt = true; delay(2_500); hurt = false } }
    val mood = when {
        m.unsupported -> Mood.Sleeping
        !m.loaded -> Mood.Thinking
        hurt || (m.error != null && m.all.isEmpty()) -> Mood.Error
        m.inFlight > 0 -> Mood.Working
        else -> Mood.Idle
    }
    val brain = bots.firstOrNull { it.isDefault } ?: bots.firstOrNull()
    val spec = brain?.spec ?: FALLBACK
    fun ownerOf(p: Project): Bot? = bots.firstOrNull { it.profile == p.profile }

    var filter by remember { mutableStateOf(ProjectFilter.Active) }
    var menu by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    var deleting by remember { mutableStateOf<Project?>(null) }
    val active = m.rows(ProjectFilter.Active)
    val archived = m.rows(ProjectFilter.Archived)
    val rows = if (filter == ProjectFilter.Active) active else archived
    val owners = (active + archived).map { it.profile }.distinct().size
    val canCreate = !m.unsupported && bots.isNotEmpty()
    val newProject = { editor = EditorTarget.New(brain?.profile.orEmpty()) }
    fun openProject(p: Project) = nav.push(Screen.Project(p.profile, p.id))

    Box(Modifier.fillMaxSize()) {
        PageFrame(s.projects, actions = { IconButton(Icons.add, t.newProject, newProject, enabled = canCreate) }) {
            item(key = "head") { Header(spec, mood, m.cue, if (m.loaded) t.summary(active.size, owners) else s.loading) }
            if (m.loaded && !m.unsupported && (active.isNotEmpty() || archived.isNotEmpty())) item(key = "filters") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("${t.active} · ${active.size}", filter == ProjectFilter.Active, { filter = ProjectFilter.Active })
                    Chip("${s.archived} · ${archived.size}", filter == ProjectFilter.Archived, { filter = ProjectFilter.Archived })
                }
            }
            when {
                m.unsupported -> item(key = "msg") { Message(s.unsupported) }
                !m.loaded -> Unit
                m.error != null && m.all.isEmpty() -> item(key = "msg") { Message(m.error.orEmpty(), s.retry, Icons.refresh) { m.load(profiles) } }
                rows.isEmpty() -> item(key = "msg") {
                    if (filter == ProjectFilter.Active) Message(t.noProjects, t.newProject.takeIf { canCreate }, Icons.add, newProject)
                    else Message(t.noArchived)
                }
                else -> items(rows, key = { it.key }) { p ->
                    val owner = ownerOf(p)
                    ProjectRow(
                        p, owner, current = m.isCurrent(p), showOwner = owners > 1, archived = filter == ProjectFilter.Archived,
                        modifier = Modifier.animateItem(fadeInSpec = Motion.fade(), placementSpec = Motion.smooth(), fadeOutSpec = Motion.fade()),
                        onOpen = { openProject(p) }, onMenu = { menu = p.key },
                        onArchive = { m.archive(p, restore = filter == ProjectFilter.Archived, spec = owner?.spec) },
                    )
                }
            }
        }
        val menuFor = m.find(menu)
        ProjectMenuSheet(
            menuFor, archived = menuFor != null && menuFor in archived, current = menuFor != null && m.isCurrent(menuFor),
            onDismiss = { menu = null }, onOpen = ::openProject, onEdit = { editor = EditorTarget.Edit(it.key) },
            onCurrent = { p, on -> m.setCurrent(p, on) },
            onArchive = { p -> m.archive(p, restore = p in archived, spec = ownerOf(p)?.spec) },
            onDelete = { deleting = it },
        )
        ProjectEditor(m, editor, bots) { editor = null }
        DeleteProjectSheet(deleting, { deleting = null }) { p -> m.delete(p, ownerOf(p)?.spec) }
    }
}

/** The brain as a living buddy over the count: it thinks while loading, works while a write runs, cheers each change. */
@Composable
private fun Header(spec: BuddySpec, mood: Mood, cue: BuddyCue?, summary: String) {
    // Serials are timestamps: never replay an old cue on a freshly composed buddy.
    val shown = cue?.takeIf { nowMillis() - it.serial < 2_000 }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Buddy(spec, mood, 64.dp, cue = shown)
        Spacer(Modifier.width(14.dp))
        Txt(summary, Theme.type.headline, modifier = Modifier.weight(1f), maxLines = 2)
    }
}

/** 76dp row: folder-blob in the project's color with its bot on the corner; name (+ "current"), owner · description, folder. */
@Composable
private fun ProjectRow(
    p: Project, owner: Bot?, current: Boolean, showOwner: Boolean, archived: Boolean, modifier: Modifier,
    onOpen: () -> Unit, onMenu: () -> Unit, onArchive: () -> Unit,
) {
    val c = Theme.colors
    val s = S.current
    val t = S.projectsPage
    PressRow(onOpen, modifier, minHeight = 76.dp, onLongPress = onMenu, label = p.title) {
        ProjectBlob(projectColor(p), owner?.spec, owner?.status?.toMood() ?: Mood.Idle)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(p.title, Theme.type.headline, if (archived) c.secondary else c.text, Modifier.weight(1f, fill = false), maxLines = 1)
                if (current) {
                    Spacer(Modifier.width(8.dp))
                    Txt(t.current, Theme.type.micro, c.onAccent, Modifier.clip(CircleShape).background(c.accentFill).padding(horizontal = 8.dp, vertical = 2.dp), maxLines = 1)
                }
            }
            val line = listOfNotNull(owner?.name?.takeIf { showOwner }, p.description).joinToString(" · ")
            if (line.isNotEmpty()) Txt(line, Theme.type.sub, c.secondary, maxLines = 1)
            Txt(p.primaryPath ?: t.noFolder, if (p.primaryPath != null) Theme.type.code else Theme.type.sub, c.tertiary, maxLines = 1)
        }
        Spacer(Modifier.width(4.dp))
        IconButton(if (archived) Icons.unarchive else Icons.archive, if (archived) t.restore else s.archive, onArchive, tint = c.secondary)
    }
}

/** Empty, error and unsupported states: one line and at most one button; the buddy above carries the feeling. */
@Composable
private fun Message(text: String, action: String? = null, icon: ImageVector? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(text, Theme.type.title2.copy(textAlign = TextAlign.Center), Theme.colors.secondary)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            PillButton(action, onAction, icon = icon, kind = ButtonKind.Primary)
        }
    }
}
