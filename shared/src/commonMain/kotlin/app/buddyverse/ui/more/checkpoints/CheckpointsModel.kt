package app.buddyverse.ui.more.checkpoints

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.AppStore
import app.buddyverse.core.Bot
import app.buddyverse.core.RestError
import app.buddyverse.core.RpcError
import app.buddyverse.core.api.BaseBranch
import app.buddyverse.core.api.Checkpoint
import app.buddyverse.core.api.CheckpointList
import app.buddyverse.core.api.DiffText
import app.buddyverse.core.api.GitBranch
import app.buddyverse.core.api.RepoStatus
import app.buddyverse.core.api.ReviewFile
import app.buddyverse.core.api.ReviewScope
import app.buddyverse.core.api.ShipInfo
import app.buddyverse.core.api.Workspace
import app.buddyverse.core.api.Worktree
import app.buddyverse.core.api.gitAddWorktree
import app.buddyverse.core.api.gitBaseBranches
import app.buddyverse.core.api.gitBranches
import app.buddyverse.core.api.gitCommit
import app.buddyverse.core.api.gitCreatePr
import app.buddyverse.core.api.gitDiscard
import app.buddyverse.core.api.gitDraftMessage
import app.buddyverse.core.api.gitPush
import app.buddyverse.core.api.gitRemoveWorktree
import app.buddyverse.core.api.gitReviewDiff
import app.buddyverse.core.api.gitReviewList
import app.buddyverse.core.api.gitShipInfo
import app.buddyverse.core.api.gitStage
import app.buddyverse.core.api.gitStatus
import app.buddyverse.core.api.gitSwitchBranch
import app.buddyverse.core.api.gitUnstage
import app.buddyverse.core.api.gitWorktrees
import app.buddyverse.core.api.recentWorkspaces
import app.buddyverse.core.api.rollbackDiff
import app.buddyverse.core.api.rollbackList
import app.buddyverse.core.api.rollbackRestore
import app.buddyverse.core.api.rollbackSessionDiff
import app.buddyverse.core.api.workspaceOf
import app.buddyverse.core.nowMillis
import app.buddyverse.design.Feedback
import app.buddyverse.design.Haptic
import app.buddyverse.design.Sound
import app.buddyverse.i18n.CheckpointsPageStrings
import app.buddyverse.i18n.Strings
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.Toasts
import app.buddyverse.ui.toolLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CkTab { Checkpoints, Changes }

private val PRE_ROLLBACK = Regex("""^pre-rollback snapshot \(restoring to ([0-9a-fA-F]+)\)$""")

/** The manager's reason in words: "before terminal: npm test" → "Before: Run npm test" (no raw tool names, design §3.2). */
fun checkpointLabel(raw: String, s: Strings, t: CheckpointsPageStrings): String {
    val m = raw.trim()
    PRE_ROLLBACK.find(m)?.let { return t.beforeGoingBack(it.groupValues[1]) }
    if (m.startsWith("before ")) {
        val rest = m.removePrefix("before ")
        val ctx = rest.substringAfter(':', "").trim().ifBlank { null }
        return t.before(toolLabel(rest.substringBefore(':').trim(), ctx, s))
    }
    return if (m.isBlank() || m == "auto") t.autoSnapshot else m
}

/** Bot Chats first (in roster order), then the other recent chats, newest first; one row per session. */
fun mergeWorkspaces(mains: List<Workspace>, recents: List<Workspace>): List<Workspace> {
    val seen = mains.map { it.key }.toSet()
    return mains + recents.filter { it.key !in seen }.distinctBy { it.key }.sortedByDescending { it.lastActive }
}

/** The newest chat that works in a folder, else the first one. */
fun defaultWorkspace(all: List<Workspace>): Workspace? = all.filter { it.cwd != null }.maxByOrNull { it.lastActive } ?: all.firstOrNull()

/** Files as shown: discarded ones gone, optimistic stage flips applied. */
fun visibleFiles(files: List<ReviewFile>, stagedNow: Map<String, Boolean>, gone: Set<String>): List<ReviewFile> =
    files.filter { it.path !in gone }.map { f -> stagedNow[f.path]?.let { f.copy(staged = it) } ?: f }

/**
 * State and intents of More ▸ Checkpoints for one chosen chat ([selected]): its checkpoints (`rollback.*`) and the git
 * state of its folder (`/api/git/…`). Writes run on the store's scope, so they finish even if the page closes; reads
 * that land for a chat no longer selected are dropped.
 */
@Stable
class CheckpointsModel(private val store: AppStore, private val toasts: Toasts, private val fb: Feedback) {
    private val api get() = store.api
    lateinit var t: CheckpointsPageStrings
    var openUrl: (String) -> Unit = {}
    var openLabel = "Open"

    var workspaces by mutableStateOf<List<Workspace>>(emptyList()); private set
    var wsLoaded by mutableStateOf(false); private set
    var wsError by mutableStateOf<String?>(null); private set
    var selected by mutableStateOf<Workspace?>(null); private set
    var tab by mutableStateOf(CkTab.Checkpoints)

    var checkpoints by mutableStateOf<CheckpointList?>(null); private set
    var cpError by mutableStateOf<String?>(null); private set
    var cpUnsupported by mutableStateOf(false); private set

    var status by mutableStateOf<RepoStatus?>(null); private set
    var gitLoaded by mutableStateOf(false); private set
    var notRepo by mutableStateOf(false); private set
    var gitError by mutableStateOf<String?>(null); private set
    var gitUnsupported by mutableStateOf(false); private set
    var scope by mutableStateOf(ReviewScope.Uncommitted); private set
    private var files by mutableStateOf<List<ReviewFile>>(emptyList())
    private var stagedNow by mutableStateOf<Map<String, Boolean>>(emptyMap())
    private var gone by mutableStateOf<Set<String>>(emptySet())
    var ship by mutableStateOf<ShipInfo?>(null); private set

    var branches by mutableStateOf<List<GitBranch>>(emptyList()); private set
    var bases by mutableStateOf<List<BaseBranch>>(emptyList()); private set
    var worktrees by mutableStateOf<List<Worktree>>(emptyList()); private set

    /** Writes in flight, by name ("restore", "commit", "push", "pr", "switch", "stage:<path>"…). */
    var busy by mutableStateOf<Set<String>>(emptySet()); private set
    private var reads by mutableIntStateOf(0)
    val reading: Boolean get() = reads > 0
    /** Serial = timestamp, so a buddy composed later can skip a stale cue. */
    var cue by mutableStateOf<BuddyCue?>(null); private set
    var failedAt by mutableStateOf(0L); private set

    val shownFiles: List<ReviewFile> get() = visibleFiles(files, stagedNow, gone)
    val gitPath: String? get() = selected?.gitPath

    // ------------------------------------------------------------ plumbing

    private fun react(shot: OneShot) { cue = BuddyCue(nowMillis(), shot) }
    private fun ouch() { failedAt = nowMillis(); fb.sound(Sound.Bonk); fb.haptic(Haptic.Reject) }
    private fun fail(msg: String) { ouch(); toasts.show(ToastData(msg)) }
    private fun done(msg: String, shot: OneShot, sound: Boolean = true) {
        react(shot); fb.haptic(Haptic.Confirm); if (sound) fb.sound(Sound.Bloop); toasts.show(ToastData(msg))
    }
    private fun stale(w: Workspace) = w.key != selected?.key

    fun why(e: Throwable): String {
        if (e is RpcError) return when {
            e.code == 4009 || e.reason == "session_busy" -> t.busyChat
            e.reason == "unsupported_terminal_backend" -> t.container
            e.reason == "invalid_checkpoint" -> t.checkpointGone
            e.reason == "not_found" -> t.chatGone
            e.reason == "permission_denied" -> t.notAllowed
            else -> e.message?.takeIf { it.isNotBlank() } ?: e.reason
        }
        return (e as? RestError)?.detail?.takeIf { it.isNotBlank() } ?: e.message ?: e.toString()
    }

    /** Runs [block] on the store's scope (survives the sheet or page closing) and waits for it here. */
    private suspend fun <T> detached(block: suspend () -> T): T = store.scope.async { block() }.await()

    private inline fun guard(name: String, block: () -> Unit) {
        if (name in busy) return
        busy = busy + name
        block()
    }
    private fun free(name: String) { busy = busy - name }

    // ------------------------------------------------------------ workspaces

    /** Every bot's main chat plus the recent chats, with their folders; keeps the user's pick if it still exists. */
    suspend fun loadWorkspaces(bots: List<Bot>) {
        reads++
        try {
            var recentsFailed: Exception? = null
            val (mains, recents) = coroutineScope {
                val m = bots.mapNotNull { b ->
                    b.botChatId?.let { id -> async { quiet { api.workspaceOf(id, b.profile, main = true) } } }
                }
                val r = async {
                    try { api.recentWorkspaces() } catch (e: CancellationException) { throw e } catch (e: Exception) { recentsFailed = e; null }
                }
                m.awaitAll().filterNotNull() to r.await()
            }
            if (recents == null && mains.isEmpty()) recentsFailed?.let { throw it }
            val all = mergeWorkspaces(mains, recents.orEmpty())
            workspaces = all
            wsLoaded = true
            wsError = null
            val keep = selected?.let { s -> all.firstOrNull { it.key == s.key } }
            if (keep != null) selected = keep else select(defaultWorkspace(all), user = false)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            wsLoaded = true
            if (workspaces.isEmpty()) wsError = why(e)
        } finally { reads-- }
    }

    private suspend fun <T> quiet(block: suspend () -> T): T? =
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    fun select(w: Workspace?, user: Boolean = true) {
        if (w?.key == selected?.key && w != null) return
        selected = w
        checkpoints = null; cpError = null; cpUnsupported = false
        status = null; gitLoaded = false; notRepo = false; gitError = null; gitUnsupported = false
        files = emptyList(); stagedNow = emptyMap(); gone = emptySet(); ship = null
        branches = emptyList(); worktrees = emptyList(); bases = emptyList()
        if (user) react(OneShot.LookAround)
        refresh()
    }

    /** Re-reads both halves for the chosen chat. */
    fun refresh() {
        val w = selected ?: return
        store.scope.launch { readCheckpoints(w) }
        store.scope.launch { readGit(w) }
    }

    // ------------------------------------------------------------ checkpoints

    private suspend fun readCheckpoints(w: Workspace) {
        reads++
        try {
            val l = api.rollbackList(w.sessionId, w.profile)
            if (stale(w)) return
            checkpoints = l; cpError = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (stale(w)) return
            if (e is RpcError && e.unknownMethod) cpUnsupported = true else if (checkpoints == null) cpError = why(e)
        } finally { reads-- }
    }

    suspend fun checkpointDiff(cp: Checkpoint): DiffText {
        val w = selected ?: throw IllegalStateException(t.chatGone)
        return api.rollbackDiff(w.sessionId, w.profile, cp.hash)
    }

    /** Null = checkpoints are off. */
    suspend fun sessionDiff(): DiffText? {
        val w = selected ?: throw IllegalStateException(t.chatGone)
        return detached { api.rollbackSessionDiff(w.sessionId, w.profile) }
    }

    /** Whole tree when [file] is null (also rewinds the chat's last turn), else just that file. */
    fun restore(cp: Checkpoint, file: String?, all: Boolean) {
        val w = selected ?: return
        guard("restore") {
            store.scope.launch {
                try {
                    val r = api.rollbackRestore(w.sessionId, w.profile, cp.hash, file, all)
                    if (!r.success) { fail(r.error ?: t.checkpointGone); return@launch }
                    val head = if (file != null) t.fileBack(fileName(file)) else t.wentBack(r.restoredTo ?: cp.hash.take(8))
                    val notes = listOfNotNull(
                        t.turnUndone.takeIf { r.historyRemoved > 0 },
                        r.skippedUserEdits.size.takeIf { it > 0 }?.let(t.keptYourEdits),
                    )
                    done((listOf(head) + notes).joinToString(" · "), OneShot.Return)
                    if (r.historyRemoved > 0) store.refreshSessions()
                    if (!stale(w)) { readCheckpoints(w); readGit(w) }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(why(e)) } finally { free("restore") }
            }
        }
    }

    // ------------------------------------------------------------ git

    private suspend fun readGit(w: Workspace) {
        val path = w.gitPath ?: run { if (!stale(w)) { notRepo = true; gitLoaded = true }; return }
        reads++
        try {
            val st = api.gitStatus(path, w.profile)
            if (stale(w)) return
            status = st
            notRepo = st == null
            if (st != null) {
                val sc = scope
                val list = api.gitReviewList(path, sc, w.profile)
                if (stale(w) || sc != scope) return
                files = list; stagedNow = emptyMap(); gone = emptySet()
            }
            gitLoaded = true; gitError = null
            if (st != null) store.scope.launch { quiet { api.gitShipInfo(path, w.profile) }?.let { if (!stale(w)) ship = it } }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (stale(w)) return
            gitLoaded = true
            if (e is RestError && e.status == 404) gitUnsupported = true else if (status == null) gitError = why(e)
        } finally { reads-- }
    }

    @kotlin.jvm.JvmName("pickScope")
    fun setScope(s: ReviewScope) {
        if (s == scope) return
        scope = s
        files = emptyList()
        selected?.let { w -> store.scope.launch { readGit(w) } }
    }

    suspend fun fileDiff(f: ReviewFile): String {
        val w = selected ?: return ""
        val path = w.gitPath ?: return ""
        val d = api.gitReviewDiff(path, f.path, scope, staged = scope == ReviewScope.Uncommitted && f.staged && f.status != "?", w.profile)
        // A staged-only view of a partly staged file can be empty: fall back to the working tree.
        return if (d.isBlank() && f.staged && scope == ReviewScope.Uncommitted) api.gitReviewDiff(path, f.path, scope, false, w.profile) else d
    }

    fun toggleStage(f: ReviewFile) {
        val w = selected ?: return
        val path = w.gitPath ?: return
        val to = !f.staged
        stagedNow = stagedNow + (f.path to to)
        store.scope.launch {
            try {
                if (to) api.gitStage(path, f.path, w.profile) else api.gitUnstage(path, f.path, w.profile)
                react(OneShot.Nod)
                if (!stale(w)) readGit(w)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                stagedNow = stagedNow - f.path; fail(why(e))
            }
        }
    }

    /** Unstages everything when anything is staged, else stages everything. */
    fun stageAll() {
        val w = selected ?: return
        val path = w.gitPath ?: return
        val shown = shownFiles
        val to = shown.none { it.staged }
        guard("stageAll") {
            stagedNow = stagedNow + shown.associate { it.path to to }
            store.scope.launch {
                try {
                    if (to) api.gitStage(path, null, w.profile) else api.gitUnstage(path, null, w.profile)
                    react(OneShot.Nod)
                    if (!stale(w)) readGit(w)
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    stagedNow = stagedNow - shown.map { it.path }.toSet(); fail(why(e))
                } finally { free("stageAll") }
            }
        }
    }

    fun discard(f: ReviewFile) {
        val w = selected ?: return
        val path = w.gitPath ?: return
        gone = gone + f.path
        fb.haptic(Haptic.Reject)
        store.scope.launch {
            try {
                api.gitDiscard(path, f.path, w.profile)
                react(OneShot.Wiggle); toasts.show(ToastData(t.discarded(fileName(f.path))))
                if (!stale(w)) readGit(w)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { gone = gone - f.path; fail(why(e)) }
        }
    }

    /** Null on success, else why it failed (the sheet stays open and says so). */
    suspend fun commit(message: String, push: Boolean): String? {
        val w = selected ?: return t.chatGone
        val path = w.gitPath ?: return t.notRepo
        if ("commit" in busy) return null
        busy = busy + "commit"
        return try {
            detached { api.gitCommit(path, message.trim(), push, w.profile) }
            done(if (push) t.committedPushed else t.committed, OneShot.Done)
            if (!stale(w)) store.scope.launch { readGit(w) }
            null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { ouch(); why(e) } finally { free("commit") }
    }

    /** A message drafted by the chat's model from what will commit; [avoid] = the last draft. */
    suspend fun draftMessage(avoid: String?): String {
        val w = selected ?: throw IllegalStateException(t.chatGone)
        val path = w.gitPath ?: throw IllegalStateException(t.notRepo)
        val text = detached { api.gitDraftMessage(path, w.profile, w.sessionId, avoid) }
        react(OneShot.Learned)
        return text
    }

    fun push() {
        val w = selected ?: return
        val path = w.gitPath ?: return
        guard("push") {
            store.scope.launch {
                try { api.gitPush(path, w.profile); done(t.pushed, OneShot.Hop); if (!stale(w)) readGit(w) }
                catch (e: CancellationException) { throw e } catch (e: Exception) { fail(why(e)) } finally { free("push") }
            }
        }
    }

    fun createPr() {
        val w = selected ?: return
        val path = w.gitPath ?: return
        guard("pr") {
            store.scope.launch {
                try {
                    val url = api.gitCreatePr(path, w.profile)
                    react(OneShot.Done); fb.haptic(Haptic.Confirm); fb.sound(Sound.Bloop)
                    val open: (() -> Unit)? = if (url.isNotBlank()) ({ openUrl(url) }) else null
                    toasts.show(ToastData(t.prMade, if (open != null) openLabel else null, open))
                    if (!stale(w)) readGit(w)
                } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(why(e)) } finally { free("pr") }
            }
        }
    }

    // ------------------------------------------------------------ branches and worktrees

    /** Branches, worktrees and base branches for the branch sheet; a part that fails stays as it was. */
    suspend fun loadBranches() {
        val w = selected ?: return
        val path = w.gitPath ?: return
        coroutineScope {
            launch { quiet { api.gitBranches(path, w.profile) }?.let { if (!stale(w)) branches = it } }
            launch { quiet { api.gitWorktrees(path, w.profile) }?.let { if (!stale(w)) worktrees = it } }
            launch { quiet { api.gitBaseBranches(path, w.profile) }?.let { if (!stale(w)) bases = it } }
        }
    }

    /** A remote row (`origin/x`) switches to a local `x` that tracks it (git's guess mode). */
    fun switchBranch(b: GitBranch) {
        val w = selected ?: return
        val path = w.gitPath ?: return
        val name = if (b.isRemote) b.name.substringAfter('/') else b.name
        guard("switch") {
            store.scope.launch {
                try {
                    val now = api.gitSwitchBranch(path, name, w.profile)
                    done(t.switched(now), OneShot.Arrive, sound = false)
                    if (!stale(w)) { readGit(w); loadBranches() }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(why(e)) } finally { free("switch") }
            }
        }
    }

    /** Null on success. The branch is named after the worktree, so no engine-made prefix lands in the user's repo. */
    suspend fun addWorktree(name: String, base: String?): String? {
        val w = selected ?: return t.chatGone
        val path = w.gitPath ?: return t.notRepo
        return try {
            val made = detached { api.gitAddWorktree(path, name.trim(), base, w.profile) }
            done(t.worktreeMade(made), OneShot.Spawn)
            loadBranches()
            null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { ouch(); why(e) }
    }

    fun removeWorktree(tree: Worktree, force: Boolean) {
        val w = selected ?: return
        val path = w.gitPath ?: return
        guard("worktree") {
            store.scope.launch {
                try {
                    api.gitRemoveWorktree(path, tree.path, force, w.profile)
                    worktrees = worktrees.filterNot { it.path == tree.path }
                    done(t.removed, OneShot.Leave, sound = false)
                    loadBranches()
                } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(why(e)) } finally { free("worktree") }
            }
        }
    }

    companion object {
        /** Parses off the main thread: a whole-chat diff can be megabytes. */
        suspend fun parse(diff: String): List<DiffLine> = withContext(Dispatchers.Default) { parseDiff(diff) }
    }
}
