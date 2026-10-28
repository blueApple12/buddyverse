package app.buddyverse.ui.more.kanban

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.AppStore
import app.buddyverse.core.RestError
import app.buddyverse.core.RpcError
import app.buddyverse.core.api.kanbanBoard
import app.buddyverse.core.api.kanbanBoards
import app.buddyverse.core.api.kanbanComment
import app.buddyverse.core.api.kanbanCreate
import app.buddyverse.core.api.kanbanCreateBoard
import app.buddyverse.core.api.kanbanDecompose
import app.buddyverse.core.api.kanbanDelete
import app.buddyverse.core.api.kanbanDeleteBoard
import app.buddyverse.core.api.kanbanDispatch
import app.buddyverse.core.api.kanbanLog
import app.buddyverse.core.api.kanbanOrchestration
import app.buddyverse.core.api.kanbanPatch
import app.buddyverse.core.api.kanbanReassign
import app.buddyverse.core.api.kanbanRenameBoard
import app.buddyverse.core.api.kanbanSetOrchestration
import app.buddyverse.core.api.kanbanSpecify
import app.buddyverse.core.api.kanbanSwitchBoard
import app.buddyverse.core.api.kanbanTask
import app.buddyverse.core.bool
import app.buddyverse.core.child
import app.buddyverse.core.list
import app.buddyverse.core.long
import app.buddyverse.core.nowMillis
import app.buddyverse.core.obj
import app.buddyverse.core.str
import app.buddyverse.core.toJsonValue
import app.buddyverse.design.Feedback
import app.buddyverse.design.Haptic
import app.buddyverse.design.Sound
import app.buddyverse.i18n.KanbanPageStrings
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.Toasts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One card of `GET /board` (the full `Task` row plus the board's rollups). Times are epoch seconds. */
@Immutable
data class KTask(
    val id: String,
    val title: String,
    val body: String?,
    val assignee: String?,
    val status: String,
    val priority: Int,
    val createdAt: Long?,
    val completedAt: Long?,
    val runStartedAt: Long?,
    val summary: String?,
    val result: String?,
    val failures: Int,
    val lastError: String?,
    val comments: Int,
    val doneChildren: Int?,
    val totalChildren: Int?,
    val warnings: Int,
) {
    companion object {
        fun from(o: JsonObject): KTask? {
            val id = o.str("id") ?: return null
            val progress = o.child("progress")
            return KTask(
                id = id, title = o.str("title").orEmpty(), body = o.str("body")?.ifBlank { null },
                assignee = o.str("assignee")?.ifBlank { null }, status = o.str("status") ?: "todo",
                priority = (o.long("priority") ?: 0L).toInt(), createdAt = o.long("created_at"), completedAt = o.long("completed_at"),
                runStartedAt = o.long("current_run_started_at") ?: o.long("started_at"),
                summary = o.str("latest_summary")?.ifBlank { null }, result = o.str("result")?.ifBlank { null },
                failures = (o.long("consecutive_failures") ?: 0L).toInt(), lastError = o.str("last_failure_error")?.ifBlank { null },
                comments = (o.long("comment_count") ?: 0L).toInt(),
                doneChildren = progress.long("done")?.toInt(), totalChildren = progress.long("total")?.toInt(),
                warnings = (o.child("warnings").long("count") ?: 0L).toInt(),
            )
        }
    }
}

@Immutable
data class KBoard(val slug: String, val name: String, val total: Int, val current: Boolean) {
    companion object {
        fun from(o: JsonObject): KBoard? {
            val slug = o.str("slug") ?: return null
            if (o.bool("archived") == true) return null
            return KBoard(slug, o.str("name")?.ifBlank { null } ?: slug, (o.long("total") ?: 0L).toInt(), o.bool("is_current") == true)
        }
    }
}

@Immutable data class KComment(val author: String, val body: String, val at: Long)
@Immutable data class KEvent(val id: Long, val kind: String, val at: Long)
@Immutable data class KRun(val id: Long, val profile: String?, val outcome: String, val startedAt: Long, val endedAt: Long?, val note: String?, val failed: Boolean)
@Immutable data class KLink(val id: String, val title: String, val status: String)

/** `GET /tasks/{id}`: what the board card doesn't carry. */
@Immutable
data class KDetail(
    val id: String,
    val body: String?,
    val summary: String?,
    val result: String?,
    val comments: List<KComment>,
    val events: List<KEvent>,
    val runs: List<KRun>,
    val parents: List<KLink>,
    val children: List<KLink>,
    val files: List<String>,
) {
    companion object {
        fun from(r: JsonObject?): KDetail? {
            val task = r.child("task") ?: return null
            val id = task.str("id") ?: return null
            val linked = r.list("link_tasks").mapNotNull { it.obj }.associateBy { it.str("id").orEmpty() }
            fun links(key: String) = r.child("links").list(key).mapNotNull { (it as? JsonPrimitive)?.content }.map { lid ->
                val l = linked[lid]
                KLink(lid, l.str("title") ?: lid, l.str("status").orEmpty())
            }
            return KDetail(
                id = id, body = task.str("body")?.ifBlank { null }, summary = task.str("latest_summary")?.ifBlank { null },
                result = task.str("result")?.ifBlank { null },
                comments = r.list("comments").mapNotNull { it.obj }.map { KComment(it.str("author").orEmpty(), it.str("body").orEmpty(), it.long("created_at") ?: 0L) },
                events = r.list("events").mapNotNull { it.obj }.map { KEvent(it.long("id") ?: 0L, it.str("kind").orEmpty(), it.long("created_at") ?: 0L) }
                    .sortedByDescending { it.id },
                runs = r.list("runs").mapNotNull { it.obj }.map {
                    val outcome = it.str("outcome") ?: it.str("status").orEmpty()
                    KRun(it.long("id") ?: 0L, it.str("profile"), outcome, it.long("started_at") ?: 0L, it.long("ended_at"),
                        it.str("error")?.ifBlank { null } ?: it.str("summary")?.ifBlank { null }, it.str("error")?.isNotBlank() == true)
                }.sortedByDescending { it.id },
                parents = links("parents"), children = links("children"),
                files = r.list("attachments").mapNotNull { it.obj?.str("filename") },
            )
        }
    }
}

/**
 * Where a person may move a card from each status: `kanban_workflow.DEFAULT_WORKFLOW.manual`, minus the columns the
 * Desktop locks as drop targets (scheduled needs a wake time, review a reviewer, running is the dispatcher's).
 * ponytail: hard-coded like the server's DEFAULT_WORKFLOW; read `GET /workflow` once boards get their own workflows.
 */
private val MANUAL = mapOf(
    "triage" to listOf("todo", "ready"),
    "todo" to listOf("triage", "ready"),
    "scheduled" to listOf("triage", "todo", "ready"),
    "ready" to listOf("triage", "todo", "blocked", "done"),
    "running" to listOf("triage", "todo", "ready", "blocked", "done"),
    "blocked" to listOf("triage", "todo", "ready", "done"),
    "review" to listOf("triage", "todo", "ready", "done"),
    "done" to listOf("triage", "todo", "ready"),
    "archived" to listOf("triage", "todo", "ready"),
)

fun movesFrom(status: String): List<String> = MANUAL[status].orEmpty()

/** The one-tap move of a card: stop a worker, start a waiting task, finish a ready one, reopen a done one. */
fun primaryMove(status: String): String? = when (status) {
    "running" -> "todo"
    "triage", "todo", "blocked", "scheduled" -> "ready"
    "ready", "review" -> "done"
    "done" -> "todo"
    else -> null
}

/** Board slug from a display name: `[a-z0-9][a-z0-9-_]{0,63}`; Hebrew-only names get a numbered slug. */
fun slugOf(name: String, taken: Set<String>): String {
    val base = name.trim().lowercase().map { if (it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_') it else '-' }
        .joinToString("").replace(Regex("-{2,}"), "-").trimStart('-', '_').trimEnd('-').take(48)
        .ifEmpty { "board" }
    if (base !in taken) return base
    var n = 2
    while ("$base-$n" in taken) n++
    return "$base-$n"
}

private fun Throwable.why(): String = when (this) {
    is RestError -> detail
    is RpcError -> message ?: reason
    else -> message ?: toString()
}

/**
 * State and intents of More ▸ Kanban for one board. Writes run on the store's scope (they survive leaving the page),
 * show at once through [overrides], and nudge the dispatcher after each success (the Desktop's 400 ms debounce).
 * Live updates: the documented fallback polling (board 8 s, open card 4 s) driven by the page.
 * ponytail: polling, not the `/events` socket (it takes no named ticket; the bearer would ride in the URL). Add it when 8 s feels slow.
 */
@Stable
class KanbanModel(private val store: AppStore, private val toasts: Toasts, private val fb: Feedback) {
    private val api get() = store.api
    lateinit var t: KanbanPageStrings
    var undoLabel = "Undo"
    var errorLabel = "Something went wrong"

    var boards by mutableStateOf<List<KBoard>>(emptyList()); private set
    var board by mutableStateOf<String?>(null); private set
    private var snapshot by mutableStateOf<List<Pair<String, List<KTask>>>>(emptyList())
    /** Optimistic cards (id → card) shown over the snapshot until their write settles. */
    private val overrides = mutableStateMapOf<String, KTask>()
    var assignees by mutableStateOf<List<String>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var unsupported by mutableStateOf(false); private set
    /** Cards waiting out their undo window (delete / archive). */
    var hidden by mutableStateOf<Set<String>>(emptySet()); private set
    /** Cards with a helper-model call in flight (write out / split). */
    var thinking by mutableStateOf<Set<String>>(emptySet()); private set
    var cue by mutableStateOf<BuddyCue?>(null); private set
    var failedAt by mutableStateOf(0L); private set

    var detailId by mutableStateOf<String?>(null); private set
    var detail by mutableStateOf<KDetail?>(null); private set
    var log by mutableStateOf<String?>(null); private set
    var orchestration by mutableStateOf<JsonObject?>(null); private set

    private var seen: Map<String, KTask>? = null
    /** Card id → time of our own last write, so our own moves don't toast back at us. */
    private val mine = mutableMapOf<String, Long>()
    private var nudgeJob: Job? = null

    /** Columns in server order with optimistic cards applied; a card that moved goes first in its new column. */
    val columns: List<Pair<String, List<KTask>>>
        get() {
            val base = snapshot.flatMap { it.second }
            val known = base.associateBy { it.id }
            val all = base.map { overrides[it.id] ?: it } + overrides.values.filter { it.id !in known }
            val moved = all.filter { known[it.id]?.status != it.status }.map { it.id }.toSet()
            return snapshot.map { (name, _) ->
                val here = all.filter { it.status == name && it.id !in hidden }
                name to (here.filter { it.id in moved } + here.filter { it.id !in moved })
            }
        }

    fun task(id: String?): KTask? = id?.let { i -> overrides[i] ?: snapshot.firstNotNullOfOrNull { c -> c.second.firstOrNull { it.id == i } } }

    fun specOf(profile: String?): BuddySpec? {
        val p = profile ?: return null
        return store.bots.value.firstOrNull { it.profile == p }?.spec ?: BuddySpec(p, p, BuddyShape.parse(null, p), BuddyColors.forName(p))
    }

    private fun react(shot: OneShot) { cue = BuddyCue(nowMillis(), shot) }
    private fun ouch() { failedAt = nowMillis(); fb.sound(Sound.Bonk); fb.haptic(Haptic.Reject) }
    private fun fail(msg: String) { ouch(); toasts.show(ToastData(msg)) }

    // ---------------------------------------------------------------- reads

    suspend fun loadBoards() {
        try {
            val r = api.kanbanBoards()
            val list = r.list("boards").mapNotNull { it.obj?.let(KBoard::from) }
            boards = list
            unsupported = false
            if (board == null || list.none { it.slug == board }) {
                board = r.str("current")?.takeIf { c -> list.any { it.slug == c } } ?: list.firstOrNull()?.slug ?: "default"
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            loaded = true
            if (e is RestError && e.status == 404) unsupported = true else error = e.why()
        }
    }

    suspend fun refresh() {
        val b = board ?: return
        try {
            val r = api.kanbanBoard(b)
            if (b != board) return
            val cols = r.list("columns").mapNotNull { it.obj }.map { c -> c.str("name").orEmpty() to c.list("tasks").mapNotNull { it.obj?.let(KTask::from) } }
            notice(cols.flatMap { it.second })
            snapshot = cols
            assignees = r.list("assignees").mapNotNull { (it as? JsonPrimitive)?.content }
            loaded = true; error = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (b != board) return
            loaded = true
            if (e is RestError && e.status == 404 && boards.isEmpty()) unsupported = true else error = e.why()
        }
    }

    /** Toasts what changed on the PC since the last poll (the Desktop's completion notices); never our own moves. */
    private fun notice(tasks: List<KTask>) {
        val prev = seen
        seen = tasks.associateBy { it.id }
        if (prev == null) return
        val now = nowMillis()
        val theirs = tasks.filter { tk -> prev[tk.id] != null && (mine[tk.id]?.let { now - it > 15_000 } ?: true) }
        val done = theirs.filter { it.status == "done" && prev[it.id]?.status != "done" }
        val stuck = theirs.filter { it.status == "blocked" && prev[it.id]?.status != "blocked" }
        val broke = theirs.filter { it.failures > (prev[it.id]?.failures ?: 0) }
        when {
            done.size > 1 -> { toasts.show(ToastData(t.finishedMany(done.size))); fb.sound(Sound.Bloop); fb.haptic(Haptic.Confirm); react(OneShot.Done) }
            done.size == 1 -> {
                val d = done.first()
                toasts.show(ToastData(t.finished(d.title), buddy = specOf(d.assignee), shot = OneShot.Done))
                fb.sound(Sound.Bloop); fb.haptic(Haptic.Confirm); react(OneShot.Done)
            }
            stuck.isNotEmpty() -> {
                val s = stuck.first()
                toasts.show(ToastData(t.blocked(s.title), buddy = specOf(s.assignee), shot = OneShot.Startle))
                fb.sound(Sound.BoopBoop); fb.haptic(Haptic.Confirm); react(OneShot.Startle)
            }
            broke.isNotEmpty() -> { val f = broke.first(); toasts.show(ToastData(t.failed(f.title), buddy = specOf(f.assignee), shot = OneShot.Dizzy)); ouch() }
        }
    }

    fun pick(slug: String) {
        if (slug == board) return
        board = slug
        snapshot = emptyList(); overrides.clear(); hidden = emptySet(); seen = null; assignees = emptyList()
        loaded = false; error = null; detailId = null; detail = null
        react(OneShot.Hop)
    }

    fun open(id: String?) {
        if (id != detailId) { detail = null; log = null }
        detailId = id
    }

    suspend fun loadDetail(id: String) {
        val b = board ?: return
        try { val d = KDetail.from(api.kanbanTask(b, id)); if (id == detailId && d != null) detail = d }
        catch (e: CancellationException) { throw e } catch (_: Exception) { /* the card from the board stays on screen */ }
    }

    suspend fun loadLog(id: String) {
        val b = board ?: return
        val text = try { api.kanbanLog(b, id).str("content").orEmpty() }
        catch (e: CancellationException) { throw e } catch (e: Exception) { if (e is RestError && e.status == 404) "" else e.why() }
        if (id == detailId) log = text.takeLast(8_000)
    }

    fun hideLog() { log = null }

    // ---------------------------------------------------------------- writes

    private fun nudge(b: String) {
        nudgeJob?.cancel()
        nudgeJob = store.scope.launch { delay(400); runCatching { api.kanbanDispatch(b) } }
    }

    /** Runs [block] on the store scope; then re-reads the board (and the open card) and drops the optimistic card. */
    private fun write(id: String?, onError: (String) -> Unit = { fail(it) }, block: suspend (String) -> Unit) {
        val b = board ?: return
        if (id != null) mine[id] = nowMillis()
        store.scope.launch {
            val ok = try { block(b); true } catch (e: CancellationException) { throw e } catch (e: Exception) { onError(e.why()); false }
            if (ok) nudge(b)
            if (b == board) refresh()
            if (id != null) {
                overrides.remove(id)
                if (id == detailId) loadDetail(id)
            }
        }
    }

    fun move(task: KTask, to: String, undoable: Boolean = true) {
        if (to == task.status) return
        val from = task.status
        overrides[task.id] = task.copy(status = to)
        when (to) {
            "done" -> { fb.haptic(Haptic.Confirm); fb.sound(Sound.Bloop); react(OneShot.Done) }
            "ready" -> { fb.haptic(Haptic.Tick); react(OneShot.Hop) }
            else -> { fb.haptic(Haptic.Tick); react(OneShot.Nod) }
        }
        write(task.id) { b -> api.kanbanPatch(b, task.id, "status" to to) }
        val back = from.takeIf { undoable && it in movesFrom(to) }
        val text = if (from == "running" && to == "todo") t.stopped(task.title) else t.moved(task.title, t.column(to))
        toasts.show(ToastData(text, back?.let { undoLabel }, onAction = back?.let { b -> { move(task.copy(status = to), b, undoable = false) } },
            buddy = specOf(task.assignee), shot = if (to == "done") OneShot.Done else null))
    }

    fun primary(task: KTask) { primaryMove(task.status)?.let { move(task, it) } }

    fun assign(task: KTask, profile: String?) {
        if (task.assignee == profile) return
        overrides[task.id] = task.copy(assignee = profile)
        fb.haptic(Haptic.Tick); react(OneShot.Wave)
        write(task.id) { b -> api.kanbanReassign(b, task.id, profile, reclaimFirst = task.status == "running") }
    }

    fun setPriority(task: KTask, p: Int) {
        if (task.priority == p) return
        overrides[task.id] = task.copy(priority = p)
        fb.haptic(Haptic.Tick)
        write(task.id) { b -> api.kanbanPatch(b, task.id, "priority" to p) }
    }

    /** [done] gets null on success or the reason. */
    fun edit(task: KTask, title: String, body: String, done: (String?) -> Unit) {
        if (title.isBlank()) { done(t.needTitle); return }
        overrides[task.id] = task.copy(title = title.trim(), body = body.ifBlank { null })
        detail = detail?.takeIf { it.id == task.id }?.copy(body = body.ifBlank { null }) ?: detail
        write(task.id, onError = { ouch(); done(it) }) { b ->
            api.kanbanPatch(b, task.id, "title" to title.trim(), "body" to body)
            fb.haptic(Haptic.Confirm); react(OneShot.Nod); done(null)
        }
    }

    fun create(title: String, body: String, assignee: String?, priority: Int, triage: Boolean, done: (String?) -> Unit) {
        if (title.isBlank()) { done(t.needTitle); return }
        val b = board ?: return
        store.scope.launch {
            try {
                val r = api.kanbanCreate(b, title.trim(), body.ifBlank { null }, assignee, priority, triage)
                val tk = r.child("task")?.let(KTask::from)
                if (tk != null) { overrides[tk.id] = tk; mine[tk.id] = nowMillis() }
                fb.sound(Sound.Bloop); fb.haptic(Haptic.Confirm); react(OneShot.Learned)
                toasts.show(ToastData(r.str("warning") ?: t.added(title.trim()), buddy = specOf(assignee), shot = OneShot.Spawn))
                done(null)
                nudge(b)
                if (b == board) refresh()
                tk?.let { overrides.remove(it.id) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { ouch(); done(e.why()) }
        }
    }

    fun comment(task: KTask, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        detail = detail?.takeIf { it.id == task.id }?.let { it.copy(comments = it.comments + KComment("user", body, nowMillis() / 1000)) } ?: detail
        fb.sound(Sound.Pop); fb.haptic(Haptic.Confirm)
        write(task.id) { b -> api.kanbanComment(b, task.id, body) }
    }

    /** Ask the helper model to write a triage card out in full. */
    fun specify(task: KTask) = helper(task) { b ->
        val r = api.kanbanSpecify(b, task.id)
        if (r.bool("ok") == true) { toasts.show(ToastData(t.written)); fb.haptic(Haptic.Confirm); react(OneShot.Learned) }
        else fail(r.str("reason") ?: errorLabel)
    }

    /** Ask the helper model to split a triage card into sub-tasks. */
    fun decompose(task: KTask) = helper(task) { b ->
        val r = api.kanbanDecompose(b, task.id)
        if (r.bool("ok") == true) {
            toasts.show(ToastData(t.splitInto(r.list("child_ids").size))); fb.sound(Sound.Bloop); fb.haptic(Haptic.Confirm); react(OneShot.Spawn)
        } else fail(r.str("reason") ?: errorLabel)
    }

    private fun helper(task: KTask, block: suspend (String) -> Unit) {
        if (task.id in thinking) return
        thinking = thinking + task.id
        fb.haptic(Haptic.Press); react(OneShot.LookAround)
        write(task.id) { b -> try { block(b) } finally { thinking = thinking - task.id } }
    }

    fun delete(task: KTask) = later(task, t.deleted(task.title)) { b -> api.kanbanDelete(b, task.id) }
    fun archive(task: KTask) = later(task, t.archived(task.title)) { b -> api.kanbanPatch(b, task.id, "status" to "archived") }

    /** Hide now, write after the undo window. */
    private fun later(task: KTask, text: String, block: suspend (String) -> Unit) {
        val b = board ?: return
        hidden = hidden + task.id
        if (detailId == task.id) open(null)
        fb.haptic(Haptic.Reject)
        val job = store.scope.launch {
            delay(4_500)
            try { block(b) } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e.why()) }
            if (b == board) refresh()
            hidden = hidden - task.id
        }
        toasts.show(ToastData(text, undoLabel, onAction = { job.cancel(); hidden = hidden - task.id }, buddy = specOf(task.assignee), shot = OneShot.Leave))
    }

    // ---------------------------------------------------------------- boards

    fun createBoard(name: String, done: (String?) -> Unit) {
        if (name.isBlank()) { done(t.needName); return }
        val slug = slugOf(name, boards.map { it.slug }.toSet())
        store.scope.launch {
            try {
                val r = api.kanbanCreateBoard(slug, name.trim())
                loadBoards()
                pick(r.child("board").str("slug") ?: slug)
                toasts.show(ToastData(t.boardCreated(name.trim()))); fb.sound(Sound.Bloop); fb.haptic(Haptic.Confirm); react(OneShot.Learned)
                done(null)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { ouch(); done(e.why()) }
        }
    }

    fun renameBoard(slug: String, name: String, done: (String?) -> Unit) {
        if (name.isBlank()) { done(t.needName); return }
        boards = boards.map { if (it.slug == slug) it.copy(name = name.trim()) else it }
        store.scope.launch {
            try { api.kanbanRenameBoard(slug, name.trim()); fb.haptic(Haptic.Confirm); done(null) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { ouch(); done(e.why()) }
            loadBoards()
        }
    }

    fun deleteBoard(slug: String) {
        boards = boards.filter { it.slug != slug }
        if (board == slug) pick(boards.firstOrNull { it.current }?.slug ?: "default")
        fb.haptic(Haptic.Reject)
        store.scope.launch {
            try { api.kanbanDeleteBoard(slug) } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e.why()) }
            loadBoards()
        }
    }

    fun makeMain(slug: String) {
        boards = boards.map { it.copy(current = it.slug == slug) }
        fb.haptic(Haptic.Confirm); react(OneShot.Nod)
        store.scope.launch {
            try { api.kanbanSwitchBoard(slug) } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e.why()) }
            loadBoards()
        }
    }

    // ---------------------------------------------------------------- team setup (config.yaml kanban.*)

    fun loadOrchestration(quiet: Boolean = false) {
        store.scope.launch {
            try { orchestration = api.kanbanOrchestration() } catch (e: CancellationException) { throw e } catch (e: Exception) { if (!quiet) fail(e.why()) }
        }
    }

    fun setOrchestration(key: String, value: Any) {
        orchestration = orchestration?.let { JsonObject(it + (key to value.toJsonValue())) }
        fb.haptic(Haptic.Tick)
        store.scope.launch {
            try { api.kanbanSetOrchestration(key to value); orchestration = api.kanbanOrchestration() }
            catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e.why()); orchestration = runCatching { api.kanbanOrchestration() }.getOrNull() }
        }
    }
}
