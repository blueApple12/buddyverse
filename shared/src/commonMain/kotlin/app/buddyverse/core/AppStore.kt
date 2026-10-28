package app.buddyverse.core

import app.buddyverse.core.contract.CommandsCatalogResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/** [rows] = your chats (this PC's apps and messaging apps); [background] = internal work (Hermes.INTERNAL_SOURCES), shown only under its own filter. */
data class SessionsState(
    val rows: List<SessionRow> = emptyList(), val archived: List<SessionRow> = emptyList(), val loaded: Boolean = false, val error: String? = null,
    val background: List<SessionRow> = emptyList(),
)

private val internalSources = Hermes.INTERNAL_SOURCES.split(',').toSet()
data class RoutinesState(val jobs: List<JsonObject> = emptyList(), val running: Set<String> = emptySet(), val loaded: Boolean = false, val error: String? = null)

/**
 * App state and optimistic intents (design §1 `core/Store`). Owns the sign-in, the one gateway socket,
 * the roster poll, the session list, routines and every open [ChatController].
 */
class AppStore(private val kv: KeyValueStore, val scope: CoroutineScope) : TokenSource {
    private val http = platformHttpClient()
    private val auth = HermesAuth(http)
    private val tokenLock = Mutex()
    private var tokens: Tokens? = kv.get(K_TOKENS)?.let { runCatching { WireJson.decodeFromString(Tokens.serializer(), it) }.getOrNull() }
    override var base: String = kv.get(K_BASE).orEmpty()
        private set
    val gateway = Gateway(scope, http, this)
    val api = Hermes(gateway, Rest(http, this))

    private val _prefs = MutableStateFlow(kv.get(K_PREFS)?.let { runCatching { WireJson.decodeFromString(Prefs.serializer(), it) }.getOrNull() } ?: Prefs())
    val prefs: StateFlow<Prefs> = _prefs.asStateFlow()
    private val _paired = MutableStateFlow(tokens != null && base.isNotEmpty())
    val paired: StateFlow<Boolean> = _paired.asStateFlow()
    val link: StateFlow<LinkState> get() = gateway.state

    private val _bots = MutableStateFlow<List<Bot>>(emptyList())
    val bots: StateFlow<List<Bot>> = _bots.asStateFlow()
    private val _botsLoaded = MutableStateFlow(false)
    val botsLoaded: StateFlow<Boolean> = _botsLoaded.asStateFlow()
    private val _rooms = MutableStateFlow<List<Room>>(emptyList())
    val rooms: StateFlow<List<Room>> = _rooms.asStateFlow()
    private val _sessions = MutableStateFlow(SessionsState())
    val sessions: StateFlow<SessionsState> = _sessions.asStateFlow()
    private val _routines = MutableStateFlow(RoutinesState())
    val routines: StateFlow<RoutinesState> = _routines.asStateFlow()
    private val _signals = MutableSharedFlow<UiSignal>(extraBufferCapacity = 32)
    val signals: SharedFlow<UiSignal> = _signals.asSharedFlow()
    /** Bumps whenever any open chat changes its running state or items: roster rows re-derive live status. */
    private val _chatPulse = MutableStateFlow(0L)
    val chatPulse: StateFlow<Long> = _chatPulse.asStateFlow()

    /** A chat to show next (a tapped notification): the UI pushes it and clears this. */
    val pendingOpen = MutableStateFlow<ChatTarget?>(null)

    private val chats = mutableMapOf<String, ChatController>()
    private val catalogs = mutableMapOf<String, CommandsCatalogResult>()
    private var lastEpoch: String? = null
    private val _foreground = MutableStateFlow(true)
    /** The app is on screen: sounds and haptics play only then; notifications only when it isn't (design §6, §3.11). */
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()
    private val closing = mutableMapOf<String, Job>()
    private var pollJob: Job? = null
    private var debounceSessions: Job? = null
    private var debounceRoutines: Job? = null
    private var nonce = 0L

    init {
        scope.launch { gateway.events.collect(::route) }
        scope.launch { gateway.requests.collect(::routeRequest) }
        scope.launch {
            gateway.state.collect { s ->
                if (s is LinkState.Ready) {
                    if (lastEpoch != null && lastEpoch != s.replayEpoch) catalogs.clear()
                    lastEpoch = s.replayEpoch
                    onReady()
                }
                if (s is LinkState.SignedOut) { tokens = null; kv.put(K_TOKENS, null); _paired.value = false }
            }
        }
        if (_paired.value) gateway.start()
    }

    // ------------------------------------------------------------------ sign-in

    override suspend fun accessToken(forceRefresh: Boolean): String? = tokenLock.withLock {
        val t = tokens ?: return null
        if (!forceRefresh && !t.expiresSoon(nowMillis())) return t.accessToken
        return try {
            val fresh = auth.refresh(base, t)
            tokens = fresh; kv.put(K_TOKENS, WireJson.encodeToString(Tokens.serializer(), fresh)); fresh.accessToken
        } catch (_: AuthError.Expired) { null } catch (e: AuthError) { if (forceRefresh) throw e else t.accessToken }
    }

    /** Returns null on success, else an [AuthError]. */
    suspend fun signIn(rawLink: String, user: String, password: String): AuthError? {
        val b = normalizeBaseUrl(rawLink) ?: return AuthError.Protocol("bad link")
        return try {
            val t = auth.signIn(b, user.trim(), password)
            gateway.stop()
            base = b; tokens = t
            kv.put(K_BASE, b); kv.put(K_TOKENS, WireJson.encodeToString(Tokens.serializer(), t))
            chats.clear(); _bots.value = emptyList(); _botsLoaded.value = false; _sessions.value = SessionsState(); _routines.value = RoutinesState()
            _paired.value = true
            gateway.start()
            null
        } catch (e: CancellationException) { throw e } catch (e: AuthError) { e }
    }

    fun signOut() {
        gateway.stop()
        tokens = null; kv.put(K_TOKENS, null)
        chats.clear()
        _paired.value = false
    }

    val savedLink: String get() = base

    /** A first-run default for the sign-in screen (the PC app: its own gateway); a saved link always wins. */
    fun presetLink(link: String) { if (base.isEmpty()) base = link }

    /** The unsent text of one chat ([key] = chat or room key); kept across leaving the chat and app restarts. */
    fun draft(key: String): String = kv.get("draft:$key").orEmpty()
    fun saveDraft(key: String, text: String) { if (text != draft(key)) kv.put("draft:$key", text.ifBlank { null }) }

    fun setPrefs(p: Prefs) { _prefs.value = p; kv.put(K_PREFS, WireJson.encodeToString(Prefs.serializer(), p)) }

    fun signal(s: UiSignal) { _signals.tryEmit(s) }
    internal fun chatChanged(@Suppress("UNUSED_PARAMETER") c: ChatController) { _chatPulse.update { it + 1 } }

    fun setForeground(on: Boolean) {
        _foreground.value = on
        if (on && _paired.value) { gateway.start(); gateway.retryNow(); startPolling() }
    }

    /** A failure toast in plain words (the raw detail never reaches the screen). */
    fun problem(e: Throwable) = signal(UiSignal.Problem(oopsOf(e)))

    // ------------------------------------------------------------------ live routing

    private fun onReady() {
        startPolling()
        refreshSessions(); refreshRoutines(); refreshRooms()
        // Every session must be resumed on the new socket before acting (hermes-api.md §2.1).
        chats.values.forEach { it.open() }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                if (_foreground.value && gateway.state.value is LinkState.Ready) runCatching { refreshRoster() }
                delay(5_000)
            }
        }
    }

    private fun route(e: WireEvent) {
        if (e.sessionId.isNotEmpty()) {
            chats.values.filter { it.sessionId == e.sessionId }.forEach { it.onEvent(e) }
            return
        }
        when (e.type) {
            "sessions.changed" -> { debounce(true); }
            "cron.changed" -> debounce(false)
            "skills.changed" -> catalogs.clear()
        }
    }

    private fun routeRequest(r: ServerRequest) {
        val sid = r.sessionId
        val target = chats.values.firstOrNull { it.sessionId == sid }
        if (target != null) target.onRequest(r)
        else if (r.method in WINDOW_REQUESTS) gateway.respondError(r.id, 4404, "no window here shows this session")
        else {
            // Kept briefly for a chat that is opening; then declined so the tool fails fast instead of timing out (hermes-api.md §2.2).
            orphanRequests += r
            scope.launch { delay(30_000); if (orphanRequests.remove(r)) gateway.respondError(r.id, -32603, "no open chat shows this session") }
        }
    }

    private val orphanRequests = mutableListOf<ServerRequest>()

    private fun debounce(sessions: Boolean) {
        if (sessions) { debounceSessions?.cancel(); debounceSessions = scope.launch { delay(800); refreshSessions() } }
        else { debounceRoutines?.cancel(); debounceRoutines = scope.launch { delay(800); refreshRoutines() } }
    }

    // ------------------------------------------------------------------ chats

    fun chat(target: ChatTarget): ChatController {
        val c = chats.getOrPut(target.key) { ChatController(this, target) }
        c.open()
        // Deliver any server request that arrived before the chat was open.
        c.sessionId?.let { sid -> orphanRequests.filter { it.sessionId == sid }.forEach { r -> orphanRequests -= r; c.onRequest(r) } }
        return c
    }

    fun newChatTarget(profile: String) = ChatTarget(profile, null, botChat = false, nonce = ++nonce)

    // ------------------------------------------------------------------ quick chats

    /** A fresh quick chat with the brain: a normal saved session, made on the first message (see [TempChats]). */
    fun quickChatTarget() = ChatTarget(_bots.value.firstOrNull { it.isDefault }?.profile ?: "default", null, botChat = false, nonce = ++nonce, temp = true)

    fun openChats(): Collection<ChatController> = chats.values

    /** A live helper's own session, from the `child_session_id` its events carried into an open chat's work card. */
    fun helperSession(subagentId: String): String? = chats.values.firstNotNullOfOrNull { c ->
        c.state.value.items.firstNotNullOfOrNull { i -> (i as? app.buddyverse.core.chat.WorkItem)?.steps?.firstOrNull { it.id == "sa-$subagentId" }?.childSession }
    }

    fun forget(c: ChatController) { chats.remove(c.target.key) }

    suspend fun catalog(profile: String, sessionId: String?): CommandsCatalogResult? =
        catalogs[profile] ?: runCatching { api.commandsCatalog(profile, sessionId) }.getOrNull()?.also { catalogs[profile] = it }

    // ------------------------------------------------------------------ roster

    suspend fun refreshRoster() {
        val result = api.profiles(includeSessions = true)
        val rows = result.profiles
        val live = rows.mapNotNull { it.name }.map { name ->
            scope.async { name to runCatching { api.activeList(name).sessions }.getOrNull() }
        }.awaitAll().toMap()
        _bots.value = rows.mapNotNull { row ->
            val name = row.name ?: return@mapNotNull null
            val active = live[name].orEmpty()
            val status = when {
                active.any { it.status == "waiting" } -> BotStatus.NeedsYou
                active.any { it.status == "working" || it.status == "starting" } -> BotStatus.Working
                else -> BotStatus.Idle
            }
            Bot.from(row, status, active.filter { it.status != "idle" }.mapNotNull { it.id }.toSet())
        }.sortedWith(Bot.order)
        _botsLoaded.value = true
        // Open chats still showing a turn the server no longer runs heal themselves (only profiles whose list was read).
        for (c in chats.values.toList()) {
            if (c.target.watch) continue
            val sid = c.sessionId ?: continue
            val list = live[c.target.profile] ?: continue
            c.reconcile(list.any { it.id == sid && it.status != "idle" })
        }
    }

    fun refreshRooms() {
        scope.launch {
            runCatching {
                val profile = _bots.value.firstOrNull { it.isDefault }?.profile ?: "default"
                val r = api.groups(profile)
                _rooms.value = r.list("rooms").mapNotNull { it.obj }.filter { it.str("disbanded_at") == null }.mapNotNull { o ->
                    Room(profile, o.str("room_id") ?: return@mapNotNull null, o.str("name").orEmpty(),
                        o.list("members").mapNotNull { m -> m.obj.str("profile") }, o.double("updated_at") ?: 0.0)
                }
            }
        }
    }

    // ------------------------------------------------------------------ sessions (Chats tab)

    fun refreshSessions() {
        scope.launch {
            try {
                val side = api.sidebar()
                fun rows(o: JsonObject?) = o.list("sessions").mapNotNull { it.obj?.let(SessionRow::from) }
                // Messaging-app chats are yours too; that slice also carries internal one-shots, which only Background shows.
                val recents = (rows(side.child("recents")) + rows(side.child("messaging")).filter { it.source !in internalSources }).distinctBy { it.id }
                val archived = runCatching { rows(api.archivedSessions()) }.getOrDefault(_sessions.value.archived)
                val background = runCatching { rows(api.backgroundSessions()) }.getOrDefault(_sessions.value.background)
                _sessions.value = SessionsState(recents.sortedWith(compareByDescending<SessionRow> { it.pinned }.thenByDescending { it.lastActive }), archived, true, null, background)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _sessions.update { it.copy(loaded = true, error = e.message) }
            }
        }
    }

    private val rowOrder = compareByDescending<SessionRow> { it.pinned }.thenByDescending { it.lastActive }

    /** Close = archive (resumable) + leave the conversation. Optimistic; [undoClose] puts it back (design §3.4–3.5). */
    fun closeSession(row: SessionRow) {
        _sessions.update { s -> s.copy(rows = s.rows.filterNot { it.id == row.id }, archived = listOf(row.copy(archived = true)) + s.archived) }
        closing[row.id] = scope.launch {
            chats.values.filter { it.sessionId == row.id }.forEach { it.detach() }
            runCatching { api.patchSession(row.id, row.profile, mapOf("archived" to true, "pinned" to false)) }
                .onFailure { if (it is CancellationException) throw it; refreshSessions(); problem(it) }
        }
    }

    /** Waits for the close write to land first (never races it), then restores the row as it was, pin included. */
    fun undoClose(row: SessionRow) {
        _sessions.update { s -> s.copy(rows = (s.rows.filterNot { it.id == row.id } + row.copy(archived = false)).sortedWith(rowOrder), archived = s.archived.filterNot { it.id == row.id }) }
        val close = closing.remove(row.id)
        scope.launch {
            close?.join()
            val patch = if (row.pinned) mapOf("archived" to false, "pinned" to true) else mapOf("archived" to false)
            runCatching { api.patchSession(row.id, row.profile, patch) }.onFailure { refreshSessions() }
        }
    }

    fun unarchive(row: SessionRow) = undoClose(row)

    fun renameSession(row: SessionRow, title: String) {
        _sessions.update { s -> s.copy(rows = s.rows.map { if (it.id == row.id) it.copy(title = title) else it }) }
        scope.launch { runCatching { api.patchSession(row.id, row.profile, mapOf("title" to title)) }.onFailure { refreshSessions(); problem(it) } }
    }

    fun pinSession(row: SessionRow, pinned: Boolean) {
        _sessions.update { s -> s.copy(rows = s.rows.map { if (it.id == row.id) it.copy(pinned = pinned) else it }.sortedWith(compareByDescending<SessionRow> { it.pinned }.thenByDescending { it.lastActive })) }
        scope.launch { runCatching { api.patchSession(row.id, row.profile, mapOf("pinned" to pinned)) }.onFailure { refreshSessions() } }
    }

    fun deleteSession(row: SessionRow) {
        _sessions.update { s -> s.copy(rows = s.rows.filterNot { it.id == row.id }, archived = s.archived.filterNot { it.id == row.id }, background = s.background.filterNot { it.id == row.id }) }
        scope.launch {
            chats.values.filter { it.sessionId == row.id }.forEach { it.detach(); forget(it) }
            runCatching { api.deleteSession(row.id, row.profile) }.onFailure { refreshSessions(); problem(it) }
        }
    }

    // ------------------------------------------------------------------ routines

    fun refreshRoutines() {
        scope.launch {
            try {
                val jobs = api.cronJobs()
                _routines.update { it.copy(jobs = jobs, loaded = true, error = null) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _routines.update { it.copy(loaded = true, error = e.message) }
            }
        }
    }

    fun toggleRoutine(job: JsonObject, on: Boolean) {
        val id = job.str("id") ?: return
        val profile = job.str("profile") ?: "default"
        _routines.update { s -> s.copy(jobs = s.jobs.map { if (it.str("id") == id) JsonObject(it + ("enabled" to on.toJsonValue()) + ("state" to (if (on) "scheduled" else "paused").toJsonValue())) else it }) }
        scope.launch {
            runCatching { if (on) api.cronResume(id, profile) else api.cronPause(id, profile) }.onFailure { problem(it) }
            refreshRoutines()
        }
    }

    fun runRoutine(job: JsonObject) {
        val id = job.str("id") ?: return
        if (id in _routines.value.running) return
        _routines.update { it.copy(running = it.running + id) }
        scope.launch {
            // Synchronous on the server: the call returns when the run finishes; a client timeout means "unknown", so refetch.
            val r = runCatching { api.cronTrigger(id, job.str("profile") ?: "default") }
            _routines.update { it.copy(running = it.running - id) }
            r.onFailure { if (it !is CancellationException) problem(it) }
            refreshRoutines()
        }
    }

    suspend fun saveRoutine(existing: JsonObject?, profile: String, name: String, prompt: String, schedule: String): String? = try {
        if (existing == null) api.cronCreate(profile, mapOf("name" to name, "prompt" to prompt, "schedule" to schedule, "deliver" to "local"))
        else api.cronUpdate(existing.str("id")!!, existing.str("profile") ?: profile, mapOf("name" to name, "prompt" to prompt, "schedule" to schedule))
        refreshRoutines(); null
    } catch (e: CancellationException) { throw e } catch (e: RestError) { e.detail } catch (e: Exception) { e.message ?: "error" }

    fun deleteRoutine(job: JsonObject) {
        val id = job.str("id") ?: return
        _routines.update { s -> s.copy(jobs = s.jobs.filterNot { it.str("id") == id }) }
        scope.launch { runCatching { api.cronDelete(id, job.str("profile") ?: "default") }; refreshRoutines() }
    }

    // ------------------------------------------------------------------ bots

    suspend fun createBot(name: String, description: String): String? = try {
        api.createProfile(name, description.ifBlank { null })
        refreshRoster(); null
    } catch (e: CancellationException) { throw e } catch (e: RpcError) { e.message } catch (e: Exception) { e.message ?: "error" }

    companion object {
        private const val K_BASE = "base"
        private const val K_TOKENS = "tokens"
        private const val K_PREFS = "prefs"
        /** Window-owned bridges a phone never shows (hermes-api.md §2.2 "Sudo, secret and vault"). */
        private val WINDOW_REQUESTS = setOf("terminal.read", "preview.read", "preview.act", "window.read", "tour", "theme.apply", "theme.pick", "setup_beat")
    }
}
