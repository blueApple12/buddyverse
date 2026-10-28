package app.buddyverse

import app.buddyverse.core.AuthError
import app.buddyverse.core.HermesAuth
import app.buddyverse.core.Tokens
import app.buddyverse.core.WireJson
import app.buddyverse.core.cookieHeader
import app.buddyverse.core.obj
import app.buddyverse.core.sha256
import app.buddyverse.core.str
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The native bearer flow (hermes-api.md §1.4 flow B) against a fake gateway that checks every step. */
class AuthTest {
    private class FakeGateway(val password: String = "pw") {
        var challenge = ""; var state = ""; var pkceCookieSeen = false; var originSeen = false
        val calls = mutableListOf<String>()
        @OptIn(ExperimentalEncodingApi::class)
        val engine = MockEngine { req: HttpRequestData ->
            calls += req.url.encodedPath
            if (req.headers[HttpHeaders.Origin] != null) originSeen = true
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when (req.url.encodedPath) {
                "/auth/native/authorize" -> {
                    challenge = req.url.parameters["code_challenge"]!!; state = req.url.parameters["state"]!!
                    assertEquals("S256", req.url.parameters["code_challenge_method"])
                    assertTrue(req.url.parameters["redirect_uri"]!!.startsWith("http://127.0.0.1"))
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location to listOf("/login"),
                        HttpHeaders.SetCookie to listOf("__Host-hermes_session_pkce_abc=PKCE123; Max-Age=600; Path=/; Secure; HttpOnly; SameSite=none")))
                }
                "/auth/password-login" -> {
                    pkceCookieSeen = req.headers[HttpHeaders.Cookie]?.contains("__Host-hermes_session_pkce_abc=PKCE123") == true
                    val body = WireJson.parseToJsonElement((req.body as TextContent).text).obj
                    if (body.str("password") != password) respond("""{"detail":"Invalid credentials"}""", HttpStatusCode.Unauthorized, json)
                    else respond("""{"ok":true,"next":"http://127.0.0.1:53682/callback?code=CODE43&state=$state"}""", HttpStatusCode.OK, json)
                }
                "/auth/native/token" -> {
                    val body = WireJson.parseToJsonElement((req.body as TextContent).text).obj
                    assertEquals("CODE43", body.str("code"))
                    val verifier = body.str("code_verifier")!!
                    val expect = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(sha256(verifier.encodeToByteArray()))
                    assertEquals(challenge, expect, "PKCE challenge must be base64url(sha256(verifier))")
                    respond("""{"access_token":"AT","refresh_token":"RT","token_type":"Bearer","expires_at":1893456000,"provider":"basic","user_id":"u"}""", HttpStatusCode.OK, json)
                }
                "/auth/native/refresh" -> respond("""{"detail":"session_expired"}""", HttpStatusCode.Unauthorized, json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
    }

    @Test fun signsInWithPkceAndTheBrokeredCode() = runTest {
        val gw = FakeGateway()
        val t = HermesAuth(HttpClient(gw.engine) { followRedirects = false; expectSuccess = false }).signIn("https://x.example", "me", "pw")
        assertEquals(Tokens("AT", "RT", 1893456000.0, "basic"), t)
        assertTrue(gw.pkceCookieSeen, "the PKCE cookie from authorize must ride on password-login")
        assertTrue(!gw.originSeen, "native client never sends Origin")
        assertEquals(listOf("/auth/native/authorize", "/auth/password-login", "/auth/native/token"), gw.calls)
    }

    @Test fun badPasswordIsBadCredentials() = runTest {
        val gw = FakeGateway()
        assertFailsWith<AuthError.BadCredentials> {
            HermesAuth(HttpClient(gw.engine) { followRedirects = false; expectSuccess = false }).signIn("https://x.example", "me", "nope")
        }
    }

    @Test fun expiredRefreshMeansSignInAgain() = runTest {
        val gw = FakeGateway()
        assertFailsWith<AuthError.Expired> {
            HermesAuth(HttpClient(gw.engine) { expectSuccess = false }).refresh("https://x.example", Tokens("a", "r"))
        }
    }

    @Test fun tokensRefreshBeforeExpiry() {
        val t = Tokens("a", "r", expiresAt = 1_000_000.0)
        assertTrue(t.expiresSoon((1_000_000 - 200) * 1000L))
        assertTrue(!t.expiresSoon((1_000_000 - 900) * 1000L))
    }

    @Test fun cookieHeaderKeepsOnlyPairs() {
        assertEquals("a=1; b=2", cookieHeader(listOf("a=1; Path=/; HttpOnly", "b=2", "junk")))
    }
}
