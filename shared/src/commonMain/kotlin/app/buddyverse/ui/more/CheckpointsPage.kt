package app.buddyverse.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.core.api.Checkpoint
import app.buddyverse.core.api.DiffText
import app.buddyverse.core.api.ReviewFile
import app.buddyverse.core.api.ReviewScope
import app.buddyverse.core.nowMillis
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Card
import app.buddyverse.design.Chip
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.CheckpointsPageStrings
import app.buddyverse.i18n.S
import app.buddyverse.i18n.checkpointsPage
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.MorePage
import app.buddyverse.ui.PageFrame
import app.buddyverse.ui.ago
import app.buddyverse.ui.ltr
import app.buddyverse.ui.more.checkpoints.BranchSheet
import app.buddyverse.ui.more.checkpoints.CheckpointSheet
import app.buddyverse.ui.more.checkpoints.CheckpointsModel
import app.buddyverse.ui.more.checkpoints.CkTab
import app.buddyverse.ui.more.checkpoints.CommitSheet
import app.buddyverse.ui.more.checkpoints.ConfirmAsk
import app.buddyverse.ui.more.checkpoints.ConfirmSheet
import app.buddyverse.ui.more.checkpoints.Counts
import app.buddyverse.ui.more.checkpoints.DiffSheet
import app.buddyverse.ui.more.checkpoints.FileMenuSheet
import app.buddyverse.ui.more.checkpoints.LoadingBuddy
import app.buddyverse.ui.more.checkpoints.StatusTile
import app.buddyverse.ui.more.checkpoints.WorkspaceSheet
import app.buddyverse.ui.more.checkpoints.checkpointLabel
import app.buddyverse.ui.more.checkpoints.fileDir
import app.buddyverse.ui.more.checkpoints.fileName
import app.buddyverse.ui.more.checkpoints.rememberLast
import app.buddyverse.ui.more.checkpoints.shortPath
import app.buddyverse.ui.more.checkpoints.statSummary
import app.buddyverse.ui.more.checkpoints.workspaceTitle
import app.buddyverse.ui.parseIsoMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * More ▸ Checkpoints (hermes-api.md §2.6 Rollback / checkpoints + Git review), for one chat at a time:
 * - Checkpoints: the snapshots taken before the bot changed files; see what changed since, put one file back, or go back
 *   to it (which also undoes the chat's last turn); everything the chat changed in one diff.
 * - Changes: the git state of the chat's folder: branch, ahead/behind, the changed files with their diffs, stage, discard,
 *   commit (with a drafted message), push, pull request, branches and worktrees.
 */
@Composable
fun CheckpointsPage() {
    val store = LocalStore.current
    val t = S.checkpointsPage
    val s = S.current
    val toasts = LocalToasts.current
    val fb = LocalFeedback.current
    val uri = LocalUriHandler.current
    val m = remember(store) { CheckpointsModel(store, toasts, fb) }
    m.t = t
    m.openLabel = s.open
    m.openUrl = { url -> runCatching { uri.openUri(url) } }
    val bots by store.bots.collectAsState()
    val scope = rememberCoroutineScope()

    val roster = bots.map { it.profile to it.botChatId }
    LaunchedEffect(m, roster) { m.loadWorkspaces(bots) }
    // The gateway has no file watcher: a finished tool may have changed the folder (Desktop's workspace tick).
    LaunchedEffect(m) {
        store.gateway.events.filter { it.type == "tool.complete" }.collectLatest { delay(800); m.refresh() }
    }

    var hurt by remember { mutableStateOf(false) }
    LaunchedEffect(m.failedAt) { if (m.failedAt > 0) { hurt = true; delay(2_500); hurt = false } }
    val mood = when {
        hurt -> Mood.Error
        m.busy.isNotEmpty() -> Mood.Working
        m.reading -> Mood.Thinking
        else -> Mood.Idle
    }

    val w = m.selected
    val bot = w?.let { sel -> bots.firstOrNull { it.profile == sel.profile } }
    val spec = bot?.spec ?: specFor(w?.profile ?: bots.firstOrNull()?.profile ?: "default")

    var picking by remember { mutableStateOf(false) }
    var openCp by remember { mutableStateOf<Checkpoint?>(null) }
    var sessionOpen by remember { mutableStateOf(false) }
    var openFile by remember { mutableStateOf<ReviewFile?>(null) }
    var menuFile by remember { mutableStateOf<ReviewFile?>(null) }
    var committing by remember { mutableStateOf(false) }
    var branching by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<ConfirmAsk?>(null) }

    fun askGoBack(cp: Checkpoint) {
        ask = ConfirmAsk(t.goBackTitle, t.goBackBody, t.goBack, toggle = t.alsoMyEdits) { all -> openCp = null; m.restore(cp, null, all) }
    }
    fun askPutBack(cp: Checkpoint, file: String) {
        ask = ConfirmAsk(t.putFileTitle(fileName(file)), t.putFileBody, t.putBack, danger = false) { m.restore(cp, file, false) }
    }
    fun askDiscard(f: ReviewFile) {
        ask = ConfirmAsk(t.discardTitle(fileName(f.path)), t.discardBody, t.discard) { openFile = null; m.discard(f) }
    }

    Box(Modifier.fillMaxSize()) {
        PageFrame(MorePage.Checkpoints.label, actions = {
            IconButton(Icons.refresh, t.refresh, { fb.haptic(Haptic.Tick); m.refresh() }, enabled = w != null)
        }) {
            item(key = "who") { WhoRow(m, spec, mood, m.cue, w?.let { workspaceTitle(it, bot) }) { picking = true } }
            when {
                !m.wsLoaded -> item(key = "wait") { LoadingBuddy(spec) }
                m.wsError != null && m.workspaces.isEmpty() -> item(key = "err") { Message(m.wsError.orEmpty(), s.retry) { scope.launch { m.loadWorkspaces(bots) } } }
                w == null -> item(key = "none") { Message(t.noChats) }
                else -> {
                    item(key = "tabs") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip(t.checkpointsTab, m.tab == CkTab.Checkpoints, { m.tab = CkTab.Checkpoints }, icon = Icons.history)
                            Chip(t.changesTab, m.tab == CkTab.Changes, { m.tab = CkTab.Changes }, icon = Icons.edit)
                        }
                    }
                    if (m.tab == CkTab.Checkpoints) checkpointItems(m, t, spec, onOpen = { openCp = it }, onGoBack = ::askGoBack, onSession = { sessionOpen = true })
                    else changeItems(m, t, spec, onOpen = { openFile = it }, onMenu = { menuFile = it }, onCommit = { committing = true }, onBranches = { branching = true })
                }
            }
        }

        WorkspaceSheet(picking, m.workspaces, w, bots, spec, { picking = false }) { m.select(it) }

        val cpShown = rememberLast(openCp)
        CheckpointSheet(
            openCp, cpShown?.let { checkpointLabel(it.message, s, t) }.orEmpty(), cpShown?.let { whenText(it) }.orEmpty(), m, spec,
            onDismiss = { openCp = null }, onGoBack = ::askGoBack, onPutBack = ::askPutBack,
        )

        DiffSheet(
            sessionOpen, key = "session:" + w?.key, title = t.allChanges, subtitle = w?.cwd, spec = spec,
            load = {
                val d = m.sessionDiff()
                if (d == null) Pair(t.checkpointsOff, "") else Pair(sessionNote(d, t), d.diff)
            },
            onDismiss = { sessionOpen = false }, why = m::why,
        )

        val fileShown = rememberLast(openFile)
        val canStage = m.scope == ReviewScope.Uncommitted
        DiffSheet(
            openFile != null, key = fileShown?.let { "${w?.key}:${m.scope}:${it.path}:${it.staged}" },
            title = fileShown?.let { fileName(it.path) }.orEmpty(), subtitle = fileShown?.let { fileDir(it.path) }?.ifEmpty { null }, spec = spec,
            load = { null to (fileShown?.let { m.fileDiff(it) }.orEmpty()) },
            onDismiss = { openFile = null }, why = m::why,
            footer = {
                val f = fileShown
                if (canStage && f != null) Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val staged = m.shownFiles.firstOrNull { it.path == f.path }?.staged ?: f.staged
                    PillButton(t.discard, { askDiscard(f) }, Modifier.weight(1f), kind = ButtonKind.Quiet, icon = Icons.delete)
                    PillButton(if (staged) t.unstage else t.stage, { m.toggleStage(f.copy(staged = staged)); openFile = null }, Modifier.weight(1f),
                        kind = if (staged) ButtonKind.Outline else ButtonKind.Primary, icon = Icons.check)
                }
            },
        )

        FileMenuSheet(menuFile, canStage, { menuFile = null }, onView = { openFile = it }, onToggle = m::toggleStage, onDiscard = ::askDiscard)
        CommitSheet(committing, m, m.shownFiles.let { fs -> fs.count { it.staged }.takeIf { it > 0 } ?: fs.size }, spec) { committing = false }
        BranchSheet(branching, m, { branching = false }) { tree ->
            ask = ConfirmAsk(t.removeWorktreeTitle(fileName(tree.path)), t.removeWorktreeBody, t.remove, toggle = t.evenUnsaved) { force -> m.removeWorktree(tree, force) }
        }
        ConfirmSheet(ask) { ask = null }
    }
}

/** A bot not (yet) in the roster still gets its own look, seeded by its name. */
private fun specFor(profile: String) = BuddySpec(profile, profile, BuddyShape.parse(null, profile), BuddyColors.forName(profile))

/** "5m · a1b2c3d4": when it was taken (from the ISO stamp) and its short hash, always LTR. */
@Composable
private fun whenText(cp: Checkpoint): String {
    val s = S.current
    val at = parseIsoMillis(cp.timestamp)?.let { ago(it / 1000.0, nowMillis(), s) }.orEmpty()
    return listOf(at, ltr(cp.hash.take(8))).filter { it.isNotEmpty() }.joinToString(" · ")
}

private fun sessionNote(d: DiffText, t: CheckpointsPageStrings): String? {
    val shown = if (d.diff.isEmpty()) 0 else d.diff.lines().size
    return listOfNotNull(statSummary(d.stat), t.linesCut(shown, d.lines).takeIf { d.lines > shown }).joinToString(" · ").ifBlank { null }
}

private fun Modifier.smooth(scope: LazyItemScope): Modifier =
    with(scope) { this@smooth.animateItem(fadeInSpec = Motion.fade(), placementSpec = Motion.smooth(), fadeOutSpec = Motion.fade()) }

/** The chosen chat: its bot (alive, with the page's mood), title, folder and branch. Tap = pick another chat. */
@Composable
private fun WhoRow(m: CheckpointsModel, spec: BuddySpec, mood: Mood, cue: BuddyCue?, title: String?, onPick: () -> Unit) {
    val c = Theme.colors
    val t = S.checkpointsPage
    val w = m.selected
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), padding = 0.dp) {
        PressRow(onPick, minHeight = 76.dp, label = t.pickChat) {
            Buddy(spec, mood, 52.dp, cue = cue, interactive = false)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(title ?: t.pickChat, Theme.type.headline, maxLines = 1)
                val folder = w?.cwd?.let(::shortPath)
                Txt(listOfNotNull(folder ?: t.noFolder, w?.branch).joinToString(" · "), if (folder != null) Theme.type.code else Theme.type.sub, c.secondary, maxLines = 1)
            }
            Icon(Icons.expandMore, c.tertiary)
        }
    }
}

@Composable
private fun Message(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
        Txt(text, Theme.type.callout, Theme.colors.secondary)
        if (action != null) {
            Spacer(Modifier.size(12.dp))
            PillButton(action, onAction, kind = ButtonKind.Quiet, height = 48.dp, icon = Icons.refresh)
        }
    }
}

// ---------------------------------------------------------------- checkpoints tab

private fun androidx.compose.foundation.lazy.LazyListScope.checkpointItems(
    m: CheckpointsModel, t: CheckpointsPageStrings, spec: BuddySpec,
    onOpen: (Checkpoint) -> Unit, onGoBack: (Checkpoint) -> Unit, onSession: () -> Unit,
) {
    val list = m.checkpoints
    when {
        m.cpUnsupported -> item(key = "cp:uns") { Message(S.current.unsupported) }
        list == null && m.cpError != null -> item(key = "cp:err") { Message(m.cpError.orEmpty(), S.current.retry, m::refresh) }
        list == null -> item(key = "cp:wait") { LoadingBuddy(spec) }
        !list.enabled -> item(key = "cp:off") { Message(t.checkpointsOff) }
        else -> {
            item(key = "cp:all") {
                PressRow(onSession, minHeight = 64.dp, label = t.allChanges) {
                    Tile(Icons.description, Theme.colors.accent)
                    Spacer(Modifier.width(14.dp))
                    Txt(t.allChanges, Theme.type.headline, modifier = Modifier.weight(1f), maxLines = 1)
                    Icon(Icons.chevronRight, Theme.colors.tertiary, mirror = true)
                }
            }
            item(key = "cp:hdr") { SectionLabel(t.checkpointsTab) }
            if (list.items.isEmpty()) item(key = "cp:empty") { Message(t.noCheckpoints) }
            items(list.items, key = { "cp:" + it.hash }) { cp ->
                val restoring = "restore" in m.busy
                PressRow({ onOpen(cp) }, Modifier.smooth(this), minHeight = 64.dp, label = checkpointLabel(cp.message, S.current, t)) {
                    Tile(Icons.history, if (cp == list.items.first()) Theme.colors.accent else Theme.colors.secondary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(checkpointLabel(cp.message, S.current, t), Theme.type.headline, maxLines = 1)
                        Txt(whenText(cp), Theme.type.sub, Theme.colors.secondary, maxLines = 1)
                    }
                    IconButton(Icons.undo, t.goBack, { onGoBack(cp) }, tint = Theme.colors.secondary, enabled = !restoring)
                }
            }
        }
    }
}

@Composable
private fun Tile(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = .14f)), contentAlignment = Alignment.Center) { Icon(icon, tint, 22.dp) }
}

// ---------------------------------------------------------------- changes tab

private fun androidx.compose.foundation.lazy.LazyListScope.changeItems(
    m: CheckpointsModel, t: CheckpointsPageStrings, spec: BuddySpec,
    onOpen: (ReviewFile) -> Unit, onMenu: (ReviewFile) -> Unit, onCommit: () -> Unit, onBranches: () -> Unit,
) {
    val st = m.status
    when {
        m.gitUnsupported -> item(key = "g:uns") { Message(S.current.unsupported) }
        !m.gitLoaded -> item(key = "g:wait") { LoadingBuddy(spec) }
        m.notRepo -> item(key = "g:none") { Message(t.notRepo) }
        st == null -> item(key = "g:err") { Message(m.gitError ?: S.current.error, S.current.retry, m::refresh) }
        else -> {
            item(key = "g:status") { StatusCard(m, onBranches) }
            item(key = "g:scope") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(t.notCommitted, m.scope == ReviewScope.Uncommitted, { m.setScope(ReviewScope.Uncommitted) })
                    Chip(t.wholeBranch, m.scope == ReviewScope.Branch, { m.setScope(ReviewScope.Branch) })
                }
            }
            val files = m.shownFiles
            val canStage = m.scope == ReviewScope.Uncommitted
            if (files.isEmpty()) item(key = "g:clean") { Message(t.clean) }
            else if (canStage) item(key = "g:all") {
                val anyStaged = files.any { it.staged }
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(t.filesCount(files.size), Theme.type.caps, Theme.colors.secondary, Modifier.weight(1f), maxLines = 1)
                    PillButton(if (anyStaged) t.unstageAll else t.stageAll, m::stageAll, height = 48.dp, kind = ButtonKind.Quiet, enabled = "stageAll" !in m.busy)
                }
            }
            items(files, key = { "g:f:" + it.path }) { f -> FileRow(f, canStage, m, Modifier.smooth(this), { onOpen(f) }, { onMenu(f) }) }
            item(key = "g:actions") { ShipBar(m, t, canStage && files.isNotEmpty(), onCommit) }
        }
    }
}

@Composable
private fun StatusCard(m: CheckpointsModel, onBranches: () -> Unit) {
    val t = S.checkpointsPage
    val c = Theme.colors
    val st = m.status ?: return
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), padding = 0.dp) {
        PressRow(onBranches, minHeight = 72.dp, label = t.branches) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(if (st.detached) t.detached else st.branch ?: t.detached, Theme.type.headline.copy(fontFamily = Theme.type.mono), modifier = Modifier.weight(1f, fill = false), maxLines = 1)
                    if (st.ahead > 0) { Spacer(Modifier.width(8.dp)); Txt(ltr(t.ahead(st.ahead)), Theme.type.caption, c.accent, maxLines = 1) }
                    if (st.behind > 0) { Spacer(Modifier.width(6.dp)); Txt(ltr(t.behind(st.behind)), Theme.type.caption, c.needsYouText, maxLines = 1) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(t.filesCount(st.changed), Theme.type.sub, c.secondary, maxLines = 1)
                    if (st.added > 0 || st.removed > 0) { Spacer(Modifier.width(8.dp)); Counts(st.added, st.removed) }
                    if (st.conflicted > 0) { Spacer(Modifier.width(8.dp)); Txt(t.conflicted(st.conflicted), Theme.type.sub, c.errorText, maxLines = 1) }
                }
            }
            Icon(Icons.expandMore, c.tertiary)
        }
        m.ship?.pr?.let { pr ->
            PressRow({ m.openUrl(pr.url) }, minHeight = 56.dp, label = t.pr(pr.number, pr.state)) {
                Icon(Icons.link, c.accent, 22.dp)
                Spacer(Modifier.width(12.dp))
                Txt(t.pr(pr.number, pr.state.lowercase()), Theme.type.callout, c.accent, Modifier.weight(1f), maxLines = 1)
                Icon(Icons.chevronRight, c.tertiary, 20.dp, mirror = true)
            }
        }
    }
}

/** 64dp row: status letter, name over folder, +/−, and its one control: the 48dp stage check (Not committed only). */
@Composable
private fun FileRow(f: ReviewFile, canStage: Boolean, m: CheckpointsModel, modifier: Modifier, onOpen: () -> Unit, onMenu: () -> Unit) {
    val c = Theme.colors
    val t = S.checkpointsPage
    PressRow(onOpen, modifier, minHeight = 64.dp, onLongPress = onMenu, label = fileName(f.path)) {
        StatusTile(f.status)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(fileName(f.path), Theme.type.callout, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                fileDir(f.path).takeIf { it.isNotEmpty() }?.let { Txt(it, Theme.type.code, c.tertiary, Modifier.weight(1f, fill = false), maxLines = 1); Spacer(Modifier.width(8.dp)) }
                Counts(f.added, f.removed)
            }
        }
        if (canStage) IconButton(
            if (f.staged) Icons.checkCircleFilled else Icons.checkCircle, if (f.staged) t.unstage else t.stage, { m.toggleStage(f) },
            tint = if (f.staged) c.accent else c.tertiary,
        )
    }
}

/** Commit, push and pull request: only the ones that make sense right now. */
@Composable
private fun ShipBar(m: CheckpointsModel, t: CheckpointsPageStrings, canCommit: Boolean, onCommit: () -> Unit) {
    val st = m.status ?: return
    val ship = m.ship
    val canPr = ship != null && ship.ghReady && ship.pr == null && !st.detached && st.branch != null && st.branch != st.defaultBranch
    if (!canCommit && st.ahead <= 0 && !canPr) return
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (canCommit) PillButton(t.commit, onCommit, Modifier.fillMaxWidth(), icon = Icons.check, enabled = "commit" !in m.busy)
        if (st.ahead > 0) PillButton(t.push(st.ahead), m::push, Modifier.fillMaxWidth().heightIn(min = 56.dp),
            kind = if (canCommit) ButtonKind.Outline else ButtonKind.Primary, icon = Icons.arrowUpward, enabled = "push" !in m.busy)
        if (canPr) PillButton(t.openPr, m::createPr, Modifier.fillMaxWidth(), kind = ButtonKind.Outline, icon = Icons.link, enabled = "pr" !in m.busy)
    }
}
