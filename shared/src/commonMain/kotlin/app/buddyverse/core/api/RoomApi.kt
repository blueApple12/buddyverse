@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package app.buddyverse.core.api

// Owner: Group room (ui/room). Extra Hermes calls and store intents for this area go here as extension functions
// (`suspend fun Hermes.x(...)`, `fun AppStore.y(...)`), never into Hermes.kt or AppStore.kt.
// hermes-api.md §2.4 "Group chats": canonical `groups.*` on the one socket, `profile` = the room's authority profile.
// Canonical handlers refuse any key outside each method's exact field set, so every call below sends only those keys.

import androidx.compose.runtime.Immutable
import app.buddyverse.core.AppStore
import app.buddyverse.core.Hermes
import app.buddyverse.core.Room
import app.buddyverse.core.RpcError
import app.buddyverse.core.bool
import app.buddyverse.core.child
import app.buddyverse.core.double
import app.buddyverse.core.list
import app.buddyverse.core.long
import app.buddyverse.core.obj
import app.buddyverse.core.params
import app.buddyverse.core.str
import app.buddyverse.core.uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64

/** One seat (`RoomMember`). Mentions match [handle] case-insensitively. */
@Immutable
data class RoomSeat(val memberId: String, val profile: String, val handle: String, val displayName: String?)

/** A committed room file (`Manifest`): exactly these five fields go into a send. */
@Immutable
data class RoomFile(val attachmentId: String, val kind: String, val name: String, val size: Long, val mime: String) {
    fun manifest(): Map<String, Any?> = mapOf("attachment_id" to attachmentId, "kind" to kind, "name" to name, "size" to size, "mime" to mime)

    companion object {
        fun from(o: JsonObject?): RoomFile? = RoomFile(
            o.str("attachment_id") ?: return null, o.str("kind") ?: "file", o.str("name").orEmpty(), o.long("size") ?: 0L, o.str("mime").orEmpty(),
        )

        /** `image/…` → image, `application/pdf` → pdf, else file (the Desktop's rule). */
        fun kindOf(mime: String): String = when {
            mime.startsWith("image/") -> "image"
            mime == "application/pdf" -> "pdf"
            else -> "file"
        }

        const val MAX_BYTES = 15L * 1024 * 1024
        const val MAX_FILES = 8
    }
}

@Immutable
data class RoomActor(val kind: String, val id: String, val profile: String?, val displayName: String?)

/** One `RoomEvent` from `groups.log` (kinds: see hermes-api.md "Room event kinds"). */
@Immutable
data class RoomEvent(val seq: Long, val eventId: String, val kind: String, val actor: RoomActor, val payload: JsonObject, val createdAt: Double) {
    val text: String get() = payload.str("text").orEmpty()
    val files: List<RoomFile> get() = payload.list("attachments").mapNotNull { RoomFile.from(it.obj) }
    /** The member a turn event or reply is about. */
    val memberId: String? get() = payload.str("member_id") ?: actor.id.takeIf { actor.kind == "member" }

    companion object {
        fun from(o: JsonObject?): RoomEvent? {
            val a = o.child("actor")
            return RoomEvent(
                seq = o.long("seq") ?: return null,
                eventId = o.str("event_id").orEmpty(),
                kind = o.str("kind") ?: return null,
                actor = RoomActor(a.str("kind").orEmpty(), a.str("id").orEmpty(), a.str("profile"), a.str("display_name")?.takeIf { it.isNotBlank() }),
                payload = o.child("payload") ?: JsonObject(emptyMap()),
                createdAt = o.double("created_at") ?: 0.0,
            )
        }
    }
}

/** A `Room` with its full seats ([room] carries only member profiles, the shape the store and Bots home use). */
@Immutable
data class RoomInfo(val room: Room, val seats: List<RoomSeat>, val latestSeq: Long, val disbanded: Boolean)

fun roomInfoOf(profile: String, o: JsonObject?): RoomInfo? {
    val id = o.str("room_id") ?: return null
    val seats = o.list("members").mapNotNull { m ->
        val s = m.obj
        val p = s.str("profile") ?: s.child("target").str("profile") ?: return@mapNotNull null
        RoomSeat(s.str("member_id") ?: p, p, s.str("handle") ?: p, s.str("display_name")?.takeIf { it.isNotBlank() })
    }
    val room = Room(profile, id, o.str("name").orEmpty(), seats.map { it.profile }, o.double("updated_at") ?: 0.0)
    return RoomInfo(room, seats, o.long("latest_seq") ?: 0L, o.double("disbanded_at") != null)
}

/** One `driver_status.pending_actions[]` row: `approval`, `retry` (deferred task) or `discard` (indeterminate task). */
@Immutable
data class RoomAction(
    val kind: String, val memberId: String, val taskId: String, val generation: Long,
    val requestId: String?, val command: String?, val description: String?,
) {
    val key: String get() = "$kind:$memberId:$taskId:$generation"
    fun fence(): Array<Pair<String, Any?>> = arrayOf("member_id" to memberId, "task_id" to taskId, "execution_generation" to generation)
}

/** `RoomDriverStatus`: present only while the hosted driver runs. [activity] = member_id → queued | running | stopping. */
@Immutable
data class RoomDriver(val running: Boolean, val working: Boolean, val blocked: Boolean, val activity: Map<String, String>, val actions: List<RoomAction>)

fun roomDriverOf(o: JsonObject?): RoomDriver? {
    o ?: return null
    val actions = o.list("pending_actions").mapNotNull { e ->
        val a = e.obj
        val approval = a.child("approval")
        RoomAction(
            kind = a.str("kind") ?: return@mapNotNull null,
            memberId = a.str("member_id") ?: return@mapNotNull null,
            taskId = a.str("task_id") ?: return@mapNotNull null,
            generation = a.long("execution_generation") ?: return@mapNotNull null,
            requestId = a.str("request_id") ?: approval.str("request_id"),
            command = approval.str("command") ?: a.str("command"),
            description = approval.str("description") ?: a.str("description"),
        )
    }
    val activity = o.list("member_activity").mapNotNull { e -> e.obj.let { a -> (a.str("member_id") ?: return@mapNotNull null) to (a.str("status") ?: "running") } }.toMap()
    return RoomDriver(o.bool("running") == true, o.bool("working") == true, o.bool("blocked") == true, activity, actions)
}

/** `groups.capabilities`: [canonical] = gateway-hosted rooms (`driver: true`); [seatable] = who can sit in a room (null = not reported). */
@Immutable
data class RoomCaps(val canonical: Boolean, val seatable: Set<String>?)

data class RoomLogPage(val events: List<RoomEvent>, val latestSeq: Long, val hasMore: Boolean)

/** The server's words for a refusal: `data.detail` (names the refused member on create), else the reason. */
fun Throwable.roomReason(): String = (this as? RpcError)?.let { e ->
    e.data.str("detail")?.takeIf { it.isNotBlank() } ?: e.message?.takeIf { it.isNotBlank() } ?: e.reason
} ?: message ?: toString()

/** True when the room is gone for good (disbanded and pruned, or never existed for this subject). */
val Throwable.roomGone: Boolean get() = (this as? RpcError)?.reason in setOf("room_history_expired", "permission_denied")

// ---------------------------------------------------------------- calls

suspend fun Hermes.groupsCapabilities(profile: String): RoomCaps {
    val o = gateway.call("groups.capabilities", params("profile" to profile)).obj
    val seatable = (o?.get("seatable_profiles") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet()
    return RoomCaps(o.bool("driver") == true, seatable)
}

/** `groups.create` with local members only (`{member_id, profile, handle, target:{kind:"local",profile}, display_name?}`), 2–6 of them. */
suspend fun Hermes.groupsCreate(profile: String, roomId: String, name: String, members: List<Pair<String, String?>>): RoomInfo {
    require(members.size in 2..6) { "2–6 members" }
    val seats = members.map { (p, display) ->
        buildMap<String, Any?> {
            put("member_id", p); put("profile", p); put("handle", p)
            put("target", mapOf("kind" to "local", "profile" to p))
            display?.takeIf { it.isNotBlank() }?.let { put("display_name", it.take(200)) }
        }
    }
    val r = gateway.call("groups.create", params("profile" to profile, "room_id" to roomId, "name" to name.take(200), "members" to seats)).obj
    return roomInfoOf(profile, r.child("room")) ?: throw IllegalStateException("no room")
}

/** `groups.state`: the room and, while its driver runs, the driver status. */
suspend fun Hermes.roomState(profile: String, roomId: String): Pair<RoomInfo?, RoomDriver?> {
    val o = gateway.call("groups.state", params("profile" to profile, "room_id" to roomId), 30_000).obj
    return roomInfoOf(profile, o.child("room")) to roomDriverOf(o.child("driver_status"))
}

/** One `groups.log` page after [sinceSeq] (exclusive), up to 500 events. */
suspend fun Hermes.roomLog(profile: String, roomId: String, sinceSeq: Long): RoomLogPage {
    val o = gateway.call("groups.log", params("profile" to profile, "room_id" to roomId, "since_seq" to sinceSeq, "limit" to 500), 30_000).obj
    return RoomLogPage(o.list("events").mapNotNull { RoomEvent.from(it.obj) }, o.long("latest_seq") ?: 0L, o.bool("has_more") == true)
}

/**
 * `groups.send` with the exact payload `{text, thread_id, attachments?}`. [eventId] is the idempotency key: a retry with the
 * same id and payload returns the original event. Every composer message starts its own thread (`thread_id = event_id`).
 */
suspend fun Hermes.roomSend(profile: String, roomId: String, eventId: String, text: String, files: List<RoomFile>): RoomEvent? {
    val payload = buildMap<String, Any?> {
        put("text", text); put("thread_id", eventId)
        if (files.isNotEmpty()) put("attachments", files.map { it.manifest() })
    }
    val o = gateway.call("groups.send", params("profile" to profile, "room_id" to roomId, "event_id" to eventId, "payload" to payload), 60_000).obj
    return RoomEvent.from(o.child("event"))
}

suspend fun Hermes.groupsRename(profile: String, roomId: String, name: String): RoomInfo? {
    val o = gateway.call("groups.rename", params("profile" to profile, "room_id" to roomId, "event_id" to uuid(), "name" to name.take(200))).obj
    return roomInfoOf(profile, o.child("room"))
}

/** `groups.stop`: cancels every pending discussion; returns how many tasks it cancelled. */
suspend fun Hermes.groupsStop(profile: String, roomId: String): Int =
    gateway.call("groups.stop", params("profile" to profile, "room_id" to roomId, "cancel_id" to uuid())).obj.long("cancelled")?.toInt() ?: 0

/** `groups.disband`: retires the room id for good. Refused while work is still stopping (stop first, then retry). */
suspend fun Hermes.groupsDisband(profile: String, roomId: String) {
    gateway.call("groups.disband", params("profile" to profile, "room_id" to roomId, "cancel_id" to uuid()))
}

/** Valid only for a deferred task. */
suspend fun Hermes.groupsRetry(profile: String, roomId: String, a: RoomAction) {
    gateway.call("groups.retry", params("profile" to profile, "room_id" to roomId, *a.fence()))
}

/** Valid only for an indeterminate task: drops a result nobody can confirm. */
suspend fun Hermes.groupsDiscard(profile: String, roomId: String, a: RoomAction) {
    gateway.call("groups.discard", params("profile" to profile, "room_id" to roomId, *a.fence()))
}

/** [choice] is `once` or `deny` (the server rejects `session` / `always` in rooms). */
suspend fun Hermes.groupsApprove(profile: String, roomId: String, a: RoomAction, choice: String) {
    gateway.call("groups.approve", params("profile" to profile, "room_id" to roomId, *a.fence(), "choice" to choice, "request_id" to a.requestId), 60_000)
}

/** `groups.attachment.upload` (≤ 15 MB a file; idempotent per [uploadId]). Commits when a send references it. */
suspend fun Hermes.roomUpload(profile: String, roomId: String, uploadId: String, name: String, mime: String, bytes: ByteArray): RoomFile {
    val o = gateway.call("groups.attachment.upload", params(
        "profile" to profile, "room_id" to roomId, "upload_id" to uploadId, "kind" to RoomFile.kindOf(mime),
        "name" to name.take(255), "mime" to mime.take(127), "data_base64" to withContext(Dispatchers.Default) { Base64.encode(bytes) },
    ), 180_000).obj
    return RoomFile.from(o) ?: throw IllegalStateException("upload failed")
}

/** `groups.attachment.download`: needs the message event that committed the file. */
suspend fun Hermes.roomDownload(profile: String, roomId: String, eventId: String, attachmentId: String): ByteArray {
    val o = gateway.call("groups.attachment.download", params("profile" to profile, "room_id" to roomId, "event_id" to eventId, "attachment_id" to attachmentId), 180_000).obj
    val b64 = o.str("data_base64").orEmpty()
    return withContext(Dispatchers.Default) { Base64.decode(b64) }
}

/**
 * The live session a room member is working in, so its turn can be watched (hermes-api.md §2.4: member sessions are hidden
 * and titled `Group: …`; `session.active_list` lists hidden live sessions too). Prefers the one naming [roomName]; null when
 * the member has nothing live in a group.
 */
suspend fun Hermes.memberLiveSession(profile: String, roomName: String): String? {
    val live = activeList(profile).sessions.filter { it.status != null && it.status != "idle" && it.title?.startsWith("Group:") == true }
    return (live.firstOrNull { roomName.isNotBlank() && it.title?.contains(roomName, ignoreCase = true) == true } ?: live.firstOrNull())?.id
}

// ---------------------------------------------------------------- store intents

/** The profile that hosts rooms (`groups.*` authority): the brain, the same one [AppStore.refreshRooms] lists. */
val AppStore.roomAuthority: String get() = bots.value.firstOrNull { it.isDefault }?.profile ?: "default"

/** The Desktop's default room name: members' display names joined by ", ", cut to 64. */
fun defaultRoomName(names: List<String>): String = names.joinToString(", ").take(64)

/** Creates a room on the authority with [profiles] (2–6) seated by profile name; the room list refreshes behind it. */
suspend fun AppStore.createRoom(name: String, profiles: List<String>, roomId: String = uuid()): Room {
    val bots = bots.value
    val members = profiles.map { p -> p to bots.firstOrNull { it.profile == p }?.name }
    val info = api.groupsCreate(roomAuthority, roomId, name.ifBlank { defaultRoomName(members.map { it.second ?: it.first }) }, members)
    refreshRooms()
    return info.room
}
