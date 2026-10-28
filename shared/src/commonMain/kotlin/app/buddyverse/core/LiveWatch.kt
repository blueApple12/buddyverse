package app.buddyverse.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps every live session of every bot attached while no chat is on screen, so a turn that needs you, finishes or fails
 * reaches [AppStore.signals] (and the phone's notifications) with the app closed. Reuses the store's one socket, roster
 * poll and chat controllers; the phone's listening service runs it.
 * ponytail: attached chats are never dropped (as on Bots home); prune idle ones if a long-lived process shows the cost.
 */
class LiveWatch(private val store: AppStore) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = store.scope.launch {
            val kick = Channel<Unit>(Channel.CONFLATED)
            launch { store.gateway.events.collect { if (it.type == "sessions.changed") kick.trySend(Unit) } }
            launch { store.gateway.state.collect { if (it is LinkState.Ready) kick.trySend(Unit) } }
            while (isActive) {
                // On screen the store polls the roster every 5 s itself; closed, this is the only poll.
                if (!store.foreground.value && store.gateway.state.value is LinkState.Ready) runCatching { store.refreshRoster() }
                val open = store.openChats().toList()
                for ((sid, t) in watchTargets(store.bots.value)) if (open.none { it.target.key == t.key || it.sessionId == sid }) store.chat(t)
                withTimeoutOrNull(30_000) { kick.receive() }
                delay(1_000) // a burst of changes costs one poll
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}

/** Live (non-idle) session id → the chat to attach; a bot's Bot Chat by its usual key, so the chat screen and the watch share one. */
internal fun watchTargets(bots: List<Bot>): List<Pair<String, ChatTarget>> = bots.flatMap { b ->
    b.liveSessions.map { sid -> sid to if (sid == b.botChatId) ChatTarget(b.profile) else ChatTarget(b.profile, sid) }
}
