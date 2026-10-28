package app.buddyverse.core

import app.buddyverse.core.api.modelOptions
import app.buddyverse.core.api.watchSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import app.buddyverse.core.chat.ChatState
import app.buddyverse.core.chat.Prompt
import app.buddyverse.core.chat.PromptKind
import app.buddyverse.core.chat.Queued
import app.buddyverse.core.chat.SendState
import app.buddyverse.core.chat.Tone
import app.buddyverse.core.chat.TurnReducer
import app.buddyverse.core.contract.CanonicalEventsResult
import app.buddyverse.core.contract.CanonicalSnapshot
import app.buddyverse.core.contract.OpenRequestEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * One open conversation: attaches the session on the current socket, folds its events through [TurnReducer],
 * and turns user intents into canonical calls (optimistic first, network after).
 */
class ChatController(private val store: AppStore, val target: ChatTarget) {
    private val _state = MutableStateFlow(ChatState(sessionId = target.sessionId, profile = target.profile, model = if (target.temp) TempChats.startModel(store.prefs.value.tempModel)?.model else null))
    val state: StateFlow<ChatState> = _state.asStateFlow()
    private val api get() = store.api
    private val profile = target.profile
    private val attachLock = Mutex()
    private var attachedOn = -1L
    private var buffering = false
    private val buffer = mutableListOf<WireEvent>()
    private val pendingRequests = mutableListOf<ServerRequest>()
    private var notifiedPrompts = mutableSetOf<String>()

    val sessionId: String? get() = _state.value.sessionId

    /** The engine's signed-in providers and their models, for Settings' "Automatic" temp chat model; null when it can't say. */
    private suspend fun tempProviders(): app.buddyverse.core.api.ModelOptions? =
        try { api.modelOptions(profile, null) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    /** (Re)open: a chat that still shows a running turn re-reads the server's truth, so a turn killed while we were away doesn't stay "Working". */
    fun open() {
        if (_state.value.running) attachedOn = -1
        store.scope.launch { guard { ensureAttached() } }
    }

    private var staleStrikes = 0
    private var lastResync = 0L

    /**
     * Roster poll (every 5 s on screen): [serverLive] = `session.active_list` lists this session as non-idle. Two polls in a
     * row that say "not running" while this chat still shows a turn with no events for [StaleTurn.GRACE_MS] → re-resume;
     * the snapshot's `running=false` clears running, the turn timer and the work card.
     */
    internal fun reconcile(serverLive: Boolean) {
        val st = _state.value
        val now = nowMillis()
        staleStrikes = if (StaleTurn.suspect(st.running, st.lastEventAt, serverLive, now)) staleStrikes + 1 else 0
        if (staleStrikes >= 2 && now - lastResync >= StaleTurn.RESYNC_GAP_MS) {
            staleStrikes = 0
            lastResync = now
            attachedOn = -1
            store.scope.launch { guard { ensureAttached() } }
        }
    }

    /** Drop the attachment and resume again: the snapshot replaces what we show. */
    private suspend fun resync() { lastResync = nowMillis(); attachedOn = -1; ensureAttached() }

    /** Resolve (Bot Chat) and attach on this socket; re-attach after every reconnect (hermes-api.md §2.1 "Attach before acting"). */
    suspend fun ensureAttached(createIfMissing: Boolean = false): String? = attachLock.withLock {
        // Wait for the link first, then read its serial: a serial read before Ready is the old socket's (hermes-api.md §2.2 "Attach first").
        if (!store.gateway.awaitReady()) throw Disconnected()
        val serial = store.gateway.serial
        var id = _state.value.sessionId
        if (id != null && attachedOn == serial) return id
        try {
            if (id == null && target.botChat) {
                id = findBotChat(createIfMissing)
                if (id != null && attachedOn == serial) return id // just created and attached
            }
            if (id == null && !target.botChat && createIfMissing) {
                buffering = true
                // A quick chat: no title (Hermes auto-titles it), not hidden, starts on Settings' temp chat model (see TempChats).
                val snap = if (!target.temp) api.createSession(profile, uuid())
                else TempChats.resolve(store.prefs.value.tempModel, tempProviders()).let { m ->
                    _state.update { it.copy(model = m?.model ?: it.model) }
                    api.createSession(profile, uuid(), model = m?.model, provider = m?.provider)
                }
                applySnapshot(snap)
                attachedOn = serial
                return snap.sessionId
            }
            if (id == null) { _state.update { it.copy(loaded = true) }; return null }
            buffering = true
            val prev = _state.value
            // A helper chat is read over REST, refreshed while it runs: `session.resume` fails for helpers another Hermes process
            // runs, and its lazy watch returns an empty transcript once the helper has finished.
            val snap = if (!target.watch) api.resume(id, profile) else api.watchSnapshot(id, profile).also { if (it.running == true) pollWatch(id) }
            val keepLive = prev.loaded && prev.running && snap.running == true && prev.replayEpoch != null && prev.replayEpoch == snap.replayEpoch
            if (keepLive && replay(snap.sessionId ?: id, prev)) {
                _state.update { TurnReducer.mergePrompts(it.copy(subscriptionId = snap.subscriptionId, revision = maxOf(it.revision, snap.revision ?: 0), sessionId = snap.sessionId ?: id), snap) }
                afterAttach(snap)
            } else applySnapshot(snap)
            attachedOn = serial
            snap.sessionId ?: id
        } catch (e: Throwable) {
            // A failed or timed-out resume must not leave the chat buffering (frozen) until the next reconnect.
            if (buffering) flushBuffer()
            throw e
        }
    }

    private var watchPoll: Job? = null

    /** Re-reads a REST-watched helper chat every 3 s until it ends (it has no live events here). */
    private fun pollWatch(id: String) {
        if (watchPoll?.isActive == true) return
        watchPoll = store.scope.launch {
            while (isActive) {
                delay(3_000)
                val snap = try { api.watchSnapshot(id, profile) } catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
                _state.update { TurnReducer.fromSnapshot(it, snap, nowMillis()) }
                if (snap.running != true) break
            }
        }
    }

    private suspend fun findBotChat(create: Boolean): String? {
        val rows = api.findBotChat(profile).sessions
        val row = rows.firstOrNull { it.rootTitle == Hermes.BOT_CHAT || (it.rootTitle.isNullOrBlank() && it.title == Hermes.BOT_CHAT) }
        if (row != null) return row.resolvedId ?: row.id ?: row.sessionId
        // Fail closed: never mint a second Bot Chat when the roster says one exists (hermes-api.md §2.4).
        if (store.bots.value.firstOrNull { it.profile == profile }?.botChatId != null || !create) return null
        buffering = true
        val snap = api.createSession(profile, uuid(), title = Hermes.BOT_CHAT, hidden = true)
        applySnapshot(snap)
        attachedOn = store.gateway.serial
        return snap.sessionId
    }

    private fun applySnapshot(snap: CanonicalSnapshot) {
        _state.update { TurnReducer.fromSnapshot(it, snap, nowMillis()) }
        flushBuffer()
        afterAttach(snap)
    }

    /** Prompts and server requests still open on the server come back as cards (hermes-api.md §1.6 step 4). */
    private fun afterAttach(snap: CanonicalSnapshot) = afterAttach(snap.openRequests) {
        snap.prompts.forEach { p -> p.promptId?.let { notifyPrompt(it, p.kind == "approval") } }
    }

    private fun afterAttach(open: List<OpenRequestEntry>, then: () -> Unit = {}) {
        deliverPendingRequests()
        for (r in open) {
            val id = r.id ?: continue
            if (_state.value.prompts.none { it.id == id }) onRequest(ServerRequest(id, r.method ?: continue, r.params ?: JsonObject(emptyMap())))
        }
        then()
    }

    /** Replay what we missed while disconnected; false = gap can't be replayed, take the snapshot. */
    private suspend fun replay(sessionId: String, prev: ChatState): Boolean {
        val r: CanonicalEventsResult = runCatching {
            store.gateway.call("session.events.since", params("session_id" to sessionId, "last_sequence" to prev.lastSeq,
                "replay_epoch" to prev.replayEpoch, "profile" to profile), 10_000).decodeAs<CanonicalEventsResult>()
        }.getOrNull() ?: return false
        if (r.snapshotRequired == true || r.truncated == true) return false
        if (r.openRequests.isNotEmpty()) store.scope.launch { afterAttach(r.openRequests) }
        _state.update { st ->
            r.events.fold(st) { acc, e ->
                TurnReducer.apply(acc, WireEvent(e.type ?: return@fold acc, e.sessionId ?: sessionId, e.payload.obj ?: JsonObject(emptyMap()), e.seq,
                    e.replayEpoch, e.executionGeneration, e.admissionId), nowMillis())
            }
        }
        flushBuffer()
        return true
    }

    private fun flushBuffer() {
        val pending = buffer.sortedBy { it.seq ?: Long.MAX_VALUE }.toList()
        buffer.clear(); buffering = false
        for (e in pending) apply(e)
    }

    fun onEvent(e: WireEvent) {
        if (e.type == "session.replay_gap") { attachedOn = -1; open(); return } // our backlog overflowed: re-resume
        if (buffering) { buffer += e; return }
        apply(e)
    }

    private fun apply(e: WireEvent) {
        val before = _state.value
        _state.update { TurnReducer.apply(it, e, nowMillis()) }
        val after = _state.value
        when (e.type) {
            "message.complete" -> {
                val took = before.turnStartedAt?.let { nowMillis() - it } ?: 0L
                if (e.payload.str("status") == "error") store.signal(UiSignal.Failed(profile, sessionId))
                else store.signal(UiSignal.TurnDone(profile, took >= 3_000, sessionId, (after.items.lastOrNull { it is app.buddyverse.core.chat.BotItem } as? app.buddyverse.core.chat.BotItem)?.text, took))
            }
            "approval.request", "clarify.request" -> e.payload.str("prompt_id")?.let { notifyPrompt(it, e.type == "approval.request") }
        }
        if (after.items.size != before.items.size || after.running != before.running || after.activeSubagents != before.activeSubagents) store.chatChanged(this)
    }

    private fun notifyPrompt(promptId: String, approval: Boolean) {
        if (!notifiedPrompts.add(promptId)) return
        store.signal(UiSignal.NeedsYou(profile, sessionId))
        val sid = sessionId ?: return
        if (approval) store.scope.launch { runCatching { api.approvalSeen(sid, profile, promptId) } }
    }

    fun onRequest(r: ServerRequest) {
        if (buffering || !_state.value.loaded) { pendingRequests += r; return }
        if (_state.value.prompts.any { it.id == r.id }) return
        _state.update { TurnReducer.serverRequest(it, r) }
        if (notifiedPrompts.add(r.id)) store.signal(UiSignal.NeedsYou(profile, sessionId))
    }

    private fun deliverPendingRequests() {
        val all = pendingRequests.toList(); pendingRequests.clear()
        all.forEach(::onRequest)
    }

    // ------------------------------------------------------------------ intents

    fun send(raw: String, mode: SendMode = SendMode.Auto, attachments: List<Attachment> = emptyList()) {
        val text = raw.trim()
        if (text.isEmpty() && attachments.isEmpty()) return
        if (text.startsWith("/") && attachments.isEmpty() && mode != SendMode.Aside) { runSlash(text); return }
        val sub = uuid()
        val st = _state.value
        val steer = st.running && (mode == SendMode.Auto || mode == SendMode.Steer) && attachments.isEmpty()
        _state.update { TurnReducer.optimisticSend(it, text.ifEmpty { "📎" }, sub, nowMillis(), attachments = attachments.map { a -> a.name }) }
        store.scope.launch {
            try {
                val sid = ensureAttached(createIfMissing = true) ?: throw IllegalStateException("no session")
                when {
                    mode == SendMode.Aside -> { sideTask(sid, "prompt.btw", text); mark(sub, SendState.Sent) }
                    steer -> steerOrQueue(sid, text, sub)
                    mode == SendMode.StopAndSend && st.running -> {
                        runCatching { api.interrupt(sid, profile, _state.value.generation) }
                        submit(sid, text, sub, attachments)
                    }
                    else -> submit(sid, text, sub, attachments)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                mark(sub, SendState.Failed)
                store.signal(UiSignal.Failed(profile))
            }
        }
    }

    /** /btw or /background: a side agent that never enters the turn FIFO; it shows as a helper until its *.complete. */
    private suspend fun sideTask(sid: String, method: String, text: String) {
        val r = api.gateway.call(method, params("session_id" to sid, "profile" to profile, "text" to text)).obj
        r.str("task_id")?.let { id -> _state.update { TurnReducer.sideTaskStart(it, id, text, nowMillis()) }; store.chatChanged(this) }
    }

    private suspend fun submit(sid: String, text: String, sub: String, attachments: List<Attachment>) {
        val images = mutableListOf<Map<String, String>>()
        val refs = mutableListOf<String>()
        for (a in attachments) {
            if (a.mime.startsWith("image/")) {
                val r = api.attachImage(sid, profile, a.base64(), a.name)
                val path = r.str("path") ?: continue
                images += mapOf("path" to path, "mime" to (r.str("mime") ?: a.mime))
            } else {
                val up = api.uploadFile(profile, a.name, a.mime, a.bytes)
                val staged = up?.get("staged_upload")
                val att = if (staged != null && staged !is kotlinx.serialization.json.JsonNull) api.attachFile(sid, profile, staged, a.name)
                else api.gateway.call("file.attach", params("session_id" to sid, "profile" to profile, "path" to up.str("path"), "name" to a.name)).obj
                att.str("ref_text")?.let { refs += it }
            }
        }
        val body = if (refs.isEmpty()) text.ifEmpty { if (images.isNotEmpty()) "What do you see in this image?" else text } else refs.joinToString("\n") + "\n\n" + text
        // "Session busy" while nothing runs is a stale view: re-resume and send once more.
        val r = StaleTurn.retryOnceIfBusy({ resync() }) { api.submit(sid, profile, body, sub, images) }
        val running = _state.value.running
        // user_row_id: the durable row of this prompt; edit/rewind needs it (absent when queued or not yet persisted).
        _state.update { TurnReducer.markSend(it, sub, if (r.str("status") == "queued" && running) SendState.Queued else SendState.Sent, r.long("user_row_id")) }
    }

    /** Mid-turn message: `session.steer` reaches the running turn; undelivered → queue it as a normal prompt (hermes-api.md §2.2). */
    private suspend fun steerOrQueue(sid: String, text: String, sub: String) {
        val gen = _state.value.generation
        val undelivered = setOf("execution_not_ready", "stale_generation", "unsupported_control")
        val ack = try { api.steer(sid, profile, gen, text) } catch (e: RpcError) {
            if (e.reason in undelivered) null else throw e
        }
        if (ack != null && ack.status != "rejected") { mark(sub, SendState.Steered); return }
        if (ack != null && ack.delivered != false) { mark(sub, SendState.Steered); return }
        submit(sid, text, sub, emptyList())
    }

    private fun mark(sub: String, s: SendState) = _state.update { TurnReducer.markSend(it, sub, s) }

    fun retry(key: String) {
        val item = _state.value.items.firstOrNull { it.key == key } as? app.buddyverse.core.chat.UserItem ?: return
        _state.update { TurnReducer.removeItem(it, key) }
        send(item.text)
    }

    fun discard(key: String) = _state.update { TurnReducer.removeItem(it, key) }

    fun stop() {
        val sid = sessionId ?: return
        store.scope.launch {
            guard {
                ensureAttached()
                try { api.interrupt(sid, profile, _state.value.generation) } catch (e: RpcError) { if (e.reason != "stale_generation") throw e }
            }
        }
    }

    /** The card goes at once; if the answer can't be delivered it comes back (with a notice) so the turn never waits out its timeout. */
    fun answer(p: Prompt, value: String, extra: String? = null) {
        _state.update { it.copy(prompts = it.prompts.filterNot { x -> x.id == p.id }) }
        val sid = sessionId ?: return
        store.scope.launch {
            val ok = guard {
                val sent = when {
                    p.viaRequest && p.kind == PromptKind.Clarify ->
                        store.gateway.respond(p.id, params("answers" to mapOf((p.command ?: "q0") to value), "outcome" to "submitted"))
                    p.viaRequest -> store.gateway.respond(p.id, params("value" to value))
                    p.kind == PromptKind.Approval -> { ensureAttached(); api.approve(sid, profile, p.generation, p.id, value); true }
                    else -> { ensureAttached(); api.answerClarify(sid, profile, p.generation, p.id, value); true }
                }
                if (!sent) throw Disconnected()
            }
            if (!ok) _state.update {
                val back = if (it.prompts.any { x -> x.id == p.id }) it else it.copy(prompts = it.prompts + p)
                if (back.lastError != null) back else TurnReducer.notice(back, "answer not delivered", Tone.Error, nowMillis(), Oops.Offline)
            }
        }
    }

    fun cancelQueued(q: Queued) {
        val sid = sessionId ?: return
        _state.update { it.copy(queued = it.queued.filterNot { x -> x.admissionId == q.admissionId }, items = it.items.filterNot { i -> i is app.buddyverse.core.chat.UserItem && i.submissionId == q.submissionId }) }
        store.scope.launch { guard { ensureAttached(); api.cancelQueued(sid, profile, q.admissionId) } }
    }

    fun steerQueued(q: Queued) {
        val sid = sessionId ?: return
        store.scope.launch { guard { ensureAttached(); api.steerQueued(sid, profile, q.admissionId) } }
    }

    private fun runSlash(text: String) {
        val name = text.drop(1).substringBefore(' ').lowercase()
        val arg = text.substringAfter(' ', "").trim()
        when (name) {
            "stop" -> { stop(); return }
            "new" -> { store.signal(UiSignal.Toast("/new")); return }
            "btw", "background", "bg" -> if (arg.isNotEmpty()) {
                // Side agents: call them directly so their task id is tracked as a live helper.
                _state.update { TurnReducer.optimisticSend(it, text, "slash-${uuid()}", nowMillis(), SendState.Sent) }
                store.scope.launch { guard { sideTask(ensureAttached(createIfMissing = true) ?: return@guard, if (name == "btw") "prompt.btw" else "prompt.background", arg) } }
                return
            }
        }
        _state.update { TurnReducer.optimisticSend(it, text, "slash-${uuid()}", nowMillis(), SendState.Sent) }
        store.scope.launch {
            try {
                val sid = ensureAttached(createIfMissing = true) ?: return@launch
                val r = try { api.slashExec(sid, profile, text) } catch (e: RpcError) {
                    if (e.reason == "unsupported_command" || e.unknownMethod) {
                        try { api.commandDispatch(sid, profile, name, arg) } catch (e2: RpcError) { throw e }
                    } else throw e
                }
                when (r.str("type")) {
                    "send", "skill" -> {
                        val msg = r.str("message") ?: return@launch
                        r.str("notice")?.let { n -> _state.update { TurnReducer.notice(it, n, Tone.Info, nowMillis()) } }
                        api.submit(sid, profile, msg, uuid())
                    }
                    "prefill" -> store.signal(UiSignal.Toast(r.str("message").orEmpty()))
                    else -> r.str("output")?.takeIf { it.isNotBlank() }?.let { out -> _state.update { TurnReducer.notice(it, out, Tone.Info, nowMillis()) } }
                }
            } catch (e: CancellationException) { throw e } catch (e: RpcError) {
                val detail = e.data.str("detail") ?: e.reason
                _state.update { TurnReducer.notice(it, detail, Tone.Warn, nowMillis()) }
            } catch (e: Exception) {
                _state.update { TurnReducer.notice(it, e.message ?: "error", Tone.Error, nowMillis(), oopsOf(e)) }
            }
        }
    }

    fun dismissAside(taskId: String) = _state.update { it.copy(sideAnswers = it.sideAnswers.filterNot { a -> a.taskId == taskId }) }

    /** Leave the conversation: the owner ends the row when the last viewer leaves an idle session. */
    suspend fun detach(reason: String = "cli_close") {
        val sid = sessionId ?: return
        val sub = _state.value.subscriptionId ?: return
        runCatching { api.detach(sid, sub, reason) }
        attachedOn = -1
    }

    /** Runs [block]; a failure becomes a plain-words notice (raw detail kept for long press). False = it failed. */
    private suspend fun guard(block: suspend () -> Unit): Boolean {
        try { block(); return true } catch (e: CancellationException) { throw e } catch (e: Exception) {
            val msg = (e as? RpcError)?.reason ?: e.message ?: "error"
            if (e !is Disconnected) _state.update { TurnReducer.notice(it.copy(loaded = true), msg, Tone.Error, nowMillis(), oopsOf(e)) }
            else _state.update { it.copy(loaded = true, error = msg) }
            return false
        }
    }
}

/** A file picked in the composer. */
class Attachment(val name: String, val mime: String, val bytes: ByteArray) {
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    fun base64(): String = kotlin.io.encoding.Base64.encode(bytes)
}

