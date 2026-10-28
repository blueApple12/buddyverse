package app.buddyverse.core

import app.buddyverse.core.contract.CanonicalCorrectionAck
import app.buddyverse.core.contract.CanonicalListResult
import app.buddyverse.core.contract.CanonicalProfilesResult
import app.buddyverse.core.contract.CanonicalSnapshot
import app.buddyverse.core.contract.CommandsCatalogResult
import app.buddyverse.core.contract.CompleteSlashResult
import app.buddyverse.core.contract.SessionActiveListResult
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private fun q(v: String) = v.encodeURLParameter()

/** Typed calls over the gateway socket and REST (hermes-api.md §2). Canonical verbs send the canonical wire form only. */
class Hermes(val gateway: Gateway, val rest: Rest) {
    // ---- bots / profiles ----
    suspend fun profiles(includeSessions: Boolean = true, profile: String? = null): CanonicalProfilesResult =
        gateway.call("profiles.list", params("include_sessions" to includeSessions, "profile" to profile)).decodeAs()

    suspend fun activeList(profile: String): SessionActiveListResult =
        gateway.call("session.active_list", params("profile" to profile)).decodeAs()

    suspend fun createProfile(name: String, description: String? = null): JsonElement =
        gateway.call("profiles.create", params("name" to name, "description" to description, "clone_from" to null), timeoutMs = 120_000)

    suspend fun configureProfile(name: String, fields: Map<String, Any?>): JsonElement =
        gateway.call("profiles.configure", params("name" to name, *fields.map { it.key to it.value }.toTypedArray()))

    suspend fun deleteProfile(name: String): JsonElement = rest.delete("/api/profiles/${q(name)}")

    // ---- sessions (canonical) ----
    suspend fun findBotChat(profile: String): CanonicalListResult =
        gateway.call("session.list", params("title" to "Bot Chat", "include_hidden" to true, "limit" to 200, "profile" to profile)).decodeAs()

    // No `messages` seed: the gateway's canonical session.create (CanonicalCreateParams) refuses it as invalid_params.
    suspend fun createSession(profile: String, requestId: String, title: String? = null, hidden: Boolean? = null,
                              model: String? = null, provider: String? = null): CanonicalSnapshot =
        gateway.call("session.create", params("request_id" to requestId, "source" to "gui", "profile" to profile, "title" to title,
            "hidden" to hidden, "follow_profile_config" to if (title == BOT_CHAT) true else null, "model" to model, "provider" to provider)).decodeAs()

    /** [lazy]: a watch window with no agent (a subagent's chat): stored history plus the child's live mirror. */
    suspend fun resume(sessionId: String, profile: String, lazy: Boolean = false): CanonicalSnapshot =
        gateway.call("session.resume", params("session_id" to sessionId, "source" to "desktop", "profile" to profile, "lazy" to if (lazy) true else null)).decodeAs()

    suspend fun detach(sessionId: String, subscriptionId: String, endReason: String? = null): JsonElement =
        gateway.call("session.detach", params("session_id" to sessionId, "subscription_id" to subscriptionId, "end_reason" to endReason))

    /** [apiText]: what the model reads instead of [text]; the stored, painted row stays [text] (`cli_turn_v1.api_text`). */
    suspend fun submit(sessionId: String, profile: String, text: String, submissionId: String, attachments: List<Map<String, String>> = emptyList(),
        apiText: String? = null): JsonObject? =
        gateway.call("prompt.submit", params("session_id" to sessionId, "profile" to profile, "text" to text, "submission_id" to submissionId,
            "attachments" to attachments.ifEmpty { null }, "api_text" to apiText), timeoutMs = 300_000).obj

    suspend fun interrupt(sessionId: String, profile: String, generation: Long): JsonElement =
        gateway.call("session.interrupt", params("session_id" to sessionId, "profile" to profile, "execution_generation" to generation))

    suspend fun steer(sessionId: String, profile: String, generation: Long, text: String): CanonicalCorrectionAck =
        gateway.call("session.steer", params("session_id" to sessionId, "profile" to profile, "execution_generation" to generation, "text" to text)).decodeAs()

    suspend fun cancelQueued(sessionId: String, profile: String, admissionId: String): JsonElement =
        gateway.call("prompt.cancel", params("session_id" to sessionId, "profile" to profile, "admission_id" to admissionId))

    suspend fun steerQueued(sessionId: String, profile: String, admissionId: String): JsonElement =
        gateway.call("prompt.steer_queued", params("session_id" to sessionId, "profile" to profile, "admission_id" to admissionId))

    suspend fun approve(sessionId: String, profile: String, generation: Long, promptId: String, choice: String): JsonElement =
        gateway.call("approval.respond", params("session_id" to sessionId, "profile" to profile, "execution_generation" to generation,
            "prompt_id" to promptId, "choice" to choice), timeoutMs = 300_000)

    suspend fun approvalSeen(sessionId: String, profile: String, promptId: String) =
        runCatching { gateway.call("approval.received", params("session_id" to sessionId, "profile" to profile, "request_id" to promptId), 10_000) }

    suspend fun answerClarify(sessionId: String, profile: String, generation: Long, promptId: String, answer: String): JsonElement =
        gateway.call("clarify.respond", params("session_id" to sessionId, "profile" to profile, "execution_generation" to generation,
            "prompt_id" to promptId, "answer" to answer))

    suspend fun mutate(sessionId: String, profile: String, operation: String, payload: Map<String, Any?>, requestId: String,
        expectedRevision: Long, expectedGeneration: Long? = null): JsonObject? =
        gateway.call("session.mutate", params("session_id" to sessionId, "profile" to profile, "request_id" to requestId,
            "expected_revision" to expectedRevision, "expected_generation" to expectedGeneration, "operation" to operation, "payload" to payload)).obj

    suspend fun attachImage(sessionId: String, profile: String, base64: String, filename: String): JsonObject? =
        gateway.call("image.attach_bytes", params("session_id" to sessionId, "profile" to profile, "content_base64" to base64, "filename" to filename), 120_000).obj

    suspend fun attachFile(sessionId: String, profile: String, stagedUpload: JsonElement, name: String): JsonObject? =
        gateway.call("file.attach", params("session_id" to sessionId, "profile" to profile, "staged_upload" to stagedUpload, "name" to name), 120_000).obj

    // ---- commands ----
    suspend fun commandsCatalog(profile: String, sessionId: String? = null): CommandsCatalogResult =
        gateway.call("commands.catalog", params("profile" to profile, "session_id" to sessionId)).decodeAs()

    suspend fun completeSlash(text: String, profile: String, sessionId: String?): CompleteSlashResult =
        gateway.call("complete.slash", params("text" to text, "profile" to profile, "session_id" to sessionId), 15_000).decodeAs()

    suspend fun slashExec(sessionId: String, profile: String, command: String): JsonObject? =
        gateway.call("slash.exec", params("session_id" to sessionId, "profile" to profile, "command" to command)).obj

    suspend fun commandDispatch(sessionId: String, profile: String, name: String, arg: String?): JsonObject? =
        gateway.call("command.dispatch", params("session_id" to sessionId, "profile" to profile, "name" to name, "arg" to arg?.ifBlank { null })).obj

    // ---- session list / history (REST) ----
    suspend fun sidebar(limit: Int = 100): JsonObject? =
        rest.get("/api/profiles/sessions/sidebar?recents_profile=all&recents_limit=$limit&cron_limit=50&messaging_limit=50" +
            "&recents_exclude=${q(INTERNAL_SOURCES)}").obj

    /** Internal work (one-shots, tools, helpers, kanban, routine runs): the Chats "Background" filter, never mixed into All. */
    suspend fun backgroundSessions(limit: Int = 100): JsonObject? =
        rest.get("/api/profiles/sessions?limit=$limit&offset=0&archived=exclude&profile=all&min_messages=1&order=recent&sources=${q(INTERNAL_SOURCES)}").obj

    suspend fun archivedSessions(limit: Int = 100): JsonObject? =
        rest.get("/api/profiles/sessions?limit=$limit&offset=0&archived=only&profile=all&min_messages=1&order=recent").obj

    suspend fun messages(sessionId: String, profile: String, limit: Int = 120): JsonObject? =
        rest.get("/api/sessions/${q(sessionId)}/messages?limit=$limit&order=latest&include_compacted=true&profile=${q(profile)}").obj

    suspend fun mutationSnapshot(sessionId: String, profile: String): JsonObject? =
        rest.get("/api/sessions/${q(sessionId)}/mutation-snapshot?profile=${q(profile)}").obj

    /** Fenced sidebar write (rename, archive, pin, unread, hidden) through `PATCH /api/sessions/{id}`. */
    suspend fun patchSession(sessionId: String, profile: String, fields: Map<String, Any?>): JsonObject? {
        val snap = mutationSnapshot(sessionId, profile)
        val body = params("request_id" to uuid(), "expected_revision" to (snap.long("runtime_revision") ?: 0L),
            "expected_generation" to if (snap.bool("exists") == true) snap.long("runtime_generation") else null, "profile" to profile,
            *fields.map { it.key to it.value }.toTypedArray())
        return rest.patch("/api/sessions/${q(sessionId)}?profile=${q(profile)}", body).obj
    }

    suspend fun deleteSession(sessionId: String, profile: String): JsonObject? {
        val snap = mutationSnapshot(sessionId, profile)
        val gen = snap.long("runtime_generation") ?: 0L
        return rest.delete("/api/sessions/${q(sessionId)}?profile=${q(profile)}&request_id=${uuid()}" +
            "&expected_revision=${snap.long("runtime_revision") ?: 0}&expected_generation=$gen").obj
    }

    suspend fun uploadFile(profile: String, name: String, mime: String, bytes: ByteArray): JsonObject? =
        rest.upload("/api/chat/file-upload?profile=${q(profile)}", name, mime, bytes).obj

    // ---- routines (cron REST) ----
    suspend fun cronJobs(): List<JsonObject> = rest.get("/api/cron/jobs?profile=all").arr.orEmpty().mapNotNull { it.obj }
    suspend fun cronCreate(profile: String, body: Map<String, Any?>): JsonObject? = rest.post("/api/cron/jobs?profile=${q(profile)}", params(*body.map { it.key to it.value }.toTypedArray())).obj
    suspend fun cronUpdate(id: String, profile: String, updates: Map<String, Any?>): JsonObject? =
        rest.put("/api/cron/jobs/${q(id)}?profile=${q(profile)}", params("updates" to updates)).obj
    suspend fun cronPause(id: String, profile: String): JsonObject? = rest.post("/api/cron/jobs/${q(id)}/pause?profile=${q(profile)}").obj
    suspend fun cronResume(id: String, profile: String): JsonObject? = rest.post("/api/cron/jobs/${q(id)}/resume?profile=${q(profile)}").obj
    suspend fun cronTrigger(id: String, profile: String): JsonObject? = rest.post("/api/cron/jobs/${q(id)}/trigger?profile=${q(profile)}", timeoutMs = Long.MAX_VALUE).obj
    suspend fun cronDelete(id: String, profile: String): JsonElement = rest.delete("/api/cron/jobs/${q(id)}?profile=${q(profile)}")
    suspend fun cronRuns(id: String, profile: String): List<JsonObject> =
        rest.get("/api/cron/jobs/${q(id)}/runs?limit=20&profile=${q(profile)}").obj.list("runs").mapNotNull { it.obj }

    // ---- More: capabilities, memory, status ----
    suspend fun skills(profile: String): List<JsonObject> = rest.get("/api/skills?profile=${q(profile)}").arr.orEmpty().mapNotNull { it.obj }
    suspend fun skillContent(name: String, profile: String): JsonObject? = rest.get("/api/skills/content?name=${q(name)}&profile=${q(profile)}").obj
    suspend fun toggleSkill(name: String, enabled: Boolean, profile: String): JsonElement =
        rest.put("/api/skills/toggle?profile=${q(profile)}", params("name" to name, "enabled" to enabled, "profile" to profile))
    suspend fun learningGraph(profile: String): JsonObject? = rest.get("/api/learning/graph?profile=${q(profile)}").obj
    suspend fun learningNode(id: String, profile: String): JsonObject? = rest.get("/api/learning/node?id=${q(id)}&profile=${q(profile)}").obj
    suspend fun editMemory(id: String, content: String, profile: String): JsonElement =
        rest.put("/api/learning/node?profile=${q(profile)}", params("id" to id, "content" to content))
    suspend fun deleteMemory(id: String, profile: String): JsonElement = rest.delete("/api/learning/node?profile=${q(profile)}", params("id" to id))
    suspend fun toolsets(profile: String): List<JsonObject> = rest.get("/api/tools/toolsets?profile=${q(profile)}").arr.orEmpty().mapNotNull { it.obj }
    suspend fun toggleToolset(name: String, enabled: Boolean, profile: String): JsonElement =
        rest.put("/api/tools/toolsets/${q(name)}", params("enabled" to enabled, "profile" to profile))
    suspend fun mcpServers(profile: String): JsonObject? = gateway.call("mcp.servers.list", params("profile" to profile)).obj
    suspend fun platforms(profile: String): JsonObject? = rest.get("/api/messaging/platforms?profile=${q(profile)}").obj
    suspend fun usage(days: Int, profile: String): JsonObject? = rest.get("/api/analytics/usage?days=$days&profile=${q(profile)}").obj
    suspend fun status(): JsonObject? = rest.get("/api/status").obj
    suspend fun logs(file: String, lines: Int = 120): JsonObject? = rest.get("/api/logs?file=${q(file)}&lines=$lines").obj
    suspend fun projects(profile: String): JsonObject? = gateway.call("projects.list", params("profile" to profile)).obj
    suspend fun groups(profile: String): JsonObject? = gateway.call("groups.list", params("profile" to profile, "limit" to 100)).obj
    suspend fun groupLog(profile: String, roomId: String, sinceSeq: Long): JsonObject? =
        gateway.call("groups.log", params("profile" to profile, "room_id" to roomId, "since_seq" to sinceSeq, "limit" to 200)).obj
    suspend fun groupState(profile: String, roomId: String): JsonObject? = gateway.call("groups.state", params("profile" to profile, "room_id" to roomId)).obj
    suspend fun groupSend(profile: String, roomId: String, text: String): JsonObject? =
        gateway.call("groups.send", params("profile" to profile, "room_id" to roomId, "event_id" to uuid(), "payload" to params("text" to text))).obj

    companion object {
        const val BOT_CHAT = "Bot Chat"
        /** Desktop sidebar exclusions: internal and messaging sources (hermes-api.md §2.1). */
        const val INTERNAL_SOURCES = "acp,cron,kanban,oneshot,subagent,tool"
    }
}
