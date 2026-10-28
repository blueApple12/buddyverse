package app.buddyverse.ui.more.processes

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.buddyverse.core.AppStore
import app.buddyverse.core.WireJson
import app.buddyverse.core.api.hostTerminalTicket
import app.buddyverse.core.api.hostTerminalUrl
import app.buddyverse.core.bool
import app.buddyverse.core.obj
import app.buddyverse.core.platformHttpClient
import app.buddyverse.core.str
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface ShellState {
    data object Idle : ShellState
    data object Opening : ShellState
    @Immutable data class Open(val shell: String?, val cwd: String?) : ShellState
    @Immutable data class Closed(val end: ShellEnd, val detail: String? = null) : ShellState
}

enum class ShellEnd { Exited, Busy, Denied, TakenOver, Failed }

/** Close codes of `/api/host-terminal` (hermes-api.md §2.6 Terminal rail). */
fun shellEndOf(code: Int?): ShellEnd = when (code) {
    1000, 4410 -> ShellEnd.Exited
    1013 -> ShellEnd.Busy
    4403, 4404, 4408 -> ShellEnd.Denied
    4409 -> ShellEnd.TakenOver
    else -> ShellEnd.Failed
}

/**
 * One shell on the PC over the sibling socket `/api/host-terminal` (the Webapp's terminal rail): raw PTY bytes in, keystrokes
 * out. Closing it ends the shell on the PC too (`action=close`), so nothing is left running in the background.
 */
@Stable
class HostShell(private val store: AppStore) {
    val buf = TermBuffer()
    var state by mutableStateOf<ShellState>(ShellState.Idle); private set
    private var job: Job? = null
    private var socket: DefaultClientWebSocketSession? = null
    private var scope: CoroutineScope? = null
    private var terminalId: String? = null
    private var profile = "default"

    fun open(scope: CoroutineScope, profile: String, cols: Int, rows: Int) {
        if (job?.isActive == true) return
        this.scope = scope
        this.profile = profile
        buf.cols = cols
        buf.rows = rows
        buf.reset()
        state = ShellState.Opening
        job = scope.launch {
            val http = platformHttpClient()
            try {
                val url = hostTerminalUrl(store.base, store.api.hostTerminalTicket(), profile, cols, rows)
                var code: Int? = null
                http.webSocket(urlString = url) {
                    socket = this
                    var carry = ByteArray(0)
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.readText()
                                if (text.startsWith(META)) onMeta(text.removePrefix(META)) else buf.feed(text)
                            }
                            is Frame.Binary -> if (frame.data.isNotEmpty()) {
                                val bytes = carry + frame.data
                                val n = utf8Complete(bytes)
                                buf.feed(bytes.decodeToString(0, n))
                                carry = bytes.copyOfRange(n, bytes.size)
                            }
                            else -> Unit
                        }
                    }
                    code = closeReason.await()?.code?.toInt()
                }
                if (state !is ShellState.Closed) state = ShellState.Closed(shellEndOf(code))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = ShellState.Closed(ShellEnd.Failed, e.message)
            } finally {
                socket = null
                http.close()
            }
        }
    }

    private fun onMeta(json: String) {
        val o = runCatching { WireJson.parseToJsonElement(json).obj }.getOrNull() ?: return
        o.str("terminalId")?.let { terminalId = it }
        state = if (o.bool("closed") == true) ShellState.Closed(ShellEnd.Exited) else ShellState.Open(o.str("shell"), o.str("cwd"))
    }

    /** Keystrokes as typed: a line ends with `\r`; control keys are their raw bytes (Ctrl+C = `\u0003`). */
    fun send(text: String) {
        val ws = socket ?: return
        scope?.launch { runCatching { ws.send(Frame.Text(text)) } }
    }

    /** The visible size changed (keyboard, rotation): tell the PTY (`ESC[RESIZE:c;r]`, hermes-api.md §2.6). */
    fun resize(cols: Int, rows: Int) {
        if (cols == buf.cols && rows == buf.rows) return
        buf.cols = cols
        buf.rows = rows
        send("\u001b[RESIZE:$cols;$rows]")
    }

    /** A fresh shell with the same size and bot, after this one ended. */
    fun reopen(scope: CoroutineScope) {
        val p = profile; val c = buf.cols; val r = buf.rows
        close()
        open(scope, p, c, r)
    }

    /** Leave and end the shell on the PC. Safe to call twice. */
    fun close() {
        val id = terminalId
        job?.cancel()
        job = null
        socket = null
        terminalId = null
        state = ShellState.Idle
        val p = profile
        if (id != null) store.scope.launch { runCatching { end(id, p) } }
    }

    private suspend fun end(id: String, profile: String) {
        val http = platformHttpClient()
        try {
            http.webSocket(urlString = hostTerminalUrl(store.base, store.api.hostTerminalTicket(), profile, 80, 24, attach = id, close = true)) {
                withTimeoutOrNull(5_000) { for (frame in incoming) Unit }
            }
        } finally {
            http.close()
        }
    }

    private companion object {
        /** First text frame and the close ack: a NUL-prefixed JSON line (wire constant). */
        const val META = "\u0000HERMES_TERMINAL_META:"
    }
}
