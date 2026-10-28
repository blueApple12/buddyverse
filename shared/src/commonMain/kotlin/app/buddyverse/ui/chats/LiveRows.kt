package app.buddyverse.ui.chats

import app.buddyverse.core.Bot
import app.buddyverse.core.SessionRow

/**
 * The Live filter: every running session of every bot, newest first. A live session the listings never return
 * (each bot's Bot Chat is `hidden`, so the sidebar and session lists skip it; a fresh chat has no messages yet)
 * still shows, as a row built from its bot.
 */
internal fun liveRows(bots: List<Bot>, listed: List<SessionRow>): List<SessionRow> {
    val byId = listed.associateBy { it.id }
    return bots.flatMap { b ->
        b.liveSessions.map { sid ->
            byId[sid] ?: if (sid == b.botChatId) {
                SessionRow(sid, b.profile, b.name, b.preview, b.lastActive, true, false, false, null, 0)
            } else {
                SessionRow(sid, b.profile, null, null, b.lastActive, true, false, false, null, 0)
            }
        }
    }.distinctBy { it.id }.sortedByDescending { it.lastActive }
}
