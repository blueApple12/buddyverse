package app.buddyverse.core.api

// Owner: Chats (ui/chats). Extra Hermes calls and store intents for this area go here as extension functions
// (`suspend fun Hermes.x(...)`, `fun AppStore.y(...)`), never into Hermes.kt or AppStore.kt.

import app.buddyverse.core.AppStore
import app.buddyverse.core.Hermes
import app.buddyverse.core.SessionRow
import app.buddyverse.core.list
import app.buddyverse.core.obj
import app.buddyverse.core.str
import app.buddyverse.core.long
import app.buddyverse.core.double
import app.buddyverse.core.contract.CanonicalSnapshot
import app.buddyverse.core.contract.CanonicalSnapshotInfo
import app.buddyverse.core.contract.TranscriptMessage
import kotlinx.serialization.json.JsonNull
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject

/** One search hit: the row to show (its id is the live compression tip, the one to resume) and the matched text. */
data class SearchHit(val row: SessionRow, val snippet: String?)

private fun enc(v: String) = v.encodeURLParameter()

/** `GET /api/sessions/search` on one profile (hermes-api.md §2.1 Search). Archived rows included, internal sources left out. */
suspend fun Hermes.searchSessions(q: String, profile: String, limit: Int = 30): List<SearchHit> =
    rest.get("/api/sessions/search?q=${enc(q)}&limit=$limit&profile=${enc(profile)}&exclude_sources=${enc(Hermes.INTERNAL_SOURCES)}")
        .obj.list("results").mapNotNull { it.obj?.let(::searchHit) }

internal fun searchHit(o: JsonObject): SearchHit? {
    val row = SessionRow.from(o) ?: return null
    return SearchHit(row.copy(id = o.str("session_id") ?: row.id), o.str("snippet")?.let(::stripFts)?.takeIf { it.isNotEmpty() })
}

/** FTS5 snippet highlight markers out, whitespace folded. */
internal fun stripFts(s: String): String = s.replace(">>>", "").replace("<<<", "").replace(Regex("\\s+"), " ").trim()

/** Searches every profile at once (the server searches one at a time); a profile that fails is skipped. Newest first. */
suspend fun AppStore.searchSessions(q: String, profiles: Collection<String>): List<SearchHit> = coroutineScope {
    profiles.map { p ->
        async { try { api.searchSessions(q, p) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() } }
    }.awaitAll().flatten().distinctBy { it.row.id }.sortedByDescending { it.row.lastActive }
}

/**
 * A helper chat read over REST (`GET /api/sessions/{id}` + `/messages`), shaped like a resume snapshot: `session.resume`
 * fails for helpers that another Hermes process runs (the desktop app's delegates). [CanonicalSnapshot.running] = not ended.
 */
suspend fun Hermes.watchSnapshot(sessionId: String, profile: String): CanonicalSnapshot {
    val row = rest.get("/api/sessions/${enc(sessionId)}?profile=${enc(profile)}").obj
    val msgs = rest.get("/api/sessions/${enc(sessionId)}/messages?limit=500&order=oldest&include_compacted=true&inline_images=false&profile=${enc(profile)}")
        .obj.list("messages").mapNotNull { it.obj }
        .filter { it.str("display_kind") != "hidden" }
        .mapNotNull { m ->
            val role = m.str("role")
            val text = m.str("display_content") ?: m.str("content")
            when (role) {
                "user", "assistant" -> TranscriptMessage(role = role, text = text, rowId = m.long("id"), timestamp = m.double("timestamp"))
                "tool" -> TranscriptMessage(role = role, text = text, name = m.str("tool_name"), toolCallId = m.str("tool_call_id"), rowId = m.long("id"),
                    timestamp = m.double("timestamp"))
                else -> null
            }
        }
    val ended = row?.get("ended_at").let { it != null && it !is JsonNull }
    val title = row?.str("title")?.removePrefix("Subagent:")?.trim()?.takeIf { it.isNotEmpty() }
    return CanonicalSnapshot(sessionId = sessionId, messages = msgs, running = !ended,
        info = CanonicalSnapshotInfo(title = title, model = row?.str("model")))
}

/**
 * The chat as plain text, oldest first ("Export" in the session menu, hermes-api.md §2.1 Export). No attach needed.
 * ponytail: first 500 rows only; page with `offset` if someone exports a longer chat.
 */
suspend fun Hermes.sessionText(sessionId: String, profile: String, me: String, bot: String): String =
    rest.get("/api/sessions/${enc(sessionId)}/messages?limit=500&order=oldest&include_compacted=true&inline_images=false&profile=${enc(profile)}")
        .obj.list("messages").mapNotNull { it.obj }
        .filter { it.str("display_kind") != "hidden" }
        .mapNotNull { m ->
            val who = when (m.str("role")) { "user" -> me; "assistant" -> bot; else -> return@mapNotNull null }
            (m.str("display_content") ?: m.str("content"))?.trim()?.takeIf { it.isNotEmpty() }?.let { "$who: $it" }
        }
        .joinToString("\n\n")
