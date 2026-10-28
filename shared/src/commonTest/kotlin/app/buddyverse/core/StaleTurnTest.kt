package app.buddyverse.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaleTurnTest {
    @Test fun suspectOnlyWhenServerIdleAndQuietPastGrace() {
        val now = 100_000L
        assertTrue(StaleTurn.suspect(running = true, lastEventAt = now - 20_000, serverLive = false, now = now))
        assertFalse(StaleTurn.suspect(running = true, lastEventAt = now - 5_000, serverLive = false, now = now)) // fresh events
        assertFalse(StaleTurn.suspect(running = true, lastEventAt = now - 60_000, serverLive = true, now = now)) // long tool call
        assertFalse(StaleTurn.suspect(running = false, lastEventAt = 0, serverLive = false, now = now))
    }

    @Test fun busyIsCode4009OrBusyReason() {
        assertTrue(StaleTurn.isBusy(RpcError(4009, "session busy", null)))
        assertTrue(StaleTurn.isBusy(RpcError(4000, "x", kotlinx.serialization.json.buildJsonObject { put("reason", kotlinx.serialization.json.JsonPrimitive("session_busy")) })))
        assertFalse(StaleTurn.isBusy(RpcError(4001, "invalid_params", null)))
        assertFalse(StaleTurn.isBusy(IllegalStateException("busy")))
    }

    @Test fun busySendRefreshesAndRetriesOnce() = runTest {
        var calls = 0; var refreshed = 0
        val r = StaleTurn.retryOnceIfBusy({ refreshed++ }) { if (++calls == 1) throw RpcError(4009, "session busy", null) else "ok" }
        assertEquals("ok", r); assertEquals(2, calls); assertEquals(1, refreshed)
    }

    @Test fun secondBusyAndOtherErrorsAreThrown() = runTest {
        var calls = 0
        assertFailsWith<RpcError> { StaleTurn.retryOnceIfBusy({}) { calls++; throw RpcError(4009, "session busy", null) } }
        assertEquals(2, calls)
        var refreshed = 0
        assertFailsWith<RpcError> { StaleTurn.retryOnceIfBusy({ refreshed++ }) { throw RpcError(4001, "bad", null) } }
        assertEquals(0, refreshed)
    }
}
