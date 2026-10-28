package app.buddyverse.ui.room

import androidx.compose.runtime.Immutable
import app.buddyverse.core.Attachment
import app.buddyverse.core.api.RoomActor
import app.buddyverse.core.api.RoomEvent
import app.buddyverse.core.api.RoomFile
import app.buddyverse.core.api.RoomSeat
import app.buddyverse.core.str

/**
 * A message the user sent that the log doesn't show yet. [eventId] is the idempotency key and [uploadIds] stay fixed, so
 * Retry resends exactly the same thing (hermes-api.md "Send journal"); [uploaded] keeps files already committed.
 */
@Immutable
data class OutSend(
    val eventId: String,
    val text: String,
    val files: List<Attachment>,
    val uploadIds: List<String>,
    val failed: String? = null,
    val uploaded: List<RoomFile>? = null,
)

/** One row of the room transcript, oldest first. */
sealed interface RoomLine { val key: String }

@Immutable
data class UserLine(override val key: String, val text: String, val files: List<RoomFile>, val eventId: String?, val out: OutSend? = null) : RoomLine

/** A member's reply; [first] starts a cluster (a run of replies from one bot gets one label, design §3.3). */
@Immutable
data class MemberLine(
    override val key: String, val memberId: String, val profile: String?, val name: String, val text: String,
    val files: List<RoomFile>, val eventId: String, val seq: Long, val first: Boolean,
) : RoomLine

/** A notice (failed, stopped, put off, away, stop, closed, host lost, work limit); same-kind neighbours merge their names. */
@Immutable
data class NoticeLine(override val key: String, val kind: String, val names: List<String>, val detail: String?) : RoomLine

private val NOTICE_KINDS = setOf("turn.failed", "turn.cancelled", "turn.deferred", "member.unavailable", "room.stop_requested", "room.disbanded", "authority.lost")

/** The notice kinds the Desktop renders, plus `room.activity` only when the work limit was hit. */
fun isNotice(e: RoomEvent): Boolean = e.kind in NOTICE_KINDS || (e.kind == "room.activity" && e.payload.str("status") == "bounded")

/** Transcript lines from the log, then the user's not-yet-logged sends. [nameOf] names a member id (and its actor, when known). */
fun roomLines(events: List<RoomEvent>, sends: List<OutSend>, nameOf: (String, RoomActor?) -> String): List<RoomLine> {
    val out = ArrayList<RoomLine>(events.size + sends.size)
    for (e in events) when {
        e.kind == "message.user" -> out += UserLine("e${e.seq}", e.text, e.files, e.eventId)
        e.kind == "message.member" -> {
            val mid = e.memberId ?: e.actor.id
            val prev = out.lastOrNull()
            out += MemberLine("e${e.seq}", mid, e.actor.profile, nameOf(mid, e.actor), e.text, e.files, e.eventId, e.seq,
                first = !(prev is MemberLine && prev.memberId == mid))
        }
        isNotice(e) -> {
            val name = e.memberId?.let { nameOf(it, null) }
            val detail = if (e.kind == "turn.failed" || e.kind == "member.unavailable") (e.payload.str("error") ?: e.payload.str("reason"))?.takeIf { it.isNotBlank() } else null
            val prev = out.lastOrNull()
            if (prev is NoticeLine && prev.kind == e.kind) out[out.lastIndex] = prev.copy(names = (prev.names + listOfNotNull(name)).distinct(), detail = prev.detail ?: detail)
            else out += NoticeLine("e${e.seq}", e.kind, listOfNotNull(name), detail)
        }
    }
    for (s in sends) out += UserLine("out:${s.eventId}", s.text, emptyList(), null, s)
    return out
}

/** Members whose latest turn after the user's latest message failed: their buddy looks sad until the next message. */
fun failedMembers(events: List<RoomEvent>): Set<String> {
    val lastUser = events.lastOrNull { it.kind == "message.user" }?.seq ?: 0L
    val latest = HashMap<String, String>()
    for (e in events) if (e.seq > lastUser && e.kind.startsWith("turn.")) e.memberId?.let { latest[it] = e.kind }
    return latest.filterValues { it == "turn.failed" }.keys
}

/** Each member's newest reply: (seq, text length). A new seq makes its buddy talk along. */
fun lastReplies(events: List<RoomEvent>): Map<String, Pair<Long, Int>> {
    val m = HashMap<String, Pair<Long, Int>>()
    for (e in events) if (e.kind == "message.member") (e.memberId ?: e.actor.id).let { m[it] = e.seq to e.text.length }
    return m
}

/** Working (running / stopping) and queued member ids, in admission order. */
fun presenceOf(activity: Map<String, String>): Pair<List<String>, List<String>> =
    activity.filterValues { it != "queued" }.keys.toList() to activity.filterValues { it == "queued" }.keys.toList()

/** A member's label: the bot's own title for a local bot, then the room's display name, the handle, the id. */
fun seatName(memberId: String, seat: RoomSeat?, actor: RoomActor?, botName: (String) -> String?): String =
    (seat?.profile ?: actor?.profile)?.let(botName) ?: actor?.displayName ?: seat?.displayName ?: seat?.handle ?: memberId
