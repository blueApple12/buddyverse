package app.buddyverse.core.api

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateMapOf
import app.buddyverse.core.AppStore
import app.buddyverse.core.Bot
import app.buddyverse.core.Hermes
import app.buddyverse.core.bool
import app.buddyverse.core.child
import app.buddyverse.core.double
import app.buddyverse.core.list
import app.buddyverse.core.long
import app.buddyverse.core.obj
import app.buddyverse.core.params
import app.buddyverse.core.str
import app.buddyverse.core.toJsonValue
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

// Owner: Bots home (ui/home). Extra Hermes calls and store intents for this area go here as extension functions
// (`suspend fun Hermes.x(...)`, `fun AppStore.y(...)`), never into Hermes.kt or AppStore.kt.

private const val BOT_META = "hermes-bots"

/**
 * Home-side state the roster poll doesn't carry yet: optimistic pin/hide/delete edits until the next poll shows them,
 * and the per-bot read watermark (hermes-api.md §2.4 "Activity signals": unread is client-side).
 * ponytail: process lifetime only; persist through the store's key-value store if unread should survive a restart.
 */
object HomeLocal {
    val pinned = mutableStateMapOf<String, Boolean>()
    val hidden = mutableStateMapOf<String, Boolean>()
    val gone = mutableStateMapOf<String, Boolean>()
    /** The `lastActive` the user has seen, per bot; seeded on first sight. */
    val readAt = mutableStateMapOf<String, Double>()
}

/**
 * CAS write of `ui_meta['hermes-bots']` (hermes-api.md §2.4 "Edit bot": read row + revision, merge, write with the expected
 * revision, retry a conflict up to 3 times). True when the server persisted it.
 */
suspend fun Hermes.saveBotMeta(profile: String, patch: Map<String, Any?>): Boolean {
    repeat(3) {
        val rows = runCatching { profiles(includeSessions = false, profile = profile) }.getOrNull() ?: profiles(includeSessions = false)
        val row = rows.profiles.firstOrNull { it.name == profile }
        val server = row?.uiMeta.child(BOT_META) ?: JsonObject(emptyMap())
        val merged = JsonObject(server - "chat" + patch.mapValues { it.value.toJsonValue() })
        val rev = row?.uiMetaRevisions.long(BOT_META)
        val fields = buildMap<String, Any?> {
            put("ui_meta", mapOf(BOT_META to merged))
            if (rev != null) put("ui_meta_expected_revisions", mapOf(BOT_META to rev))
        }
        val applied = configureProfile(profile, fields).obj.child("applied") ?: return false
        if (applied.bool("ui_meta") == true) return true
        if (applied.child("ui_meta_conflicts") == null) return false
    }
    return false
}

/** Id prefix of a stage mini with no live record behind it ([helperChat] then finds the n-th running helper chat). */
const val SLOT = "slot-"

/** A running `delegate_task` child (`delegation.status`, hermes-api.md §2.5 "Delegation live state"). */
@Immutable
data class LiveSubagent(val id: String, val goal: String?, val ownerSession: String?)

suspend fun Hermes.liveSubagents(profile: String): List<LiveSubagent> {
    if ("delegation.status" in gateway.unsupported) return emptyList()
    return gateway.call("delegation.status", params("profile" to profile), 10_000).obj.list("active").mapNotNull { e ->
        val o = e.obj
        LiveSubagent(o.str("subagent_id") ?: return@mapNotNull null, o.str("goal")?.takeIf { it.isNotBlank() }, o.str("owner_agent_session_id"))
    }
}

/**
 * [sa]'s own chat id from the server. No live record carries it (tools/delegate_tool_child_run.py `_register_subagent`), and
 * session lists hide helpers (`sessions.show_subagents` off, so finished helpers never clutter a chat list), but search
 * scoped to `source=subagent` returns them with `parent_session_id` and `is_active`. Matched by owner chat + goal; a stage
 * mini with no live record ([SLOT]n: a helper run by another Hermes process) opens the n-th running helper, newest first.
 */
suspend fun Hermes.helperChat(profile: String, sa: LiveSubagent): String? {
    val goal = sa.goal?.trim().orEmpty()
    // FTS needs a word: the goal's first words (its first message), else one every goal has.
    val q = goal.split(' ').filter { w -> w.length > 3 && w.all { it.isLetterOrDigit() } }.take(3).joinToString(" ").ifEmpty { "the" }
    val rows = rest.get("/api/sessions/search?q=${q.encodeURLParameter()}&source=subagent&limit=50&profile=${profile.encodeURLParameter()}")
        .obj.list("results").mapNotNull { it.obj }.distinctBy { it.str("session_id") }
        .sortedByDescending { it.double("started_at") ?: 0.0 }
    val owner = sa.ownerSession
    if (owner == null) {
        val running = rows.filter { it.bool("is_active") == true }
        return (running.getOrNull(sa.id.removePrefix(SLOT).toIntOrNull() ?: 0) ?: running.firstOrNull())?.str("session_id")
    }
    val mine = rows.filter { it.str("parent_session_id") == owner }
    fun stem(r: JsonObject) = r.str("title").orEmpty().removePrefix("Subagent:").trim().trimEnd('…', '.').trim()
    return (mine.firstOrNull { r -> stem(r).let { it.isNotEmpty() && goal.startsWith(it) } } ?: mine.firstOrNull { it.bool("is_active") == true }
        ?: mine.firstOrNull())?.str("session_id")
}

/** Opens [sa]'s own chat: the id from live events when this app saw them, else asked from the server; null = not found. */
fun AppStore.withHelperChat(profile: String, sa: LiveSubagent, open: (String?) -> Unit) {
    helperSession(sa.id)?.let { open(it); return }
    scope.launch { open(try { api.helperChat(profile, sa) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }) }
}

/** Opens [sa]'s own chat, or says it can't ([missing]) instead of silently showing the parent's main chat. */
fun AppStore.openHelperChat(profile: String, sa: LiveSubagent, open: (String) -> Unit, missing: () -> Unit) =
    withHelperChat(profile, sa) { child -> if (child != null) open(child) else missing() }

/** Every listed profile's projects in parallel; a profile that fails reads as none. */
suspend fun Hermes.projectsOf(profiles: List<String>): Map<String, List<Project>> = coroutineScope {
    profiles.map { p -> async { p to (runCatching { projectList(p) }.getOrNull() ?: emptyList()) } }.awaitAll().toMap()
}

fun AppStore.pinBot(profile: String, on: Boolean, onFail: () -> Unit) = metaIntent(profile, HomeLocal.pinned, "pinned", on, onFail)

fun AppStore.hideBot(profile: String, on: Boolean, onFail: () -> Unit) = metaIntent(profile, HomeLocal.hidden, "hidden", on, onFail)

/** Optimistic first (the row moves this frame), then the CAS write, then a fresh poll; a failed write puts it back. */
private fun AppStore.metaIntent(profile: String, local: MutableMap<String, Boolean>, key: String, on: Boolean, onFail: () -> Unit) {
    local[profile] = on
    scope.launch {
        val ok = try { api.saveBotMeta(profile, mapOf(key to on)) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        if (ok) runCatching { refreshRoster() } else onFail()
        local.remove(profile)
    }
}

/** Server-side "read" for the bot's Bot Chat (fenced `PATCH … {unread:false}`, hermes-api.md §2.1) plus the local watermark. */
fun AppStore.markBotRead(bot: Bot) {
    HomeLocal.readAt[bot.profile] = bot.lastActive
    val sid = bot.botChatId ?: return
    scope.launch { runCatching { api.patchSession(sid, bot.profile, mapOf("unread" to false)) } }
}

/** `DELETE /api/profiles/{name}` (hermes-api.md §2.4 "Delete a bot"); the row vanishes at once. Never for `default`. */
fun AppStore.deleteBot(profile: String, onFail: (String?) -> Unit) {
    if (profile == "default") return
    HomeLocal.gone[profile] = true
    scope.launch {
        try {
            api.deleteProfile(profile)
            runCatching { refreshRoster() }
            HomeLocal.readAt.remove(profile)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { onFail(e.message) }
        HomeLocal.gone.remove(profile)
    }
}
