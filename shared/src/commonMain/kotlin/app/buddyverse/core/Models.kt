package app.buddyverse.core

import androidx.compose.runtime.Immutable
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.MotionLevel
import app.buddyverse.core.contract.CanonicalProfileRow
import app.buddyverse.i18n.Lang
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

enum class ThemePref { System, Light, Dark }

@Serializable
data class Prefs(
    val theme: ThemePref = ThemePref.System,
    val lang: Lang = Lang.System,
    val sounds: Boolean = true,
    val haptics: Boolean = true,
    val motion: MotionLevel = MotionLevel.Full,
    /** Phone notifications while the app is in the background (needs you, done, failed). */
    val notifications: Boolean = true,
    /** Phone: keep the link open with the app closed (a quiet "listening" notification), so notifications always arrive. */
    val listen: Boolean = true,
    /** Best score per mini-game id (ui/play). */
    val best: Map<String, Int> = emptyMap(),
    /** Settings ▸ Temp chat model: what new quick chats start on; null = Automatic (see [TempChats.resolve]). */
    val tempModel: ModelChoice? = null,
)

enum class BotStatus { Idle, Working, NeedsYou, Error, Offline }

/** One bot = one Hermes profile (hermes-api.md §2.4). */
@Immutable
data class Bot(
    val profile: String,
    val name: String,
    val description: String,
    val isDefault: Boolean,
    val spec: BuddySpec,
    val model: String?,
    val botChatId: String?,
    val preview: String?,
    val lastActive: Double,
    val status: BotStatus,
    val pinned: Boolean,
    val hidden: Boolean,
    val liveSessions: Set<String> = emptySet(),
) {
    companion object {
        private val dmPrefix = Regex("^Message from (?:agent '[^']*'|🤖[^:]+):\\s*")

        fun from(row: CanonicalProfileRow, status: BotStatus, live: Set<String>): Bot? {
            val name = row.name ?: return null
            val meta = row.uiMeta.child("hermes-bots")
            val title = meta.str("title")?.takeIf { it.isNotBlank() } ?: row.displayName?.takeIf { it.isNotBlank() } ?: name
            val color = BuddyColors.parse(meta.str("color")) ?: if (row.isDefault == true) BuddyColors.palette[8] else BuddyColors.forName(name)
            val shape = BuddyShape.parse(meta.str("shape") ?: if (row.isDefault == true) "squircle" else null, name)
            val chat = row.canonicalSession
            val last = maxOf(chat?.lastActive.num ?: 0.0, row.lastSession?.lastActive.num ?: 0.0, row.workerSession?.lastActive.num ?: 0.0)
            val preview = (chat?.preview?.takeIf { it.isNotBlank() } ?: row.lastSession?.preview)?.replace(dmPrefix, "")
            return Bot(
                profile = name, name = title, description = row.description.orEmpty(), isDefault = row.isDefault == true,
                spec = BuddySpec(name, title, shape, color), model = row.model?.takeIf { it.isNotBlank() },
                botChatId = chat?.resolvedId ?: chat?.id, preview = preview?.takeIf { it.isNotBlank() }, lastActive = last,
                status = status, pinned = meta.bool("pinned") == true, hidden = meta.bool("hidden") == true, liveSessions = live,
            )
        }

        /** Brain first, then pinned, then needs-you, then most recent (design §3.1). */
        val order = compareByDescending<Bot> { it.isDefault }.thenByDescending { it.pinned }
            .thenByDescending { it.status == BotStatus.NeedsYou }.thenByDescending { it.lastActive }
    }
}

@Immutable
data class Room(val profile: String, val roomId: String, val name: String, val members: List<String>, val updatedAt: Double)

@Immutable
data class SessionRow(
    val id: String,
    val profile: String,
    val title: String?,
    val preview: String?,
    val lastActive: Double,
    val isActive: Boolean,
    val pinned: Boolean,
    val archived: Boolean,
    val source: String?,
    val messageCount: Long,
) {
    companion object {
        fun from(o: JsonObject): SessionRow? = SessionRow(
            id = o.str("id") ?: return null,
            profile = o.str("profile") ?: "default",
            title = o.str("title")?.takeIf { it.isNotBlank() },
            preview = o.str("preview")?.takeIf { it.isNotBlank() },
            lastActive = o.double("last_active") ?: o.double("last_activity_at") ?: o.double("started_at") ?: 0.0,
            isActive = o.bool("is_active") == true,
            pinned = o.bool("pinned") == true,
            archived = o.bool("archived") == true,
            source = o.str("source"),
            messageCount = o.long("message_count") ?: 0,
        )
    }
}

/**
 * What a chat screen shows: a bot's forever Bot Chat, one of its other sessions, or a new one. [watch]: a helper's (subagent's) own chat, read-only.
 * [temp]: a fresh quick chat (a normal saved brain session, starts on Sonnet; see [TempChats]); its key stays the same once the session exists.
 */
data class ChatTarget(val profile: String, val sessionId: String? = null, val botChat: Boolean = sessionId == null, val nonce: Long = 0, val watch: Boolean = false, val temp: Boolean = false) {
    val key: String get() = when {
        temp -> "$profile|temp-$nonce"
        botChat -> "$profile|botchat"
        sessionId != null -> "$profile|$sessionId" + if (watch) "|watch" else ""
        else -> "$profile|new-$nonce"
    }
}

enum class SendMode { Auto, Steer, Queue, StopAndSend, Aside }

sealed interface UiSignal {
    /** [sessionId] / [text] feed the background notification (design §3.11); the in-app cue ignores them. */
    data class TurnDone(val profile: String, val long: Boolean, val sessionId: String? = null, val text: String? = null, val tookMs: Long = 0) : UiSignal
    data class NeedsYou(val profile: String, val sessionId: String? = null) : UiSignal
    data class Failed(val profile: String, val sessionId: String? = null) : UiSignal
    data class Toast(val text: String) : UiSignal
    /** A failure the UI says in plain words ([Oops]); the raw detail stays in logs. */
    data class Problem(val kind: Oops) : UiSignal
}

/** What went wrong, in words a person can act on (never raw transport text or server detail). */
enum class Oops { Offline, Busy, Denied, Failed }

fun oopsOf(e: Throwable): Oops = when {
    e is Disconnected || e is RpcTimeout || e is SignedOut || e is kotlinx.io.IOException -> Oops.Offline
    e is RestError && e.status in setOf(502, 503, 504) -> Oops.Offline
    e is RestError && e.status in setOf(409, 423, 429) -> Oops.Busy
    e is RpcError && e.reason in setOf("busy", "session_busy", "stale_generation", "stale_revision", "execution_not_ready", "conflict") -> Oops.Busy
    e is RestError && e.status in setOf(401, 403) -> Oops.Denied
    e is RpcError && e.reason in setOf("permission_denied", "forbidden", "not_owner") -> Oops.Denied
    else -> Oops.Failed
}
