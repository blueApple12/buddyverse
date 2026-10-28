package app.buddyverse.ui.more

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.nowMillis
import app.buddyverse.core.str
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.PressRow
import app.buddyverse.design.SectionLabel
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.kanbanPage
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.PageFrame
import app.buddyverse.ui.more.kanban.BoardForm
import app.buddyverse.ui.more.kanban.BoardHeader
import app.buddyverse.ui.more.kanban.BoardMenu
import app.buddyverse.ui.more.kanban.BoardTarget
import app.buddyverse.ui.more.kanban.ColumnChips
import app.buddyverse.ui.more.kanban.ConfirmSheet
import app.buddyverse.ui.more.kanban.FormTarget
import app.buddyverse.ui.more.kanban.KBoard
import app.buddyverse.ui.more.kanban.KMessage
import app.buddyverse.ui.more.kanban.KTask
import app.buddyverse.ui.more.kanban.KanbanModel
import app.buddyverse.ui.more.kanban.TaskForm
import app.buddyverse.ui.more.kanban.TaskMenu
import app.buddyverse.ui.more.kanban.TaskRow
import app.buddyverse.ui.more.kanban.TaskSheet
import app.buddyverse.ui.more.kanban.TeamSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DONE_PREVIEW = 8

/**
 * More ▸ Kanban (hermes-api.md §2.5): the PC's multi-agent task boards. Switch / add / rename / delete boards; cards by
 * column, each acted out by its bot's buddy; one-tap stop / start / done; a card sheet with every move, who, priority,
 * comments, runs, log and activity; new and edited cards; the team setup (planner, default worker, auto split / start).
 */
@Composable
fun KanbanPage() {
    val store = LocalStore.current
    val toasts = LocalToasts.current
    val fb = LocalFeedback.current
    val t = S.kanbanPage
    val s = S.current
    val scope = rememberCoroutineScope()
    val m = remember(store) { KanbanModel(store, toasts, fb) }
    m.t = t
    m.undoLabel = s.undo
    m.errorLabel = s.error
    val bots by store.bots.collectAsState()
    val crew = remember(bots, m.assignees) {
        bots.filter { !it.hidden }.map { it.spec } + m.assignees.filter { a -> bots.none { it.profile == a } }.mapNotNull { m.specOf(it) }
    }
    var now by remember { mutableLongStateOf(nowMillis()) }

    LaunchedEffect(m) { m.loadBoards(); m.loadOrchestration(quiet = true) }
    LaunchedEffect(m.board) {
        if (m.board == null) return@LaunchedEffect
        while (true) { m.refresh(); now = nowMillis(); delay(8_000) }
    }
    LaunchedEffect(m.detailId) {
        val id = m.detailId ?: return@LaunchedEffect
        while (true) {
            m.loadDetail(id)
            if (m.log != null && m.task(id)?.status == "running") m.loadLog(id)
            now = nowMillis()
            delay(4_000)
        }
    }

    val cols = m.columns
    val total = cols.sumOf { it.second.size }
    val running = cols.firstOrNull { it.first == "running" }?.second?.size ?: 0
    val stuck = cols.firstOrNull { it.first == "blocked" }?.second?.size ?: 0
    var hurt by remember { mutableStateOf(false) }
    LaunchedEffect(m.failedAt) { if (m.failedAt > 0) { hurt = true; delay(2_500); hurt = false } }
    val mood = when {
        m.unsupported -> Mood.Sleeping
        !m.loaded -> Mood.Thinking
        hurt || (m.error != null && total == 0) -> Mood.Error
        m.thinking.isNotEmpty() -> Mood.Thinking
        stuck > 0 -> Mood.NeedsYou
        running > 0 -> Mood.Working
        else -> Mood.Idle
    }
    val planner = m.orchestration.str("resolved_orchestrator_profile")
    val headSpec = planner?.let { p -> bots.firstOrNull { it.profile == p }?.spec } ?: bots.firstOrNull { it.isDefault }?.spec ?: bots.firstOrNull()?.spec
    var poke by remember { mutableStateOf<BuddyCue?>(null) }
    // Serials are timestamps: show the newest, and never replay an old one on a freshly composed buddy.
    val cue = listOfNotNull(m.cue, poke).maxByOrNull { it.serial }?.takeIf { nowMillis() - it.serial < 2_000 }

    var filter by remember(m.board) { mutableStateOf<String?>(null) }
    var form by remember { mutableStateOf<FormTarget?>(null) }
    var menu by remember { mutableStateOf<KTask?>(null) }
    var boardForm by remember { mutableStateOf<BoardTarget?>(null) }
    var boardMenu by remember { mutableStateOf<KBoard?>(null) }
    var dropBoard by remember { mutableStateOf<KBoard?>(null) }
    var team by remember { mutableStateOf(false) }
    val boardName = m.boards.firstOrNull { it.slug == m.board }?.name ?: m.board.orEmpty()
    val summary = listOfNotNull(
        t.tasksCount(total),
        running.takeIf { it > 0 }?.let(t.runningCount),
        stuck.takeIf { it > 0 }?.let(t.stuckCount),
    ).joinToString(" · ")

    @Composable
    fun Card(tk: KTask, modifier: Modifier) =
        TaskRow(tk, m.specOf(tk.assignee), now, onOpen = { m.open(tk.id) }, onMenu = { menu = tk }, onPrimary = { m.primary(tk) }, modifier = modifier)

    Box(Modifier.fillMaxSize()) {
        PageFrame(s.kanban, actions = {
            IconButton(Icons.tune, t.orchestration, { team = true; m.loadOrchestration() }, enabled = !m.unsupported)
            IconButton(Icons.add, t.newTask, { form = FormTarget.New }, enabled = m.board != null && !m.unsupported)
        }) {
            item(key = "head") {
                BoardHeader(
                    headSpec, mood, cue, boardName.ifEmpty { s.kanban }, if (m.loaded) summary else s.loading, m.boards, m.board,
                    onBuddy = { poke = BuddyCue(nowMillis(), OneShot.Giggle); scope.launch { m.loadBoards(); m.refresh() } },
                    onPick = { m.pick(it.slug) }, onBoardMenu = { boardMenu = it }, onNewBoard = { boardForm = BoardTarget.New },
                )
            }
            when {
                m.unsupported -> item(key = "msg") { KMessage(s.unsupported) }
                !m.loaded -> Unit
                m.error != null && total == 0 -> item(key = "msg") {
                    KMessage(m.error.orEmpty(), s.retry, Icons.refresh) { scope.launch { m.loadBoards(); m.refresh() } }
                }
                total == 0 -> item(key = "msg") { KMessage(t.emptyBoard, t.newTask, Icons.add) { form = FormTarget.New } }
                else -> {
                    item(key = "chips") { ColumnChips(cols.map { it.first to it.second.size }, filter) { filter = it } }
                    val picked = filter
                    if (picked == null) {
                        cols.filter { it.second.isNotEmpty() }.forEach { (name, tasks) ->
                            item(key = "sec:$name") { SectionLabel("${t.column(name)} · ${tasks.size}") }
                            val shown = if (name == "done") tasks.take(DONE_PREVIEW) else tasks
                            items(shown, key = { "t:" + it.id }) { tk ->
                                Card(tk, Modifier.animateItem(fadeInSpec = Motion.fade(), placementSpec = Motion.smooth(), fadeOutSpec = Motion.fade()))
                            }
                            if (name == "done" && tasks.size > DONE_PREVIEW) item(key = "more:done") {
                                PressRow({ filter = "done" }, minHeight = 56.dp, label = t.showAllDone(tasks.size)) {
                                    Txt(t.showAllDone(tasks.size), Theme.type.callout, Theme.colors.accent, maxLines = 1)
                                }
                            }
                        }
                    } else {
                        val tasks = cols.firstOrNull { it.first == picked }?.second.orEmpty()
                        if (tasks.isEmpty()) item(key = "msg") { KMessage(t.emptyColumn) }
                        else items(tasks, key = { "t:" + it.id }) { tk ->
                            Card(tk, Modifier.animateItem(fadeInSpec = Motion.fade(), placementSpec = Motion.smooth(), fadeOutSpec = Motion.fade()))
                        }
                    }
                }
            }
        }
        TaskSheet(m, m.detailId?.takeIf { it !in m.hidden }, crew, now, onEdit = { form = FormTarget.Edit(it) }) { m.open(null) }
        TaskMenu(m, menu, onDismiss = { menu = null }, onOpen = { m.open(it.id) }, onEdit = { form = FormTarget.Edit(it) })
        TaskForm(m, form, crew, m.orchestration.str("default_assignee")?.ifBlank { null }) { form = null }
        BoardMenu(boardMenu, onDismiss = { boardMenu = null }, onRename = { boardForm = BoardTarget.Rename(it) }, onMain = { m.makeMain(it.slug) },
            onDelete = { dropBoard = it })
        BoardForm(m, boardForm) { boardForm = null }
        ConfirmSheet(dropBoard?.let { t.confirmDeleteBoard(it.name) }, t.deleteBoard, onConfirm = { dropBoard?.let { m.deleteBoard(it.slug) } }) { dropBoard = null }
        TeamSheet(m, team, crew) { team = false }
    }
}
