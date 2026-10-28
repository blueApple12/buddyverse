package app.buddyverse.ui.more.checkpoints

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.core.Bot
import app.buddyverse.core.api.Checkpoint
import app.buddyverse.core.api.GitBranch
import app.buddyverse.core.api.ReviewFile
import app.buddyverse.core.api.Workspace
import app.buddyverse.core.api.Worktree
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Chip
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.PillButton
import app.buddyverse.design.PressRow
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Toggle
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.checkpointsPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private class Last<T> { var v: T? = null }

/** The latest non-null [value], so a closing sheet keeps showing what it showed. */
@Composable
internal fun <T : Any> rememberLast(value: T?): T? {
    val box = remember { Last<T>() }
    if (value != null) box.v = value
    return box.v
}

/** Last two path segments ("buddyverse\app"): the folder name survives where a full path would be cut. */
fun shortPath(path: String): String {
    val sep = if ('\\' in path) '\\' else '/'
    val parts = path.trimEnd('/', '\\').split('/', '\\').filter { it.isNotEmpty() }
    return parts.takeLast(2).joinToString(sep.toString()).ifEmpty { path }
}

/** A chat as the picker and header name it. */
@Composable
fun workspaceTitle(w: Workspace, bot: Bot?): String =
    if (w.main) S.checkpointsPage.mainChatOf(bot?.name ?: w.profile) else w.title ?: S.current.untitled

/** A living buddy thinking next to "Loading…" (no spinners, design rule 3). */
@Composable
fun LoadingBuddy(spec: BuddySpec, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Buddy(spec, Mood.Thinking, 44.dp, interactive = false)
        Spacer(Modifier.width(12.dp))
        Txt(S.current.loading, Theme.type.sub, Theme.colors.secondary, maxLines = 1)
    }
}

@Composable
private fun Label(text: String) = SectionLabel(text)

@Composable
private fun MenuRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = Theme.colors
    PressRow(onClick, minHeight = 56.dp, label = label) {
        Icon(icon, if (danger) c.error else c.text)
        Spacer(Modifier.width(16.dp))
        Txt(label, Theme.type.body, if (danger) c.errorText else c.text, maxLines = 1)
    }
}

// ---------------------------------------------------------------- which chat

@Composable
fun WorkspaceSheet(visible: Boolean, list: List<Workspace>, selected: Workspace?, bots: List<Bot>, fallback: BuddySpec, onDismiss: () -> Unit, onPick: (Workspace) -> Unit) {
    val t = S.checkpointsPage
    Sheet(visible, onDismiss, title = t.pickChat, heightFraction = .8f) {
        val mains = list.filter { it.main }
        val rest = list.filterNot { it.main }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (list.isEmpty()) item { Txt(t.noChats, Theme.type.body, Theme.colors.secondary, Modifier.padding(20.dp)) }
            if (mains.isNotEmpty()) item(key = "lm") { Label(t.mainChats) }
            items(mains, key = { "m" + it.key }) { w -> WorkspaceRow(w, w.key == selected?.key, bots, fallback) { onDismiss(); onPick(w) } }
            if (rest.isNotEmpty()) item(key = "lr") { Label(t.recentChats) }
            items(rest, key = { "r" + it.key }) { w -> WorkspaceRow(w, w.key == selected?.key, bots, fallback) { onDismiss(); onPick(w) } }
        }
    }
}

@Composable
private fun WorkspaceRow(w: Workspace, selected: Boolean, bots: List<Bot>, fallback: BuddySpec, onClick: () -> Unit) {
    val c = Theme.colors
    val bot = bots.firstOrNull { it.profile == w.profile }
    val title = workspaceTitle(w, bot)
    PressRow(onClick, minHeight = 64.dp, label = title) {
        Buddy(bot?.spec ?: fallback, if (selected) Mood.Listening else Mood.Idle, 40.dp, interactive = false)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, Theme.type.headline, maxLines = 1)
            val folder = w.cwd?.let(::shortPath)
            Txt(listOfNotNull(folder ?: S.checkpointsPage.noFolder, w.branch).joinToString(" · "),
                if (folder != null) Theme.type.code else Theme.type.sub, c.secondary, maxLines = 1)
        }
        if (selected) Icon(Icons.check, c.accent)
    }
}

// ---------------------------------------------------------------- one checkpoint

private sealed interface DiffState {
    data object Loading : DiffState
    data class Failed(val why: String) : DiffState
    @Immutable data class Ready(val note: String?, val lines: List<DiffLine>, val files: List<String> = emptyList()) : DiffState
}

/** A checkpoint: what changed since, each touched file with its own "put back", and "Go back to here" for all of it. */
@Composable
fun CheckpointSheet(
    cp: Checkpoint?, label: String, when_: String, m: CheckpointsModel, spec: BuddySpec,
    onDismiss: () -> Unit, onGoBack: (Checkpoint) -> Unit, onPutBack: (Checkpoint, String) -> Unit,
) {
    val t = S.checkpointsPage
    val c = Theme.colors
    val shown = rememberLast(cp)
    Sheet(cp != null, onDismiss, title = label, heightFraction = .9f) {
        val point = shown ?: return@Sheet
        var state by remember(point.hash) { mutableStateOf<DiffState>(DiffState.Loading) }
        LaunchedEffect(point.hash) {
            state = try {
                val d = m.checkpointDiff(point)
                val lines = CheckpointsModel.parse(d.diff)
                DiffState.Ready(statSummary(d.stat), lines, touchedFiles(d.stat, lines))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { DiffState.Failed(m.why(e)) }
        }
        Txt(when_, Theme.type.sub, c.secondary, Modifier.padding(horizontal = 20.dp).padding(bottom = 4.dp), maxLines = 1)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            when (val st = state) {
                DiffState.Loading -> item(key = "load") { LoadingBuddy(spec) }
                is DiffState.Failed -> item(key = "fail") { Txt(st.why, Theme.type.body, c.errorText, Modifier.padding(20.dp)) }
                is DiffState.Ready -> {
                    st.note?.let { n -> item(key = "note") { Txt(n, Theme.type.sub, c.secondary, Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) } }
                    if (st.files.isNotEmpty()) {
                        item(key = "lf") { Label(t.filesTitle) }
                        items(st.files, key = { "f$it" }) { f ->
                            PressRow({ onPutBack(point, f) }, minHeight = 56.dp, label = t.putFileBack) {
                                Icon(Icons.description, c.secondary, 22.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Txt(fileName(f), Theme.type.callout, maxLines = 1)
                                    fileDir(f).takeIf { it.isNotEmpty() }?.let { Txt(it, Theme.type.code, c.tertiary, maxLines = 1) }
                                }
                                IconButton(Icons.undo, t.putFileBack, { onPutBack(point, f) }, tint = c.secondary)
                            }
                        }
                    }
                    item(key = "ld") { Label(t.sinceThen) }
                    if (st.lines.isEmpty()) item(key = "none") { Txt(t.noChanges, Theme.type.body, c.secondary, Modifier.padding(horizontal = 20.dp)) }
                    else diffLines(st.lines)
                }
            }
        }
        PillButton(t.goBack, { onGoBack(point) }, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp),
            icon = Icons.undo, enabled = "restore" !in m.busy)
    }
}

// ---------------------------------------------------------------- any diff

/**
 * A diff in a tall sheet: the whole chat's changes or one git file. [load] returns a note (stat line, "showing N of M")
 * and the diff text; [footer] holds the file's actions.
 */
@Composable
fun DiffSheet(
    visible: Boolean, key: Any?, title: String, subtitle: String?, spec: BuddySpec,
    load: suspend () -> Pair<String?, String>, onDismiss: () -> Unit, why: (Throwable) -> String,
    footer: (@Composable () -> Unit)? = null,
) {
    val c = Theme.colors
    val t = S.checkpointsPage
    val shownTitle = rememberLast(title.takeIf { visible })
    Sheet(visible, onDismiss, title = shownTitle, heightFraction = .9f) {
        var state by remember(key) { mutableStateOf<DiffState>(DiffState.Loading) }
        LaunchedEffect(key) {
            state = try {
                val (note, diff) = load()
                DiffState.Ready(note, CheckpointsModel.parse(diff))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { DiffState.Failed(why(e)) }
        }
        subtitle?.let { Txt(it, Theme.type.code, c.secondary, Modifier.padding(horizontal = 20.dp).padding(bottom = 4.dp), maxLines = 2) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            when (val st = state) {
                DiffState.Loading -> item(key = "load") { LoadingBuddy(spec) }
                is DiffState.Failed -> item(key = "fail") { Txt(st.why, Theme.type.body, c.errorText, Modifier.padding(20.dp)) }
                is DiffState.Ready -> {
                    st.note?.let { n -> item(key = "note") { Txt(n, Theme.type.sub, c.secondary, Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) } }
                    if (st.lines.isEmpty()) item(key = "none") { Txt(t.noChanges, Theme.type.body, c.secondary, Modifier.padding(20.dp)) }
                    else diffLines(st.lines)
                }
            }
        }
        footer?.invoke()
    }
}

// ---------------------------------------------------------------- confirm

/** One question before something that can't be taken back. [toggle] adds an option the action receives. */
@Immutable
data class ConfirmAsk(
    val title: String,
    val body: String,
    val confirm: String,
    val toggle: String? = null,
    val danger: Boolean = true,
    val action: (Boolean) -> Unit,
)

@Composable
fun ConfirmSheet(ask: ConfirmAsk?, onDismiss: () -> Unit) {
    val s = S.current
    val shown = rememberLast(ask)
    Sheet(ask != null, onDismiss, title = shown?.title) {
        val a = shown ?: return@Sheet
        var on by remember(a) { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Txt(a.body, Theme.type.callout, Theme.colors.secondary)
            if (a.toggle != null) Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(a.toggle, Theme.type.callout, modifier = Modifier.weight(1f))
                Toggle(on, { on = it }, a.toggle)
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(s.cancel, onDismiss, Modifier.weight(1f), kind = ButtonKind.Quiet)
                PillButton(a.confirm, { onDismiss(); a.action(on) }, Modifier.weight(1f),
                    kind = if (a.danger) ButtonKind.Danger else ButtonKind.Primary, haptic = if (a.danger) Haptic.Reject else Haptic.Press)
            }
        }
    }
}

// ---------------------------------------------------------------- one changed file

@Composable
fun FileMenuSheet(f: ReviewFile?, canStage: Boolean, onDismiss: () -> Unit, onView: (ReviewFile) -> Unit, onToggle: (ReviewFile) -> Unit, onDiscard: (ReviewFile) -> Unit) {
    val t = S.checkpointsPage
    val shown = rememberLast(f)
    Sheet(f != null, onDismiss, title = shown?.let { fileName(it.path) }) {
        val file = shown ?: return@Sheet
        fileDir(file.path).takeIf { it.isNotEmpty() }?.let { Txt(it, Theme.type.code, Theme.colors.secondary, Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp), maxLines = 2) }
        MenuRow(Icons.visibility, t.viewChanges) { onDismiss(); onView(file) }
        if (canStage) {
            MenuRow(if (file.staged) Icons.checkCircle else Icons.checkCircleFilled, if (file.staged) t.unstage else t.stage) { onDismiss(); onToggle(file) }
            MenuRow(Icons.delete, t.discard, danger = true) { onDismiss(); onDiscard(file) }
        }
    }
}

/** M blue, A/? green, D red, R/C accent, U amber: a 36dp letter tile. */
@Composable
fun StatusTile(status: String) {
    val c = Theme.colors
    val color = when (status) {
        "A", "?" -> c.success
        "D" -> c.error
        "R", "C" -> c.accent
        "U" -> c.needsYou
        else -> c.working
    }
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
        Txt(if (status == "?") "+" else status, Theme.type.headline.copy(fontWeight = FontWeight.Bold), color, maxLines = 1)
    }
}

/** "+40 −12" in green and red. */
@Composable
fun Counts(added: Int, removed: Int) {
    val c = Theme.colors
    Row {
        if (added > 0) Txt("+$added", Theme.type.caption, c.success, maxLines = 1)
        if (added > 0 && removed > 0) Spacer(Modifier.width(6.dp))
        if (removed > 0) Txt("−$removed", Theme.type.caption, c.error, maxLines = 1)
    }
}

// ---------------------------------------------------------------- commit

/** Message (typed or drafted by the chat's model), "push too", commit. The draft survives closing the sheet. */
@Composable
fun CommitSheet(visible: Boolean, m: CheckpointsModel, count: Int, spec: BuddySpec, onDismiss: () -> Unit) {
    val t = S.checkpointsPage
    val c = Theme.colors
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var push by remember { mutableStateOf(false) }
    var drafting by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val committing = "commit" in m.busy
    Sheet(visible, onDismiss, title = t.commit) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Field(msg, { msg = it; err = null }, t.commitMessage, singleLine = false, minHeight = 120.dp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PillButton(if (msg.isBlank()) t.writeForMe else t.writeAgain, {
                    drafting = true; err = null
                    scope.launch {
                        try { msg = m.draftMessage(msg.ifBlank { null }) }
                        catch (e: CancellationException) { throw e } catch (e: Exception) { err = m.why(e) }
                        finally { drafting = false }
                    }
                }, kind = ButtonKind.Quiet, height = 48.dp, icon = Icons.autoAwesome, enabled = !drafting)
                Spacer(Modifier.width(12.dp))
                if (drafting) Buddy(spec, Mood.Thinking, 36.dp, interactive = false)
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(t.pushToo, Theme.type.callout, modifier = Modifier.weight(1f))
                Toggle(push, { push = it }, t.pushToo)
            }
            err?.let { Txt(it, Theme.type.sub, c.errorText, Modifier.padding(bottom = 8.dp)) }
            PillButton(if (committing) S.current.loading else t.commitFiles(count), {
                if (msg.isBlank()) { err = t.needMessage; return@PillButton }
                scope.launch {
                    val e = m.commit(msg, push)
                    if (e == null) { msg = ""; err = null; onDismiss() } else err = e
                }
            }, Modifier.fillMaxWidth(), icon = Icons.check, enabled = !committing && !drafting)
        }
    }
}

// ---------------------------------------------------------------- branches and worktrees

@Composable
fun BranchSheet(visible: Boolean, m: CheckpointsModel, onDismiss: () -> Unit, onRemove: (Worktree) -> Unit) {
    val t = S.checkpointsPage
    val c = Theme.colors
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var base by remember { mutableStateOf<String?>(null) }
    var making by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    Sheet(visible, onDismiss, title = t.branches, heightFraction = .85f) {
        LaunchedEffect(Unit) { m.loadBranches() }
        val current = m.status?.branch
        val local = m.branches.filter { !it.isRemote }
        val remote = m.branches.filter { it.isRemote }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(local, key = { "b" + it.name }) { b -> BranchRow(b, b.name == current, m) }
            if (remote.isNotEmpty()) {
                item(key = "lrem") { Label(t.remote) }
                items(remote, key = { "o" + it.name }) { b -> BranchRow(b, false, m) }
            }
            if (m.worktrees.isNotEmpty()) {
                item(key = "lwt") { Label(t.worktrees) }
                items(m.worktrees, key = { "w" + it.path }) { w ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.folder, c.secondary, 22.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Txt(fileName(w.path), Theme.type.callout, maxLines = 1)
                            Txt(listOfNotNull(w.branch ?: t.detached, t.mainTree.takeIf { w.isMain }).joinToString(" · "), Theme.type.code, c.tertiary, maxLines = 1)
                        }
                        if (!w.isMain) IconButton(Icons.delete, t.removeWorktree, { onRemove(w) }, tint = c.secondary, enabled = "worktree" !in m.busy)
                    }
                }
            }
            item(key = "new") {
                Column(Modifier.fillMaxWidth()) {
                    Label(t.newWorktree)
                    Field(name, { name = it.replace(' ', '-'); err = null }, t.worktreeName, Modifier.padding(horizontal = 20.dp), mono = true)
                    Txt(t.from.uppercase(), Theme.type.caps, c.secondary, Modifier.padding(start = 20.dp, top = 12.dp), maxLines = 1)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(t.fromHere, base == null, { base = null })
                        m.bases.take(10).forEach { b -> Chip(b.name, base == b.name, { base = b.name }) }
                    }
                    err?.let { Txt(it, Theme.type.sub, c.errorText, Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
                    PillButton(t.makeWorktree, {
                        making = true
                        scope.launch {
                            val e = m.addWorktree(name, base)
                            making = false
                            if (e == null) { name = ""; base = null } else err = e
                        }
                    }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), kind = ButtonKind.Quiet, icon = Icons.add,
                        enabled = name.isNotBlank() && !making)
                }
            }
        }
    }
}

@Composable
private fun BranchRow(b: GitBranch, current: Boolean, m: CheckpointsModel) {
    val t = S.checkpointsPage
    val c = Theme.colors
    val elsewhere = !current && b.worktreePath != null
    val content: @Composable RowScope.() -> Unit = {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (current) Icon(Icons.check, c.accent) else Box(Modifier.size(8.dp).clip(CircleShape).background(c.tertiary.copy(alpha = .5f)))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Txt(b.name, Theme.type.code.copy(fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal), if (elsewhere) c.tertiary else c.text, maxLines = 1)
            if (elsewhere) Txt(t.inOtherTree, Theme.type.caption, c.tertiary, maxLines = 1)
        }
        if (b.isDefault) Txt(t.defaultTag, Theme.type.micro, c.secondary,
            Modifier.clip(CircleShape).background(c.surface2).padding(horizontal = 8.dp, vertical = 2.dp), maxLines = 1)
    }
    if (current || elsewhere || "switch" in m.busy) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    } else {
        PressRow({ m.switchBranch(b) }, minHeight = 56.dp, label = b.name) { content() }
    }
}

/** Colored dot + text for the status card's second line. */
@Composable
internal fun Tag(text: String, color: Color) = Txt(text, Theme.type.sub, color, maxLines = 1)
