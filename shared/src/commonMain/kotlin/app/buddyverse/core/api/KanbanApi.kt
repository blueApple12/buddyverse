package app.buddyverse.core.api

// Owner: More ▸ Kanban (ui/more/KanbanPage.kt). hermes-api.md §2.5 "Kanban": REST under `/api/plugins/kanban`, host-wide boards.
// Every task call names its board (`?board=`), so a board switch on the PC never redirects a write.

import app.buddyverse.core.Hermes
import app.buddyverse.core.obj
import app.buddyverse.core.params
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private const val K = "/api/plugins/kanban"
private fun q(v: String) = v.encodeURLParameter()
private fun on(board: String) = "board=${q(board)}"

/** `{boards: [{slug, name, description?, total, counts, is_current, archived?…}], current}`. */
suspend fun Hermes.kanbanBoards(): JsonObject? = rest.get("$K/boards").obj

/** `{columns: [{name, tasks[]}], tenants[], assignees[], latest_event_id, now}`. */
suspend fun Hermes.kanbanBoard(board: String): JsonObject? = rest.get("$K/board?${on(board)}").obj

/** `{task, comments[], events[], attachments[], links{parents,children}, link_tasks[], child_results[], runs[]}`. */
suspend fun Hermes.kanbanTask(board: String, id: String): JsonObject? = rest.get("$K/tasks/${q(id)}?${on(board)}").obj

/** Worker stdout/stderr tail: `{exists, size_bytes, content, truncated}`; 404 before the first run. */
suspend fun Hermes.kanbanLog(board: String, id: String, tail: Int = 16_384): JsonObject? =
    rest.get("$K/tasks/${q(id)}/log?tail=$tail&${on(board)}").obj

/** `POST /tasks` (CreateTaskBody): `{task, warning?}`. */
suspend fun Hermes.kanbanCreate(board: String, title: String, body: String?, assignee: String?, priority: Int, triage: Boolean): JsonObject? =
    rest.post("$K/tasks?${on(board)}", params("title" to title, "body" to body, "assignee" to assignee, "priority" to priority, "triage" to triage)).obj

/** `PATCH /tasks/{id}`: any of status, assignee ("" = nobody), priority, title, body, result, block_reason, summary. 409 = move refused. */
suspend fun Hermes.kanbanPatch(board: String, id: String, vararg fields: Pair<String, Any?>): JsonObject? =
    rest.patch("$K/tasks/${q(id)}?${on(board)}", params(*fields)).obj

suspend fun Hermes.kanbanDelete(board: String, id: String): JsonElement = rest.delete("$K/tasks/${q(id)}?${on(board)}")

suspend fun Hermes.kanbanComment(board: String, id: String, body: String): JsonElement =
    rest.post("$K/tasks/${q(id)}/comments?${on(board)}", params("body" to body, "author" to "user"))

/** Reassign; a running task must release its worker first ([reclaimFirst]). [profile] null = nobody. */
suspend fun Hermes.kanbanReassign(board: String, id: String, profile: String?, reclaimFirst: Boolean): JsonElement =
    rest.post("$K/tasks/${q(id)}/reassign?${on(board)}", params("profile" to (profile ?: ""), "reclaim_first" to reclaimFirst))

/** Flesh out a triage task with the helper model: `{ok, reason, new_title}`. Non-ok is not an HTTP error. */
suspend fun Hermes.kanbanSpecify(board: String, id: String): JsonObject? =
    rest.post("$K/tasks/${q(id)}/specify?${on(board)}", JsonObject(emptyMap())).obj

/** Fan a triage task out into children: `{ok, reason, fanout, child_ids[], new_title}`. */
suspend fun Hermes.kanbanDecompose(board: String, id: String): JsonObject? =
    rest.post("$K/tasks/${q(id)}/decompose?${on(board)}", JsonObject(emptyMap())).obj

/** Nudge the dispatcher so a ready task doesn't wait out the 60 s tick. */
suspend fun Hermes.kanbanDispatch(board: String): JsonElement = rest.post("$K/dispatch?${on(board)}", JsonObject(emptyMap()))

// ---- boards

/** Idempotent: an existing [slug] returns that board. */
suspend fun Hermes.kanbanCreateBoard(slug: String, name: String): JsonObject? =
    rest.post("$K/boards", params("slug" to slug, "name" to name)).obj

suspend fun Hermes.kanbanRenameBoard(slug: String, name: String): JsonObject? =
    rest.patch("$K/boards/${q(slug)}", params("name" to name)).obj

/** Archives the board (`default` is refused). */
suspend fun Hermes.kanbanDeleteBoard(slug: String): JsonElement = rest.delete("$K/boards/${q(slug)}")

/** The PC's active board (CLI and `/kanban` in chats use it). */
suspend fun Hermes.kanbanSwitchBoard(slug: String): JsonElement = rest.post("$K/boards/${q(slug)}/switch", JsonObject(emptyMap()))

// ---- orchestration (config.yaml `kanban.*`)

/** `{orchestrator_profile, default_assignee, auto_decompose, auto_promote_children, resolved_*, active_profile}`. */
suspend fun Hermes.kanbanOrchestration(): JsonObject? = rest.get("$K/orchestration").obj

/** Only the passed fields are written; "" clears a profile setting. */
suspend fun Hermes.kanbanSetOrchestration(vararg fields: Pair<String, Any?>): JsonObject? =
    rest.put("$K/orchestration", params(*fields)).obj
