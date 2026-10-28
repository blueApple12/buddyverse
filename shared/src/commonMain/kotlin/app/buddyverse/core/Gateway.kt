package app.buddyverse.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame as WsFrame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.min
import kotlin.random.Random

/** One notification frame (`{"method":"event","params":{type, session_id, payload, seq, …}}`). */
data class WireEvent(
    val type: String,
    val sessionId: String,
    val payload: JsonObject,
    val seq: Long? = null,
    val replayEpoch: String? = null,
    val generation: Long? = null,
    val admissionId: String? = null,
)

/** The backend asking the client something (approval, clarify, sudo, secret, …); answer with the same id. */
data class ServerRequest(val id: String, val method: String, val params: JsonObject) {
    val sessionId: String? get() = params.str("session_id")
}

class RpcError(val code: Int, message: String, val data: JsonObject?) : Exception(message) {
    /** Canonical refusals carry the reason in `data.reason` (hermes-api.md §1.6). */
    val reason: String get() = data.str("reason") ?: message.orEmpty()
    val unknownMethod: Boolean get() = code == -32601
}

class Disconnected : Exception("disconnected")
/** A call that got no answer in time. A plain Exception, never a CancellationException, so callers fail visibly and the loop retries. */
class RpcTimeout(method: String) : Exception("timeout: $method")
class SignedOut : Exception("signed_out")

sealed interface RpcFrame {
    data class Response(val id: String, val result: JsonElement?, val error: RpcError?) : RpcFrame
    data class Event(val event: WireEvent) : RpcFrame
    data class Request(val request: ServerRequest) : RpcFrame
    data object Unknown : RpcFrame
}

/** Decoder rule from `json-rpc-channel.ts::handleFrame`: id + method ≠ "event" → server request; method "event" → notification; id only → response. */
fun decodeFrame(text: String): RpcFrame {
    val o = runCatching { WireJson.parseToJsonElement(text).obj }.getOrNull() ?: return RpcFrame.Unknown
    val method = o.str("method")
    val id = (o["id"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
    if (method == "event") {
        val p = o.child("params") ?: return RpcFrame.Unknown
        val payload = p.child("payload") ?: JsonObject(emptyMap())
        return RpcFrame.Event(WireEvent(
            type = p.str("type") ?: return RpcFrame.Unknown,
            sessionId = p.str("session_id").orEmpty(),
            payload = payload,
            seq = p.long("seq"),
            replayEpoch = p.str("replay_epoch"),
            generation = p.long("execution_generation") ?: payload.long("execution_generation"),
            admissionId = p.str("admission_id"),
        ))
    }
    if (method != null && id != null) return RpcFrame.Request(ServerRequest(id, method, o.child("params") ?: JsonObject(emptyMap())))
    if (id != null) {
        val e = o.child("error")
        return RpcFrame.Response(id, o["result"], e?.let { RpcError(it.long("code")?.toInt() ?: 0, it.str("message") ?: "error", it.child("data")) })
    }
    return RpcFrame.Unknown
}

sealed interface LinkState {
    data object Idle : LinkState
    data class Connecting(val attempt: Int) : LinkState
    data class Ready(val replayEpoch: String) : LinkState
    data class Down(val reason: String, val retryAtMs: Long) : LinkState
    data object SignedOut : LinkState
}

/** Where the gateway lives and how to get a fresh access token (null = signed out). */
interface TokenSource {
    val base: String
    suspend fun accessToken(forceRefresh: Boolean = false): String?
}

/** Full-jitter exponential backoff: random(0, min(15 s, 300 ms · 2^attempt)) (hermes-api.md §1.6). */
fun backoffMs(attempt: Int, random: Random = Random): Long = random.nextLong(0, min(15_000L, 300L shl min(attempt, 12)) + 1)

fun wsUrl(base: String): String = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/api/ws?native_dial=browser"

/**
 * The one live connection: JSON-RPC 2.0 over `/api/ws`, a fresh one-use ticket per dial, 15 s pings,
 * 45 s liveness deadline, jittered reconnect. Pending calls fail with [Disconnected] when the socket drops.
 */
class Gateway(private val scope: CoroutineScope, private val http: HttpClient, private val tokens: TokenSource) {
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<WireEvent>(extraBufferCapacity = 2048)
    val events: SharedFlow<WireEvent> = _events.asSharedFlow()
    private val _requests = MutableSharedFlow<ServerRequest>(replay = 0, extraBufferCapacity = 64)
    val requests: SharedFlow<ServerRequest> = _requests.asSharedFlow()

    private val lock = Mutex()
    private val pending = mutableMapOf<String, CompletableDeferred<JsonElement>>()
    private var counter = 0L
    private var socket: DefaultClientWebSocketSession? = null
    private var loop: Job? = null
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private var lastInbound = 0L
    /** Bumps in the same step that sets Ready: a session attached on an older serial must re-attach first. */
    var serial = 0L
        private set
    /** Methods this backend answered -32601 for; the UI hides them (Desktop does the same). */
    val unsupported = mutableSetOf<String>()

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { run() }
    }

    fun stop() {
        loop?.cancel(); loop = null
        // Capture now: by the time the launch runs, start() may have opened a new socket with its own pending calls.
        val s = socket; socket = null
        val upTo = counter
        scope.launch { s?.close(CloseReason(CloseReason.Codes.NORMAL, "bye")); failPending(upTo) }
        _state.value = LinkState.Idle
    }

    /** Skip the backoff wait (the "Try again" button). */
    fun retryNow() { kick.trySend(Unit) }

    suspend fun awaitReady(timeoutMs: Long = 25_000): Boolean =
        withTimeoutOrNull(timeoutMs) { state.first { it is LinkState.Ready } } != null

    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 120_000): JsonElement {
        if (!awaitReady()) throw Disconnected()
        val ws = socket ?: throw Disconnected()
        val deferred = CompletableDeferred<JsonElement>()
        val id = lock.withLock { "r${++counter}".also { pending[it] = deferred } }
        try {
            ws.send(WsFrame.Text(params("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params).toString()))
            return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: throw RpcTimeout(method)
        } catch (e: RpcError) {
            if (e.unknownMethod) unsupported += method
            throw e
        } finally {
            lock.withLock { pending.remove(id) }
        }
    }

    /** False = no socket, nothing was sent (callers must say so, never drop an answer silently). */
    fun respond(id: String, result: JsonElement): Boolean = sendRaw(params("jsonrpc" to "2.0", "id" to id, "result" to result))
    fun respondError(id: String, code: Int, message: String) =
        sendRaw(params("jsonrpc" to "2.0", "id" to id, "error" to params("code" to code, "message" to message)))

    private fun sendRaw(o: JsonObject): Boolean {
        val ws = socket ?: return false
        scope.launch { runCatching { ws.send(WsFrame.Text(o.toString())) } }
        return true
    }

    private suspend fun run() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            _state.value = LinkState.Connecting(attempt)
            var openedAt = 0L
            val reason = try {
                dial(mintTicket()) { openedAt = nowMillis() }
            } catch (e: CancellationException) {
                // Only our own cancellation (stop()) ends the loop; a stray timeout from below is just a failed dial.
                if (!currentCoroutineContext().isActive) throw e
                "timeout"
            } catch (_: SignedOut) {
                failPending(); _state.value = LinkState.SignedOut; return
            } catch (e: Exception) {
                e.message ?: e::class.simpleName ?: "error"
            }
            socket = null
            failPending()
            if (openedAt > 0 && nowMillis() - openedAt >= 5_000) attempt = 0
            val wait = backoffMs(attempt++)
            _state.value = LinkState.Down(reason, nowMillis() + wait)
            withTimeoutOrNull(wait) { kick.receive() }
        }
    }

    private suspend fun mintTicket(): String {
        for (force in listOf(false, true)) {
            val token = tokens.accessToken(force) ?: throw SignedOut()
            val r = http.post("${tokens.base}/api/webapp/socket-ticket?socket=gateway") { header(HttpHeaders.Authorization, "Bearer $token") }
            if (r.status.value == 401) continue
            if (r.status.value != 200) throw Exception("ticket ${r.status.value}")
            return WireJson.parseToJsonElement(r.bodyAsText()).obj.str("ticket") ?: throw Exception("no ticket")
        }
        throw SignedOut()
    }

    /**
     * One socket's life; returns why it ended. The session runs in its own job, so when the heartbeat finds the link dead
     * we drop it at once instead of waiting out OkHttp's graceful close (up to 60 s), and the loop redials.
     */
    private suspend fun dial(ticket: String, onOpen: () -> Unit): String {
        val end = CompletableDeferred<String>()
        val job = scope.launch {
            try {
                http.webSocket(urlString = wsUrl(tokens.base), request = {
                    header(HttpHeaders.SecWebSocketProtocol, "hermes-gateway-v1, hermes-gateway-ticket.$ticket")
                }) { session(onOpen, end) }
                end.complete("closed")
            } catch (e: Throwable) {
                end.completeExceptionally(e)
            }
        }
        try { return end.await() } finally { job.cancel() }
    }

    private suspend fun DefaultClientWebSocketSession.session(onOpen: () -> Unit, end: CompletableDeferred<String>) {
        val first = withTimeoutOrNull(20_000) { incoming.receive() } ?: throw Exception("no ready frame")
        val ready = (decodeFrame((first as? WsFrame.Text)?.readText().orEmpty()) as? RpcFrame.Event)?.event?.takeIf { it.type == "gateway.ready" }
            ?: throw Exception("unexpected first frame")
        socket = this
        lastInbound = nowMillis()
        onOpen()
        val reader = launch {
            for (frame in incoming) {
                lastInbound = nowMillis()
                if (frame is WsFrame.Text) handle(frame.readText())
            }
        }
        // 15 s pings, checked every second: a link silent for 45 s is dropped within a second (hermes-api.md §1.6).
        val heartbeat = launch {
            var n = 0
            var pingAt = nowMillis()
            while (isActive) {
                delay(1_000)
                val now = nowMillis()
                if (now - lastInbound > 45_000) { end.complete("heartbeat"); break }
                if (now - pingAt >= 15_000) {
                    pingAt = now
                    runCatching { send(WsFrame.Text(params("jsonrpc" to "2.0", "id" to "heartbeat-${n++}", "method" to "gateway.ping", "params" to JsonObject(emptyMap())).toString())) }
                }
            }
        }
        try {
            callOn(this, "client.capabilities", params("server_requests" to true))
            serial++
            _state.value = LinkState.Ready(ready.payload.str("replay_epoch").orEmpty())
            reader.join()
        } finally {
            heartbeat.cancel(); reader.cancel()
            if (socket === this) socket = null
        }
    }

    /** A call during the handshake, before the state is Ready. */
    private suspend fun callOn(ws: DefaultClientWebSocketSession, method: String, params: JsonObject): JsonElement {
        val deferred = CompletableDeferred<JsonElement>()
        val id = lock.withLock { "r${++counter}".also { pending[it] = deferred } }
        try {
            ws.send(WsFrame.Text(params("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params).toString()))
            return withTimeoutOrNull(15_000) { deferred.await() } ?: throw RpcTimeout(method)
        } finally { lock.withLock { pending.remove(id) } }
    }

    private suspend fun handle(text: String) {
        when (val f = decodeFrame(text)) {
            is RpcFrame.Response -> {
                val d = lock.withLock { pending[f.id] } ?: return
                if (f.error != null) d.completeExceptionally(f.error) else d.complete(f.result ?: JsonNull)
            }
            is RpcFrame.Event -> _events.emit(f.event)
            is RpcFrame.Request -> _requests.emit(f.request)
            RpcFrame.Unknown -> Unit
        }
    }

    /** Fails pending calls, only those with ids up to [upTo] when given (calls made before a stop()). */
    private suspend fun failPending(upTo: Long = Long.MAX_VALUE) {
        val all = lock.withLock {
            val old = pending.filterKeys { (it.removePrefix("r").toLongOrNull() ?: 0L) <= upTo }
            old.keys.forEach(pending::remove)
            old.values.toList()
        }
        all.forEach { it.completeExceptionally(Disconnected()) }
    }
}
