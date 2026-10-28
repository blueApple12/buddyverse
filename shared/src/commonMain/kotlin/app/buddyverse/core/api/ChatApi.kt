package app.buddyverse.core.api

// Owner: Bot chat (ui/chat). Extra Hermes calls and store intents for this area go here as extension functions
// (`suspend fun Hermes.x(...)`, `fun AppStore.y(...)`), never into Hermes.kt or AppStore.kt.

import androidx.compose.runtime.Immutable
import app.buddyverse.core.AppStore
import app.buddyverse.core.Attachment
import app.buddyverse.core.ChatController
import app.buddyverse.core.ChatTarget
import app.buddyverse.core.Hermes
import app.buddyverse.core.RpcError
import app.buddyverse.core.bool
import app.buddyverse.core.chat.BotItem
import app.buddyverse.core.chat.ChatItem
import app.buddyverse.core.chat.NoticeItem
import app.buddyverse.core.chat.Tone
import app.buddyverse.core.chat.TurnReducer
import app.buddyverse.core.chat.SendState
import app.buddyverse.core.chat.Step
import app.buddyverse.core.chat.StepStatus
import app.buddyverse.core.chat.UserItem
import app.buddyverse.core.chat.WorkItem
import app.buddyverse.core.child
import app.buddyverse.core.double
import app.buddyverse.core.list
import app.buddyverse.core.long
import app.buddyverse.core.obj
import app.buddyverse.core.params
import app.buddyverse.core.str
import app.buddyverse.core.uuid
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun q(v: String) = v.encodeURLParameter()
private val JsonElement.text: String? get() = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

// ------------------------------------------------------------------ session.mutate (hermes-api.md §2.1)

private val RETRY_MUTATE = setOf("revision_conflict", "stale_generation")

/** `session.mutate` with the chat's fences; one retry on a stale fence with the owner's current revision/generation. */
suspend fun Hermes.sessionOp(sessionId: String, profile: String, operation: String, payload: Map<String, Any?>, revision: Long, generation: Long): JsonObject? =
    try {
        mutate(sessionId, profile, operation, payload, uuid(), revision, generation)
    } catch (e: RpcError) {
        if (e.reason !in RETRY_MUTATE) throw e
        val snap = runCatching { mutationSnapshot(sessionId, profile) }.getOrNull()
        mutate(sessionId, profile, operation, payload, uuid(),
            e.data.long("revision") ?: snap.long("runtime_revision") ?: revision,
            e.data.long("execution_generation") ?: snap.long("runtime_generation") ?: generation)
    }

/** Copy the transcript (through [throughRowId], or all of it) into a new session; returns its id. Needs an idle session. */
suspend fun Hermes.branch(sessionId: String, profile: String, throughRowId: Long?, revision: Long, generation: Long): String? =
    sessionOp(sessionId, profile, "branch", buildMap<String, Any?> { if (throughRowId != null) put("through_message_id", throughRowId) }, revision, generation)
        .str("branched_session_id")

/** Remove the user row [rowId] and everything after it (edit & resend, regenerate). Returns the removed user text. */
suspend fun Hermes.rewind(sessionId: String, profile: String, rowId: Long, revision: Long, generation: Long): String? =
    sessionOp(sessionId, profile, "rewind", mapOf("target_message_id" to rowId), revision, generation).child("target_message").str("content")

/** Summarize older context. Returns "noop" when there was nothing to remove. */
suspend fun Hermes.compress(sessionId: String, profile: String, focus: String?, revision: Long, generation: Long): String? =
    sessionOp(sessionId, profile, "compress", buildMap<String, Any?> { if (!focus.isNullOrBlank()) put("focus", focus) }, revision, generation).str("status")

// ------------------------------------------------------------------ model and reasoning (hermes-api.md §2.3)

@Immutable
data class ModelProvider(
    val slug: String,
    val name: String,
    val current: Boolean,
    val models: List<String>,
    val featured: List<String>,
    val warning: String?,
)

@Immutable
data class ModelOptions(val providers: List<ModelProvider>, val model: String?, val provider: String?)

suspend fun Hermes.modelOptions(profile: String, sessionId: String?): ModelOptions {
    val o = gateway.call("model.options", params("profile" to profile, "session_id" to sessionId, "explicit_only" to true), 30_000).obj
    val providers = o.list("providers").mapNotNull { it.obj }.mapNotNull { p ->
        val slug = p.str("slug") ?: return@mapNotNull null
        if (p.bool("authenticated") == false) return@mapNotNull null
        ModelProvider(slug, p.str("name") ?: slug, p.bool("is_current") == true,
            p.list("models").mapNotNull { it.text }, p.list("featured_models").mapNotNull { it.text }, p.str("warning")?.takeIf { it.isNotBlank() })
    }.sortedByDescending { it.current }
    return ModelOptions(providers, o.str("model"), o.str("provider"))
}

sealed interface ModelPick {
    data object Applied : ModelPick
    /** The session is busy: the pick applies at the next turn boundary. */
    data object Deferred : ModelPick
    /** A guarded target (cost, context): resend the same target with [token] after the user agrees. */
    data class Confirm(val token: String, val message: String) : ModelPick
}

suspend fun Hermes.pickModel(sessionId: String, profile: String, model: String, provider: String?, revision: Long, generation: Long, confirm: String? = null): ModelPick {
    val payload = buildMap<String, Any?> {
        put("model", model)
        if (!provider.isNullOrBlank()) put("provider", provider)
        if (confirm != null) put("confirm", confirm)
    }
    val r = sessionOp(sessionId, profile, "model", payload, revision, generation)
    return when (r.str("status")) {
        "confirmation_required" -> ModelPick.Confirm(r.str("confirm").orEmpty(),
            r.str("confirm_message") ?: r.list("warnings").firstNotNullOfOrNull { it.obj.str("message") }.orEmpty())
        "deferred" -> ModelPick.Deferred
        else -> ModelPick.Applied
    }
}

/** The next turn's reasoning level ("none" = off), or null when unknown. */
suspend fun Hermes.reasoningLevel(sessionId: String, profile: String): String? =
    gateway.call("config.get", params("key" to "reasoning", "session_id" to sessionId, "profile" to profile), 15_000).obj.str("value")

/** Set this session's reasoning level; [effort] null = back to the bot's level. True when deferred to the next turn. */
suspend fun Hermes.setReasoning(sessionId: String, profile: String, effort: String?, revision: Long, generation: Long): Boolean =
    sessionOp(sessionId, profile, "reasoning", mapOf("effort" to effort), revision, generation).str("status") == "deferred"

// ------------------------------------------------------------------ reactions, helpers, voice (hermes-api.md §2.2)

/** React to a persisted row ([rowId]) or the newest row of [newestRole]; [emoji] null clears. */
suspend fun Hermes.react(sessionId: String, profile: String, rowId: Long?, newestRole: String?, emoji: String?): JsonElement =
    gateway.call("message.react", params("session_id" to sessionId, "profile" to profile, "row_id" to rowId,
        "newest_role" to if (rowId == null) newestRole else null, "emoji" to emoji, "author" to "user"))

/** Last 16 KiB of a running helper's transcript (watch it live). */
suspend fun Hermes.subagentTail(sessionId: String, profile: String, subagentId: String): String? =
    gateway.call("subagent.tail", params("session_id" to sessionId, "profile" to profile, "subagent_id" to subagentId), 15_000).obj
        .takeIf { it.bool("available") != false }.str("text")

suspend fun Hermes.subagentInterrupt(sessionId: String, profile: String, subagentId: String): JsonElement =
    gateway.call("subagent.interrupt", params("session_id" to sessionId, "profile" to profile, "subagent_id" to subagentId))

/** Dictation through the PC's speech-to-text. "" = no speech heard. */
suspend fun Hermes.transcribe(profile: String?, audio: Attachment): String? {
    val path = "/api/audio/transcribe" + (profile?.let { "?profile=${q(it)}" } ?: "")
    return rest.post(path, params("data_url" to "data:${audio.mime};base64,${audio.base64()}", "mime_type" to audio.mime)).obj.str("transcript")
}

// ------------------------------------------------------------------ older history (hermes-api.md §2.1 "Transcript history & paging")

class HistoryPage(val items: List<ChatItem>, val hasOlder: Boolean)

/** Rows before [rowId] as chat items (keys prefixed `o-` so they never collide with live ones). */
suspend fun Hermes.historyBefore(sessionId: String, profile: String, rowId: Long): HistoryPage {
    val o = rest.get("/api/sessions/${q(sessionId)}/messages/around?row_id=$rowId&limit=120&profile=${q(profile)}").obj
    val rows = o.list("messages").mapNotNull { it.obj }.filter { (it.long("id") ?: Long.MAX_VALUE) < rowId }
    return HistoryPage(historyItems(rows), o.child("pagination").bool("has_older") == true && rows.isNotEmpty())
}

/** REST transcript rows → user/bot bubbles and folded work cards. */
fun historyItems(rows: List<JsonObject>): List<ChatItem> {
    val out = mutableListOf<ChatItem>()
    var work: WorkItem? = null
    fun flush() { val w = work; if (w != null) out += w.copy(endedAt = w.at); work = null }
    fun addStep(at: Long, firstId: Long, step: Step) {
        val w = work ?: WorkItem("o-w-$firstId", emptyList(), "", at)
        work = w.copy(steps = w.steps + step)
    }
    for (r in rows) {
        if (r.str("display_kind") == "hidden" || r.str("role") == "system") continue
        val id = r.long("id") ?: continue
        val at = ((r.double("timestamp") ?: 0.0) * 1000).toLong()
        val text = r.str("display_content")?.takeIf { it.isNotBlank() } ?: contentText(r["content"])
        // Engine-made rows (helper / process finished, compaction) are a divider, never a bubble with the engine's text.
        TurnReducer.markOf(r.str("role"), r.str("display_kind"), text)?.let { mk ->
            flush()
            if ((out.lastOrNull() as? NoticeItem)?.mark != mk) out += NoticeItem("o-n-$id", "", Tone.Info, at, mark = mk)
            continue
        }
        when (r.str("role")) {
            "user" -> { flush(); if (text.isNotBlank()) out += UserItem("o-u-$id", text, SendState.Sent, at, rowId = id) }
            "assistant" -> {
                val reasoning = r.str("display_reasoning") ?: r.str("reasoning")
                if (!reasoning.isNullOrBlank()) {
                    val w = work ?: WorkItem("o-w-$id", emptyList(), "", at)
                    work = w.copy(reasoning = w.reasoning + reasoning)
                }
                val labels = r.child("tool_call_labels")
                for (call in r.list("tool_calls").mapNotNull { it.obj }) {
                    val callId = call.str("id") ?: continue
                    val fn = call.child("function")
                    val label = labels.list(callId).firstNotNullOfOrNull { it.obj.str("text") }
                    addStep(at, id, Step(callId, fn.str("name") ?: "tool", label, StepStatus.Done, args = fn.str("arguments")?.take(4_000)))
                }
                if (text.isNotBlank()) { flush(); out += BotItem("o-b-$id", text, false, at, id) }
            }
            "tool" -> {
                val callId = r.str("tool_call_id")
                val w = work
                if (w != null && callId != null && w.steps.any { it.id == callId }) {
                    work = w.copy(steps = w.steps.map { if (it.id == callId) it.copy(result = text.take(4_000)) else it })
                } else addStep(at, id, Step(callId ?: "o-t-$id", r.str("tool_name") ?: "tool", null, StepStatus.Done, result = text.take(4_000)))
            }
        }
    }
    flush()
    return out
}

private fun contentText(c: JsonElement?): String = when (c) {
    null, is JsonNull -> ""
    is JsonPrimitive -> c.content
    is JsonArray -> c.mapNotNull { part -> part.obj?.takeIf { it.str("type") in setOf("text", "input_text", "output_text") }?.str("text") }.joinToString("\n")
    else -> ""
}

// ------------------------------------------------------------------ store intents

/**
 * Re-read a session after its transcript was rewritten (rewind, compress): a fresh controller resumes it,
 * then the old subscription leaves without an end reason (the session keeps running). Returns the new controller.
 */
suspend fun AppStore.reopenChat(c: ChatController): ChatController {
    val sid = c.sessionId
    val sub = c.state.value.subscriptionId
    forget(c)
    val target = if (sid != null && c.target.temp) c.target.copy(sessionId = sid)
        else if (sid != null && !c.target.botChat) ChatTarget(c.target.profile, sid, botChat = false) else c.target
    val fresh = chat(target)
    runCatching { fresh.ensureAttached() }
    if (sid != null && sub != null) runCatching { api.detach(sid, sub) }
    return fresh
}
