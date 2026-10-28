package app.buddyverse.core.api

// Owner: More ▸ Processes (ui/more/ProcessesPage.kt, ui/more/processes). hermes-api.md §2.6 "Agent terminals and background
// processes" and "Terminal rail (host shell over sibling WebSocket)".

import app.buddyverse.core.Hermes
import app.buddyverse.core.RestError
import app.buddyverse.core.list
import app.buddyverse.core.obj
import app.buddyverse.core.params
import app.buddyverse.core.str
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonObject

private fun q(v: String) = v.encodeURLParameter()

/** `process.list {session_id, profile}` → the `ProcessEntry` rows that session owns (its `session_id` field is the process id). */
suspend fun Hermes.processList(sessionId: String, profile: String): List<JsonObject> =
    gateway.call("process.list", params("session_id" to sessionId, "profile" to profile), timeoutMs = 15_000).obj
        .list("processes").mapNotNull { it.obj }

/** `process.kill` → `ProcessKillResult {status: killed|already_exited|not_found|error, exit_code?, output?, error?}`. */
suspend fun Hermes.processKill(sessionId: String, processId: String, profile: String): JsonObject? =
    gateway.call("process.kill", params("session_id" to sessionId, "process_id" to processId, "profile" to profile), timeoutMs = 30_000).obj

/** `process.stop {session_id}` (owner-scoped: only that session's processes; never sent without a session) → `{killed, delegations?}`. */
suspend fun Hermes.processStop(sessionId: String, profile: String): JsonObject? =
    gateway.call("process.stop", params("session_id" to sessionId, "profile" to profile), timeoutMs = 30_000).obj

/** One-use 30 s ticket for one dial of `/api/host-terminal` (mint a fresh one per dial). */
suspend fun Hermes.hostTerminalTicket(): String =
    rest.post("/api/webapp/socket-ticket?socket=host-terminal").obj.str("ticket") ?: throw RestError(500, "no ticket")

/** The host shell socket URL; [close] with [attach] ends that shell on the PC (`action=close`). */
fun hostTerminalUrl(base: String, ticket: String, profile: String, cols: Int, rows: Int, attach: String? = null, close: Boolean = false): String =
    base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") +
        "/api/host-terminal?ticket=${q(ticket)}&profile=${q(profile)}&cols=${cols.coerceIn(2, 2000)}&rows=${rows.coerceIn(2, 1000)}" +
        (attach?.let { "&attach=${q(it)}" } ?: "") + (if (close) "&action=close" else "")
