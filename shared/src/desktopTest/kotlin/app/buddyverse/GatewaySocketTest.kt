package app.buddyverse

import app.buddyverse.core.Disconnected
import app.buddyverse.core.Gateway
import app.buddyverse.core.LinkState
import app.buddyverse.core.TokenSource
import app.buddyverse.core.WireEvent
import app.buddyverse.core.WireJson
import app.buddyverse.core.obj
import app.buddyverse.core.params
import app.buddyverse.core.str
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The live socket against a fake gateway: subprotocol ticket, handshake, calls, events, server requests, reconnect with a fresh ticket. */
class GatewaySocketTest {
    @Test fun ticketPerDialHandshakeCallsEventsAndReconnect() = testApplication {
        val minted = Collections.synchronizedList(mutableListOf<String>())
        val used = Collections.synchronizedList(mutableListOf<String>())
        val sawCapabilities = Collections.synchronizedList(mutableListOf<Boolean>())
        install(WebSockets)
        routing {
            post("/api/webapp/socket-ticket") {
                if (call.request.headers[HttpHeaders.Authorization] != "Bearer AT") { call.respondText("{}", status = HttpStatusCode.Unauthorized); return@post }
                assertEquals("gateway", call.request.queryParameters["socket"])
                val t = "T${minted.size + 1}"; minted += t
                call.respondText("""{"ticket":"$t","ttl_seconds":30}""")
            }
            webSocket("/api/ws", protocol = "hermes-gateway-v1") {
                val protocols = call.request.headers[HttpHeaders.SecWebSocketProtocol].orEmpty().split(",").map { it.trim() }
                val ticket = protocols.single { it.startsWith("hermes-gateway-ticket.") }.removePrefix("hermes-gateway-ticket.")
                if (ticket !in minted || ticket in used) { close(CloseReason(4401, "bad ticket")); return@webSocket }
                used += ticket
                send(Frame.Text("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","session_id":"","payload":{"replay_epoch":"EP","session_authority":true}}}"""))
                for (frame in incoming) {
                    val o = WireJson.parseToJsonElement((frame as Frame.Text).readText()).obj
                    val id = o.str("id")
                    when (o.str("method")) {
                        "client.capabilities" -> { sawCapabilities += o?.get("params").obj?.get("server_requests").toString() == "true"; send(Frame.Text("""{"jsonrpc":"2.0","id":"$id","result":{"server_requests":["approval","clarify"]}}""")) }
                        "echo" -> {
                            send(Frame.Text("""{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"S","seq":5,"payload":{"text":"hey"}}}"""))
                            send(Frame.Text("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"S"}}"""))
                            send(Frame.Text("""{"jsonrpc":"2.0","id":"$id","result":${o!!["params"]}}"""))
                        }
                        "refuse" -> send(Frame.Text("""{"jsonrpc":"2.0","id":"$id","error":{"code":-32601,"message":"unknown method: refuse","data":{"reason":"unknown_method"}}}"""))
                        "drop" -> { close(CloseReason(CloseReason.Codes.GOING_AWAY, "restart")); return@webSocket }
                    }
                }
            }
        }
        val http = createClient { install(io.ktor.client.plugins.websocket.WebSockets); expectSuccess = false }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gw = Gateway(scope, http, object : TokenSource {
            override val base = "http://localhost"
            override suspend fun accessToken(forceRefresh: Boolean) = "AT"
        })
        val event = CompletableDeferred<WireEvent>()
        val request = CompletableDeferred<String>()
        scope.launch { event.complete(gw.events.first()) }
        scope.launch { request.complete(gw.requests.first().method) }
        try {
            gw.start()
            withTimeout(10_000) { gw.state.first { it is LinkState.Ready } }
            assertEquals(LinkState.Ready("EP"), gw.state.value)
            val r = withTimeout(10_000) { gw.call("echo", params("x" to 1)) }
            assertEquals("""{"x":1}""", r.toString())
            assertEquals("hey", withTimeout(5_000) { event.await() }.payload.str("text"))
            assertEquals("approval", withTimeout(5_000) { request.await() })
            assertFailsWith<app.buddyverse.core.RpcError> { gw.call("refuse") }
            assertTrue("refuse" in gw.unsupported)
            // The server drops the socket: the pending call fails, then the client re-dials with a NEW ticket.
            assertFailsWith<Disconnected> { withTimeout(10_000) { gw.call("drop", JsonObject(emptyMap())) } }
            gw.retryNow()
            withTimeout(15_000) { while (used.size < 2 || gw.state.value !is LinkState.Ready) kotlinx.coroutines.delay(20) }
            assertEquals(listOf("T1", "T2"), used.toList())
            assertEquals(listOf(true, true), sawCapabilities.toList())
            assertEquals("""{"y":2}""", withTimeout(10_000) { gw.call("echo", params("y" to 2)) }.toString())
        } finally {
            gw.stop(); scope.cancel()
        }
    }

    /** A handshake that times out (no capabilities answer) is a failed dial, not the end of the loop; a slow call fails as RpcTimeout. */
    @Test fun handshakeTimeoutRetriesAndCallTimeoutIsPlain() = testApplication {
        val dials = Collections.synchronizedList(mutableListOf<Int>())
        install(WebSockets)
        routing {
            post("/api/webapp/socket-ticket") { call.respondText("""{"ticket":"T${dials.size}"}""") }
            webSocket("/api/ws", protocol = "hermes-gateway-v1") {
                val n = dials.size; dials += n
                send(Frame.Text("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","session_id":"","payload":{"replay_epoch":"EP"}}}"""))
                for (frame in incoming) {
                    val o = WireJson.parseToJsonElement((frame as Frame.Text).readText()).obj
                    // First socket: never answer capabilities (a busy, draining gateway). Later: answer; "slow" is never answered.
                    if (o.str("method") == "client.capabilities" && n > 0) send(Frame.Text("""{"jsonrpc":"2.0","id":"${o.str("id")}","result":{}}"""))
                }
            }
        }
        val http = createClient { install(io.ktor.client.plugins.websocket.WebSockets); expectSuccess = false }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gw = Gateway(scope, http, object : TokenSource {
            override val base = "http://localhost"
            override suspend fun accessToken(forceRefresh: Boolean) = "AT"
        })
        try {
            gw.start()
            withTimeout(40_000) { gw.state.first { it is LinkState.Ready } }
            assertTrue(dials.size >= 2)
            assertEquals(1L, gw.serial)
            assertFailsWith<app.buddyverse.core.RpcTimeout> { gw.call("slow", timeoutMs = 300) }
            assertTrue(gw.state.value is LinkState.Ready)
        } finally {
            gw.stop(); scope.cancel()
        }
    }
}
