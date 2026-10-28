package app.buddyverse.core.api

// Owner: More ▸ Checkpoints (ui/more/CheckpointsPage.kt, ui/more/checkpoints). hermes-api.md §2.6 "Rollback / checkpoints"
// (WS `rollback.*`, served by gateway/session_tui_ops.py) and "Git review and coding rail" (REST `/api/git/*`,
// hermes_cli/web_routers/git.py). Git mutations fail with 400 `{"detail": "<git stderr>"}` → RestError.detail.

import androidx.compose.runtime.Immutable
import app.buddyverse.core.Hermes
import app.buddyverse.core.bool
import app.buddyverse.core.child
import app.buddyverse.core.double
import app.buddyverse.core.list
import app.buddyverse.core.long
import app.buddyverse.core.obj
import app.buddyverse.core.params
import app.buddyverse.core.str
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun q(v: String) = v.encodeURLParameter()
private fun JsonObject?.strings(key: String): List<String> = list(key).mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
private fun JsonObject?.int(key: String): Int = (long(key) ?: 0L).toInt()

// ---------------------------------------------------------------- workspaces (which chat / folder)

/** A chat and the folder it works in: rollback is per session (its frozen launch cwd), git per folder. */
@Immutable
data class Workspace(
    val sessionId: String,
    val profile: String,
    val title: String?,
    val cwd: String?,
    val repoRoot: String?,
    val branch: String?,
    val lastActive: Double,
    /** The bot's forever Bot Chat. */
    val main: Boolean = false,
) {
    val key: String get() = "$profile/$sessionId"
    /** Where git runs: the repo root when the PC knows it, else the chat's folder. */
    val gitPath: String? get() = repoRoot?.takeIf { it.isNotBlank() } ?: cwd?.takeIf { it.isNotBlank() }

    companion object {
        fun from(o: JsonObject?, main: Boolean = false): Workspace? = Workspace(
            sessionId = o.str("id") ?: return null,
            profile = o.str("profile") ?: "default",
            title = o.str("title")?.takeIf { it.isNotBlank() },
            cwd = o.str("cwd")?.takeIf { it.isNotBlank() },
            repoRoot = o.str("git_repo_root")?.takeIf { it.isNotBlank() },
            branch = o.str("git_branch")?.takeIf { it.isNotBlank() },
            lastActive = o.double("last_active") ?: o.double("started_at") ?: 0.0,
            main = main,
        )
    }
}

/** Recent chats of every bot with their folders (the sidebar rows carry `cwd`, `git_repo_root`, `git_branch`). */
suspend fun Hermes.recentWorkspaces(limit: Int = 60): List<Workspace> =
    sidebar(limit).child("recents").list("sessions").mapNotNull { Workspace.from(it.obj) }

/** One session row by id (`GET /api/sessions/{id}`): how a Bot Chat (hidden from the sidebar) gets its folder. */
suspend fun Hermes.workspaceOf(sessionId: String, profile: String, main: Boolean): Workspace? =
    Workspace.from(rest.get("/api/sessions/${q(sessionId)}?profile=${q(profile)}").obj, main)?.copy(profile = profile)

// ---------------------------------------------------------------- rollback.* (WS)

/** One snapshot of the chat's folder, newest first. [message] is the manager's reason ("before write_file", …). */
@Immutable
data class Checkpoint(val hash: String, val timestamp: String, val message: String)

/** [enabled] false = checkpoints are switched off for this bot (config `checkpoints.enabled`). */
@Immutable
data class CheckpointList(val enabled: Boolean, val items: List<Checkpoint>)

/** `git diff --stat` text and a unified diff (≤ 4000 chars for one checkpoint; ≤ 4 MiB for the whole chat). */
@Immutable
data class DiffText(val stat: String, val diff: String, val lines: Int = 0, val empty: Boolean = false)

/** `RollbackRestoreResult`; [historyRemoved] = transcript rows rewound by a whole-tree restore. */
@Immutable
data class RestoreResult(
    val success: Boolean,
    val restoredTo: String?,
    val file: String?,
    val restoredFiles: List<String>,
    val skippedUserEdits: List<String>,
    val skippedOversize: List<String>,
    val failedDeletes: List<String>,
    val historyRemoved: Int,
    val error: String?,
)

/** Only `session_id` and `profile`: the owner refuses any other key (`_ROLLBACK_FIELDS`). */
suspend fun Hermes.rollbackList(sessionId: String, profile: String): CheckpointList {
    val r = gateway.call("rollback.list", params("session_id" to sessionId, "profile" to profile)).obj
    return CheckpointList(r.bool("enabled") == true, r.list("checkpoints").mapNotNull { c ->
        val o = c.obj
        Checkpoint(o.str("hash")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null, o.str("timestamp").orEmpty(), o.str("message").orEmpty())
    })
}

/** What changed since [hash] (old = the checkpoint, new = the folder now). */
suspend fun Hermes.rollbackDiff(sessionId: String, profile: String, hash: String): DiffText {
    val r = gateway.call("rollback.diff", params("session_id" to sessionId, "profile" to profile, "hash" to hash)).obj
    return DiffText(r.str("stat").orEmpty(), r.str("diff").orEmpty())
}

/**
 * Puts the folder (or just [filePath]) back to [hash]. A whole-tree restore also rewinds the chat to before its newest
 * user turn, is refused mid-turn (`session_busy`) and keeps the user's own edits unless [all].
 */
suspend fun Hermes.rollbackRestore(sessionId: String, profile: String, hash: String, filePath: String?, all: Boolean): RestoreResult {
    val r = gateway.call("rollback.restore", params(
        "session_id" to sessionId, "profile" to profile, "hash" to hash,
        "file_path" to filePath?.takeIf { it.isNotBlank() }, "all" to all.takeIf { it && filePath == null },
    ), timeoutMs = 180_000).obj
    return RestoreResult(
        success = r.bool("success") == true, restoredTo = r.str("restored_to"), file = r.str("file"),
        restoredFiles = r.strings("restored_files"), skippedUserEdits = r.strings("skipped_user_edits"),
        skippedOversize = r.strings("skipped_oversize"), failedDeletes = r.strings("failed_deletes"),
        historyRemoved = r.int("history_removed"), error = r.str("error")?.takeIf { it.isNotBlank() },
    )
}

/** Everything this chat changed: the earliest kept checkpoint vs the folder now (`/diff session`). Null = checkpoints off. */
suspend fun Hermes.rollbackSessionDiff(sessionId: String, profile: String): DiffText? {
    val r = gateway.call("rollback.session_diff", params("session_id" to sessionId, "profile" to profile), timeoutMs = 180_000).obj
    if (r.bool("enabled") == false) return null
    if (r.bool("success") == false) throw IllegalStateException(r.str("error")?.takeIf { it.isNotBlank() } ?: "diff failed")
    return DiffText(r.str("stat").orEmpty(), r.str("diff").orEmpty(), r.int("diff_lines"), r.bool("empty") == true)
}

// ---------------------------------------------------------------- /api/git/* (REST)

@Immutable
data class RepoStatus(
    val branch: String?,
    val defaultBranch: String?,
    val detached: Boolean,
    val ahead: Int,
    val behind: Int,
    val staged: Int,
    val unstaged: Int,
    val untracked: Int,
    val conflicted: Int,
    val changed: Int,
    val added: Int,
    val removed: Int,
)

/** One changed file. [status]: `M A D R C U ?`. */
@Immutable
data class ReviewFile(val path: String, val added: Int, val removed: Int, val status: String, val staged: Boolean)

/** `lastTurn` needs the HEAD captured when a turn started (Desktop composer); not offered here. */
enum class ReviewScope(val wire: String) { Uncommitted("uncommitted"), Branch("branch") }

@Immutable
data class PullRequest(val url: String, val state: String, val number: Int)

/** [ghReady] = the PC has the GitHub CLI signed in; [pr] = this branch's pull request. */
@Immutable
data class ShipInfo(val ghReady: Boolean, val pr: PullRequest?)

@Immutable
data class GitBranch(val name: String, val checkedOut: Boolean, val isDefault: Boolean, val isRemote: Boolean, val worktreePath: String?)

@Immutable
data class BaseBranch(val name: String, val isRemote: Boolean, val isDefault: Boolean)

@Immutable
data class Worktree(val path: String, val branch: String?, val isMain: Boolean, val detached: Boolean, val locked: Boolean)

private fun gitQ(path: String, profile: String) = "path=${q(path)}&profile=${q(profile)}"

/** Null = not a git repo. */
suspend fun Hermes.gitStatus(path: String, profile: String): RepoStatus? {
    val o = rest.get("/api/git/status?${gitQ(path, profile)}").obj ?: return null
    return RepoStatus(
        branch = o.str("branch"), defaultBranch = o.str("defaultBranch"), detached = o.bool("detached") == true,
        ahead = o.int("ahead"), behind = o.int("behind"), staged = o.int("staged"), unstaged = o.int("unstaged"),
        untracked = o.int("untracked"), conflicted = o.int("conflicted"), changed = o.int("changed"),
        added = o.int("added"), removed = o.int("removed"),
    )
}

suspend fun Hermes.gitReviewList(path: String, scope: ReviewScope, profile: String): List<ReviewFile> =
    rest.get("/api/git/review/list?${gitQ(path, profile)}&scope=${scope.wire}").obj.list("files").mapNotNull { f ->
        val o = f.obj
        ReviewFile(o.str("path") ?: return@mapNotNull null, o.int("added"), o.int("removed"), o.str("status") ?: "M", o.bool("staged") == true)
    }

suspend fun Hermes.gitReviewDiff(path: String, file: String, scope: ReviewScope, staged: Boolean, profile: String): String =
    rest.get("/api/git/review/diff?${gitQ(path, profile)}&file=${q(file)}&scope=${scope.wire}&staged=$staged").obj.str("diff").orEmpty()

/** [file] null = every file (`git add -A` / `git reset HEAD`). */
suspend fun Hermes.gitStage(path: String, file: String?, profile: String) {
    rest.post("/api/git/review/stage?profile=${q(profile)}", params("path" to path, "file" to (file ?: JsonNull)))
}

suspend fun Hermes.gitUnstage(path: String, file: String?, profile: String) {
    rest.post("/api/git/review/unstage?profile=${q(profile)}", params("path" to path, "file" to (file ?: JsonNull)))
}

/** Discards [file]'s changes back to HEAD (an untracked file is deleted). */
suspend fun Hermes.gitDiscard(path: String, file: String, profile: String) {
    rest.post("/api/git/review/revert?profile=${q(profile)}", params("path" to path, "file" to file))
}

/** Commits what is staged, or everything when nothing is. */
suspend fun Hermes.gitCommit(path: String, message: String, push: Boolean, profile: String) {
    rest.post("/api/git/review/commit?profile=${q(profile)}", params("path" to path, "message" to message, "push" to push))
}

suspend fun Hermes.gitPush(path: String, profile: String) {
    rest.post("/api/git/review/push?profile=${q(profile)}", params("path" to path))
}

suspend fun Hermes.gitShipInfo(path: String, profile: String): ShipInfo {
    val o = rest.get("/api/git/review/ship-info?${gitQ(path, profile)}").obj
    val pr = o.child("pr")?.let { p -> p.str("url")?.let { PullRequest(it, p.str("state").orEmpty(), p.int("number")) } }
    return ShipInfo(o.bool("ghReady") == true, pr)
}

/** Pushes, then `gh pr create --fill`; returns the new PR's link. */
suspend fun Hermes.gitCreatePr(path: String, profile: String): String =
    rest.post("/api/git/review/create-pr?profile=${q(profile)}", params("path" to path)).obj.str("url").orEmpty()

/**
 * A commit message drafted by the chat's own model (Desktop `generateCommitMessage`): `commit-context` →
 * `llm.oneshot {template: commit_message}`. [avoid] = the previous draft, so "again" gives a new one.
 */
suspend fun Hermes.gitDraftMessage(path: String, profile: String, sessionId: String?, avoid: String?): String {
    val ctx = rest.get("/api/git/review/commit-context?${gitQ(path, profile)}").obj
    val r = gateway.call("llm.oneshot", params(
        "session_id" to sessionId, "profile" to profile, "template" to "commit_message", "temperature" to 0.8,
        "variables" to mapOf("diff" to ctx.str("diff").orEmpty(), "recent_commits" to ctx.str("recent").orEmpty(), "avoid" to avoid.orEmpty()),
    ), timeoutMs = 120_000).obj
    return r.str("text").orEmpty().trim()
}

suspend fun Hermes.gitBranches(path: String, profile: String): List<GitBranch> =
    rest.get("/api/git/branches?${gitQ(path, profile)}").obj.list("branches").mapNotNull { b ->
        val o = b.obj
        GitBranch(o.str("name") ?: return@mapNotNull null, o.bool("checkedOut") == true, o.bool("isDefault") == true,
            o.bool("isRemote") == true, o.str("worktreePath"))
    }

suspend fun Hermes.gitBaseBranches(path: String, profile: String): List<BaseBranch> =
    rest.get("/api/git/base-branches?${gitQ(path, profile)}").obj.list("branches").mapNotNull { b ->
        val o = b.obj
        BaseBranch(o.str("name") ?: return@mapNotNull null, o.bool("isRemote") == true, o.bool("isDefault") == true)
    }

/** `git switch`: a remote name (`origin/x`) makes a local tracking branch. */
suspend fun Hermes.gitSwitchBranch(path: String, branch: String, profile: String): String =
    rest.post("/api/git/branch/switch?profile=${q(profile)}", params("path" to path, "branch" to branch)).obj.str("branch") ?: branch

suspend fun Hermes.gitWorktrees(path: String, profile: String): List<Worktree> =
    rest.get("/api/git/worktrees?${gitQ(path, profile)}").obj.list("worktrees").mapNotNull { w ->
        val o = w.obj
        Worktree(o.str("path") ?: return@mapNotNull null, o.str("branch"), o.bool("isMain") == true, o.bool("detached") == true, o.bool("locked") == true)
    }

/** A new worktree under `<repo>/.worktrees/<name>` on a new branch from [base] (null = HEAD). Returns its path. */
suspend fun Hermes.gitAddWorktree(path: String, name: String, base: String?, profile: String): String =
    rest.post("/api/git/worktree/add?profile=${q(profile)}", params("path" to path, "name" to name.ifBlank { null }, "base" to base))
        .obj.str("path").orEmpty()

suspend fun Hermes.gitRemoveWorktree(path: String, worktreePath: String, force: Boolean, profile: String) {
    rest.post("/api/git/worktree/remove?profile=${q(profile)}", params("path" to path, "worktreePath" to worktreePath, "force" to force))
}
