package app.buddyverse.ui.more.processes

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.AppStore
import app.buddyverse.core.LinkState
import app.buddyverse.core.RpcError
import app.buddyverse.core.WireEvent
import app.buddyverse.core.api.processKill
import app.buddyverse.core.api.processList
import app.buddyverse.core.api.processStop
import app.buddyverse.core.bool
import app.buddyverse.core.double
import app.buddyverse.core.long
import app.buddyverse.core.nowMillis
import app.buddyverse.core.str
import app.buddyverse.design.Feedback
import app.buddyverse.design.Haptic
import app.buddyverse.design.Sound
import app.buddyverse.i18n.ProcessesPageStrings
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.Toasts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import app.buddyverse.i18n.Strings

/** One `ProcessEntry` (hermes-api.md §2.6), plus the session that owns it. */
@Immutable
data class Proc(
    val id: String,
    val sessionId: String,
    val profile: String,
    /** Title of the owning chat (null = unknown or the bot's main chat). */
    val chat: String?,
    val command: String,
    val cwd: String?,
    val pid: Long?,
    val status: String,
    val uptime: Long?,
    val exitCode: Long?,
    val exitedAt: Double?,
    val reason: String?,
    val preview: String,
    val tail: String?,
    val notify: Boolean,
    val watchHit: Boolean,
) {
    val running: Boolean get() = status == "running" || status == "starting"
    val failed: Boolean get() = !running && exitCode != null && exitCode != 0L
    /** First line of the command, short enough for a row or a toast. */
    val title: String get() = command.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80) ?: id

    companion object {
        fun from(o: JsonObject, sessionId: String, profile: String, chat: String?): Proc? = Proc(
            id = o.str("session_id") ?: return null,
            sessionId = sessionId,
            profile = profile,
            chat = chat,
            command = o.str("command").orEmpty(),
            cwd = o.str("cwd")?.takeIf { it.isNotBlank() },
            pid = o.long("pid"),
            status = o.str("status") ?: "running",
            uptime = o.long("uptime_seconds"),
            exitCode = o.long("exit_code"),
            exitedAt = o.double("exited_at"),
            reason = o.str("completion_reason")?.takeIf { it.isNotBlank() },
            preview = o.str("output_preview").orEmpty(),
            tail = o.str("output_tail"),
            notify = o.bool("notify_on_complete") == true,
            watchHit = o.bool("watch_hit") == true,
        )

        /** Running first (newest first), then finished (latest exit first). */
        val order = compareByDescending<Proc> { it.running }.thenByDescending { it.exitedAt ?: 0.0 }.thenBy { it.uptime ?: 0L }
    }
}

/** "45s", "5m", "2h 3m", "3d" (Western digits; wrap in `ltr()` inside a sentence). */
fun dur(sec: Long, s: Strings): String = when {
    sec < 60 -> s.unit(sec.coerceAtLeast(0), "s")
    sec < 3_600 -> s.unit(sec / 60, "m")
    sec < 86_400 -> s.unit(sec / 3_600, "h") + " " + s.unit((sec % 3_600) / 60, "m")
    else -> s.unit(sec / 86_400, "d")
}

/** A session that may own background processes. */
data class Owner(val sessionId: String, val profile: String, val chat: String?)

/**
 * State and intents of More ▸ Processes. `process.list` is per session, so every poll asks the sessions that can hold one:
 * live sessions of every bot, open chats, each bot's main chat and the most recent chats. Live output comes from
 * `agent.terminal.output` (sessions attached on this socket); otherwise the polled `output_tail` fills the terminal.
 * Kills run on the store's scope, so they finish even if the page closes.
 */
@Stable
class ProcessesModel(private val store: AppStore, private val toasts: Toasts, private val fb: Feedback) {
    lateinit var t: ProcessesPageStrings
    var errorText = "error"

    var procs by mutableStateOf<List<Proc>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set
    var unsupported by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var stopping by mutableStateOf<Set<String>>(emptySet()); private set
    /** One-shot per process for its buddy (finished, failed, stopped). Serial = timestamp. */
    val cues = mutableStateMapOf<String, BuddyCue>()
    /** Last known row of every process seen while the page is open: an open sheet keeps one that left the list. */
    private val seen = mutableStateMapOf<String, Proc>()
    private val buffers = HashMap<String, TermBuffer>()
    private val tails = HashMap<String, String>()
    private val liveAt = HashMap<String, Long>()
    private val kicks = Channel<Unit>(Channel.CONFLATED)

    fun proc(id: String): Proc? = seen[id]
    fun buffer(id: String): TermBuffer = buffers.getOrPut(id) { TermBuffer() }
    fun runningIn(sessionId: String): Int = procs.count { it.sessionId == sessionId && it.running }

    /** Poll every 5 s (the Desktop's rate) while connected; [kick] polls now. Runs until the page leaves. */
    suspend fun poll() {
        while (true) {
            // The roster names the bots whose sessions are asked; wait for it rather than show a false "nothing running".
            if (store.link.value is LinkState.Ready && store.botsLoaded.value) refresh()
            withTimeoutOrNull(5_000) { kicks.receive() }
        }
    }

    fun kick() { kicks.trySend(Unit) }

    fun onEvent(e: WireEvent) {
        when (e.type) {
            "agent.terminal.output" -> {
                val id = e.payload.str("process_id") ?: return
                val chunk = e.payload.str("chunk") ?: return
                liveAt[id] = nowMillis()
                buffer(id).feed(chunk)
                if (id !in seen) kick()
            }
            "terminal.close" -> kick()
            "message.start" -> if (e.payload.str("display_kind") == "process_complete") kick()
        }
    }

    private suspend fun refresh() {
        try {
            val owners = owners()
            var unknown = 0
            val rows = coroutineScope {
                owners.map { o ->
                    async {
                        try {
                            store.api.processList(o.sessionId, o.profile).mapNotNull { Proc.from(it, o.sessionId, o.profile, o.chat) }
                        } catch (e: RpcError) {
                            // not_found and friends: that session holds nothing here.
                            if (e.unknownMethod) unknown++
                            emptyList()
                        } catch (e: TimeoutCancellationException) {
                            // A slow session must not end the poll (a timeout is a CancellationException).
                            emptyList()
                        }
                    }
                }.awaitAll().flatten().distinctBy { it.id }
            }
            if (owners.isNotEmpty() && unknown == owners.size) { unsupported = true; loaded = true; return }
            unsupported = false
            val before = procs.associateBy { it.id }
            val now = nowMillis()
            for (p in rows) {
                val old = before[p.id] ?: seen[p.id]
                if (old != null && old.running && !p.running) cues[p.id] = BuddyCue(now, if (p.failed) OneShot.Startle else OneShot.Done)
                seen[p.id] = p
                syncOutput(p)
            }
            procs = rows.sortedWith(Proc.order)
            loaded = true
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loaded = true
            error = e.message ?: errorText
        }
    }

    private suspend fun owners(): List<Owner> {
        val out = LinkedHashMap<String, Owner>()
        fun add(id: String?, profile: String, chat: String?) { if (!id.isNullOrBlank() && id !in out) out[id] = Owner(id, profile, chat) }
        val bots = store.bots.value
        val live = coroutineScope {
            bots.map { b -> async { b.profile to runCatching { store.api.activeList(b.profile).sessions }.getOrDefault(emptyList()) } }.awaitAll()
        }
        live.forEach { (profile, rows) -> rows.forEach { add(it.id, profile, it.title) } }
        store.sessions.value.rows.take(RECENT).forEach { add(it.id, it.profile, it.title) }
        store.openChats().forEach { add(it.sessionId, it.target.profile, null) }
        bots.forEach { add(it.botChatId, it.profile, null) }
        return out.values.toList()
    }

    private fun syncOutput(p: Proc) {
        val text = p.tail?.takeIf { it.isNotEmpty() } ?: p.preview
        if (text.isEmpty() || tails[p.id] == text) return
        // Live chunks are newer than the polled tail; the tail takes over again once they go quiet.
        if (nowMillis() - (liveAt[p.id] ?: 0L) < 15_000) return
        tails[p.id] = text
        buffer(p.id).apply { reset(); feed(text) }
    }

    fun kill(p: Proc) {
        if (p.id in stopping) return
        stopping = stopping + p.id
        cues[p.id] = BuddyCue(nowMillis(), OneShot.Dizzy)
        store.scope.launch {
            try {
                val r = store.api.processKill(p.sessionId, p.id, p.profile)
                r.str("output")?.takeIf { it.isNotEmpty() }?.let { out ->
                    tails[p.id] = out; liveAt.remove(p.id); buffer(p.id).apply { reset(); feed(out) }
                }
                when (r.str("status")) {
                    "killed" -> { settle(p, r.long("exit_code")); toasts.show(ToastData(t.stopped(p.title))) }
                    "already_exited" -> { settle(p, r.long("exit_code")); toasts.show(ToastData(t.alreadyDone)) }
                    "not_found" -> toasts.show(ToastData(t.gone))
                    else -> fail(r.str("error") ?: errorText)
                }
            } catch (e: TimeoutCancellationException) {
                fail(errorText)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: errorText)
            } finally {
                stopping = stopping - p.id
            }
            kick()
        }
    }

    /** `process.stop` for the chat that owns [p]: every process of that session. */
    fun stopAll(p: Proc) {
        val ids = procs.filter { it.sessionId == p.sessionId && it.running }.map { it.id }.toSet()
        if (ids.isEmpty()) return
        stopping = stopping + ids
        val now = nowMillis()
        ids.forEach { cues[it] = BuddyCue(now, OneShot.Dizzy) }
        store.scope.launch {
            try {
                val r = store.api.processStop(p.sessionId, p.profile)
                toasts.show(ToastData(t.stoppedCount((r.long("killed") ?: ids.size.toLong()).toInt())))
            } catch (e: TimeoutCancellationException) {
                fail(errorText)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: errorText)
            } finally {
                stopping = stopping - ids
            }
            kick()
        }
    }

    private fun settle(p: Proc, exit: Long?) {
        val done = p.copy(status = "exited", exitCode = exit ?: p.exitCode, exitedAt = nowMillis() / 1000.0)
        seen[p.id] = done
        procs = procs.map { if (it.id == p.id) done else it }.sortedWith(Proc.order)
    }

    private fun fail(msg: String) {
        fb.sound(Sound.Bonk)
        fb.haptic(Haptic.Reject)
        toasts.show(ToastData(msg))
    }

    private companion object {
        /** Recent chats asked per poll, besides live sessions and main chats. */
        const val RECENT = 12
    }
}
