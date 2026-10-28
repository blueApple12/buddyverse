package app.buddyverse

import app.buddyverse.core.RpcFrame
import app.buddyverse.core.backoffMs
import app.buddyverse.core.decodeFrame
import app.buddyverse.core.normalizeBaseUrl
import app.buddyverse.core.params
import app.buddyverse.core.str
import app.buddyverse.core.wsUrl
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    @Test fun decodesEventWithStamp() {
        val f = decodeFrame("""{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"S","seq":42,"payload":{"text":"hi"},"execution_generation":3,"authority_epoch":1,"admission_id":"A","replay_epoch":"E"}}""")
        val e = assertIs<RpcFrame.Event>(f).event
        assertEquals("message.delta", e.type); assertEquals("S", e.sessionId); assertEquals(42L, e.seq)
        assertEquals(3L, e.generation); assertEquals("E", e.replayEpoch); assertEquals("hi", e.payload.str("text"))
    }

    @Test fun generationFallsBackToPayload() {
        val f = decodeFrame("""{"method":"event","params":{"type":"session.info","session_id":"S","payload":{"running":true,"execution_generation":9}}}""")
        assertEquals(9L, assertIs<RpcFrame.Event>(f).event.generation)
    }

    @Test fun decodesResponseAndError() {
        val ok = assertIs<RpcFrame.Response>(decodeFrame("""{"jsonrpc":"2.0","id":"r7","result":{"sessions":[]}}"""))
        assertEquals("r7", ok.id); assertNull(ok.error)
        val err = assertIs<RpcFrame.Response>(decodeFrame("""{"jsonrpc":"2.0","id":"r9","error":{"code":4001,"message":"revision_conflict","data":{"reason":"revision_conflict","revision":12}}}"""))
        assertEquals(4001, err.error!!.code); assertEquals("revision_conflict", err.error!!.reason)
        val unknown = assertIs<RpcFrame.Response>(decodeFrame("""{"id":"r1","error":{"code":-32601,"message":"unknown method: x","data":{"reason":"unknown_method"}}}"""))
        assertTrue(unknown.error!!.unknownMethod)
    }

    @Test fun serverRequestNeedsIdAndNonEventMethod() {
        val r = assertIs<RpcFrame.Request>(decodeFrame("""{"jsonrpc":"2.0","id":"srq-5","method":"clarify","params":{"session_id":"S","questions":[]}}""")).request
        assertEquals("srq-5", r.id); assertEquals("clarify", r.method); assertEquals("S", r.sessionId)
        assertIs<RpcFrame.Unknown>(decodeFrame("not json"))
        assertIs<RpcFrame.Unknown>(decodeFrame("""{"method":"event"}"""))
    }

    @Test fun paramsDropNulls() {
        val p = params("a" to 1, "b" to null, "c" to mapOf("d" to listOf("x")), "e" to true)
        assertEquals("""{"a":1,"c":{"d":["x"]},"e":true}""", p.toString())
    }

    @Test fun backoffIsFullJitterCappedAt15s() {
        val r = Random(1)
        repeat(200) { a -> val d = backoffMs(a, r); assertTrue(d in 0..15_000, "attempt $a gave $d") }
        repeat(200) { assertTrue(backoffMs(0, r) <= 300) }
        assertTrue((0 until 500).map { backoffMs(10, r) }.max() > 10_000)
    }

    @Test fun linksNormalize() {
        assertEquals("https://example.trycloudflare.com", normalizeBaseUrl("﻿https://example.trycloudflare.com\r\n"))
        assertEquals("https://example.trycloudflare.com", normalizeBaseUrl("example.trycloudflare.com/login"))
        assertEquals("http://127.0.0.1:9120", normalizeBaseUrl("127.0.0.1:9120"))
        assertEquals("http://127.0.0.1:9120", normalizeBaseUrl("http://127.0.0.1:9120/"))
        assertNull(normalizeBaseUrl("   "))
        assertNull(normalizeBaseUrl("ftp://x"))
        assertEquals("wss://a.b/api/ws?native_dial=browser", wsUrl("https://a.b"))
    }
}
