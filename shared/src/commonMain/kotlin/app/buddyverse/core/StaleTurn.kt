package app.buddyverse.core

/** A chat that shows "Working" after the server's turn is gone (killed, or its end event lost): how the chat notices and heals. */
internal object StaleTurn {
    /** No event for this long while the server lists the session idle → suspect a stale turn. */
    const val GRACE_MS = 15_000L
    /** At most one stale re-resume per chat this often. */
    const val RESYNC_GAP_MS = 30_000L

    fun suspect(running: Boolean, lastEventAt: Long, serverLive: Boolean, now: Long): Boolean =
        running && !serverLive && now - lastEventAt >= GRACE_MS

    /** `prompt.submit` refuses a running session with 4009 "session busy" (tui_gateway/methods_prompt.py:902). */
    fun isBusy(e: Throwable): Boolean = e is RpcError && (e.code == 4009 || e.reason.contains("busy", ignoreCase = true))

    /** [call]; on a busy refusal, [refresh] (re-resume) and [call] once more. A second refusal is thrown. */
    suspend fun <T> retryOnceIfBusy(refresh: suspend () -> Unit, call: suspend () -> T): T =
        try { call() } catch (e: Exception) {
            if (!isBusy(e)) throw e
            refresh()
            call()
        }
}
