package app.buddyverse.ui.more.kanban

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.core.nowMillis
import app.buddyverse.core.bool
import app.buddyverse.core.str
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Chip
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.PillButton
import app.buddyverse.design.Radius
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.kanbanPage
import app.buddyverse.ui.ago
import kotlinx.coroutines.launch

/** What the task form edits: a new card, or an existing card's title and details. */
sealed interface FormTarget {
    data object New : FormTarget
    data class Edit(val task: KTask) : FormTarget
}

sealed interface BoardTarget {
    data object New : BoardTarget
    data class Rename(val board: KBoard) : BoardTarget
}

/**
 * One card, everything Hermes keeps on it: status and the one-tap move, other moves, who works on it, priority,
 * details, summary / result / last error, linked cards, files, comments (+ write one), runs, the worker's log,
 * activity, and edit / write out / split / archive / delete. Shown while [id] != null.
 */
@Composable
fun TaskSheet(m: KanbanModel, id: String?, crew: List<BuddySpec>, nowMs: Long, onEdit: (KTask) -> Unit, onDismiss: () -> Unit) {
    val t = S.kanbanPage
    val s = S.current
    val c = Theme.colors
    val scope = rememberCoroutineScope()
    val shownId = rememberLast(id)
    val task = m.task(shownId)
    Sheet(id != null, onDismiss, title = task?.title, heightFraction = .92f) {
        if (task == null) return@Sheet
        val d = m.detail?.takeIf { it.id == task.id }
        val spec = m.specOf(task.assignee)
        val busy = task.id in m.thinking
        var draft by remember(task.id) { mutableStateOf("") }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (spec != null) Buddy(spec, if (busy) Mood.Thinking else moodOf(task), 64.dp, cue = m.cue?.takeIf { nowMillis() - it.serial < 2_000 })
                else StatusBubble(task.status, 64.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusPill(task.status)
                    val since = task.createdAt?.let { ago(it.toDouble(), nowMs, s) }.orEmpty()
                    Txt(listOfNotNull(spec?.name ?: t.noWorker, since.ifBlank { null }).joinToString(" · "), Theme.type.sub, c.secondary, maxLines = 2)
                }
            }
            primaryMove(task.status)?.let { to ->
                val stop = to == "todo" && task.status == "running"
                val icon = when {
                    to == "done" -> Icons.check
                    stop -> Icons.stop
                    to == "todo" -> Icons.undo
                    else -> Icons.playArrow
                }
                PillButton(primaryLabel(task.status), { m.move(task, to) }, Modifier.fillMaxWidth(),
                    kind = if (stop) ButtonKind.Danger else ButtonKind.Primary, icon = icon,
                    haptic = if (to == "done") Haptic.Confirm else Haptic.Press)
            }
            val others = movesFrom(task.status).filter { it != primaryMove(task.status) }
            if (others.isNotEmpty()) Column {
                Head(t.moveTo)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    others.forEach { to -> Chip(t.column(to), false, { m.move(task, to) }, icon = statusIcon(to)) }
                }
            }
            Column {
                Head(t.who)
                WhoPicker(crew, task.assignee) { m.assign(task, it) }
            }
            Column {
                Head(t.priority)
                PriorityChips(task.priority) { m.setPriority(task, it) }
            }
            (d?.body ?: task.body)?.let { TextBlock(t.body, it) }
            val summary = d?.summary ?: task.summary
            summary?.let { TextBlock(t.summary, it) }
            (d?.result ?: task.result)?.takeIf { it != summary }?.let { TextBlock(t.result, it) }
            task.lastError?.takeIf { task.failures > 0 && task.status != "done" }?.let { TextBlock(t.lastError, it, mono = true, error = true) }
            if (d != null && d.parents.isNotEmpty()) Column { Head(t.dependsOn); d.parents.forEach { l -> LinkRow(l) { m.open(l.id) } } }
            if (d != null && d.children.isNotEmpty()) Column { Head(t.subtasks); d.children.forEach { l -> LinkRow(l) { m.open(l.id) } } }
            if (d != null && d.files.isNotEmpty()) TextBlock(t.files, d.files.joinToString("\n"), mono = true)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Head(t.comments)
                d?.comments?.forEach { cm -> CommentBubble(cm.author, cm.body, ago(cm.at.toDouble(), nowMs, s)) }
                Row(verticalAlignment = Alignment.Bottom) {
                    Field(draft, { draft = it }, t.writeComment, Modifier.weight(1f), singleLine = false)
                    Spacer(Modifier.width(8.dp))
                    IconButton(Icons.arrowUpward, s.send, { m.comment(task, draft); draft = "" }, fill = c.accentFill, tint = c.onAccent,
                        enabled = draft.isNotBlank(), modifier = Modifier.padding(bottom = 4.dp))
                }
            }

            if (d != null && d.runs.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Head(t.runs)
                d.runs.take(10).forEach { r -> RunRow(r, m.specOf(r.profile), nowMs) }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Head(t.showLog)
                val log = m.log
                if (log == null) PillButton(t.showLog, { scope.launch { m.loadLog(task.id) } }, kind = ButtonKind.Quiet, icon = Icons.terminal, height = 48.dp)
                else Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface2).padding(12.dp)) {
                    Txt(log.ifBlank { t.noLog }, Theme.type.code, if (log.isBlank()) c.secondary else c.text)
                }
            }

            if (d != null && d.events.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Head(t.activity)
                d.events.take(20).forEach { e ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Txt(t.event(e.kind), Theme.type.sub, modifier = Modifier.weight(1f), maxLines = 1)
                        Txt(ago(e.at.toDouble(), nowMs, s), Theme.type.caption, c.secondary, maxLines = 1)
                    }
                }
            }

            Column {
                MenuRow(Icons.edit, t.editTask) { onEdit(task) }
                if (task.status == "triage") {
                    MenuRow(Icons.autoAwesome, t.specify, enabled = !busy) { m.specify(task) }
                    MenuRow(Icons.hub, t.decompose, enabled = !busy) { m.decompose(task) }
                }
                MenuRow(Icons.archive, t.archiveTask) { m.archive(task) }
                MenuRow(Icons.delete, s.delete, danger = true) { m.delete(task) }
            }
        }
    }
}

@Composable
private fun TextBlock(head: String, text: String, mono: Boolean = false, error: Boolean = false) {
    val c = Theme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Head(head, if (error) c.errorText else c.secondary)
        Txt(text, if (mono) Theme.type.code else Theme.type.callout, if (error) c.errorText else c.text)
    }
}

/** A comment: yours sit on the end side like your chat bubbles (design §9.3). */
@Composable
private fun CommentBubble(author: String, body: String, at: String) {
    val c = Theme.colors
    val mine = author == "user"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Column(
            Modifier.clip(RoundedCornerShape(Radius.bubble.dp)).background(if (mine) c.accentFill else c.surface2).padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (!mine) Txt(author, Theme.type.caption, c.secondary, maxLines = 1)
            Txt(body, Theme.type.callout, if (mine) c.onAccent else c.text)
        }
        Txt(at, Theme.type.micro, c.tertiary, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), maxLines = 1)
    }
}

@Composable
private fun RunRow(r: KRun, spec: BuddySpec?, nowMs: Long) {
    val s = S.current
    val c = Theme.colors
    val running = r.endedAt == null
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (spec != null) Buddy(spec, if (running) Mood.Working else if (r.failed) Mood.Error else Mood.Idle, 32.dp, interactive = false)
        else StatusBubble(if (running) "running" else "done", 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val took = r.endedAt?.let { e -> ((e - r.startedAt).coerceAtLeast(0)).let { sec -> if (sec < 60) s.unit(sec, "s") else if (sec < 3600) s.unit(sec / 60, "m") else s.unit(sec / 3600, "h") + " " + s.unit((sec % 3600) / 60, "m") } }
            Txt(listOfNotNull(spec?.name, r.outcome.replace('_', ' ').ifBlank { null }, took ?: ago(r.startedAt.toDouble(), nowMs, s)).joinToString(" · "),
                Theme.type.sub, if (r.failed) c.errorText else if (running) c.working else c.text, maxLines = 1)
            r.note?.let { Txt(it, Theme.type.caption, c.secondary, maxLines = 3) }
        }
    }
}

/** New card (title, details, who, priority, plan first) or edit an existing card's title and details. */
@Composable
fun TaskForm(m: KanbanModel, target: FormTarget?, crew: List<BuddySpec>, defaultWho: String?, onDismiss: () -> Unit) {
    val t = S.kanbanPage
    val s = S.current
    val shown = rememberLast(target)
    Sheet(target != null, onDismiss, title = if (shown is FormTarget.Edit) t.editTask else t.newTask) {
        val tg = shown ?: return@Sheet
        val edit = (tg as? FormTarget.Edit)?.task
        var title by remember(tg) { mutableStateOf(edit?.title.orEmpty()) }
        var body by remember(tg) { mutableStateOf(m.detail?.takeIf { it.id == edit?.id }?.body ?: edit?.body.orEmpty()) }
        var who by remember(tg) { mutableStateOf(defaultWho) }
        var prio by remember(tg) { mutableIntStateOf(0) }
        var plan by remember(tg) { mutableStateOf(false) }
        var err by remember(tg) { mutableStateOf<String?>(null) }
        var saving by remember(tg) { mutableStateOf(false) }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(title, { title = it; err = null }, t.title, Modifier.padding(horizontal = 20.dp), singleLine = false)
            Field(body, { body = it }, t.body, Modifier.padding(horizontal = 20.dp), singleLine = false, minHeight = 120.dp)
            if (edit == null) {
                Box(Modifier.padding(horizontal = 20.dp)) { Head(t.who) }
                WhoPicker(crew, who) { who = it }
                Box(Modifier.padding(horizontal = 20.dp)) { Head(t.priority) }
                PriorityChips(prio, Modifier.padding(horizontal = 20.dp)) { prio = it }
                ToggleRow(t.startInTriage, plan) { plan = it }
            }
            err?.let { Txt(it, Theme.type.sub, Theme.colors.errorText, Modifier.padding(horizontal = 20.dp)) }
            PillButton(
                if (edit == null) t.add else s.save,
                {
                    saving = true
                    val done: (String?) -> Unit = { e -> saving = false; err = e; if (e == null) onDismiss() }
                    if (edit == null) m.create(title, body, who, prio, plan, done) else m.edit(edit, title, body, done)
                },
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                icon = if (edit == null) Icons.add else Icons.check, enabled = !saving && title.isNotBlank(), haptic = Haptic.Confirm,
            )
        }
    }
}

/** Long-press menu of a card: open, every allowed move, edit, archive, delete. */
@Composable
fun TaskMenu(m: KanbanModel, task: KTask?, onDismiss: () -> Unit, onOpen: (KTask) -> Unit, onEdit: (KTask) -> Unit) {
    val t = S.kanbanPage
    val shown = rememberLast(task)
    Sheet(task != null, onDismiss, title = shown?.title) {
        val tk = shown ?: return@Sheet
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            PaddedMenuRow(Icons.chevronRight, t.openTask) { onDismiss(); onOpen(tk) }
            movesFrom(tk.status).forEach { to ->
                val label = if (tk.status == "running" && to == "todo") t.stopWorker else "${t.moveTo} ${t.column(to)}"
                PaddedMenuRow(statusIcon(to), label) { onDismiss(); m.move(tk, to) }
            }
            PaddedMenuRow(Icons.edit, t.editTask) { onDismiss(); onEdit(tk) }
            PaddedMenuRow(Icons.archive, t.archiveTask) { onDismiss(); m.archive(tk) }
            PaddedMenuRow(Icons.delete, S.current.delete, danger = true) { onDismiss(); m.delete(tk) }
        }
    }
}

/** New board, or rename one. */
@Composable
fun BoardForm(m: KanbanModel, target: BoardTarget?, onDismiss: () -> Unit) {
    val t = S.kanbanPage
    val s = S.current
    val shown = rememberLast(target)
    Sheet(target != null, onDismiss, title = if (shown is BoardTarget.Rename) t.renameBoard else t.newBoard) {
        val tg = shown ?: return@Sheet
        val old = (tg as? BoardTarget.Rename)?.board
        var name by remember(tg) { mutableStateOf(old?.name.orEmpty()) }
        var err by remember(tg) { mutableStateOf<String?>(null) }
        var saving by remember(tg) { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(name, { name = it; err = null }, t.boardName, Modifier.padding(horizontal = 20.dp))
            err?.let { Txt(it, Theme.type.sub, Theme.colors.errorText, Modifier.padding(horizontal = 20.dp)) }
            PillButton(
                if (old == null) s.create else s.save,
                {
                    saving = true
                    val done: (String?) -> Unit = { e -> saving = false; err = e; if (e == null) onDismiss() }
                    if (old == null) m.createBoard(name, done) else m.renameBoard(old.slug, name, done)
                },
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                icon = if (old == null) Icons.add else Icons.check, enabled = !saving && name.isNotBlank(), haptic = Haptic.Confirm,
            )
        }
    }
}

/** Long-press menu of a board: rename, make it the PC's main board, delete (never `default`). */
@Composable
fun BoardMenu(board: KBoard?, onDismiss: () -> Unit, onRename: (KBoard) -> Unit, onMain: (KBoard) -> Unit, onDelete: (KBoard) -> Unit) {
    val t = S.kanbanPage
    val shown = rememberLast(board)
    Sheet(board != null, onDismiss, title = shown?.name) {
        val b = shown ?: return@Sheet
        PaddedMenuRow(Icons.edit, S.current.rename) { onDismiss(); onRename(b) }
        if (!b.current) PaddedMenuRow(Icons.pushPin, t.setMain) { onDismiss(); onMain(b) }
        if (b.slug != "default") PaddedMenuRow(Icons.delete, t.deleteBoard, danger = true) { onDismiss(); onDelete(b) }
    }
}

/** Destructive confirm: Cancel and the red action, side by side. */
@Composable
fun ConfirmSheet(text: String?, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val shown = rememberLast(text)
    Sheet(text != null, onDismiss) {
        Txt(shown.orEmpty(), Theme.type.headline, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(S.current.cancel, onDismiss, Modifier.weight(1f), kind = ButtonKind.Quiet)
            PillButton(action, { onConfirm(); onDismiss() }, Modifier.weight(1f), kind = ButtonKind.Danger, haptic = Haptic.Reject)
        }
    }
}

/** The team's kanban setup (config.yaml `kanban.*`): who plans, who works by default, auto split, auto start. */
@Composable
fun TeamSheet(m: KanbanModel, visible: Boolean, crew: List<BuddySpec>, onDismiss: () -> Unit) {
    val t = S.kanbanPage
    Sheet(visible, onDismiss, title = t.orchestration) {
        val o = m.orchestration ?: return@Sheet
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) { Head(t.planner) }
            WhoPicker(crew, o.str("orchestrator_profile")?.ifBlank { null } ?: o.str("resolved_orchestrator_profile"), allowNobody = false) {
                m.setOrchestration("orchestrator_profile", it.orEmpty())
            }
            Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) { Head(t.defaultWorker) }
            WhoPicker(crew, o.str("default_assignee")?.ifBlank { null } ?: o.str("resolved_default_assignee"), allowNobody = false) {
                m.setOrchestration("default_assignee", it.orEmpty())
            }
            ToggleRow(t.autoSplit, o.bool("auto_decompose") != false) { m.setOrchestration("auto_decompose", it) }
            ToggleRow(t.autoStart, o.bool("auto_promote_children") != false) { m.setOrchestration("auto_promote_children", it) }
        }
    }
}
