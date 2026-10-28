# Hermes Agent backend API: the contract for the native app

Target: a native Kotlin Compose Multiplatform client (Android + Windows desktop) that offers every Hermes Desktop/Webapp feature by talking to a running Hermes gateway, without embedding Hermes's UI.

- Source of truth: Hermes Agent checkout `%LOCALAPPDATA%\hermes\hermes-agent`, branch `main`, commit `9b904dcc22c23fe8b82834339bbdd96495072e59` (2026-10-26). Every claim cites a path in that repo. Re-check on upgrade: the wire is generated from Python models, so a drift shows up as a diff in `contract/`.
- Machine-readable contract, copied verbatim beside this doc: `contract/gateway-contract.generated.ts` (every RPC method, server→client request and event with its TS type) and `contract/gateway-contract.openrpc.json` (the same as OpenRPC 1.x, plus `x-canonical-methods`). Generate Kotlin `@Serializable` models from the OpenRPC file. Both come from `tui_gateway/contracts/*.py` via `scripts/gen_gateway_contracts.py`.
- Live smoke test: `verify-hermes-api.mjs` (read-only; section 4).
- Appendices A (REST routes) and B (JSON-RPC methods, server requests and events) are generated from the checkout. After a Hermes update, regenerate them with `node _gen/gen-catalogs.mjs "%LOCALAPPDATA%/hermes/hermes-agent" hermes-api.md`, re-copy `apps/shared/src/gateway-contract.*` into `contract/` and diff.

How to read this doc: section 1 is how to connect and stay connected. Section 2 lists every UI feature with the calls behind it. Section 3 covers what only native Desktop can do and what needs a gateway restart. Section 4 records what was verified live. Transport tags used throughout:

| Tag | Meaning |
|---|---|
| **WS canonical** | JSON-RPC method on `/api/ws` served by the gateway's session authority (`gateway/session_controls.py::AuthorityConnection`; Appendix B.1). Closed parameter sets: an unknown or missing key gives `4001 invalid_params`. |
| **WS legacy** | JSON-RPC method on the same `/api/ws` socket that the authority does not serve, forwarded to the legacy `tui_gateway/server.py` dispatcher (Appendix B.2). |
| **REST** | HTTP route on the same origin (Appendix A). JSON in and out unless noted. |
| **sibling WS** | Another WebSocket on the same origin (`/api/host-terminal`, `/api/audio/*-stream`, `/api/display/ws`, `/api/plugins/kanban/events`), each dialed with its own one-use ticket. |
| **server request** | The backend asks the client something over `/api/ws` (`approval`, `clarify`, `sudo`, …; Appendix B.3) and blocks until the client answers. |

---

## 1. Connection and auth

### 1.1 What is running, and where

- One process, the **gateway** (`hermes gateway`), owns every session, tool, model call and messaging platform. With `webapp.enabled: true` in `<HERMES_HOME>/config.yaml` it binds `webapp.host:webapp.port` (default `127.0.0.1:9120`, `gateway/webapp_listener.py`) and serves the Desktop renderer built for the browser (`apps/desktop/dist-webapp`) plus the whole API on that one listener.
- On this PC: `config.yaml` has `webapp: {enabled: true, host: 127.0.0.1, public_url: https://<quick-tunnel>.trycloudflare.com}`. A `public_url` engages the sign-in gate even on the loopback bind, so **every** client, local or remote, must sign in (`/api/health` reports `auth_required: true`).
- Base URL: `http://127.0.0.1:9120` on the PC; the tunnel URL (`C:\Users\alex\buddyverse\bin\phone-link.txt`) elsewhere. All paths below are relative to it. A reverse proxy may mount Hermes under a prefix and send `X-Forwarded-Prefix`; then every path, cookie `Path` and `login_url` carries that prefix (`hermes_cli/dashboard_auth/prefix.py`). The tunnel uses no prefix.
- One gateway can serve several **profiles** (Hermes homes) from one process (`tui_gateway/AGENTS.md` § Profile scope). The launch profile is `default`. REST calls for another profile add `?profile=<name>`; RPC calls add `"profile": "<name>"` to params, or the socket is bound to a profile when its ticket was minted with `?profile=` (section 1.5).

### 1.2 Host and Origin rules (what makes a request "allowed")

From `hermes_cli/web_server_boundary.py` and `hermes_cli/dashboard_auth/middleware.py`:

1. **Host header** must be the bound host (`127.0.0.1`, `localhost`, `::1` on a loopback bind) or exactly the host of `webapp.public_url`. Anything else is refused (`host_mismatch`, HTTP 400/403, WS close 4403). A native client gets this for free as long as it connects to the real URL. **If the quick-tunnel hostname changes, the gateway refuses it until `webapp.public_url` is updated and the gateway restarted** (section 3.2).
2. **Cookie-authenticated writes** (any non-GET/HEAD/OPTIONS request carrying session cookies) must come from the Webapp's own origin. A request **without an `Origin` header** passes (the shape only native code produces). A request with `Origin` passes only if `Sec-Fetch-Site: same-origin`, or Origin equals the public URL or Host. Otherwise: `403 {"detail":"Cookie-authenticated writes must come from the Webapp's own origin. …"}`. **Native client rule: never send `Origin` or `Sec-Fetch-*` on REST calls.** OkHttp and Ktor don't add them by default.
3. **WebSocket upgrades**: `Origin` may be absent, a non-http(s) scheme, or the bound/public host. The credential is the one-use ticket (section 1.5); cookies are not read on upgrades.
4. Nothing checks `User-Agent`. There is no CSRF token: rule 2 is the CSRF defence.

### 1.3 Public (unauthenticated) routes

`GET /api/health` → `{ok, version, displayVersion, commit, auth_required}`; `GET /api/status` → gateway state (keys verified live: `active_agents, active_sessions, auth_flows, auth_providers, auth_required, can_update_hermes, components, config_version, disk, gateway_busy, gateway_drainable, gateway_exit_reason, gateway_heartbeat_stale_s, gateway_mode, gateway_platforms, gateway_running, gateway_shared_with, gateway_state, gateway_updated_at, install_id, latest_config_version, memory, multiplex_standalone_reason, nous_session_valid, overall, parked_profiles, profiles, release_date, restart_drain_timeout, shared_profile_warning, version`); `GET /api/auth/providers` → `{"providers":[{"name":"basic","display_name":"Username & Password","supports_password":true}]}` (live); `GET /api/config/defaults`, `GET /api/config/schema`, `GET /api/model/info`, `GET /api/dashboard/themes`, `GET /api/dashboard/plugins`, `POST /api/cron/fire` (own JWT). Plus the auth bootstrap routes `/login`, `/auth/*` (`hermes_cli/dashboard_auth/public_paths.py`, `middleware.py::_GATE_PUBLIC_PREFIXES`).

Any other `/api/*` call without credentials returns
```json
HTTP 401 {"error":"unauthenticated","detail":"Unauthorized","reason":"no_cookie","login_url":"/login"}
```
`error` is `session_expired` when a presented token is invalid or expired (`reason: invalid_or_expired_session`), and `reason: signed_out` when a cookie login was revoked by a Webapp sign-out. HTML routes 302 to `/login`.

### 1.4 Signing in

The provider on this gateway is `basic` (username/password, `supports_password: true`; credentials in `%LOCALAPPDATA%\hermes\webapp-login.txt`). Two flows work; **use flow B (native bearer) for the app**, flow A is what the browser does.

**Flow A: cookie login (what the Webapp page does)**
```http
POST /auth/password-login
Content-Type: application/json

{"provider":"basic","username":"…","password":"…","next":"/"}
```
→ `200 {"ok":true,"next":"/"}` with four `Set-Cookie`s (verified live):

| Cookie (HTTP) | Over HTTPS | Max-Age | Holds |
|---|---|---|---|
| `hermes_session_at_<gw>` | `__Host-hermes_session_at_<gw>`; Secure | 43200 s (the provider's access-token TTL, 12 h) | access token |
| `hermes_session_rt_<gw>` | `__Host-…` | 2592000 s (30 d; the provider's RT TTL decides) | rotating refresh token |
| `hermes_session_provider_<gw>` | `__Host-…` | 30 d | `basic` (routing hint) |
| `hermes_webapp_signin_<gw>` | `__Host-…` | 30 d | HMAC binding to the gateway's browser credential |

All `HttpOnly; Path=/; SameSite=lax`. `<gw>` is a 16-hex digest of the gateway home (here `651bcb348b6cffda`), so two gateways on one hostname keep separate logins (`hermes_cli/dashboard_auth/cookies.py`, `hermes_cli/webapp_sign_in.py`). Send all four back on every request. When the access token has expired (or its cookie was evicted) and the RT is valid, the middleware refreshes on the fly, serves the request and re-sets the cookies: **persist every `Set-Cookie` you receive**. Errors: 401 `Invalid credentials`, 404 unknown provider, 429 rate limited (per-IP sliding window), 503 provider unreachable. Sign out: `POST /auth/logout` (302 to `/login`, clears the cookies, revokes the RT). `hermes webapp --sign-out` on the PC replaces the browser credential: every cookie login becomes `signed_out` and their sockets close.

**Flow B: native bearer (RFC 8252 brokered by the gateway; `hermes_cli/dashboard_auth/routes.py`, `native_flow.py`)**. It works headless: the loopback redirect never needs to be served, because the password login returns the redirect target in JSON. Verified live over the tunnel:
1. Make a PKCE pair: `code_verifier` = 32 random bytes base64url; `code_challenge` = base64url(SHA-256(verifier)).
2. `GET /auth/native/authorize?provider=basic&code_challenge=<cc>&code_challenge_method=S256&redirect_uri=http://127.0.0.1:<any-port>/callback&state=<nonce>`, without following redirects. → `302 Location: /login` and `Set-Cookie: hermes_session_pkce_<gw>=…; Max-Age=600; SameSite=none` (`__Host-` over HTTPS). `redirect_uri` must be `http://127.0.0.1[:port]/…` or `http://[::1]…` (no `localhost`, no fragment or userinfo).
3. `POST /auth/password-login` **with that PKCE cookie** and body `{"provider":"basic","username":"…","password":"…","next":""}` → `200 {"ok":true,"next":"http://127.0.0.1:<port>/callback?code=<43 chars>&state=<nonce>"}`. No session cookies are set; the PKCE cookie is cleared. Parse `code` from `next` and check `state`.
4. `POST /auth/native/token {"code":"…","code_verifier":"…"}` → `200 {"access_token","refresh_token","token_type":"Bearer","expires_at":<unix s>,"provider":"basic","user_id"}` (expires in about 12 h). The code lives 120 s and works once; the whole pending flow lives 600 s.
5. Send `Authorization: Bearer <access_token>` on every REST call. Before `expires_at`, or on a 401 `session_expired`, call `POST /auth/native/refresh {"refresh_token":"…","provider":"basic"}`, which returns the same shape with rotated tokens. A 401 `session_expired` there means sign in again; 503 means the provider is unreachable, so retry later.

Native tokens are recorded (by digest) in the gateway home, so they survive a gateway restart and a browser sign-out. Tokens taken from a browser cookie login do **not** work as a bearer on this gateway (`webapp_sign_in.token_works_alone`). With a bearer there is no Origin rule (1.2 rule 2 applies to cookies only).

Identity probe: `GET /api/auth/me` → `{user_id, email, display_name, org_id, provider, expires_at}`.

Store tokens in Android Keystore-backed EncryptedSharedPreferences, or Windows DPAPI/Credential Manager. Never log them.

### 1.5 One-use tickets for WebSockets and media URLs

Browsers can't set headers on a WebSocket upgrade, so every socket dial mints a ticket first (`hermes_cli/web_routers/webapp.py`, `dashboard_auth/ws_tickets.py`):
```http
POST /api/webapp/socket-ticket?socket=gateway[&profile=<name>]     (cookie or bearer)
→ 200 {"ticket":"<43 chars>","ttl_seconds":30}      Cache-Control: no-store
```
`socket` ∈ `gateway` (for `/api/ws`), `host-terminal` (`/api/host-terminal`), `speak-stream` / `transcribe-stream` (`/api/audio/…`). A ticket lives **30 s**, opens **one** dial of **that** socket only, and stops working once the browser credential it was minted under is replaced. `profile` (gateway tickets only) is validated (400/404 if unknown) and binds the socket so requests that name no profile are answered for that profile. Mint a fresh ticket for **every** dial, including each reconnect. `POST /api/auth/ws-ticket` mints an unnamed 30 s ticket (dashboard path); prefer the named one.

Media and download URLs that can't carry headers: with cookies or bearer you can call `GET /api/fs/download?path=…` / `GET /api/files/stream?path=…&session_id=…` directly with headers (ExoPlayer and Coil accept headers). The browser's token-session path mints `POST /api/files/ticket {path, profile?, session_id?, route:"download"|"stream"}` → `{ticket}` and appends `&ticket=`.

### 1.6 The live connection: `/api/ws`

**Dial** (`apps/desktop/src/api/canonical-protocol.ts::openGatewaySocket`, server `hermes_cli/web_routers/chat_ws.py::gateway_ws`, `tui_gateway/ws.py::handle_ws`):
```
GET wss://<host>/api/ws?native_dial=browser[&profile=<name>]
Sec-WebSocket-Protocol: hermes-gateway-v1, hermes-gateway-ticket.<ticket>
```
- The protocol list must hold `hermes-gateway-v1` and exactly one `hermes-gateway-ticket.<t>`, or the upgrade closes 4401. The server accepts with `Sec-WebSocket-Protocol: hermes-gateway-v1` and never echoes the ticket. `?ticket=<t>` in the query also works, but keep the ticket out of URLs. `native_dial=browser` is read only by the client (it marks the URL credential-free); the server ignores it.
- Close codes before or at accept (`chat_ws.py::_close_unless_sidecar_allowed`, `gateway_ws`): **4401** bad, missing or expired ticket (mint a new one; if minting returns 401, re-auth), **4403** host/origin refused, embedded chat disabled, or the ticket's profile is not served by this process. Later: **1000** normal; anything else = drop, then reconnect.
- OkHttp: `Request.Builder().url(...).header("Sec-WebSocket-Protocol", "hermes-gateway-v1, hermes-gateway-ticket.$t")`. Ktor: `webSocket(urlString, request = { header(HttpHeaders.SecWebSocketProtocol, …) })`.

**Framing**: JSON-RPC 2.0, one JSON object per WebSocket **text** frame (the server also tolerates a trailing newline). Four frame kinds:
```jsonc
// client → server request (id: any string/number; Desktop uses "r1", "r2", …)
{"jsonrpc":"2.0","id":"r7","method":"session.list","params":{"scope":"stored","limit":50}}
// server → client response
{"jsonrpc":"2.0","id":"r7","result":{"sessions":[…],"scope":"stored"}}
{"jsonrpc":"2.0","id":"r9","error":{"code":4001,"message":"revision_conflict","data":{"reason":"revision_conflict","revision":12}}}
// server → client notification (event)
{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"…","seq":42,"payload":{…},
  "execution_generation":3,"authority_epoch":1,"admission_id":"…","replay_epoch":"…"}}
// server → client REQUEST: string id + method other than "event"; answer with the same id
{"jsonrpc":"2.0","id":"srq-5","method":"clarify","params":{"session_id":"…","questions":[…]}}
{"jsonrpc":"2.0","id":"srq-5","result":{"answers":{…}}}          // or "error":{"code":-32601,…}
```
Decoder rule (`apps/shared/src/json-rpc-channel.ts::handleFrame`): string `id` + `method` ≠ `"event"` → server request. `id` matching a pending call → response. `method:"event"` → notification.

**Handshake** (verified live):
1. The server's first frame is `event gateway.ready`, payload `{skin: SkinPayload, change_events: true, heartbeat: true, replay_epoch: "<process epoch>", session_authority: true}`. `session_authority: true` means `session.*`/`prompt.*`/`message.*` speak the canonical contract.
2. The client sends `client.capabilities {"server_requests": true}` once per connection. Live result: `{"server_requests":["approval","clarify","display.install.sudo","preview.act","preview.read","secret","setup_beat","sudo","terminal.read","theme.apply","theme.pick","tour","vault.code","vault.save_login","vault.unlock_prompt","window.read"],"declines_not_shown":true}`. Without it the backend treats the socket as an old client and fails every approval/clarify for it at once. Send `desktop_local: true` **only** for a client on the gateway's own machine: it unlocks first-run onboarding and the welcome trial.
3. Optional: `runtime.describe {}` → `{instance_id, profile_id, authority_epoch, capabilities:["durable-admission-v1","event-replay-v1","local-cli-create-v1","acp-editor-policy-v1","acp-session-mcp-v1","clarify-batch-v1"], session_create: SessionCreateDescriptor, skin}` (live). Gate features on `capabilities`.

**Heartbeat**: the server never pings. The client sends `{"jsonrpc":"2.0","id":"heartbeat-<n>","method":"gateway.ping","params":{}}` every **15 s**; the server answers `{"result":{"ok":true}}` inline, ahead of the request queue (live: 0–1 ms locally, about 120 ms over the tunnel). The Desktop counts **any inbound frame** as liveness and drops the socket when no frame arrives for **45 s** after a ping (`DEFAULT_HEARTBEAT_INTERVAL_MS`/`DEFAULT_HEARTBEAT_DEADLINE_MS`). The server also enables TCP keepalive and `TCP_NODELAY`.

**Request handling on the server**: requests run **serially in arrival order** per socket (bounded queue of 256). The exceptions are answers to server requests and `clarify.lock`, which bypass the queue, and the read `session.context_breakdown`, which runs concurrently (max 2). A slow handler therefore delays the next request on the same socket. Keep the per-request timeout at about 120 s (Desktop default), 300 s for `approval.respond`, 10 s for replay. Streaming events `message.delta`, `reasoning.delta` and `thinking.delta` are coalesced server-side into roughly 33 ms batches. Every other event flushes the batch first, so ordering holds.

**Which dispatcher answers what** (`tui_gateway/ws.py::_dispatch_request`, `tui_gateway/ws_legacy_fallback.py`):
- The authority answers (from `AuthorityConnection.handlers()` and the modules it merges in; `gateway/session_controls.py:171`): the canonical-only methods of Appendix B.1, plus these shared-name methods, using the legacy contract shapes unless B.1 overrides them: `session.create/resume/list/info/interrupt/steer/redirect/mutate/detach/prune/stats/export/history/save/automation/control/control.read/context_breakdown/active_list/events.since`, `prompt.submit/receipt/cancel/resolve_unknown/replace_queued/steer_queued/background/btw`, `message.react`, `approval.respond/pending/received`, `approvals.mode`, `clarify.respond/lock`, `commands.catalog`, `complete.slash`, `complete.path`, `slash.exec`, `command.dispatch`, `command.observe`, `config.get/set` (restricted keys, see section 2.3), `model.options`, `profiles.list`, every `groups.*` (`capabilities, list, state, log, create, rename, disband, send, stop, approve, discard, retry, attachment.upload/download`), `cron.submit/cancel/status/recover`, `kanban.run`, `image.attach_bytes`, `file.attach`, `shell.exec`, `process.kill/stop` (session-scoped), `rollback.list/diff/restore/session_diff`, `skills.reload`, `tools.show`, `subagent.list`, `reload.env`, `reload.mcp`, `delegation.pause` (owner rules), `preview.restart`, `llm.oneshot`, `plugins.list`, `bot_relay.deliver/settle/reply/outbox.drain/roster.sync`, `a2a.forward`, `connection.respond`, `connectors.operation.status/wake`, `connectors.connect` (session-owned only), `onboarding.start/skip`, `setup.status/runtime_check`, `runtime.describe`, `ping`, `worker.*`.
- For an unknown method, the request falls back to the legacy handler (Appendix B.2) **unless** it is in the `session.`, `prompt.` or `message.` namespace (only `session.most_recent`, `session.active_list`, `session.events.stats`, `session.foreign.list/preview/import` may fall back) or process-global (`process.stop`, `delegation.pause`, `reload.mcp`, `reload.env`, `agents.list` are refused). Refused or unknown methods return `-32601 {"message":"unknown method: <m>","data":{"reason":"unknown_method"}}` (live). The Desktop treats `-32601` as "this backend lacks the feature" and hides it. Do the same.
- So on this gateway: session lifecycle and turns go through the canonical verbs, and nearly everything else (pets, connectors, MCP, model options, projects, groups list, voice, billing …) through legacy methods on the same socket.

**Error codes** (`gateway/session_controls.py`, `tui_gateway/server.py`, `apps/shared/src/json-rpc-channel.ts`):

| code | meaning |
|---|---|
| -32700 | parse error |
| -32601 | unknown method / not served here (`data.reason: unknown_method`) |
| -32603 | internal error (handler crashed) |
| 4000 | legacy dispatcher: unknown param key (message names the key path) |
| 4001 | canonical refusal; `message` = `data.reason`. Frequent reasons: `invalid_params` (+`data.fields`), `permission_denied`, `not_found`, `profile_mismatch`, `revision_conflict` (+`data.revision`), `stale_generation` (+`data.execution_generation`), `session_busy`, `admission_conflict`, `runtime_draining` (gateway restarting: only answer parked turns), `execution_not_ready`, `unsupported_control`, `unsupported_command`, `storage_unavailable`, `launch_credentials_unavailable`, `model_resolution_failed`, `export_too_large` |
| 4064 | legacy: profile unavailable |
| 4404 | client answer to a window-owned server request: "no window here shows this session" (send only if `declines_not_shown`) |
| 5001 | `storage_unavailable` (sqlite error) |

**Concurrency fences (canonical)**. The authority guards shared state with two numbers. A client must track both per `(profile, session_id)` (`apps/desktop/src/api/canonical-protocol.ts`):
- `revision`: compare-and-swap counter of the session's metadata/transcript. It comes from the `session.resume`/`session.create` snapshot, every `session.info` event and every `session.updated` receipt (adopt only if larger). Every `session.mutate` sends `expected_revision`.
- `execution_generation`: which turn-runner generation owns the session. It is stamped on events (`params.execution_generation`), snapshots and prompts. `session.interrupt/steer/redirect`, prompt answers and fenced mutations send it. Ignore events whose generation is lower than the one you hold.
- A `revision_conflict` / `stale_generation` refusal carries the current values in `data`. Adopt them; a value edit (model, reasoning) may be retried once at the new fences. Never blindly retry a CAS edit of shared metadata.
- Every mutating request carries a client-generated `request_id` (UUID; `submission_id` for `prompt.submit`). Retrying after a lost reply **must reuse the same id**: the owner then answers it as an exact retry instead of running it twice.

**Reconnect and resume** (`apps/shared/src/json-rpc-gateway.ts`, `reconnect-backoff.ts`):
1. On close or heartbeat failure, reject all pending calls and reconnect with full-jitter exponential backoff: delay = random(0, min(15 000, 300 · 2^attempt)) ms. Reset the ladder once a socket has stayed open 5 s. **Mint a new ticket for every attempt.**
2. After the new `gateway.ready` and `client.capabilities`: if `gateway.ready.replay_epoch` differs from the previous one, the backend process restarted. Drop every per-session `seq` watermark and re-open (`session.resume`) the sessions on screen.
3. Otherwise, for each session you hold a watermark for (Desktop keeps at most 256, LRU), call `session.events.since {"session_id","last_seen":<last seq>, "replay_epoch":<session epoch>}` (canonical; `last_sequence` is an alias) → `{events, latest_seq, last_sequence, epoch, replay_epoch, truncated, snapshot_required, count, open_requests?}`. Buffer live events for that session until the replay lands, dispatch replayed events whose `seq` is greater than the watermark (mark them `replayed`), then flush the buffer. `snapshot_required: true` or `truncated: true` means the gap can't be replayed: call `session.resume` for a fresh snapshot (live: a session not running in the authority answers `truncated:true, snapshot_required:true`). An error with `data.reason: "not_found"` means the session is gone: forget its watermark.
4. `open_requests` (from `session.resume` / `session.events.since`): approvals, clarifies and other server requests still waiting. Deliver them to your request handlers as if just received (same `id`). Answering twice is harmless because the first answer wins.
5. Event `session.replay_gap {replay_epoch?, latest_seq?}` (canonical fan-out overflow): re-resume that session.
6. Turns keep running server-side while you are disconnected: the authority holds the agent. A socket's disconnect detaches its viewers. Sessions are reaped only after a grace window (`tui_gateway/session_reaper.py`), and `session.resume` reattaches.

**Change signals** (`change_events: true`): `sessions.changed`, `projects.changed`, `cron.changed`, `skills.changed`, `pet.changed`, `platforms.changed`, `pairing.changed`, `skin.changed`, `bot_relay.outbox.pending`. Their payloads are empty or minimal: refetch the matching list. Live: `platforms.changed` arrived within 40 s of connecting.

**Minimal client loop** (Kotlin-ish pseudocode):
```kotlin
suspend fun connect() {
  val ticket = http.post("/api/webapp/socket-ticket?socket=gateway").body<Ticket>().ticket   // bearer header
  val ws = wsClient.connect("$wsBase/api/ws?native_dial=browser",
      protocols = listOf("hermes-gateway-v1", "hermes-gateway-ticket.$ticket"))
  val ready = ws.awaitEvent("gateway.ready")                    // first frame
  rpc("client.capabilities", mapOf("server_requests" to true))
  if (ready.replayEpoch != lastEpoch) resetWatermarks() else replayOpenSessions()  // session.events.since
  launch { while (isActive) { delay(15_000); rpc("gateway.ping", emptyMap(), timeout = 45_000) } }
}
```

---

## 2. Feature inventory: every Desktop/Webapp feature and the calls behind it

The UI lives in `apps/desktop/src/app/*` (screens), `src/plugins/{hermes-bots,kanban}` (Bot Mode, Kanban), `src/components/*` and `src/store/*`. Six slices follow (2.1–2.6). Each `####` feature lists **UI** (renderer source), **Calls**, **Events** and **Notes**. Where the renderer sends a legacy-shaped call that `apps/desktop/src/api/canonical-protocol.ts` rewrites, the doc gives the **canonical wire form**: that is what a native client sends.

Index of the features this contract must cover:

| Feature | Section | Core calls |
|---|---|---|
| Sessions: list, live badges | 2.1 Sessions sidebar list, Live / running badges | `GET /api/profiles/sessions/sidebar`, `GET /api/sessions`, `session.active_list`, `sessions.changed` |
| Create, open/resume, close | 2.1 Create / Open / Close | `session.create` (canonical, `source:"gui"`, `request_id`), `session.resume` snapshot, `session.detach` |
| Rename, pin, archive, unread, hidden, delete | 2.1 Rename / REST fenced writes / Delete | `session.mutate {operation:"rename"\|"archive"}`, `PATCH /api/sessions/{id}`, `DELETE /api/sessions/{id}` |
| Search | 2.1 Search (FTS) | `GET /api/sessions/search` |
| Fork/branch, rewind, undo, retry, edit, regenerate, compress | 2.1 Fork / Rewind / Compress | `session.mutate {operation:"branch"\|"rewind"\|"compress"}` + `prompt.submit` |
| History paging, export, import | 2.1 Transcript history / Export / Import | `GET /api/sessions/{id}/messages`, `…/export`, `session.foreign.*` |
| Resume after reconnect | 2.1 Event replay, 1.6 | `session.events.since`, `session.replay_gap` |
| Send a message, queue, edit queued | 2.2 Submit a prompt, Admission receipt | `prompt.submit {submission_id}`, `prompt.cancel/replace_queued/steer_queued/receipt/resolve_unknown` |
| Streaming tokens, thinking/reasoning, tool calls/results | 2.2 Turn event stream, Reconstructing a message | `message.start/delta/interim/complete`, `reasoning.delta`, `thinking.delta`, `tool.generating/start/progress/complete` |
| Approvals / permission prompts | 2.2 Approvals | events `approval.request/settled` → `approval.respond {execution_generation, prompt_id, choice}` |
| AskUserQuestion-style questions | 2.2 Clarify | `clarify.request` / server request `clarify` → `clarify.respond`, `clarify.lock` |
| Sudo / secret / vault prompts | 2.2 Sudo, secret and vault | server requests `sudo`, `secret`, `vault.*` |
| Plan / todos | 2.2 Plan / todos | `todo.updated`, `tool.complete.todos` |
| Interrupt, stop, steer, redirect | 2.2 Interrupt / Steer and redirect | `session.interrupt/steer/redirect {execution_generation}` |
| Subagents, background agents | 2.2 Subagents / Background agents | `subagent.list/tail/steer/interrupt`, `prompt.background`, `background.complete` |
| Attachments / uploads | 2.2 Attachments, 2.6 Managed files | `POST /api/chat/image-upload`, `POST /api/chat/file-upload`, `image.attach_bytes`, `file.attach` |
| Voice / dictation, wake word | 2.2 Voice and dictation, 2.6 Wake word | sibling WS `/api/audio/transcribe-stream`, `/api/audio/speak-stream`, `voice.*`, `wake.*` |
| Message reactions | 2.2 Message reactions | `message.react`, `message.reaction` |
| Slash commands, palette, command center, quick entry | 2.3 Slash command catalog … Command palette | `commands.catalog`, `complete.slash`, `complete.path`, `slash.exec`, `command.dispatch` |
| Models, providers, reasoning effort, fast, personality, yolo/approvals | 2.3 Model picker … YOLO and approvals mode | `model.options`, `session.mutate {operation:"model"\|"reasoning"}`, `config.set`, `/api/model/*`, `/api/providers/oauth/*` |
| Settings (every page) | 2.3 Settings page → endpoints | `GET/PUT /api/config`, `GET/PUT /api/env`, `config.get/set` |
| Billing, free tier, onboarding, updates | 2.3 Billing … Updates | `billing.*`, `subscription.*`, `free_tier.*`, `onboarding.*`, `GET /api/hermes/update/check` |
| Bots (Bot Mode roster, sections, pin/hide, activity) | 2.4 Bot roster … Activity signals | `profiles.list {include_sessions}`, `ui_meta` writes |
| Bot Chat (canonical forever-chat), side chats | 2.4 Open the canonical Bot Chat / Side chats | `session.list {title:"Bot Chat", include_hidden:true}`, `session.create {title:"Bot Chat", hidden:true}` |
| Bot create/edit/delete/duplicate, avatar, title/description, SOUL, skills, toolsets, MCP, model | 2.4 Create … Model / provider per bot | `POST/PATCH/DELETE /api/profiles…`, `PUT /api/profiles/{name}/soul`, profile-scoped skills/tools/MCP calls |
| Bot-to-bot messaging | 2.4 Bot relay | `bot_relay.*` |
| Group chats / rooms | 2.4 Group chats … | `groups.*` (canonical), polling `groups.state` + `groups.log` |
| Pets | 2.4 Pets … | `pet.*`, `pet.changed` |
| Routines / cron (CRUD, run now, history) | 2.5 Routines | `/api/cron/*` REST, `cron.changed` |
| Webhooks | 2.5 Webhooks | `/api/webhooks*` |
| Kanban | 2.5 Kanban | `/api/plugins/kanban/*`, WS `/api/plugins/kanban/events` |
| Projects / workspaces | 2.5 Projects and workspaces | `projects.*`, `GET /api/chat/workspaces`, `session.mutate {operation:"workspace"}` |
| Skills (list, view, create, edit, delete, hub, curator) | 2.5 Skills, 2.6 Skills | `/api/skills*`, `skills.*`, `skills.changed` |
| Memory (MEMORY.md / USER.md, providers) | 2.5 Memory / Memory providers | `learning.*`, `/api/memory/*` |
| Delegation, goals, plugins | 2.5 Delegation / Plugins | `delegation.*`, `session.automation`, `plugins.manage` |
| Capabilities: tools/toolsets, MCP, connectors, plugins | 2.6 Tools … Plugins | `/api/tools*`, `tools.*`, `/api/mcp*`, `mcp.*`, `reload.mcp`, `connectors.*`, `connection.*` |
| Messaging platforms | 2.6 Messaging platforms | `/api/messaging/*`, `platforms.changed`, `pairing.changed` |
| Artifacts | 2.6 Artifacts | (see section) |
| Files tree, downloads | 2.6 Files tree / Managed files | `/api/fs/*`, `/api/files/*` |
| Git review, rollback | 2.6 Git review / Rollback | `/api/git/*`, `rollback.*` |
| Terminal rail, agent terminals | 2.6 Terminal rail / Agent terminals | sibling WS `/api/host-terminal`, `process.*`, `agent.terminal.output` |
| Previews (in-app browser) | 2.6 Preview pane | server requests `preview.read/act`, `preview.restart`, `browser.manage` |
| Bot screen | 2.6 Bot screen | `display.*`, WS `/api/display/ws` |
| Insights / usage / analytics | 2.6 Insights, usage and analytics | `/api/analytics/*`, `insights.get`, `usage.*` |
| Gateway status, logs, restart, update | 2.6 Gateway status … restart | `/api/status`, `/api/logs`, `POST /api/gateway/restart`, `/api/actions/{name}/status` |
| Notifications, "needs you" badges | 2.6 Notifications / "Needs you" badges, 2.4 Activity signals | `approval.request`, `clarify.request`, `message.complete`, `notification.show`, `unread` |
| Panes, HUD, agent-driven UI | 2.6 Workspace panes | server requests `tour`, `theme.*`, `pane.reveal` |

### 2.1 Sessions and history

Source: `hermes-agent` main @ 9b904dcc22. Paths are relative to the repo root. Type names (for example `CanonicalSnapshot`) refer to `app/docs/contract/gateway-contract.generated.ts`.

#### Ground rules for this slice

- **Two transports.** Reads of the stored history (sidebar list, search, transcript pages, timeline) are **REST**, against each profile's `state.db`. Lifecycle (create, open, mutate, live state) goes over **WS RPC canonical** (`/api/ws`, JSON-RPC 2.0). Sidebar flag edits (pin, archive, unread, hidden, rename of a session that isn't open) and delete are **REST writes**. They go through the same fenced owner mutation as `session.mutate`.
- **Session id.** A canonical local session id is `local-<sha256 hex>`: `sha256(json([profile_id, principal, request_id]))` (`hermes_state_keys.py::local_identity`). A retried `session.create` with the same `request_id` therefore lands on the same session. Messaging and legacy rows keep their own ids. A REST read can resolve a compressed chain to its tip id: the response `session_id` may differ from the id you asked for. Use the returned id.
- **Attach before acting.** The owner keeps one subscription per (socket, session). `prompt.submit`, `approval.respond`, `clarify.respond`, `session.interrupt`, `prompt.cancel`, the rewind mutation and the queued-prompt controls need the session attached on **this** socket first, through `session.create` or `session.resume`. Without that, `prompt.submit` answers `permission_denied` (`gateway/session_controls.py::submission`). The Desktop auto-resumes with `{session_id, defer_history:true, omit_messages:true}` before these methods (`apps/desktop/src/api/client.ts::attachForRequest`, `ATTACH_REQUIRED`). A branch also needs its parent attached, to learn the parent's revision.
- **Fences.** Every canonical mutation carries:
  - `request_id`: a UUID. It is retry-idempotent. Reusing it with a different payload answers `admission_conflict`.
  - `expected_revision`: an int, the CAS on `sessions.runtime_revision`.
  - `expected_generation`: `runtime_generation`. Required for `delete, rewind, reset, branch, model, compress, reasoning, workspace, editor_mcp, edit_policy`. Optional for `rename/archive/sidebar/import`; it is checked when sent.

  The client caches both counters per (profile, session_id):
  - `revision`: from the snapshot top level, every `session.info` event payload `revision`, `session.updated` payloads (keep the max), and mutation receipts.
  - `execution_generation`: from the snapshot, plus the frame-level or payload `execution_generation` of every event. Ignore values lower than the cached one.

  On `revision_conflict` / `stale_generation`, `error.data.revision` and `error.data.execution_generation` carry the current values. Adopt them. Retry once with a **new** `request_id` for value edits (model/reasoning). For anything else, show the conflict (`canonical-protocol.ts::failure/retryableFence`). On a transport failure (no reply), retry with the **same** `request_id` and the same fences.
- **Profile routing.**
  - WS: add `"profile":"<name>"` to params. The host-scoped Desktop/dashboard socket routes it to that profile's owner. A profile-bound ticket naming another profile gets `profile_mismatch`. For the launch profile, `profile` is stripped before the closed contract check. The Desktop always sends it, `default` included, on `session.create`.
  - REST: `?profile=<name>` on GET/DELETE, `"profile"` in the body of PATCH/POST. Destructive bulk routes (`bulk-delete`, `empty`, `prune`) answer 400 without a profile when the host serves more than one profile (`web_routers/_common.py::destructive_profile`).
- **WS error frame.** `{"jsonrpc":"2.0","id":7,"error":{"code":4001,"message":"<reason>","data":{"reason":"<reason>","field"?:..,"detail"?:..,"revision"?:int,"execution_generation"?:int}}}`.
  - Contract key violations: `4001 invalid_params` with `data.fields`.
  - Unknown method: `-32601` with `data.reason:"unknown_method"`.
  - SQLite failure: `5001 storage_unavailable`, which is retryable.
  - Legacy fallback with a bad profile: `4064 "profile unavailable"`.
- **REST error.** FastAPI `{"detail": "<reason or text>"}`. Mutation reason mapping (`web_server_sessions.py::_MUTATION_REFUSAL_STATUS`):
  - 403: `permission_denied`, `profile_mismatch`.
  - 404: `not_found`.
  - 400: `invalid_params`, or a `ValueError` such as a title conflict.
  - 503: `runtime_draining`, `storage_unavailable`, `owner_unreachable`, `owner_unavailable: ...`, `session_authority_unavailable`.
  - 409 for everything else: `revision_conflict`, `stale_generation`, `session_busy`, `admission_conflict`, `session_has_children`, `runtime_coordination_required`, `mutation_identity_required` (missing `request_id` or `expected_revision`), and a client-upgrade text when no identity was sent at all.
  - 401: unauthenticated.
- **Legacy names the renderer sends and their canonical wire form.** `apps/desktop/src/api/canonical-protocol.ts`. A native client sends only the right-hand side.

| Renderer (legacy) call | Canonical wire |
|---|---|
| `session.create {cols, source:'desktop', cwd, cwd_explicit, profile, model, provider, reasoning_effort, fast, service_tier}` | `session.create {request_id, source:"gui", cwd?, cwd_explicit?, profile, model?, provider?, reasoning?, service_tier?, toolsets?, title?, hidden?, follow_profile_config?}` (`cols` dropped; `fast:true` becomes `service_tier:"priority"`, `fast:false` becomes `"normal"`) |
| `session.activate {session_id, cols, omit_messages}` | `session.resume {session_id, source:"desktop", profile?}` |
| `session.title {session_id, title}` | `session.mutate {operation:"rename", payload:{title}}` (no generation) |
| `session.archive {session_id, archived}` | `session.mutate {operation:"archive", payload:{archived}}` |
| `session.branch {session_id, count, through_message_id}` / `session.branch_whole` / `session.branch_stored {parent_session_id, title?}` | `session.mutate {operation:"branch", payload:{through_message_id?, title?}, expected_generation}` on the **parent** |
| `session.rewind {session_id, target_message_id}` | `session.mutate {operation:"rewind", payload:{target_message_id}, expected_generation}` |
| `session.compress {session_id, focus_topic?}` | `session.mutate {operation:"compress", payload:{focus?}, expected_generation}`, then `session.resume` to repaint |
| `prompt.submit {..., truncate_before_row_id, confirm_truncate, ...}` (edit / regenerate / restore) | `session.mutate rewind {target_message_id: truncate_before_row_id}`, then `prompt.submit {session_id, text, submission_id}` |
| `prompt.submit` without an id | `prompt.submit` with `submission_id: crypto.randomUUID()` |
| `session.cwd.set {session_id, cwd}` / `session.workspace.move {session_key, cwd}` | `session.mutate {operation:"workspace", payload:{cwd}, expected_generation}` |
| `config.set {key:"reasoning", value}` (session scope) | `session.mutate {operation:"reasoning", payload:{effort}}` |
| `session.close` | **not served** on canonical (`-32601`; the renderer swallows it). Use `session.detach`. |
| `session.usage` | **not served** on canonical. Use `session.stats`. |

---

#### Sessions sidebar list (recents / cron / messaging, paging, archived, pinned)

**UI:** `apps/desktop/src/app/chat/sidebar/index.tsx`, `sessions-section.tsx`, `virtual-session-list.tsx`, `load-more-row.tsx`, `filter-menu.tsx`, `order.ts`. Refresh logic: `apps/desktop/src/app/session/hooks/use-session-list-actions.ts`. Archived view: `apps/desktop/src/store/sidebar-archive.ts`. API wrappers: `apps/desktop/src/api/sessions.ts` (`listSidebarSessions`, `listAllProfileSessions`, `listSessions`).

**Calls (REST):**

1. **Batched sidebar:** `GET /api/profiles/sessions/sidebar` (`hermes_cli/web_routers/profiles.py`, single-flight cached per DB fingerprint).
   - Query:
     - `recents_profile`: str, default `all`. It scopes the whole payload.
     - `recents_limit`: int 1..500, default 20. The Desktop sends `$sessionsLimit`, which starts at 50 and grows by 50 on "Load more".
     - `recents_exclude`: CSV. The Desktop sends `acp,cron,kanban,oneshot,subagent,tool,<messaging ids: telegram,discord,slack,mattermost,matrix,signal,...>`.
     - `cron_limit`: default 50.
     - `messaging_limit`: default 100.
     - `messaging_exclude`: CSV, `cron` plus the local ids `acp,cli,codex,desktop,gateway,kanban,...`.
   - Every slice uses `min_messages=1`, `archived=exclude`, recency order, `include_pinned` (pinned rows past the window are appended) and hides `hidden=1` rows.
   - Result: `{recents:{sessions:SessionRow[], profiles_truncated:{<profile>:bool}, profiles_usage:{<profile>:{cost_usd,tokens}}}, cron:{sessions}, messaging:{sessions,total}, errors?:[{profile,error}], profiles_failed?, storage?:{<profile>:"corrupt"}}`. A failed load slice carries `failed:true, retry:true`.
   - A 404 means an older backend. Fall back to three `GET /api/profiles/sessions` calls.
2. **Per-slice / paging / archived view:** `GET /api/profiles/sessions`.
   - Query:
     - `limit`: 0..500, default 20.
     - `offset`: default 0.
     - `min_messages`: default 0.
     - `archived`: `exclude|only|include`.
     - `order`: `recent|created`, default `recent`.
     - `profile`: `all` or a name.
     - `source`, `sources` (CSV), `exclude_sources` (CSV).
     - `full`: bool. `full=1` keeps `system_prompt` and `model_config`.
   - Result: `{sessions, total, profile_totals:{<profile>:int}, limit, offset, errors, storage}`.
   - Archived view: `limit=ARCHIVED_FETCH_PAGE_SIZE&offset=N&archived=only&profile=all`.
   - Messaging "load more": `source=<platform>&limit=loaded+50`.
3. **Single-profile list:** `GET /api/sessions` (`hermes_cli/web_routers/sessions.py::get_sessions`). Query: `limit` 0..100 (default 20), `offset`, `min_messages`, `archived`, `order` (`created` default \| `recent`), `source`, `sources`, `exclude_sources`, `cwd_prefix`, `full`, `profile`. Result: `{sessions, total, limit, offset, storage}`.
4. **One row by id or unique prefix:** `GET /api/sessions/{id}?profile=`. Returns the full `sessions` row plus `profile`, `is_default_profile`, and for cron rows `scheduler_owned`. 404 `{"detail":"..."}` when absent.
5. **Newest child leaf of a lineage:** `GET /api/sessions/{id}/latest-descendant?profile=` → `{requested_session_id, session_id, path:[...], changed}`.

**Session row** (`SessionInfo` in `apps/desktop/src/types/hermes.ts`; `list_sessions_rich` compact columns):
- `id`, `title|null`, `preview|null`, `source`, `model|null`, `cwd`, `git_branch`, `git_repo_root`.
- `started_at`, `ended_at|null`, `last_active`.
- `message_count`, `tool_call_count`, `input_tokens`, `output_tokens`, `actual_cost_usd`, `estimated_cost_usd`.
- `parent_session_id`, `_branched_from`, `_reset_from`, `_lineage_root_id`, `_lineage_ids[]`, `continuation_kind:"compression"`.
- `archived:bool`, `pinned:bool`, `hidden`, `unread`.
- `is_active`: `ended_at==null && now-last_active<300s`.
- `is_internal_child`, `handoff_platform/state/error`, `profile`, `is_default_profile`, and `scheduler_owned` (cron).

Example:
```json
{"recents":{"sessions":[{"id":"local-9f2c…","title":"Fix login flow","preview":"the login button…","source":"desktop","model":"claude-sonnet-4-5","cwd":"C:\\src\\app","git_branch":"main","git_repo_root":"C:\\src\\app","started_at":1761400000.1,"ended_at":null,"last_active":1761401234.5,"message_count":14,"tool_call_count":6,"input_tokens":52012,"output_tokens":3021,"archived":false,"pinned":true,"unread":false,"is_active":true,"profile":"default","is_default_profile":true}],"profiles_truncated":{"default":false},"profiles_usage":{"default":{"cost_usd":1.92,"tokens":901233}}},"cron":{"sessions":[]},"messaging":{"sessions":[],"total":0}}
```

**Grouping:**
- Date buckets: `apps/desktop/src/lib/session-date-groups.ts`.
- Branch nesting by `_branched_from`. `_reset_from` rows render as siblings (`lib/session-branch-tree.ts`).
- Project/workspace grouping happens on the client, keyed by `git_repo_root` then `cwd` (`app/chat/sidebar/projects/workspace-groups.ts`). Project membership comes from WS `projects.tree` / `projects.project_sessions` (Projects slice).
- Pinned section = rows with `pinned:true`.

**Events:**
- `sessions.changed` (global; the change watcher polls the `state.db` signature every 0.5 s and broadcasts at most once per 2 s) → refetch the list. Frame:
```json
{"jsonrpc":"2.0","method":"event","params":{"type":"sessions.changed","session_id":"","payload":{}}}
```
- `gateway.ready` advertises `payload.change_events:true`. List polling then becomes a backstop only.

**Notes:**
- 503 means the store is busy or corrupt (`detail` text, or `{"storage":{"default":"corrupt"}}`). Keep the old list and offer Retry.
- Rows from `/api/profiles/sessions*` are read straight off each profile's DB, with no owner involved. Live state comes from `session.active_list` (below).

#### Live / running badges

**UI:** `apps/desktop/src/store/session-states.ts` (`liveTurnVerdict`, `checkLiveTurn`), `apps/desktop/src/app/contrib/hooks/use-background-sync.ts` (`refreshLiveStatuses`), `app/chat/sidebar/session-row.tsx`.

**Calls (WS RPC canonical):** `session.active_list` (`gateway/session_active_list.py`).
- Params: `{current_session_id?: str, profile?: str}`. Any other key answers `invalid_params`.
- Result: `SessionActiveListResult` = `{sessions:[{current, id, session_key, last_active, started_at, message_count, model, preview:"", title, source, status}]}`. `status` is one of:
  - `working`: a turn is running.
  - `waiting`: running with an approval or clarify prompt open.
  - `starting`: queued, not yet claimed.
  - `idle`.
- Only sessions this owner holds live are listed, hidden ones included. Internal sources and closed sessions are excluded. A session missing from the list means its turn is over.
```json
{"jsonrpc":"2.0","id":12,"method":"session.active_list","params":{"current_session_id":"local-9f2c…"}}
{"jsonrpc":"2.0","id":12,"result":{"sessions":[{"current":true,"id":"local-9f2c…","session_key":"local-9f2c…","last_active":1761400000.1,"started_at":1761400000.1,"message_count":14,"model":"claude-sonnet-4-5","preview":"","title":"Fix login flow","source":"desktop","status":"working"}]}}
```
**Events:** for attached sessions, `message.start` (a turn claimed), `message.complete`, and `session.info` with `payload.running`. The badge flips on these.

**Notes:** the Desktop polls `session.active_list` on reconnect and while a turn has been silent (about 45 s). The legacy fallback also lists `session.active_list`, but the authority serves it first. The finished-unread dot is client state plus the backend `unread` watermark.

#### Create new session

**UI:**
- New-chat button and composer first send: `apps/desktop/src/app/session/hooks/use-session-actions/index.ts` (`createBackendSessionForSend`, around lines 860–930).
- Params builder: `use-session-actions/session-create-request.ts::desktopSessionCreateParams`.
- Composer selection atoms: `$currentModel`, `$currentProvider`, `$currentReasoningEffort`, `$currentFastMode`, `$currentServiceTier`, `$currentCwdExplicit` in `apps/desktop/src/store/session.ts`.

**Calls (WS RPC canonical):** `session.create` (params `CanonicalCreateParams`, result `CanonicalSnapshot`). The contract refuses unknown keys.

| Param | Type | Req | Meaning |
|---|---|---|---|
| `request_id` | str ≤256 | opt (send it) | Idempotency key and part of the deterministic id. A retry returns the same session. Retrying with a different frozen request answers `invalid_params field=request_id`. |
| `source` | `cli\|tui\|gui\|acp\|tool\|oneshot` | opt, default `cli` | The GUI sends `"gui"` (stored as `local`, surface `desktop`). |
| `profile` | str | opt | Owner profile. The Desktop always sends it. |
| `cwd` | str | opt | Execution / workspace dir. |
| `cwd_explicit` | bool | opt | The user picked the folder. A named profile's own workspace beats an inherited cwd. |
| `model` / `provider` / `base_url` / `api_key` | str | opt | Launch route. The Desktop sends `model` and `provider` only for a manual pick. |
| `reasoning` | str | opt | `none\|minimal\|low\|medium\|high\|xhigh\|max\|ultra\|…` (`parse_reasoning_effort`). |
| `service_tier` | str | opt | `priority` (Fast), `normal`, `ultrafast`. |
| `toolsets` | str[] | opt | Toolset allowlist. |
| `title` | str | opt | **Title as identity.** If a session with this exact title exists and the caller may read it, that session is opened instead of creating one. Otherwise a new session is created with this title. |
| `hidden` | bool | opt | Born hidden from the sidebar (Bot Chat). A non-bool answers `invalid_params field=hidden`. |
| `follow_profile_config` | bool | opt | Re-read the profile's model, provider and toolsets on every turn. Implied for title `"Bot Chat"`. |
| `max_turns`, `yolo`, `safe_mode`, `ignore_user_config`, `ignore_rules`, `skills[]`, `checkpoints`, `accept_hooks`, `pass_session_id`, `editor{}`, `run_budget`, `verbose`, `row_cwd`, `client_env{}` | — | opt | CLI/TUI/ACP launch flags. The Desktop renderer refuses to send these (`prepareCreate` allowlist). |

`runtime.describe {}` → `{instance_id, profile_id, authority_epoch, capabilities:["durable-admission-v1","event-replay-v1","local-cli-create-v1","acp-editor-policy-v1","acp-session-mcp-v1","clarify-batch-v1"], session_create:{sources:[...], parameters:[...]}, skin}` lists the accepted create fields.

```json
{"jsonrpc":"2.0","id":3,"method":"session.create","params":{"request_id":"6f1d3c1e-6a3b-4f2e-9a57-2b8f6f0d1a11","source":"gui","profile":"default","cwd":"C:\\src\\app","cwd_explicit":true,"model":"claude-sonnet-4-5","provider":"anthropic","reasoning":"medium","service_tier":"priority"}}
```
The result is the same snapshot as `session.resume` (next section). The new session is already attached, so `prompt.submit` may follow at once.

**Events:** the subscription starts immediately. `session.info` arrives on the first admission. `sessions.changed` follows when the row lands in the DB.

**Notes:**
- Refusals:
  - `permission_denied`: no `session:create` capability.
  - `profile_mismatch`.
  - `runtime_draining`: the owner is restarting.
  - `admission_conflict`: an `api_key` differs on retry.
- The Desktop drops `cwd_explicit` / `service_tier` and retries when an older backend answers `invalid params for session.create: <field>:` (`session-create-request.ts::rejectedField`).
- The first message: `session.create`, then `prompt.submit {session_id, text, submission_id}` (prompts slice).
- A session with no turns stays a row with `message_count 0`. The sidebar hides it (`min_messages=1`).

#### Open / resume a session (snapshot)

**UI:**
- Sidebar row click → `use-session-actions/index.ts` (resume around lines 2000–2100).
- Params: `resume-owner.ts::primaryResumeParams`.
- Warm re-attach: `session.activate` at around line 1555.
- Snapshot projection: `canonical-protocol.ts::snapshotResult`.
- The Desktop runs a REST transcript prefetch (`getLatestSessionMessages`) **in parallel** with the resume and paints from REST first.

**Calls (WS RPC canonical):** `session.resume` (params `CanonicalResumeParams`, result `CanonicalSnapshot`).
- Params:
  - `session_id`: str. A `local-` plus 16-hex abbreviation resolves when unique.
  - `profile`: str, optional.
  - `title`: str, optional. Resolve by exact title; `not_found` if none.
  - `latest`: `cli|tui`, with `workspace`: str. Most recent session of that surface.
  - `source`: str, optional. The Desktop sends `"desktop"`.
  - `display_projection`: bool. Adds `display_messages` (the TUI projection).
  - `editor`, `api_key`: ACP/CLI only.
  - `defer_history`, `omit_messages`, `cols`: **accepted but ignored by the owner**. `messages` is always returned.
- Result `CanonicalSnapshot`:

| Key | Meaning |
|---|---|
| `session_id`, `stored_session_id` | Canonical id (after resolving a compression tip or lineage owner). Use this id afterwards. |
| `messages[]` | Display history in display order (shape below). A row compaction summarized away carries `archived:true` and is never a rewind or undo target. When the byte budget cut older archived rows, the first row has `archived_truncated:true`. Page the rest via REST. |
| `message_count` | `len(messages)`. |
| `running` | A turn is executing. |
| `authority_epoch` | Owner incarnation. |
| `replay_epoch`, `last_sequence` | Cursor for `session.events.since`. |
| `subscription_id` | Needed for `session.detach`. |
| `revision`, `execution_generation` | Mutation fences. Cache them. |
| `pending[]` | `CanonicalPendingAdmission`: `{admission_id, ref:{profile_id,session_id}, sequence, status:"queued"\|"started"\|"unknown"\|"terminal", outcome, authority_epoch, execution_generation, input_id, text, controls?:["steer","replace"], steering?}`. The Desktop mirrors it as `pending_submissions` (`text` becomes `user`). |
| `prompts[]` | `CanonicalPendingPrompt`: `{kind:"approval"\|"clarify", prompt_id, execution_generation, choices[], command?, description?, edit?, question?, multi_select?, timeout_s?}`. Re-show these cards. |
| `open_requests[]` | Unanswered server→client requests `{id, method, params}` (sudo, secret, setup, batch clarify). Re-deliver them to your request handlers. |
| `pending_connection` | The open `manage_connections` card, or null. |
| `todo_state` | `{todos[], revision}` or null. |
| `info` | `CanonicalSnapshotInfo` (display fields, below). Messaging sessions get only `{desktop_protocol}`. |

```json
{"jsonrpc":"2.0","id":5,"method":"session.resume","params":{"session_id":"local-9f2c…","source":"desktop","profile":"default"}}
{"jsonrpc":"2.0","id":5,"result":{"session_id":"local-9f2c…","stored_session_id":"local-9f2c…","message_count":3,"running":false,"authority_epoch":7,"replay_epoch":"4c1e…","last_sequence":118,"subscription_id":"b3f0…","revision":42,"execution_generation":9,
 "messages":[{"role":"user","content":"Fix the login button","row_id":1201,"timestamp":1761400001.2},
             {"role":"assistant","content":"","row_id":1202,"reasoning":"Look at LoginForm…","tool_calls":[{"id":"toolu_1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"src/Login.tsx\"}"}}],"finish_reason":"tool_calls"},
             {"role":"tool","content":"…file text…","tool_call_id":"toolu_1","tool_name":"read_file","row_id":1203}],
 "pending":[],"prompts":[],"open_requests":[],"pending_connection":null,"todo_state":null,
 "info":{"desktop_protocol":"hermes-gateway-v1","source":"desktop","model":"claude-sonnet-4-5","provider":"anthropic","lazy":true,"profile_id":"C:\\Users\\me\\AppData\\Local\\hermes","profile_name":"default","cwd":"C:\\src\\app","reasoning_effort":"medium","service_tier":"priority","fast":true,"title":"Fix login flow","started_at":1761400000.1,"last_active_at":1761401234.5,"yolo":false,"branch":"main","context_length":200000,"tool_count":41,"launch_request":{"cwd":"C:\\src\\app","model":"claude-sonnet-4-5","source":"gui"}}}}
```

**Message row (snapshot `messages[]`)**, from `hermes_state_messages.py::get_messages_as_conversation` and `gateway/session_local_recovery.py::local_history`. Keys starting with `_` are stripped before sending.
- `role`: `user|assistant|tool|system`. `content`: a string or a list of parts (`{type:"text"|"input_text"|"output_text",text}`, `{type:"image_url",image_url:{url:"data:..."}}`). User attachments persist as image parts or path references; prompt attachments are sent as `{path,mime}` (prompts slice).
- `row_id`: int. The durable `messages.id`, and the address for rewind, branch and reactions.
- `timestamp`, `display_kind`, `display_metadata`, `api_content`, `observed`, `message_id` (platform id).
  - `display_kind`: `hidden|steer|model_switch|failed_turn|auto_continue|async_delegation_complete|process_complete|…`.
  - `display_metadata`: may hold `{reactions:[{emoji,author:"user"|"agent",at,seen}]}`, `{model,provider}`, `{tool_result_metadata}`, …
- Assistant only: `tool_calls[]` (OpenAI shape: `{id,type:"function",function:{name,arguments(JSON str)}}`), `reasoning`, `reasoning_content`, `reasoning_details`, `codex_reasoning_items`, `codex_message_items`, `finish_reason`.
- Tool only: `tool_call_id`, `tool_name`.
- `archived`, `archived_truncated`: see the snapshot table above.

**Where the UI gets model, reasoning and cwd for display:**
- `snapshot.info` (`CanonicalSnapshotInfo` / `SessionInventoryFields`): `model`, `provider`, `reasoning_effort`, `reasoning_effort_wire`, `service_tier`, `fast`, `cwd`, `branch`, `project`, `title`, `yolo`, `api_mode`, `tool_count`, `context_length`, `enabled_toolsets`, `tools`, `skills`, `mcp_servers`, `version`, `credential_hint`, `config_warning`, `launch_request` (the frozen create request), `profile_name`.
- Kept live by `session.info` events. Consumer: `apps/desktop/src/app/session/hooks/use-message-stream/gateway-event/session-info.ts` (`setCurrentReasoningEffort`, cwd follow, …).
- Sidebar rows use the row's `model`, `cwd` and `git_branch`.
- Usage: `session.stats`.

**Events after attach:** all session-scoped frames for this session (prompts/streaming slice). Lifecycle ones:
```json
{"jsonrpc":"2.0","method":"event","params":{"authority_epoch":7,"execution_generation":10,"admission_id":"a-77…","type":"session.info","session_id":"local-9f2c…","payload":{"stored_session_id":"local-9f2c…","pending":[],"authority_epoch":7,"desktop_protocol":"hermes-gateway-v1","running":true,"execution_generation":10,"revision":43},"replay_epoch":"4c1e…","seq":119}}
```
That is the light `session.info` (`gateway/session_authority.py::_publish_pending`), sent on every admission change. It is the **only** place a turn's revision bump becomes visible. The full `session.info` (payload = `local_session_info`: `source, model, lazy, profile_id, profile_name, desktop_protocol, cwd, reasoning_effort, …, title, started_at, last_active_at, yolo, launch_request`) is sent after model, reasoning, workspace or edit_policy mutations and after auto-titling.

**Notes:**
- `session.activate` (Desktop warm re-attach) is the same `session.resume` call.
- An ended local row stays ended until the next admitted turn, so opening history never re-lights liveness.
- `not_found`: an unknown id, or a cold row the owner can't mount. The Desktop then probes other profiles via REST `getSession`.
- Opening a session as a viewer re-reads the profile's config for local routes, so model and reasoning can change at resume.

#### Transcript history & paging (REST)

**UI:** `apps/desktop/src/app/chat/transcript-backfill.ts` ("Show earlier"), `apps/desktop/src/store/transcript-tail.ts`, `api/sessions.ts` (`getLatestSessionMessages` limit 120, `getOlderSessionMessages`, `getAllSessionMessages` page 500 oldest-first). Timeline rail: `components/assistant-ui/thread/timeline-index.ts`, `app/chat/history-window.ts`.

**Calls (REST):**
1. `GET /api/sessions/{id}/messages`.
   - Query:
     - `profile`.
     - `limit`: 0..500. Omitted means the latest 500.
     - `offset`: default 0. With `order=latest` it counts back from the newest row.
     - `order`: `latest|oldest`. Omitted with a `limit` means `oldest`.
     - `include_compacted`: bool, default false. **Send `true`**, otherwise history stops at the compaction boundary.
     - `inline_images`: bool, default true. `false` turns data-URI images into `[image]`.
   - Includes compression-ancestor rows.
   - Result: `{session_id:<resolved tip>, profile, messages:[...], pagination:{limit, offset, order, returned}}`. Treat the page as a tail only if `pagination.order=="latest"`.
   - Row = the raw `messages` row (`id` is the row id, the same value as the snapshot's `row_id`) plus display projections:
     - `display_content`: the user-visible content for compaction summaries and steer rows.
     - `display_kind:"hidden"`: untyped `[System:` scaffold rows and empty compaction wrappers.
     - `display_commentary[]`, `display_reasoning`.
     - `tool_call_labels`: `{<call_id>:[label]}`.
     - `display_kind:"failed_turn"`: typed on read.
   - Older page: `offset = number_of_rows_already_loaded`, `order=latest`.
```json
GET /api/sessions/local-9f2c…/messages?limit=120&order=latest&include_compacted=true&profile=default
{"session_id":"local-9f2c…","profile":"default","pagination":{"limit":120,"offset":0,"order":"latest","returned":3},
 "messages":[{"id":1201,"session_id":"local-9f2c…","role":"user","content":"Fix the login button","timestamp":1761400001.2,"active":1,"compacted":0},
             {"id":1202,"role":"assistant","content":"","reasoning":"…","tool_calls":[{"id":"toolu_1","type":"function","function":{"name":"read_file","arguments":"{…}"}}],"tool_call_labels":{"toolu_1":[{"kind":"path","text":"src/Login.tsx"}]}},
             {"id":1203,"role":"tool","tool_call_id":"toolu_1","tool_name":"read_file","content":"…"}]}
```
   (The exact column set is `SELECT *` on `messages`; the example is abbreviated. `tool_call_labels` item shape (unverified).)
2. `GET /api/sessions/{id}/timeline?profile=&limit=1..500&after_row_id=0` → `{session_id, profile, entries:[{row_id, preview, timestamp}], pagination:{limit, after_row_id, returned, total, has_more, next_cursor}}`. Lists user prompts only, compacted ones included. Page with `after_row_id=next_cursor`.
3. `GET /api/sessions/{id}/messages/around?row_id=<int>&profile=&limit=1..120` → `{session_id, profile, messages, pagination:{row_id, limit, returned, order:"oldest", offset, total, has_older, has_newer}}`. 404 `"Prompt not found"`.
4. WS alternative (owner-served): `session.history {session_id, profile?}` → `{count, messages}` (the TUI display projection). The Desktop uses it only to resolve a durable `row_id` by text before a rewind (`use-prompt-actions/rewind.ts`).

**Notes:**
- 404 = unknown id on that profile. Probe other profiles or connections.
- `getAllSessionMessages` stops at about 32 MB of JSON.
- REST reads are side-effect free and don't need an attach.

#### Rename

**UI:** `app/chat/sidebar/session-actions-menu.tsx` (`renameSessionPreferringRpc`, rename dialog). The `/title` slash command is in `use-prompt-actions/slash.ts`. Bot plugin: `plugins/hermes-bots/canonical-chat.ts`.

**Calls:**
- Session open on this socket → **WS RPC canonical** `session.mutate`:
```json
{"jsonrpc":"2.0","id":9,"method":"session.mutate","params":{"session_id":"local-9f2c…","request_id":"0b7e…","expected_revision":42,"operation":"rename","payload":{"title":"Login fix"}}}
{"jsonrpc":"2.0","id":9,"result":{"session_id":"local-9f2c…","revision":43,"execution_generation":9,"operation":"rename","title":"Login fix"}}
```
- Not open (or on RPC failure) → **REST** `PATCH /api/sessions/{id}` with fences from `GET /api/sessions/{id}/mutation-snapshot?profile=` (below, "REST fenced writes").

**Events:** `session.updated` (payload = the receipt) to every viewer of the session. Then `sessions.changed`.
```json
{"jsonrpc":"2.0","method":"event","params":{"type":"session.updated","session_id":"local-9f2c…","payload":{"session_id":"local-9f2c…","revision":43,"execution_generation":9,"operation":"rename","title":"Login fix"},"replay_epoch":"4c1e…","seq":120}}
```

**Notes:**
- Title must be a str. The stored title is normalized and returned in `title`.
- A title another session already holds raises a `ValueError` (REST 400 with message; WS error code unverified).
- Renaming the hidden canonical "Bot Chat" is refused ("This is the bot's canonical Bot Chat…").
- Rename/archive/pin of a compressed or reset segment is authorized on the lineage owner.

#### REST fenced writes (pin, unpin, archive, unarchive, mark read/unread, hidden, rename)

**UI:**
- Menu: `session-actions-menu.tsx`.
- Archive / unarchive: `use-session-actions/index.ts`.
- Pins: `store/session-pin-sync.ts`. Unread: `store/session-unread-remote.ts`.
- Settings → Sessions: `app/settings/sessions-settings.tsx`.
- Shared client: `apps/shared/src/session-http-mutations.ts` and `api/sessions.ts::mutateSessionHttp`.

**Calls (REST):**
1. `GET /api/sessions/{id}/mutation-snapshot?profile=` → `{session_id, exists:bool, runtime_revision:int, runtime_generation:int|null}`. An absent session reads as revision 0.
2. `PATCH /api/sessions/{id}`, JSON body `SessionRename`:
   - `request_id`: str. Required.
   - `expected_revision`: int ≥0. Required.
   - `expected_generation`: int ≥0. Optional; the Desktop sends it when `exists`.
   - `profile`: str. Required in multi-profile hosting.
   - Any of: `title`: str, `archived`: bool, `pinned`: bool, `hidden`: bool, `unread`: bool. `unread:true` clears `last_read_at`; `false` sets it to now.
   - Runs `operation:"sidebar"`.
   - Result: `{"ok":true, session_id, revision, execution_generation, operation:"sidebar", <the fields set>}`.
   - Archive sends `{archived:true, pinned:false}` (archive implies unpin). Unarchive sends `{archived:false}`.
```json
GET  /api/sessions/local-9f2c…/mutation-snapshot?profile=default
→ {"session_id":"local-9f2c…","exists":true,"runtime_revision":43,"runtime_generation":9}
PATCH /api/sessions/local-9f2c…?profile=default
{"pinned":true,"request_id":"c2a1…","expected_revision":43,"expected_generation":9,"profile":"default"}
→ {"ok":true,"session_id":"local-9f2c…","revision":44,"execution_generation":9,"operation":"sidebar","pinned":true}
```
- WS equivalent for an open session: `session.mutate operation:"archive" payload:{archived:bool}` or `operation:"sidebar" payload:{title?,archived?,hidden?,pinned?,unread?}`.

**Events:** `session.updated` to the session's viewers (when the owner holds it live), plus `sessions.changed`.

**Notes:**
- Retry an ambiguous failure with the **same** identity.
- Drop the identity on a definitive refusal (`revision_conflict|stale_generation|permission_denied|profile_mismatch|invalid_params|admission_conflict|session_busy|retained_receipts|runtime_coordination_required|unknown_execution|not_found|mutation_identity_required`). Re-read the snapshot only on a new user action.
- A 404 on the snapshot route itself (older backend) means: send the write without an identity.
- Archived rows move to the `archived=only` view. Pinned rows always come back in list pages.

#### Close / detach

**UI:** tab close and session switch (`use-session-actions/index.ts`). The renderer sends legacy `session.close` best-effort and ignores the answer; the canonical owner answers `-32601`.

**Calls (WS RPC canonical):** `session.detach`.
- Params: `{session_id, subscription_id (from the snapshot), end_reason?}`. `end_reason` is snake_case `[a-z][a-z_]{0,31}`, for example `cli_close`, `new_session`, `resumed_other`, `branched`.
- Result: `{session_id, subscription_id, detached:bool}`. A stale subscription id answers `detached:false`.
```json
{"jsonrpc":"2.0","id":21,"method":"session.detach","params":{"session_id":"local-9f2c…","subscription_id":"b3f0…","end_reason":"cli_close"}}
{"jsonrpc":"2.0","id":21,"result":{"session_id":"local-9f2c…","subscription_id":"b3f0…","detached":true}}
```
**Notes:**
- Without `end_reason`, the viewer leaves and the session keeps running in the background. With it, the viewer is closing the conversation: when the last viewer leaves an idle local session, the owner ends the row (memory and plugin finalize). If a turn is still queued or running, the close is deferred until the FIFO goes idle.
- When the socket drops, all subscriptions detach as orphaned, and an orphan-close grace timer is armed for sessions detached without a reason.
- Re-open with `session.resume`.

#### Delete

**UI:** `session-actions-menu.tsx` (delete ConfirmDialog), `use-session-actions/index.ts` (around line 3150), `app/settings/sessions-settings.tsx`, `app/chat/session-tile.tsx`.

**Calls:**
1. If the session is running: **WS canonical** `session.interrupt {session_id, execution_generation}` (prompts slice). Then the renderer's legacy `session.close` (a no-op on canonical).
2. **REST** `DELETE /api/sessions/{id}?profile=&request_id=&expected_revision=&expected_generation=`, with the identity from `mutation-snapshot`. `expected_generation` is **required** for delete.
   - Result: `{"ok":true, session_id, revision, execution_generation, operation:"delete", deleted_ids:[...]}`. `deleted_ids` covers the whole compression chain and delegate subtree.
   - An id that is already absent returns `{"ok":true,"already_absent":true}`.
   - On-disk `session_<id>.json` and request dumps are scrubbed.
   - WS equivalent: `session.mutate {operation:"delete", payload:{}, expected_generation}`.
```json
DELETE /api/sessions/local-9f2c…?profile=default&request_id=51d0…&expected_revision=44&expected_generation=9
→ {"ok":true,"session_id":"local-9f2c…","revision":45,"execution_generation":9,"operation":"delete","deleted_ids":["local-9f2c…"]}
```
**Events:** `sessions.changed`. A `session.updated` with `operation:"delete"` goes to live viewers; the owner then retires the live session (unverified ordering).

**Notes:**
- `session_busy` (409): a turn is running or input is queued.
- `session_has_children` (409, `field:"session_id"`): deleting a cold history mount that has branch children.
- `runtime_coordination_required`: no routing store.
- **Bulk (dashboard routes, not used by the Desktop):**
  - `POST /api/sessions/bulk-delete {ids:[≤500], profile}` → `{ok, deleted, skipped_active:[...]}`.
  - `GET /api/sessions/empty/count?profile=` → `{count}`.
  - `DELETE /api/sessions/empty?profile=` → `{ok, deleted}`.
  - These open the DB under the exclusive maintenance lock and answer 409 (detail = the ownership conflict) while a gateway owns that profile (unverified in the webapp's composed process).

#### Search (FTS)

**UI:** `app/chat/sidebar/use-server-session-search.ts`, `lib/session-search.ts`, `app/chat/sidebar/search-result-to-session.test.ts`, `strip-fts-markers`. Wrapper: `api/sessions.ts::searchSessions`.

**Calls (REST):** `GET /api/sessions/search`.
- Query: `q` (required; empty returns `{results:[]}`), `limit` 1..100 (default 20), `profile`, `source`, `sources` (CSV), `exclude_sources` (CSV).
- Order:
  1. Id match first.
  2. Then FTS5 over message content. Each token gets a `*` prefix unless it is quoted.
  3. Then title matches.
- Results are deduped per compression lineage (branches stay separate). Archived rows are included.
- Result:
```json
{"results":[{"snippet":"…fix the >>>login<<< button…","role":"user","source":"desktop","model":"claude-sonnet-4-5","session_started":1761400000.1,"last_active":1761401234.5,
  "session_id":"local-9f2c…","lineage_root":"local-9f2c…","id":"local-9f2c…","title":"Login fix","started_at":1761400000.1,"ended_at":null,"is_active":false,"message_count":14,"tool_call_count":6,"input_tokens":52012,"output_tokens":3021,"preview":"…","parent_session_id":null,"profile":"default","is_default_profile":true,"archived":false}]}
```
**Notes:**
- `session_id` is the live compression tip; resume by it. `lineage_root` is the durable pin id.
- The snippet carries FTS highlight markers. Strip or render them; the marker format follows FTS5 `snippet()` (unverified).
- 500 `{"detail":"Search failed"}`.
- Search one profile at a time; the Desktop passes the profile it is showing.

#### Fork / branch (whole session and from a message)

**UI:** session menu "Branch from here" (`session-actions-menu.tsx` → `onBranch`), the message action (GitFork in `components/assistant-ui/thread/assistant-message.tsx`), `/branch [title]`. Flow: `use-session-actions/index.ts` (around lines 2650–2800), `branch-boundary.ts`, `branch-create-key.ts`.

**Calls (WS RPC canonical):** `session.mutate` on the **parent**. The parent must be attached; the Desktop auto-resumes it.
- `payload`: `{through_message_id?: int>0, title?: str, mcp_servers?}`. `through_message_id` is the `row_id` of the **last row the branch keeps**. Omit it for the whole active transcript.
- `expected_generation`: required.
- Needs `session:create`.
- Result: `{session_id:<parent>, revision, execution_generation, operation:"branch", branched_session_id, copied_messages}`.
```json
{"jsonrpc":"2.0","id":30,"method":"session.mutate","params":{"session_id":"local-9f2c…","request_id":"8e0a…","expected_revision":44,"expected_generation":9,"operation":"branch","payload":{"through_message_id":1202}}}
{"jsonrpc":"2.0","id":30,"result":{"session_id":"local-9f2c…","revision":45,"execution_generation":9,"operation":"branch","branched_session_id":"local-1aa7…","copied_messages":2}}
```
The owner attaches the child on this socket. The client then opens it with `session.resume {session_id: branched_session_id}` for its snapshot; the Desktop navigates to it as a new tab. In the sidebar the child appears under `_branched_from = parent`. With no title, the branch is auto-named from the parent.

**Events:** `session.updated` (the branch receipt) on the parent. `sessions.changed`.

**Notes:**
- `session_busy`: the branch requires an idle parent, with no queued input either.
- A message without a durable `row_id` (not settled yet) can't be a boundary. The Desktop refuses with "This message is not saved yet…".
- Keep the same `request_id` on retry, otherwise a second child is created.

#### Rewind / undo / retry / edit-and-resend / regenerate

**UI:**
- Edit, regenerate and restore message actions: `use-prompt-actions/rewind.ts::runRewindSubmit` and `truncateSubmitParams`, split on the wire by `canonical-protocol.ts::splitRewindSubmit`.
- `/undo [N]` and `/retry`: `use-prompt-actions/slash-session-commands.ts`.

**Calls (WS RPC canonical):**
1. `session.mutate` with `operation:"rewind"`, `payload:{target_message_id:int>0, preserve_compaction_handoff?:bool}`, and `expected_generation` (required).
   - The target must be an **active `role:"user"` row** of the session. The rewind removes that user row and everything after it, and bumps `execution_generation` by 1.
   - Result: `{session_id, revision, execution_generation, operation:"rewind", rewound_count, target_message:{id, role:"user", content, ...}, new_head_id, replacement_message_id}`.
2. Then, for anything but `/undo`: `prompt.submit {session_id, text, submission_id}`. That is a normal new admission (prompts slice).

| Feature | rewind target | then submit |
|---|---|---|
| Edit & resend | The edited user bubble's `row_id` | The edited text |
| Regenerate | The user row that precedes the assistant reply | The same user text |
| Restore older turn | That user row | Its text |
| `/retry` | The last user turn (from a fresh `session.resume` snapshot, newest non-archived `role:user`) | `target_message.content` |
| `/undo N` | The Nth-from-last user turn | None. Put `target_message.content` back into the composer. |

```json
{"jsonrpc":"2.0","id":40,"method":"session.mutate","params":{"session_id":"local-9f2c…","request_id":"aa12…","expected_revision":45,"expected_generation":9,"operation":"rewind","payload":{"target_message_id":1201}}}
{"jsonrpc":"2.0","id":40,"result":{"session_id":"local-9f2c…","revision":46,"execution_generation":10,"operation":"rewind","rewound_count":3,"target_message":{"id":1201,"role":"user","content":"Fix the login button"},"new_head_id":1200,"replacement_message_id":null}}
{"jsonrpc":"2.0","id":41,"method":"prompt.submit","params":{"session_id":"local-9f2c…","text":"Fix the login button on mobile","submission_id":"e3c9…"}}
```
**Events:** `session.updated` (the rewind receipt). The cached agent is evicted. Then the new turn's `message.start` / `session.info` / `message.complete`.

**Notes:**
- `invalid_params field=target_message_id` refusals:
  - `detail` starts with `compacted:`: the target was summarized by compression. Show "can't edit compacted message".
  - `detail:"target user message is no longer in session history"`: the row was already rewound, is an assistant row, or is unknown.
- `session_busy`: a turn is running. The Desktop interrupts first (`session.interrupt`), then retries.
- Keep the rewind's `request_id` for a lost-reply retry. Forget it on any typed refusal.
- Never address a rewind by ordinal or text, only by `row_id`.

#### Compress

**UI:** the composer `/compress [focus]`, the context menu or button (`use-prompt-actions/slash.ts` around line 855, `session.compress`), and the context meter.

**Calls (WS RPC canonical):** `session.mutate` with `operation:"compress"` and `expected_generation` (required).
- `payload`: `{focus?: str, preview?: bool, partial?: bool, keep_last?: int≥1 (only with partial:true)}`.
- Results:
  - Commit: `{session_id, revision, execution_generation, operation:"compress", target_session_id, previous_target_session_id, message_count, summary}`.
  - `preview:true`: `{session_id, operation, status:"preview", lines:[...], head_count, tail_count, message_count}`, no `revision`, nothing written.
  - Nothing to remove: `{session_id, operation, status:"noop", summary}`.
- After a commit, call `session.resume` again to get the compacted `messages`.
```json
{"jsonrpc":"2.0","id":50,"method":"session.mutate","params":{"session_id":"local-9f2c…","request_id":"f00d…","expected_revision":46,"expected_generation":10,"operation":"compress","payload":{"focus":"auth changes"}}}
```
**Events:** `session.updated` (the receipt). The `session.stats` compression count goes up.

**Notes:**
- The summarization is an LLM call that runs outside the session lock. Turns can't claim meanwhile.
- Refusals:
  - `session_busy` (`field:"compression"` = already compressing).
  - `compression_checkpoint_required`.
  - `runtime_coordination_required`: Codex app-server mode.
- After compression the canonical `session_id` stays the same; the physical transcript id rotates (`target_session_id`).

#### Other `session.mutate` operations (reference)

`reset` (`payload:{}`, `/new` in place, generation required), `model` (`{model, provider?, reasoning?, confirm?}` → may answer `status:"confirmation_required", confirm` or `status:"deferred"` while busy; models slice), `reasoning` (`{effort: str|null}`), `workspace` (`{cwd}`, Move to project / folder picker; `session_busy` mid-turn), `edit_policy` (ACP), `editor_mcp` (ACP), `import` (below). A deferred model pick resolves later as `session.updated {operation:"model", status:"confirmation_required"|"refused", …}`.

#### Export

**UI:** session menu "Export" → `apps/desktop/src/lib/session-export.ts`. It builds the JSON on the client from REST `getAllSessionMessages`. `/save` → `use-prompt-actions/slash-desktop-commands.ts`.

**Calls:**
- **REST** (client-built, menu export): page `GET /api/sessions/{id}/messages?order=oldest&limit=500&offset=N&include_compacted=true` until a short page. Then save `{exported_at, session_id, title, session, message_count, messages}` as `<title>-<id8>.json`.
- **REST** (server stream, dashboard): `GET /api/sessions/{id}/export?profile=` → `application/json` stream of `{...session row, "messages":[every row incl. inactive/compacted]}`. 404 if missing. This is the format `POST /api/sessions/import` accepts.
- **WS RPC canonical** (`/save`): `session.export {session_id, profile?, format?: "json"|"md"|"markdown"|"html"|"snapshot", redact?: bool}` → `{format, filename, content}`. The gateway writes nothing; the client saves `content`. On an older owner, fall back to `session.save` → `{file}`.

#### Import (dashboard export, foreign Claude Code / Codex sessions)

**UI:** `apps/desktop/src/app/session-import/index.tsx` and `api.ts` (foreign import browser).

**Calls:**
- **WS RPC legacy** (these fall through to `tui_gateway/methods_session_foreign.py` and are explicitly allowed by `ws_legacy_fallback`):
  - `session.foreign.list {source?: "claude"|"codex", offset?, limit?, profile?}` → `{sessions:[ForeignSessionRow{id (opaque handle), source, label, title, cwd, mtime, turn_count, excerpt}], next_offset|null, host, unreadable}`.
  - `session.foreign.preview {id, profile?}` → `{messages:[{role, content}] (last 40 turns, ≤8000 chars each), total, truncated, already_imported: <local id>|null, cwd}`.
  - `session.foreign.import {id, profile?}` → `{session_id, already_imported}`. Idempotent per origin. Then open `session_id` with `session.resume`. The Desktop uses a 60 s timeout.
```json
{"jsonrpc":"2.0","id":60,"method":"session.foreign.list","params":{"source":"claude","limit":50,"profile":"default"}}
{"jsonrpc":"2.0","id":60,"result":{"sessions":[{"id":"fs_3b…","source":"claude","label":"Claude Code","title":"Refactor api","cwd":"C:\\src\\api","mtime":1761300000,"turn_count":22,"excerpt":"…"}],"next_offset":50,"host":"MY-PC","unreadable":0}}
```
- **REST** (dashboard JSON): `POST /api/sessions/import`. The body (≤ the size cap, else 413) is `SessionImport`: `{sessions:[{...row, messages:[...]}], request_id, expected_revision (0 for a new anchor), profile?}`. It runs the canonical `import` mutation, anchored on `sessions[0].id`.
  - Result: `{ok, session_id, revision, operation:"import", imported, skipped, imported_ids, skipped_ids, detached, errors:[]}`.
  - Validation errors: 400 `{"detail":{"errors":[...]}}`.
  - A previously deleted id: 409 `admission_conflict` (`field:"sessions"`).
- `session.most_recent {profile?}` (legacy fallback) → `{session_id|null, title, started_at, source}`.

#### Session info, stats and usage

**UI:** status bar / context meter (`app/session/...`, `use-message-stream/gateway-event/session-info.ts` for `session.usage`), the `/status` and `/usage` slash commands.

**Calls (WS RPC canonical):**
- `session.info {session_id}`. **Only** `session_id`; `profile` is stripped for the owner's home, and any other key answers `invalid_params`.
  - Result `CanonicalSessionInfo`: `source, model, lazy, profile_id, profile_name, desktop_protocol, cwd, reasoning_effort, reasoning_effort_wire, service_tier, fast, title, started_at, last_active_at, yolo, api_mode, tool_count, credential_hint, config_warning, launch_request`, plus inventory `provider, tools, skills, mcp_servers, version, release_date, update_behind, update_command, branch, project, system_prompt, context_length, enabled_toolsets, tool_availability`.
- `session.stats {session_id, profile?}` → `SessionStats`: `input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, prompt_tokens, completion_tokens, total_tokens, reasoning_tokens, api_calls, last_prompt_tokens, context_tokens, context_length, config_context_length, context_source, compression_count, avg_latency_s, tokens_per_s, last_turn_tokens, rate_limit, active_subagents, model, provider, fallback_model, plan_gauge`.
- `session.events.stats {profile?}` (legacy fallback, ops) → `{sessions, events, bytes, max_per_session, max_bytes_per_session, max_bytes_process}`.
- **REST** `GET /api/sessions/stats?profile=` → `{total, active_store, archived, messages, by_source:{<source>:n}}`.

**Events:** `session.usage` mid-turn ticks (payload has cumulative counters at the top level), `message.complete` (authoritative usage), `session.info` (settings).

#### Event replay after reconnect (`session.events.since`)

**UI:** `api/client.ts` (`routeCanonicalParams`; the shared client's reconnect cursor), `components/assistant-ui/clarify/use-undelivered.ts`, `gateway-event/lifecycle.ts::handleReplayGapEvent`.

**Calls (WS RPC canonical):** `session.events.since`.
- Params: `{session_id, last_sequence?: int≥0 (alias last_seen), replay_epoch?: str, profile?}`.
- Result `CanonicalEventsResult`: `{events:[CanonicalReplayEvent{type, session_id, payload, replay_epoch, seq, authority_epoch?, execution_generation?, admission_id?}], latest_seq, last_sequence, epoch, replay_epoch, truncated, snapshot_required, count, open_requests}`.
- `session:read` is required. The session must be readable; it doesn't need to be attached first (unverified: the replay ring is per live session).
```json
{"jsonrpc":"2.0","id":70,"method":"session.events.since","params":{"session_id":"local-9f2c…","last_sequence":118,"replay_epoch":"4c1e…"}}
{"jsonrpc":"2.0","id":70,"result":{"events":[{"type":"session.info","session_id":"local-9f2c…","payload":{"running":true,"revision":43,"execution_generation":10,"pending":[]},"replay_epoch":"4c1e…","seq":119,"authority_epoch":7,"execution_generation":10}],"latest_seq":119,"last_sequence":119,"epoch":"4c1e…","replay_epoch":"4c1e…","truncated":false,"snapshot_required":false,"count":1,"open_requests":[]}}
```
**Notes:**
- Every session frame carries `seq` (monotonic per session) and `replay_epoch`. Track the last `seq` per session.
- `snapshot_required:true` means one of: the epoch changed (owner restart or ring eviction), the cursor is ahead, or the ring is truncated. The client must call `session.resume` again (authoritative snapshot).
- A live subscriber whose outbound backlog overflowed gets a `session.replay_gap` event and is unsubscribed. Re-resume. Frame:
  `{"type":"session.replay_gap","session_id":"local-9f2c…","payload":{"replay_epoch":"4c1e…","latest_seq":240}}`.
- `gateway.ready.payload.replay_epoch` is the process-level epoch. Compare it on reconnect to detect a backend restart.
- After a reconnect, `attached` is empty: every session must be resumed on the new socket before submitting.

#### `session.updated` / `session.info` / `sessions.changed` / `session.title` / `session.reclaimed` (event summary)

| type | Scope | Payload | Client action |
|---|---|---|---|
| `session.updated` | Session viewers | The mutation receipt (`session_id, revision, execution_generation, operation, …projection`) or a deferred-pick outcome | Adopt `revision` if higher and repaint title, archived, etc. A branch, rewind or compress receipt means: re-resume or refresh |
| `session.info` (light) | Session viewers | `{stored_session_id, pending[], authority_epoch, desktop_protocol, running, execution_generation, revision}` | Update the running badge, queue, fences |
| `session.info` (full) | Session viewers | The `local_session_info` projection (`model, cwd, reasoning_effort, title, …`) | Repaint the header, model pill, cwd, title |
| `sessions.changed` | Global (`session_id:""`) | `{}` | Refetch the sidebar REST list (debounced) |
| `session.title` | Legacy sidecar only | `{session_id, title}` | The canonical owner sends the title via full `session.info` instead |
| `session.reclaimed` | Legacy broadcast | `{session_id, stored_session_id, reason: idle_timeout\|lru_evict\|ws_orphan_reap}` | Treat the runtime as gone and re-resume on demand |
| `session.replay_gap` | Session viewer | `{replay_epoch, latest_seq}` | Re-resume authoritatively |

#### Prune (old sessions)

**UI:** dashboard / settings maintenance (not in the Desktop sidebar). CLI equivalent: `hermes sessions prune`.

**Calls:**
- **REST** `POST /api/sessions/prune`, body `SessionPrune`:
  - Window: `older_than_days` (default 90, ≥1 unless a window is given), `started_before`, `started_after` (epoch s).
  - String filters: `source`, `title_like`, `end_reason`, `cwd_prefix`, `model_like`, `provider`, `user_id`, `chat_id`, `chat_type`, `branch_like`.
  - Numeric filters: `min/max_messages`, `min/max_tokens`, `min/max_cost`, `min/max_tool_calls`.
  - Flags: `include_archived` (default false), `dry_run` (default false).
  - `profile`: required when the host serves several profiles and `dry_run` is false.
  - Dry run → `{ok, removed:0, matched, skipped_open, oldest_last_active, newest_last_active, oldest_started_at, newest_started_at, sessions:[{id, source, title, model, started_at, last_active, message_count}]}`.
  - Real run → `{ok, removed, skipped_open}`. It takes the maintenance lock: 409 while an owner holds it (unverified for the composed web process).
- **WS RPC canonical** `session.prune {filters?: {...} | never_active_days?: float}` (exactly one) → `{deleted, routing_deleted?, skipped?}`. **Local operator only** (`native_owner` plus `session:operator`). Other clients get `permission_denied`.

#### Session automation (/goal, /loop, /heartbeat)

**UI:** the classic CLI and TUI slash commands. The Desktop shows the persisted state via the `session.control.update` event and `session.control.read` (another slice).

**Calls (WS RPC canonical):** `session.automation`.
- Params: `{session_id, kind:"goal"|"loop"|"heartbeat", call:str, args?:[], kwargs?:{}, profile?}`.
- `call` must be one of:
  - goal: `state, set, pause, resume, clear, mark_done, add_subgoal, remove_subgoal, clear_subgoals, add_gate, remove_gate, clear_gates, wait_on, wait_on_session, wait_for_seconds, stop_waiting, is_waiting, evaluate_after_turn, command`.
  - loop: `state, set, pause, resume, clear, fire_tick, abandon_tick, complete_tick, goal_blocks_tick`.
  - heartbeat: `state, set, pause, resume, clear, due_prompt, abandon_fire`.
- Result: `{state: <manager JSON string>|null, value?, error?: {type, message}}`.

**Events:** `session.control.update {control: snapshot}`.

#### Reactions on messages (row-addressed)

**Calls (WS RPC canonical):** `message.react`.
- Params: `{session_id, profile?, row_id?: int, newest_role?: "user"|"assistant" (when row_id is omitted), emoji?: str (null/omitted = remove), author?: "user"|"agent"}`.
- Result: `{row_id, reactions:[{emoji, author, at, seen?}]}`. One reaction per author.
- `session:submit` is required. The session must be live on the owner, else `not_found`.
- Persisted reactions come back in the message row's `display_metadata.reactions`. The agent's reactions arrive as the `message.reaction` event.

#### Canonical session list (TUI picker; not used by the Desktop sidebar)

**Calls (WS RPC canonical):** `session.list`.
- Params: `{scope?: "live"|"stored" (default "live"), limit?: 1..200 (default 200), title?: str (not with scope), include_hidden?: bool, profile?}`.
- Result: `{sessions:[CanonicalSessionListRow{session_id, id, title, source, started_at, message_count, running, model?, resolved_id?, root_title?}], scope}`.
- `live` = sessions this owner holds (internal sources, closed, hidden and archived are excluded unless `include_hidden`). `stored` = legacy local rows resumable on this native transport. `title` = an exact-title lookup (returns `scope:"stored"`).

#### Bot Chat `canonical_session` rule (brief; bots slice has details)

Source: `apps/desktop/src/AGENTS.md` "Bot Mode" and `apps/desktop/src/plugins/hermes-bots/canonical-chat.ts`.
- A bot's chat is identified only by (bot profile, session titled exactly `"Bot Chat"`). Never store a session-id pointer, and never pick by recency.
- Opening it:
  1. Look it up: `session.list {title:"Bot Chat", include_hidden:true, profile:<bot>}`. The answer resolves compression lineages to the live tip.
  2. Row found → `session.resume`.
  3. No row → `session.create {title:"Bot Chat", hidden:true, profile:<bot>, request_id, source:"gui"}`. The owner adopts an existing titled row before minting and sets `follow_profile_config:true`. Then the intro `prompt.submit`.
- `profiles.list` returns each profile's `canonical_session` (resolved server-side by title). Use it for roster preview and activity.
- Canonical Bot Chats are always `hidden`, so they never appear in the sidebar, and they can't be renamed. Side chats with a bot are ordinary visible sessions.

### 2.2 Chat turns

One turn, end to end: submit, admission, the streamed event sequence, blocking prompts (approval, clarify, sudo, secret, vault), mid-turn control (stop, steer, redirect, queued rows), subagents, side agents, attachments, reactions, voice. Every claim cites a path in `%LOCALAPPDATA%\hermes\hermes-agent` (main @ 9b904dcc22). Transport tags are those of section 1 (**WS canonical**, **WS legacy**, **REST**, **sibling WS**, **server request**). Type names refer to `contract/gateway-contract.generated.ts`.

**Read first: the canonical wire is not the Desktop renderer's wire.** The renderer still speaks a legacy-shaped dialect and `apps/desktop/src/api/canonical-protocol.ts` (`CanonicalDesktopProtocol.prepare`) rewrites it on the way out. A native client must send the canonical form directly. Each section below gives the canonical form; legacy shapes are noted only where a canonical gateway still serves them.

#### Wire conventions for a turn

**Frames** (all text frames on `/api/ws`, JSON-RPC 2.0):

| Direction | Shape |
|---|---|
| client → server request | `{"jsonrpc":"2.0","id":<int>,"method":"prompt.submit","params":{…}}` |
| server → client result | `{"jsonrpc":"2.0","id":<int>,"result":{…}}` |
| server → client refusal | `{"jsonrpc":"2.0","id":<int>,"error":{"code":4001,"message":"<reason>","data":{"reason":"<reason>","field"?:…,"detail"?:…,"revision"?:<int>,"execution_generation"?:<int>}}}` (`gateway/session_controls.py::_refusal_data`) |
| server → client event | `{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"…","payload":{…},"seq":<int>,"replay_epoch":"<hex>","authority_epoch"?:<int>,"execution_generation"?:<int>,"admission_id"?:"<hex>"}}` (`gateway/session_events.py::SessionEvents.publish`) |
| server → client request | `{"jsonrpc":"2.0","id":"srq-<12 hex>","method":"sudo","params":{"session_id":"…",…}}` (`tui_gateway/server_requests.py`, `gateway/session_server_requests.py`) |
| client → server answer | `{"jsonrpc":"2.0","id":"srq-…","result":{…}}` or `{"jsonrpc":"2.0","id":"srq-…","error":{"code":-32603,"message":"…"}}` (an error = "unanswered"; no reply frame comes back) |

**Error codes**: `4001` every `RuntimeStoreError` refusal (reason in `message` and `data.reason`; a connector refusal may carry its own `wire_code`), `-32601` unknown method (`data.reason:"unknown_method"`), `5001 storage_unavailable` (SQLite failure, retryable). Unknown or missing param key on a canonical method: `4001 invalid_params` with `data.fields` (`session_controls.py::dispatch`, `registry.canonical_param_problems`). Common reasons on turn verbs: `invalid_params`, `permission_denied`, `not_found`, `profile_mismatch`, `runtime_draining`, `stale_generation`, `queue_full`, `admission_conflict`, `unknown_execution`, `session_busy`, `storage_unavailable`.

**Execution stamp.** While a turn's claim is live, every frame the session publishes carries `authority_epoch`, `execution_generation`, `admission_id` beside `type` (`gateway/session_authority.py::_stamp_claim` sets `event_stream.execution`; cleared in the drain's `finally`). The stamp is the fence:

- Track per session the highest `execution_generation` seen (from event params, `payload.execution_generation` of `session.info`, the snapshot of `session.create`/`session.resume`). Drop any stamped frame whose generation is lower (`canonical-protocol.ts::event`, renderer `admitExecutionEvent` in `app/session/hooks/use-message-stream/gateway-event/index.ts`).
- `session.interrupt`, `session.steer`, `session.redirect`, `approval.respond`, `clarify.respond` must name that generation; a mismatch is `stale_generation`.
- `seq` is per-session monotonic (`tui_gateway/event_replay.py::_stamp_event`); keep `(replay_epoch, last seq)` for `session.events.since` on reconnect (section 1). A `session.replay_gap {replay_epoch, latest_seq}` event (subscriber backlog overflow, `session_events.py`) means re-`session.resume` for a fresh snapshot.

**Attach first.** `prompt.submit`, `approval.respond`, `clarify.respond`, `session.interrupt`, `prompt.cancel`, `prompt.steer_queued`, `prompt.replace_queued`, `image.attach_bytes`, `file.attach` require the session to be attached on THIS socket (`session.create` or `session.resume`), else `permission_denied` (`session_controls.py::submission`, `session_authority.respond`, `session_images.attach_bytes`; the renderer's `ATTACH_REQUIRED` set in `apps/desktop/src/api/client.ts`). After a reconnect, resume before sending.

**Profile.** Every turn verb accepts `"profile": "<name>"`. On a host-scoped socket it routes the call to that profile's authority; on a profile-bound socket a different profile is `profile_mismatch`; naming the socket's own profile is stripped (`session_controls.py::_sibling_for`, `_PROFILE_IMPLICIT`).

**Which verbs the owner serves.** `session.*`, `prompt.*`, `message.*` are served only by the authority and never fall back to the legacy sidecar (except `session.most_recent`, `session.active_list`, `session.events.stats`, `session.foreign.*`) (`tui_gateway/ws_legacy_fallback.py`). Other methods the authority answers `-32601` for fall back to `tui_gateway/server.py`; a legacy handler that looks the session up in the sidecar's own table answers "session not found" for an owner session. Owner-served turn-adjacent verbs: `approval.pending`, `approval.received`, `subagent.list|tail|steer|interrupt`, `file.attach`, `image.attach_bytes`, `process.stop|kill` (`gateway/session_ancillary.py`), `prompt.background`, `prompt.btw`, `preview.restart` (`gateway/session_tui_side.py`), `message.react` (`gateway/session_reactions.py`), `clarify.lock` (`gateway/session_clarify_lock.py`), `config.get/set` for `busy|verbose|show_reasoning|service_tier|yolo|…` (`gateway/session_busy_controls.py`, `gateway/session_tui_config.py`).

---

#### Submit a prompt

**UI**: composer `apps/desktop/src/app/chat/composer/` (`index.tsx`, `hooks/use-composer-submit.ts`, `hooks/use-composer-queue.ts`, `attachments.tsx`, `controls.tsx`); pipeline `app/session/hooks/use-prompt-actions/submit.ts` (`submitParams`, ~line 1082); bubble rendering `components/assistant-ui/thread/`.

**Calls**: `prompt.submit` — **WS canonical** (`CanonicalSubmitParams`, `tui_gateway/contracts/canonical_sessions.py`; handler `gateway/session_controls.py::submit` → `session_authority.submit`). Closed key set:

| Param | Type | Req | Meaning |
|---|---|---|---|
| `session_id` | string | yes | Attached session. |
| `text` | string | yes | The user turn as the model sees it, including `@file:` refs from `file.attach` (see Attachments). Must be a string (canonical refuses structured parts). |
| `submission_id` | string | one of | Client-minted idempotency key (UUID). Same id + same payload = replay of the same admission (lost-ACK retry is safe). Same id + different payload = `admission_conflict`. The Desktop mints one when absent (`canonicalProducerFrame`). |
| `input_id` | string | one of | Alias of `submission_id` (either must be a non-empty string, else `invalid_params`). |
| `attachments` | `[{path:string, mime:string}]` | no | Images only. 1-10 items, `mime` ∈ `image/png`, `image/jpeg`, `image/gif`, `image/webp`; `path` absolute and directly inside the profile's image staging dir `<HERMES_HOME>/cache/images` (as returned by `image.attach_bytes` or `POST /api/chat/image-upload`). Anything else is `invalid_params` (`gateway/session_ingress_media.py::admit_attachments`). |
| `queued` | bool | no | Accepted, not committed: on the canonical owner every submit is a FIFO admission anyway (`submission()` drops it). |
| `display_kind` | `"hidden"` | no | Persist the user row as hidden (never a user bubble). Any other value: `invalid_params` (`gateway/session_display.py`). |
| `title_preview` | string | no | Large-paste preview, titler input only, truncated to 1000 chars. |
| `surface` | `"hud"` \| `"voice-live"` | no | Where the user typed; adds a per-turn model note (`gateway/session_surface.py`). |
| `voice_context` | string | no | Only with `surface:"voice-live"` (else `invalid_params`); recent spoken transcript, model input only, truncated to 6000 chars. |
| `voice_turn` | bool | no | Spoken voice-conversation turn; runs on `auxiliary.voice_chat`. |
| `interrupted` | bool | no | Client-side barge-in: the model message carries an "interrupted" note. |
| `finite` | bool | no | One-shot viewer (`-z`/`chat -q`): clarify answered headless, only the terminal reply matters (`gateway/session_finite.py`). |
| `unattended` | bool | no | Requires `finite:true` and an operator grant. |
| `turn_author` | `{id?,name?,is_bot?,origin?}` | no | Memory attribution (bots/relay). |
| `api_text` | string | no | Model-facing text when it differs from the persisted `text`. |
| `model_override` | `{model, provider?, reasoning?}` | no | `/model X --once` for this admission only. |
| `clarify_batch` | bool | no | Ask the clarify tool's whole batch as one `clarify` server request (see Clarify). Default: one `clarify.request` event per question. |
| `profile` | string | no | Routing (see conventions). |

Edit / regenerate / restore: there are no truncate keys on the owner. Send `session.mutate {operation:"rewind", payload:{target_message_id:<row_id>}, expected_revision, expected_generation, request_id}` then a plain `prompt.submit` (`canonical-protocol.ts::splitRewindSubmit`, `client.ts` ~line 395). Rewind is documented in the sessions slice.

**Result** (`CanonicalSubmitResult`): either the admission receipt `AdmissionStatus` (`gateway/session_contract.py::AdmissionReceipt` via `asdict`):

```json
{"admission_id":"3f9c0d2e8b1a4c6f9e7d5b3a1c0e2f4d",
 "ref":{"profile_id":"C:\\Users\\me\\AppData\\Local\\hermes","session_id":"<session_id>"},
 "sequence":14, "status":"queued", "outcome":null,
 "authority_epoch":7, "execution_generation":null}
```

- `status` ∈ `queued | started | unknown | terminal`. A fresh submit normally answers `queued`; the drain claims it right after (`_schedule_admitted`). A replayed `submission_id` returns the row's current status.
- `outcome` (terminal rows): `completed | failed | interrupted | rejected | cancelled | steered` (`hermes_state_runtime.py`). `execution_generation` is the claim's generation once started, else null.
- `ref.profile_id` is the owning profile's home path, not its name (`session_images.py` builds `Path(authority.profile_id)/cache/images`).
- Or `{"voice_stopped": true}`: the text was a bare stop phrase typed while the session's local voice chat was on; the chat ended and nothing was admitted (`session_controls.py::_typed_voice_stop`).

Refusals: `permission_denied` (not attached / no `session:submit`), `invalid_params` (unknown key, missing id, bad attachment, bad surface), `admission_conflict`, `queue_full` (32 queued rows per session, `hermes_state_runtime.ADMISSION_QUEUE_MAX`), `runtime_draining` (owner restarting; an interactive operator socket is still queued during a restart's after-turn wait, `session_authority.submit queue_while_restarting`), `storage_unavailable`.

**Events** (immediately, before the result frame): `session.info` with the pending list (section below), because `_admitted` publishes before the receipt returns.

**Notes**
- Client deadline: the Desktop waits up to 1 800 000 ms for the `prompt.submit` reply (`api/client.ts::PROMPT_SUBMIT_REQUEST_TIMEOUT_MS`); canonical replies right after the SQLite write, so a long wait means a stalled owner. On timeout, re-send with the SAME `submission_id`.
- Bind the optimistic user bubble to `submission_id`; `message.complete.persisted_turn.submission_id` and `user_row_id` later name its durable row.
- Busy session: the submit just queues behind the running turn (FIFO). To steer or redirect instead, use `session.steer` / `session.redirect`; to stop first, `session.interrupt` then submit. The per-session busy preference is `config.get|set {session_id, key:"busy", value:"interrupt"|"steer"|"queue"}` → `{key:"busy", value, scope:"session"}` (`session_busy_controls.py::busy_config`; default `interrupt`, `queue` for a TUI launch, overridable by `display.busy_input_mode`).
- Legacy (non-canonical `hermes serve`) form, for reference only: `PromptSubmitParams` (`text`, `display_kind`, `interrupted`, `queued`, `surface`, `voice_context`, `voice_turn`, `title_preview`, `truncate_before_*`, `confirm_*`, `rebind_survivor_row_ids`) → `PromptSubmitResult {status: streaming|queued|steered|redirected, user_row_id?, survivor_*?, admission_id?, forwarded?}` (`tui_gateway/contracts/prompt_voice.py`). A legacy server refuses `submission_id` as an unknown key (`4000`/`4094`); the Desktop retries identityless (`submit.ts` ~line 1162).

---

#### Admission receipt and queued prompts

**UI**: queued rows above the composer `app/chat/composer/hooks/use-composer-queue.ts`, actions `app/chat/composer/discard-lost-prompt.ts` (cancel / steer / edit / discard lost), pending tracking `store/pending-submissions.ts`, `store/composer-queue.ts`.

**Calls** (all **WS canonical**, `tui_gateway/contracts/canonical.py`, `canonical_sessions.py`; handlers `session_controls.py`, `gateway/session_queued_controls.py`):

| Method | Params | Result | Notes |
|---|---|---|---|
| `prompt.receipt` | `session_id`, `admission_id`, `include_result?: bool` | `AdmissionStatus` + `result?`, `usage?` | `result`/`usage`: the structured turn result committed at settlement, only with `include_result:true` on a terminal row. Caller must be the submitter or hold `session:control`. |
| `prompt.cancel` | `session_id`, `admission_id` | `AdmissionStatus` | Queued → `terminal/cancelled`. Started → the claim is interrupted in the same call (no extra `session.interrupt`). A row claimed between read and cancel is stopped too. |
| `prompt.replace_queued` | `session_id`, `admission_id`, `submission_id` (NEW id for the edit), `text`, `attachments?`, `display_kind?`, `title_preview?` | `AdmissionStatus` of the NEW admission | Cancels the queued row and admits the edit at the FIFO tail in one write. Refusals: `admission_started`, `admission_conflict`, `queue_full`, `unsupported_control`, `not_found`. Retry an unconfirmed save with the same `submission_id`. |
| `prompt.steer_queued` | `session_id`, `admission_id` | `AdmissionStatus` | Hands a still-queued row's text to the running turn as a steer. The row reads `started` (pending list: `steering:true`) and settles `terminal/steered` when the turn completes with it, `interrupted` otherwise; if the text never reached the agent it returns to its queue place. Refusals: `admission_started`, `session_idle`, `steer_unavailable` (no agent to take it; stays queued), `unsupported_control`, `not_found`. |
| `prompt.resolve_unknown` | `session_id`, `admission_id`, `execution_generation` (the generation stamped on the UNKNOWN row, not the live one) | `AdmissionStatus` | Acknowledge a turn lost across an owner restart (`status:"unknown"`); the FIFO behind it resumes. Never requeues the lost input. Exactly these three keys (`invalid_params` otherwise). |

Which rows offer steer/replace: `pending[].controls` lists `"steer"`, `"replace"` only for a queued plain-text row (no attachments/finite/automation, not hidden) in an interactive session (source `cli|tui|gui|desktop|acp`) (`session_queued_controls.py::queued_controls`). An owner that predates the verbs omits `controls`; show no buttons then.

**Events**: the pending FIFO is broadcast as a `session.info` event (`session_authority.py::_publish_pending`) after every admission, claim, cancel, settlement:

```json
{"jsonrpc":"2.0","method":"event","params":{"type":"session.info","session_id":"<sid>","seq":41,"replay_epoch":"9c1e…",
 "authority_epoch":7,"execution_generation":12,"admission_id":"3f9c…",
 "payload":{"stored_session_id":"<sid>","desktop_protocol":"hermes-gateway-v1","authority_epoch":7,
  "running":true,"execution_generation":12,"revision":31,
  "pending":[{"admission_id":"77ab…","ref":{"profile_id":"…","session_id":"<sid>"},"sequence":15,
              "status":"queued","outcome":null,"authority_epoch":7,"execution_generation":null,
              "input_id":"<submission_id>","text":"and then run the tests","controls":["steer","replace"],"steering":false}]}}}
```

- `pending[]` = `CanonicalPendingAdmission` (`AdmissionStatus` + `input_id` (= your `submission_id`), `text`, `controls?`, `steering?`), sorted by `sequence`. It lists rows with status `queued`, `started` (the running turn's own row) and `unknown`, plus queued rows in transfer to the running turn (`started` + `steering:true`) (`session_queued_controls.py::pending_rows`, `hermes_state_runtime.list_session_admissions`). Rows with `status:"unknown"` are lost turns: offer Discard → `prompt.resolve_unknown` with that row's `execution_generation`.
- `revision` is the session's CAS revision (turns bump it without `session.updated`); cache it for `session.mutate expected_revision` (`canonical-protocol.ts::adoptEventRevision`).
- `running` + `execution_generation`: authoritative busy state. The Desktop also derives `pending_submissions = pending.map(r => ({...r, user: r.text}))` client-side; that key is not on the wire.
- Snapshot form: `session.create` / `session.resume` return `pending[]`, `execution_generation`, `running`, `revision` at top level (`CanonicalSnapshot`).

---

#### Turn event stream

**UI**: dispatcher `app/session/hooks/use-message-stream/gateway-event/index.ts` (ordered handlers), streaming `gateway-event/message-stream.ts`, tools `gateway-event/tools.ts`, status `gateway-event/status.ts`, session info `gateway-event/session-info.ts`; bubble assembly `app/session/hooks/use-message-stream/index.ts`, `lib/chat-messages/tool-parts.ts`.

**Calls**: none (server → client events). Emitters on the canonical owner: `gateway/session_authority.py` (`message.start`, `session.info`), `gateway/run_turn_runner.py` (`message.delta`, `message.interim`), `gateway/run_turn_progress.py` (`tool.start`, `tool.complete`, `todo.updated`, `agent.step`, `status.update`, `notification.*`), `gateway/local_viewer_events.py` (`tool.generating`, `agent.print`, `thinking.delta`, `reaction`, `reasoning.delta`, `reasoning.available`, `tool.progress`, `subagent.*`, `moa.*`), `gateway/session_usage.py` (`session.usage`), `gateway/session_settlement_recovery.py` (`message.complete`). Payload models: `tui_gateway/contracts/events.py`. Most of these are emitted only for a **local viewer** turn (Desktop/TUI/CLI session, `Platform.LOCAL`); a messaging-platform route never installs them.

Every example below shows `params` of the event frame; `<stamp>` = `"authority_epoch":7,"execution_generation":12,"admission_id":"3f9c…","seq":N,"replay_epoch":"9c1e…"`.

| Event `type` | Payload | Meaning / when |
|---|---|---|
| `message.start` | `{}` for an ordinary turn; automation turn: `{display_kind, display_text, text, notification_category?}` | Turn claimed and started. An automation turn (background process finished) carries its async-result card (`session_automation.admission_display`). |
| `message.delta` | `{text: string \| null}` | One reply chunk. `text:null` = segment boundary (the agent closed the reply box before tool lines). Legacy may add `rendered`. |
| `reasoning.delta` | `{text}` | Streamed model reasoning; only when the session shows reasoning (`config.set key:"show_reasoning"`, default on for gui/tui launches, re-checked per delta). Legacy may add `verbose`. |
| `reasoning.available` | `{text}` | A completed reasoning block (non-streaming providers); same gate. |
| `thinking.delta` | `{text}` | Spinner text ("" clears). Not transcript. The Desktop shows explained provider waits from it in the status row (`store/provider-wait.ts`). |
| `message.interim` | `{text, already_streamed:false}` | Commentary beside tool calls that was NOT streamed as deltas; seal it as its own bubble. |
| `tool.generating` | `{name}` | Model is emitting a tool call's arguments. Status only: no id, no args, no row. |
| `tool.start` | `{tool_id, name, context, tool_call_id, tool_name, args, labels?}` | Tool call began. `tool_id`=`tool_call_id` (stable id), `context` = ≤80-char preview, `args` bounded to 64 KiB (`gateway/session_tool_events.py::bounded_args`), `labels` = `ToolLabel[]` for bridged tool_search calls (`{kind: connector\|mcp\|tool, app, action, emoji, text, name, preview}`). |
| `tool.progress` | `{tool_id, name, progress, total?, percent?, message?}` | MCP `notifications/progress`, ≤1 per 500 ms per call. Row annotation only. |
| `tool.complete` | start fields + `{is_error, result, result_text?, duration_s, inline_diff?, todos?, revision?}` | Tool finished. `result` is a STRING bounded to 64 KiB (oversize → `{"output"\|"error": "<32 KiB>", "truncated": true}` JSON). `result_text` only under `/verbose` (`config.set key:"verbose" value:"verbose"`). `inline_diff` = rendered edit diff for write tools. Todo tool adds `todos`/`revision`. Legacy sends `result` as parsed JSON plus `summary`. |
| `todo.updated` | `{todos:[{id, content, status}], revision}` | Full todo snapshot after a todo tool (see Plan/todos). |
| `agent.step` | `{iteration}` | One agent loop iteration (not in the typed contract). |
| `agent.print` | `{text}` | A status line the agent printed (subagent completion, compaction, budget notices). |
| `status.update` | `{kind, text}` | Transient status line. `kind`: `status`, `lifecycle`, `compacting` (auto-compaction progress), `goal`, `loop`, `heartbeat`, `process`, … |
| `notification.show` | `{text, level: info\|warn\|error\|success, kind: sticky\|ttl\|agent, ttl_ms?, key?, id?, detail?}` | Keyed toast. |
| `notification.clear` | `{key}` | Withdraw that toast. |
| `session.usage` | `{usage: Usage, …top-level counters (deprecated)}` | After every API call and at turn end. Read `usage` (`model, input, output, reasoning, prompt, completion, total, calls, context_used?, context_max?, context_percent?, context_source?, context_estimated?, compressions?, cache_hit_pct?, avg_latency_s?, avg_tps?`) (`session_usage.py::usage_frame`, `tui_usage`). |
| `session.info` | pending projection (above) or full `local_session_info` | Pending/running/generation changes; after auto-title or inventory change the full info (`model`, `title`, …) is republished (`run_turn_progress.py::_attach_session_title_callback`, `session_local_inventory.py`). Legacy also emits `session.title {session_id, title}`. |
| `reaction` | `{kind}` | Affection detected in the user's message (`kind:"vibe"` → hearts). |
| `message.reaction` | `{row_id, reactions:[MessageReaction], role}` | The agent reacted to a row (see Reactions). |
| `moa.reference` / `moa.progress` / `moa.phase` / `moa.aggregating` | `{label, text, index?, count?}` / `{label, refs_done, refs_total}` / `{phase:"aggregator", refs_done?, refs_total?, aggregator?}` / `{aggregator}` | Mixture-of-agents progress; rendered in the reasoning disclosure. |
| `subagent.*` | `SubagentEventPayload` | See Subagents. |
| `approval.request` / `approval.settled` / `clarify.request` / `clarify.settled` | see Approvals / Clarify | Blocking prompts. |
| `review.summary` | `{text}` | Background review of the last turn finished. |
| `message.complete` | see below | Turn ended. |
| `error` | `{message}` | Session-level failure outside a turn (agent init, model switch, resume). Turn failures arrive as `message.complete status:"error"`. |

**`message.complete`** (canonical, `session_settlement_recovery.py::completion_payload`):

| Field | Type | Meaning |
|---|---|---|
| `text`, `content` | string | Final response text (same value twice). For a healthy turn: the response after the last tool row, not the whole bubble. |
| `admission_id` | string | The admission this turn ran. |
| `outcome` | `completed\|failed\|cancelled\|rejected` | `interrupted` is reported as `cancelled`. |
| `status` | `complete\|interrupted\|error` | The `TurnStatus` to render. |
| `error_surface?` | `{layer, code, retryable, provider?, model?, resets_at?, usage_limit?}` | Present on `status:"error"`; `code:"usage_window"` carries `usage_limit {gauge_id, scope, limit_name, resets_at_ms?, message, suggestions[], credits?, current_model}`. |
| `response_reused?` | true | `text` repeats a reply already streamed/sealed this turn: settle in place, add no text. |
| `response_previewed?`, `response_transformed?` | true | A transform plugin rewrote the reply; with `response_transformed` and empty stream, `text` is the stored rewrite. |
| `warning?` | string | Toast it (e.g. "Gateway restarting: this turn was interrupted…"). |
| `billing?` | `BillingBlock {provider, provider_label, model, billing_url, is_nous, message, unverified?}` | Credit wall. |
| `failure_reason?`, `reasoning?` | string | Local viewer turns only. |
| `usage?` | `Usage` | Session-cumulative status-bar view at turn end. |
| `persisted_turn?` | `{row_ids[], complete, user_row_id?, final_assistant_row_id?, user_row_ids?, submission_id}` | Committed SQLite row ids; bind the user bubble (`submission_id`, `user_row_id`) and the assistant bubble (`final_assistant_row_id`) to durable rows so a transcript re-read never paints twice. |
| (legacy only) `partial`, `error`, `recoverable`, `rendered` | | Legacy `prompt_turn._complete_turn_payload`. |

After `message.complete` the owner publishes `session.info` with `running:false` (and the next pending head, which then gets its own `message.start`).

**Notes**
- `tool.complete` may arrive for a tool whose row lives in an earlier, already-sealed bubble (long tools outlive interim boundaries): route by `tool_id`, not by "current bubble" (`use-message-stream/index.ts::upsertToolCall` `eventTarget`).
- `is_error` is authoritative for failure on canonical; the Desktop renderer infers failure from the result instead (`tools.ts`). Use `is_error`.
- Ordering guarantees: frames of one session are published under one lock in order; `message.start` precedes all stamped turn frames; `message.complete` is last stamped frame of the turn. Whether the `prompt.submit` result frame precedes `message.start` is not guaranteed (claim runs right after the admission write) (unverified).
- After compaction mid-turn no new `message.start` comes; the next model/tool event means it resumed.

#### Reconstructing a message from deltas

Algorithm the Desktop applies (`use-message-stream/index.ts`: `flushQueuedDeltas`, `appendReasoningDelta`, `upsertToolCall`, `finalizeInterimAssistantMessage`, `completeAssistantMessage`; `lib/chat-messages/tool-parts.ts::upsertToolPart`):

1. **On submit**: append an optimistic user bubble keyed `user-<submission_id>`; set busy/awaiting.
2. **`message.start`**: if no turn was live, DROP any buffered deltas (stragglers of the previous turn); else flush them. Mark turn live, start the turn timer, clear billing banner, clear "compacting".
3. **`message.delta`**: buffer text per session and paint every ~33 ms (adaptive, at most 250 ms: `utils.ts STREAM_DELTA_FLUSH_MS`, `MAX_STREAM_FLUSH_GAP_MS`). Append to the assistant bubble's LAST text part; if the last part is a tool or reasoning part, start a new text part. `text:null` closes the current text part (next text starts a new part below tool rows).
4. **`reasoning.delta`**: append to the bubble's reasoning part (separate collapsible). `reasoning.available` / first `moa.reference`: replace the reasoning part, unless the bubble already has reply text.
5. **`tool.start`**: flush buffered text first (a tool row must not jump ahead of the text before it); stamp `completedAt` on open text/reasoning parts; upsert a tool part keyed by `tool_id` (`running`, args + `context` preview + labels). A `tool.start` for an id whose row was sealed without a result re-opens it.
6. **`tool.complete`**: find the part by `tool_id` anywhere in the transcript (else append one); set `result`, `duration_s`, `inline_diff`, `is_error`, `completedAt`. Todo payloads update the session's todo store.
7. **`message.interim`**: flush, then seal `text` as its own finished assistant bubble; later deltas start a new bubble.
8. **`message.complete`**: flush; clear open prompts for the session; then:
   - `status:"interrupted"` after a local Stop: keep the partial already on screen, extend it to `text` if longer.
   - `status:"error"`: mark the bubble failed with `error_surface`; drop its text unless the legacy `partial:true` says the text is real output.
   - `response_reused:true` and the bubble already contains `text` (whitespace-normalised): keep as is.
   - otherwise replace the text AFTER the last tool part with `text` (healthy finals carry only the current response).
   - Close all open parts, record duration, bind `persisted_turn.final_assistant_row_id` as the bubble's `rowId` and `user_row_id` to the optimistic user bubble; apply `usage`.
9. **`session.info running:false`**: authoritative idle (clear busy even if `message.complete` was lost). A silent session (no attributed event for a long time) is force-settled by the Desktop (`noteSessionEvent`).
10. On reconnect, replace optimistic state with the snapshot's `messages` (`TranscriptMessage[]`) and replay `session.events.since` from your last `seq`.

#### Example: one complete turn (canonical)

```text
→ {"jsonrpc":"2.0","id":31,"method":"prompt.submit","params":{"session_id":"S","text":"How big is README.md?","submission_id":"0b6f2c1e-6a1f-4f3e-9d2b-2f4a8c1d7e55"}}
← {"jsonrpc":"2.0","method":"event","params":{"type":"session.info","session_id":"S","seq":101,"replay_epoch":"E",
     "payload":{"stored_session_id":"S","desktop_protocol":"hermes-gateway-v1","authority_epoch":7,"running":false,"execution_generation":11,"revision":30,
       "pending":[{"admission_id":"A","ref":{"profile_id":"H","session_id":"S"},"sequence":14,"status":"queued","outcome":null,"authority_epoch":7,"execution_generation":null,
                   "input_id":"0b6f2c1e-…","text":"How big is README.md?","controls":["steer","replace"],"steering":false}]}}}
← {"jsonrpc":"2.0","id":31,"result":{"admission_id":"A","ref":{"profile_id":"H","session_id":"S"},"sequence":14,"status":"queued","outcome":null,"authority_epoch":7,"execution_generation":null}}
← {"jsonrpc":"2.0","method":"event","params":{"authority_epoch":7,"execution_generation":12,"admission_id":"A","type":"message.start","session_id":"S","payload":{},"replay_epoch":"E","seq":102}}
← {"jsonrpc":"2.0","method":"event","params":{"authority_epoch":7,"execution_generation":12,"admission_id":"A","type":"session.info","session_id":"S","payload":{"stored_session_id":"S","desktop_protocol":"hermes-gateway-v1","authority_epoch":7,"running":true,"execution_generation":12,"revision":31,"pending":[{"admission_id":"A",…,"status":"started","execution_generation":12,…}]},"replay_epoch":"E","seq":103}}
← {… "type":"agent.step","payload":{"iteration":1},"seq":104}
← {… "type":"reasoning.delta","payload":{"text":"Need the file size; use terminal."},"seq":105}
← {… "type":"message.delta","payload":{"text":"Let me check."},"seq":106}
← {… "type":"message.delta","payload":{"text":null},"seq":107}
← {… "type":"tool.generating","payload":{"name":"terminal"},"seq":108}
← {… "type":"tool.start","payload":{"tool_id":"call_9x","name":"terminal","context":"wc -c README.md","tool_call_id":"call_9x","tool_name":"terminal","args":{"command":"wc -c README.md"}},"seq":109}
← {… "type":"tool.complete","payload":{"tool_id":"call_9x","name":"terminal","context":"wc -c README.md","tool_call_id":"call_9x","tool_name":"terminal","args":{"command":"wc -c README.md"},"is_error":false,"result":"{\"output\": \"4812 README.md\", \"exit_code\": 0}","duration_s":0.21,"inline_diff":null},"seq":110}
← {… "type":"agent.step","payload":{"iteration":2},"seq":111}
← {… "type":"session.usage","payload":{"usage":{"model":"claude-…","input":5120,"output":64,"reasoning":12,"prompt":5120,"completion":64,"total":5184,"calls":1,"context_used":5120,"context_max":200000,"context_percent":3,"context_source":"provider_usage","context_estimated":false},"input_tokens":5120,"output_tokens":64,"api_calls":1},"seq":112}
← {… "type":"message.delta","payload":{"text":"README.md is 4,812 bytes."},"seq":113}
← {… "type":"session.usage","payload":{"usage":{…,"calls":2}},"seq":114}
← {"jsonrpc":"2.0","method":"event","params":{"authority_epoch":7,"execution_generation":12,"admission_id":"A","type":"message.complete","session_id":"S","replay_epoch":"E","seq":115,
     "payload":{"text":"README.md is 4,812 bytes.","content":"README.md is 4,812 bytes.","admission_id":"A","outcome":"completed","status":"complete",
       "usage":{"model":"claude-…","input":10400,"output":120,"total":10520,"calls":2},
       "persisted_turn":{"row_ids":[881,882,883,884],"complete":true,"user_row_id":881,"final_assistant_row_id":884,"user_row_ids":[881],"submission_id":"0b6f2c1e-…"}}}}
← {… "type":"session.info","payload":{"stored_session_id":"S","desktop_protocol":"hermes-gateway-v1","authority_epoch":7,"running":false,"execution_generation":12,"revision":32,"pending":[]},"seq":116}
```

(Ids, numbers and the exact interleaving of `agent.step` / `session.usage` are illustrative; field sets are from source.)

---

#### Approvals (dangerous commands, edits)

**UI**: card `components/assistant-ui/tool/approval.tsx` (+ `approval-activity.ts`), keybinds `lib/keybinds/approval-keys.ts`, store `store/prompts.ts` (`receiveApprovalRequest`, `answerApproval`, `replayPendingApproval`), reconnect `app/session/hooks/use-session-actions/restore-pending-approval.ts`, mode menu `app/shell/approval-mode-menu.tsx` (`config.set key:"approvals.mode"`).

**Calls**

1. **Canonical (what a native client uses on `/api/ws`)** — prompt arrives as an EVENT, answered by RPC:
   - `approval.respond` — **WS canonical** (`CanonicalApprovalParams`): exactly `{session_id, execution_generation:int, prompt_id:string, choice:string}` (any extra key → `invalid_params`; a non-empty `profile` is consumed by routing before this check). `choice` must be one of the prompt's `choices` (`invalid_params` otherwise). Result `PromptResponseResult {status:"resolved"|"already_resolved", prompt_id}`. Refusals: `stale_generation` (turn over / other generation / not running), `permission_denied` (not attached or no `session:approve`), `invalid_params`.
   - `approval.pending` — **WS canonical (owner-served)** `{session_id}` → `{approvals:[{request_id, command, description, choices, allow_permanent}]}` (replay after reconnect; `session_ancillary.py::approvals`).
   - `approval.received` — **WS canonical (owner-served)** `{session_id, request_id}` → `{acknowledged: bool}`: tell the backend the card is on screen. The contract says this starts the timeout clock; the code only records `entry.acknowledged` (`tools/approval.py::ack_gateway_approval`) (effect unverified).
2. **Legacy server request** (`tui_gateway/server.py::_emit_approval_request`, non-canonical gateway): server request `approval` with `ApprovalRequestParams {session_id, request_id, command, description, choices, allow_permanent?, allow_session?, smart_denied?, tool_name?, gateway_session_id?, …open keys}` → answer `{"result":{"choice":"once"|"session"|"always"|"deny","all"?:true}}` (`all` resolves every pending approval). Alternative: legacy RPC `approval.respond {session_id, choice?, all?, request_id?}` → `{resolved:int}`. The Desktop answers via the server request when it has one, else the RPC, and re-sends once on a timeout (`store/prompts.ts::answerApproval`).

**Events**

`approval.request` (canonical, `gateway/session_pending_controls.py::register`, stamped):

```json
{"type":"approval.request","session_id":"S","authority_epoch":7,"execution_generation":12,"admission_id":"A","seq":220,"replay_epoch":"E",
 "payload":{"kind":"approval","prompt_id":"5d1c9a7e0f2b4e6c8a9d3b1f7e5c2a40","execution_generation":12,
  "command":"rm -rf build/","description":"recursive delete","choices":["once","deny","session","always"],"timeout_s":300}}
```

- `choices`: `once`, `deny`, plus `session` unless `allow_session:false` or smart-denied, plus `always` unless `allow_permanent:false` or smart-denied. An edit approval carries `edit:{…}` (opaque preview object; shape owned by the tool, unverified) and only `["once","deny"]`.
- `command` / `description` are redacted server-side.
- `timeout_s`: present on local-viewer turns = `approvals.timeout` (default 300 s, `tools/approval_context.py::_get_approval_timeout`).

`approval.settled` `{prompt_id, execution_generation}`: the prompt is gone (answered here or elsewhere, timed out, turn ended). Remove the card. At turn end every remaining prompt is settled (`_publish_completion` → `controls.snapshot(…, None)`).

`approval.cancelled` (legacy broadcast) `{session_id, stored_session_id, reason, cancelled_count, request_ids[]}`: pending approvals dropped by interrupt/reap/teardown, resolved as deny. `request.cancel {id, method:"approval", reason}`: legacy server request withdrawn.

**Notes**
- Choice semantics: `once` this call; `session` this command pattern for the rest of the session; `always` persist to the allowlist; `deny` refuse (the tool returns a denial to the model).
- Reconnect: the `session.resume` snapshot's `prompts[]` (`CanonicalPendingPrompt {kind:"approval"|"clarify", prompt_id, execution_generation, choices, command?, description?, edit?, question?, multi_select?, timeout_s?}`) re-delivers open approvals; render them as if the event had just arrived. Use that prompt's `execution_generation` when answering.
- Bypass: session yolo `config.set {session_id, key:"yolo", value:"on"|"off"}` → `{key:"yolo", value:"1"|"0", scope:"session"}`; approval mode `config.set key:"approvals.mode"`.
- A canonical approval timeout resolves as deny on the server; the card gets `approval.settled`.

#### Example: one approval round trip (canonical)

```text
← {… "type":"tool.start","payload":{"tool_id":"call_7","name":"terminal","context":"rm -rf build/",…},"seq":219}
← {"jsonrpc":"2.0","method":"event","params":{"authority_epoch":7,"execution_generation":12,"admission_id":"A","type":"approval.request","session_id":"S","replay_epoch":"E","seq":220,
     "payload":{"kind":"approval","prompt_id":"P","execution_generation":12,"command":"rm -rf build/","description":"recursive delete","choices":["once","deny","session","always"],"timeout_s":300}}}
→ {"jsonrpc":"2.0","id":32,"method":"approval.received","params":{"session_id":"S","request_id":"P"}}
← {"jsonrpc":"2.0","id":32,"result":{"acknowledged":true}}
→ {"jsonrpc":"2.0","id":33,"method":"approval.respond","params":{"session_id":"S","execution_generation":12,"prompt_id":"P","choice":"once"}}
← {"jsonrpc":"2.0","method":"event","params":{…,"type":"approval.settled","session_id":"S","payload":{"prompt_id":"P","execution_generation":12},"seq":221}}
← {"jsonrpc":"2.0","id":33,"result":{"status":"resolved","prompt_id":"P"}}
← {… "type":"tool.complete","payload":{"tool_id":"call_7","name":"terminal","is_error":false,"result":"{\"output\": \"\", \"exit_code\": 0}",…},"seq":222}
```

(Whether `approval.settled` precedes the RPC result is not guaranteed (unverified); handle either order.)

---

#### Clarify (AskUserQuestion-style questions)

**UI**: inline card `components/assistant-ui/clarify/` (`pending.tsx`, clarify tool row), store `store/clarify.ts` (`lockAnswer`, `typedAnswer`, `settleClarify`), server-request handler `app/session/hooks/use-message-stream/gateway-event/server-requests.ts::clarify`, settle handling `gateway-event/input-requests.ts`.

**Calls / Events** — two forms on a canonical gateway:

**A. Per-question (default; what Desktop gets).** The clarify tool's batch (1-5 questions) is asked ONE CARD AT A TIME, in order; the batch stops at the first question not answered (cancel, timeout, undelivered) (`gateway/run_turn_runner.py::_clarify_callback_sync`).

- Event `clarify.request` (stamped, `session_pending_controls.py::register_clarify`):
  ```json
  {"type":"clarify.request","session_id":"S","execution_generation":12,…,
   "payload":{"kind":"clarify","prompt_id":"Q1","execution_generation":12,"question":"Which environment?","choices":["staging","production"],"multi_select":false,"timeout_s":3600}}
  ```
  `choices` may be `[]` (free text). `timeout_s` = clarify timeout (default 3600 s; `0`/negative = unlimited, then omitted) (`tools/clarify_gateway.py::get_clarify_timeout`, `local_viewer_events.prompt_timeout_s`).
- `clarify.respond` — **WS canonical** exactly `{session_id, execution_generation, prompt_id, answer:string}` → `{status:"resolved"|"already_resolved", prompt_id}`. `answer:""` = skipped. Multi-select: a JSON-array string, e.g. `"[\"production\",\"staging\"]"` (`store/clarify.ts` line ~285). Free text: any string (≤16 384 chars when the prompt is remote-relayed). Refusals as `approval.respond` (`session:respond` capability).
- Event `clarify.settled {prompt_id, execution_generation}`; then the next question's `clarify.request` (if any).

**B. Batch (opt-in with `prompt.submit clarify_batch:true`).** One server request with all questions; answers editable until all are locked (`gateway/session_clarify_batch.py`, `tui_gateway/server_requests.py`).

- Server request `clarify` `ClarifyRequestParams {session_id, questions:[{qid, question, choices?, multi_select}], answers?}` (`answers` only on a reconnect replay: already-locked answers, `null` = skipped).
- `clarify.lock` — **WS canonical** `{request_id:"srq-…", question_id:qid, answer:string|null, profile?}` → `{status:"ok"|"expired", remaining?:[qid…]}`. `null` = skip; non-string answers are JSON-encoded server-side. The lock that empties `remaining` resolves the request (no response frame owed). `expired` = the wait already ended (not an error). Only a viewer attached to the session may lock.
- Or answer the request frame directly: `{"result":{"answers":{"q0":"staging","q1":null},"outcome":"submitted"}}`; `{"result":{}}` = cancel-all; `outcome` ∈ `submitted|timed_out|cancelled` (`ClarifyResult`). Answers locked earlier are merged in (`tools/clarify_batch.settle_batch`).
- Timeout: same clarify timeout; on timeout the locked answers survive and `request.cancel {id, method:"clarify", reason:"timeout"}` is emitted.
- `request.answer {id, result, profile?}` → `{status:"ok"|"expired"}` (legacy sidecar): answer an open request from a client that never received the frame.

**Notes**
- The Desktop's `clarify.lock` for a canonical per-question prompt is rewritten into `clarify.respond` (`client.ts` ~line 405). Native: use `clarify.respond` for form A, `clarify.lock`/response for form B.
- A clarify blocks the turn: the first `tool.complete` after it means it resolved.
- Reconnect: per-question prompts come back in the snapshot's `prompts[]`; batch requests in `open_requests[]` (`OpenRequestEntry {id, method, params}`), also returned by `session.events.since`.
- Legacy gateway: only form B exists (server request `clarify`, timeout = clarify config, 300 s fallback; `tui_gateway/server.py::_clarify_timeout_seconds`).

---

#### Sudo, secret and vault prompts

**UI**: masked-input overlays `components/prompt-overlays.tsx`; stores `store/prompts.ts` (`setSudoRequest`, `setSecretRequest`), handlers `gateway-event/server-requests.ts` (`sudo`, `secret`, `vault.*`, MCP setup); vault settings `app/settings/vault-settings.tsx`.

**Calls**: all are **server requests** (owner: `gateway/session_sudo_prompt.py`, `session_secret_prompt.py`, `session_vault_prompt.py` through `gateway/session_server_requests.py::ask_viewer`). Answer `{"result":{"value":"<string>"}}`; `""` = declined/skipped. Values are never published, journaled or logged.

| Method | Params (+`session_id`) | Answer `value` | Timeout |
|---|---|---|---|
| `sudo` | `command` (redacted) | sudo password | 120 s |
| `secret` | `env_var`, `prompt`, `metadata?` | the secret value | 120 s |
| `vault.unlock_prompt` | `backend`, `display_name` | master password | 120 s |
| `vault.save_login` | `origin`, `site` | JSON text `{"identifier":"…","password":"…"}` | 180 s |
| `vault.code` | `site?`, `hint?` | one-time / 2FA code | 180 s |

**Events**: `request.cancel {id, method, reason}` with `reason` ∈ `timeout | interrupted | shutdown | resolved | session_closed` (`RequestCancelPayload`): clear only the card with that `id`.

**Notes**
- Who may answer: a connection attached to that session with `session:respond` (`session_server_requests.may_settle`); others' answers are dropped silently.
- The client must have sent `client.capabilities {server_requests:true}` (section 1); otherwise the owner never writes the frame and the tool sees "no answer".
- A stopped session: answer with an error (`-32603 "session interrupted"`) instead of dropping, so the tool returns now (`server-requests.ts::declineIfSessionStopped`).
- Window-owned bridges (`terminal.read`, `preview.read`, `preview.act`, `window.read`, `tour`) are covered in the native-only slice; a client that shows no such window answers error code `4404` (decline).

---

#### Plan / todos

**UI**: status stack above the composer `app/chat/composer/status-stack/` (`index.tsx`, `status-row.tsx`), stores `store/todos.ts`, `lib/todos.ts`; tool rows `components/assistant-ui/tool/`.

**Calls**: none dedicated. Snapshot: `session.resume` / `session.create` → `todo_state: {todos, revision} | null` (`CanonicalSnapshot.todo_state`, the transcript's newest todo result).

**Events**
- `tool.start` / `tool.complete` with `name` = the todo tool (`tools/todo_tool.py::is_todo_tool_name`); `tool.complete` carries `todos` + `revision`.
- `todo.updated` `{todos:[{id:string, content:string, status:"pending"|"in_progress"|"completed"|"cancelled"}], revision:int}` (`run_turn_progress.py::combined_tool_complete_callback`, `tui_gateway/tool_progress.py::_normalize_todo_state`):
  ```json
  {"type":"todo.updated","session_id":"S","execution_generation":12,…,"payload":{"todos":[{"id":"1","content":"Read config","status":"completed"},{"id":"2","content":"Patch parser","status":"in_progress"}],"revision":3}}
  ```

**Notes**: snapshots are full replacements; ignore one whose `revision` is lower than the one you hold (`{todos:[], revision:0}` is "no list", not a clear; empty at revision ≥1 is a real clear). The Desktop drops a still-unfinished list at `message.complete` (`clearActiveSessionTodos`) and keeps a finished one briefly. Session automation state (goal / loop / heartbeat) is `session.control.update` (other slice).

---

#### Interrupt / stop

**UI**: Stop button `app/chat/composer/controls.tsx`; `cancelRun` in `app/session/hooks/use-prompt-actions/index.ts` (~line 828); interrupted-bubble handling `use-message-stream/interrupted-reply.ts`.

**Calls**: `session.interrupt` — **WS canonical** `{session_id, execution_generation:int}` (`CanonicalControlParams`; handler `session_authority.interrupt`). Result `CanonicalSessionHandle {ref:{profile_id, session_id}, instance_id, authority_epoch, revision, execution_generation, execution_state:"idle"|"running"|"waiting"|"unknown"|"terminal"}`. Refusals: `stale_generation` (the generation is not the session's current one: that turn already settled; treat as done), `permission_denied` (no `session:control`).

To stop a queued-but-not-started prompt use `prompt.cancel`. Legacy form: `session.interrupt {session_id, expected_hosted_task_id?}` → `{status:"interrupted"|"not_interrupted", interrupted?, turn_isolation?}`.

**Events** after a stop (order indicative): pending prompts → `approval.settled` / `clarify.settled`; owner server requests → `request.cancel {reason:"interrupted"}` (unverified on canonical); `message.complete {status:"interrupted", outcome:"cancelled", text:<persisted partial>}`; `session.info {running:false}`. A stop latched before the turn built its agent fires when the agent is adopted (`adopt_agent`), so it is never lost.

**Notes**
- Stop hard-cancels the turn (also aborts compression). Background delegations (`delegate_task` children) keep running and report back; `/stop` or session close ends them.
- Client side: set a local "interrupted" latch for that generation, seal the bubble with what is on screen, drop late frames of that generation (except terminal `subagent.complete`), and on `message.complete status:"interrupted"` extend the bubble to the persisted partial. A newer generation's `message.start` clears the latch.
- Voice barge-in: the next submit carries `interrupted:true`; legacy `voice.interrupted` event (no payload) signals a spoken barge-in.

---

#### Steer and redirect mid-turn

**UI**: busy composer (`app/chat/composer/busy-submit-state.ts`, `hooks/use-composer-submit.ts::steerDraft`), `redirectPrompt` / `injectHiddenPrompt` in `app/session/hooks/use-prompt-actions/index.ts` (~lines 848-1030), `/steer` slash `slash-session-commands.ts`.

**Calls** — **WS canonical** (`gateway/session_busy_controls.py::correct`):

| Method | Params | Meaning |
|---|---|---|
| `session.steer` | `{session_id, execution_generation:int, text:non-empty string, profile?}` | Inject `text` into the running agent's next tool result without interrupting (joins this turn after its running tool). |
| `session.redirect` | same | Interrupt the current step and continue the turn with `text` as a user correction (recorded as a user message). |

Result `CanonicalCorrectionResult`: `{status:"queued"|"redirected"|"rejected", text, execution_generation, authority_epoch, delivered?:false}` or `{voice_stopped:true}`.

- `steer` → `queued` (delivered or latched for the agent this generation will adopt) or `rejected`.
- `redirect` → `redirected` (applied in place), `queued` (latched in the build window → becomes the next step), or `rejected`.
- `rejected` + `delivered:false`: no agent was live; safe to send the text as the next `prompt.submit`. Plain `rejected` may follow delivery (e.g. a Codex steer whose ACK timed out): never re-send.

Refusals (error frames): `stale_generation` (not running, or another generation), `unsupported_control` (managed worker / no steer seam), `execution_not_ready` (the agent has no `steer`/`redirect` method), `invalid_params` (empty text, non-int generation, extra key), `permission_denied`. These three (`execution_not_ready`, `stale_generation`, `unsupported_control`) mean the text reached no agent: re-queue it as a normal prompt (`apps/shared/src/json-rpc-channel.ts::UNDELIVERED_CORRECTION_REASONS`).

**Events**: none dedicated; the correction shows up in the stream (`message.delta` of the redirected step; for redirect the Desktop paints an optimistic user bubble at the arrival point and seals the stream there on `redirected`).

**Notes**: Steer a queued row instead of free text with `prompt.steer_queued`. Hidden notes (widget intents) use `session.steer` because it records nothing in the transcript. Legacy result: `{status, text}` only.

---

#### Subagents (delegate_task children)

**UI**: `app/chat/composer/status-stack/subagent-section.tsx`, `subagent-controls.tsx` (Steer/Stop per child), `subagent-transcript.tsx` (tail), `use-subagent-snapshot.ts`; store `store/subagents.ts`; event handling `gateway-event/tools.ts` (`SUBAGENT_EVENT_TYPES`).

**Calls** — **WS canonical (owner-served, `gateway/session_ancillary.py`)**; contracts `tui_gateway/contracts/profiles_vault_complete_foreign_subagents.py`, `billing_delegation_pets.py`:

| Method | Params | Result |
|---|---|---|
| `subagent.list` | `{session_id}` | `{subagents:[{subagent_id, parent_id?, depth?, goal?, delegation_id?, model?, started_at?, status?, tool_count?, last_tool?, accepting_steer?}], delegations:[{delegation_id, task_index, status, goal, error?, dispatched_at?, completed_at?}]}` — only this session's live children; `delegations` = recently failed async tasks. |
| `subagent.tail` | `{session_id, subagent_id}` | `{subagent_id, available, text, truncated}` — last 16 KiB of the child's live transcript. |
| `subagent.steer` | `{session_id, subagent_id, text}` | `{status:"queued"\|"rejected", subagent_id, text}` (`queued` ≠ delivered; a child past its last tool batch reports `missed_steer`). |
| `subagent.interrupt` | `{session_id, subagent_id}` | `{found, subagent_id}` (`found:false` = already finished). |
| `delegation.pause` | `{paused:bool, profile?}` | `{paused}` — block/unblock NEW spawns globally (owner-side rules, `gateway/session_tui_ops.py`). |
| `spawn_tree.save` / `.list` / `.load` | `{subagents[], session_id?, started_at?, finished_at?, label?}` / `{session_id?, cross_session?, limit?}` / `{path}` | `{path, session_id}` / `{entries:[{path, session_id?, started_at?, finished_at?, label, count}]}` / snapshot file — **WS legacy** (TUI history of finished trees; Desktop does not use them). |

**Events** `subagent.spawn_requested | subagent.start | subagent.progress | subagent.thinking | subagent.tool | subagent.complete` — payload `SubagentEventPayload` (`tui_gateway/tool_progress.py::_subagent_payload`):

```json
{"type":"subagent.complete","session_id":"S","execution_generation":12,…,
 "payload":{"goal":"Audit the parser","task_count":2,"task_index":0,"subagent_id":"sa_1","parent_id":null,"child_session_id":"S-child","delegation_id":"d_9",
  "depth":1,"model":"…","status":"completed","summary":"Found 2 bugs…","duration_seconds":41.2,"tool_count":7,"input_tokens":21000,"output_tokens":900,
  "api_calls":5,"files_read":["src/p.py"],"files_written":[],"output_tail":[{"tool":"read_file","preview":"…","is_error":false}]}}
```

Fields: `goal, task_count, task_index` always; optional `subagent_id, parent_id, child_session_id, delegation_id, depth, model, tool_count, toolsets, input_tokens, output_tokens, reasoning_tokens, api_calls, files_read, files_written, output_tail, tool_name, text, status (queued|running|completed|failed|error|timeout|interrupted), summary, duration_seconds, tool_preview`. Build the tree from `subagent_id` / `parent_id`; identity fields may be absent on older emitters (render flat).

**Notes**: a background delegation outliving the turn publishes its `subagent.*` frames WITHOUT an execution stamp (`local_viewer_events._publish_subagent`); accept terminal `subagent.complete` even after a Stop. If no `subagent.*` arrives, the Desktop synthesises rows from `delegate_task` tool events (`use-message-stream/utils.ts::delegateTaskPayloads`).

---

#### Background agents (/background, /btw)

**UI**: slash handling `app/session/hooks/use-prompt-actions/slash.ts` (`prompt.background`, `prompt.btw`); completion rendering in the message stream (`background-complete-event`, `btw-complete-event` tests).

**Calls** — **WS canonical (owner-served, `gateway/session_tui_side.py`)**:

| Method | Params | Result |
|---|---|---|
| `prompt.background` | `{session_id, text, profile?}` | `{task_id:"bg_<6 hex>"}` immediately; a fresh agent runs `text` detached. |
| `prompt.btw` | `{session_id, text, profile?}` | `{task_id:"btw_<6 hex>"}`; side question over a snapshot of the conversation. |
| `preview.restart` | `{session_id, url, cwd?, context?}` | `{task_id:"preview_<6 hex>"}`; hidden agent restarts the preview dev server. |

Refusal `invalid_params` (empty text, extra key), `not_found`.

**Events** (on the parent session's stream):
- `background.complete {task_id, text}`
- `btw.complete {task_id, question, text}`
- `preview.restart.progress {task_id, text, level?}` → `preview.restart.complete {task_id, text}`

**Notes**: these never enter the turn FIFO and carry no admission. A background PROCESS (terminal tool `background:true`) finishing is different: it is admitted as an automation turn whose `message.start` carries `{display_kind, display_text, text, notification_category?}`; process output streams as `agent.terminal.output {process_id, chunk}` / `terminal.close {process_id}` (other slice).

---

#### Attachments and uploads

**UI**: chips `app/chat/composer/attachments.tsx`, drop overlay `app/chat/chat-drop-overlay.tsx`, sync `app/session/hooks/use-prompt-actions/index.ts` (`stageForSession`), server-owned images `use-prompt-actions/upload-server-image.ts`, browser staging `lib/browser-uploads.ts`.

**Flow for a native client on a canonical gateway**

1. **Images** (sent as `prompt.submit attachments`), either:
   - `image.attach_bytes` — **WS canonical (owner-served, `gateway/session_images.py`)** `{session_id, content_base64 | data, filename?, ext?, profile?}` (all strings; magic bytes decide the type; ≤25 MiB decoded) → `{attached:true, path, mime, name, count:1, remainder:"", text:"[User attached image: <name>]", bytes}`. Staging only: nothing is queued on the session. Refusals `invalid_params` (not png/jpeg/gif/webp, empty, too big), `permission_denied` (not attached), `storage_unavailable`.
   - or `POST /api/chat/image-upload?profile=<name>` — **REST** JSON `{data_url:"data:image/png;base64,…", filename?}` → `{ok, path, name, bytes, mime_type}` (`hermes_cli/web_routers/files.py`; cap 25 MiB, browser keeps ≤16 MiB). Lands in the same `<HERMES_HOME>/cache/images`.
   - Then `prompt.submit {…, attachments:[{path, mime}]}` (≤10).
2. **Other files** (sent as `@file:` refs in `text`):
   - `POST /api/chat/file-upload?profile=<name>` — **REST** multipart, field `file` → `{ok:true, path, size, staged_upload?:{install_id, path, profile_home}}` (`hermes_cli/web_routers/uploads.py`; cap 16 MiB `WEBAPP_ATTACHMENT_MAX_BYTES`, 413 above).
   - `file.attach` — **WS canonical (owner-served, `session_ancillary.py::attach_file`)** `{session_id, name?, staged_upload}` (or `{path}` for a gateway-visible host path, or `{data_url}`) → `{attached, name, path, ref_path, ref_text:"@file:<workspace-relative path>", uploaded}`. Refusals `invalid_params`, `permission_denied` (not attached, or `staged_upload.profile_home` not reachable), `storage_unavailable`.
   - Prepend each `ref_text` to the prompt: `text = refs.join("\n") + "\n\n" + userText` (`submit.ts::buildContextText`). Image-only send with empty text: the Desktop sends `"What do you see in this image?"`.
3. **File references typed by the user**: built-in `@` prefixes `@file:<path>`, `@folder:<path>`, `@diff`, `@staged`, `@git:…`, `@url:<url>` plus plugin prefixes (`agent/context_references.py::BUILTIN_PREFIXES`); values with spaces/brackets are quoted (`tui_gateway/prompt_attachments.py::_format_ref_value`). Completion comes from `complete.path` (owner-served; other slice). The model-side expansion happens on the gateway.
4. **Reading a staged/sent image back** for display: `GET /api/fs/read-data-url?path=…&profile=…&session_id=…` → `{dataUrl}` (≤16 MiB) (`files.py`).

**Legacy-only attachment verbs** (sidecar session table; on a canonical owner they answer "session not found" or `-32601`, unverified per verb): `image.attach {session_id, path}`, `clipboard.paste {session_id}` (host clipboard), `pdf.attach {session_id, path|content_base64|data, filename?, first_page?, last_page?}` → `{attached, filename, pages_attached, pages:[{path, page, …}], count, text}`, `image.detach {session_id, path}` → `{detached, count}`, `input.detect_drop {session_id, text}`, `paste.collapse {text}` → `{placeholder, path, lines}` (large paste spilled to a file; Desktop uses `title_preview` instead). Native: render PDFs to images client-side or send them via `file.attach` as `@file:` refs.

**Notes**: an image admission is immutable: if the owner refuses the submit, re-upload before resending (`submit.ts::unstageRefusedImages`). Only `attachments` images reach the model as vision input; `@image:` refs in the Desktop bubble are display-only.

---

#### Message reactions

**UI**: `components/assistant-ui/thread/message-reactions.tsx`, `use-message-reactions.ts`; stores `store/reactions.ts`, `store/reactions-enabled.ts`, `store/display-toggles.ts`; event `gateway-event/desktop-bridge.ts` (`message.reaction`), hearts `components/chat/vibe-hearts`.

**Calls**
- `message.react` — **WS canonical (owner-served, `gateway/session_reactions.py`)** `{session_id, row_id?:int, newest_role?:"user"|"assistant", emoji?:string|null, author?:"user"|"agent", profile?}`. Address a persisted row by `row_id`; a not-yet-persisted live message by `newest_role` (latest row of that role). `emoji:null` clears; the same emoji again retracts (Tapback). Result `{row_id, reactions:[{emoji, author, at?, seen?}]}`. Refusals `invalid_params`, `not_found`.
- Toggle the feature: `config.set {key:"display.message_reactions", value:"true"|"false", profile?}` → `{key, value:bool}` (profile `config.yaml` write, `gateway/session_tui_config.py::_set_profile_leaf`). Read it via `config.get` / REST config (other slice).

**Events**
- `message.reaction {row_id, reactions:[MessageReaction], role}` — the agent reacted (`tools/react_to_message_tool.py`); repaint that row.
- `reaction {kind}` — core-detected affection in the user's message (`kind:"vibe"`): play hearts for the visible session only.

**Notes**: `row_id` comes from snapshot `messages[].row_id` or `message.complete.persisted_turn` (`user_row_id`, `final_assistant_row_id`).

---

#### Voice and dictation

**UI**: mic `app/chat/composer/hooks/use-mic-recorder.ts`, dictation stream `lib/voice-stream.ts`, conversation `app/chat/composer/hooks/use-composer-voice.ts`, GPT-Live `hooks/use-voice-live-conversation.ts`, `lib/voice-live.ts`, playback `lib/voice-playback.ts`, barge-in `lib/voice-barge-in.ts`, stop word `lib/voice-stop-word.ts`, wake word `store/wake-word.ts`, `lib/wake-client-capture.ts`, REST client `api/system.ts`.

**REST** (`hermes_cli/web_routers/audio.py`; all accept `?profile=`):

| Route | Body | Response | Notes |
|---|---|---|---|
| `POST /api/audio/transcribe` | `{data_url:"data:audio/webm;base64,…", mime_type?}` | `{ok:true, transcript, provider}` | ≤25 MiB; mime `audio/*` or `video/webm`; `transcript:""` = no speech (re-listen, not an error); 400 bad payload, 413 too large. |
| `POST /api/audio/speak` | `{text}` | `{ok:true, data_url:"data:audio/mpeg;base64,…", mime_type, provider}` | Whole-reply TTS fallback. |
| `POST /api/audio/tts-lease` | `{lease:"desktop:conversation", active:bool}` | lease state (unverified shape) | Warm/release the TTS engine; may take minutes (Desktop timeout 180 s). |
| `POST /api/audio/stt-lease` | `{lease:"desktop:voice-input:<id>", active:bool}` | lease state (unverified shape) | Pre-load local STT. |
| `GET /api/audio/voice-config` | — | `{ok:true, stt:{mode:"relay"\|…}, tts:{…}}` | Client-direct STT/TTS config (provider keys for direct calls) or `relay` (use the endpoints here). Gate `voice.client_direct`. |
| `GET /api/audio/voice-live/status` | — | `{ok:true, …}` | Selected voice chat mode (`chained` \| `gpt-live`) and whether GPT-Live can start. |
| `POST /api/audio/voice-live/session` | `{sdp, history?}` | `{ok:true, …SDP answer, session id}` | WebRTC GPT-Live; 503/502 on config/vendor errors. |
| `GET /api/audio/elevenlabs/voices` | — | voice list | Settings. |

**sibling WS — live dictation** `/api/audio/transcribe-stream`:
1. `POST /api/webapp/socket-ticket?socket=transcribe-stream` → `{ticket, ttl_seconds}` (one-use, this socket only; `hermes_cli/web_routers/webapp.py`).
2. Dial `wss://<host>/api/audio/transcribe-stream?ticket=<t>&profile=<p>`. Close `4401` = bad ticket, `4403` = host/origin refused.
3. Client: first text frame `{"sample_rate":48000}` (8000-48000, the capture rate; the host resamples); then binary frames of s16le mono PCM (Desktop: 4096-sample chunks from the AudioContext); then `{"eos":true}`. Disconnect = cancel.
4. Server: `{"type":"partial","text":"…"}` while speaking; `{"type":"final","transcript":"…","provider":"…"}` then close; or `{"type":"error","message":"…"}` (also immediately when the provider has no live STT) → fall back to `POST /api/audio/transcribe` with the recorded blob (Desktop records a MediaRecorder blob in parallel: `audio/webm;codecs=opus` preferred).

**sibling WS — streaming TTS** `/api/audio/speak-stream` (one socket per reply):
1. Ticket `socket=speak-stream`; dial `…/api/audio/speak-stream?ticket=<t>&profile=<p>`.
2. Client: `{"text":"<delta>"}` frames as `message.delta` text arrives (may combine with `done`), `{"done":true}` at `message.complete`, `{"stop":true}` (or disconnect) = barge-in.
3. Server: `{"type":"start","sample_rate":N,"channels":1}` (sent with the first PCM chunk), binary int16 PCM frames, then `{"type":"end"}`; or `{"type":"fallback"}` when synthesis produced no audio → POST the whole reply to `/api/audio/speak`. The server cuts sentences itself and flushes after ~2 s of idle; markdown is stripped.

**RPC** (host-audio voice, TUI parity — **WS legacy** sidecar handlers in `tui_gateway/methods_voice.py`; the Desktop does not use `voice.*`; they drive the gateway HOST's microphone/speaker, so a phone client should not):
- `voice.toggle {action:"status"|"on"|"off"|"tts", profile?}` → `{enabled, record_key, tts, stop_hint?, available?, audio_available?, stt_available?, details?}`
- `voice.record {action:"start"|"stop", session_id?, profile?}` → `{status:"recording"|"stopped"|"busy", reason?:"wake_owned"}`
- `voice.tts {text, profile?}` → `{status:"speaking"}`
- Events: `voice.status {state}`, `voice.partial {text}`, `voice.transcript {text?, stop_phrase?, typed?, no_speech_limit?}`, `voice.interrupted` (no payload).

**Wake word** (**WS legacy** fallback; used by Desktop): `wake.status {surface:"gui", client_capture:true, profile?}` → `WakeStatusResult {listening, owned_by_caller, phrase, provider, enabled, available, hint, audio_silent, capture, local_input_available, sample_rate, frame_length, …}`; `wake.start {surface:"gui", persist?:true, client_capture:true, session_id?}` → `{started, reason?:"unavailable"|"disabled"|"disabled_for_surface"|"owned", sample_rate, frame_length, …}`; `wake.feed {pcm:"<base64 int16 mono LE 16 kHz>", sample_rate?}` → `{fed, reason?:"empty"|"not_owner"}` (client capture; coalesce frames); `wake.pause` / `wake.resume` → `{paused}` / `{resumed}`; `wake.stop {persist?}` → `{stopped, disabled_persisted}`. Event `wake.detected {phrase, profile?, start_new_session}` (start a new chat when true).

**Voice chat mode** (`config.set {key:"voice.voice_chat_mode", value:"chained"|"gpt-live"}` → `{key, value}`):
- **chained** (default): loop = mic with VAD → dictation (stream or REST) → `prompt.submit {text, submission_id, voice_turn:true, interrupted?:true}` (`interrupted` when the user barged in over playback) → speak the reply through `speak-stream` fed from `message.delta` → listen again. A bare stop phrase typed or submitted while voice chat is on returns `{voice_stopped:true}` from `prompt.submit` / `session.steer` (chat ended, nothing admitted). Wake/stop words are matched client-side too (`lib/voice-stop-word.ts`).
- **gpt-live**: WebRTC session via `/api/audio/voice-live/session`; each vendor `session.delegation.created` becomes `prompt.submit {text, surface:"voice-live", voice_context:"<recent spoken exchange>"}` on the session (`use-voice-live-conversation.ts`).
- HUD typing (floating window): `surface:"hud"`.

---

#### Gaps and uncertainties

- Exact interleaving of the `prompt.submit` result vs `message.start`, and of `approval.settled` vs the `approval.respond` result, is not fixed by code (unverified).
- `approval.received` timeout semantics: contract says it starts the clock; code only records the ack (unverified).
- `request.cancel` emission on canonical interrupt for owner server requests (unverified); canonical approvals/clarify use `*.settled` instead.
- Legacy-only verbs (`pdf.attach`, `image.attach`, `image.detach`, `clipboard.paste`, `input.detect_drop`, `paste.collapse`, `voice.*`, `spawn_tree.*`, `wake.*`) on a canonical gateway: fallback is allowed by `ws_legacy_fallback.py`, but session-scoped ones likely answer "session not found" for owner sessions (unverified per verb). `wake.*` is sessionless and the Desktop calls it, so it presumably works through the fallback (unverified live).
- `edit` object shape in edit approvals, `clarify_id` format, and `tts-lease`/`stt-lease`/`voice-live` response bodies were not traced (unverified).
- `agent.step` and the canonical `message.complete` extras (`content`, `admission_id`, `outcome`) are published but not in `tui_gateway/contracts/events.py`; generate models leniently (ignore unknown keys).

### 2.3 Commands, models and settings

Source: `hermes-agent` main @ 9b904dcc22. Paths are relative to the repo root. Type names refer to `app/docs/contract/gateway-contract.generated.ts`. Transport labels:
- **WS canonical**: served by the session authority (`gateway/session_controls.py::handlers`, which also merges `session_busy_controls`, `session_config`, `session_tui_config`, `session_ancillary`, `session_tui_ops`, `session_tui_side`, `session_onboarding`, `session_complete_path`).
- **WS legacy**: the authority answers `-32601` and `tui_gateway/ws_legacy_fallback.py` hands the call to the `tui_gateway/server.py` handler.
- **REST**: FastAPI routes in `hermes_cli/web_routers/*.py`.

#### Ground rules for this slice

- **The canonical handler set is wider than `CanonicalRpcMethods`.** The authority also serves these methods, with no legacy fallback:
  - discovery: `commands.catalog`, `complete.slash`, `complete.path`, `plugins.list`
  - commands: `slash.exec`, `command.dispatch`, `command.observe`
  - models and setup: `model.options`, `setup.status`, `setup.runtime_check`
  - onboarding: `onboarding.start`, `onboarding.skip`
  - reloads and side tasks: `reload.env`, `reload.mcp`, `skills.reload`, `rollback.*`, `prompt.btw`, `prompt.background`, `llm.oneshot`, `session.save`, `session.history`
  - config: `config.get`, `config.set`

  Each canonical handler runs its own closed parameter check. Unknown keys or non-string values answer `4001 invalid_params`. An unknown `config.get` key does **not** fall back to legacy.
- **Legacy fallback** (`ws_legacy_fallback.py::legacy_fallback_allowed`) applies only when:
  - the connection holds the full interactive grant;
  - the method is outside `session.`, `prompt.` and `message.`. The allowlist exception is `session.most_recent|active_list|events.stats|foreign.*`;
  - the method is not process-global: `process.stop`, `delegation.pause`, `reload.mcp`, `reload.env`, `agents.list`.

  `session.status` (Desktop `/status`) therefore answers `-32601` on the shared gateway, and the Desktop falls back to `slash.exec "/status"`.
- **Error frames.** Canonical: `{"code":4001,"message":"<reason>","data":{"reason":..,"field"?,"detail"?}}`, `-32601` for an unknown method, `5001 storage_unavailable`. Legacy: free-text messages with codes such as:
  - `4002 unknown config key: X`
  - `4018 not a quick/plugin/bundle/skill command: X`
  - `4011 unknown command`
  - `5020` catalog failure
  - `4064` profile unavailable
- **Profile routing.**
  - **WS**: add `"profile":"<name>"` to sessionless params. Canonical handlers require it to match the authority's profile home (`profile_matches_home`), or they answer `profile_mismatch`. A different profile needs its own socket: `/api/ws?profile=<name>` with a ticket from `POST /api/webapp/socket-ticket`, per `lib/browser-transport.ts::gatewaySocketUrl`. Legacy handlers are `@_profile_scoped`: their `profile` comes from the session's `profile_home` or the param.
  - **REST**: `?profile=<name>`, or `"profile"` in the body. The body field wins on writes (`ConfigUpdate.profile`, `EnvVarUpdate.profile`, `ModelAssignment.profile`).
- **Fences** (`expected_revision`, `expected_generation`, `request_id`) for `session.mutate` are defined in `10-sessions.md`. This slice only uses operations `model` and `reasoning`.

---

#### Slash command catalog (`commands.catalog`)

**UI**
- Composer slash popover: `apps/desktop/src/app/chat/composer/hooks/use-slash-completions.ts`.
- `/help` renderer: `use-prompt-actions/slash.ts` `help` handler.
- Offline fallback: `apps/desktop/src/lib/desktop-slash-registry.json`, a dump of `hermes_cli/commands.py::desktop_surface_registry()`.

**Calls:** `commands.catalog` (WS canonical; `gateway/session_discovery.py::discover_commands`). Legacy twin: `tui_gateway/methods_tools.py` (`@_rpc("commands.catalog", 5020)`, which also accepts `cwd`).

| param | type | req | notes |
|---|---|---|---|
| `session_id` | string | opt | Binds project-local skills to that session's workspace. Requires `session:read` on the session. |
| `profile` | string | opt | Scopes a session-less draft. Must match the authority's profile. |

Canonical: only these two keys, and both must be strings. The connection needs `session:read`.

Result `CommandsCatalogResult` (`tui_gateway/command_discovery.py::command_catalog`):
- `pairs: [key, description][]`. Covers every listed command: registry, TUI extras, quick commands, plugin commands **and** skills. Skills are appended to `pairs` only, never to `categories`. A description includes the localized usage suffix when the command has `args_hint`.
- `categories: {name, pairs}[]`, in registry category order:
  - `Session`, `Configuration`, `Info`, `Tools & Skills`, `Exit`, `TUI`
  - `User commands`: `quick_commands` from config
  - `Plugin commands`

  Not listed: `gateway_only` commands and `sethome|set-home|commands|approve|deny`.
- `canon: {"/alias-lowercase": "/canonical"}`.
- `commands: {"/name": CommandCatalogMeta}` covers **every** registry name and alias, including `gateway_only` ones, plus plugin commands. Fields:
  - `argument_mode`: `'options'|'text'|'mixed'|null`
  - `desktop`: `null|'hidden'|'terminal'|'advanced'|'composer-voice'|'settings'|'messaging'`
  - `desktop_subcommands?`: `string[]`
- `sub: {"/name": string[]}`. Taken from `subcommands`, else from a pipe pattern in `args_hint` (`[on|off|status]`).
- `skills: {"/skill": {usage:int, origin:'hub'|'bundled'|'local'}}`, `skill_count`.
- `warning`: the first discovery failure (skills, then quick commands, then plugins). Otherwise the notice about skills whose name collides with a built-in. `""` when there is nothing to report.
- `plugin_discovery_error`: `""` when plugin discovery worked.
- `plugin_commands: {name: description}`.

`argument_mode` inference (`commands.py::infer_argument_mode`):
- An explicit value wins.
- Otherwise, a command with `subcommands` is `mixed` when the hint contains `<prompt>`, `[text`, `instructions`, `[interval]` or `<what`, else `options`.
- Otherwise, any `args_hint` means `text`.
- Otherwise `null`.

```json
// → {"jsonrpc":"2.0","id":11,"method":"commands.catalog","params":{"session_id":"local-9f…"}}
// ← result (abridged)
{
  "pairs": [["/new","Start a new session (fresh session ID + history) …[name]"],["/compress","Compress conversation context …"],["/work","Do focused work on a task"]],
  "categories": [{"name":"Session","pairs":[["/new","…"],["/compress","…"]]},{"name":"User commands","pairs":[["/deploy","exec: ./deploy.sh"]]}],
  "canon": {"/new":"/new","/reset":"/new","/compact":"/compress"},
  "commands": {
    "/new":{"argument_mode":"text","desktop":null},
    "/model":{"argument_mode":"text","desktop":"hidden"},
    "/clear":{"argument_mode":null,"desktop":"terminal"},
    "/skills":{"argument_mode":"options","desktop":null,"desktop_subcommands":["pending","approve","reject","diff","approval"]}
  },
  "sub": {"/reasoning":["none","minimal","low","medium","high","xhigh","max","ultra","show","hide","on","off","full","clamp","--global"]},
  "skills": {"/work":{"usage":4,"origin":"local"}}, "skill_count": 1,
  "warning": "", "plugin_discovery_error": "", "plugin_commands": {}
}
```

**Events:** `skills.changed` (`ChangeSignalPayload {}`) tells the client to refetch `commands.catalog`.

**Notes**
- Descriptions follow the active language (`slash.<name>.description`). Category names stay English identifiers.
- A listed command is not necessarily executable on the canonical gateway. See "Slash execution".

#### Slash completion (`complete.slash`)

**UI:** `app/chat/composer/hooks/use-slash-completions.ts`, `completion-drawer.tsx`, `trigger-popover.tsx`.

**Calls:** `complete.slash` (WS canonical; `session_discovery.discover_commands(completion=True)`). Params: `{text: string (the whole composer text starting with "/"), session_id?: string, profile?: string}`. All must be strings.

Result `CompleteSlashResult`:
- `items: {text, display, meta, kind: 'command'|'skill'}[]`
- `replace_from`: `1` at the command-token stage, otherwise the index after the last space

Ranking (`command_discovery.slash_completions`):
- Registry commands keep registry order.
- Skills rank by fuzzy score, then usage, then A-Z.
- The limit is 30 commands plus 30 skills.
- Bare `/` (browsing) drops never-used bundled skills.
- A typed token also merges substring and description matches (`slash_fuzzy`).
- At the argument stage (`/personality `) the command's own order is kept, cut at 30.
- `/details …` gets its own section/mode completions.
- Non-`/` text answers `{items:[]}`.

```json
// → {"method":"complete.slash","params":{"text":"/comp","session_id":"local-9f…"}}
// ← {"items":[{"text":"/compress","display":"/compress","meta":"Compress conversation context …","kind":"command"}],"replace_from":1}
// → {"params":{"text":"/personality alic"}}
// ← {"items":[{"text":"alice","display":"alice","meta":"…","kind":"command"}],"replace_from":13}
```

**Notes**
- The Desktop rewrites argument-stage items (`replace_from > 1`) into full tokens (`/personality alice`).
- It caches per `(owner, session|profile, text)`.
- It filters rows through `isDesktopSlashSuggestion`, which hides the `terminal`/`advanced`/… dispositions and `hidden`, and through `filterDesktopSubcommandCompletions` (the `desktop_subcommands` allowlist).
- Whether command-stage `text` carries the leading `/` comes from the prompt_toolkit completer (unverified). Use `replace_from`.

#### Path / `@` completion (`complete.path`)

**UI:** `app/chat/composer/hooks/use-at-completions.ts`, `path-refs.ts`.

**Calls:** `complete.path` (WS canonical; `gateway/session_complete_path.py`). Params: `{word: string ("@foo" | "@file:" | "@folder:" …), session_id?: string, cwd?: string, profile?: string}`. All must be strings.

Result `{items: CompletionItem[]}`.

**Notes**
- Canonical behavior:
  - Without `session_id` it answers `[]`.
  - `cwd` is accepted but ignored. The root is the session's frozen policy cwd.
  - When the session's terminal backend is not host-local (SSH, sandbox), it answers `[]`.
  - An unknown session answers `not_found`.
- Legacy behavior: the root is `cwd` or the session cwd. Items also cover profile mentions and plugin providers.

#### Slash execution (`slash.exec`, `command.dispatch`) — canonical rules

**UI:** `app/session/hooks/use-prompt-actions/slash.ts` (`runSlash` → `runExec`), `apps/shared/src/slash.ts::parseCommandDispatch`.

**Calls** (WS canonical; `gateway/session_commands.py::execute_command`):
- `slash.exec {session_id: string (req), command: string (req; "/name args" or "name args"), profile?: string}`.
- `command.dispatch {session_id: string (req), name: string (req; one token, no args), arg?: string, profile?: string}`.

Any other key or a non-string value answers `invalid_params`. An unbalanced quote in `arg` answers `invalid_params` with `field:"arg"`.

Result `CommandDispatchResult` / `SlashExecResult`. `type` is one of `'exec'|'alias'|'plugin'|'send'|'skill'|'prefill'`:
- `exec`: `{type:"exec", output}`. Built-in read/control, info reads, cron, quick `exec`, plugin commands.
- `send`: `{type:"send", message, display, notice?}`. Comes from prompt-rewriting commands (`/plan`, `/learn`, `/init`, `/moa`, `/blueprint`, `/egress`) and handlers that queue a turn (`/goal <text>`). **The client submits `message` itself** through `prompt.submit` with its own `submission_id`, and shows `display`. `notice` is a system line shown first.
- `skill`: `{type:"skill", name, message, display, notice?}`. A profile/project skill, or stacked skills `/a /b do X`. Same client duty: submit `message`, render `display`, never render `message`.
- `alias` / `prefill` / `plugin`: legacy only.
  - `alias {target}`: the client re-runs `/<target> <arg>`.
  - `prefill {message}`: put it in the composer (legacy `/undo`).
  - `plugin {output}`.

**Execution verdicts** (`session_commands.py::command_verdict`; capability checked by `authority.authorize`):

| verdict | capability | commands |
|---|---|---|
| read | `session:read` | `help`, `commands`, `status`, `context`, `version`, `whoami`, `insights`, `profile`, `diff`, `topup`, `bundles`, `toolsets`, `platforms` |
| read (argument-limited) | `session:read` | `usage` (bare only); `suggestions` (bare only); `memory` (bare\|`pending`); `skills` (bare\|`pending`\|`diff`); `kanban` (`list ls show stats assignees context runs log diagnostics diag help`); `cron` bare\|`list` |
| control | `session:control` | `title`; handler controls `goal subgoal loop heartbeat review refine debug curator login autoprune`; prompt rewrites `plan learn init moa blueprint egress` (→ `send`); per-session settings `reasoning voice approvals codex-runtime footer busy verbose yolo fast personality`; `memory`/`skills` `approve apply reject deny drop approval mode` |
| approve | `session:approve` | `approve`, `deny` (resolve this session's waiting approval; viewers receive `approval.settled`) |
| refused | — | everything else, including `voice tts`, `model`, `new`, `compress`, `undo`, `retry`, `stop`, `branch`, `rollback`, `save`, `queue`, `steer`, `bg`, `btw`, `resume`, `reload-mcp`, `reload-skills`, `update`, `restart`… → `unsupported_command` |

**Other routing rules**
- Quick commands:
  - `type: alias` resolves recursively (a loop answers `invalid_params`).
  - `type: exec` needs `session:control`. It runs in the session's policy cwd (`cwd_unavailable` if that cwd is missing) with a sanitized env, a 30 s limit and redacted output.
- Plugin slash commands need `session:control` and run under the session's env.
- An unknown name resolves as a skill. That needs `session:submit` and returns the `skill` directive. Unknown answers `unsupported_command`. An empty skill body answers `command_unavailable`.
- Mid-turn, the registry `busy_policy` applies (`_dispatch_busy_slash_command`).
- A history-mount session answers `read_only`. A non-read command needs admission open (it is refused while draining).
- Cron writes are refused when the caller's connection is subscribed to the session. They are allowed only from the classic CLI's command socket.
- `unsupported_command` carries a hint in `data.detail` for:
  - `export`: `hermes profile export …`
  - `import`: `hermes profile import …`
  - `snapshot`: `hermes backup --quick`
  - `handoff`: `hermes --cli --resume <session>`, then `/handoff`
  - `hatch`: `hermes --cli`, then `/hatch`
  - `worktree`: `hermes -w`
  - `palette`: "Type / to search commands"
- `pre_command` hooks fire once for an accepted recognized command (`command.observe` is the fire-only twin used by the classic CLI and Ink).

```json
// → {"method":"slash.exec","params":{"session_id":"local-9f…","command":"context"}}
// ← {"type":"exec","output":"Context: 41,203 / 200,000 tokens …"}
// → {"method":"command.dispatch","params":{"session_id":"local-9f…","name":"work","arg":"fix the login bug"}}
// ← {"type":"skill","name":"work","message":"<expanded skill body …>","display":"/work fix the login bug"}
// → {"method":"slash.exec","params":{"session_id":"local-9f…","command":"export"}}
// ← {"error":{"code":4001,"message":"unsupported_command","data":{"reason":"unsupported_command","detail":"Run `hermes profile export [name] -o <file>` in a terminal."}}}
```

**Desktop translation before the wire** (`api/canonical-protocol.ts::slashMutation`):
- `slash.exec "/model <name>"` (no flags) becomes `session.mutate {operation:"model", payload:{model}}`.
- `"/branch [title]"` becomes `branch {title}`.
- `"/compress [focus]"` becomes `compress {focus}`.
- The receipt comes back as `{type:"exec", output:"model: X (provider)"}`.
- `/model` with flags (`--global`, `--provider`, `--refresh`) stays `slash.exec` and is refused.

**Desktop client flow:**
1. Run `slash.exec`.
2. `parseCommandDispatch(result)` gives typed directives. Otherwise render `output`, prefixed with `warning:` when present.
3. If `slash.exec` throws, fall back to `command.dispatch {session_id, name, arg}`.
4. If the dispatch error matches `/not a quick\/plugin\/(bundle\/)?skill command/`, show the original `slash.exec` error instead.

A guarded `/model` (`confirm_required`) opens the model-switch confirm dialog and resends with `confirm_expensive_model:true`. On canonical that becomes the `payload.confirm` token (see Model switch).

**Legacy** (`methods_tools.py`): `slash.exec` runs in the `_SlashWorker` HermesCLI subprocess, with live mirrors for `model`, `approvals`, `personality`, `compress`, `fast`, `reload-mcp`, `stop` and `autoprune` (`methods_slash.py::_SLASH_MIRRORS`). `model`, `personality` and `compress` are refused while running.

#### Desktop-owned built-ins (`apps/desktop/src/lib/desktop-slash-commands.ts`)

Surface kinds: `action` (local handler), `picker`, `rpc`, `exec` (backend), `unavailable` (reason). Local specs win over catalog metadata. Registry commands with no local row are `exec` when `desktop` is `null`, `unavailable` otherwise. `hidden` stays executable but out of the popover.

| command (aliases) | surface | backend calls (transport) |
|---|---|---|
| `/new` (`/reset`, `/clear`) | action | none until first send → `session.create` (canonical) |
| `/stop` | action | `session.interrupt` + `process.stop {session_id}` (canonical) |
| `/branch` (`/fork`) | action | `session.branch` → `session.mutate branch` |
| `/yolo` | action | `config.set {key:"yolo", session_id, value:"1"\|"0"}` (canonical). Without a session: armed locally, applied at create. |
| `/reasoning [lvl\|show\|hide\|full\|clamp] [--global\|--session]` | action, options | bare: `config.get {key:"reasoning", session_id}` → `{value, display}`; else `config.set {key:"reasoning", session_id, value, scope?}`. A session-scope level becomes `session.mutate reasoning`. |
| `/wake [on\|off\|status]` | action | `wake.status`/`wake.start`/`wake.stop {persist:true}` (legacy) |
| `/handoff <platform>` | action | `handoff.request`/`handoff.state`/`handoff.fail` (legacy) |
| `/profile` | action | client profile switch |
| `/skin [name]` | action | client theme only (no backend write) |
| `/title <name>` | action, text | `session.title` → `session.mutate rename` |
| `/help` (`/commands`) | action | `commands.catalog {session_id}` rendered locally |
| `/browser [connect\|disconnect\|status]` | action | `browser.manage` (legacy) |
| `/journey` (`/learning`, `/memory-graph`) | action | opens memory graph (REST `/api/learning/graph`) |
| `/model` | picker, hidden | model picker (below) |
| `/resume` (`/sessions`, `/switch`) | picker, mixed | session picker |
| `/compress` (`/compact`) | action, text | `session.compress` → `session.mutate compress {focus?}` |
| `/btw <q>` | action | `prompt.btw` (canonical); answer arrives as `btw.complete` |
| `/bg` (`/background`) | action | `prompt.background` (canonical) |
| `/undo [N]`, `/retry` | action | `session.rewind` → `session.mutate rewind` (+ resend) |
| `/steer` (`/s`) | action | `session.steer` (canonical) |
| `/queue` (`/q`) | action | client next-turn queue → `prompt.submit` |
| `/pet`, `/hatch` (`/generate-pet`) | action | `pet.*` (legacy) / generator overlay |
| `/rollback [list\|diff <n>\|<n> [file] [--all]]` | action | `rollback.list`/`rollback.diff {hash}`/`rollback.restore` (canonical) |
| `/agents` (`/tasks`) | action | `subagent.list` + `process.list {session_id}` (canonical) |
| `/config` | action | `config.get {key:"full", session_id}` |
| `/history` | action | `session.resume` snapshot |
| `/save [fmt] [file] [redact]` | action | `session.export` (canonical) then a local file write (fallback `session.save`) |
| `/export`, `/import` | action | profile share flow (REST `/api/profiles/{name}/export`, `/api/profiles/import`); refused text on a remote gateway |
| `/timestamps` (`/ts`) | action | client-only |
| `/palette` | action | opens the command palette |
| `/status` | rpc `session.status` | legacy. On canonical: `-32601` → falls back to `slash.exec "/status"`. |
| `/skills` | exec, `desktopSubcommands` `pending approve reject diff approval` | `slash.exec` |
| `/density`, `/details`, `/logs`, `/mouse` | unavailable (terminal) | TS-only |
| `/pets` | unavailable (settings) | TS-only |

Unavailable messages:
- `terminal`: "only available in the terminal interface"
- `advanced`: "not shown in the desktop slash palette…"
- `settings`: "managed from the desktop sidebar"
- `messaging`: "only used from messaging platforms"
- `composer-voice`: use the mic button

Every typed command also fires `shared_metrics.slash_command {command: name, session_id?}` (WS legacy, fire-and-forget).

#### Command registry reference (`hermes_cli/commands.py::COMMAND_REGISTRY`)

`CommandDef` fields:
- `name`, `description`, `category`, `aliases`, `args_hint`, `subcommands`
- `cli_only`, `gateway_only`, `gateway_config_gate`
- `busy_policy` (`dispatch`\|`reject`\|`interrupt_then_dispatch`), `busy_handler`
- `execute`, `argument_mode`
- `desktop` (`None` = offered, `"hidden"`, or a reason), `desktop_subcommands`

Table columns:
- **scope**: `cli` = cli_only; `gw` = gateway_only.
- **desktop**: the catalog `desktop` value.
- **mode**: the inferred `argument_mode`.
- **desktop surface**: the local spec, otherwise `exec`.
- **canonical**: the `slash.exec` verdict on the shared gateway. `*` means argument-limited.

| cmd | aliases | scope | desktop | mode | desktop surface | canonical |
|---|---|---|---|---|---|---|
| /start | | gw | - | - | exec | refused |
| /new | /reset | | - | text | action | refused |
| /topic | | gw | - | text | exec | refused |
| /clear | | cli | terminal | - | action | refused |
| /redraw | | cli | terminal | - | exec | refused |
| /history | | cli | - | - | action | refused |
| /save | | | - | text | action | refused |
| /retry | | | - | - | action | refused |
| /prompt | /compose | cli | - | text | exec | refused |
| /undo | | | - | text | action | refused |
| /title | | | - | text | action | control |
| /handoff | | cli | - | options | action | refused |
| /branch | /fork | | - | text | action | refused |
| /worktree | | cli | terminal | options | exec | refused |
| /compress | /compact | | - | text | action | refused |
| /rollback | | | - | text | action | refused |
| /snapshot | /snap | cli | terminal | text | exec | refused |
| /export | | cli | - | text | action | refused |
| /import | | cli | - | text | action | refused |
| /stop | | | - | - | action | refused |
| /discard | | gw | - | - | exec | refused |
| /pause | | gw | - | text | exec | refused |
| /approve | | gw | - | text | exec | approve |
| /deny | | gw | - | text | exec | approve |
| /bg | | | - | text | action | refused |
| /btw | | | - | text | action | refused |
| /agents | /tasks | | - | - | action | refused |
| /journey | /learning,/memory-graph | cli | - | options | action | refused |
| /queue | /q | | - | mixed | action | refused |
| /steer | /s | | - | text | action | refused |
| /goal | | | - | mixed | exec | control |
| /heartbeat | /hb | | - | mixed | exec | control |
| /refine | | | - | text | exec | control |
| /review | | | - | text | exec | control |
| /loop | /proactive | | - | mixed | exec | control |
| /plan | | | - | text | exec | control (send) |
| /moa | | | - | text | exec | control (send) |
| /subgoal | | | - | text | exec | control |
| /status | | | - | - | rpc | read |
| /egress | | | - | options | exec | control (send) |
| /context | /ctx | | - | options | exec | read |
| /whoami | | | - | - | exec | read |
| /profile | | | - | - | action | read |
| /sethome | /set-home | gw | terminal | - | exec | refused |
| /resume | | | - | mixed | picker | refused |
| /sessions | | | - | - | picker | refused |
| /config | | cli | - | - | action | refused |
| /model | | | hidden | text | picker | refused |
| /codex-runtime | /codex_runtime | | - | text | exec | control |
| /personality | | | - | options | exec | control |
| /statusbar | /sb | cli | terminal | - | exec | refused |
| /battery | | cli | - | options | exec | refused |
| /timestamps | /ts | cli | - | options | action | refused |
| /diff | | | - | options | exec | read |
| /verbose | | cli | terminal | - | exec | control |
| /focus | | cli | - | options | exec | refused |
| /footer | | | terminal | options | exec | control |
| /yolo | | | - | - | action | control |
| /approvals | | | - | options | exec | control |
| /reasoning | | | - | options | action | control |
| /fast | | | advanced | options | exec | control |
| /skin | | cli | - | options | action | refused |
| /indicator | | cli | terminal | options | exec | refused |
| /voice | | | composer-voice | options | exec | control (`tts` refused) |
| /wake | | cli | - | options | action | refused |
| /busy | | | terminal | options | exec | control |
| /autoprune | | | - | options | exec | control |
| /tools | | cli | - | options | exec | refused |
| /toolsets | | cli | terminal | - | exec | read |
| /skills | | cli | - | options | exec* | read*/control |
| /memory | | | - | options | exec | read*/control |
| /bundles | | | - | - | exec | read |
| /pet | | cli | - | options | action | refused |
| /hatch | /generate-pet | cli | - | text | action | refused |
| /learn | | | - | text | exec | control (send) |
| /init | | | - | text | exec | control (send) |
| /cron | | cli | terminal | options | exec | read / control (owner socket only) |
| /suggestions | /suggest | | - | options | exec | read* |
| /blueprint | /bp | | - | text | exec | control (send) |
| /curator | | | advanced | options | exec | control |
| /kanban | | | advanced | options | exec | read* |
| /reload | | cli | terminal | - | exec | refused |
| /reload-mcp | /reload_mcp | | advanced | - | exec | refused |
| /reload-skills | /reload_skills | | advanced | - | exec | refused |
| /browser | | cli | - | options | action | refused |
| /plugins | | cli | terminal | - | exec | refused |
| /commands | | gw | - | text | action | read |
| /help | | | - | text | action | read |
| /palette | | cli | - | - | action | refused |
| /restart | | gw | terminal | - | exec | refused |
| /usage | | | - | text | exec | read* |
| /subscription | /upgrade | cli | - | - | exec | refused |
| /login | | | settings | - | exec | control |
| /topup | | | - | - | exec | read |
| /insights | | | advanced | text | exec | read |
| /platforms | /gateway | cli | terminal | - | exec | read |
| /platform | | gw | - | text | exec | refused |
| /copy | | cli | terminal | text | exec | refused |
| /paste | | cli | terminal | - | exec | refused |
| /image | | cli | terminal | text | exec | refused |
| /update | | | terminal | - | exec | refused |
| /version | /v | | - | - | exec | read |
| /debug | | | - | text | exec | control |
| /quit | /exit | cli | terminal | text | exec | refused |

Subcommand lists, for option chips:
- `/reasoning`: `none minimal low medium high xhigh max ultra show hide on off full clamp --global`
- `/fast`: `normal fast auto cold ultrafast ultra status on off --global`
- `/approvals`: `manual smart off`
- `/busy`: `queue steer interrupt status`
- `/voice`: `on off tts status`
- `/autoprune`: `on off later status`
- `/queue`: `list edit rm move clear add`
- `/heartbeat`: `status pause resume clear`
- `/egress`: `status`
- `/context`: `all`
- `/diff`: `staged all session`
- `/memory`: `pending approve reject approval`
- `/suggestions`: `accept dismiss catalog clear`
- `/curator`: `status run pause resume pin unpin restore list-archived`
- `/kanban`: 30+ verbs, see the registry

**Other command RPCs**
- `command.resolve {name, profile?}` → `{canonical, description, category}` (WS legacy; code 4011 when unknown).
- `command.observe {session_id, name, arg?, profile?}` → `{queued}` (WS canonical; fires `pre_command` only; the classic CLI and Ink use it).
- `cli.exec {argv: string[], timeout?: ≤600 s, profile?}` → `{blocked, code, output, hint?}` (WS legacy; runs `hermes <argv>`; blocklisted argv answers `blocked:true`). The Desktop uses it only in the bots plugin.

#### Command palette, command center, quick entry

**UI**
- `app/command-palette/index.tsx` (⌘K / ⌘P). Sub-pages: `theme`, `install-theme` (`marketplace-theme-page.tsx`), `pets` (`pet-palette-page.tsx`).
- `app/command-center/index.tsx` (⌘.), with `maintenance.tsx`.
- `app/quick-entry/quick-entry-app.tsx`.

**Calls**
- **Palette.** Rows are assembled client-side from navigation, contributed registry commands (`contrib`), settings search, themes, color mode, pinned and recent sessions, archived chats, MCP server names from config, and plugins. It does **not** list slash commands. Server queries, all REST:
  - `GET /api/config` (settings fields + MCP names)
  - `GET /api/config/schema` + `GET /api/env` (deep settings search: `use-settings-search.ts`)
  - `listAllProfileSessions(200, …)` → `/api/profiles/sessions` (sessions slice), for recent (`exclude` archived) and archived (`only`)

  Theme rows are local (`setTheme`). Marketplace themes go through the Electron bridge `themes.searchMarketplace`/`fetchMarketplace`, which is stubbed in a browser.
- **Command center**, all REST:
  - `GET /api/status`
  - `GET /api/logs?…`
  - `GET /api/analytics/usage?days=N`
  - `POST /api/gateway/restart` → `{ok, pid, name:"gateway-restart"}`
  - `POST /api/hermes/update`, `GET /api/actions/{name}/status?lines=N`
  - Maintenance: `GET /api/curator`, `PUT /api/curator/paused {paused}`, `POST /api/curator/run`; `GET /api/memory`, `POST /api/memory/reset {target: all|memory|user}`; `POST /api/ops/backup`, `POST /api/ops/doctor`, `POST /api/ops/security-audit`, `POST /api/ops/debug-share`
- **Quick entry.** A native Electron window. `hermesDesktop.quickEntry.submit(text, target)` hands the text to the primary renderer's normal `prompt.submit` path. There is no direct backend call. Settings come from `hermesDesktop.quickEntry` (native only).

#### Keybinds that reach the backend (`apps/desktop/src/lib/keybinds/actions.ts`)

All bindings are stored client-side (`store/keybinds`). The ones that trigger backend calls:
- `composer.modelPicker` (mod+shift+M) opens the picker: `model.options`, then `config.set model` → `session.mutate model`.
- `composer.reasoningUp`/`Down` (no default): `config.set {key:"reasoning", session_id, value}` → `session.mutate reasoning`.
- `composer.send`/`composer.steer` (Enter while busy, per busy mode)/`composer.queue` (mod+Enter)/`composer.sendQueued` (mod+shift+K): `prompt.submit`, `session.steer`, `prompt.steer_queued`/`prompt.replace_queued` (sessions slice).
- `session.new` (mod+N)/`session.newTab` (mod+T): `session.create` on the first send.
- `session.archive`, `session.togglePin`: sessions slice (`session.mutate archive` / REST pin).
- `composer.voice`/`composer.dictate`: voice slice.
- `profile.*`: client profile switch.
- Approval keys (`lib/keybinds/approval-keys.ts`): `approval.respond`.
- Status-bar zap: click → `config.set {key:"yolo", session_id, value}`. Shift+click → `config.set {key:"yolo", session_id, scope:"global", value}` (a global flip of `approvals.mode` between `off` and `manual`).

---

#### Model picker (composer)

**UI**
- `app/chat/composer/model-pill.tsx` (opens `setModelPickerOpen`), `reasoning-pill.tsx`.
- `app/session/hooks/use-model-controls.ts`.
- Shell model menu (`shell.modelMenu.*`), `store/model-presets.ts`, `lib/model-options.ts::requestModelOptions`.

**Calls**
1. **List models.** `model.options` (WS canonical; `gateway/session_config.py::model_options`). Params: `{session_id?: string, profile?: string, explicit_only?: bool, include_unconfigured?: bool, refresh?: bool}`. The flags must be real booleans. The Desktop sends `explicit_only:true`, plus `session_id` when one exists. REST twin: `GET /api/model/options?refresh=1&include_unconfigured=1&explicit_only=1&profile=` (503 "Restart required: …" on code skew).
2. **Pick on an open session.**
   - Desktop legacy shape: `config.set {key:"model", session_id, value:"<model> --provider <slug>[ --session]", confirm_expensive_model?}`.
   - Canonical wire: `session.mutate {session_id, request_id, expected_revision, expected_generation, operation:"model", payload:{model, provider, reasoning?, confirm?}}`. Canonical `config.set key=model` answers `use_session_mutation_model`.
   - Payload rules (`hermes_state_mutations.validate_action`): keys are only `model`, `provider`, `confirm`, `reasoning`, all non-empty strings. `reasoning` must parse as an effort.
3. **Pick on a draft** (no session yet): stored locally and sent with `session.create {model, provider, reasoning?, service_tier?}`.

Result `ModelOptionsResult {providers: ModelOptionProvider[], model?: string, provider?: string}`. Main row fields:
- identity: `slug`, `name`
- models: `models[]`, `total_models`, `featured_models[]`, `unavailable_models[]`
- status: `is_current`, `authenticated`, `auth_type`, `key_env`, `source`, `is_user_defined`, `api_url`, `aliases[]`, `warning`
- `capabilities {model: {fast, ultrafast?, reasoning, can_disable_reasoning?}}`. Per-model `supported_efforts` is deliberately **not** sent: the client offers every level.
- `pricing {model: {input, output, cache?, free, discount_percent?, was_input?, was_output?}}` ($/Mtok strings, `""` = unknown), `pricing_pending`
- free tier: `free_tier`, `free_tier_pending`, `free_tier_row`
- `limit {scope:'account'|'models', resets_at?, models?}`
- `usage {windows[{label, used_percent, resets_at?, scope}], accounts[{id, label?, windows, state, resets_at?}]?}`
- `plan_usage {kind, plan?, gauges[{label, left_fraction, style, resets_at_ms?, scope?, title?}], top_tier_model_ids, featured_model_ids, paused_reason?, credits_pay?}`
- `usage_levels {model: level}`

```json
// one providers[] entry
{"slug":"openrouter","name":"OpenRouter","authenticated":true,"is_current":true,"auth_type":"api_key","key_env":"OPENROUTER_API_KEY",
 "models":["anthropic/claude-sonnet-4.6","openai/gpt-5.5"],"total_models":312,"featured_models":["anthropic/claude-sonnet-4.6"],
 "capabilities":{"openai/gpt-5.5":{"fast":true,"ultrafast":false,"reasoning":true}},
 "pricing":{"openai/gpt-5.5":{"input":"$1.25","output":"$10.00","cache":"$0.13","free":false}},
 "limit":null,"usage":{"windows":[]}}
```

**Model switch handshake** (canonical; `gateway/session_mutation_model.py`):
- **Applied.** The receipt is `{session_id, operation:"model", revision, …}`, then a `session.info` event (new `model`, `provider`, `reasoning_effort`) to every viewer.
- **Busy session.** A `session_busy` refusal is turned into a deferred pick: `{status:"deferred", model, revision, execution_generation}`. It applies at the next turn boundary. The Desktop shows `shell.modelMenu.pickDeferred`.
- **Guarded target** (cost, data policy, or a large-context switch; `hermes_cli/model_selection_guards`). Nothing is written. The answer is `{session_id, operation:"model", status:"confirmation_required", confirm_required:true, confirm:"<sha256 token>", target_model, target_provider, confirm_message, warnings:[{kind,title,message}]}`. The user confirms, and the client resends **the same target** with `payload.confirm = token`. The token is bound to the target, the current selection and the session revision. A stale or foreign token gets a fresh refusal (never a silent apply).
- **Deferred pick that needs confirmation later.** The server sends a `session.updated` event `{operation:"model", status:"confirmation_required", confirm, payload, committed_model, committed_provider?}`.
- **Refusals:** `model_resolution_failed`, `revision_conflict`, `stale_generation` (adopt `data.revision`/`data.execution_generation`, retry once with a new `request_id`), `invalid_params`.

```json
// → {"method":"session.mutate","params":{"session_id":"local-9f…","request_id":"5b0c…","expected_revision":12,"expected_generation":3,
//      "operation":"model","payload":{"model":"claude-opus-4.6","provider":"anthropic"}}}
// ← {"session_id":"local-9f…","operation":"model","status":"confirmation_required","confirm_required":true,"confirm":"a41f…",
//    "target_model":"claude-opus-4.6","target_provider":"anthropic","confirm_message":"Opus costs ~5x …","warnings":[{"kind":"cost","title":"…","message":"…"}]}
// → same params, new request_id, "payload":{"model":"claude-opus-4.6","provider":"anthropic","confirm":"a41f…"}
// ← {"session_id":"local-9f…","operation":"model","revision":13, …}   + event session.info
```

**Legacy** `config.set key=model` (`tui_gateway/model_switch.py`):
- value `"<model> [--provider p] [--session|--global]"`
- result `{key, value, warning?, confirm_required?, confirm_message?, scope?, deferred?}`
- resend with `confirm_expensive_model:true`

**Notes**
- A canonical model change is always session-scope policy. It never writes `config.yaml`. The global default is REST `POST /api/model/set` (Settings ▸ Model).
- `model.options` never starts inference. `refresh:true` probes custom endpoints and busts the catalog cache.
- `model.save_key {slug, api_key, session_id?}` → `{provider}` and `model.disconnect {slug, session_id?}` → `{slug, name, disconnected}` are TUI-only legacy RPCs, not used by the Desktop. Errors: 4001 missing, 4002 unknown provider, 4003 OAuth provider, 4006 managed install.

#### Reasoning effort

**UI:** `reasoning-pill.tsx`, model menu, `/reasoning`, `composer.reasoningUp/Down`. Levels come from `apps/shared/src/reasoning-effort.ts`:
- `REASONING_EFFORTS = minimal, low, medium, high, xhigh, max, ultra` (ascending)
- `none` means thinking disabled
- default `medium`

The backend's `hermes_constants.VALID_REASONING_EFFORTS` is the same list. `parse_reasoning_effort` accepts `none|false|disabled` as disabled.

**Calls**
- Read: `config.get {key:"reasoning", session_id?}` (canonical) → `{value: effort|"none", display:"show"|"hide"}`. With a session, `value` is the next turn's level (typed override, else the frozen policy).
- Session-scope level (Desktop `config.set {key:"reasoning", session_id, value:<level>}` without `scope:"global"`) → canonical `session.mutate {operation:"reasoning", payload:{effort:<level>|null}}`. `null` means reset to the profile level. The Desktop reads the result as `value: effort`. On a busy session the result is `status:"deferred"`.
- Global or display words → canonical `config.set {key:"reasoning", session_id (req), value, scope:"session"|"global"}` (`session_tui_config._set_command_setting`).
  - Accepted values: `show|on|hide|off`, `reset`, or a level.
  - `full` and `clamp` are rejected with `invalid_params` on canonical (legacy accepts them).
  - `scope:"global"` also persists the level to `config.yaml`.
  - Result: `{key:"reasoning", value: level|"show"|"hide", scope}`.
- Global default in Settings: REST `PUT /api/config {config:{agent:{reasoning_effort}}}` (Model ▸ main field `agent.reasoning_effort`). Display uses `display.show_reasoning`.
- Session reasoning display only: `config.set {key:"show_reasoning", session_id, value:"1"|"0"|"on"|"off"|"true"|"false"}` → `{key:"show_reasoning", value:"1"|"0", scope:"session"}` (canonical, not a settings write).

**Events:** `session.info` (`reasoning_effort`, `reasoning_effort_wire`).

#### Fast mode / service tier

**UI:** model menu speed dimension (`store/model-presets.ts`), pill label (`lib/model-status-label.ts`). `/fast` is `desktop=advanced`, so it is unavailable as a Desktop slash command.

**Calls**
- **Canonical `config.set {key:"fast", session_id (req), value}`** (`session_tui_config._set_fast`):
  - Words: `normal|default|standard|off|none|""` → normal; `fast|priority|on` → `priority`; `ultrafast|ultra`; `auto`; `cold`; `toggle`/`""` flips normal↔fast; `status` reads.
  - `priority`/`ultrafast` answer `fast_unavailable` when the session's model or route doesn't offer them.
  - Result: `{key:"fast", value:"normal"|"fast"|"ultrafast"|"auto"|"cold"}`.
  - It is a per-session override, never `config.yaml`.
- Canonical `config.set {key:"service_tier", session_id, value}` → `{key:"service_tier", value: tier|"normal", scope:"session"}` (the strict tier word).
- Read: `config.get {key:"fast", session_id?}` → `{value:"fast"|"ultrafast"|"normal"}`.
- At create: `session.create {service_tier}`. The Desktop's `fast:true` becomes `"priority"`, and `ultrafast` is sent explicitly.
- Global default: REST `PUT /api/config` `agent.service_tier`.

**Events:** `session.info` (`service_tier`, `fast`).

#### Personality

**UI:** Settings ▸ Chat ▸ behavior (`display.personality`), `/personality <name>` (exec, options; completions come from `complete.slash`).

**Calls**
- Canonical `config.set {key:"personality", value:<name>|"none"|"" , session_id?}` → `{key:"personality", value, history_reset:false}`. Unknown name: `unknown_personality`. It persists `display.personality` in the **owning profile's** `config.yaml` (`hermes_cli.personality.persist_personality`), so it is profile-wide.
- `slash.exec "/personality X"` is canonical control.
- REST `PUT /api/config {config:{display:{personality}}}`.
- Built-ins: `helpful`, `concise`, `technical`, …. User entries live in `agent.personalities` or top-level `personalities`.

**Notes:** Every next turn of the profile's sessions picks up the change (the cached agent is rebuilt). Legacy `config.set personality` returns `history_reset` and `info`.

#### YOLO and approvals mode

**UI:** status-bar zap, `/yolo`, Settings ▸ Safety ▸ Approvals, `store/approval-mode.ts`, `lib/yolo-session.ts`.

**Calls** (canonical)
- Session YOLO: `config.set {key:"yolo", session_id (req), value:"1"|"0"|"on"|"off"|"true"|"false" (or omit to toggle)}` → `{key:"yolo", value:"1"|"0", scope:"session"}`. It is persisted on the session row. It never touches `approvals.mode` or `HERMES_YOLO_MODE`. Read with `config.get {key:"yolo", session_id}`.
- Global YOLO: `config.set {key:"yolo", session_id (req), scope:"global", value:"1"|"0"|""|"toggle"}` → `{key:"yolo", value, scope:"global"}`. It flips `approvals.mode` between `off` and `manual` in `config.yaml`.
- Approvals mode: `config.get {key:"approvals.mode", profile}` → `{value:"manual"|"smart"|"off"}`. `config.set {key:"approvals.mode"|"approval_mode", value, profile?}` → `{key:"approvals.mode", value}`. Needs `session:control`.
- REST `PUT /api/config` with a changed `approvals.mode` also broadcasts `session.info` to the live sessions of the same process.

**Events:** `session.info` (`yolo`, `approval_mode`).

#### Busy mode, verbose

- Busy mode: `config.get {key:"busy", session_id (req)}` / `config.set {key:"busy", session_id, value:"queue"|"steer"|"interrupt"}` → `{key:"busy", value, scope:"session"}` (canonical, session-only). Default: `queue` for `tui` sources, else `display.busy_input_mode`, else `interrupt`. The Desktop reads it (`app/session/hooks/use-busy-input-mode.ts`) and skips the read when the server owns the composer queue. The persistent default is REST `display.busy_input_mode`.
- Verbose: `config.set {key:"verbose", session_id, value:"off"|"new"|"all"|"verbose"|"cycle"}` → `{key:"verbose", value, scope:"session"}`.

#### `config.get` / `config.set` key reference

**Canonical `config.get`** (`gateway/session_config.py::config_get`). Params: `{key (req), session_id?, profile?, cwd? (only with key=project)}`.

| key | result |
|---|---|
| `full` | `{config: {display, approvals, paste_collapse_threshold, paste_collapse_char_threshold, voice:{record_key, submit_mode}}}`. Redacted. No provider/MCP/plugin/terminal sections. |
| `mtime` | `{mtime, mcp_rev}` (config poller; `mcp_rev` is pinned to the session's frozen policy) |
| `reasoning` | `{value, display}` |
| `fast` | `{value}` |
| `skin` | `{value: display.skin \|\| "default"}` |
| `theme` | `{value: display.tui_theme \|\| "auto"}` |
| `indicator` | `{value}` |
| `details_mode` | `{value: hidden\|collapsed\|expanded}` |
| `approvals.mode`, `approval_mode` | `{value}` |
| `project` | `{cwd, branch}` |
| `profile` | `{home, display}` |
| `voice` | `{value: off\|voice_only\|all}` (needs `session_id`) |
| `busy`, `verbose`, `yolo`, `show_reasoning` | session controls (need `session_id`) |

**Canonical `config.set`** (`gateway/session_tui_config.py::config_set`). Params: `{key (req), value?, session_id?, profile?, scope?}`.

| key | value / effect | result extras |
|---|---|---|
| `busy`, `verbose`, `yolo`, `show_reasoning`, `service_tier` | session-only (above) | `scope:"session"` |
| `model` | **refused** `use_session_mutation_model` | — |
| `reasoning` (+`scope`), `yolo` + `scope:"global"` | see above (needs `session_id`, `session:control`) | `scope` |
| `fast` | session tier | — |
| `personality` | profile `display.personality` | `history_reset:false` |
| `approvals.mode` / `approval_mode` | `manual\|smart\|off` | — |
| `display.message_reactions`, `display.in_app_tips`, `display.in_app_tours` | bool words `on/off/true/false/yes/no/1/0` | `value: bool` |
| `voice.voice_chat_mode` | `chained\|gpt-live` | — |
| `density` | `on\|off\|toggle\|""` → `display.tui_compact` | `value:on\|off` |
| `battery`, `timestamps` | bool words / toggle | `on\|off` |
| `statusbar` | `on\|top\|bottom\|off\|toggle` | — |
| `mouse` | mouse preset words | — |
| `theme` | `auto\|light\|dark` → `display.tui_theme` | — |
| `indicator` | an `INDICATOR_STYLES` word | — |
| `details_mode` | `hidden\|collapsed\|expanded` (sets every section) | — |
| `details_mode.<thinking\|tools\|subagents\|activity>` | mode, or `""` to clear | — |
| `focus` | `on\|off\|status` | `tool_progress` |
| `skin` | non-empty skin name → `display.skin` (then a `skin.changed` broadcast) | — |

Extra keys beyond `{session_id, profile, key, value}` answer `invalid_params`. Refusals: `invalid_params`, `permission_denied` (needs `session:control`), `storage_unavailable` (unparseable `config.yaml` is never overwritten), `profile_mismatch`, `fast_unavailable`, `unknown_personality`.

**Desktop client guard.** `canonical-protocol.ts::CANONICAL_CONFIG_KEYS` lets only `busy, verbose, yolo, model, reasoning, fast, personality, approvals.mode, voice.voice_chat_mode, display.message_reactions, display.in_app_tips, display.in_app_tours` onto the wire, and `scope` only with `reasoning`/`yolo`. Anything else throws "Changing X is not available on the shared gateway yet". The display/TUI keys in the table above are served by the authority but the Desktop never sends them.

**Legacy-only keys** (`tui_gateway/methods_config.py` / `methods_config_set.py`): `config.get` `provider`, `prompt`, `personality`, `thinking_mode`, `density`, `statusbar`, `focus`, `mouse`; `config.set` `cwd|terminal.cwd|workdir`, `prompt`, `thinking_mode`, `model` (legacy switch). `config.show {profile?}` → `{sections}` (legacy).

```json
// → {"method":"config.set","params":{"key":"yolo","session_id":"local-9f…","value":"1"}}
// ← {"key":"yolo","value":"1","scope":"session"}
// → {"method":"config.set","params":{"key":"reasoning","session_id":"local-9f…","value":"high","scope":"global"}}
// ← {"key":"reasoning","value":"high","scope":"global"}
// → {"method":"config.set","params":{"key":"display.in_app_tips","value":"false","profile":"default"}}
// ← {"key":"display.in_app_tips","value":false}
// → {"method":"config.set","params":{"key":"model","session_id":"local-9f…","value":"gpt-5.5 --provider openai"}}
// ← {"error":{"code":4001,"message":"use_session_mutation_model","data":{"reason":"use_session_mutation_model"}}}
```

---

#### Settings: config REST (schema-driven pages)

**UI**
- `app/settings/index.tsx` (nav, export/import/reset)
- `config-settings.tsx` (all `config:*` sections, debounced autosave)
- `config-field.tsx`, `config-subpages.ts`, `constants.ts` (`SECTIONS`)
- `use-settings-search.ts`

**Calls** (REST; `hermes_cli/web_routers/config_env.py`, `analytics.py`)
- `GET /api/config?profile=&include_defaults=true|false` → the config dict without `_*` keys.
  - Normalized: `model` is flattened to a string (`model.default`) and `model_context_length` (int, 0 = auto) is added.
  - `include_defaults=false` returns only the saved values.
- `GET /api/config/defaults` → `DEFAULT_CONFIG`.
- `GET /api/config/schema?profile=` → `{fields: {"dotted.key": {type: string|number|boolean|select|…, description, category, options?}}, category_order: string[]}`. Provider options (voice, memory plugins) are merged dynamically per request.
- `PUT /api/config?profile=&preserve_language=bool`, body `{config: {...partial...}, profile?}` → `{ok:true}`.
  - The body is **deep-merged** over disk. Send sparse patches.
  - `model_context_length` is written back as `model.context_length`. A changed `model` re-detects the provider.
  - `preserve_language=true` keeps an explicit `display.language: en`.
  - A changed `approvals.mode` broadcasts `session.info`.
- `GET /api/config/raw?profile=` → raw YAML. `PUT /api/config/raw {yaml_text, profile?}` (`RawConfigUpdate`).
- Export: the client downloads `GET /api/config` as `hermes-config.json`. Import: the client reads a file and sends `PUT /api/config`. Reset: `GET /api/config/defaults` followed by `PUT /api/config`.

```json
// PUT /api/config?profile=work
{"config":{"sessions":{"auto_archive":true,"auto_archive_days":30}}}
// ← {"ok":true}
```

**Notes**
- **Restart and scope semantics.** Config edits never touch a running session's frozen launch policy. They apply to **new sessions** (Desktop copy: "applies to new sessions"), or to sessions that follow the profile config (`follow_profile_config`) at their next turn.
- These need the gateway itself to restart:
  - `webapp:` (Web Access restarts it automatically)
  - messaging platform credentials ("takes effect after a gateway restart"; messaging slice)
  - a gateway running stale code (`/api/model/*` answer `503 Restart required: …`)
- MCP sections: the client polls `config.get mtime`. When `mcp_rev` changes it may call `reload.mcp` (canonical, session-scoped).

#### Keys / `.env` management

**UI**
- `app/settings/keys-settings.tsx`, views `tools|settings` (`?kview=`)
- `env-credentials.tsx`, `env-var-actions-menu.tsx`, `credential-key-ui.tsx`
- `toolset-config-panel.tsx`

**Calls** (REST)
- `GET /api/env?profile=` → `{VAR: {is_set, redacted_value, description, url, category, is_password, tools[], advanced, channel_managed, provider, provider_label, provider_profiles[], …}}` (`EnvVarInfo`). Values are never returned in clear.
- `PUT /api/env`, body `EnvVarUpdate {key, value, profile?, api_key?, provider_setup?: bool}`. It writes `.env` and reconciles mirrored copies in `config.yaml` (`model.api_key`, `auxiliary.*.api_key`, `custom_providers`). A redacted preview as `value` answers 400.
- `DELETE /api/env`, body `{key, profile?}` → 404 when the key is missing.
- `POST /api/env/reveal`, body `{key, profile?}` → `{key, value}`. It requires the ephemeral session token (`_require_token`), is rate-limited to 5 per 30 s (429) and is audit-logged.
- Toolset keys and providers:
  - `GET /api/tools/toolsets`
  - `GET /api/tools/toolsets/{name}/config`
  - `GET /api/tools/toolsets/{name}/models`
  - `PUT /api/tools/toolsets/{name}` (enable)
  - `PUT /api/tools/toolsets/{name}/model`, `/provider`, `/env`
  - `POST /api/tools/toolsets/{name}/post-setup`, then poll `GET /api/actions/{name}/status`

  Toolset OAuth uses the provider OAuth endpoints below.

**Notes**
- `reload.env {session_id (req), profile?}` → `{updated}` (canonical; needs `session:control`; a sessionless frame is refused).
  - Single-profile owner: it re-reads `.env` into the process.
  - Multiplexed owner: it re-pulls external secret sources. Every turn already re-reads `.env` into its own scope.
- Built agents keep their credential pool until `/new`.
- The Desktop calls `reload.env` with no params after a provider sign-in and ignores the error. On canonical that call is refused, so new keys simply apply to new sessions or turns.

#### Providers: OAuth sign-in

**UI**
- `app/settings/providers-settings.tsx`, views `accounts|keys|custom-endpoints|local` (`PROVIDER_VIEWS`)
- `store/onboarding.ts` (`startManualProviderOAuth`)
- `store/free-tier-sign-in.ts`

**Calls** (REST; `web_routers/oauth.py`, `web_server_oauth.py`)
- `GET /api/providers/oauth?profile=` → `{providers:[{id, name, flow:"device_code"|"external", cli_command, docs_url, disconnect_hint, disconnect_command, disconnectable, status:{logged_in?, source?, …}}]}`.
  - Catalog: `nous`, `openai-codex`, `minimax-oauth` and `xai-oauth` (device_code); `qwen-oauth`, `anthropic` and `claude-code` (external = run `cli_command` in a terminal).
- `POST /api/providers/oauth/{id}/start?profile=` → `{session_id, flow:"device_code", user_code, verification_url, expires_in, poll_interval}`.
  - It needs the session token.
  - An `external` flow answers 400 "uses an external CLI; run `…`".
  - Open `verification_url` and show `user_code`.
- `GET /api/providers/oauth/{id}/poll/{session_id}?profile=&wait=<seconds>` (long-poll; the Desktop uses `wait=25`) → `{session_id, status:"pending"|"approved"|"error"|"expired"|"cancelled", error_message, expires_at, reason, account_email, model, handoff, handoff_status, retryable, retry_after}`. 404 when expired. 400 on a provider or profile mismatch.
- `POST /api/providers/oauth/{id}/submit`, body `{session_id, code}` (PKCE): currently answers 400 "submit not supported" for every provider.
- `DELETE /api/providers/oauth/sessions/{session_id}` → `{ok, session_id}` (cancel).
- `DELETE /api/providers/oauth/{id}` → `{ok, provider}` (disconnect). 400 when not disconnectable (hint returned).
- `GET /api/providers/oauth/nous/handoff?since=<updated_at>&wait=25` → the free-tier handoff record (long-poll).

```json
// POST /api/providers/oauth/openai-codex/start
// ← {"session_id":"9c1e…","flow":"device_code","user_code":"WDJB-MJHT","verification_url":"https://auth.openai.com/device","expires_in":900,"poll_interval":5}
// GET /api/providers/oauth/openai-codex/poll/9c1e…?wait=25
// ← {"session_id":"9c1e…","status":"approved","account_email":"me@example.com","model":"gpt-5.5", "error_message":null, …}
```

**Notes:** After approval the Desktop sends `reload.env` (sessionless, so refused on canonical; see Keys) and refetches `model.options`. Policy: the Anthropic and Claude subscription logins are deliberately `external`, never driven by HTTP.

#### Providers: API keys, validation, custom endpoints

**UI:** `providers-settings.tsx` (keys view), `custom-endpoints-settings.tsx`.

**Calls** (REST)
- `POST /api/providers/validate`, body `EnvVarUpdate {key, value, api_key?}` → `{ok, reachable, message, models?, model_details?}`.
  - Live probes exist for `OPENROUTER_API_KEY`, `OPENAI_API_KEY`, `XAI_API_KEY` and `GEMINI_API_KEY`. Others answer "unknown" (`ok:true, reachable:false`).
  - With `OPENAI_BASE_URL`, it probes `/models`.
- Then `PUT /api/env {key, value, provider_setup:true}`.
- `GET /api/providers/custom-endpoints?profile=` → `{endpoints:[{id, name, base_url, model, models[], api_mode, api_key_set, api_key_display?, …}], …}` (shape from `_custom_endpoint_response`; partly unverified).
- `POST /api/providers/custom-endpoints`, body `CustomEndpointUpdate {id?, name, base_url, model, api_key?, api_mode?: ""|"chat_completions"|"codex_responses"|"anthropic_messages", context_length?, discover_models=true, make_default=false, models?, model_details?:[{id, canonical_model?, reasoning_effort?}]}`. 400 for a missing name, base_url or model, or a base_url without scheme and host.
- `POST /api/providers/custom-endpoints/validate` (same body) → `{ok, reachable, message, models[]}`.
- `POST /api/providers/custom-endpoints/{id}/activate` → `{ok, provider, model}`. `DELETE /api/providers/custom-endpoints/{id}` → the list.

#### Global model assignment (Settings ▸ Model)

**UI**
- `app/settings/model-settings.tsx` (subpages `main`, `fallbacks`, `auxiliary`, `delegation`, `moa`)
- `fallback-models-field.tsx`, `aux-task-rows.tsx`, `model-select.tsx`
- `delegation/*`, `delegation-model-provider-field.tsx`

**Calls** (REST; `web_routers/models.py`)
- `GET /api/model/info?profile=` → `{model, provider, auto_context_length, config_context_length, effective_context_length, capabilities:{supports_tools, supports_vision, supports_reasoning, context_window, max_output_tokens, model_family}}`.
- `GET /api/model/options?…` (see Model picker).
- `GET /api/model/recommended-default?provider=<slug>&profile=` → `{provider, model, free_tier: bool|null}`.
- `POST /api/model/set?profile=`, body `ModelAssignment {scope:"main"|"auxiliary", provider, model, task="" (aux slot; ""=all, "__reset__"=all to auto), reasoning_effort? (aux; null clears), base_url="", api_key="", confirm_expensive_model=false, profile?}`.
  - A guarded model answers `{ok:false, scope, provider, model, confirm_required:true, confirm_message}`. Resend with `confirm_expensive_model:true`.
  - 400 for a bad scope. 503 "Restart required" on skew.
  - It writes `config.yaml` and applies to **new sessions only**.
- `GET /api/model/auxiliary?profile=` → `{tasks:[{task, provider, model, base_url, reasoning_effort, local_endpoint, label, hint, plugin, inherit_from, effective?}], main:{provider, model}}`.
- `GET /api/model/moa?profile=` / `PUT /api/model/moa`, body `MoaConfigPayload {default_preset, active_preset, presets:{name:{reference_models:[{provider, model, reasoning_effort?, enabled}], aggregator, reference_temperature?, aggregator_temperature?, fanout?, enabled, reference_timeout?, degraded_reference_policy:"loud"|"silent"}}, profile?}` → `{ok:true, …normalized}`. 422 when invalid.
- Fallbacks are `fallback_providers`, and the main fields are `model`, `model_context_length`, `agent.reasoning_effort`, `agent.service_tier` → `PUT /api/config`.
- Delegation:
  - `GET /api/delegation/routing`
  - `PUT /api/delegation/backend`
  - `PUT /api/delegation/backends/model_map`
  - `PUT /api/delegation/backends/profile_routes`
  - plus the `delegation.*` config keys

#### Local models

**UI**
- `app/settings/local-models-*.tsx` (providers ▸ local)
- `local-model-download-progress.tsx`
- Gated by `$localModelsEnabled` (`hermesDesktop.localModelsEnabled`; browser value unverified)

**Calls** (REST; `web_routers/local_models.py`)
- `GET /api/local-models/status` → `{enabled, tag, configured_tag, update_available, runtime_installed, runtime_backend, server_running, server_base_url, active_model_id, loaded_models, loading, placement, models[], models_dir, …}`.
- `GET /api/local-models/hardware`, `GET /api/local-models/catalog` → `{models:[…, recommended, installed…]}`.
- `POST /api/local-models/runtime/install {backend?}` → `{job_id, backend, tag}`.
- `POST /api/local-models/quickstart {model_id?}` → `{job_id, model_id, display_name, …}`.
- `POST /api/local-models/download {model_id}` → `{job_id|null, already_downloaded?, model_id}`.
- `POST /api/local-models/download/pause {job_id}` → `{ok, paused}`. `/resume {job_id}` → `{ok, resumed}`.
- `DELETE /api/local-models/models/{model_id}`.
- `POST /api/local-models/server {action:"start"|"stop"}`.
- `POST /api/local-models/eject {model_id}`, `POST /api/local-models/activate {model_id}` → `{job_id}`.
- `GET /api/local-models/jobs` → `{jobs[]}`. `GET /api/local-models/jobs/{id}`.
- `GET /api/local-models/search?q=&limit=20` → `{hits[]}`. `GET /api/local-models/search/files?repo=` → `{files[{…, paths[]}]}`.
- `POST /api/local-models/download-browsed {repo, paths[]}`.
- `POST /api/local-models/sideload {path}` → `{ok, model_id, already_present?}` (server-local path).

**Notes:** Long operations are jobs. Poll `jobs/{id}`. Quickstart and activate also write the profile's model config (applies to new sessions).

---

#### Theme / skins

**UI**
- `app/settings/appearance-settings.tsx` (theme subpage)
- `themes/backend-sync.ts`, `themes/skin.ts`, `themes/context.tsx`
- command palette theme page

**Calls / events**
- **`gateway.ready`** (the first frame on every socket) `GatewayReadyPayload {skin: SkinPayload, change_events: bool, replay_epoch, heartbeat?, session_authority?}`. The Desktop **seeds** the skin into its theme registry without applying it, so the user's persisted desktop theme wins. `change_events:true` demotes the polls for `pet.changed`, `cron.changed`, `sessions.changed`, `projects.changed`, `platforms.changed` and `pairing.changed`.
- **`skin.changed`** (`SkinPayload {name, description, colors, light_colors, dark_colors, branding, banner_logo, banner_hero, tool_prefix, help_header}`). The client applies it when it comes from the active source and profile. Canonical publishes it per attached session after `config.set skin`. Legacy fans it session-less.
- `config.get {key:"skin"}` → `{value}`. `config.set {key:"skin", value}` (canonical, writes `display.skin`, broadcasts). The Desktop `/skin` and the Appearance picker are **client-only**: no backend write.
- `runtime.describe` also carries `skin`.
- **Server→client `theme.apply`** `{session_id, accent?, mode?: light|dark|system, layout?}`, from the agent's `set_theme` tool. Answer `{value: JSON-string}`:
  - success: `{"success":true,"applied":{…}}`
  - failure: `{"success":false,"error":"…","available":[…]}`
  - Only the active (on-screen) session applies it. Others answer an error.
- **Server→client `theme.pick`** `{session_id, picker:"accent"|"layout"}`. The client shows a picker card in the chat. Answer `ThemePickResult {picked?: {accent?, mode?, layout?}, said?, cancelled?}`. A response with none of these means cancel.

```json
// ← server request {"jsonrpc":"2.0","id":"r-12","method":"theme.pick","params":{"session_id":"local-9f…","picker":"accent"}}
// → {"jsonrpc":"2.0","id":"r-12","result":{"picked":{"accent":"violet"}}}
// ← {"jsonrpc":"2.0","id":"r-13","method":"theme.apply","params":{"session_id":"local-9f…","mode":"dark"}}
// → {"jsonrpc":"2.0","id":"r-13","result":{"value":"{\"success\":true,\"applied\":{\"mode\":\"dark\"}}"}}
```

**Notes**
- Marketplace (VS Code) themes and native title-bar colors go through the Electron bridge (stubbed in a browser).
- Legacy dashboard routes (`GET /api/dashboard/themes`, `PUT /api/dashboard/theme {name}`, `GET/PUT /api/dashboard/font {font}`) are not used by the Desktop or the Webapp.

#### i18n

**UI:** `apps/desktop/src/i18n/*` (bundled catalogs), `i18n/backend-packs.ts`, `i18n/context.tsx`, the Appearance ▸ General language picker.

**Calls**
- `i18n.languages {profile?}` → `{languages:[{id, endonym, rtl, source:"bundled"|"overlay"|"plugin:<name>"}]}` (WS legacy).
- `i18n.catalog {lang (req), surface?: "core"|"tui"|"desktop" (default core), profile?}` → `{lang (canonical id, unknown→"en"), surface, messages: {flat.key: string}}` (WS legacy).
  - The result holds only the pack and user-overlay layer. Merge it over the bundled catalog.
  - Error 4002 for a bad surface. Error 5400 for a failure.
  - The Desktop asks for `surface:"desktop"`.
- Persisting the language: REST `PUT /api/config?preserve_language=true {config:{display:{language:"<id>"}}}`.

**Notes**
- Method-not-found on older gateways means the client keeps its bundled catalogs.
- Both calls are profile-scoped: the overlay lives in `<home>/locales`. Plugin packs are process-wide.
- Slash descriptions and categories also follow `display.language`.

#### Display toggles that reach the agent

**UI:** Appearance ▸ chat-display (`store/reactions-enabled.ts`, `store/tips.ts`, `store/tours.ts`, `store/display-toggles.ts`).

**Calls**
- `config.set {key:"display.message_reactions"|"display.in_app_tips"|"display.in_app_tours", value:"true"|"false", profile}` (canonical). Sent on change, and again on every (re)connect for toggles the user has touched.
- These decide whether the `react_to_message`, `tip` and `tour` tools are in the model's schema.
- Every other Appearance setting is renderer-local:
  - `localStorage`: backdrop, text scale, translucency, tool view, timeline, tabs, zoom, intro splash, interface mode, model pricing display, reasoning collapse
  - a few REST writes (`PUT /api/config`): `display.resume_last_session`, the chat and terminal font keys (exact keys unverified)

#### Web Access (serve the Webapp to other devices)

**UI:** `app/settings/web-access-settings.tsx` (view `web-access`; hidden where `useWebAccessAvailable()` is false), `api/webapp-access.ts`.

**Calls** (REST; `web_routers/webapp_access.py`)
- `GET /api/webapp-access/status` → `WebAccessStatus {enabled, gateway:{state:"off"|"serving"|"starting"|"stopped"|"unavailable", reason, url}, lan_address, mode:"local"|"lan"|"public"|null, nous_signed_in, port, public_url}`.
- `POST /api/webapp-access/start {mode:"lan"|"public", public_url?}` → `WebAccessChange` (= status + `restart_started`, `restart_busy?`, `restart_error?`). It builds the Webapp bundle first (the client timeout is 35 min).
- `POST /api/webapp-access/stop`, `POST /api/webapp-access/restart` → `WebAccessChange`.

**Notes**
- **Native Desktop on the same machine only.** The dependency `owner_on_this_machine` requires a native HTTP grant, so every browser credential gets 403 "Web Access can only be changed from the Hermes app on this computer". The routes exist only on the gateway's own listener (404 elsewhere).
- Start and stop write `webapp:` in the serving gateway's `config.yaml` and **restart that gateway**. A running chat can refuse the restart (`restart_busy`); the config stays saved.
- One change runs at a time. Access errors answer 400. Public mode needs a Nous sign-in.

#### Gateway settings

**UI**
- `app/settings/gateway-settings.tsx` (subpages `connection`, `devices`, `managed-updates`; all `nativeHostOnly`)
- `connections-registry.tsx`, `managed-updates-section.tsx`, `keychain-encryption-setting.tsx`
- `webapp-gateway-settings.tsx` (browser)

**Calls**
- Native:
  - Electron bridge only: `getConnectionConfig`, `saveConnectionConfig`, `applyConnectionConfig`, `testConnectionConfig`, `sshConfigHosts`, `sshResolveHost`, `revealLogs`, `connections`, `get/setSecretStorageEncryption`
  - REST `POST /api/gateway/restart?profile=`; `POST /api/gateway/start|stop` (ops router)
  - `GET /api/status` (`gateway_running`, `gateway_state`, `gateway_pid`, `gateway_platforms`, `gateway_busy`, `gateway_drainable`, `active_sessions`, `version`, `release_date`, `config_version`, `latest_config_version`, …)
- Webapp (`WebappGatewaySettings`): shows which host this is. Sign-out is `POST {basePath}/auth/logout` (same-origin cookie), then navigate to `{basePath}/login`.
- Diagnostics (Safety ▸ privacy, `shared-metrics-settings.tsx`):
  - `shared_metrics.status {profile?}` → `{enabled, send, decided, reask?}`
  - `shared_metrics.set {profile?, enabled, send?, first_run?}` → same shape
  - both WS legacy

#### Sessions settings

**UI:** `app/settings/sessions-settings.tsx` (subpages `archived`, `default-directory`).

**Calls**
- Archived list: `listEveryArchivedSession` → REST `/api/profiles/sessions?…archived=only` (paged). Unarchive with `setSessionArchived`; delete with `deleteSession` (sessions slice REST).
- Auto-archive: `GET /api/config` → `sessions.auto_archive`, `sessions.auto_archive_days`. Write with a sparse `PUT /api/config {config:{sessions:{auto_archive, auto_archive_days}}}`. The backend sweep is `SessionDB.maybe_auto_archive`. Pins are exempt.
- Default project dir:
  - native: Electron `settings.get/set/pickDefaultProjectDir`
  - browser: `localStorage` key `hermes:browser:default-project-dir:["<basePath>","<profile>"]`, picked through the server folder browser (`lib/browser-settings.ts`)

#### Notifications settings

**UI:** `app/settings/notifications-settings.tsx` (subpages `alerts`, `sounds`).

**Calls:** none to the backend. Native notifications go through `hermesDesktop.notify`. Sounds and haptics are local stores.

**Events consumed elsewhere**
- `notification.show {text, level: info|warn|error|success, kind: sticky|ttl|agent, ttl_ms?, key?, id?, detail?}`
- `notification.clear {key}`. Example: `sessions.auto_prune` is cleared after `/autoprune`.

#### Keybinds settings

**UI:** `app/settings/keybind-settings.tsx` (subpages `shortcuts`, `hud-gesture`, `screen-capture`), `hud-modifier-settings.tsx`, `screenshot-settings.tsx`.

**Calls:** client-only (`store/keybinds`). The HUD and screenshot settings use Electron `hudModifier` and `screenshot` (native only).

#### Billing, subscription, usage

**UI:** `app/settings/billing/*` (view `billing`, sub-views `overview|plans` via `?bview=`): `use-billing-state.ts`, `use-step-up.ts`, `use-charge-poller.ts`, `use-subscription-change.ts`, `errors.ts`.

**Calls** (all WS legacy, `tui_gateway/methods_session.py`; params also take `profile?`)

| method | params | result |
|---|---|---|
| `billing.state` | — | `BillingStateResult`. Read-only, fail-open. The Nous free tier is answered locally. Key fields: `ok`, `logged_in`, `free_tier_account`, `free_tier_model`, `org_name`, `role`, `is_admin`, `can_change_plan`, `can_charge`, `balance_display`, `charge_presets[_display]`, `min_usd`, `max_usd`, `card`, `payment_method`, `monthly_cap`, `auto_reload`, `keep_going_on_credits`, `portal_url`, `usage: UsageModel`, `plan_usage` |
| `subscription.state` | — | `SubscriptionStateResult {ok, logged_in, is_admin, can_change_plan, org_name, org_id, role, context, current:{tier_id, tier_name, monthly_credits, credits_remaining, cycle_ends_at, pending_downgrade_*, cancel_at_period_end, cancellation_effective_*}, tiers:[{tier_id, name, tier_order, dollars_per_month_display, monthly_credits, is_current, is_enabled}], portal_url, usage, plan_usage}` |
| `usage.bars` | — | `UsageModel {ok?, available, status, plan_name, renews_at, renews_display, subscription_remaining_display, topup_remaining_display, total_spendable_display, has_topup, plan_bar, topup_bar: {kind: plan\|topup, remaining_display, total_display, spent_display, pct_used?, fill_fraction}}` |
| `subscription.preview` | `subscription_type_id` | `{ok, effect: charge_now\|scheduled\|no_op\|blocked, reason, current_tier_*, target_tier_*, monthly_credits_delta, amount_due_now_cents, effective_at}` |
| `subscription.change` | `subscription_type_id` or `cancel:true` | `BillingPendingChangeResult {ok, message, payload}` |
| `subscription.resume` | — | same |
| `subscription.upgrade` | `subscription_type_id, idempotency_key` | `{ok, status, target_tier_name, recovery_url (SCA/decline → finish in portal), idempotency_key}` (not called by the Desktop's `api.ts`; unverified use) |
| `billing.charge` | `amount_usd, idempotency_key` | `{ok, charge_id, idempotency_key}`. Money is unconfirmed until polled. |
| `billing.charge_status` | `charge_id` | `{ok, status: pending\|settled\|failed, amount_usd, settled_at, reason}`. The client drives the poll cadence. |
| `billing.auto_reload` | `enabled, threshold, top_up_amount` | `BillingMutationResult` |
| `billing.keep_going_on_credits` | `enabled` | `BillingMutationResult` |
| `billing.step_up` | `session_id?` | `{ok, granted}`. Blocks for minutes: a device flow for the `billing:manage` scope. |

Every error envelope includes `{ok:false, error|code, message, portal_url?, retry_after?, recovery?, actor?, payload?}`. Codes the Desktop maps (`billing/errors.ts`):
- `insufficient_scope` → run `billing.step_up`
- `consent_required`, `remote_spending_revoked|disabled`, `session_revoked`, `cli_billing_disabled`, `role_required`
- `idempotency_conflict`, `no_payment_method`, `org_access_denied`, `monthly_cap_exceeded`
- `rate_limited`, `temporarily_unavailable`, `stripe_unavailable`, `upgrade_cap_exceeded`, `endpoint_unavailable`
- client-side `timeout`, `transport`

**Events:** `billing.step_up.verification {verification_url, user_code}`. Open the URL and show the code.

**Notes**
- Keep the `idempotency_key` across retries of `billing.charge` and `subscription.upgrade`.
- `/usage` (`slash.exec`, bare = read) and `session.usage`/`session.stats` (canonical) give the token usage of a session.

#### Free tier

**UI:** `store/free-tier-sign-in.ts`, the setup chip/strip, onboarding.

**Calls** (WS legacy, `methods_free_tier.py`)
- `free_tier.status {profile?}` → `FreeTierStatusResult {has_guest, enabled, available, notice_pending, model, label, error?, error_code?, retryable?, retry_after?, challenge?, nudge_due_in?, connector_nudge_due_in?}`. A pure read.
- `free_tier.provision {profile?}` → `{has_guest, enabled, error?…}`. Retries the identity mint.
- `free_tier.ack_notice` → `{acked}`.
- `free_tier.claim_nudge {kind?: "trial"|"connector"}` → `{claimed}`.
- `free_tier.challenge_result {url, attempt?, outcome: done|failed|closed|timeout|refused|error|unsupported}` → `{accepted}`.

**Events**
- `free_tier.challenge {type:"browser", url, required, expires_in, message, attempt?}` (broadcast). The Desktop loads `url` in a hidden window and reports the outcome. A native app needs a WebView.
- `setup.ready` (below).

#### Onboarding / setup

**UI**
- `store/onboarding-run.ts`, `store/onboarding.ts`, `store/setup-beat.ts`
- `components/onboarding/first-run/*`
- `app/settings/onboarding-settings.tsx` (Advanced ▸ desktop: "Run onboarding again")
- `lib/runtime-readiness.ts`

**Calls**
- `setup.status {profile?}` (WS canonical; `gateway/session_readiness.py`) → `SetupStatusResult {provider_configured, ready, free_tier_account, free_tier_route, other_providers, inference_provider, profile, ok?, error?, error_code?, retryable?, retry_after?}`.
- `setup.runtime_check {profile?, provider?}` (canonical) → `{ok, provider, model, source, error?, free_tier_route, profile}`. `ok:false` means the resolved model can't be served (show `error`).
- `onboarding.state {assume_fresh?}` (WS legacy) → `{run, eligible, session_id?, run_id?}`. It reads the root profile's `onboarding.run`.
- `onboarding.start {rerun?, fresh_launch?, assume_fresh?, facts_source?, locale?}` (WS canonical) → `{session_id, stored_session_id, resumed, run_id}`. Attach to `session_id`. A Settings rerun sends `rerun:true`. It always runs in the default/root profile (`ONBOARDING_PROFILE`; the server uses a sibling connection when the socket belongs to another profile).
- `onboarding.skip {session_id, profile?}` (canonical) → `{skipped}`.
- `onboarding.set_run {run, mark_profile_offered?}` (WS legacy) → the run state.
- `shared_metrics.desktop_onboarding {…}` (legacy, fire-and-forget).

**Server→client `setup_beat`** `SetupBeatRequestParams {session_id, beat: name|theme|layout|connectors|plugins|tour|build, question?, tool_call_id?, card}`:
- `card` is per beat: `SetupNameCard`, `SetupThemeCard`, `SetupOptionsCard` (`options`), `SetupConnectorsCard`, `SetupPluginsCard`.
- Only the transcript row for `tool_call_id` draws the card.
- Answer `SetupBeatResult {answer?: per-beat answer (ids must be from the card's options), said? (composer text while the name card waits), wrote_instead?: bool}`.
- An empty answer, or no answer within 300 s, closes the card. With `wrote_instead:true` the backend ends the turn and the client sends its message next.

Also `tour` (a guided highlight; answer `{value}`) and `secret` (masked env value) server requests during setup.

**Events**
- `setup.ready` `SetupReadyPayload {provider_configured, inference_provider, free_tier_account, free_tier_route, has_identity, other_providers, error?, error_code?, retryable?, retry_after?, finished_at}`. A broadcast after boot bootstrap. Re-read `setup.status`, `setup.runtime_check` and `free_tier.status`.
- `onboarding.ended {run_id, stored_session_id, outcome: done|skipped|replaced|deleted}` (to every window).

#### Updates and version

**UI**
- `app/settings/about-settings.tsx` (subpages `updates`, `uninstall`), `update-channel-row.tsx`, `uninstall-section.tsx`
- `store/updates.ts`
- `apps/shared/src/update-debt.ts`

**Calls**
- Desktop client version and app updates: Electron `getVersion`, `updates.check|apply|setChannel`. In a browser the stub says "Run `hermes update` on the server host".
- Backend:
  - `GET /api/hermes/update/check?force=true|false&profile=` → `{install_method: git|apt|docker|nix|nixos|unknown|desktop-app|managed-runtime, current_version, behind (0 up to date, -1 unknown, null not run), update_available, can_apply (git only), update_command, message, commits?[{sha, summary, author, at}]}`
  - `POST /api/hermes/update` → `{ok:true, pid, name:"hermes-update", action_id, already_running?}`, or a refusal `{ok:false, error_code, message, update_command}` (commit build, managed externally, not-in-place install)
  - `GET /api/actions/hermes-update/status?lines=200` → `{name, running, exit_code, pid, lines[], action_id?, receipt?: {outcome, action_id, started_at, finished_at, pre_sha, post_sha, post_version, fleet_states[], followups[{step, reason}], user_action?}}`
  - `GET /api/hermes/update/receipt` → `{receipt, summary}`
- **Update debt** (`updateDebt(receipt, actionId)`): only when `receipt.action_id === actionId` and `outcome ∈ {success, partial}`.
  - `followups`: `"step (reason); step"`. A rerun of `hermes update` finishes them.
  - `userAction`: `"step: reason"`. Only the user can do it. Never phrase it as "re-run update".
  - The run is finished when a log line `=== hermes-update completed <action_id> ===` appears, or a finished receipt matches.
- Version reads:
  - `GET /api/health` → `{ok, version, commit, auth_required}`
  - `GET /api/status` → `version`, `release_date`
  - `session.info` → `version`, `release_date`, `update_behind`, `update_command`, `desktop_contract`
- Uninstall: Electron `uninstall.*`. The browser stub says "Run `hermes uninstall` on the server host".
- The `shared_metrics.update_run` RPC counts packaged update outcomes (fire-and-forget).

---

#### Settings page → endpoints

The `?tab=` value is the view id and `?page=` is the subpage.

| Settings view / subpage | Component | Endpoints (transport) |
|---|---|---|
| `config:model` / `main`, `fallbacks`, `auxiliary`, `moa` | `model-settings.tsx`, `config-settings.tsx`, `fallback-models-field.tsx`, `aux-task-rows.tsx` | `GET /api/model/info`, `GET /api/model/options`, `GET /api/model/recommended-default`, `POST /api/model/set`, `GET /api/model/auxiliary`, `GET/PUT /api/model/moa`, `PUT /api/env` (key entry), `GET /api/config/schema`, `GET/PUT /api/config` (REST) |
| `config:model` / `delegation` | `delegation/*` | `GET /api/delegation/routing`, `PUT /api/delegation/backend`, `PUT /api/delegation/backends/model_map`, `PUT /api/delegation/backends/profile_routes`, `GET /api/model/options`, `PUT /api/config` `delegation.*` (REST) |
| `config:chat` / `behavior`, `attachments` | `config-settings.tsx` | `GET/PUT /api/config` (`display.personality`, `timezone`, `display.show_reasoning`, `agent.image_input_mode`) |
| `config:appearance` / `general` | `appearance-settings.tsx` | language: `PUT /api/config?preserve_language=true`, `i18n.languages`, `i18n.catalog` (WS legacy); other rows local |
| `config:appearance` / `theme` | `appearance-settings.tsx` | local; backend skin via `gateway.ready`/`skin.changed`; marketplace via the Electron bridge |
| `config:appearance` / `typography`, `window-layout` | `chat-font-setting.tsx`, `terminal-font-setting.tsx`, `minimize-to-tray-setting.tsx` | `PUT /api/config` (font keys, unverified); Electron `minimizeToTray` |
| `config:appearance` / `chat-display` | `appearance-settings.tsx` | `config.set display.message_reactions\|in_app_tips\|in_app_tours` (WS canonical); `PUT /api/config display.resume_last_session` |
| `config:appearance` / `pet` | `pet-settings.tsx` | `pet.gallery`, `pet.info`, `pet.info.meta`, `pet.select`, `pet.rename`, `pet.remove`, `pet.export`, `pet.scale`, `pet.disable`, `pet.thumb` (WS legacy) |
| `config:workspace` / `projects`, `shell`, `files` | `config-settings.tsx` | `GET/PUT /api/config` (`terminal.cwd`, `desktop.repo_scan_*`, `terminal.persistent_shell`, `terminal.env_passthrough`, `code_execution.mode`, `file_read_max_chars`) |
| `config:safety` / `approvals`, `checkpoints` | `config-settings.tsx` | `GET/PUT /api/config` (`approvals.*`, `command_allowlist`, `checkpoints.*`); `config.get/set approvals.mode` (WS canonical, `store/approval-mode.ts`) |
| `config:safety` / `privacy` | `config-settings.tsx`, `shared-metrics-settings.tsx` | `PUT /api/config security.*`; `shared_metrics.status`, `shared_metrics.set` (WS legacy) |
| `config:browser` / `profile`, `network` | `config-settings.tsx`, `browser-real-profile-panel.tsx` | `GET/PUT /api/config` (`browser.*`) |
| `config:memory` / `persistent`, `context` | `config-settings.tsx`, `memory/*` | `GET/PUT /api/config` (`memory.*`, `context.*`, `compression.*`); `GET/PUT /api/memory/providers/{p}/config?surface=declared`, `POST /api/memory/providers/{p}/oauth/start`, `GET /api/memory/providers/{p}/oauth/status` |
| `config:voice` / `conversation`, `transcription`, `speech` | `config-settings.tsx`, `voice-provider-fields.tsx` | `GET/PUT /api/config` (`voice.*`, `stt.*`, `tts.*`), `GET /api/audio/elevenlabs/voices`; `config.set voice.voice_chat_mode` (WS canonical, `store/voice-live.ts`) |
| `config:advanced` / `desktop` | `config-settings.tsx`, `quick-entry-settings.tsx`, `onboarding-settings.tsx` | `PUT /api/config updates.*`; Electron `quickEntry`, keep-awake; `onboarding.start {rerun:true}` (WS canonical) |
| `config:advanced` / `runtime`, `tools`, `terminal`, `output` | `config-settings.tsx`, `terminal-backend-panel.tsx`, `computer-use-panel.tsx` | `GET/PUT /api/config`; `GET /api/tools/terminal/backends`, `PUT /api/tools/terminal/backend`; `GET /api/tools/computer-use/status`, `POST /api/tools/computer-use/permissions/grant`, `GET /api/actions/{name}/status` |
| `providers` / `accounts` | `providers-settings.tsx` | `GET /api/providers/oauth`, `POST …/{id}/start`, `GET …/{id}/poll/{sid}?wait=25`, `DELETE …/sessions/{sid}`, `DELETE …/{id}`; `GET /api/providers/oauth/nous/handoff`; then `reload.env` (WS canonical) + `model.options` |
| `providers` / `keys` | `providers-settings.tsx`, `env-credentials.tsx` | `GET /api/env`, `POST /api/providers/validate`, `PUT /api/env {provider_setup:true}`, `DELETE /api/env`, `POST /api/env/reveal` |
| `providers` / `custom-endpoints` | `custom-endpoints-settings.tsx` | `GET/POST /api/providers/custom-endpoints`, `POST …/validate`, `POST …/{id}/activate`, `DELETE …/{id}` |
| `providers` / `local` | `local-models-*.tsx` | `/api/local-models/*` (status, hardware, catalog, runtime/install, quickstart, download[/pause\|/resume], models/{id}, server, eject, activate, jobs[/{id}], search[/files], download-browsed, sideload) |
| `keys` / `tools`, `settings` | `keys-settings.tsx`, `toolset-config-panel.tsx`, `env-credentials.tsx` | `GET /api/tools/toolsets`, `GET …/{name}/config`, `GET …/{name}/models`, `PUT …/{name}/model\|provider\|env`, `POST …/{name}/post-setup`; `GET/PUT/DELETE /api/env`, `POST /api/env/reveal`; OAuth start/poll |
| `billing` / `overview`, `plans` | `billing/*` | `billing.state`, `subscription.state`, `subscription.preview`, `subscription.change`, `subscription.resume`, `billing.charge`, `billing.charge_status`, `billing.auto_reload`, `billing.keep_going_on_credits`, `billing.step_up` + event `billing.step_up.verification` (WS legacy) |
| `web-access` | `web-access-settings.tsx` | `GET /api/webapp-access/status`, `POST /api/webapp-access/start\|stop\|restart` (REST, native-only) |
| `plugins` | `plugin-settings.tsx` | `plugins.manage` (WS legacy), `PUT /api/env` |
| `vault` / `credentials`, `sources` | `vault-settings.tsx` | `vault.list`, `vault.add`, `vault.remove`, `vault.lock`, `vault.unlock`, `vault.sources`, `vault.source.set` (WS legacy) |
| `sessions` / `archived`, `default-directory` | `sessions-settings.tsx` | `/api/profiles/sessions` (archived), session archive/delete REST; `GET/PUT /api/config sessions.auto_archive*`; Electron `settings.*` or `localStorage` |
| `gateway` (`connections` alias) / `connection`, `devices`, `managed-updates` | `gateway-settings.tsx`, `connections-registry.tsx`, `managed-updates-section.tsx`, `keychain-encryption-setting.tsx` | native: Electron bridge + `POST /api/gateway/restart`, `GET /api/status`; browser: `POST {base}/auth/logout` |
| `keybinds` / `shortcuts`, `hud-gesture`, `screen-capture` | `keybind-settings.tsx`, `hud-modifier-settings.tsx`, `screenshot-settings.tsx` | local; Electron `hudModifier`, `screenshot` |
| `notifications` / `alerts`, `sounds` | `notifications-settings.tsx` | local; Electron `notify` |
| `about` / `updates`, `uninstall` | `about-settings.tsx`, `update-channel-row.tsx`, `uninstall-section.tsx` | `GET /api/hermes/update/check`, `POST /api/hermes/update`, `GET /api/actions/hermes-update/status`, `GET /api/hermes/update/receipt` (REST); Electron `getVersion`, `updates.*`, `uninstall.*` |

Settings chrome (`index.tsx`): export (`GET /api/config`), import, and reset (`GET /api/config/defaults` + `PUT /api/config`). Profile scope comes from `$settingsScopeProfile` → `?profile=`. A config write targets the gateway that served the read (`bindConfigReadOrigin`).

#### Gaps / unverified

- `slash.shared.usage_suffix` description format, and whether command-stage `complete.slash` `text` includes the `/`.
- The exact `session.mutate model` receipt projection fields (beyond `session_id`, `operation`, `revision`, `status`).
- The JSON-RPC `id` format of server→client requests.
- Custom-endpoint list response fields. The font and `display.resume_last_session` config keys.
- `subscription.upgrade` use by the Desktop. The browser value of `localModelsEnabled`.
- Canonical `config.set reasoning scope:"global"` end-to-end: the Desktop comment calls it a refusal, but the server code accepts it.
- `full`/`clamp` reasoning words are refused on canonical.

### 2.4 Bots, profiles, group chats and pets

Transport labels used below:
- **WS canonical**: answered by the session authority (`gateway/session_controls.py` → `gateway/session_group_controls.py` for `groups.*` + `profiles.list`, `gateway/session_bot.py` for `bot_relay.deliver/settle`).
- **WS legacy**: the authority answers `-32601`, and `tui_gateway/ws_legacy_fallback.py` runs the `tui_gateway/server.py` handler. Examples: `profiles.create/describe/configure/set_asset/get_asset`, `pet.*`, `bot_relay.roster.sync/outbox.drain/reply`, `groups.peer.*`, `groups.promote/demote/replicate/replica_state`, `cli.exec`, `skills.manage`, `mcp.*`, `model.options`, `image.generate`.
- **REST**: `hermes_cli/web_routers/profiles.py`, same origin, cookie auth, JSON bodies.

Source map: renderer Bot Mode = `apps/desktop/src/plugins/hermes-bots/**`. Core profile panel = `apps/desktop/src/app/profiles/**`. REST wrappers = `apps/desktop/src/api/profiles.ts`. `apps/desktop/src/app/agents/index.tsx` is the **subagents** panel (`subagent.*` events), not bots; it is out of this slice.

#### Data model: one bot = one Hermes profile

**UI**: every Bot Mode surface. Types live in `plugins/hermes-bots/types.ts` (`RosterRow`, `BotMeta`).

**Notes**
- A profile is a directory: `~/.hermes` is the `default` profile, and `~/.hermes/profiles/<name>/` holds the rest. What a profile directory holds:

  | Item | Contents |
  |---|---|
  | `profile.yaml` | `description`, `description_auto`, `display_name`, `previous_names`, `ui_meta` (client namespaces), `_ui_meta_revisions` (per-key CAS counters) |
  | `SOUL.md` | Persona/system instructions |
  | `config.yaml` | `model.{provider,default,base_url}`, `platform_toolsets.cli` (toolset pin), `skills.disabled`, `mcp_servers.<name>.{url\|command,…,enabled}`, `stt/tts/voice` |
  | `.env`, `auth.json` | Secrets. Never exported. |
  | `skills/` | Installed skills |
  | `assets/avatar.{png,jpg,webp}` | Avatar image (one file) |
  | `state.db` | Sessions, including the canonical "Bot Chat" |
  | `pets/<slug>/` | Pets (see Pets) |
- Profile name (id) rule: `^[a-z0-9][a-z0-9_-]{0,63}$` (`hermes_constants.PROFILE_ID_RE`).
  - Reserved names: `hermes`, `default`, `test`, `tmp`, `root`, `sudo`. `default` is the root profile, so it can't be created, deleted or renamed to.
  - Names can't contain `:`, so roster keys use `<connectionId>::<profile>`.
- Handle: `default` → `@hermes`; any other profile → `@<name>`. A duplicate name across connections gets a source-qualified UI alias (`@name-device`). `message_agent` resolves only `<name>` or `<name>@<connectionId>`.
- Friendly names: the Bot Mode **title** (`ui_meta['hermes-bots'].title`) and the core **display_name** (`profile.yaml display_name`; set by renaming `default`).
  - Display precedence: title → display_name → name.
  - Mention forms are a slug and a collapsed form of each friendly name (`mentionNameForms`). These are never accepted: `all`, `everyone`, `user`, `default`, `hermes`.
- `BotMeta` lives at `ui_meta['hermes-bots']`. It is synced via `profiles.configure` and is also mirrored to plugin-local storage (`bot-meta-v2`):

  | field | type | meaning |
  |---|---|---|
  | `title` | string | friendly name shown in roster/mentions/group prompts |
  | `description` | string | legacy local copy (server description lives in profile.yaml) |
  | `shape` | string | `circle\|blob\|squircle\|pill\|triangle\|hexagon\|cloud\|drop`, or `blobatar`, `blobatar:<seed>`, `blobatar:<seed>:<kind>`, `blobatar::<kind>` (kind ∈ 10 `BLOB_KINDS`). Default for new bots = blobatar. |
  | `color` | string (CSS hex) | avatar color; default derived from the name (`profileColor`); `default` profile uses `#8b5cf6` squircle unless `custom` |
  | `imageKind` | `'photo'\|'shape'` | whether an image avatar is active |
  | `image` | data URL | **never sent in ui_meta** (stripped). It goes to `profiles.set_asset` instead. |
  | `custom` | bool | user changed shape/color/image (overrides default look) |
  | `pinned` | bool | pinned band at top of roster |
  | `hidden` | bool | hidden from roster (display only; bot keeps working) |
  | `sectionId`, `sectionName` | string\|null | user folder membership |
  | `groups` | string[] | legacy client-driven group rooms this bot is in |
  | `screenAutoOpen` | bool | auto-open Bot Screen pane |
  | `created` | number (ms epoch) | roster recency tiebreak |
  | `pet` | — | local-only extracted pet icon, never sent |
- `ui_meta` limits:
  - Total ≤ 64 KB JSON. Over the cap, the call silently returns `applied.ui_meta:false`.
  - Writes are key-wise merges: a `null` value deletes the key.
  - Every written key bumps `_ui_meta_revisions[key]`, which survives deletion.
  - Other namespace in use: `ui_meta['hermes-bots-groups']` on the **default** profile (the legacy group-room projection, see Groups).

#### Bot roster list (`profiles.list`)

**UI**: `roster-pane.tsx`, `roster-pane-content.tsx`, `roster-pane-sections.tsx`, `bot-row.tsx`. Data: `data.ts` `useRoster()` / `fetchRosterSnapshot()`.

**Calls**: `profiles.list`, WS canonical (`CanonicalProfilesParams` → `CanonicalProfilesResult`).
- Params:
  - `profile?: string`: route to that profile's authority. If given, it must resolve to the answering home, else `profile_mismatch`.
  - `include_sessions?: boolean`: default `true`. It must be a real JSON boolean; anything else gives `invalid_params`.
  - No other keys are allowed (`invalid_params`).
- Result: `{profiles: CanonicalProfileRow[], bot_mode_protocol: true, install_id: string}`.
  - `bot_mode_protocol: true` means the backend injects the teammate-messaging protocol into every system prompt, so the client must **not** append it to SOUL.md.
  - `install_id` is the machine identity; it equals `/api/status`.
- Row order: the answering profile's own **full** row first, then every other profile it can name. Those other rows are metadata-only: `model:''`, `provider:''`, `skill_count:0`, `display_name`, `ui_meta`, and no `ui_meta_revisions`/`has_avatar`.
  - A sibling the same process serves gets `last_session`/`worker_session`/`canonical_session` too. This needs a host-scoped socket with `include_sessions:true` (unverified for browser/native tickets).
  - To get a sibling's full row (revisions, avatar flag), call `profiles.list {profile:"<sibling>"}`. This is inferred from the routing; the Desktop does it per bot for CAS reads.
- Session fields:
  - `canonical_session`: the session titled exactly `"Bot Chat"`, owned by the caller (or the caller has `session:operator`), not archived. Fields: `{id, resolved_id (compression tip), title, root_title, preview ≤80 chars+"...", started_at, last_active, message_count}`, all times in epoch s.
  - `last_session`: the newest local, non-hidden, non-archived, non-internal-source session. Same shape. It equals `canonical_session` when the Bot Chat is newest.
  - `worker_session`: `{id, source, title, last_active}` of the newest kanban/tool worker (internal source).
- Legacy `profiles.list` (`tui_gateway/methods_profiles.py`, error 5061) returns a full row for **every** profile. It adds `previous_names: string[]` and `live_message_count`, and accepts `include_sessions` as bool or string. It is only reached on old/legacy transports.

Example canonical entry:
```json
{
  "name": "research",
  "path": "C:\\Users\\me\\.hermes\\profiles\\research",
  "is_default": false,
  "model": "claude-sonnet-4-5",
  "provider": "anthropic",
  "description": "Finds and summarizes papers",
  "display_name": "",
  "skill_count": 41,
  "ui_meta_revisions": {"hermes-bots": 7},
  "ui_meta": {"hermes-bots": {"title": "Research Buddy", "shape": "blobatar:k3x9", "color": "#22c55e",
    "imageKind": "shape", "custom": true, "pinned": true, "sectionId": "s-lq3f", "sectionName": "Clients",
    "created": 1761480000000}},
  "has_avatar": false,
  "last_session": {"id": "20261026_101500_ab12cd", "resolved_id": "20261026_101500_ab12cd", "title": "Bot Chat",
    "root_title": "Bot Chat", "preview": "Message from 🤖 hermes (@hermes): can you check…", "started_at": 1761400000,
    "last_active": 1761480123, "message_count": 58},
  "worker_session": {"id": "20261026_093000_ff00aa", "source": "kanban", "title": "Task #12", "last_active": 1761480100},
  "canonical_session": {"id": "20261026_101500_ab12cd", "resolved_id": "20261026_101500_ab12cd", "title": "Bot Chat",
    "root_title": "Bot Chat", "preview": "Message from 🤖 hermes (@hermes): can you check…", "started_at": 1761400000,
    "last_active": 1761480123, "message_count": 58}
}
```

**Events**: none. The roster is **polled**:
- `refetchInterval` and `staleTime` are 5000 ms.
- Retry twice, with backoff `min(15 s, 1 s·2^n)`.
- Server-side, session fields are cached per store signature (`tui_gateway/profile_roster_cache.py`), so polling is cheap.

**Notes**
- Profile hot-serve: with profile multiplexing, `create`/`rename`/`delete` (REST or WS) fire the `rescan-profiles` control verb (`gateway/run_profile_reconcile.py`, 30 s watcher as a safety net). A new bot is served without a gateway restart.
- Until a new profile is served, it shows as metadata-only (no `canonical_session`).
- A `config.yaml`/`.env` change on a served profile re-scans its adapters on the next 30 s tick.

#### Agents roster across connections

**UI**: `data.ts` `mergeMultiSourceRoster`, `annotateBotSource`, `roster-sections.tsx` (one automatic section per connection).

**Calls**: Desktop `host.agents()` → Electron `getAgentRoster`. In the **browser** it is `GET /api/profiles` (REST), mapped to `{agents:[{connectionId:'local', connectionKind:'local', connectionLabel: location.host, handle: name==='default'?'hermes':name, profile, targetProfile}], primaryConnectionId:'local', sources:[{connectionId:'local', kind:'local', label, reachable:true}]}`.

`GET /api/profiles` result: `{profiles:[{name, path, is_default, model, provider, has_env, skill_count, gateway_running, description, description_auto, display_name, bot_title, distribution_name, distribution_version, distribution_source, has_alias}]}`.

**Notes**
- Rich rows always come from the **active** connection's `profiles.list`. Rows from other connections are thin: `connectionId`, `connectionLabel`, `handle`, `remoteSource:true`, `sourceReachable`, plus credential-free `title`/`display_name`/`ui_meta`/`has_avatar`.
- A native app with several gateways should call `profiles.list` on each and merge, keyed by `connectionId::profile`.

#### Roster layout: sections, pin, hide, order, filters

**UI**: `roster-pane-toolbar.tsx`, `roster-pane-derivation.ts` (`sortRosterBots`), `user-sections.ts`, `user-sections-ui.tsx`, `hidden-bots.ts`, `bot-row.tsx` context menu.

**Calls**: every change is `saveBotMeta()` → `profiles.configure`, WS legacy, with CAS (see Edit look):
- pin: `{pinned}`
- hide: `{hidden}`
- move to section: `{sectionId, sectionName}`
- Bot Screen auto-open: `{screenAutoOpen}`

**Notes**
- Order: pinned band first, then by `max(created, last_active(canonical ?? last_session)·1000)` descending. Bots have no manual reorder; the default bot competes on recency.
- Sections:
  - The section list `[{id, name}]` (ordered) lives in plugin-local storage `bot-sections-v1`.
  - Membership is per bot (`sectionId` in ui_meta), so it follows the profile to other machines.
  - `adoptBotSectionsFromMeta` rebuilds missing sections from members' `sectionId`/`sectionName`.
  - "Unassigned" is the remainder, drawn last.
  - A deleted section clears members one write at a time (there is an Undo).
  - Section reorder (`moveBotSection`) is local only.
- Filters (local, session-only):
  - kind: `all | bots | groups`
  - activity: `active | recent (≤7 days) | older | all`
  - gateway/connection filter
  - text search (shown once there are ≥ 8 bots)
  - "show hidden" toggle (hidden bots are dimmed)
- Selected row: persisted in plugin storage `selected-roster-bot-v1` as `<connectionId>::<name>`.
- Activity toasts pref: plugin storage `activity-toasts` (default off).

#### Activity signals: working, active, unread, needs-input, needs-attention

**UI**: `bot-row.tsx`, `row-helpers.ts`, `roster-actions.ts` (`trackInboundActivity`), `bot-state.ts`, `app/chat/session-status-dot.tsx`.

**Calls**: none of their own. Everything derives from the 5 s `profiles.list` poll plus core session state.

**Events**
- The status dot reads core per-session state for `botCanonicalSessionId = canonical_session.resolved_id ?? id`, driven by the session event stream (sessions slice):
  - `needs-input` (amber): a clarify/approval is pending
  - `working`, `stalled`, `background`, `unread`, `draft`, idle
- `session.reclaimed` `{session_id, stored_session_id, reason}`: if it names the open Bot Chat's stored id, the plugin re-resolves and re-opens it in place (background, no navigation).
- Face mood / pulse:
  - `think`: a turn is busy on the focused owner (`host.state.busy`, from core `message.start…complete`), or the bot is a member of the active group.
  - `work`: `worker_session.last_active` is less than 150 s old.
  - Otherwise `idle`.
  - "active now" pulse: `last_active` is less than 90 s old.

**Notes**
- Unread is computed client-side: a per-bot `last_active` watermark is seeded on the first poll. When a later poll shows `last_active` past the watermark for a bot that isn't selected, the bot's canonical session is marked unread and a toast fires (if enabled; deduped by preview text; hidden bots never toast).
- The canonical Bot Chat is always hidden, so this poll is its only unread source.
- When the selected bot's chat moves server-side (cron/teammate/CLI), it is re-opened in place with `forceResume` to refresh the transcript.
- Needs-attention badge (`$botAttention`, display only, not persisted): set by a failed relay delivery or group turn, cleared by the next good turn.
  - Classes: `missing_config`, `provider_auth_or_access`, `provider_quota_limit`, `agent_blocked`.
  - These come from a typed `reason` or regex on the error text. Transient errors (429/5xx/timeout) never badge.
- Row age = `max(activity.last_active, worker_session.last_active)`.
- Preview = `canonical_session.preview` (else `last_session`). A DM prefix matching `^Message from (?:agent '…'|🤖[^:]+):\s*` is stripped and shown as "from bot".

#### Open the canonical Bot Chat

**UI**: `roster-actions.ts` `openRosterBot`, `canonical-chat.ts` (`openBotCanonicalChat`, `createCanonicalChat`, `findExistingCanonicalChat`).

**Calls** (all with the bot's `profile`):
1. Resolve: `session.list` (WS canonical) `{profile, title:"Bot Chat", limit:200, include_hidden:true}`. Match a row whose `root_title` is `"Bot Chat"`, or whose `title` is `"Bot Chat"` when `root_title` is empty. Open `resolved_id || id`.
   - **Fail closed**: on an RPC error, or on 0 rows when the roster's `canonical_session.id` exists, show "try again". Never mint.
2. If absent, mint (adopt-before-mint):
   1. `session.create` `{profile, title:"Bot Chat", hidden:true, follow_profile_config:true}` → `{session_id (runtime), stored_session_id}`.
   2. Immediately `session.title` `{session_id: <runtime>, title:"Bot Chat"}`. This materializes the lazy row and claims the unique title.
   3. If the error matches `/already in use/i`, someone else won: re-run step 1 and adopt the winner (the stray empty session is pruned).
   4. Open `stored_session_id`. Only on genuine Create Bot (or if the title write failed): wait 400 ms, then `prompt.submit` `{session_id, text: i18n 'bot.kickoff' ("Hey, tell me about yourself!")}`.
3. Opening = the core session-open flow (sessions slice) in the bot's workspace (`workspaceMode:'bots'`). An already-open canonical tile is just fronted, then refreshed.

**Notes**
- Identity is the **title**. Core has a UNIQUE(title) index per profile DB, so there is at most one "Bot Chat" per profile. No session-id pointer is stored client-side.
- Bot Mode sessions are always `hidden` (Bot Chats, and `Group: …` member sessions).
  - A load sweep (`session-sweep.ts`) re-hides rows titled `Bot Chat`, `Agent Inbox` or `Group: *` that are older than 5 min, via REST `PATCH /api/sessions/{id}` `{hidden:true}` (`?profile=`).
- Server side, `gateway/session_bot.py::_target` creates a missing Bot Chat on the first delivery (same title lock).
- `/new` or `/reset` typed inside a Bot Chat is rewritten to `/compact` (mention middleware), because the forever-chat never resets.

#### Side chats: new chat, recent session, open in tab

**UI**: `bot-row.tsx` context menu, `data.ts` `newBotChat`, `recent-session.ts`.

The menu items:
- Open Bot Chat
- Open Bot Chat in new tab (`intent:'tab'`)
- Bot Screen
- Auto-open screen
- Pin / Unpin
- Hide / Unhide
- Edit…
- Groups…
- Duplicate
- New chat with this bot
- Open recent session
- Move to section ▸
- Delete (not shown for `default`)

**Calls**
- "New chat with this bot": `host.newChat(route, {workspaceMode:'bots'})`, a core draft session on that profile. It becomes `session.create {profile,…}` on first send (sessions slice).
- "Open recent session": opens `last_session.id` as a tab, `session.resume`-style with `forceResume:true` (sessions slice). It is disabled if there is no `last_session`.

#### Create a bot

**UI**: `create-dialog.tsx` (New Agent). The dialog has these tabs:
- Name/title/description
- Look (avatar picker)
- Advanced: SOUL, Model, Capabilities (skills/toolsets/MCP)
- Clone-from picker
- Target connection

**Calls**
1. Capabilities preview, in parallel:
   - `profiles.describe {name:<clone source or "default">}`
   - `mcp.catalog {}`
2. `profiles.create` (WS legacy):
   - Required: `name: string`.
   - Optional:
     - `description?`
     - `clone_from?: string|null` (null = fresh + bundled skills)
     - `clone_all?: bool`
     - `clone_channels?: bool` (default false; strips bot tokens/allowlists)
     - `no_skills?: bool`
     - `no_alias?: bool`
     - `soul?: string`
     - `model?` + `provider?`
     - `share_auth?: bool` (share one auth pool instead of copying `auth.json`)
     - `mirror_credentials?: bool` (default true: copy launch `.env` (channel keys stripped) and `auth.json`, voice sections, and inherit the launch model when no model is given)
   - Result: `{ok, name, path, soul_written: bool, model_set: bool, mirrored:{env: bool, auth: bool|"shared", model_inherited: bool, voice: bool}}`.
   - Errors: 4061 name required; 4062 invalid/exists/clone source missing; 5062 other.
3. Only if the user changed them: `profiles.configure {name, disabled_skills?, enabled_toolsets?, enabled_mcp_servers?}`.
4. Look: `saveBotMeta(name, {shape, color, image, imageKind, title, created: Date.now()})` → `profiles.configure {name, ui_meta:{'hermes-bots':{…}}}`, plus `profiles.set_asset` if there is an image. For a remote target the same two calls go directly to that connection.
5. `setup.runtime_check {profile:name}`. If `ok:false`, show "created, needs a model" (with `error`) and skip the intro turn.
6. `createCanonicalChat(name, {kickoff:true})` (see Open the canonical Bot Chat).

Example:
```json
{"jsonrpc":"2.0","id":41,"method":"profiles.create","params":{
  "name":"research","description":"Finds and summarizes papers","clone_from":null,"no_skills":false,
  "share_auth":true,"soul":"# Research Buddy\n\n**Role:** Research Buddy\n…","model":"claude-sonnet-4-5","provider":"anthropic"}}
→ {"jsonrpc":"2.0","id":41,"result":{"ok":true,"name":"research","path":"C:\\Users\\me\\.hermes\\profiles\\research",
   "soul_written":true,"model_set":true,"mirrored":{"env":true,"auth":"shared","model_inherited":false,"voice":true}}}
```

**Notes**
- Default SOUL (`soul.ts composeSoul`):
  ```
  # <Title>

  **Role:** <title>
  **Mission:** <description>

  You are <Title>, a persistent named agent (profile `<name>`) on this machine.
  You keep your own memory, skills, and conversation history across sessions.
  ```
  The `## Messaging other agents` section is appended **only when** `bot_mode_protocol` is false (older backends).
- A cancelled draft deletes the minted profile (`DELETE /api/profiles/{name}`).
- REST twin `POST /api/profiles`:
  - Body: `{name, soul?, clone_from?, clone_from_default?, clone_all?, clone_channels?, no_skills?, description?, provider?, model?, mcp_servers?: [{name, url?|command?, args?, env?, auth?: "none"|"oauth"|"header", bearer_token?}], keep_skills?: string[] (replace semantics), hub_skills?: string[] (async install)}`.
  - Result: `{ok, name, path, model_set, model_error, mcp_written, skills_disabled, hub_installs:[{identifier, pid}], soul_set}`.
  - The REST twin does **not** mirror credentials.

#### Duplicate (clone) a bot

**UI**: `bot-row.tsx` → `profile-ops.ts duplicateBot`.

**Calls**
1. `profiles.create {name:"<base>-<n>" (n = 2…99, first free; base truncated to fit 64), clone_from:<base>, description}`. This clones config, skills, SOUL and memory.
2. `saveBotMeta(copy, {…look, title:"<title> (copy)"})`. `chat` and `created` are not copied.

#### Edit bot: title, description, look

**UI**: `edit-profile-dialog.tsx`, `edit-profile-look.ts`, `avatar-picker.tsx`, `profile-config.tsx` (Advanced).

**Calls**
- Look/title (`data.ts saveBotMeta` → `persistBotMetaPatch`), CAS loop with up to 3 attempts:
  1. `profiles.list {include_sessions:false}` (routed to the bot). Read `ui_meta['hermes-bots']` (drop `chat`) and `ui_meta_revisions['hermes-bots']`.
  2. `profiles.configure {name, ui_meta:{'hermes-bots': {...server, ...patch_without_image}}, ui_meta_expected_revisions:{'hermes-bots': rev}}`. The revisions map is omitted when the gateway reported none.
  3. If the reply has `applied.ui_meta_conflicts` → re-read and retry.
  - Outcome:
    - `persisted`: `applied.ui_meta === true`
    - `failed`: `applied` is present but `ui_meta` is not true
    - `unsupported`: no `applied` at all, or the call threw (local-only fallback)
- Description: `profiles.configure {name, description}`. This writes profile.yaml `description`, `description_auto:false`.
  - The Desktop actually sends `cli.exec {argv:["profile","describe",<name>,"--text",<desc>]}`. Either works.
  - REST alternatives: `PUT /api/profiles/{name}/description {description}` → `{ok, description, description_auto:false}`, and `POST /api/profiles/{name}/describe-auto {overwrite?}` → `{ok, reason, description, description_auto}` (an LLM writes it).

`profiles.configure` (WS legacy) full contract:
- Required: `name`.
- Optional sections (independent):
  - `ui_meta?: object` + `ui_meta_expected_revisions?: {key: int}`
  - `soul?: string`
  - `description?: string`
  - `model?` + `provider?` (+ `confirm_expensive_model?: bool`)
  - `disabled_skills?: string[]`
  - `enabled_toolsets?: string[]`
  - `enabled_mcp_servers?: string[]`
- Result: `{ok: all(applied), applied:{ui_meta?, ui_meta_revisions?: {key: int}, ui_meta_conflicts?: {key: {expected, actual}}, soul?, description?, model?, skills?, toolsets?, mcp_servers?}, confirm_required?, confirm_message?}`.
- Errors: 4063 name required, 4064 not found, 5064 other.

```json
{"jsonrpc":"2.0","id":52,"method":"profiles.configure","params":{"name":"research",
  "ui_meta":{"hermes-bots":{"title":"Research Buddy","shape":"blobatar:k3x9","color":"#22c55e","imageKind":"shape","custom":true}},
  "ui_meta_expected_revisions":{"hermes-bots":7}}}
→ {"result":{"ok":true,"applied":{"ui_meta":true,"ui_meta_revisions":{"hermes-bots":8}}}}
   // CAS loss: {"ok":false,"applied":{"ui_meta":false,"ui_meta_conflicts":{"hermes-bots":{"expected":7,"actual":8}},"ui_meta_revisions":{"hermes-bots":8}}}
```

**Notes**
- Only fields the user changed are sent, so another machine's newer write isn't reverted.
- Changing shape/color/image sets `custom:true`.
- Local writes stamp a 60 s freshness fence, so a roster snapshot issued before the write can't overwrite it.

#### Avatars (shape, color, photo, generated, pet)

**UI**: `avatar-picker.tsx`, `avatar.tsx`, `avatar-image.ts`, `pet.tsx` (Pet tab), `profile-ops.ts` (`pullServerAvatars`, `pushLocalAvatars`).

**Calls**
- Shape/color: ui_meta only (above).
- Upload photo: device picker (`image/png,jpeg,webp,gif`) → centre-crop to a 256×256 PNG data URL → `saveBotMeta({image, imageKind:'photo', custom:true})`. That triggers `profiles.set_asset` **only if the image changed**.
- `profiles.set_asset` (WS legacy):
  - Params: `{name, asset:"avatar" (only value), data: "data:image/(png|jpeg|webp);base64,…" or bare base64}`, or `{name, asset:"avatar", clear:true}`.
  - Limits: ≤ 2,000,000 bytes. The format is sniffed by magic bytes (the declared mime is ignored). The file is stored atomically as `assets/avatar.<png|jpg|webp>`, one file.
  - Result: `{ok, asset, size}`, or with clear: `{ok, asset, size:0, removed:n}`.
  - Errors: 4063, 4064, 4066 unknown asset, 4067 data required, 4068 bad base64, 4069 too large, 4070 unsupported format, 5065.
- `profiles.get_asset {name, asset:"avatar"}` → `{found:true, mime, size, data:"data:<mime>;base64,…"}` or `{found:false}` (5066). It is fetched only for rows with `has_avatar:true` that have no cached image.
- Generate: probe `image.generate {probe:true}` → `{available}`. Then `image.generate {prompt:"Cute minimal robot avatar for an AI agent named \"<title — description>\". Friendly simple mascot face, bold flat vector style, solid color background, centered, no text.", aspect_ratio:"square"}`, timeout 90 s → `{success, image_data (data URL), image (URL), error}`.
- Pet avatar (Pet tab): `pet.gallery {}` (browse, 24 per page), then `pet.thumb {slug, url?}` → `{ok, slug, dataUri}` (a server-cropped first frame, ~96×104 PNG). The dataUri becomes the bot `image` (photo). Thumbs are cached in an LRU of 120, 15 s timeout. See Pets.

**Notes**
- A 160 px PNG that is a raster of the vector face (`isBackfilledFacePng`) is ignored for the roster (the live SVG face is kept).
- Avatar images follow the profile across machines via the asset store. ui_meta never carries data URLs.

#### SOUL.md

**UI**: Bot Mode Advanced → SOUL (`profile-config.tsx`); core Profiles panel editor (`app/profiles/index.tsx`).

**Calls**
- Read:
  - `profiles.describe {name}` → `soul` (WS legacy), or
  - `GET /api/profiles/{name}/soul` → `{content, exists}` (REST, UTF-8-sig).
- Write:
  - `profiles.configure {name, soul}` → `applied.soul`, or
  - `PUT /api/profiles/{name}/soul {content}` → `{ok}` (atomic write, mode 0644).

**Notes**
- On old backends (`bot_mode_protocol` false) the Desktop appends the messaging-protocol section before saving and backfills it into existing SOULs. On current backends the client must not do this.
- SOUL is read per turn by the agent (unverified whether a running session picks it up mid-session; a new turn or a new session does).

#### Per-bot skills, toolsets, MCP servers

**UI**: `profile-config.tsx` (Capabilities tab), `skills-hub.tsx`, `mcp-setup.tsx`.

**Calls**
- Snapshot: `profiles.describe {name}` (WS legacy, errors 4063/4064/5063) → `{name, description, soul, model:{provider, default}, skills:[{name, enabled}], toolsets:[{name, label, description, tool_count, enabled}], toolsets_pinned: bool, mcp_servers:[{name, enabled, transport: "http"|"sse"|…|"stdio"}]}`.
  - Toolsets hidden by default (default-off integrations) appear only when enabled.
  - Installed skills are enabled unless listed in `skills.disabled`.
- Save: `profiles.configure {name, disabled_skills?, enabled_toolsets?, enabled_mcp_servers?}`. All three are **replace** semantics.
  - Desktop sends `enabled_toolsets: []` when all or none are checked, which clears the `platform_toolsets.cli` pin.
  - An `enabled_mcp_servers` name not defined in the profile is copied from the **launch** profile's `mcp_servers` (raw `${VAR}` refs kept; unknown names skipped). The `enabled` flag is written and the legacy `disabled` key dropped.
- MCP catalog/setup, all with `profile:<bot>`:
  - `mcp.catalog {profile}`
  - `mcp.servers.list {}`
  - `mcp.servers.add {profile, name, preset}`
  - `mcp.servers.set_api_key {profile, name, env_var, value}`
  - `mcp.servers.test {profile, name}`
  - OAuth via `host.completeMcpOAuth` (`mcp.servers.oauth.*`; MCP slice)
- Skills hub:
  - `skills.manage {action:"search", query}` → `{results}`
  - `skills.manage {action:"install", query:<name>, profile:<bot>}`

**Notes**: MCP/toolset changes take effect on the profile's next agent build (new turn/session); `reload.mcp` is blocked on the shared owner (unverified exact moment).

#### Model / provider per bot

**UI**: `model-picker.tsx`, `profile-config.tsx`.

**Calls**
- Options: `model.options {profile?, include_unconfigured:true, explicit_only:false}` (routed to the bot; staleTime 120 s).
- Set: `profiles.configure {name, model, provider}`.
  - If the model is guarded (expensive or data-policy), the reply is `{confirm_required:true, confirm_message}` and **nothing is written**. Resend `{name, model, provider, confirm_expensive_model:true}` after the user confirms.
  - The pin is validated against the catalog, and writes `config.yaml model.{provider, default}`.
- REST alternative: `PUT /api/profiles/{name}/model {provider, model}` → `{ok, provider, model}` (400 if either is missing). It has no confirm gate.
- Clear model: `cli.exec {argv:["--profile",<name>,"config","unset","model"]}` → `{code, output, blocked?, hint?}`.

**Notes**: the Bot Chat is created with `follow_profile_config:true`, so it always runs the profile's **current** model; no restart is needed.

#### Profile config and env

**Notes**
- No dedicated per-profile env editor exists in Bot Mode. Credentials come from `profiles.create` mirroring, or from per-server `mcp.servers.set_api_key`.
- Generic config/env reads and writes use `config.get`/`config.set` with `profile` (WS canonical; config slice) and REST `GET/PUT /api/config?profile=<name>`, which is scoped by `_config_profile_scope` (home + secret scope).
- `.env`/`auth.json` are never returned or exported.

#### Rename a profile

**UI**: `app/profiles/rename-profile-dialog.tsx`. Bot Mode has no profile rename; it renames the **title** instead.

**Calls**: `PATCH /api/profiles/{name}` `{new_name}` (REST).
- Result: `{ok, name: <normalized new>, path}`.
- For `default`: `{ok, name:"default", display_name:<new>, path}` (only `display_name` changes).
- Errors: 400 (invalid name / exists / reserved), 404, 500.

**Notes**: what a rename does:
- Stops the profile's own gateway (polls up to 10 s).
- Unroutes it from the multiplexer, then moves the directory.
- Migrates profile-keyed session/routing state and rewrites the wrapper alias.
- Re-serves under the new name.
- Records `previous_names`; the legacy list carries it and the UI uses it for member/mention matching.

No gateway restart is needed. Sessions (including the Bot Chat) move with the directory.

#### Delete a bot/profile

**UI**: Bot Mode Delete (`profile-ops.ts deleteBot`), core `app/profiles/delete-profile-dialog.tsx`.

**Calls**: `DELETE /api/profiles/{name}` (REST; Desktop `host.deleteProfile` uses the same route).
- Result: `{ok, path}`, or `{ok:true, path, identity_settled:false, settlement_pending:true, retry_command}` when the directory is gone but identity cleanup is pending.
- `default` can't be deleted: the client refuses, and the server returns 400.
- Old fallback: `cli.exec {argv:["profile","delete",<name>,"--yes"]}`.

**Notes**
- The server stops the profile's gateway (polls up to 10 s), tombstones it, unserves it from the multiplexer, then `rmtree`s it.
- Client cleanup:
  - drop local meta
  - forget unread for both canonical ids
  - clear watermarks and selection
  - invalidate the roster
  - if the deleted bot was focused, open a new chat on `default`
- The Desktop first retires the local pooled backend so it can't re-create the directory mid-delete.

#### Profile import / export

**UI**: `store/profile-share.ts` (Settings/profile menu).

**Calls** (REST; paths, not bytes):
- `POST /api/profiles/{name}/export` `{extra_files?: {filename: text}, output?: path}` → `{ok, archive: "<path>.tar.gz"}`.
  - With no `output`, the archive goes to a staging dir under HERMES_HOME.
  - Credentials are excluded and staged text is force-redacted.
  - Desktop adds `desktop.json` = `ProfileDesktopOverlay {version:1, skin, mode, themes?, profileColor, layoutTree, layoutPreset?}`.
- `POST /api/profiles/import` `{archive: <server-side path>, name?: string}` → `{ok, name, path, desktop: overlay|null}`.
- `GET /api/profiles/{name}/desktop-overlay` → `{exists, desktop}`.

**Notes**: the archive path is on the **gateway's** filesystem. A remote native client can't upload or download the bytes through these routes; it would need the file routes (files slice) or another channel (gap).

#### Core Profiles panel (non-Bot-Mode)

**UI**: `app/profiles/index.tsx`, `create-profile-dialog.tsx`.

**Calls** (REST):
- `GET /api/profiles` (list)
- `POST /api/profiles {name, clone_from, soul?}`. If `soul` is set but `soul_set` is false, follow with `PUT …/soul`.
- `GET/PUT /api/profiles/{name}/soul`
- `PATCH` / `DELETE /api/profiles/{name}`
- `GET /api/profiles/{name}/setup-command` → `{command: "hermes setup" | "<name> setup"}`
- `POST /api/profiles/{name}/open-terminal` → `{ok, command}` (spawns a terminal on the **server** machine)
- `GET /api/profiles/active` → `{active (sticky CLI default), current (this dashboard's profile)}`
- `POST /api/profiles/active {name}` → `{ok, active}` (affects future CLI runs only)

#### Bot-to-bot messaging and @mentions

**UI**: `plugin.tsx` composer middleware `mention-middleware` and autocomplete `mention-completions`.

**Calls**: none for delivery. Before submit, the middleware appends an identification note to the user text. If the roster cache is cold it calls `profiles.list {include_sessions:false}`. The note looks like this:

`\n\n[@mentions resolved from the Bot Mode roster — the user is referring to: @research = agent profile "research" ("Research Buddy"); … If they want one of these agents contacted, compose your own message and send it with your message_agent tool (agents on other connected machines are reachable too — the Desktop relays it); never forward the user’s text verbatim. If this session has no message_agent tool, agent messaging is unavailable here — say so.]`

**Notes**
- Delivery is the agent's `message_agent` tool, server-side. Within one install it goes straight into the target's Bot Chat via the authority. The UI sees it only as roster activity (preview `Message from 🤖 <title> (@handle): …`).
- Remote targets are annotated `message_agent target: "<name>@<connectionId>"`.

#### Bot relay across connections (`bot_relay.*`)

Only needed when the client holds sockets to **several gateways/machines**. A single-gateway native app can skip it; in-install delivery needs no client.

**UI**: `relay.ts`, `relay-budget.ts`. Started when Bot Mode is on and at least 2 connections exist.

**Calls**
- `bot_relay.roster.sync` (WS legacy, error 5090), every 60 s on **each** gateway:
  - Params: `{agents: RelayAgentRow[]}`, where each row is `{profile, handle, connection_id, connection_label, title, description, online?}`. These are the agents of the **other** connections, built from their `profiles.list {include_sessions:false}`. Send `[]` to clear.
  - Result: `{count}`.
  - The rows are stored install-wide so `message_agent` can resolve `name@connection`.
- `bot_relay.outbox.drain` (WS legacy, 5091) `{}` → `{envelopes: [{id, created_at, from_profile, from_handle, target_connection, target_profile, target_handle, message, …}]}`.
  - Claimed envelopes move to `claimed/` (no double delivery) and are replayed until replied.
  - Envelopes older than 900 s are refused with reason `queued_expired`.
  - Triggered by the push event (debounced 250 ms), with a 30 s fallback tick.
- `bot_relay.deliver` on the **target** connection, with `profile = target_profile`, timeout ≈ 22 min (`RELAY_DELIVER_TIMEOUT_MS` = 120 s + 2×600 s + 180 s):
  - WS canonical params (`CanonicalBotDeliverParams`, closed key set):
    - Required: `id` (32–64 lowercase hex = envelope id), `message` (≤ 16,200 chars with author; ≤ 1 MiB without).
    - Optional: `profile`, `session_id`, `author: {id, name, is_bot}`, `notification_category`.
  - The Desktop sends legacy `{id, profile, message, from_profile, from_handle, from_connection}`. `api/canonical-protocol.ts` translates these to `author = {id: "bot:<from_connection>/<from_profile>", name: <from_handle sans @>, is_bot:true}`, and rewrites the message stamp `Message from 🤖 X (@h): ` to `(@h@<connection>)`.
  - Result `CanonicalBotDeliverResult`: `{status: "queued"|"claimed"|"ambiguous"|"settled"|"failed"|"cancelled", delivery_id, profile_home, session_id, admission_id?, refused?: true, message, reply?, retry_admission_id?, error?, reason?}`.
  - A result either names an `admission_id`, or is `refused:true` + `failed`.
  - Idempotent per `id`: a re-send returns the same receipt. Different content under the same id gives `admission_conflict`.
- `bot_relay.settle` (WS canonical): same params. It finalizes a refusal marked `admitted:false` and returns the terminal receipt.
- `bot_relay.reply` on the **sender** connection (WS legacy):
  - Params: `{id, reply?, error?, reason?}` → `{ok:true}`.
  - Errors: 4093 id required, 4094 bad id, 5095.
  - Send it only when the `deliver` status is `settled` (with `reply`) or `failed` (with `error`, `reason`). Otherwise keep the envelope for the next drain.
  - On a thrown error with `data.reason` other than `runtime_unavailable`, reply with that error/reason. On `runtime_unavailable` or no reason, keep the envelope for retry.
- Legacy `bot_relay.deliver` (non-canonical gateways) errors: 4090 invalid_params, 4092 unknown_profile, 5094 runtime_unavailable.

**Events**: `bot_relay.outbox.pending`, `ChangeSignalPayload` `{}`. `tui_gateway/change_watcher.py` emits it on a 1 s signature check when an outbox envelope is queued.
```json
{"jsonrpc":"2.0","method":"event","params":{"type":"bot_relay.outbox.pending","payload":{}}}
```

**Notes**
- Deliveries are serialized per `target_connection::target_profile` lane.
- A settled delivery clears the target's needs-attention badge; a failed one sets it from `reason`.
- `a2a.forward` (WS canonical; `{agent, tenant, peer, context_id, input_id, text}` → admission status + `session_id`) is the server's A2A-protocol ingress. No UI uses it.

#### Bot Screen (headless desktop per bot)

**UI**: `screen-pane.tsx`, `screen-portal.tsx`, `screen-hero.tsx`, `screen-install.tsx`, `screen-connection.ts`.

**Calls** (WS legacy, routed to the bot): `display.status`, `display.observe`, `display.start`, `display.lease.acquire`, `display.lease.release`, `display.thumbnail`, `display.install`. The view is a noVNC RFB stream over a sibling ticketed WebSocket.

**Events**: `display.status`, `display.lease`, `display.install.log`, `display.install.done` (display slice; details unverified here).

#### Restart matrix (bots/profiles)

| change | effect | restart? |
|---|---|---|
| create / rename / delete profile | multiplexer `rescan-profiles` signal, 30 s watcher safety net | no |
| `ui_meta` / title / pin / hide / section | next `profiles.list` poll (≤ 5 s) | no |
| avatar asset | `has_avatar` on the next poll; clients fetch `get_asset` | no |
| SOUL / skills / toolsets / MCP | applies on the next agent build (turn/session) (unverified exact point) | no |
| model / provider | Bot Chat follows profile config (`follow_profile_config`) | no |
| bot token / platform channel in `.env`/config | adapters re-scanned on a 30 s tick | no |


#### Group chats: two engines, and how the UI picks one

**UI**: `apps/desktop/src/plugins/hermes-bots/group-chat-view.tsx` (`GroupChatWorkspace` → `GroupExecutionGate`), `canonical-group-capabilities.ts` (`groupExecutionMode`, `useGroupCapability`), `create-dialog.tsx` (`CreateGroupChatDialog`), `roster-pane-toolbar.tsx` (menu entry gate).

**Calls**: `groups.capabilities` (WS canonical) `{profile}` → used only to classify:
- `driver === true` → **canonical** (gateway-hosted room, `CanonicalGroupWorkspace`). This is the current path.
- `driver === false && persistent_process === false` and the answer has none of `protocol_version`, `authority_gateway_id`, `methods ∋ "groups.create"`, `features ∋ "canonical_session_owner"` → **legacy** (client-driven rooms, `LegacyGroupChatWorkspace`).
- anything else, an error, or a closed gateway → **unavailable** ("driver unavailable" text; no composer).

**Notes**
- Both current gateway paths return `protocol_version`, so "legacy" only triggers on old gateways that predate hosted rooms (unverified that any still in use answer that shape). A new client can implement **canonical only**.
- Mode is re-checked right before create (`revalidateGroupCreationMode`: a second `groups.capabilities` call that must give the same mode, on the same connection/profile/activation epoch).
- A legacy room opened by name on a canonical gateway: `groups.list` is searched by `name`; if the authority answers and the room isn't there, the UI offers "Start gateway group" (`groups.create` with the legacy members). The old Desktop history is not replayed.

---

#### Group room routing and authority (canonical)

**Calls**: every room RPC goes on the one `/api/ws` socket with `params.profile` = the **room's authority profile** (`route.profile`). The renderer uses `host.requestProfile({connectionId, profile, targetProfile: profile})`, which never falls back to the foreground gateway (`canonical-groups.ts` `canonicalGroupRequest`).

**Notes**
- The authority is the profile that was active (`host.state.profile`) in the window when the room was created (`captureCanonicalGroupRoute`). Members are other local profiles seated in that authority's room. One room = one authority profile + `room_id`.
- Served by `gateway/session_group_controls.py` (`dispatch_group_control`) for these 14 methods: `groups.capabilities`, `groups.list`, `groups.state`, `groups.log`, `groups.create`, `groups.rename`, `groups.disband`, `groups.send`, `groups.attachment.upload`, `groups.attachment.download`, `groups.stop`, `groups.retry`, `groups.discard`, `groups.approve`. These are the only `groups.*` the Desktop UI calls.
- Capability needed per method: `session:read` (capabilities, list, state, log, attachment.download), `session:control` (create, rename, disband, stop, retry, discard), `session:submit` (send, attachment.upload), `session:approve` (approve).
- **Strict param sets on canonical.** Any key outside the method's field set (plus `profile`) → `invalid_params`. Allowed keys:
  - capabilities: none
  - list: `limit`, `offset`, `include_disbanded`
  - state: `room_id`, `include_disbanded`
  - log: `room_id`, `since_seq`, `limit`, `include_disbanded`
  - create: `room_id`, `name`, `members`
  - rename: `room_id`, `event_id`, `name`
  - disband / stop: `room_id`, `cancel_id`
  - send: `room_id`, `event_id`, `payload`
  - attachment.upload: `room_id`, `upload_id`, `kind`, `name`, `mime`, `data_base64`
  - attachment.download: `room_id`, `event_id`, `attachment_id`
  - retry / discard: `room_id`, `member_id`, `task_id`, `execution_generation`
  - approve: retry's keys + `choice`, `request_id`
- `profile` must resolve to the connection's authority home, else `profile_mismatch`.
- **Room ownership.** `groups.create` records the caller's auth subject as the owner (`state_meta` key `gateway.hosted.owner.v1:<room_id>`). Every later call checks that owner, so another subject gets `permission_denied`, and `groups.list` hides rooms the caller doesn't own.
- **Errors (canonical).** JSON-RPC `error.code` 4001, `message` = the reason, `data = {reason, field?, detail?}`. Reasons:
  - `permission_denied`, `profile_mismatch`, `invalid_params` (create names the refused member in `detail`, with `field: "members"`)
  - `runtime_coordination_required`: hosted driver not running, on send/stop/retry/discard/approve/attachments
  - `stale_generation`, `unknown_execution`, `session_busy`, `unsupported_operation` (retry/discard on a peer member)
  - `room_history_expired`, `authority_conflict`
  - every other `HostedRoomError` maps to `invalid_params`
  - `-32601` = unknown method
- **Legacy fallback.** `groups.peer.invite|register|revoke`, `groups.promote`, `groups.demote`, `groups.replicate`, `groups.replica_state` are not canonical. They answer -32601 there and fall through to `tui_gateway/methods_groups.py`. No current UI calls them.
- **Legacy errors** (`tui_gateway/methods_groups.py`, a plain TUI sidecar): numeric codes. For example 4110–4119 carry `data.reason` from a `HostedRoomError`, 4123 = "Group Chat worker is unavailable. Restart the Hermes gateway and try again.", and 5110–5120 are generic failures.
- **Limits** (`gateway/hosted_rooms.py`, `hosted_room_discussion.py`):
  - room_id / event_id: ≤128 chars, regex `^[A-Za-z0-9][A-Za-z0-9._:-]*$`
  - room name: ≤200 chars (the UI cuts names to 64)
  - members: 2–6
  - active rooms: ≤256 per host
  - log page: ≤500 events and ≤2 MB
  - user text: ≤64 KB
  - events per room: ≤50 000

---

#### List group chats (sidebar)

**UI**: `canonical-group-registry.tsx` `CanonicalGroupList` (rendered at the top of `roster-pane.tsx`). Open = `openGroupChat(key,'key')`. Keys look like `canonical:<enc(connectionId)>:<enc(profile)>:<room_id>`. Name aliases look like `alias:<conn>:<profile>:<name>`, kept in the in-memory atom `$canonicalGroupBindings`. After a restart a key or name is resolved again through `groups.list`; same-name rooms resolve to the most recently updated one.

**Calls**
- `groups.capabilities` (WS canonical) `{profile}`, then page through `groups.list`.
- `groups.list` `{profile, limit?: int (default 500, UI sends 100), offset?: int (default 0), include_disbanded?: bool}` → `{rooms: Room[], next_offset: int|null}`.
  - Loop until `next_offset === null`.
  - `next_offset` is an opaque continuation cursor over the *visible* rooms, not a page index.
  - Order: `updated_at` DESC, then `room_id`.
- `Room` fields:
  - `room_id`, `name`, `members: RoomMember[]`
  - `authority_gateway_id` (the host install id), `authority_epoch`, `revision`
  - `created_at` / `updated_at`: float epoch **seconds**
  - `idempotent`, `disbanded_at?`, `latest_seq?`

**Events**: none. The list re-reads on a connection or profile change, on the Refresh button, and after a disband (an internal revision bump). It is not polled.

**Notes**
- Pin, order and section filing exist only for **legacy** rooms (see "Group pin / order / sections"). The canonical list is a plain button list in server order.

---

#### Create a group chat

**UI**: `create-dialog.tsx` `CreateGroupChatDialog`.
- Pick 2–6 bots (search and checkboxes), an optional name and an optional image (the image is used by legacy rooms only).
- Default name = the member display names joined by ", ", cut to 64 chars.
- Bots offered = `seatableGroupBots(...)`: bots on the current connection whose target profile is in `groups.capabilities.seatable_profiles`; when the field is absent, the connection check alone applies. Outage placeholder (ghost) rows are never offered.

**Calls**: `groups.create` (WS canonical) `{profile, room_id: string(UUID v4), name: string, members: RoomMemberInput[]}`.
- Member shape the UI sends:
  ```json
  {"member_id":"<profile>","profile":"<profile>","handle":"<handle or profile>","target":{"kind":"local","profile":"<profile>"},"display_name":"<optional>"}
  ```
- Server validation (`validate_roster`):
  - required `member_id`, `profile`, `handle`; optional `display_name` (≤200 chars) and `target`
  - `target`: `{kind:"local",profile}` or `{kind:"peer",peer_id,installation_id,profile,capability_digest:sha256hex}`; canonical seats local members only
  - unique member_ids, handles (case-insensitive) and targets
  - handles `all` and `everyone` are reserved
  - cross-gateway keys (`connectionId`, `route`, `targetProfile`, …) are refused
  - profiles that can't be seated → `invalid_params` with a `detail`
- Result `{room: Room}`. Create is idempotent for the same `room_id` with identical name and members; different state → conflict error.

**Notes**
- **Seatable profiles** (`gateway/session_hosted_members.py member_roster`): the authority's own profile, plus served siblings when the socket is host-scoped, plus usable entries under `hosted_rooms.profiles` in config. Parked or retiring bots are refused.
- Creating doesn't need the driver running: without the service the room is still written to the DB. Sending does need it (`runtime_coordination_required`).
- After create the UI calls `registerCanonicalGroup(route, room)` and opens the key.
- No `room.created` log event is written; the log starts empty (`latest_seq` 0).

---

#### Room view: polling, state, members, activity

**UI**: `canonical-group-workspace.tsx` `CanonicalRoomView`, `canonical-group-header.tsx` (title, roster chips, Stop / Rename / Disband), `canonical-group-history.tsx` (log rendering), `canonical-group-activity.tsx` (per-member spinner, "working" line).

**Calls**: every **2000 ms** while the view is visible, the UI calls `groups.state` and then reads the **whole log** again from `since_seq: 0`:
- `groups.state` `{profile, room_id, include_disbanded?: bool}` → `{room: Room, driver_status?: RoomDriverStatus}`. `driver_status` is present only when the hosted driver is running and the room is live. Without it the UI shows "driver unavailable" and disables Send and Stop.
  - `RoomDriverStatus` fields:
    - `running`, `working` (= any member active), `blocked` (the room is blocked, or a task is `indeterminate` / `stopping`)
    - `counts`: `{<task status>: n}`, status ∈ `queued | running | stopping | settled | failed | cancelled | indeterminate | deferred`
    - `member_activity`: `[{member_id, status}]`, live tasks only (`queued | running | stopping`)
    - `pending_actions[]` (see "Approvals, retry, discard")
    - `peer_routes[]`: `{room_id, member_id, status}`
  - UI phase per member: any non-`queued` live task = **working** (spinner); `queued` = "· queued". The "working" line lists working members, or shows a generic line when `working` is true.
- `groups.log` `{profile, room_id, since_seq?: int ≥0 (default 0), limit?: int ≤500 (default 100), include_disbanded?: bool}` → `{events: RoomEvent[], cursor: int, latest_seq: int, has_more: bool, authority: {gateway_id, epoch}}`.
  - Page until `has_more` is false, using the last event's `seq` as the next `since_seq`.
  - `since_seq > latest_seq` → error ("since_seq is ahead…").
  - Pages are also cut at 2 MB of serialized JSON.

**Events**: **no WS notification exists for rooms**. `BackendGatewayEventMap` has no `groups.*` or room event, and the workspace subscribes to nothing. Refresh is polling only, plus a `refresh()` right after each mutation.

**Notes**
- A native client should poll incrementally: keep a cursor and call `groups.log since_seq=<last seq>`. The full re-read the Desktop does is O(log) per tick (UI choice, not a protocol need).
- `RoomEvent` fields:
  - `room_id`, `seq` (1-based, strictly increasing per room), `event_id`, `kind`
  - `actor: {kind, id, profile?, display_name?, connection_id?}`
  - `authority_epoch`, `payload`
  - `created_at`: float seconds
  - `idempotent`
- The history renders only message kinds and notice kinds (see "Room event kinds"). `room.activity` renders only when `status: "bounded"` ("work limit reached").
- Message text goes through the shared markdown renderer with `media={false}`, so `MEDIA:` paths from another machine never resolve.
- Author label: `actor.display_name` → roster `display_name` → `handle` → `actor.id`.

---

#### Room event kinds (the `groups.log` payload vocabulary)

The actor kind decides which event kinds it may append (`_EVENT_KINDS_BY_ACTOR`, `gateway/hosted_rooms.py`).

| kind | actor.kind | payload (exact fields) | event_id pattern |
|---|---|---|---|
| `message.user` | `user` (server-owned; the client never sets the actor) | `{text: string (trimmed, may be "" if attachments), thread_id: string, attachments?: Manifest[]}` | `user:<sha256(client event_id)>` |
| `message.member` | `member` (`{kind:"member", id: member_id, profile, connection_id? (peer)}`) | turn coordinates + `{text (never "(pass)"), attachments?, recipient_member_ids?: string[]}` | `dmessage:<task digest>` |
| `turn.settled` | `gateway` (`id` = authority gateway id) | coords + `{seen_through_seq, passed: bool, message_event_id: string\|null}` (null iff `passed`) | `dterminal:<digest>` |
| `turn.failed` | gateway | coords + `{seen_through_seq, error: string, reason_code?: string}` (shared failure vocabulary `tools/bot_failure_reasons.ALL_REASONS`) | `dterminal:<digest>` |
| `turn.cancelled` | gateway | coords + `{seen_through_seq, reason: string}` (also published when a newer user message in the same thread supersedes a late result) | `dterminal:<digest>` |
| `turn.deferred` | gateway | coords + `{seen_through_seq, execution_generation: int, reason: string}` | `ddeferred:<digest>:g<gen>` |
| `room.activity` | gateway | `{status: "settled"\|"bounded", reason_code: "silent_round"\|"max_rounds"\|"max_messages"\|…, thread_id, discussion_event_id}` | `dactivity:<discussion_event_id>:<reason>` |
| `room.stop_requested` | gateway | `{cancel_id}` | (server) |
| `room.renamed` | `system` (`id: "room-control"`) | `{name}` | client `event_id` from `groups.rename` |
| `room.disbanded` | system | `{room_id}` | `system:room-disbanded` |
| `authority.claimed` | system | `{previous_gateway_id, authority_gateway_id, authority_epoch}` | (server) |
| `authority.lost`, `member.unavailable`, `turn.started`, `turn.reassigned`, `room.created`, `room.members_changed` | allowed by the schema; no emitter found in this tree (unverified) | — | — |

- **Turn coordinates** = `{discussion_event_id (the triggering message.user event_id), member_id, member_index (0-5), round_index (0-2), task_id ("dtask:<digest>"), thread_id, turn_id ("d<seq>.r<round>.p<pos>.s<seen>.m<24hex>")}`.
- **UI notice labels**: `turn.failed`, `turn.cancelled`, `turn.deferred`, `member.unavailable`, `room.stop_requested`, `room.disbanded`, `authority.lost`, and `room.activity` when `bounded`. Detail text = `payload.error || reason || reason_code`.

---

#### Send a message (text, @mentions, attachments)

**UI**: the composer in `canonical-group-workspace.tsx`. `GroupMentionInput` (`group-chat-parts.tsx`) gives an @-popover built from the room's member handles plus `@everyone` / `@all`. Paste or drop of files uploads them; pasted text stays in the composer.

**Calls**: `groups.send` (WS canonical) `{profile, room_id, event_id: string (client UUID = idempotency key), payload: {text: string, thread_id: string, attachments?: Manifest[]}}`.
- Payload keys are exact: anything other than `text`, `thread_id`, `attachments` → invalid.
- Text plus attachments must not both be empty.
- `Manifest` = exactly `{attachment_id, kind, name, size, mime}`, taken from the upload result.
- Result: `{event: RoomEvent (message.user), client_event_id, accepted: true, driver_started: true}`. Retrying the same `event_id` with the same payload returns the original event; a different payload with that `event_id` → conflict.

```json
{"jsonrpc":"2.0","id":17,"method":"groups.send","params":{
  "profile":"default",
  "room_id":"6b0d2c1e-7a4f-4f43-9a1d-2f1c8e9b5a10",
  "event_id":"e3a9f6d2-1c4b-4b8e-8f0e-5d7c2a1b9e34",
  "payload":{
    "text":"@coder fix the failing test, @reviewer check the diff",
    "thread_id":"e3a9f6d2-1c4b-4b8e-8f0e-5d7c2a1b9e34",
    "attachments":[{"attachment_id":"att_4f1c…","kind":"image","name":"error.png","mime":"image/png","size":48213}]
  }}}
```

**Notes**
- **Send journal (crash safety).** The UI journals one outstanding send per room before calling `groups.send` (`canonical-group-send.ts`):
  - Electron: `window.hermesDesktop.preparedSubmissions.compareAndSet`. Browser: `localStorage['hermes.desktop.canonicalGroupSends.v1']`.
  - Journal key: `["canonical-group-send-v1", connectionId, profile, roomId]`.
  - The entry is retired only after a definite ACK. On a timeout the composer locks and the button reads "Retry", which resends the exact same `event_id` and payload.
- **Threads.** The UI sets `thread_id = event_id`, so every composer message starts a new thread.
  - Member prompts contain only the triggering thread's messages, as a delta past each member's watermark.
  - Whether a member's own backing session keeps context across threads (unverified).
- **Mentions** (server `resolve_mentions`, regex `@([A-Za-z0-9][A-Za-z0-9._:-]*)`, case-insensitive against member `handle`):
  - Round 0: the members mentioned in the user text; no mention, `@all` or `@everyone` → every member, in rotated order.
  - Rounds 1–2: only peers a member's reply explicitly @-cited and who haven't spoken since.
  - Limits: ≤3 rounds and ≤10 member messages per discussion, then `room.activity` `bounded`.
  - A reply of `(pass)` (or empty) = silence: `turn.settled passed:true`, no `message.member`.
  - A round where nobody posts ends the discussion: `room.activity settled reason_code silent_round`.
- Pending discussion = the oldest latest-per-thread `message.user` that is newer than the last `room.stop_requested` and has no `room.activity` settled or bounded yet.

---

#### Attachments in rooms

**UI**: `canonical-group-attachments.tsx`:
- Kind detection: `image/*` → `image`, `application/pdf` → `pdf`, else `file`.
- Image previews load lazily near the viewport, only for images of 5 MB or less, two concurrent downloads per connection.
- Download saves the file through a Blob URL (`canonical-attachment-download.ts`).

**Calls**
- `groups.attachment.upload` (WS canonical) `{profile, room_id, upload_id: string (UUID; idempotency per upload_id), kind: "image"|"pdf"|"file", name, mime, data_base64: string (raw base64, no data: prefix)}` → `RoomAttachment {attachment_id, kind, name, size, mime, sha256, state ("uploaded"→"committed"), created_at, idempotent, event_id?}`.
  - Only `{attachment_id, kind, name, mime, size}` goes into the send manifest.
  - Image and PDF bytes must match their kind and mime.
- `groups.attachment.download` (WS canonical) `{profile, room_id, event_id (the message event carrying it), attachment_id}` → the same fields + `data_base64`.

**Notes**
- Limits (`gateway/hosted_room_attachments.py`):
  - ≤15 MB per file; an oversized base64 string → `invalid_params`
  - ≤8 files and ≤25 MB per message
  - ≤64 uncommitted uploads (100 MB) per room
  - ≤512 MB per room
  - name ≤255 chars, mime ≤127 chars
- Uploads commit when a `groups.send` references them.
- Downloads need the attachment's committed `event_id`, so composer drafts can't be downloaded.
- Not available on legacy TUI gateways.

---

#### Approvals, retry, discard inside a room

**UI**: one row per `driver_status.pending_actions[]` in `canonical-group-workspace.tsx`:
- `approval` → **Allow once** / **Deny**
- `retry` → **Retry**
- `discard` → confirm dialog ("discard unknown result" warning), then discard

The code is in `canonical-groups.ts` `actCanonicalGroup`.

**Calls**
- `pending_actions[]` item shapes:
  - `{kind: "approval", member_id, task_id, execution_generation, run_id, session_id, request_id, approval: {…prompt fields, request_id, choices: ["once","deny"]}}`
  - `{kind: "retry"|"discard", member_id, task_id, execution_generation}`
  - Canonical lists `discard` for an `indeterminate` task and `retry` for a `deferred` task. Peer members never get retry or discard actions.
- `groups.approve` (WS canonical) `{profile, room_id, member_id, task_id, execution_generation: int ≥1, choice: "once"|"deny", request_id}` → `{approved: true, result}`. A stale action → error "room approval is no longer pending". Legacy's `ApprovalChoice` type also lists `session` / `always`, but the server rejects both.
- `groups.retry` (WS canonical) `{profile, room_id, member_id, task_id, execution_generation}` → `{retried: true, task: RoomTaskReceipt}`. Valid only for a `deferred` task. Errors: `indeterminate` → `unknown_execution`; member session still active → `session_busy`; anything else → `stale_generation`.
  - Legacy TUI `groups.retry`: `member_id` and `execution_generation` are optional and both `indeterminate` and `deferred` tasks are accepted.
- `groups.discard` (WS canonical only) `{profile, room_id, member_id, task_id, execution_generation}` → `{discarded: true, task: RoomTaskReceipt}`. Valid only for an `indeterminate` task.
- `RoomTaskReceipt` = `{room_id, task_id, thread_id, turn_id, status, execution_generation, cancel_generation}`.

**Notes**
- Approvals raised inside member turns surface **only** through `groups.state` polling. Room members have no clarify (free-text question) channel; only approvals.
- Every control call is fenced on the exact `execution_generation`.

---

#### Stop, rename, disband

**UI**: the `canonical-group-header.tsx` buttons. Disband uses a `ConfirmDialog` and, on success, runs `forgetCanonicalGroup` and shows a toast.

**Calls**
- `groups.stop` `{profile, room_id, cancel_id?: string (UI: UUID; default "desktop-stop")}` → `{cancelled: int}`.
  - Appends `room.stop_requested`, which cancels every pending discussion up to that seq.
  - Cancels tasks in `queued | running | indeterminate | deferred | stopping`.
- `groups.rename` `{profile, room_id, event_id: string (UUID, idempotency), name: string ≤200}` → `{room: Room & {event: RoomEvent}}`. Appends `room.renamed` and bumps `revision`.
- `groups.disband` `{profile, room_id, cancel_id?: string (default "room-disbanded")}` → `{tombstone: {room_id, disbanded_at, idempotent, history_expired?, event?: RoomEvent}}`.
  - Stops the room's work (refused with "room work is still stopping; retry deletion after Stop completes" until stop is acknowledged), revokes peer routes, appends `room.disbanded`, and retires the `room_id` permanently: it can't be re-created, and creating with it → conflict.
  - Disbanded rooms are pruned later (≤512 tombstones). A pruned room reads `room_history_expired`.

---

#### Group pin / order / sections / needs-you (legacy rooms only)

**UI**: `group-pin.ts` (`toggleGroupChatPinned`), `group-order.ts` (`sortGroupRosterRows`, `reorderGroupRows`), `roster-pane-groups.tsx`, `user-sections.ts`.

**Calls**: none (client-only state).

**Notes**
- Legacy room records (`GroupChat` in `types.ts`: `pinned`, `rosterOrder`, `sectionId`, `log`, `members`, `roomId`, `watermarks`, …) persist in plugin storage `group-chats`, with tombstones in `group-chat-tombstones`. Pin, order and section never go to the gateway.
- A bounded cross-client projection of legacy rooms is written to the **default profile's** `ui_meta["hermes-bots-groups"]`. It is a CAS write via `profiles.configure` with `ui_meta_expected_revisions`, shaped `{version, rooms:{…≤16 msgs, ≤1200 chars each}, deleted:{roomKey:rev}, updatedAt}` (≤48 KB).
- Each bot's `ui_meta["hermes-bots"].groups: string[]` lists the legacy rooms it belongs to.
- **Needs-you** (`$groupNeedsYou`) badges a legacy room when a member's message matches `/@user\b/i`. It clears on the next send.
- Canonical rooms have no pin, order, needs-you or unread signal in this UI. A native client would have to derive them itself (for example from a `message.member` whose text contains `@user`) (suggestion, not upstream behaviour).

---

#### Legacy (client-driven) group chat engine

**UI**: `group-chat-view.tsx` `LegacyGroupChatWorkspace`, `group-rounds.ts` (room sequencing, ≤3 rounds, ≤6 members), `group-turns.ts` (one member turn), `group-round-prompt.ts`, `group-external-writes.ts`, `group-compress.ts`.

**Calls**: no `groups.*` methods. Per member and per thread, the client drives a hidden plumbing session in the member's profile:
- `session.resume`
- `session.create {profile: <member>, title: "Group: <roomId> · <thread>", hidden: true, room_plumbing: true, follow_profile_config: true}`
- `prompt.submit`
- `session.interrupt`, `session.compress`
- `approval.respond` / `clarify.lock` for member prompts (mirrored into `$groupClarify`)

**Events**: a turn ends on `message.complete` or `error` with the member's `session_id` (via `host.onEvent`). Fallback poll: 5000 ms, turn timeout 180 s, hard cap 180 min. Late replies are picked up every 5 s.

**Notes**
- Only relevant for gateways where `groups.capabilities` classifies as `legacy`. Recommendation for a new native app: skip it and support canonical rooms only.

---

#### Cross-machine room peers (RoomLink; legacy TUI only)

**Calls** (WS legacy, `tui_gateway/methods_groups.py`; no UI caller):
- `groups.capabilities` on legacy → `room_link {enabled, profile, catalog: RoomLinkCatalog, endpoint}`, or `{enabled:false, reason: "durable_run_storage_required"|"gateway_roomlink_secret_unavailable"}`.
  - Canonical always answers `room_link: {enabled:false, reason:"canonical_driver_required"}`, `features: ["room_identity","monotonic_log","replayable_disband"]` and `seatable_profiles`.
- `groups.peer.invite` `{profile?, room_id?, home_install_id?, authority_gateway_id?, authority_epoch?, member_id?, grant_id?, ttl_seconds? (60–86400, default 3600)}` → `{grant, target_profile, catalog, endpoint}`. Run on the target machine.
- `groups.peer.register` `{room_id, member_id, target_url, target_profile, grant, catalog, cancellation_scope_id?, trace_id?}` → `{registered, mode:"direct", transport_security, target_install_id, target_profile}`. Run on the room's home; it probes the target over HTTP.
- `groups.peer.revoke` `{grant}` → `{revoked}`.
- `groups.replicate` `{room_id, room_name, members, page (a verbatim groups.log result)}` → `{room_id, stored_seq, ingested, authority, caught_up}`.
- `groups.replica_state` `{room_id}`.
- `groups.promote` `{room_id, confirm: true (required), reason?}` → new epoch at this gateway.
- `groups.demote` `{room_id, observed_gateway_id, observed_epoch}`.

**Notes**
- Text only (`attachments: false`), direct links only. Peer member targets use `{kind:"peer", peer_id, installation_id, profile, capability_digest}`.
- Out of scope for a native client today (unverified that any shipping surface uses it).

---

#### Example: `groups.log` result

```json
{"events":[
  {"room_id":"6b0d2c1e-…","seq":1,"event_id":"user:9f2c…","kind":"message.user",
   "actor":{"kind":"user","id":"<subject>"},"authority_epoch":1,
   "payload":{"text":"@coder fix the failing test","thread_id":"e3a9f6d2-…"},
   "created_at":1792915200.12,"idempotent":false},
  {"room_id":"6b0d2c1e-…","seq":2,"event_id":"dmessage:4a7e…","kind":"message.member",
   "actor":{"kind":"member","id":"coder","profile":"coder"},"authority_epoch":1,
   "payload":{"discussion_event_id":"user:9f2c…","member_id":"coder","member_index":0,"round_index":0,
              "task_id":"dtask:4a7e…","thread_id":"e3a9f6d2-…","turn_id":"d1.r0.p0.s1.m0123456789abcdef01234567",
              "text":"Fixed in utils.py — @reviewer can you check?"},
   "created_at":1792915231.80,"idempotent":false},
  {"room_id":"6b0d2c1e-…","seq":3,"event_id":"dterminal:4a7e…","kind":"turn.settled",
   "actor":{"kind":"gateway","id":"<install id>"},"authority_epoch":1,
   "payload":{"discussion_event_id":"user:9f2c…","member_id":"coder","member_index":0,"round_index":0,
              "task_id":"dtask:4a7e…","thread_id":"e3a9f6d2-…","turn_id":"d1.r0.p0.s1.m0123456789abcdef01234567",
              "seen_through_seq":1,"passed":false,"message_event_id":"dmessage:4a7e…"},
   "created_at":1792915231.81,"idempotent":false}],
 "cursor":3,"latest_seq":3,"has_more":false,
 "authority":{"gateway_id":"<install id>","epoch":1}}
```
(The ids, the subject and the hex digests are illustrative; the field sets are exact per `hosted_room_discussion.py`.)


#### Pets — model, storage, routing (applies to every pet.* call)

**UI**: floating mascot `apps/desktop/src/components/pet/floating-pet.tsx` (+ `pet-sprite.tsx`, `pet-bubble.tsx`, `use-pet-roam.ts`, `roam-*.ts`, `use-pet-zoom-gesture.ts`); settings `apps/desktop/src/app/settings/pet-settings.tsx`; Cmd-K page `apps/desktop/src/app/command-palette/pet-palette-page.tsx`; generator overlay `apps/desktop/src/app/pet-generate/**`; pop-out window `apps/desktop/src/app/pet-overlay/**` (Electron only); stores `apps/desktop/src/store/pet.ts`, `pet-gallery.ts`, `pet-generate.ts`, `pet-overlay.ts`, `live-sync.ts`.

**Calls**: all pet.* are **WS legacy** (`tui_gateway/methods_session.py` ~L1490-1800, helpers `tui_gateway/server.py` L1765-1970). Not in `CanonicalRpcMethods`; on the canonical gateway they run via legacy fallback (`tui_gateway/ws_legacy_fallback.py`).

**Notes**:
- Pets are **per profile** (not per bot avatar): installed pets in `<HERMES_HOME>/pets/<slug>/` = `pet.json` `{id, displayName, description, spritesheetPath, (createdBy:"generator" for hatched)}` + `spritesheet.webp|png` (also probes `sprite.webp|png`). Thumb cache `<HERMES_HOME>/pets/.thumbs/<slug>.png`. Draft staging `<HERMES_HOME>/cache/pet-gen/<token>/draft-<i>.png` (+ `reference.<ext>`), swept after 1 h.
- Active pet config: `config.yaml` → `display.pet.{enabled: bool, slug: str, scale: float (default 0.33, clamp 0.1–3.0), render_mode: auto|kitty|…, unicode_cols: int}`. Unknown/missing `slug` with `enabled:true` → first installed pet alphabetically (`store.resolve_active_pet`).
- Profile routing: renderer wraps every gallery/settings/mascot call as `{...params, profile: petProfile()}` (`store/pet-gallery.ts` `petRpc`, `floating-pet.tsx`); `petProfile()` = active gateway profile key. Handlers built with `_pet_method(scoped=True)` are `@_profile_scoped` (honour `profile`). `pet.generate`, `pet.hatch`, `pet.generate.status` are `scoped=False`: they run in the connection's/ticket's home; the desktop generator store does NOT send `profile` (pet-generate.ts), so hatched pets land in the connection's profile; on the canonical WS an explicit `profile` is still bound by `_ticket_profile_request` (unverified for generate/hatch).
- Failure semantics: `_pet_method` turns any exception into `fail_open` payload (info/meta/cells → `{enabled:false}`; gallery → `{enabled:false, active:"", pets:[]}`; thumb → `{ok:false, slug}`; generate.status → `{available:false, providers:[]}`) else JSON-RPC error `5031 "<method> failed: …"`. Missing `slug` → `4004 "missing slug"`.
- On profile switch the renderer drops `$petInfo` and the gallery cache (`useOnProfileSwitch`).
- No restart needed for any pet change; config writes are picked up by the next call / watcher.

#### Pet spritesheet format (render natively)

**Notes** (`agent/pet/constants.py`, `pet-sprite.tsx`):
- Grid of cells `frameW=192` × `frameH=208` px; columns = frames; one row per state. Petdex sheets are 1536×1872 (8 cols × 9 rows), ~2 MB webp.
- Row taxonomy from row count: ≥9 rows → Codex `["idle","running-right","running-left","waving","jumping","failed","waiting","running","review"]`; else legacy 8-row `["idle","wave","run","failed","review","jump","extra1","extra2"]`. Server sends the resolved list as `stateRows`.
- App states (`PetState`): `idle|wave|run|failed|review|jump|waiting`. Row lookup by aliases: wave→`wave|waving`, jump→`jump|jumping`, run→`run|running`; not found → row 0 (idle).
- Frame count per row: `framesByRow[row]` (frames before first blank cell) → `framesByState[state]` (padding-trimmed per state) → `framesPerState` (6). Zero frames → fall back to idle.
- Timing: whole loop `loopMs` (1100 ms) per state; step = `loopMs / count`. Draw cell `sx = frame*frameW, sy = row*frameH`, scaled by `scale`; smooth (not nearest) upscale.
- Roaming walk: use `running-right` / `running-left` rows when present, else mirror `running` (faces left).
- State derivation (client, priority): error→`failed`, celebrate→`jump`, justCompleted→`wave`, awaitingInput (clarify/approval)→`waiting`, toolRunning→`run`, reasoning→`review`, busy→`run`, else `idle` (`store/pet.ts derivePetState`, mirrors `agent/pet/state.py`). Driven by the session event stream, not by pet events.

#### pet.info / pet.info.meta (active mascot)

**UI**: `components/pet/floating-pet.tsx`; `store/pet-gallery.ts` (refresh after adopt).

**Calls** (WS legacy):
- `pet.info` params `PetInfoParams {profile?: string, knownRevision?: string}` → `PetInfoResult`: `{enabled:false}` or `{enabled:true, slug, displayName, mime:"image/webp"|"image/png", spritesheetBase64, spritesheetRevision:"<mtime_ns>:<size>", spritesheetUnchanged?, frameW, frameH, framesPerState, framesByState, framesByRow, loopMs, scale, stateRows}`. If `knownRevision` == current revision, `spritesheetBase64` is omitted and `spritesheetUnchanged:true` (client keeps held bytes).
- `pet.info.meta` params `ProfileParams {profile?}` → `PetInfoMetaResult {enabled, slug?, displayName?, scale?, spritesheetRevision?}`. Cheap; client calls it first when a sheet is held and only calls `pet.info` if slug/revision moved.

Example `pet.info` result:
```json
{"enabled": true, "slug": "boba", "displayName": "Boba", "mime": "image/webp",
 "spritesheetBase64": "UklGR...", "spritesheetRevision": "1729950000123456789:2048123",
 "frameW": 192, "frameH": 208, "framesPerState": 6,
 "framesByState": {"idle": 6, "wave": 4, "run": 8, "failed": 5, "review": 6, "jump": 5, "waiting": 6},
 "framesByRow": {"idle": 6, "running-right": 8, "running-left": 8, "waving": 4, "jumping": 5, "failed": 5, "waiting": 6, "running": 8, "review": 6},
 "loopMs": 1100, "scale": 0.33,
 "stateRows": ["idle","running-right","running-left","waving","jumping","failed","waiting","running","review"]}
```

**Events**: `pet.changed` (payload `PetChangedPayload` = meta shape). Emitted by `tui_gateway/change_watcher.py` every ≤2 s check when signature `(slug, spritesheetRevision, scale)` or `("off",)` moves; first sighting at boot is silent; broadcast to **every** connected WS client (`_broadcast_global_event`, `session_id:""`). Signature is read from the watcher home (launch profile / home override) — a secondary profile's pet change may not fire it (unverified).
```json
{"jsonrpc":"2.0","method":"event","params":{"type":"pet.changed","session_id":"","payload":{"enabled":true,"slug":"boba","displayName":"Boba","scale":0.5,"spritesheetRevision":"1729950000123456789:2048123"}}}
```

**Notes** (refresh policy, `components/pet/pet-info-poll.ts`): `gateway.ready.payload.change_events === true` → event-driven + 15 s backstop; else poll 3 s while no pet / 15 s while active. Also pull on connect, window focus, and cold-start retries at 1 s/3 s/8 s (first `pet.info` may fail-open `enabled:false` while backend warms). `pet.changed` with `enabled:false` clears the mascot without a round trip.

#### pet.cells (terminal renderer)

**UI**: TUI only (Ink); not used by desktop.

**Calls** (WS legacy): params `PetCellsParams {profile?, state?: string (default "idle"), cols?: int, graphics?: bool}` → `PetCellsResult {enabled, slug, displayName, state, cols, frameMs, frames: number[row][col][8] per frame (half-block [tr,tg,tb,ta,br,bg,bb,ba]), scale}`; with `graphics:true` on a kitty terminal: `{graphics:"kitty", imageId, color, cols, rows, placeholder: string[], frames: string[], frameMs, scale}`.

**Notes**: native app should render from `pet.info` spritesheet instead.

#### pet.gallery / pet.thumb (browse petdex + installed)

**UI**: `app/settings/pet-settings.tsx`, `app/command-palette/pet-palette-page.tsx`, `components/pet/pet-thumb.tsx`; bots avatar Pet tab `plugins/hermes-bots/pet.tsx`.

**Calls** (WS legacy):
- `pet.gallery` params `PetGalleryParams {profile?, localOnly?: bool}` → `PetGalleryResult {enabled: bool (display.pet.enabled), active: string (display.pet.slug), pets: PetGalleryEntry[]}`; entry `{slug, displayName, installed: bool, spritesheetUrl: string ("" for local/hatched), curated?: bool (url contains "/curated/"), generated?: bool}`. Remote source: `https://petdex.dev/api/manifest` (307 → R2 JSON, cached server-side); `localOnly:true` returns installed only and warms the manifest in background. Installed-but-not-in-manifest pets appended. 4500+ entries.
- `pet.thumb` params `PetThumbParams {profile?, slug: string (req), url?: string}` → `PetThumbResult {ok, slug, dataUri?: "data:image/png;base64,…"}`: idle frame 0 cropped server-side to 96 px wide PNG, cached in `.thumbs/`. Source = installed sheet, else `url` only if host is `petdex.dev`/`*.petdex.dev` (anti-SSRF). Use this instead of loading CDN images directly (CDN 403s UA-less fetches; CSP).

**Notes**: renderer loads `localOnly:true` first, then full gallery; ranks installed → curated → rest; client LRU of 120 thumbs, 15 s timeout per thumb. Bots avatar Pet tab calls `pet.gallery {}` / `pet.thumb {slug,url}` via `host.request` WITHOUT `profile`; the chosen thumb data URI becomes the bot's avatar image (saved on dialog Save via the avatar path), no pet is attached to the bot.

#### pet.select / pet.disable / pet.scale / pet.rename / pet.remove / pet.export (manage)

**UI**: `app/settings/pet-settings.tsx` (enable toggle, gallery adopt, rename, export, uninstall with confirm for generated pets, scale slider %, Roam toggle), Cmd-K pet page, slash `/pet <sub> [value]` (`app/session/hooks/use-prompt-actions/slash.ts`), Alt+wheel on mascot / overlay (scale).

**Calls** (WS legacy, all accept `profile?`):
| Method | Params | Result | Side effects / errors |
|---|---|---|---|
| `pet.select` | `{slug}` req | `{ok:true, slug, displayName}` | installs from petdex if not installed (download spritesheet + pet.json; refuses non-petdex host), sets `display.pet.slug=slug, enabled=true`. `5031 "could not adopt '<slug>': …"` |
| `pet.disable` | `{}` | `{ok:true}` | `display.pet.enabled=false` |
| `pet.scale` | `{scale: number\|string}` | `{ok:true, scale}` (clamped 0.1–3.0) | writes `display.pet.scale`; non-number → `4004 "not a number: …"` |
| `pet.rename` | `{slug, name}` req | `{ok:true, slug: newSlug, displayName: name}` | updates `displayName`; moves dir/thumb to `slugify(name)` if free; active slug in config follows. `4004 "missing name"`, `5031 "pet.rename failed"` |
| `pet.remove` | `{slug}` req | `{ok: removed, slug}` | deletes `pets/<slug>/` + thumb; if active → `slug="", enabled=false` |
| `pet.export` | `{slug}` req | `{ok:true, filename:"<slug>.zip", zipBase64}` | zip of `<slug>/pet.json` + spritesheet (dotfiles skipped); re-importable (import path not exposed over RPC — unverified) |

**Events**: config/sheet changes surface as `pet.changed` (watcher, ≤2 s).

**Notes**: renderer applies scale optimistically and debounces `pet.scale` 200 ms, reconciling with server clamp. Enable toggle = `pet.select {slug}` (on) / `pet.disable` (off). Roam on/off, mascot position and overlay bounds are client-local (not on the gateway).

#### Pet generation (generate → hatch → adopt)

**UI**: route overlay `app/pet-generate/pet-generate-overlay.tsx` → `pet-generate-content.tsx` (prompt, optional reference image chip, provider picker, draft grid with remix, hatching view with egg `components/pet/pet-egg-hatch.tsx` + `pet-star-shower.tsx`, preview `hatch-preview.tsx` showing every row via `lib/frame-count.ts`, name + adopt/discard). Opened by Cmd-K → Pets → Generate or slash `/hatch [concept]` (prefills prompt). `generate-unavailable.tsx` links to Settings → Providers when no image backend.

**Calls** (WS legacy):
1. `pet.generate.status` params `{profile?}` → `PetGenerateStatusResult {available: bool, providers: PetGenProvider[]}`; `PetGenProvider {name, label, default: bool}`; names from `nous|openai|openai-codex|openrouter|krea` (labels "Nous Portal", "OpenAI", "OpenAI (Codex)", "OpenRouter", "Krea"), only configured reference-capable ones, preference order. Empty list → hide picker. Client persists pick in localStorage `hermes.desktop.petgen.provider` (`""` = backend default).
2. `pet.generate` params `PetGenerateParams {prompt?: string, referenceImage?: dataURL "data:image/(png|jpeg|jpg|webp|gif);base64,…" (≤16 MiB, env HERMES_PET_REFERENCE_MAX_BYTES), count?: int 1–4 (default 4), style?: "auto"|"pixel"|"plush"|"clay"|"sticker"|"flat-vector"|"painterly" (default "auto"), provider?: string}`; one of prompt/referenceImage required → `PetGenerateResult {ok:true, token: string(12 hex), drafts: PetDraft[]}`; `PetDraft {index: int, dataUri: PNG data URI downscaled to ≤160 px}`. Client timeout 420 s. Errors: `4004 "missing prompt"` / invalid reference message; `5031` provider error, `"generation cancelled"`, `"generation produced no usable drafts"`. Client reference prep: downscale to ≤1024 px PNG data URL, reject input >16 MiB. "Remix" = `pet.generate` again with `referenceImage` = a draft's `dataUri`.
3. `pet.hatch` params `PetHatchParams {token: string (req), name: string (req), index?: int (default 0), cancelToken?: string (client sends a fresh UUID), description?: string, prompt?: string (concept; default name), style?: string, provider?: string}` → `PetHatchResult {ok:true, slug (provisional unique slug from name), displayName, warnings: unknown[], pet: PetSpritePayload (same shape as pet.info minus enabled)}`. Pet is installed but NOT active. Client timeout 3 600 s. Errors: `4004 "missing token"|"missing name"|"draft expired — generate again"`, `5031` generation error.
4. Adopt: optional `pet.rename {slug, name}` (if user changed name; use returned slug) → `pet.select {slug}`. Discard: `pet.remove {slug}`. Closing the overlay with an unadopted preview also calls `pet.remove`.
5. `pet.cancel` params `PetCancelParams {token?: string}` → `{ok:true}` (idempotent; not pooled, lands mid-run). Cancel generate with the draft `token`, hatch with its `cancelToken`. Stop mid-hatch: server may still create the pet → client calls `pet.remove` on the returned slug.

**Events** (sent on the requesting connection, `session_id:""`):
- `pet.generate.progress` `PetGenerateProgressPayload {token, count, index?, dataUri?}`: first a token-only init frame (lets Stop target the run before any draft), then one frame per finished draft.
```json
{"jsonrpc":"2.0","method":"event","params":{"type":"pet.generate.progress","session_id":"","payload":{"token":"3f9a1c0b7e2d","count":4}}}
{"jsonrpc":"2.0","method":"event","params":{"type":"pet.generate.progress","session_id":"","payload":{"token":"3f9a1c0b7e2d","index":2,"dataUri":"data:image/png;base64,iVBORw0K...","count":4}}}
```
- `pet.hatch.progress` `PetHatchProgressPayload {event, detail?, state?, done?, total?}`: `{"event":"row","state":"waving","done":"3","total":"9"}` per sprite row (done/total are strings); `{"event":"row","detail":"idle-fallback"}`; `{"event":"compose","detail":""}`; `{"event":"save","detail":"<slug>"}`. No token in payload — filter by "hatch in progress" client state.

**Notes**: drafts expire with the staging dir (1 h sweep). Generation needs a reference-capable image provider configured in the profile (Settings → Providers). Hatch is long (one image call per row).

#### Pet pop-out overlay (desktop only)

**UI**: `app/pet-overlay/overlay-root.tsx`, `pet-overlay-app.tsx` (Electron window loaded with `?win=overlay`, transparent, no gateway connection); bridge `store/pet-overlay.ts`.

**Calls**: none to the gateway. IPC via `window.hermesDesktop.petOverlay` (`onState`, `control({type})`, `setBounds`, `setIgnoreMouse`, `setFocusable`, `onControl`); control types `ready|pop-in|bounds|scale|submit|toggle-app|open-app`. Main window pushes `$petInfo`/state; overlay `submit` sends composer text to the main window; `scale` persisted by main window via `pet.scale`.

**Notes**: in the browser webapp `petOverlay` is undefined → no pop-out, mascot stays in-page. Bubble (`pet-bubble.tsx`) text derives from `$petState`/`$petActivity` plus plugin lines (`ctx.pet.say`); core status lines only in the pop-out. "Unread" mail flag = client-only (turn finished while unfocused).

#### Learning / journey (learning.* + REST star map)

**UI**: desktop "star map" `apps/desktop/src/app/starmap/**` (`store/starmap.ts`, `node-context-menu.tsx`), archive dialog `app/learning/archive-skill-confirm-dialog.tsx`, skills tab `app/capabilities/skills/skills-tab.tsx`; slash `/journey` opens the memory graph (`openMemoryGraph`). The `learning.*` RPCs are used by the TUI only (`ui-tui/src/components/journey.tsx`).

**Calls**:
- REST (desktop): `GET /api/learning/graph?profile=` → `{nodes, edges:[{source,target}], stats, …}` (learned non-base skills + memory chunks, profile-scoped); `GET /api/learning/node?id=&profile=` → `{ok, kind:"skill"|"memory", label, content}`; `DELETE /api/learning/node` body `{id, profile?}` → `{ok, message}`; `PUT /api/learning/node` body `{id, content, profile?}` → `{ok, message}` (`hermes_cli/web_routers/status.py` ~L694-740).
- WS legacy (`tui_gateway/methods_tools.py` L1332-1355, no `profile` param, runs in connection home): `learning.frames {cols?=80, rows?=24, frames?=48}` → `LearningFramesResult {frames[{reveal,date,visible,grid,labels?}], legend[], categories[], buckets[{index,label,date,skills,memories,total,category?,color?,nodes[{id,glyph,label,fullLabel,meta,body,style}]}], summary: string[], axis{start,end}, count, cols, rows}` (pre-rendered ASCII timeline); `learning.detail {id}` → `LearningDetailResult {ok, message?, kind?, id?, label?, content?}`; `learning.edit {id, content}` / `learning.delete {id}` → `LearningMutationResult {ok, message?}`. Errors `5000 "learning.<x> failed: …"`.

**Notes**: node ids: skill = skill name; memory = `memory:<memory|profile>:<index>[:<fingerprint>]` (MEMORY.md / USER.md). Delete archives a skill (restorable via `hermes curator restore`), removes a memory entry. Per-profile (bot-related only in that each bot is a profile); native app should use the REST routes with `?profile=`.

### 2.5 Routines, projects, kanban, skills and memory

Conventions for this section:

- Transport tags as in section 1: **REST**, **WS canonical**, **WS legacy**, **sibling WS**.
- **Profile routing (REST).** Every route below takes `?profile=<name>`. Some also accept `profile` in the JSON body (body wins). Omitted means the launch profile, **except** on routes marked *destructive* (`hermes_cli/web_routers/_common.py::destructive_profile`). Those answer `400` when the profile is omitted and the process hosts several profiles, so always send `?profile=` explicitly, including `default`.
- **Profile routing (WS legacy).** Add `"profile": "<name>"` to params. All handlers here are `@_scoped_rpc`/`profile_scoped`.
- **REST errors**: FastAPI `{"detail": "<text>"}` (sometimes an object). **RPC errors**: JSON-RPC `{"code": <int>, "message": "<text>", "data"?: …}`. The codes for each method are listed with it.
- Change signals arrive as `{"jsonrpc":"2.0","method":"event","params":{"type":"<name>","payload":{}}}`. `payload` is always `{}` (`tui_gateway/change_watcher.py::_CHANGE_WATCHES`). Apply a signal only if it is for the active profile's socket (`apps/desktop/src/app/session/hooks/use-message-stream/gateway-event/lifecycle.ts`).

---

#### Routines (cron jobs)

**UI**
- `apps/desktop/src/app/cron/index.tsx` (`CronView`): job list with profile scope selector ("all" or one profile), editor dialog, per-job run history (`CronJobRuns`), delivery checkboxes (`DeliverCheckboxes`), pause/resume, run now, delete.
- `apps/desktop/src/app/cron/blueprints.tsx`: "Start from" template gallery (Automation Blueprints).
- `cron-actions.ts` (mutate, then refetch the list), `cron-job-model.ts` (editor validation, update payload, delivery-target CSV helpers, `lastErrorSummary`), `job-state.ts` (state dot, `NEXT_RUN_OVERDUE_GRACE_MS = 15 min`), `open-cron-run.ts` (open a run row as a session).
- Bot routines (separate surface): `apps/desktop/src/plugins/hermes-bots/cron.tsx` uses **WS legacy** `cron.manage`.
- `apps/shared/src/cron-trigger-controller.ts`: guard against double clicks on "Run now", local to one UI surface. `run(key, action, onStarted)` returns `{started:false, value:null}` while a run for the same key is in flight. It is **not** an execution lock: the backend's durable claim is the lock (409 below). Port it as a `Set<String>` of job ids that are running.

**Job object** (`GET /api/cron/jobs` item; stored by `cron/jobs.py::create_job` in `<HERMES_HOME>/cron/jobs.json`, then annotated by `hermes_cli/web_server_cron.py::_annotate_cron_job`). TS view: `CronJob` in `apps/desktop/src/types/hermes.ts`. The backend returns every key below.

```json
{
  "id": "3f9a1c0b7d2e",
  "name": "Morning brief",
  "prompt": "Summarize my inbox and calendar for today.",
  "skills": ["inbox-triage"],
  "skill": "inbox-triage",
  "model": null, "provider": null, "base_url": null,
  "script": null, "no_agent": false,
  "monitor_script": null, "monitor_url": null, "monitor_state": null,
  "context_from": null,
  "schedule": {"kind": "cron", "expr": "0 9 * * 1-5", "display": "0 9 * * 1-5"},
  "schedule_display": "0 9 * * 1-5",
  "repeat": {"times": null, "completed": 12},
  "enabled": true,
  "state": "scheduled",
  "paused_at": null, "paused_reason": null,
  "created_at": "2026-10-01T08:12:44.120391+03:00",
  "next_run_at": "2026-10-27T09:00:00+02:00",
  "last_run_at": "2026-10-26T09:00:03.551201+02:00",
  "last_status": "ok",
  "last_error": null,
  "last_delivery_error": null,
  "last_delivery_unverified": null,
  "last_failure": null,
  "failure_streak": 0,
  "deliver": "local,telegram",
  "origin": null,
  "enabled_toolsets": null,
  "workdir": null,
  "profile": "default", "profile_name": "default",
  "hermes_home": "C:\\Users\\alex\\.hermes",
  "is_default_profile": true,
  "scheduler_heartbeat_age_s": 4.2
}
```
- Optional keys, present only when set: `attach_to_session`, `reasoning_effort`, `failure_deliver`, `interpreter`, `last_fire_error`.
- `schedule.kind` is one of `cron` (`expr`), `interval` (`minutes`), `once` (`run_at` ISO).
- `state`: `scheduled | paused | running | completed | error`. The UI derives the effective state: a stored state wins, otherwise `enabled` decides (`job-state.ts::jobState`). Server rule: `cron/jobs.py::effective_job_state`.
- `last_status`: `ok | error | delivery_failed | null` (the last two values unverified beyond `mark_job_run`'s docstring).
- `repeat.times` = `null` means forever. A `once` schedule defaults to `times: 1`.
- A `next_run_at` in the past plus a large `scheduler_heartbeat_age_s` means the scheduler stopped ticking (#114309).

**Schedule formats** (`cron/jobs.py::parse_schedule`; the string is sent as `schedule`):

| Input | Stored as |
|---|---|
| `30m`, `2h`, `1d`, `every 30m`, `every 2h`, `hour` | `interval` (minutes) |
| `in 30m`, `in 2h` | `once` at now + duration |
| `every monday 9am`, `weekdays at 9am`, `every day at 14:00`, `monday, wednesday at 9:30am`, `weekend noon` | `cron` (converted to a 5-field expr; `display` keeps the original text) |
| `0 9 * * *` (5+ fields) | `cron` (validated with croniter) |
| `2026-02-03T14:00:00` (ISO; naive means Hermes tz) | `once` |

- Editor presets (`index.tsx::SCHEDULE_OPTIONS`): daily `0 9 * * *`, weekdays `0 9 * * 1-5`, weekly `0 9 * * 1`, monthly `0 9 1 * *`, hourly `0 * * * *`, every 15 min `*/15 * * * *`, custom.
- A `once` time already past its grace window is rejected (`400`).
- **Timezone**: `HERMES_TIMEZONE` env, else `timezone:` in the profile's `config.yaml`, else server local (`hermes_time.py`). Cron expressions are evaluated on that zone's wall clock. Intervals are added in UTC. All timestamps are ISO-8601 with an offset. There is no per-job timezone.

**Calls**

| Action | Transport | Call | Body / params | Result |
|---|---|---|---|---|
| List | REST | `GET /api/cron/jobs?profile=all\|<name>` | `profile` defaults to `all` (all profiles merged, de-duplicated by id, default profile preferred) | `CronJob[]` (bare array; includes disabled jobs) |
| Get | REST | `GET /api/cron/jobs/{id}` | `id` = job id **or exact name** (`resolve_job_ref`; an ambiguous name raises) | `CronJob` or `404 {"detail":"Job not found"}` |
| Create | REST | `POST /api/cron/jobs?profile=<name>` | `CronJobCreate` (`hermes_cli/web_models.py`): `schedule: string` (required), `prompt: string = ""`, `name: string = ""` (defaults to the first 50 chars of the prompt), `deliver: string = "local"`, `repeat?: int \| "forever" \| "once" \| "3"` (must be ≥ 1 if an int), `skills?: string[]`, `model?`, `provider?`, `base_url?`, `script?` (path inside `<HERMES_HOME>/scripts/`, stored relative), `context_from?: string \| string[]` (job ids; `"self"` = own previous output), `enabled_toolsets?: string[]`, `workdir?` (absolute), `no_agent: bool = false` (needs `script`), `paused: bool = false`, `paused_reason?` (only with `paused`) | the created `CronJob`. `400` validation (a prompt, skill or script is required; bad schedule; script outside the sandbox; unknown `context_from`; gateway-lifecycle command in the prompt). `404` unknown profile. `424` job saved but external scheduler registration failed: `detail` is the structured envelope (`CronSchedulerRegistrationError.to_dict()`) |
| Edit | REST | `PUT /api/cron/jobs/{id}` | `{"updates": {…}}`: any job key; the UI sends `name, prompt, schedule, deliver, model, provider` (`cron-job-model.ts::cronEditorUpdates`). `model`/`provider`: `null` or `""` clears the pin. `schedule` string is re-parsed, which recomputes `next_run_at` unless paused. `failure_deliver: ""` clears it. `skills` without `skill` clears `skill` | updated `CronJob`. `400`/`404` as above |
| Pause | REST | `POST /api/cron/jobs/{id}/pause` | none | `CronJob` (`enabled:false, state:"paused", next_run_at` kept) |
| Resume | REST | `POST /api/cron/jobs/{id}/resume` | none | `CronJob` |
| Enable/disable via edit | REST | `PUT …/{id}` with `{"updates":{"enabled":false}}` | type `CronJobUpdates.enabled` | `CronJob` (prefer pause/resume) |
| Run now | REST | `POST /api/cron/jobs/{id}/trigger` | none. **Synchronous**: the call returns after the run finishes (the renderer timeout is 24 h). A paused job is force-fired atomically | refreshed `CronJob` (new `last_run_at`/`last_status`). `409 {"detail":"Job is already running or was claimed by another scheduler"}`. `409` if a custom provider can't force-fire. A finished one-shot that removed itself returns `{…job, enabled:false, state:"completed"}` |
| Delete | REST | `DELETE /api/cron/jobs/{id}` | none | `{"ok": true}`; `404` |
| Run history | REST | `GET /api/cron/jobs/{id}/runs?limit=20` | `limit` 1–100 | `{"runs": SessionInfo[], "limit": n}` newest first |
| Delivery targets | REST | `GET /api/cron/delivery-targets?profile=` | none | `{"targets": CronDeliveryTarget[]}` |
| Blueprint gallery | REST | `GET /api/cron/blueprints?profile=<name>` | none | `{"blueprints": AutomationBlueprint[]}` |
| Instantiate blueprint | REST | `POST /api/cron/blueprints/instantiate?profile=<name>` (default `default`; `all` is not writable) | `{"blueprint": "<key>", "values": {"<field>": "<value>"}}` | created `CronJob`. `404` unknown blueprint, `422` field error (shown inline), `424` as create |
| Chronos fire (not UI) | REST | `POST /api/cron/fire` | Bearer NAS JWT, `{"job_id"}` | forwarded to the gateway; `503` + `Retry-After: 60` when unreachable |

- Profile per job: `{id}` routes treat `?profile=` as a **hint**. If that profile doesn't hold the job, the server scans every profile (`_job_owner_profile`). The "all" list can therefore act on rows without knowing their profile. Still pass `job.profile` when you have it.
- Create from the UI: the desktop sends only `prompt, schedule, name?, deliver, model?, provider?` (`index.tsx::handleEditorSave`). In the "all" scope it writes to `default`.

**Run history entries.** Rows have `/api/sessions` row shape (`SessionInfo`; session slice). Two kinds:

```json
{
  "id": "local-7c1e2a90b4",
  "source": "cron",
  "title": "Morning brief",
  "preview": "Here is your brief for Monday…",
  "started_at": 1761462000.55, "last_active": 1761462041.02, "ended_at": 1761462041.9,
  "message_count": 6, "tool_call_count": 3, "input_tokens": 18234, "output_tokens": 912,
  "model": "claude-sonnet-4-6", "cwd": null, "archived": false,
  "is_active": false, "scheduler_owned": false,
  "profile": "default", "cron_job_id": "3f9a1c0b7d2e", "cron_execution_id": "…"
}
```
```json
{
  "id": "cron_output:3f9a1c0b7d2e:2026-10-26_09-00-03",
  "title": "FAILED · exit status 2",
  "preview": "Traceback (most recent call last): …",
  "source": "cron_output",
  "started_at": 1761462003.0, "last_active": 1761462003.0, "ended_at": 1761462003.0,
  "input_tokens": 0, "output_tokens": 0, "message_count": 0, "tool_call_count": 0,
  "model": null, "cwd": null, "archived": false, "is_active": false
}
```
- `source:"cron"`: an agent run, i.e. a real session. Open it with the session slice's resume (`open-cron-run.ts::openCronRun`). Legacy ids look like `cron_<jobid>_<YYYYmmdd_HHMMSS>`; unified-runtime ids look like `local-*`.
- A run is **writable** (can be continued as a chat) only if `ended_at != null`, or if it is live (`scheduler_owned === true`). If `ended_at` is null and `scheduler_owned` is false, the run is a zombie: view only (`isResumableCronRun`). `is_active` is only a 300 s activity window. Use it only when `scheduler_owned` is absent (older backends).
- `source:"cron_output"`: a script-only (`no_agent`) fire, rebuilt from `<HERMES_HOME>/cron/output/<job_id>/<YYYY-mm-dd_HH-MM-SS>.md` plus the execution ledger. It has no session: show `title`/`preview` and don't open it. `…:latest` is a metadata-only row (taken from `last_run_at`/`last_error`) when no output doc survives. Title format: `"<STATUS> · <error or preview>"`.
- Polling (`index.tsx`): 8 s on older backends. When `gateway.ready.payload.change_events` is true, a 60 s backstop plus a refetch on `cron.changed`/`sessions.changed`. 1 s while a "Run now" is pending (up to 90 s, with 2 s clock-skew slack).

**Delivery targets** (`cron/scheduler_delivery.py::cron_delivery_targets`):
```json
{"targets": [
  {"id": "local", "name": "Local (save only)", "home_target_set": true, "home_env_var": null},
  {"id": "telegram", "name": "Telegram", "home_target_set": false, "home_env_var": "TELEGRAM_HOME_CHANNEL"},
  {"id": "bot-chat:work", "name": "Bot Chat (work)", "home_target_set": true, "home_env_var": null}
]}
```
- `deliver` is a comma-separated list (`"local,telegram"`; `cron-job-model.ts::parseCronDeliveryTargets`; empty means `local`). Tokens: `local` (save only), `origin` (the chat where the job was made, else the first home channel), a bare platform (its home channel; `home_target_set:false` means not configured, so the UI flags it), `platform:chat_id[:thread]` (explicit), `bot-chat[:profile]`. The `home_env_var` value in the example is illustrative (unverified).
- `failure_deliver` (optional) routes failures elsewhere; when empty, failures go to `deliver`.

**Blueprint object** (`cron/blueprint_catalog.py::blueprint_catalog_entry`; TS `AutomationBlueprint`): `{key, title, description, category, tags[], fields[], command, appUrl, source?: "builtin"|"plugin", plugin?}`. Each `field` is `{name, type: "enum"|"text"|"time"|"weekdays", label, default, options[], optional, strict?, help}`. The server rewrites the options of a `deliver` field to `["origin","local",<configured platforms>]`. A plugin blueprint key is `<plugin>:<key>`. Fetch the catalog for the same profile you instantiate into.

**WS legacy `cron.manage`** (used by the bot-routines pane; `tui_gateway/methods_tools.py`, error `5023`; unknown action `4016`):
- Params `CronManageParams`: `action: "list"|"add"|"remove"|"pause"|"resume"` (default `list`), `name` (job id for remove/pause/resume, job name for add), `include_disabled` (list; default false hides paused jobs), `schedule`, `prompt`, `repeat` (digits only), `continuity` (bool), `deliver` (e.g. `bot-chat:<profile>`), `profile`.
- Result `CronManageResult`: `list` returns `{success, count, jobs: CronJobRow[], scoped?: "<profile>", gateway_running?, warning?}`. `CronJobRow` is a compact row (`job_id, name, prompt_preview, schedule, repeat, deliver, next_run_at, last_run_at, last_status, last_error, enabled, state, …`), **not** the REST `CronJob`. `add` returns `{success, job_id, name, schedule, next_run_at, job, message, …}`. `remove` returns `{success, removed_job:{id,name,schedule}}`.
- Example: `{"jsonrpc":"2.0","id":7,"method":"cron.manage","params":{"action":"list","include_disabled":true,"profile":"work"}}`.

**WS canonical `cron.submit/status/cancel/recover`** (`gateway/session_cron.py`): this is the scheduler's producer protocol, **not** used by the UI. They run a persisted job through the owning profile's durable admission FIFO. The caller needs `session:create`, `session:submit` and `session:control`, and the caller's profile must equal the authority's profile (else `profile_mismatch`). The parameter set is closed (`invalid_params`).
- `cron.submit` `{job_id: string, request_id: string, extra_prompt: string|null}` returns `{session_id, admission_id}`. `request_id` is the idempotency key: the same id from the ticker and the CLI de-duplicates.
- `cron.status` `{session_id, admission_id}` returns `{status: "queued"|"started"|"terminal", result: [success:boolean, output_doc:string, final_response:string, error:string|null] | null, job_flags: {}, progress_at}`. `result` is non-null only when terminal.
- `cron.cancel` `{session_id, admission_id}` returns `{ok: true}`: a queued admission is dropped; a started one gets its cancellation latched.
- `cron.recover` `{job_id, request_id, extra_prompt}` returns status plus `{job, session_id, admission_id}`, or `{status:"missing", result:null}`. Use it after a restart.
- A native app should use REST `…/trigger` for "Run now". (unverified) A client holding those capabilities could call `cron.submit` and poll `cron.status` to avoid blocking an HTTP request.

**Events**
- `cron.changed`: `<HERMES_HOME>/cron/jobs.json` mtime moved. Checked every 1 s; it fires on edits **and** on each scheduler tick that touches the file. Refetch the list and any open run history. Example: `{"jsonrpc":"2.0","method":"event","params":{"type":"cron.changed","payload":{}}}`
- `sessions.changed` (floor 2 s): refetch run history too (a new run session appeared).

**Notes**
- Mutations reconcile the scheduler provider immediately (`_mutate_cron_for_profile`). No restart is needed. The gateway must be running for jobs to fire: its ticker owns execution, and `scheduler_heartbeat_age_s` shows its liveness.
- `trigger` holds an HTTP request open for the whole run. Give it its own long timeout, and treat a client timeout as "unknown" (refetch), not as a failure.
- Script paths are sandboxed to `<HERMES_HOME>/scripts/`.

---

#### Webhooks (inbound event subscriptions)

**UI**: `apps/desktop/src/app/webhooks/index.tsx`, a list of subscriptions with detail rows. The URL and secret have copy buttons; the secret is shown once, after create. Also: an enable-platform banner, create dialog, per-route enabled switch, delete confirmation. Wrapper: `apps/desktop/src/api/messaging.ts`. Server: `hermes_cli/web_routers/ops.py`, store `hermes_cli/webhook.py` (`<HERMES_HOME>/webhook_subscriptions.json`, mode 0600).

**Calls**

| Action | Transport | Call | Body | Result |
|---|---|---|---|---|
| List | REST | `GET /api/webhooks?profile=` | none | `{"enabled": bool, "base_url": "http://localhost:8644", "subscriptions": WebhookRoute[]}` |
| Enable platform | REST | `POST /api/webhooks/enable?profile=` | none | `{"ok":true,"platform":"webhook","enabled":true,"needs_restart":bool,"restart_started"?,"restart_action"?,"restart_pid"?,"restart_error"?}` |
| Create | REST | `POST /api/webhooks?profile=` | `WebhookCreate`: `name` (required; lowercased, spaces become `-`, must match `^[a-z0-9][a-z0-9_-]*$`), `description?`, `events?: string[]` (e.g. `["push","pull_request"]`; empty = all), `prompt?`, `script?`, `skills?: string[]`, `deliver = "log"` (UI options: `log, telegram, discord, slack, email, github_comment`), `deliver_only = false` (send the payload straight to the target without the agent; needs a target other than `log`), `deliver_chat_id?`, `secret?` (auto-generated when omitted) | `WebhookRoute` + `"secret": "<plaintext>"` (**returned only once**). `400` platform disabled, bad name, or `deliver_only` with `log`. `409` concurrent edit |
| Enable/disable route | REST | `PUT /api/webhooks/{name}/enabled?profile=` | `{"enabled": bool}` | `{"ok":true,"name":"<key>","enabled":bool}` |
| Delete | REST | `DELETE /api/webhooks/{name}?profile=` (*destructive*) | none | `{"ok":true}`; `404` |

`WebhookRoute`:
```json
{"name":"gh-push","description":"Dashboard-created subscription: gh-push","events":["push"],
 "deliver":"telegram","deliver_only":false,"prompt":"Summarize this push.","script":"","skills":[],
 "created_at":"2026-10-26T07:41:02Z","url":"http://localhost:8644/webhooks/gh-push",
 "secret_set":true,"enabled":true}
```
- **Senders** POST to `url` on the webhook platform listener (`platforms.webhook.extra.host/port`, default 8644; `gateway/platforms/webhook.py`), not on the webapp port. Accepted signatures: `X-Hub-Signature-256: sha256=<hex HMAC>` (GitHub), `X-Gitlab-Token: <secret>`, `X-Webhook-Signature-V2: <hex HMAC-SHA256 of "<X-Webhook-Timestamp>.<body>">` plus `X-Webhook-Timestamp` (preferred generic form), or legacy `X-Webhook-Signature` (warned as replayable). Event type comes from `X-GitHub-Event`/`X-GitLab-Event`. A disabled route returns `403`.
- The REST `url` has no profile prefix. The CLI's `_route_url` adds `/p/<profile>` for non-default profiles. Show the CLI form for non-default profiles (unverified which one the gateway accepts).

**Events**: none. Refetch after each mutation.

**Notes**: enabling the platform writes config and needs a **gateway restart**. The server tries one itself (`needs_restart` = restart not started; the UI then offers `runGatewayRestart`). Route create, toggle and delete hot-reload: no restart.

---

#### Kanban (multi-agent task board)

**UI**: `apps/desktop/src/plugins/kanban/**`, a bundled desktop plugin at route `/kanban` (`plugin.tsx`). Parts: `board.tsx` (columns, drag), `drawer.tsx` (task detail: comments, runs, events, log, attachments, links), `board-switcher.tsx`, `orchestration.tsx` (dispatcher settings), `model-override.tsx`, `completion-notify.ts` (toasts), `transfer.ts` (export/import). Backend: dashboard plugin `plugins/kanban/dashboard/plugin_api.py` mounted under `/api/plugins/kanban/`. The SQLite store and dispatcher are in `hermes_cli/kanban_db.py`. Gateway loops: `gateway/kanban_watchers*.py` (dispatcher, notifier, GC).

**Calls.** All are **REST** under `/api/plugins/kanban`. Most take `?board=<slug>`; omitted means the server's current board. Profile: `?profile=`.

| Action | Call | Body / query | Result |
|---|---|---|---|
| Board snapshot | `GET /board?board=&include_archived=&tenant=` | none | `KanbanBoard {columns:[{name, tasks: KanbanTask[]}], tenants[], assignees[], latest_event_id, now}` |
| Task detail | `GET /tasks/{id}` | none | `{task: KanbanTaskFull, comments[], events[], attachments?, links:{parents[],children[]}, link_tasks?, runs[]}` |
| Worker log | `GET /tasks/{id}/log?tail=16384` | none | `{exists, size_bytes, content, truncated}` |
| Create task | `POST /tasks` | `title` (required), `body?`, `assignee?` (profile), `tenant?`, `priority=0`, `workspace_kind?` (`scratch`/`dir`/`worktree`), `workspace_path?`, `parents: string[]`, `triage=false`, `idempotency_key?`, `max_runtime_seconds?`, `skills?: string[]`, `goal_mode=false`, `goal_max_turns?`, `model_override?`, `provider_override?`, `reasoning_effort?`, `project_id?` | `{task: KanbanTask\|null, warning?}` |
| Patch task | `PATCH /tasks/{id}` | `status?`, `assignee?`, `priority?`, `title?`, `body?`, `result?`, `block_reason?`, `summary?`, `metadata?`, `model_override?`, `provider_override?`, `clear_model_override`, `reasoning_effort?`, `clear_reasoning_effort` | `{task: …}`. Setting `status:"running"` directly is refused |
| Bulk | `POST /tasks/bulk` | `{ids[], status?, assignee?, priority?, archive?, result?, summary?, metadata?, reclaim_first?, …overrides}` | `{results:[{id, ok, error?}]}` |
| Delete | `DELETE /tasks/{id}` | none | `{deleted:true, task_id}` |
| Comment | `POST /tasks/{id}/comments` | `{body, author?:"desktop"}` | `{ok:true}` |
| Reassign | `POST /tasks/{id}/reassign` | `{profile, reclaim_first:true}` | `{ok, task_id, assignee}` |
| Reclaim (stop worker) | `POST /tasks/{id}/reclaim` | `{reason?}` | `{ok, task_id}` |
| Specify / decompose | `POST /tasks/{id}/specify`, `POST /tasks/{id}/decompose` | none | `{ok, task_id, reason, new_title}` / `{ok, task_id, reason, fanout, child_ids[], new_title}` |
| Attachments | `GET/POST /tasks/{id}/attachments` (multipart upload), `GET/DELETE /attachments/{aid}` | none | attachment rows `{id, task_id, filename, content_type, …}` |
| Links | `POST /links {parent_id, child_id}` / `DELETE /links?parent_id=&child_id=` | none | `{ok, gated}` / `{ok}` |
| Estimate | `POST /tasks/{id}/estimate`, `POST /estimate {title, body?}` | none (makes a model call) | `{ok, reason?, est_tokens?, complexity?: "S"\|"M"\|"L", rationale?, model?}` |
| Runs | `GET /workers/active`, `GET /runs/{run_id}`, `GET /runs/{run_id}/inspect`, `POST /runs/{run_id}/terminate {reason?}` | none | run rows / `{ok, run_id, task_id}`; `409` already ended |
| Nudge dispatcher | `POST /dispatch?board=` | `{}` | `{spawned?: […]}`. The UI calls it 400 ms (debounced) after every write |
| Boards | `GET /boards` → `{boards: BoardMeta[], current}`; `POST /boards {slug, name, project_id?}`; `PATCH /boards/{slug}` (name, description, `default_workdir` (`""` clears), project); `DELETE /boards/{slug}` (archives to `boards/_archived/`; `?delete=true` hard-deletes; `default` is refused); `POST /boards/{slug}/switch`; `POST /boards/{slug}/export {output: host path}`; `POST /boards/import {archive: host path}` | see left | see left |
| Roster | `GET /profiles` → `{profiles:[{name,is_default,description,description_auto}]}`; `PATCH /profiles/{name} {description}`; `POST /profiles/{name}/describe-auto {overwrite:true}` | see left | see left |
| Orchestration | `GET /orchestration`, `PUT /orchestration {orchestrator_profile?, default_assignee?, auto_decompose?, auto_promote_children?}` | see left | `{orchestrator_profile, default_assignee, auto_decompose, auto_promote_children, resolved_orchestrator_profile, resolved_default_assignee, active_profile}` |
| Other reads | `GET /projects`, `GET /config`, `GET /stats`, `GET /assignees`, `GET /diagnostics`, `GET /model-options`, `GET /workflow`, `GET /home-channels`; `POST/DELETE /tasks/{id}/home-subscribe/{platform}` | none | (shapes unverified; see `plugin_api.py`) |

- Statuses and columns (`hermes_cli/kanban_workflow.py::DEFAULT_WORKFLOW`): `triage, todo, scheduled*, ready, running*, blocked, review*, done` (+ `archived`). Columns marked `*` are not drag targets. Manual moves: triage→todo/ready; todo→triage/scheduled/ready; scheduled→triage/todo/ready; ready→triage/todo/scheduled/blocked/review/done; running→any except running; blocked→triage/todo/scheduled/ready/done; review→triage/todo/ready/done; done→triage/todo/ready; archived→triage/todo/ready.

**Events**: **sibling WS** `/api/plugins/kanban/events?board=<slug>&since=<cursor>`. Auth goes through the dashboard WS gate (`web_server_chat._ws_auth_ok`), which accepts `?ticket=` (one-use, section 1.5), `?token=` or `?internal=`; an unauthorized upgrade is closed with 1008. The board is pinned at the handshake: on a board switch, close and reopen the socket. Start from the snapshot's `latest_event_id`; never `since=0`, which replays the whole history. Frames look like `{"events":[{"id":812,"task_id":"t_9f2","kind":"completed","payload":{…},"created_at":1761462100}],"cursor":812}`. On a frame, invalidate the board and each touched task. Toast for these kinds: `completed` (success); `blocked`, `block_loop_detected`, `timed_out` (warning); `crashed`, `gave_up` (error). Kinds `status`/`archived`/`unblocked` are silent. Fallback polling: board 8 s, drawer 4 s. A stale or archived slug closes the socket with 1008.

**WS canonical `kanban.run`** `{board, task_id, run_id:int, claim_lock, db?}` returns `{session_id, receipt}`. Only the gateway's own dispatcher may call it (`native_owner` plus `session:create`). **Not for the UI.**

**Notes**: workers are spawned by the gateway's dispatcher loop, so kanban needs a running gateway; the 60 s tick is backed by `POST /dispatch` nudges. Completions also go to subscribed platform home channels (`kanban_watchers_notifier.py`). Board export/import paths are on the host's filesystem, not on the client.

---

#### Projects and workspaces

**UI**: Projects sidebar and store `apps/desktop/src/store/projects.ts`. The project tree and lanes (`projects.tree`, `projects.project_sessions`) are covered in the sessions/sidebar section; this subsection only cross-references them. All calls are **WS legacy** (`tui_gateway/methods_projects.py`). They are per profile (`<HERMES_HOME>/projects.db`) and take `profile?`. Errors: `5061` generic, `5062` "no such project", `5063` bad argument (e.g. duplicate primary path).

| Method | Params | Result |
|---|---|---|
| `projects.list` | `{profile?}` | `ProjectsPayload {projects: ProjectInfo[] (archived included), active_id}` |
| `projects.get` | `{id}` | `{project}` |
| `projects.create` | `{name (req), folders?: string[], slug?, primary_path?, description?, icon?, color?, board_slug?, use?: bool}` (`use` = also make it active) | `{project \| null}` |
| `projects.update` | `{id, name?, description?, icon?, color?, board_slug?}` | `{project}` |
| `projects.archive` | `{id, restore?: bool}` | `ProjectsPayload` |
| `projects.delete` | `{id}` | `ProjectsPayload` |
| `projects.set_active` | `{id?}` (omit or `null` = clear) | `{active_id}` |
| `projects.add_folder` | `{id, path, label?, is_primary?}` | `{project}` |
| `projects.remove_folder` | `{id, path}` | `{project}` |
| `projects.set_primary` | `{id, path}` | `{project}` |
| `projects.for_cwd` | `{cwd?}` | `{project \| null, cwd, branch?}` |
| `projects.discover_repos` | `{scan?: bool}` | `{repos: [{root, label?, sessions?, last_active?}], discovery_policy?: {enabled, roots[], exclude_paths[]}}` |
| `projects.record_repos` | `{repos?: (string \| {root, label?})[], discovery_policy?: {enabled?, roots?, exclude_paths?, …}}` | `{repos, discovery_policy?, accepted}` |
| `projects.tree` / `projects.project_sessions` | see the sessions section | |
| `project.facts` (`methods_session.py`) | `{cwd?}` | `{facts: {root, manifests[], packageManagers[], verifyCommands[], contextFiles[]} \| null}` |

`ProjectInfo`: `{id, slug, name, description?, icon?, color?, board_slug?, primary_path?, archived?, created_at (epoch s), folders?: [{path, label?, is_primary?, added_at?}]}`. `board_slug` links a kanban board.

**REST** `GET /api/chat/workspaces?profile=&scan=false` returns `{projects: ProjectInfo[] (archived excluded), repos: DiscoveredRepo[], default_cwd, home, scan_enabled}`. Use it for a new-chat workspace picker; `scan=true` rescans the discovery roots first.

**Events**: `projects.changed` (when `projects.db` moves; checked every 2 s) means refetch `projects.list` and the tree.

---

#### Skills (installed skills)

**UI**: Capabilities → Skills: `apps/desktop/src/app/capabilities/skills/skills-tab.tsx` (list, enable switch, inline SKILL.md editor), `skill-detail.tsx` (full SKILL.md view), `official-skill-detail.tsx`, `skill-provenance.ts`, `frontmatter.ts`. Archive confirm: `apps/desktop/src/app/learning/archive-skill-confirm-dialog.tsx`. Wrapper: `apps/desktop/src/api/skills.ts`. Server: `hermes_cli/web_routers/skills.py`.

| Action | Transport | Call | Body | Result |
|---|---|---|---|---|
| List | REST | `GET /api/skills?profile=` | none | `SkillInfo[]` |
| View SKILL.md | REST | `GET /api/skills/content?name=<skill>&profile=` | none | `{"name","content","path"}`; `404` |
| Enable/disable | REST | `PUT /api/skills/toggle?profile=` | `{"name": string, "enabled": bool, "profile"?}` | `{"ok":true,"name","enabled"}` (writes `skills.disabled` in config) |
| Create | REST | `POST /api/skills?profile=` | `{"name": string, "content": "<full SKILL.md with frontmatter>", "category"?: string, "profile"?}` | `{"success":true,"message":"Skill 'x' created.","path":"…"}`; `400 {detail: error}`. (Dashboard path; the desktop has no create button.) |
| Edit (full rewrite) | REST | `PUT /api/skills/content?profile=` | `{"name","content","profile"?}` | `{"success":true,"message":"Skill 'x' updated (full rewrite)."}`; `404`/`400` |
| Edit (desktop path) | REST | `PUT /api/learning/node?profile=` | `{"id": "<skill name>", "content": "<SKILL.md>"}` | `{"ok":true,"message":"updated 'x'"}`; `400 {detail}` |
| Archive ("delete") | REST | `DELETE /api/learning/node?profile=` | `{"id": "<skill name>"}` | `{"ok":true,"message":"archived 'x' — restore with: hermes curator restore x"}`. Pinned skill: `400 "'x' is pinned — unpin it first …"` |
| Reload (slash registry) | WS (served by the session owner, `gateway/session_ancillary.py`; legacy body in `methods_tools.py`) | `skills.reload` | `{session_id?, profile?}` | `{output: "Reloading skills…", result: {added[], removed[], unchanged[], total, commands}}`; error `5025` |
| List (TUI shape) | WS legacy | `skills.manage` | `{action:"list", profile?}` | `{skills: {"<category>": ["name", …]}}` |

Skill list entry (`find_all_skills` + `get_skills` enrichment):
```json
{"name": "github-pr-review", "description": "Review a GitHub PR end to end.", "category": "github",
 "enabled": true, "usage": 14, "provenance": "hub"}
```
- `provenance` is `agent` (learned/local, editable), `bundled`, `hub` (installed), or `external` (from `skills.external_dirs`). Disabled skills are still listed, with `enabled:false`.
- `skill_scaffold.ts` (`apps/shared/src/skill-scaffold.ts`): a `/skill` invocation is expanded into a model-facing message that embeds the whole SKILL.md. `skillInvocationText(text)` maps that text back to `"/name instruction"` for display, or returns `null` for plain prose. Current gateways already send `display` / history `skill_invocation` text. Port it only as a fallback for older gateways. The markers must match `agent/skill_commands.py` byte for byte.

**Events**: `skills.changed` (a skill root moved; checked every 2 s) means refetch `/api/skills` and the slash `commands.catalog`.

**Notes**: writes clear the skills system-prompt cache, so changes **apply to new sessions** (`skills.changesApplyNewSessions`). No gateway restart is needed.

##### Skill hub (catalog browse, install, uninstall, update)

**UI**: `apps/desktop/src/app/capabilities/catalog/**` (`skill-catalog.tsx`, `catalog-browser.tsx`, `catalog-detail*.tsx`, `update-skills-button.tsx`), store `apps/desktop/src/store/hub-actions.ts`, deep link `store/skill-deeplink-install.ts`. Bot pane: `plugins/hermes-bots/skills-hub.tsx` (WS legacy `skills.manage`).

| Action | Transport | Call | Result |
|---|---|---|---|
| Official catalog | REST | `GET /api/skills/hub/official?profile=` | `{"skills":[{name, description, source, identifier, trust_level, repo, tags[], category, installed}]}` |
| Sources | REST | `GET /api/skills/hub/sources?profile=` | `{"sources":[{id, label, searchable, rate_limited?, available?}], "index_available", "featured": HubSkill[], "installed": {identifier: …}}`. Source ids: `official, hermes-index, skills-sh, well-known, url, github, clawhub, lobehub, browse-sh` |
| Search | REST | `GET /api/skills/hub/search?q=&source=all&limit=20&profile=` (limit ≤ 50; 30 s server timeout; client 45 s) | `{"results": HubSkill[], "source_counts": {}, "timed_out": [], "installed": {}}`. Empty `q` returns empty lists |
| Preview | REST | `GET /api/skills/hub/preview?identifier=&profile=` | `{name, description, source, identifier, trust_level, repo, tags, skill_md, files[]}`; `404` |
| Security scan | REST | `GET /api/skills/hub/scan?identifier=&profile=` | `{name, identifier, source, trust_level, verdict, summary, policy: "allow"\|"ask"\|"block", policy_reason, findings:[{severity, category, file, line, description}], severity_counts:{critical,high,medium,low}, tier1: null\|{passed, incomplete_checks, findings[]}}` |
| Install | REST | `POST /api/skills/hub/install?profile=` `{"identifier", "profile"?}` | `ActionResponse {ok, pid, name: "skills-install-<slug>-<sha8>"}` |
| Uninstall | REST | `POST /api/skills/hub/uninstall?profile=` `{"name", "profile"?}` | `ActionResponse` |
| Update all | REST | `POST /api/skills/hub/update?profile=` `{}` | `ActionResponse {name:"skills-update"}` |
| Action progress | REST | `GET /api/actions/{name}/status?lines=200&profile=` | `{name, running, exit_code, pid, lines[]}`. Poll until `running:false`; `exit_code 0` = success. Otherwise show the last lines (scan block, ambiguous name, fetch error). `404 Unknown action` |
| Hub via RPC | WS legacy | `skills.manage` `{action:"search"\|"browse"\|"inspect"\|"install", query, page?, page_size?=20, profile?}` | search: `{results:[{name, description}]}`; browse: `{items:[{name, description, source, trust, identifier}], page, total_pages, total}`; inspect: `{info:{name, description, source, identifier, tags, skill_md_preview}}`; install: `{installed:true, name}` or error `5031` with `data:{installed:false, name, log}`; unknown action `4017` |

`HubSkill` = `{name, description, source, identifier, trust_level: "builtin"|"trusted"|"community", repo, tags[]}`. An install runs `hermes -p <profile> skills install <id> --yes` in the background, with the scan gate applied. After it finishes, refetch skills, sources and official, and invalidate slash completions. The chat's `catalog_install` tool cards (`components/assistant-ui/catalog-install-tool.tsx`) go through `connection.respond` (connections section).

##### Curator (background skill maintenance)

UI: `apps/desktop/src/app/command-center/maintenance.tsx`. Wrapper: `apps/desktop/src/api/system.ts`.
- `GET /api/curator?profile=` returns `{enabled, paused, interval_hours, last_run_at, min_idle_hours, stale_after_days, archive_after_days}`.
- `PUT /api/curator/paused?profile=` `{"paused": bool}` returns `{ok, paused}`.
- `POST /api/curator/run?profile=` (*destructive*: it archives and rewrites skills) returns `ActionResponse {name:"curator-run"}`. Poll `/api/actions/curator-run/status`.

---

#### Memory (built-in MEMORY.md / USER.md, Starmap)

**Model**: `<HERMES_HOME>/memories/MEMORY.md` is agent memory (source `memory`); `USER.md` is the user profile (source `profile`). Each file is a list of entries joined by `"\n§\n"` (`tools/memory_tool_store.py::ENTRY_DELIMITER`). Budgets: `memory.memory_char_limit` (default 2200) and `memory.user_char_limit` (1375). On/off: `memory.memory_enabled`, `memory.user_profile_enabled`. These four are config keys: edit them through the config section (`/api/config`; Settings → Memory & Context, `apps/desktop/src/app/settings/constants.ts`).

**UI**: Starmap (learning/journey view) `apps/desktop/src/app/starmap/**` (`index.tsx`, `star-map.tsx`, `timeline.tsx`, `node-context-menu.tsx` for edit and delete, `share-controls.tsx`), store `apps/desktop/src/store/starmap.ts`. Memory sizes and reset are in `command-center/maintenance.tsx`.

There is **no raw-file REST**. Read memory through the learning graph and edit it per entry:

| Action | Transport | Call | Body | Result |
|---|---|---|---|---|
| Read graph (skills + memory) | REST | `GET /api/learning/graph?profile=` | none | `StarmapGraph {nodes[], edges[], clusters[], memory: StarmapMemoryCard[], stats{}}` |
| Read one entry / skill | REST | `GET /api/learning/node?id=<node id>&profile=` | none | `{ok, kind: "memory"\|"skill", id, label, content}`; `404 {detail}` |
| Edit entry | REST | `PUT /api/learning/node?profile=` | `{"id": "memory:<memory\|profile>:<index>:<fp>", "content": "<new text>"}` | `{ok:true, message}`; `400` when empty (use delete), the id is stale, or over budget |
| Delete entry | REST | `DELETE /api/learning/node?profile=` | `{"id": "memory:…"}` | `{ok:true, message}` |
| Sizes + provider | REST | `GET /api/memory?profile=` | none | `{"active": "<provider or ''>", "providers": [...], "builtin_files": {"memory": <bytes>, "user": <bytes>}}` |
| Reset files | REST | `POST /api/memory/reset?profile=` (*destructive*) | `{"target": "all"\|"memory"\|"user"}` | `{"ok":true,"deleted":["MEMORY.md", …]}` |
| Same via RPC | WS legacy | `learning.detail` `{id}`, `learning.edit` `{id, content}`, `learning.delete` `{id}` | none | `LearningDetailResult` / `LearningMutationResult {ok, message?}` (error `5000`) |
| TUI journey frames | WS legacy | `learning.frames` `{cols?=80, rows?=24, frames?=48}` | none | pre-rendered ASCII frames (TUI only) |

- Node ids (`agent/learning_graph.py`, `agent/learning_mutations.py`): a skill's id is its name. A memory entry's id is `memory:<source>:<index>:<fingerprint>`, where `index` is the position in the combined card list (MEMORY.md first), and `fingerprint` = first 12 hex chars of `sha256(entry.strip())`. The fingerprint names the entry even if the list shifted. If the text is gone, the result is `"memory node id is stale — refresh the graph"`.
- Memory read example (`GET /api/learning/node?id=memory:profile:3:9b1f0c2ad4e7`):
```json
{"ok": true, "kind": "memory", "id": "memory:profile:3:9b1f0c2ad4e7",
 "label": "Prefers terse replies; no preamble.", "content": "Prefers terse replies; no preamble."}
```
- Memory write example: `PUT /api/learning/node?profile=default` with body `{"id":"memory:profile:3:9b1f0c2ad4e7","content":"Prefers terse, high-signal replies."}` returns `{"ok":true,"message":"…"}`. After a write, refetch the graph: ids of later entries may change.
- `StarmapMemoryCard`: `{source: "memory"|"profile", timestamp?, title (≤80 chars), body (≤1200 chars), fingerprint?}`. `StarmapNode`: `{id, label, kind, memorySource?, timestamp?, category, useCount, state, createdBy, pinned}`.
- Adding a new entry: no REST or RPC exists. Only the agent's `memory` tool appends (unverified that nothing else does).
- Events: none dedicated. The Starmap store refetches on its own (`watchStarmapGraph`; trigger unverified).

---

#### Memory providers (external memory plugins)

**UI**: Settings → Memory & Context. The `memory.provider` select is in `apps/desktop/src/app/settings/config-settings.tsx`; the empty value means "built-in only". Panels: `settings/memory/provider-config-panel.tsx`, `settings/memory/connect.tsx` (OAuth). Server: `hermes_cli/web_routers/memory_providers.py`, `hermes_cli/web_server_memory.py`, `hermes_cli/memory_oauth.py`.

| Action | Transport | Call | Body | Result |
|---|---|---|---|---|
| List + status | REST | `GET /api/memory?profile=` | none | `providers: [{name, description, available, configured, status: "ready"\|"needs_config"\|"unavailable"\|"missing", setup: {pip_dependencies[], python_dependencies_declared, external_dependencies[{name,install,check}], required_env[], dependencies_installed?}}]` |
| Select active | REST | `PUT /api/memory/provider?profile=` | `{"provider": "<name>" \| "" \| "built-in"}` (`""`/`built-in` disables the external provider) | `{"ok":true,"active":"<name>"}`. `400` if the provider is unknown or its status isn't `ready` |
| Config schema (desktop) | REST | `GET /api/memory/providers/{name}/config?surface=declared&profile=` | none | `{name, label, docs_url, fields: [{key, label, kind, description, placeholder, group, inline, info?, options[{value,label,description}], is_set, value}]}` (secret `value` is always `""`) |
| Config schema (raw plugin) | REST | `GET /api/memory/providers/{name}/config?profile=` | none | `{name, label, fields[...{required, default, url, when, minimum, maximum, step}], setup}` |
| Save config | REST | `PUT /api/memory/providers/{name}/config?surface=declared&profile=` | `{"values": {"<key>": "<value>"}}` | `{ok:true}`. Without `surface=declared` it also **activates** the provider: `{ok:true, active:name}`. Secrets go to the profile `.env` |
| Install deps + save | REST | `POST /api/memory/providers/{name}/setup?profile=` | `{"values": {…}}` | `{ok, provider, results[], status}` |
| OAuth start / poll | REST | `POST /api/memory/providers/{name}/oauth/start`, `GET …/oauth/status` | none | `{auth: "apikey"\|"oauth"\|null, connected, detail, state: "idle"\|"pending"\|"connected"\|"error"}`; `404` if the provider has no OAuth |

- The provider `name` must match a strict charset (no dots or slashes); otherwise `404`.
- The desktop changes `memory.provider` through the generic config save. `PUT /api/memory/provider` does the same and adds the readiness check.
- **Restart**: the provider is read when an agent starts, so the change applies to **new sessions**. Running sessions keep the old provider (unverified whether a gateway restart is ever required). Disabling a memory plugin through `plugins.manage toggle` can return `cleared_memory_provider:true` / `restart_required:true`: honour those flags.

---

#### Delegation (subagents) and goals

**Delegation routing (REST)**. UI: `apps/desktop/src/app/settings/delegation/**`, `settings/delegation-route-row.tsx`. Wrapper: `apps/desktop/src/api/delegation.ts`. Server: `hermes_cli/web_routers/delegation.py`.
- `GET /api/delegation/routing?profile=` returns `{configured: "default"|"model_map"|"profile_routes"|<plugin>, blocked_reason: string|null, backends: [{name, label, description, available, reason, builtin}], model_map: {options: [{name, description, provider?, model, base_url?, reasoning_effort?, service_tier?, api_key? (redacted preview), fallback_providers?}], default}, profile_routes: {profiles: [{name, description?}], default}, profiles: string[]}`. While `blocked_reason` is set, every subagent is refused: it fails closed.
- `PUT /api/delegation/backend` `{backend, profile?}` returns `{ok, backend}`.
- `PUT /api/delegation/backends/model_map` `{options[], default?, profile?}` returns `{ok, model_map}`. Send a redacted `api_key` back unchanged to keep the stored key.
- `PUT /api/delegation/backends/profile_routes` `{profiles[], default?, profile?}` returns `{ok, profile_routes}`.
- Other knobs (`delegation.model`, `provider`, `base_url`, `max_iterations`, `max_concurrent_children`, `child_timeout_seconds`, `reasoning_effort`) are plain config keys (config section).

**Delegation live state (RPC)**
- `delegation.status` (**WS legacy**) `{profile?}` returns `{active: ActiveSubagent[] {subagent_id, parent_id?, depth?, goal?, delegation_id?, model?, started_at?, status?, tool_count?, owner_agent_session_id?}, paused, max_spawn_depth, max_concurrent_children}`.
- `delegation.pause` (**served by the gateway owner**, `gateway/session_tui_ops.py`; the legacy handler can't be reached) `{paused: bool (default true), profile?, session_id?}` returns `{paused}`. Needs `session:control`. It is a **process-wide** gate on new spawns; running children continue.
- Per chat (chat section). These are served by the session owner (`gateway/session_ancillary.py`, which checks the caller owns the session), not by the legacy fallback: `subagent.list {session_id}` returns `{subagents[], delegations[]}`; `subagent.tail {session_id, subagent_id}` returns `{subagent_id, available?, text?, truncated?}`; `subagent.steer {session_id, subagent_id, text}` returns `{status, subagent_id, text}`; `subagent.interrupt {session_id, subagent_id}` returns `{found, subagent_id}`. Events `subagent.spawn_requested`, `subagent.progress`, `subagent.complete` carry `SubagentEventPayload` (`goal, task_index, task_count, subagent_id, child_session_id, status, summary, tool_count, input_tokens, output_tokens, duration_seconds, …`).

**Goals / loops / heartbeat** (per session; UI `apps/desktop/src/app/chat/composer/status-stack/**`, store `store/session-control.ts`). On the gateway, the session owner serves these methods: `session.control` in `gateway/session_controls.py`, `session.control.read` in `gateway/session_ancillary.py`. Both require `session:control`. They are listed in `RpcMethods`, not in `CanonicalRpcMethods`. The action set and snapshot shape come from `tui_gateway/methods_session_control.py`:
- `session.control.read {session_id, profile?}` returns `{control: {goal: GoalSnapshot|null, loop|null, heartbeat|null, revision, updated_at}}`.
- `session.control {session_id, action, args?: {text?, index?}, profile?}` returns `{control, dispatch: {type, output, notice, message, display}}`. `action` must be one of: `goal.pause, goal.resume, goal.clear, goal.unwait, loop.pause, loop.resume, loop.stop, subgoal.add` (`args.text`), `subgoal.remove` (`args.index`), `subgoal.clear, heartbeat.pause, heartbeat.resume, heartbeat.clear`.
- `GoalSnapshot`: `{title, status, turns_used, max_turns, contract, subgoals[], gates[], created_at?, updated_at?, paused_reason?, last_verdict?, last_reason?, wait_barrier?}`.
- Event `session.control.update` `{control: SessionControlSnapshot}` (with `session_id`).
- Setting a goal is the `/goal <text>` slash command. **WS canonical** `session.automation` is the classic CLI's manager path; nothing in `apps/desktop/src` calls it.

---

#### Plugins and plugin catalog (non-tool aspects)

**UI**: Capabilities → Plugins `apps/desktop/src/app/capabilities/plugins/plugins-tab.tsx` (+ `plugin-settings-form.tsx`, `plugin-update.ts`, `plugin-folder-access.tsx`), store `apps/desktop/src/store/agent-plugins.ts`, deep link `store/plugin-catalog-install.ts` + `lib/plugin-catalog.ts`.

- **WS legacy** `plugins.manage` (`tui_gateway/methods_tools.py`; errors `5026` generic, `4019` missing arg, `4020` not found, `4021` bad settings or update refused). `action`:
  - `list` returns `{plugins: AgentPluginRow[], user_count, bundled_count}`. Row: `{name, key, version, description, source, status, portable, install_dir, has_desktop_half, servers[], catalog_name?, catalog_tier?, installed_sha?, catalog_sha?, update_available?, pinned_sha?, settings_schema?, tools?}`.
  - `toggle {key|name, enable}` returns `{ok, unchanged, restart_required, gateway_reloaded, activation, name, plugin}`.
  - `install {identifier|repo | catalog_name, force?, enable?=true, ref?}` returns the install result (`plugin_name, enabled, warnings, missing_env, python_dependencies, known_issues, after_install_path, activation?`).
  - `update {name, accept_capabilities?, force?}` returns `{ok, unchanged, name, warnings, sha?, gateway_reloaded?, activation?, restart_required?}`, or a refusal (`ok:false` with `consent_required` + `delta`/`delta_lines`, or `scan_blocked` + `scan_verdict`/`scan_findings`). Retry with `accept_capabilities` or `force` respectively.
  - `remove {name}` uninstalls a user install.
  - `settings {key, values: {}}` returns `{ok, name, written[], plugin}`. Secrets are refused here (use `/api/env`).
  - `onboarding` returns `{onboarding[]}`; `presence {names[]}` returns `{presence: [{name, title, state, sentence, disclosure}]}`.
- **WS legacy** `plugins.catalog.lookup {name, profile?}` returns `{status: "found"|"invalid_name"|"unknown"|"unavailable", entry?: {name, repo, sha, subdir, tier?}}`. It backs `hermes://plugin/install?catalog=<name>`. Install afterwards with `plugins.manage install {catalog_name}`.
- **WS legacy** `plugins.list` is a legacy flat view (`{plugins, toolsets?, portable_mcp_servers?}`); the hub uses `plugins.manage list`.
- **Plugin REST namespace**: `/api/plugins/<pluginId>/<path>` (e.g. kanban above). Plugin sockets: `/api/plugins/<pluginId>/<path>` WS (`apps/desktop/src/api/plugins.ts::pluginSocket`). Token-mode connections add `?token=`; otherwise use the one-use ticket.
- **Restart**: honour `restart_required` / `gateway_reloaded` / `activation.deferred` / `activation.missing_in` from toggle, install and update. A plugin with a desktop half (`has_desktop_half`) needs the renderer reloaded (`plugins.reload`, desktop only).

### 2.6 Capabilities, workspace and operations

Transports: **WS canonical** = `/api/ws` method in `CanonicalRpcMethods`; **WS legacy** = `/api/ws` method in `RpcMethods` (`tui_gateway`); **REST** = same-origin HTTP (`?profile=` scopes it); **sibling WS** = separate WebSocket dialed with a one-use ticket. Contract type names refer to `app/docs/contract/gateway-contract.generated.ts`.

#### Tools and toolsets (Capabilities)

**UI**
- Page shell: `apps/desktop/src/app/capabilities/index.tsx` (`CapabilitiesView`). Tabs via `?tab=` route param: `skills` (default) · `plugins` · `connectors` · `toolsets`. Profile/connection scope picker: `app/capabilities/scope-selector.tsx` (every capability call can target ANY profile without switching the app profile).
- Toolsets tab: `app/capabilities/toolsets/toolsets-tab.tsx`, `toolset-detail.tsx`, `toolsets-data.ts` (query key `['toolsets-list']`), `tool-calls.ts` (per-tool call-count badges from usage analytics).
- Toolset provider/key config panel lives in Settings: `app/settings/toolset-config-panel.tsx`, `terminal-backend-panel.tsx`, `computer-use-panel.tsx`; `store/terminal-backend-warning.ts`.
- Renderer wrappers: `apps/desktop/src/api/toolsets.ts`.

**Profile scoping (all capability REST)**: `?profile=<name>` query (browser transport) and/or `profile` in JSON body; body `profile` wins over query on writes. Omitted = the app's active profile.

**Calls** (all REST unless noted)

| Purpose | Call | Params | Result |
|---|---|---|---|
| List toolsets | `GET /api/tools/toolsets` | `profile?` query | `ToolsetInfo[]` (see example) |
| Enable/disable | `PUT /api/tools/toolsets/{name}` | body `{enabled: bool (req), profile?: string}` | `{ok, name, platform, enabled, post_setup_started: string\|null}` |
| Provider matrix | `GET /api/tools/toolsets/{name}/config` | `profile?` | `{name, has_category, providers: ToolProvider[], active_provider, active_search_backend?, active_extract_backend?, search_via_nous?, extract_via_nous?}` (last 4 only for `web`) |
| Pick provider | `PUT /api/tools/toolsets/{name}/provider` | body `{provider: string (req), capability?: 'search'\|'extract' (web only), profile?}` | `{ok, name, provider, capability?, needs_nous_auth?: true, feature?: string}` |
| Save API keys | `PUT /api/tools/toolsets/{name}/env` | body `{env: {KEY: value} (req), profile?}`; keys must be in the category's providers' `env_vars`; blank value = leave unchanged | `{ok, name, saved: string[], skipped: string[], is_set: {KEY: bool}}`. (Desktop panel actually writes keys via `PUT /api/env`, config slice.) |
| Model catalog (image_gen/video_gen) | `GET /api/tools/toolsets/{name}/models?provider=` | `provider?`, `profile?` | `{name, has_models, provider, plugin, models: [{id, display, speed, strengths, price}], current, default}`; no catalog → `{has_models:false,...}` |
| Pick model | `PUT /api/tools/toolsets/{name}/model` | body `{model: string (req), provider?, profile?}` | `{ok, name, model, plugin}`; 400 unknown model |
| Run install hook | `POST /api/tools/toolsets/{name}/post-setup` | body `{key: string (req), profile?}` (key = provider row `post_setup`) | `ActionResponse {ok, name:"tools-post-setup", pid, key}`; tail `GET /api/actions/tools-post-setup/status?lines=N` → `{name, running, exit_code, pid, lines[]}` |
| Terminal backends | `GET /api/tools/terminal/backends` | `profile?` | `{active, backends: [{name, label, description, active, status: 'ready'\|'needs_setup'\|'unavailable', detail}]}` |
| Select backend | `PUT /api/tools/terminal/backend` | body `{backend: string (req), profile?}` | `{ok, backend}`; 400 unknown |
| Computer Use status | `GET /api/tools/computer-use/status` | `profile?` | `ComputerUseStatus {platform, platform_supported, installed, version, ready, can_grant, checks:[{label,status,message}], accessibility, screen_recording, screen_recording_capturable, source, error}` |
| Grant macOS perms | `POST /api/tools/computer-use/permissions/grant` | — | `ActionResponse` (action `computer-use-grant`); 400 on non-macOS |
| Tool call counts (badges) | `GET` usage analytics (`getUsageAnalytics(365, profile)`, analytics slice) | — | `tools: [{tool, count}]` |

WS legacy RPC (TUI / slash; Desktop Capabilities does NOT use them, it uses REST):

| Method | Params | Result |
|---|---|---|
| `tools.list` | `{session_id?: string}` | `ToolsetsListResult {toolsets: ToolsetRow[]}` with `tools` names |
| `toolsets.list` | `{session_id?}` | same, no `tools` |
| `tools.show` | `{session_id?}` | `ToolsShowResult {sections: [{name, tools:[{name, description}]}], total}` (includes tool_search-deferred tools) |
| `tools.configure` | `ToolsConfigureParams {action: 'enable'\|'disable' (req), names: string[] (req; toolset keys or `server:tool` MCP targets), session_id?, profile?}` | `ToolsConfigureResult {changed[], enabled_toolsets[], info: SessionLiveInfo\|null, missing_servers[], reset: bool, unknown[]}`; errors 4017 bad action, 4018 no names, busy error if session mid-turn, 5035 failure |

`ToolProvider` row fields: `name, badge, tag, env_vars: [{key, prompt, url, default, is_set}], post_setup: string|null, requires_nous_auth, managed_nous_feature, is_active, status: 'ready'|'needs_setup'|'needs_auth'|'needs_keys'`, plus `web_backend` + `capabilities: ('search'|'extract')[]` (web), `tts_provider` (tts).

**Examples**
```json
// GET /api/tools/toolsets  -> array entry (values illustrative)
{"name":"web","label":"Web","description":"Web search and page extraction","platform":"cli",
 "platform_label":"CLI","enabled":true,"available":true,"configured":true,
 "tools":["web_extract","web_search"]}
// toolsets.list RPC row
{"name":"web","description":"Web search and page extraction","tool_count":2,"enabled":true}
// GET /api/tools/toolsets/web/config -> providers[0]
{"name":"Firecrawl","badge":"","tag":"","env_vars":[{"key":"FIRECRAWL_API_KEY","prompt":"Firecrawl API key",
 "url":"https://firecrawl.dev","default":null,"is_set":false}],"post_setup":null,"requires_nous_auth":false,
 "managed_nous_feature":null,"is_active":false,"status":"needs_keys","web_backend":"firecrawl",
 "capabilities":["search","extract"]}
```

**Events**: none specific. Toggle → client invalidates its own toolset query and slash completions.

**Notes**
- Browser-capable (plain REST). `post-setup` / `computer-use grant` spawn processes on the gateway host.
- Toggle writes `platform_toolsets.<platform>` (most toolsets `cli`; config-only `stt` writes `stt.enabled`). Enabling a toolset whose provider still needs an install auto-spawns `hermes tools post-setup <key>` (`post_setup_started`).
- No gateway restart; REST writes config only, so a live chat keeps its built toolset until its agent is rebuilt (new session) (unverified for exactly when). `tools.configure` with `session_id` rebuilds that session's agent immediately (`reset: true`).
- Toggle calls are read-modify-write on config: send them sequentially (UI does bulk toggles one by one).
- `needs_nous_auth: true` on provider select = config written but inert until the user signs in to Nous Portal.

#### Skills (as shown in Capabilities)

**UI**: `app/capabilities/skills/skills-tab.tsx`, `skills-data.ts`, `official-skill-detail.tsx`; hub browser `app/capabilities/catalog/skill-catalog.tsx`, `catalog-browser.tsx`, `update-skills-button.tsx`. Wrappers `api/skills.ts`.

**Calls** (REST; all accept `profile`)
- `GET /api/skills` → `SkillInfo[] {name, category, description, enabled, usage?, provenance?: 'agent'|'bundled'|'external'|'hub'}`
- `PUT /api/skills/toggle` body `{name, enabled}` → `{ok, name, enabled}`
- `GET /api/skills/content?name=` → `{content, name, path}`; `PUT /api/skills/content`, `POST /api/skills` (create; skills slice)
- Hub: `GET /api/skills/hub/official`, `GET /api/skills/hub/sources`, `GET /api/skills/hub/search?q=&source=all&limit=20`, `GET /api/skills/hub/preview?identifier=`, `GET /api/skills/hub/scan?identifier=`, `POST /api/skills/hub/install` `{identifier}`, `POST /api/skills/hub/uninstall` `{name}`, `POST /api/skills/hub/update` → `ActionResponse` (background action).
- Public catalog snapshot (no backend): `GET https://nousresearch.github.io/hermes-agent/docs/api/skills.json` and `.../plugins.json` (`app/capabilities/catalog/catalog-data.ts`, `credentials: 'omit'`).
- WS legacy: `skills.manage {action: list|search|install|browse|inspect, query?, page?, page_size?, profile?}`, `skills.reload {session_id?, profile?}` → `{output, result:{added[], removed[], unchanged[], total, commands}}`.

**Events**: `skills.changed` (`{}`; change watcher, 2 s poll of skill dirs) → refetch.

**Notes**: browser-capable. Skill toggles need no restart (take effect on next prompt build) (unverified).

#### MCP servers

**UI**
- MCP server management is inside the Connectors tab: `app/capabilities/connectors/connectors-tab.tsx` (uses `useMcpServers`), `local-dialog.tsx`, `add-dialog.tsx`, `add-server-form.tsx`, `add-server-draft.ts`, `ways-section.tsx`, `tools-panel.tsx`, `tools-list.tsx`, `tool-row.tsx`.
- MCP logic: `app/capabilities/mcp/use-mcp-servers.ts` (controller), `use-mcp-probes.ts`, `use-mcp-draft.ts`, `mcp-status.ts`, `mcp-doc.ts`, `mcp-editor.tsx` (raw `mcp.json` editor), `install-catalog-entry.ts`.
- Libs: `lib/mcp-dashboard-oauth.ts` (OAuth), `lib/mcp-reload.ts` (`reload.mcp`), `lib/mcp-probe-cache.ts`, `lib/mcp-tool-filter.ts`. Wrappers `api/mcp.ts`.

**How the Desktop edits MCP (exact flow)**
1. Read: `GET /api/config` (config slice) → `mcp_servers` map (`{name: {url|command, args, env, headers, auth, enabled, tools}}`).
2. Mutate the map client-side (add, remove, `enabled:false` (re-enable deletes the key), tool filter `tools: {include?: string[], exclude?: string[]}`).
3. Save: `PUT /api/mcp/servers` body `{servers: {name: rawConfig}, profile?}` → `{ok:true}`. REPLACES the whole map (deep-merge `/api/config` can't delete keys). 400 if a plugin-owned name is included.
4. Apply live: WS legacy `reload.mcp {confirm: true, session_id?: activeSessionId}`.
5. Probe each enabled server: `POST /api/mcp/servers/{name}/test` (client timeout 60 s; cached 5 min per config fingerprint).
6. HTTP bearer key: WS `mcp.servers.set_api_key {name, value}` after save, then refetch config.

**REST calls** (`hermes_cli/web_routers/mcp.py`, helpers `web_server_mcp.py`)

| Call | Params | Result / errors |
|---|---|---|
| `GET /api/mcp/servers` | `profile?` | `{servers: McpServerSummary[]}` (REST shape: `env` = `{KEY: redactedValue}`) |
| `POST /api/mcp/servers` | `MCPServerCreate {name (req), url? \| command? (exactly one), args: string[] (stdio), env: {} (stdio), auth?: 'none'\|'header'\|'oauth', bearer_token?: string (auth=header, saved to .env only), profile?}` | summary; 409 exists / plugin-owned; 400 validation/"suspicious command" |
| `PUT /api/mcp/servers` | `{servers, profile?}` | `{ok}` |
| `DELETE /api/mcp/servers/{name}` | `profile?` | `{ok}`; 404; 409 plugin-owned |
| `PUT /api/mcp/servers/{name}/enabled` | `{enabled: bool, profile?}` | `{ok, name, enabled}` |
| `POST /api/mcp/servers/{name}/test` | `profile?` | `{ok, tools:[{name, description, schema_chars?}], prompts, resources}` or `{ok:false, error, tools:[]}` ("OAuth authentication required — no token found." when `auth: oauth` and no token) |
| `POST /api/mcp/servers/{name}/auth` | `profile?`; needs auth token/cookie | `{flow_id, server_name, status: 'starting'\|'authorization_required'\|'approved'\|'error', authorization_url, error}`; 400 stdio/header-auth server, 409 already running, 429 >8 flows |
| `GET /api/mcp/oauth/flows/{flow_id}` | — | snapshot + `tools` |
| `DELETE /api/mcp/oauth/flows/{flow_id}` | — | `{ok, status}` (idempotent; `status:"expired"` if gone) |
| `GET /api/mcp/oauth/callback/{server_name}` | `code, state, error, iss` | HTML page (provider redirect target; public URL aware) |
| `GET /api/mcp/catalog` | `profile?` | `{entries: McpCatalogEntry[], diagnostics: [{name, kind, message}]}` |
| `POST /api/mcp/catalog/install` | `MCPCatalogInstall {name (req), env: {} (only declared keys), enable: bool=true, profile?}` | `{ok, name, background: false}` or `{ok, name, background: true, action: "mcp-install-<slug>-<hash>"}` → poll `GET /api/actions/{action}/status?lines=1` until `running:false`, check `exit_code` |

`McpCatalogEntry` (REST): `name, description, connector_slug, source, transport, auth_type, required_env:[{name, prompt, required}], command, args, url, install_url, install_ref, bootstrap[], default_enabled, post_install, suggest:{keywords, hosts, applications, examples, requires_app}|null, needs_install, installed, enabled`.

**WS legacy RPC** (`tui_gateway/methods_tools.py`; all accept optional `profile`; contract `tui_gateway/contracts/tools_mcp_plugins.py`)

| Method | Params | Result |
|---|---|---|
| `mcp.catalog` | `ProfileParams` | `McpCatalogResult {servers: [{name, description, connector_slug, installed, enabled, requires: string[], transport}]}` |
| `mcp.servers.list` | `{profile?}` | `{servers: McpServerSummary[]}` (RPC shape: `env` = key names only, plus `oauth_tokens_present`) |
| `mcp.servers.status` | `{profile?}` | `McpServersStatusResult {servers: McpServerRuntimeRow[], checked_at: ms}`; cached state only, never connects |
| `mcp.servers.add` | `McpServersAddParams {name (req), preset?: catalogId, config?: {url\|command, args, env, headers, auth, tools}, bearer_token?, profile?}` | `{ok, name, server}`; 4090 exists/plugin-owned, 4063 unknown preset / no url-command, 4001 rejected |
| `mcp.servers.set_api_key` | `{name (req), value (req, non-empty), env_var?: string (default MCP_<NAME>_API_KEY), profile?}` | `{ok, name, env_var, server}`; http → `Authorization: Bearer ${ENV}` header, stdio → `env` ref |
| `mcp.servers.test` | `{name, profile?}` | `McpServersTestResult {ok, tools:[{name, description}], error?, prompts?, resources?, oauth_needed, oauth_tokens_present?}` |
| `mcp.servers.remove` | `{name, profile?}` | `{ok, removed}`; 4064 not found |
| `mcp.servers.oauth.start` | `{name, client_redirect_uri?: 'http://127.0.0.1:<port>/callback' (loopback only), profile?}` | `{ok, session_id (= flow id), auth_url, flow: "pkce"}`; blocks ≤30 s for URL |
| `mcp.servers.oauth.poll` | `{name, session_id}` | `{ok, status: 'pending'\|'approved'\|'error', session_id?, error_message?, auth_url?, tools?}` |
| `mcp.servers.oauth.cancel` | `{name, session_id}` | `{ok, status?, error_message?}` |
| `mcp.servers.oauth.callback` | `{name, session_id, code?, state?, error?, iss?}` (omit null `iss`) | `{ok, session_id?, error_message?}` |
| `reload.mcp` | `ReloadMcpParams {session_id?, confirm: bool=false, always: bool=false, rev?: string}` | `ReloadMcpResult {status: 'confirm_required'\|'reloaded', message?, loaded_rev?, coalesced?, turn_isolation?, host_ack?, session?: 'applied'\|'unchanged'\|'busy', unavailable?: string[], session_servers?: string[]}` |
| `reload.env` | `{session_id?, profile?}` | `{updated: int}` |

Common MCP RPC errors: 4064 not found, 4090 duplicate or plugin-owned (`server 'x' is provided by plugin 'y'`), 4063 invalid preset/credential, 4001 not applicable (stdio has no OAuth; header-auth server), 5024 generic failure. Long handlers (`mcp.servers.test`, `oauth.start`) run on the RPC pool; use ≥60 s client timeout.

**Desktop OAuth flow** (`completeMcpDesktopOAuth`, 6 min deadline): Electron `mcpOauth.listen()` → loopback URL on the CLIENT machine → `mcp.servers.oauth.start {name, client_redirect_uri}` → verify `auth_url`'s `redirect_uri` == listener URL → `openExternal(auth_url)` → `mcpOauth.wait(id)` relays `{code, state, error, iss}` via `mcp.servers.oauth.callback` → poll `mcp.servers.oauth.poll` every ~1 s until `approved` (returns probed tools) → finally `oauth.cancel` if not approved. Without the bridge (browser; only allowed when connection is `local`) the backend binds its own `http://127.0.0.1:<port>/callback` on the gateway host.

**Examples**
```json
// McpServerSummary (RPC mcp.servers.list)
{"name":"linear","transport":"http","url":"https://mcp.linear.app/mcp","command":null,"args":[],
 "env":[],"auth":"oauth","oauth_tokens_present":true,"enabled":true,
 "tools":{"exclude":["delete_issue"]},"source":"config","plugin":null}
// McpServerRuntimeRow (mcp.servers.status)
{"name":"linear","transport":"http","tools":23,"connected":true,"disabled":false,
 "status":"connected","source":"config","plugin":null}
// status enum: connected | disabled | connecting | failed | lazy | configured
```
Desktop-derived UI status (`mcp-status.ts statusOf`): `off` (disabled) · `probing` · `unknown` (no probe) · `ok` · `needs-auth` (probe error matches `/\b(401|unauthorized|forbidden|invalid[_ ]?token|authentication|oauth)\b/i`) · `error`. "Authenticate" shown for url servers when `auth:'oauth'` and status needs-auth/error, or no auth and needs-auth.

**Events**: none MCP-specific; `reload.mcp` result drives toasts (`session:'busy'` = deferred until the turn ends; `unavailable` lists servers that failed to connect).

**Notes**
- No gateway restart for MCP: save + `reload.mcp` re-discovers servers for every live session (invalidates the prompt cache). Without `confirm:true` the server may answer `confirm_required` (per `approvals.mcp_reload_confirm`). REST `/enabled` toggle alone only "takes effect on next session/gateway" unless followed by `reload.mcp`.
- Plugin-provided servers (`source:"plugin"`) are read-only (409/4090 on any write).
- Native app on a remote phone: the backend-loopback OAuth only works if the browser runs on the gateway host. Options: (a) Windows app hosts its own loopback and passes `client_redirect_uri`; (b) use the REST dashboard flow `POST /api/mcp/servers/{name}/auth`, whose redirect is `<public_url>/api/mcp/oauth/callback/{name}` (works remotely), then poll `GET /api/mcp/oauth/flows/{flow_id}`.
- Secrets never come back: REST redacts env values, RPC returns env key names only.

#### Connectors (hosted apps + connection cards)

**UI**
- Page: `app/capabilities/connectors/connectors-tab.tsx`, `connectors-directory.tsx`, `connector-row-card.tsx`, `connector-dialog.tsx`, `hosted-dialog.tsx`, `accounts-section.tsx`, `tools-panel.tsx`, `tools-list.tsx`, `tools-filter-bar.tsx`, `derive*.ts`, `types.ts` (card states `available|broken|connected|connecting|expired|off|unknown`; residency `hosted|local`).
- Data: `connectors/data/rpc.ts` (all calls), `queries.ts`, `mutations.ts`, `persist.ts`, `hosted-actions.ts`, `account-operations.ts` (`$accountOperations` store), `deep-link.ts`, `join.ts`.
- Chat card: `components/assistant-ui/connector-tool.tsx`; events in `app/session/hooks/use-message-stream/gateway-event/input-requests.ts`; store `store/connection-request.ts`.

**Calls** — all WS legacy RPC (`tui_gateway/methods_connectors.py`, `methods_connectors_account.py`; contracts `tui_gateway/contracts/connectors.py`, `connectors_operation.py`). Optional `profile` on all. Client timeout 45 s. `owner` = `{type:"account"}` (Connectors page) or `{type:"session", session_id}` (chat card).

| Method | Params | Result |
|---|---|---|
| `connectors.list` | `{owner (req)}` | `ConnectorsListResult {available: bool, connectors: ConnectorRow[]}` |
| `connectors.catalog` | `{}` | `{connectors: [{slug, name, description, category, logo_url?}]}` |
| `connectors.accounts` | `{connector?: slug}` | `{accounts: ConnectorAccountRow[]}` |
| `connectors.accounts.rename` | `{connection_id (req), alias (req, ^[a-z0-9][a-z0-9-]{0,31}$)}` | `ConnectorAccountRow` |
| `connectors.accounts.remove` | `{connection_id (req)}` | `{connection_id, connector, status:"removed"}` |
| `connectors.tools` | `{slug (req), refresh: bool=false}` | `{connector, toolkit_version, etag, fetched_at, source:'cache'\|'network'\|'revalidated', stale, tools:[{slug, name, description, facet:'read'\|'write'\|'destructive'\|'unclassified', hints[], categories[], no_auth, deprecated}]}` |
| `connectors.policy.get` | `{}` | `{layers:[{kind:'org'\|'role'\|'member', revision, body}], effective:{version:1, revision, issued_at_ms, mode:'unrestricted'\|'deny-all'\|'allow'\|'deny', connectors?, disabled_connectors?, tools?:{slug:[names]}, tags?}}` |
| `connectors.policy.set` | `{change: {type:'connector', connector, enabled} \| {type:'tools', connector, disabled_tools: string[] (≤2000)}, expected_revision: ULID (req)}` | `{revision, effective}`; `POLICY_CONFLICT` → refetch and retry |
| `connectors.connect` | `{owner, connectors: slug[] (≥1), reconnect: bool=false, alias? (exactly 1 connector), connection_id? (needs reconnect, 1 connector, no alias)}` | `ConnectionOperationStatus` + `status?: 'initiated'\|'settled'`, `note?`. Open `targets[].connect_url` in the system browser |
| `connectors.operation.status` | `{owner, op_id}` | `ConnectionOperationStatus {op_id, seq, deadline_at, settled, settled_at?, settled_by?: 'all_resolved'\|'continue'\|'deadline'\|'interrupt', targets[]}` |
| `connectors.operation.wake` | `{owner, op_id}` | `{status:"ok"}` — call when the browser leg returns (`hermes://connections/done?op=<id>&status=`) |
| `connection.respond` | `{owner, op_id, result: {targets: [{name, status:'approved'\|'skipped', detail?, env?: {KEY: value}}], settled_by?, said?: string}}` | `{status:"ok", settled}` |

Errors: JSON-RPC `error.data.reason` = `ConnectorErrorReason` (`INVALID_PARAMS` 4000, `NOT_OWNER` 4001, `INVALID_ANSWER`/`REISSUE_REFUSED` 4002, `UNKNOWN_OPERATION`/`UNKNOWN_TARGET` 4004, `CONNECTORS_UNAVAILABLE` 4031, `CONNECTOR_REQUEST_FAILED` 5034, plus `NEEDS_NOUS_AUTH`, `POLICY_CONFLICT`, `ALIAS_TAKEN`, `ORG_REQUIRED`, `FORBIDDEN_SCOPE`, …).

Routing: session-owned `connection.respond` / `connectors.operation.status|wake` / `connectors.connect` are served by the canonical gateway's session authority (`gateway/session_connections.py`); account-owned calls stay on legacy handlers. Same method names either way.

**Events**
- `connection.request` (session-scoped; opens the chat card; sets session `needsInput: true`; fires native notification kind `input`). Also restored on resume via `session.resume` → `pending_connection`.
```json
{"jsonrpc":"2.0","method":"event","params":{"type":"connection.request","session_id":"s_123","payload":{
 "op_id":"op_9","seq":1,"deadline_at":1761500000.0,"timeout_seconds":600,"tool_call_id":"call_abc",
 "targets":[{"name":"gmail","kind":"connector","action":"connect","state":"initiated","resolved":false,
   "connect_url":"https://...","alias":null,"detail":null}],"display":null}}}
```
- `connection.update` (one transition or settlement; full snapshot each frame; ignore frames with `seq` ≤ held). Session ops → emitted to that session; account ops → broadcast globally with `owner:{type:"account"}` and `connect_url`/`connection_id` stripped.
```json
{"type":"connection.update","session_id":"s_123","payload":{"owner":{"type":"session","session_id":"s_123"},
 "op_id":"op_9","seq":2,"deadline_at":1761500000.0,"settled":false,"targets":[...],
 "target":"gmail","from":"initiated","to":"connected","actor":"backend_watcher"}}
```
Target fields: `name, kind: connector|mcp|plugin|skill, action: authorize|connect|enable|install|reconnect, state: pending|initiated|connected|skipped|failed|expired|not_connected, resolved, detail, instructions, discovery_error, connect_url, connection_id, alias, attempt, required_env:[{name, required, secret, default, prompt}], tools, hint`, catalog rows add `display, description, tier, platforms, repo, sha, subdir, scan:{status, summary}, requires_hermes, has_desktop_half, target_profile, app_state, skill, phase, approved:{force, enable, ref}, enabled, missing_env, server_errors:[{name,error}], already_installed`.

**Examples**
```json
// ConnectorRow (connectors.list)
{"connector":"gmail","enabled":true,"connected":true,"connection_status":"active",
 "status_reason":null,"gateway_disabled_tools":["gmail_delete_message"]}
// ConnectorAccountRow
{"connection_id":"ca_01J...","connector":"gmail","status":"active","status_reason":null,
 "label":"me@example.com","alias":"work","active":true,"disabled":false,
 "created_at":"2026-10-01T10:00:00Z","updated_at":"2026-10-20T08:00:00Z"}
```

**Notes**
- Hosted connectors need a Nous account: no token → `connectors.list` `{available:true, connectors:[]}`; feature off → `available:false`. `connectors.connect` from the page creates a guest identity if none.
- Session card is gated by the `manage_connections` tool being enabled for that session.
- `connect_url` arrives only in the `connectors.connect` result (account ops) or session frames; open it externally. A native app that can't receive `hermes://connections/done` can rely on `connection.update` (backend watcher) or poll `connectors.operation.status`.
- No gateway restart needed.

#### Plugins

**UI**: `app/capabilities/plugins/plugins-tab.tsx`, `plugin-settings-form.tsx`, `plugin-update.ts`, `plugin-packages.ts`, `plugin-folder-access.tsx`; catalog `app/capabilities/catalog/*` (public `plugins.json`); stores `store/agent-plugins.ts`, `store/plugin-install-request.ts`, `store/plugin-catalog-install.ts`; Desktop renderer plugins `contrib/plugins-store.ts`, `contrib/runtime-loader.ts`; plugin backend bridge `api/plugins.ts`.

**Calls** — WS legacy
- `plugins.manage` `PluginsManageParams {action, profile?, ...}`:
  - `list` → `{plugins: AgentPluginRow[], user_count, bundled_count}`
  - `toggle {key|name, enable: bool}` → `{ok, unchanged?, restart_required, name, plugin, gateway_reloaded?, activation?}`
  - `install {identifier|repo|catalog_name, force?, enable?, ref?}` → `{ok, plugin_name, enabled, warnings[], missing_env[], python_dependencies[], known_issues[], after_install_path?, gateway_reloaded?, activation?}`
  - `update {name, accept_capabilities?, force?}` → `{ok, unchanged?, sha}` or `{ok:false, consent_required:true, delta:{surface:[added]}, delta_lines[]}` (retry with `accept_capabilities:true`) or `{ok:false, scan_blocked:true, scan_verdict, scan_findings:[{pattern_id, severity, category, file, line, description}]}` (retry with `force` only for `caution`)
  - `remove {name}` (user installs only) → `{ok, name, cleared_memory_provider?}`
  - `settings {key, values: {setting: value}}` (non-secret keys) → `{ok, name, written[], plugin}`
  - `onboarding` → `{onboarding: [{name, title, description, tier, platforms, app_state, sentence}]}`
  - `presence {names[]}` → `{presence: [{name, title, state, sentence, disclosure}]}`
  - errors: 4019 missing arg, 4020 not found, 4021 update/settings failure, 5026 generic.
- `plugins.catalog.lookup {name, profile?}` → `{status: 'found'|'invalid_name'|'unknown'|'unavailable', entry?: {name, repo, sha, subdir, tier}}` (backs `hermes://plugin/install?catalog=` links).
- `plugins.list {}` → flat legacy `{plugins:[{name, version, enabled, key?, tools?, hooks?, commands?, error?}], toolsets?, portable_mcp_servers?}` (not used by the hub).
- Plugin backend namespace (REST + WS): `ANY /api/plugins/{pluginId}/{path}` (profile-scoped) and WS `ws(s)://<base>/api/plugins/{pluginId}/{path}?token=<token>` (token mode; native grant via Electron `getPluginWsUrl` for local).

**Example**
```json
// AgentPluginRow
{"name":"demo-weather","key":"demo-weather","version":"1.2.0","description":"Weather tools","source":"user",
 "status":"enabled","portable":true,"install_dir":"C:/Users/u/.hermes/plugins/demo-weather",
 "has_desktop_half":false,"servers":[{"name":"weather","state":"connected","sentence":""}],
 "catalog_name":"demo-weather","catalog_tier":"community","installed_sha":"abc123","catalog_sha":"def456",
 "update_available":true,"settings_schema":[{"key":"units","type":"enum","label":"Units","description":"",
 "required":false,"value":"metric","default":"metric","choices":["metric","imperial"]}],"tools":["weather_now"]}
```
`servers[].state`: `connected|app_not_running|hermes_not_connected|endpoint_unavailable|no_interactive_session|version_too_old|missing_app|unsupported_gpu|unknown`. `activation`: `{name, key, activated_now:{kind:[names]}, live_now:{mcp_servers:[{name, connected, tools[], error?}], skills:[{name, description}]}, deferred:{tools|prompt:[...]}, missing_dependencies[], missing_in: ('gateway'|'backend')[]}`.

**Events**: none plugin-specific. After toggle/install the UI invalidates the toolsets query (plugin toolsets).

**Notes**
- Gateway restart only when `restart_required:true` (no gateway answered, or `activation.missing_dependencies` the running gateway/backend can't import). Otherwise live now (`gateway_reloaded`, `live_now`) or from next chat (`deferred`).
- Desktop renderer plugins (`desktop/plugin.js` halves, disk plugins, `removeDesktopPlugin`, `revealPluginsDir`, deep links) are Electron-only JS loaded into the renderer: a native app can't run them; show agent plugins only.
- Install/update are long (use the client's install timeout, minutes).

#### Messaging platforms (Channels)

**UI**: `app/messaging/index.tsx` (platform list + detail, pairing, restart banner), `telegram-qr-setup.tsx`, `allowlist-field.tsx`, `platform-icon.tsx`; status tone `lib/platform-status.ts`; restart `store/system-actions.ts` (`runGatewayRestart`, `watchGatewayRestartOutcome`). Wrappers `api/messaging.ts`.

**Calls** (REST; `?profile=`; writes may also carry body `profile`)

| Call | Params | Result |
|---|---|---|
| `GET /api/messaging/platforms` | `profile?` | `{env_path, gateway_start_command, platforms: MessagingPlatformInfo[]}` |
| `PUT /api/messaging/platforms/{id}` | `MessagingPlatformUpdate {enabled?: bool, env?: {KEY: value} (blank skipped; redacted previews rejected 400), clear_env?: string[], profile?}`; keys must belong to the platform | `{ok, platform, hot_served: bool}`; 409 multiplex port conflict (api_server/webhook on a secondary profile) |
| `POST /api/messaging/platforms/{id}/test` | `profile?` | `{ok, state, message, auth_posture?: 'open'\|'allowlist'\|'pairing'\|'deny_all'}` |
| `POST /api/messaging/telegram/onboarding/start` | `{bot_name?: string}` | `{pairing_id, suggested_username, deep_link, qr_payload, expires_at}` (render `qr_payload` as QR) |
| `GET /api/messaging/telegram/onboarding/{pairing_id}` | — | `{status:"waiting", expires_at}` \| `{status:"ready", bot_username, owner_user_id, expires_at}`; 410 expired/claimed, 404 |
| `POST /api/messaging/telegram/onboarding/{pairing_id}/apply` | `{allowed_user_ids: string[] (numeric, ≥1), profile?}` | `{ok, platform:"telegram", bot_username, needs_restart, restart_started, restart_action?:"gateway-restart", restart_pid?, restart_error?, restart_busy?}` |
| `DELETE /api/messaging/telegram/onboarding/{pairing_id}` | — | `{ok}` |
| `POST /api/messaging/whatsapp/onboarding/start` | `{mode?: 'bot'\|'self-chat' = 'bot', allowed_users?: csv, profile?, reset_session?: bool}` | `{pairing_id, status, qr_payload, expires_at, mode, allowed_users, account_id, account_name, account_phone, error}` |
| `GET /api/messaging/whatsapp/onboarding/{pairing_id}` | — | same; 410 expired |
| `POST /api/messaging/whatsapp/onboarding/{pairing_id}/apply` | `{mode?, allowed_users?, profile?}` (needs `status:"connected"`, else 409) | `{ok, platform:"whatsapp", needs_restart, restart_started, ...}` |
| `DELETE /api/messaging/whatsapp/onboarding/{pairing_id}` | — | `{ok}` |
| `GET /api/pairing` | `profile?` | `{pending: PairingUser[], approved: PairingUser[]}` |
| `POST /api/pairing/approve` | `{platform, request_id (preferred) \| code, profile?}` | `{ok, user}`; 404, 429 lockout |
| `POST /api/pairing/revoke` | `{platform, user_id, profile?}` | `{ok}`; 404 |
| `POST /api/pairing/clear-pending` | `profile?` | `{ok, cleared}` |
| `GET /api/webhooks` | `profile?` | `{enabled, base_url, subscriptions:[{name, description, events[], deliver, deliver_only, prompt, script, skills[], created_at, url, secret_set, enabled}]}` |
| `POST /api/webhooks/enable` | `profile?` | `{ok, platform:"webhook", enabled:true, needs_restart, restart_started, ...}` |
| `POST /api/webhooks` | `WebhookCreate {name (^[a-z0-9][a-z0-9_-]*$), description?, events[], prompt?, script?, skills[], deliver='log', deliver_only=false, deliver_chat_id?, secret?}` | summary + `secret` (shown once); 400 platform not enabled, 409 conflict |
| `DELETE /api/webhooks/{name}` | `profile?` | `{ok}` |
| `PUT /api/webhooks/{name}/enabled` | `{enabled}` | `{ok, name, enabled}` |
| Restart | `POST /api/gateway/restart` then poll `GET /api/actions/gateway-restart/status` (ops slice) | `ActionResponse` / `ActionStatusResponse` |

WhatsApp session `status`: `starting → installing → waiting (qr_payload = raw WhatsApp QR string) → connected | error | expired | cancelled`; TTL 600 s; `reset_session:true` backs up and re-pairs (409 if the bridge is running). The Desktop renderer has no WhatsApp QR UI (the routes are used by the web dashboard `web/src/lib/api.ts`); Desktop configures WhatsApp via env fields.

Platform ids (built-in): `telegram, discord, whatsapp, whatsapp_cloud, slack, signal, mattermost, matrix, email, sms, dingtalk, api_server, webhook, msgraph_webhook, feishu, wecom, wecom_callback, weixin, bluebubbles, qqbot, yuanbao, relay` + plugin platforms.

**Example**
```json
// MessagingPlatformInfo
{"id":"telegram","name":"Telegram","description":"...","docs_url":"https://...","enabled":true,
 "configured":true,"gateway_running":true,"state":"connected","error_code":null,"error_message":null,
 "updated_at":"2026-10-26T09:00:00Z","home_channel":{"platform":"telegram","chat_id":"123","name":"Home"},
 "ingress_url":null,"env_vars":[{"key":"TELEGRAM_BOT_TOKEN","required":true,"is_set":true,
 "redacted_value":"123…abc","value":null,"description":"","prompt":"Bot token","help":"","url":null,
 "is_password":true,"is_list":false,"advanced":false},{"key":"TELEGRAM_ALLOWED_USERS","required":false,
 "is_set":true,"redacted_value":"…","value":"11111,22222","is_list":true,"is_password":false,"advanced":false,
 "description":"","prompt":"Allowed users","help":"","url":null}]}
// PairingUser
{"platform":"telegram","user_id":"11111","user_name":"dana","request_id":"pr_abc","age_minutes":3}
```
`state`: runtime values from `gateway_state.json` (`connected`, `connecting`, `fatal`, `degraded`, `draining`, …) or computed `disabled`, `not_configured`, `pending_restart`, `startup_failed`, `gateway_stopped`. Tone: connected=good, disabled=muted, fatal/startup_failed=bad, else warn. `whatsapp` adds `whatsapp_setup:{mode, allowed_users_set, home_channel_set}`. `value` is returned only for list (allowlist) keys.

**Events**
- `platforms.changed` `{}` (gateway_state.json moved; 2 s watch, ≥5 s broadcast floor) → `GET /api/messaging/platforms`.
- `pairing.changed` `{}` (pairing store moved) → `GET /api/pairing`.
- Without change events (older backend) the page polls while visible.

**Notes**
- Gateway restart: platform enable/credential changes need one, unless `hot_served:true` (a multiplexer serving that named profile rebuilt adapters; re-read status after ~4 s). Telegram/WhatsApp apply and webhook enable start the restart themselves (`restart_started`); if `needs_restart` and not started, call `POST /api/gateway/restart`. Webhook subscription CRUD and pairing approve/revoke hot-reload (no restart).
- All browser-capable REST. Telegram onboarding calls the Nous setup service `https://setup.hermes-agent.nousresearch.com`; pairing state lives in the memory of the backend process that started it: keep the same profile scope for start/status/apply.
- Approve by `request_id`; the code is never returned by the API.


---

#### Common: server→client requests used by workspace panes

Wire (gateway → client, on `/api/ws`): `{"jsonrpc":"2.0","id":"srq-<12 hex>","method":"<m>","params":{"session_id":"<runtime sid>", ...}}` (`tui_gateway/server_requests.py::frame`). Answer: `{"jsonrpc":"2.0","id":"srq-…","result":{"value":"<string>"}}` (`ValueResult`). The renderer's `answerValue` sends `value = JSON.stringify(result)` or `""` (empty = nothing open / skipped). Gateway side `_ask()` returns that string to the tool.

- Window-owned methods (`preview.act`, `preview.read`, `terminal.read`, `window.read`, `theme.apply`, `tour`): only the client showing that session answers. Other clients decline with JSON-RPC error code **4404** (`NOT_SHOWN_CODE`) — only if the client advertised `client.capabilities {server_requests:[...], declines_not_shown:true}`; otherwise stay silent. The request settles when every attached answering client declined (`server_requests.py::_decline`).
- Cancel: gateway emits event `request.cancel` `{"id":"srq-…","method":"preview.act","reason":"timeout"}`.
- Deadlines: `terminal.read` 30 s, `preview.read` 45 s, `window.read` 30 s, `preview.act` 10 s probe on first use per session then 45 s (30 s reprobe cooldown after an unanswered probe), `display.install.sudo` 300 s.
- Source: `apps/desktop/src/app/session/hooks/use-message-stream/gateway-event/server-requests.ts` (handler table ~line 770), `tui_gateway/agent_callbacks.py::_agent_cbs`, `tui_gateway/server.py::_ask/_preview_action_request`.

Desktop-tool events from the agent (`tools/desktop_ui.py::emit`, routed to the window owning the turn via `HERMES_UI_SESSION_ID`): `preview.open`, `preview.close`, `pane.reveal`, `tip.show`, `message.reaction`. Tools return "only available in the Hermes desktop app" when no emitter is wired (i.e. not running under `tui_gateway`).

---

#### Artifacts

**UI**
- Artifacts page (route `/artifacts`, `ARTIFACTS_ROUTE` in `apps/desktop/src/app/routes.ts`): `apps/desktop/src/app/artifacts/index.tsx`, scan logic `apps/desktop/src/app/artifacts/artifact-utils.ts`. Grid of cards filtered `all | image | file | link`, paginated, search shell.
- In-chat artifact cards (generated HTML/SVG/long code): `apps/desktop/src/components/assistant-ui/artifact-card.tsx`, registry `apps/desktop/src/store/artifacts.ts`, detection `apps/desktop/src/lib/artifact-detect.ts`, rail viewer `apps/desktop/src/app/chat/right-rail/preview-artifact.tsx`.

**Calls** (no artifact-specific backend; everything is derived client-side from transcripts)
- REST `GET /api/profiles/sessions?limit=30&offset=0&min_messages=1&archived=exclude&order=recent&profile=all` → `{sessions: SessionInfo[], ...}` (`api/sessions.ts::listAllProfileSessions`).
- Per session, paged: REST `GET /api/sessions/{id}/messages?profile=<p>&limit=100&offset=<n>&order=oldest&include_compacted=true` → `{messages: SessionMessage[], pagination?: {limit,...}}`. Stop when `messages.length < pagination.limit` or no `pagination` (legacy returns all). A page whose JSON > 32 000 000 chars is refused client-side ("exceeds the Desktop safe-load limit").
- Image thumbnails: `resolveMediaDisplaySrc(path)` → remote/browser: REST `GET /api/fs/read-data-url?path=<p>[&session_id=]` → `{"dataUrl":"data:image/png;base64,..."}`; Electron-local: `hermesDesktop.readFileDataUrl`. Blocked remote CDN images: REST `GET /api/media/proxy?url=<https url>` → `{"data_url":"data:..."}` (host allowlist `fal.media`, `fal.run`, `v3.fal.media`, `storage.googleapis.com`, `*.fal.media`, `*.fal.run`; 25 MiB cap).
- Open a file artifact on a remote gateway: `downloadGatewayMediaFile` → `hermesDesktop.saveGatewayFile` → browser: `HEAD /api/fs/download?path=&session_id=` then ticketed `GET /api/fs/download` (see Files tickets). Link artifacts: `openExternal(href)` / `window.open`.

**Scan rules** (`artifact-utils.ts`): only `assistant` and `tool` messages; sources = `MEDIA:` tags (explicit), markdown images/links, bare `https?://` URLs, POSIX/`~`/Windows paths with known extensions (`png jpg jpeg gif webp svg bmp pdf txt json md csv xls(x) doc(x) ppt(x) html zip tar gz avi flac m4a mkv mp3 ogg opus wav webm mp4 mov`), tool-result JSON keys (`artifact_file|image|path|url`, `files_created|modified|written`, `generated_*`, `media_tag`, `output_file|path|url`, `result_*`, `saved_to`, `screenshot_path`; plus `path|file_path|local_path|media*|download*|attachment*` when tool name matches `create|download|export|generate|render|save|speech|tts|write`), `Screenshot path: …` lines. pip cache / wheel / package-index URLs are dropped. Dedup key `${session.id}:${value}`.

Record (client-side, `ArtifactRecord`):
```json
{"id":"20260101_ab12:/home/u/out/chart.png","kind":"image","value":"/home/u/out/chart.png","href":"file:///home/u/out/chart.png","label":"chart.png","sessionId":"20260101_ab12","profile":"default","sessionTitle":"Sales chart","timestamp":1767225600000}
```

**Generated artifacts (rail)**: a fenced block becomes an artifact when `html` document ≥160 chars or html fragment ≥1200 chars with tags, `svg` ≥2000 chars, or code ≥48 lines or ≥3000 chars. Identity = (session, slug `kind:language:title`), versions dedup by content hash; memory-only (rebuilt from transcript on reload). PreviewTarget `{kind:"artifact", url:<registry id>}`.
- HTML render: `<iframe sandbox="allow-scripts" srcDoc={composeArtifactHtml(content)}>` (fragment wrapped in `<!doctype html><html><head><meta charset=utf-8>…<body>`). SVG: DOMPurify (`USE_PROFILES {svg, svgFilters}`) inline. Also Source view and Download (Blob, MIME by kind). "Open in browser" (Electron only, `window.hermesDesktop` present): writes temp file via buffer-save IPC then `openPreviewInBrowser(file://…)`.

**Events**: none specific. The registry is fed by streaming `message.delta` / `message.complete` content.

**Notes**: Fully browser-capable. Native app: replicate the scanner and render HTML in a sandboxed WebView with scripts on, no same-origin, no navigation.

---

#### Files tree (workspace browser, right sidebar "Files")

**UI**
- `apps/desktop/src/app/right-sidebar/index.tsx` (`RightSidebarPane` → `FilesystemTab`, `PaneEmptyState`), `files/tree.tsx`, `files/use-project-tree.ts` (lazy per-dir load, expansion state, live refresh, 3 s error self-heal), `files/ipc.ts` (gitignore filtering with the `ignore` npm lib, nested-repo detection), `files/prefs.ts` (show-ignored toggle), `files/remote-picker.tsx` (folder picker for remote gateways, can create folders), `files/dnd-manager.ts` (drag to composer), `right-sidebar/file-actions.tsx` (context menu).
- Facade: `apps/desktop/src/lib/desktop-fs.ts` — Electron-local → `window.hermesDesktop.*` IPC; remote gateway OR browser-hosted (`isDesktopFsRemoteMode()`) → REST `/api/fs/*`. Browser bridge: `apps/desktop/src/lib/browser-files.ts`.
- Root = session cwd (`$currentCwd`); remote with no cwd → `/api/fs/default-cwd`.

**Calls** (all REST, auth = session header/cookie; every call takes `?profile=<name>` (added by `hermesApi`), many take `session_id` so `/workspace` resolves to that session's Docker sandbox)

| Call | Params | Result |
|---|---|---|
| `GET /api/fs/list` | `path` (req), `profile?` | `{"entries":[HermesReadDirEntry], "error"?: "ENOENT"\|"ENOTDIR"\|"EACCES"\|<strerror>}` (errors return 200 with empty entries) |
| `GET /api/fs/read-text` | `path` (req), `profile?`, `session_id?` | `{"binary":bool,"byteSize":int,"language":str,"mimeType":str,"path":str,"text":str,"truncated":bool}` — preview read cap 512 KiB, 413 if file > 64 MiB |
| `POST /api/fs/write-text` | body `{"path":str,"content":str,"session_id"?:str}`, `?profile=` | `{"ok":true,"path":str,"byteSize":int}`; parent must exist, regular files only, ≤ 8 MiB, atomic temp+rename; 400/403/413/500 |
| `GET /api/fs/read-data-url` | `path`, `profile?`, `session_id?` | `{"dataUrl":"data:<mime>;base64,..."}`; cap `WEBAPP_ATTACHMENT_MAX_BYTES` = 16 MiB (413) |
| `GET\|HEAD /api/fs/download` | `path`, `profile?`, `session_id?`, `ticket?` | file bytes, `Content-Disposition: attachment` (refuses live SQLite DBs) |
| `GET /api/fs/git-root` | `path`, `profile?` | `{"root": "/abs/repo" \| null}` |
| `GET /api/fs/default-cwd` | `profile?` | `{"cwd":str,"branch":str}` |
| `POST /api/files/mkdir` | body `{"path":str}` | managed write result (below) — used by remote picker |
| `GET /api/git/file-diff` | `path`=repo root, `file` | `{"diff": "<unified diff vs HEAD>"}` (file-tree "diff" action) |

Hidden from `/api/fs/list`: `.git .hg .svn .cache .next .turbo .venv __pycache__ build dist node_modules target venv`, plus sensitive names (`auth.json`, `auth.lock`, `credentials`, `config.yaml`, `.anthropic_oauth.json`, `google_token.json`, `google_oauth*.json`, `webhook_subscriptions.json`, `bws_cache*.json`, `.git-credentials`, any path through `mcp-tokens/` or `pairing/`). Sorted dirs first, case-insensitive. Hosted mode (`/opt/data` root or `HERMES_DASHBOARD_FILES_ROOT`) adds a read guard to that root. SSH-backed profiles: the same routes are served from the SSH workspace (`_fs_backend` / `_remote_fs_backend`).

File tree entry (`HermesReadDirEntry`):
```json
{"entries":[{"name":"src","path":"/home/u/proj/src","isDirectory":true},{"name":"README.md","path":"/home/u/proj/README.md","isDirectory":false}]}
```

Context menu (`file-actions.tsx`): remote/browser → Copy path, Copy relative path, Download (`saveGatewayFile` → ticketed `/api/fs/download`). Electron-local only → Reveal in Finder/Explorer, Rename (`hermesDesktop.renamePath`), Delete to trash (`hermesDesktop.trashPath`). No REST rename/trash for the workspace tree.
Click file → opens a Preview tab (`PreviewTarget kind:"file"`), text via `/api/fs/read-text`, images/PDF via `/api/fs/read-data-url` (PDF rendered in `<iframe src=blob:>`), HTML via data URL in remote mode (`remoteHtmlPreviewDocument`). In-place editor saves with `/api/fs/write-text` (`store/preview-edit.ts`).

**Events**: no fs watcher over the gateway. Refresh is driven by `tool.complete` (esp. with `inline_diff`) → `notifyWorkspaceChanged` (`store/workspace-events.ts`, throttled 500 ms) → surgical re-read of touched dirs, or full rescan for opaque mutations (terminal commands). Electron-only: `hermesDesktop.watchPreviewFile/onPreviewFileChanged` (browser stub = no-op).

**Notes**: `tui_gateway/project_tree.py` is NOT the file tree — it builds `projects.tree` (project → repo → lane → session sidebar; `ProjectsTreeResult` / `ProjectTreeNode` in the contract), owned by the Projects slice. `projects.for_cwd {cwd}` → `{project?, cwd, branch?}`.

---

#### Managed files, uploads, downloads, file tickets

**UI**: Webapp/dashboard Files browser (managed root), composer attachments (`apps/desktop/src/lib/browser-uploads.ts`, `app/session/hooks/use-prompt-actions/index.ts`), media players in transcript.

**Calls — managed files** (REST, root = `HERMES_DASHBOARD_FILES_ROOT` / `/opt/data` hosted, or free path; `_resolve_managed_path`)
- `GET /api/files?path=<dir>` → `{"path":str,"parent":str|null,"entries":[ManagedEntry],"root":str|null,"locked_root":str|null,"can_change_path":bool}`
- `GET /api/files/read?path=` → `{"name","path","size","mime_type","data_url","root","locked_root","can_change_path"}`
- `GET /api/files/download?path=[&ticket=|&token=]` → attachment; served inline when `Sec-Fetch-Dest: audio|video`.
- `GET|HEAD /api/files/stream?path=&profile?&session_id?&ticket=` → inline media with HTTP Range (SSH workspaces: `web_server_remote_media.py` single-range 200/206/416, `Accept-Ranges: bytes`, `nosniff`). Session token never accepted in this URL — ticket or header/cookie only.
- `POST /api/files/upload` body `{"path":str,"data_url":str,"overwrite":bool=true}` → write result.
- `POST /api/files/upload-stream` multipart `file`, `path`, `overwrite` (form, default true) → write result; 100 MiB cap (`_MANAGED_FILE_MAX_BYTES`), 1 MiB chunks, atomic.
- `POST /api/files/mkdir` body `{"path"}`; `DELETE /api/files` body `{"path":str,"recursive":bool=false}` (refuses managed root / fs root).
- Write result: `{"ok":true,"entry":ManagedEntry,"path":str,"root","locked_root","can_change_path"}`. 409 when a dir/file already exists (overwrite false).

ManagedEntry:
```json
{"name":"report.pdf","path":"/opt/data/out/report.pdf","is_directory":false,"size":48211,"mtime":1767225600.12,"mime_type":"application/pdf"}
```
(broken symlink: `{"broken_link":true,"size":null,"mtime":null,"mime_type":null}`).

**Calls — file tickets** (`hermes_cli/web_server_file_tickets.py`)
- `POST /api/files/ticket` body `{"route":"download"|"stream","path":str,"profile"?:str,"session_id"?:str}` → `{"ticket":"..."}` (`Cache-Control: no-store`). Only on the **webapp** surface (`route_on("browser_files")`).
- Then `GET /api/fs/download?path=&profile=&session_id=&ticket=` (route `download`) or `GET /api/files/stream?...&ticket=` (route `stream`). The ticket admits exactly that route + query (same `path/profile/session_id` set, nulls omitted).
- Download ticket: single-use, TTL 60 s. Stream ticket: reusable, TTL 3600 s. In-memory (die on restart); a ticket minted with the browser session credential dies on sign-out.
- Browser only needs tickets when the page has a session token (`bootstrap.token`); cookie-gated hosts use the cookie. A native client that sends the auth header on every request can GET these URLs directly; tickets are only needed when handing a URL to a player/download manager that can't add headers.

**Calls — chat attachments**
- `POST /api/chat/file-upload?profile=` multipart `file` → `{"ok":true,"path":"<HERMES_HOME>/uploads/...","size":int,"staged_upload"?:{"install_id","path","profile_home"}}`; cap 16 MiB; webapp surface only. Then WS legacy `file.attach` `{session_id, profile?, path?, data_url?, name?, staged_upload?}` → `FileAttachResult {attached, name, path, ref_path, ref_text, uploaded}`.
- `POST /api/chat/image-upload?profile=` body `{"data_url":str,"filename"?:str}` → `{"ok":true,"path":"<HERMES_HOME>/images/...","name","bytes","mime_type"}` (≤ 25 MiB stored; send reads back ≤16 MiB via `/api/fs/read-data-url`).
- `GET /api/media?path=` → `{"data_url"}` (image allowlist, media roots only, 25 MiB).

**Notes**: `/api/files/ticket`, `/api/chat/file-upload` and `/api/host-terminal` exist only when the server runs the `webapp` surface (`hermes_cli/web_server_surface.py POLICIES`); `serve`/`dashboard`/`gateway` surfaces have `browser_files=false`.

---

#### Git review and coding rail

**UI**
- Review pane: `apps/desktop/src/app/right-sidebar/review/index.tsx` (+ `file-tree.tsx`, `scope-row.tsx` (scope picker), `ship-bar.tsx` (commit / push / PR), `churn-bar.tsx`, `tree-data.ts`); state `apps/desktop/src/store/review.ts`; PR badges `store/pull-requests.ts`.
- Coding rail (composer git chip: branch, ahead/behind, +/-): `apps/desktop/src/store/coding-status.ts` (`$repoStatusByCwd`, debounce 100 ms); worktree/branch pickers `store/projects.ts`.
- Facade `apps/desktop/src/lib/desktop-git.ts`: Electron-local → `window.hermesDesktop.git` (Electron runs git), remote/browser → REST mirror below. `scanRepos` is a no-op remotely.

**Calls** (REST, all `?profile=` scoped; GET params are query strings, POST bodies JSON; `path` = repo cwd; failures in mutations → 400 `{"detail": "<git stderr>"}`)

| Call | Params | Result |
|---|---|---|
| `GET /api/git/status` | `path` | `HermesRepoStatus` or `null` (not a repo) |
| `GET /api/git/review/list` | `path`, `scope`=`uncommitted`(default)\|`branch`\|`lastTurn`, `base?` | `{"files":[HermesReviewFile],"base":str\|null}` |
| `GET /api/git/review/diff` | `path`, `file`, `scope`, `base?`, `staged`=bool | `{"diff":str}` |
| `GET /api/git/file-diff` | `path`, `file` | `{"diff":str}` (working tree vs HEAD) |
| `POST /api/git/review/stage` | `{"path","file":str\|null}` (null = all) | `{"ok":true}` |
| `POST /api/git/review/unstage` | `{"path","file"}` | `{"ok":true}` |
| `POST /api/git/review/revert` | `{"path","file"}` | `{"ok":true}` (discard changes) |
| `GET /api/git/review/rev-parse` | `path`, `ref?` (default HEAD) | `{"sha":str\|null}` |
| `POST /api/git/review/commit` | `{"path","message":str,"push":bool=false}` | `{"ok":true}` |
| `POST /api/git/review/push` | `{"path"}` | `{"ok":true}` |
| `GET /api/git/review/commit-context` | `path` | `{"diff":str,"recent":"<last 10 subjects>"}` |
| `GET /api/git/review/ship-info` | `path` | `{"ghReady":bool,"pr":{"url","state","number"}\|null}` |
| `POST /api/git/review/pr-list` | `{"path","branches":[str],"numbers":[int]}` | `{"ghReady":bool,"prs":[{"branch","draft","number","state","title","url"}]}` |
| `POST /api/git/review/create-pr` | `{"path"}` | `{"url":str}` (`gh pr create`) |
| `GET /api/git/gh-auth` | `refresh?`=bool | `{"available":bool,"authenticated":bool}` (cached 5 min) |
| `GET /api/git/worktrees` | `path` | `{"worktrees":[{"path","branch":str\|null,"isMain","detached","locked"}]}` |
| `POST /api/git/worktree/add` | `{"path","name"?,"branch"?,"base"?,"existingBranch"?}` | `{"path","branch","repoRoot"}` |
| `POST /api/git/worktree/remove` | `{"path","worktreePath","force":bool=false}` | `{"removed":str}` |
| `GET /api/git/branches` | `path` | `{"branches":[{"name","checkedOut","isDefault","isRemote","worktreePath":str\|null}]}` |
| `GET /api/git/base-branches` | `path` | `{"branches":[{"name","isRemote","isDefault"}]}` |
| `POST /api/git/branch/switch` | `{"path","branch"}` | `{"branch":str}` |

Git status example (`hermes_cli/web_git.py::repo_status`, files capped at 200):
```json
{"branch":"feature/x","defaultBranch":"main","detached":false,"ahead":2,"behind":0,
 "staged":1,"unstaged":2,"untracked":1,"conflicted":0,"changed":4,"added":57,"removed":12,
 "files":[{"path":"src/app.ts","staged":true,"unstaged":false,"untracked":false,"conflicted":false},
          {"path":"notes.md","staged":false,"unstaged":true,"untracked":true,"conflicted":false}]}
```
Review list example:
```json
{"files":[{"path":"src/app.ts","added":40,"removed":12,"status":"M","staged":false},
          {"path":"new.txt","added":17,"removed":0,"status":"?","staged":false}],"base":null}
```
Status letters: `M A D R C U ?`. Diff example (`{"diff": ...}`): standard unified diff text, e.g. `"diff --git a/src/app.ts b/src/app.ts\n--- a/src/app.ts\n+++ b/src/app.ts\n@@ -1,3 +1,4 @@\n-old\n+new\n"`; untracked files diffed as all-add.

**Flows**
- Scopes: `uncommitted` (stage/unstage/revert allowed), `branch` (vs merge-base with trunk, read-only), `lastTurn` (vs HEAD captured by `review/rev-parse` when the turn started, keyed by cwd; read-only).
- AI commit message: `commit-context` → WS legacy `llm.oneshot` `{template:"commit_message", temperature:0.8, variables:{avoid, diff, recent_commits}}` → `{text}` (`store/review.ts::generateCommitMessage`, `lib/oneshot.ts`).
- Refresh: on `$workspaceChangeTick` (driven by `tool.complete`), on pane open, manual refresh.

**Events**: none git-specific (`review.summary` is the self-improvement memory/skill review notice, not git).

**Notes**: Browser-capable via REST. Paths go through `_fs_path` + hosted read guard. `gh` CLI must be installed/authenticated on the gateway host for PR features.

---

#### Rollback / checkpoints

**UI**: slash commands `/rollback [list] | diff <n|hash> | <n|hash> [file] [--all]` in `apps/desktop/src/app/session/hooks/use-prompt-actions/slash-desktop-commands.ts` (lines ~119-140, 317+); classic CLI `/diff session`.

**Calls**
- WS legacy `rollback.list` `{session_id, profile?}` → `{"enabled":bool,"checkpoints":[{"hash","timestamp","message"}]}` (err 5020).
- WS legacy `rollback.diff` `{session_id, hash (req), profile?}` → `{"stat":str,"diff":str (≤4000 chars),"rendered"?:str}` (err 4014 hash required, 5022).
- WS legacy `rollback.restore` `{session_id, hash (req), file_path?, all?:bool, profile?}` → `RollbackRestoreResult {success, restored_to?, reason?, directory?, file?, restored_files?, skipped_user_edits?, skipped_oversize?, failed_deletes?, history_removed?, error?}`. Whole-tree restore (no `file_path`) also rewinds the transcript to before the newest user turn and is refused mid-turn (4009); keeps user hand-edits unless `all:true`.
- WS canonical `rollback.session_diff` `{session_id, profile?}` → `{"enabled":true,"success":bool,"stat","diff","diff_lines":int,"empty":bool,"error"?}` (stat+diff fitted to 4 MiB).
- Note: `rollback.list/diff/restore` are typed legacy (`RpcMethods`) but the shared owner also serves them (`gateway/session_tui_ops.py::handlers`). Container-backed sessions refuse host checkpoints.

---

#### Terminal rail (host shell over sibling WebSocket)

**UI**: `apps/desktop/src/app/right-sidebar/terminal/rail.tsx` (tabs), `workspace.tsx` (mounts user + agent tabs), `instance.tsx` (xterm), `use-terminal-session.ts`, `persistent.tsx`, `revive-*.ts`/`snapshot-persister.ts` (local scrollback revive), `cwd-tracking.ts`, `buffer.ts` (`readActiveTerminal` for `terminal.read`), `terminals.ts` (`$terminals`, `kind:'user'|'agent'`); takeover toggle + `runInTerminal(cmd)` in `right-sidebar/store.ts`. Browser transport `apps/desktop/src/lib/browser-terminal.ts`.

**Transport**
- Electron: local node-pty via IPC (`hermesDesktop.terminal`) — the shell runs on the user's machine even with a remote gateway. `/api/host-terminal` is used ONLY by the browser Webapp (`browser-desktop-bridge.ts`), shell on the gateway host.
- 1) REST `POST /api/webapp/socket-ticket?socket=host-terminal` → `{"ticket":str,"ttl_seconds":30}` (one-use, per dial). 2) Sibling WS `GET /api/host-terminal?ticket=<t>&profile=<p>&cols=<n>&rows=<n>[&cwd=<dir>][&attach=<terminalId>]`. Close an orphaned shell: `?ticket=&attach=<id>&action=close`.
- Availability: `webapp` surface only, and only if bound to loopback or auth is required (`web_host_terminal.request_allowed`), else close 4403. Embedded chat disabled → 4404.
- Query: `cols` default 80 (2..2000), `rows` default 24 (2..1000), `cwd` (falls back to home; a file → its parent), `attach` = 43-char `[A-Za-z0-9_-]` token.
- Shell ladder: POSIX `$SHELL`, `/bin/zsh`, `/bin/bash`, `/bin/sh` (`-il` for zsh/bash); Windows `pwsh`, Windows PowerShell, `COMSPEC`, `cmd.exe` (`-NoLogo` for PowerShell). Env `TERM=xterm-256color`, `COLORTERM=truecolor`, `TERM_PROGRAM=Hermes`, `HERMES_DESKTOP_TERMINAL=1`, secrets scrubbed.
- Sessions survive disconnect for 15 min (`RETENTION_SECONDS`), 1 MiB ANSI tail retained, max 16 shells/process (then 1013), not across backend restart. One attached viewer per shell (a new attach supersedes → 4409).

**Frames** (`hermes_cli/web_host_terminal_sessions.py`, `pty_session.py`)
- Server→client, first frame (text): `"\u0000HERMES_TERMINAL_META:" + JSON`
  ```json
  {"shell":"pwsh.exe","terminalId":"Xk3...43chars...","cwd":"C:\\Users\\u\\proj","reconnected":false,"retentionSeconds":900,"truncated":false}
  ```
- Then one binary frame = replay snapshot (may be empty), then binary output frames (raw PTY bytes). Empty binary frames are liveness probes (~1 s while input is backlogged) — ignore. Text non-META frames are output too (e.g. red startup error before close 1011).
- Close ack (text): `"\u0000HERMES_TERMINAL_META:{\"terminalId\":\"...\",\"closed\":true}"` then close 1000.
- Client→server: keystrokes as text or binary (UTF-8), e.g. `"ls -la\r"`; resize as the exact string `"\u001b[RESIZE:<cols>;<rows>]"` (regex `\x1b\[RESIZE:(\d+);(\d+)\]`, may be mixed between keystroke frames). On resume, the client sends its size (and if `truncated`, first `rows-1` then `rows` to force a redraw) and clears xterm with `\u001bc`.
- Close codes: 1000 normal, 1001 viewer stalled (reconnect), 1011 PTY start failure, 1013 capacity, 4401 bad/expired credential or sign-out, 4403 denied (other identity/profile, or policy), 4404 embedded chat disabled, 4408 peer not allowed, 4409 superseded, 4410 expired / `reason:"Process exited"` (shell exited: close the tab). Client retry: ≤5 retries, backoff 500 ms→8 s, 10 s startup timeout.
- Persist `{terminalId, profile}` per tab to reattach after reload.

**Other calls**
- WS legacy `terminal.resize` `{session_id, cols?, profile?}` → `{"cols":int}` — sets the session's render width for server-rendered diffs (not the PTY).
- WS canonical `shell.exec` `{session_id, command, profile?}` (legacy variant without `session_id`) → `{"stdout","stderr","code"}`; 30 s timeout (5002), dangerous/hardline commands blocked (4005), empty 4004. Not used by the desktop renderer (TUI `!cmd`).
- Server request `terminal.read` `{session_id, start?, count?}` → value = JSON of active tab buffer: `{"total_lines":int,"start":int,"end":int,"viewport_rows":int,"cursor_row":int,"text":str}`; `""` when no terminal pane.

---

#### Agent terminals and background processes

**UI**: read-only agent tabs in the terminal rail (`terminal/use-agent-terminal.ts`, `agent-terminal-stream.ts`), composer status stack rows (`app/chat/composer/status-stack/status-row.tsx`, store `apps/desktop/src/store/composer-status.ts`), `/stop` and `/agents` slash commands.

**Calls**
- WS legacy `process.list` `{session_id, profile?}` → `{"processes":[ProcessEntry]}` (session-scoped). Polled ~5 s per live session plus on events (`refreshBackgroundProcesses`).
- WS legacy `process.kill` `{session_id, process_id, profile?}` → `ProcessKillResult {status: killed|already_exited|not_found|error, exit_code?, output?, ...}`; 4012 missing id, 4044 not this session's process.
- WS legacy `process.stop` `{session_id?, profile?}` → `{"killed":int,"delegations"?:int}` (kills ALL registry processes).
- ProcessEntry example:
```json
{"session_id":"proc_9f2c","command":"npm run dev","cwd":"/home/u/app","pid":41233,"started_at":"2026-10-26T10:00:00","uptime_seconds":312,"status":"running","output_preview":"ready on http://localhost:5173","output_tail":"...","session_scoped":true,"notify_on_complete":false,"exit_code":null}
```

**Events**
- `agent.terminal.output` `{"type":"agent.terminal.output","session_id":"<owner sid>","payload":{"process_id":"proc_9f2c","chunk":"compiled in 120ms\r\n"}}` → append to that process's read-only tab.
- `terminal.close` `{"payload":{"process_id":"proc_9f2c"}}` → drop the tab only (process keeps running; agent `close_terminal` tool).
- Tabs are created from the `process.list` snapshot (`ensureAgentTerminal(item.id,item.title)` + `syncAgentTerminalSnapshot(output)`), not from an event.

**Notes**: All WS; browser-capable. Events route to the session owning the process (`tui_gateway/session_notifications.py::_wire_desktop_sinks`).

---

#### Preview pane (in-app browser / file / artifact tabs)

**UI**: `apps/desktop/src/app/chat/right-rail/preview-pane.tsx` (web tabs: Electron `<webview>`, browser-hosted `<iframe>`), `preview-file.tsx`, `preview-artifact.tsx`, `preview-browser-bar.tsx`, `preview-console.tsx`, `preview-act.ts` (drive engine), `preview-reader.ts`, `preview-annotate-*`, `preview-popout-bridge.ts` (popped-out Browser window); store `apps/desktop/src/store/preview.ts`; routing `apps/desktop/src/app/session/hooks/use-preview-routing.ts`; tile `app/chat/preview-tile.tsx`.

PreviewTarget (client model):
```json
{"kind":"url","url":"http://localhost:5173/","label":"Dev server","source":"localhost:5173","previewKind":"html","renderMode":"preview"}
```
`kind: artifact|file|url`; `previewKind: binary|directory|html|image|missing|pdf|text`; tabs are per session (+ pinned).

**Events (gateway → client)**
- `preview.open` `{"type":"preview.open","session_id":"s1","payload":{"url":"http://localhost:5173","label":"App"}}` — from agent `desktop_preview action=open` / `open_preview`. Open only if the session is on screen; url may be a web URL, bare domain, localhost server, or file path.
- `preview.close` `{"payload":{"url":"http://localhost:5173"}}` — no `url` = close all agent tabs.
- `pane.reveal` `{"payload":{"pane":"files"}}` — pane ∈ `chat|files|terminal|review|sessions`; active session only.
- `preview.restart.progress` `{"payload":{"task_id":"preview_a1b2c3","text":"Starting hidden restart agent","level":"info"}}`; `preview.restart.complete` `{"payload":{"task_id":"preview_a1b2c3","text":"Server restarted on :5173","question"?:null}}`.
- `browser.progress` `{"payload":{"message":"Launching Chrome with CDP on :9222","level":"info"}}` (for `browser.manage connect`).
- `tool.complete` with file diffs reloads open url tabs (`requestPreviewReload`).

**Calls**
- WS legacy `preview.restart` `{session_id (req), url (req), cwd?, context?, profile?}` → `{"task_id":"preview_<6hex>"}` — spawns a hidden side agent to restart the dev server (4012 url required). UI button only on `kind:"url"` tabs.
- WS legacy `browser.manage` `{action?: "status"|"connect"|"disconnect", url?, session_id?, profile?}` → `{"connected":bool,"url"?,"messages"?:[str]}` — attaches the agent's browser tool to a Chrome CDP endpoint (`/browser` slash command), not the preview pane. Errors 4015 bad url, 5031 unreachable.
- Server request `preview.read` `{session_id, start?, count?}` → value JSON `PreviewReadResult`:
```json
{"kind":"url","url":"http://localhost:5173/","title":"Vite App","text":"Welcome ...","start":0,"end":2000,"total_chars":5120,"active_tab_id":"t1","tabs":[{"id":"t1","kind":"url","label":"App","url":"http://localhost:5173/"}]}
```
  (file tabs answer identity only; `""` = nothing open).
- Server request `preview.act` `PreviewActRequestParams {session_id, action, ref?, selector?, text?, key?, submit?, full?, to?: "top"|"bottom", amount?, max?, allow_shortcut?}`; `action` ∈ `click|elements|hold|hover|locate|pin|press|scroll|strobe|type|unpin`. Answer value JSON `PreviewActResult`:
```json
{"success":true,"acted":"click","tag":"button","title":"Vite App","url":"http://localhost:5173/","delta":{"added":[],"changed":[],"removed":[],"same":42},"point":{"x":120,"y":340}}
```
  Refused when the session is not the active one: `{"success":false,"error":"The in-app browser only takes actions in the session the user is looking at."}`.
- Server request `window.read` `{session_id}` → value JSON `{"platform":"darwin","frontmost":{"app","title"}|null,"window":{"app","title","id","bounds":{"x","y","width","height"}}|null,"note"?}`; Electron-only (`hermesDesktop.readWindowBelow`), browser answers `""`.

**Notes**: Browser-hosted: web tabs are plain iframes (cross-origin pages can't be read/driven; `preview.read/act` on them is likely unsupported (unverified)); `normalizePreviewTarget`, `watchPreviewFile`, `capturePreview` (screenshot for annotate) are Electron-only (stubs in `browser-bridge-stubs.ts`). `web_server_remote_media.py` is NOT preview: it is Range streaming of SSH-workspace media for `/api/files/stream`. Native app: use a platform WebView per tab, inject the act/read engine (`lib/preview-act/act-in-page.ts`) to answer `preview.act/read`.

---

#### Bot screen (Bot Desktop / display)

**UI**: plugin `apps/desktop/src/plugins/hermes-bots/screen-pane.tsx` (noVNC viewer, Watch / Take over / Hand back), `screen-hero.tsx` (thumbnail, refresh 4 s), `screen-install.tsx` (install log), `screen-portal.tsx`, `screen-connection.ts` (URL + viewer hash).

**Calls** (WS legacy, all `@_profile_scoped` → pass `profile`; errors code 5300)
- `display.status` `{profile?}` → `DisplayStatus`.
- `display.start` `{profile?}` → `DisplayStatus`; `display.stop` `{profile?, force?}` → `DisplayStatus + {stopped}` (refused while a human holds the lease unless `force`).
- `display.thumbnail` `{profile?}` → `{"data_url":str|null,"suppressed"?:"human_has_control"}`.
- `display.observe` `{profile?, viewer_id?}` → `DisplayStatus + {"ticket":str,"path":"/api/display/ws","viewer_id":str}` (ticket single-use, 30 s; fails if screen not running).
- `display.lease.acquire` `{profile?, viewer_id (req), reason?}` → `{"lease":DisplayLease}` (Take over); `display.lease.release` `{profile?, viewer_id?, force?}` → `{"lease":DisplayLease}` (Hand back; `data.code:"viewer_mismatch"` when releasing another viewer's lease without force).
- `display.install` `{profile?}` → `{"started":true,"command":str,"profile_key":str}` (Linux gateway only; asks sudo via server request).
- `display.switchSandboxImage` `{profile?, approve?:bool}` → `DisplayStatus + {"docker_image":str}`.
- Server request `display.install.sudo` `{session_id:"", profile_key}` → answer `{"value":"<sudo password>"}` (masked prompt card; `""` = cancel).
- Sibling WS (raw RFB, binary both ways, noVNC): `wss://<host>/api/display/ws?display_ticket=<ticket>` (gateway credential stripped; the ticket is the only credential). Input reaches Xvnc only from the lease holder. Close codes: 4000 `control-taken` (drop to Watch, reconnect), 4001 desktop gone, 4401 bad ticket / sign-out, 4403 not allowed, 1003 protocol.

DisplayStatus example:
```json
{"profile":"coder","supported":true,"installed":true,"missing":[],"running":true,"pid":5521,"display":":11","socket":"/home/u/.hermes/profiles/coder/bot-desktop/rfb.sock","geometry":"1280x800","install_command":null,"browser":"chromium","blocker":null,"memory_available_mb":2048,"memory_limit_mb":null,"placement":"host","image_switch":null,"lease":{"holder":"agent","viewer_hash":null,"since":1767225600.0,"epoch":7},"profile_key":"ab12cd34"}
```

**Events** (global broadcast)
- `display.lease` `{"payload":{"profile_key":"ab12cd34","lease":{"holder":"human","viewer_hash":"3f9a0c1d2e4b","since":1767225700.5,"epoch":8,"reason":"user takeover"}}}` — also for transitions by other processes (0.5 s lease-file watcher, `methods_display_watch.py`). `viewer_hash` = first 12 hex of sha256(viewer_id).
- `display.status` `{"payload": DisplayStatus}` on start/stop/crash detected by the watcher.
- `display.install.log` `{"payload":{"profile_key":"ab12cd34","line":"Setting up xfce4 ..."}}`; `display.install.done` `{"payload":{"profile_key":"ab12cd34","code":0,"status":DisplayStatus}}`.

**Notes**: Browser-capable (noVNC needs a secure context for `crypto.subtle`). "Needs you"/badge: lease holder `human` shows on the bot row.

---

#### Images (generate / attach)

- WS legacy `image.generate` `{prompt?, aspect_ratio?: "landscape"|"square"|"portrait", probe?:bool, max_bytes? (default 8 000 000, max 16 000 000)}` → `{"available":bool,"success"?:bool,"image"?:str,"image_data"?:"data:...","error"?}`; `probe:true` = availability only; 4071 prompt required, 5071 failure. UI: bot avatar picker / group chat (`plugins/hermes-bots/avatar-*.ts(x)`, `group-chat-parts.tsx`).
- WS legacy `image.attach` `{session_id, path, profile?}` → `AttachedImageResult {attached, name?, width?, height?, token_estimate?, path?, count?, bytes?, message?}`.
- WS canonical `image.attach_bytes` `{session_id, content_base64? | data?, filename?, ext?, profile?}` → same + `mime`.
- WS legacy `image.detach` `{session_id, path, profile?}` → `{"detached":bool,"count":int}`.
- UI: composer (`app/session/hooks/use-prompt-actions/index.ts`, `app/chat/hooks/use-composer-actions.ts`).


---

#### Insights, usage and analytics

**UI**
- Command Center → **Usage** tab: `apps/desktop/src/app/command-center/index.tsx` (`refreshUsage`, periods `USAGE_PERIODS = [7, 30, 90]`). It refetches when the tab opens, when the period changes, and on the refresh hotkey.
- The same REST call feeds the call-count badges on the Toolsets tab (`app/capabilities/toolsets/tool-calls.ts`, `days=365`) and the MCP tab (`app/capabilities/mcp/mcp-status.ts`, `days=30`).
- Live per-session usage (status bar context meter and cost) comes from the `session.usage` event and RPC. The sessions and chat slices cover it; it is summarized here.
- Wrapper: `apps/desktop/src/api/models.ts::getUsageAnalytics(days, profile)`.

**Calls**

| Purpose | Call | Transport | Params | Result |
|---|---|---|---|---|
| Usage dashboard | `GET /api/analytics/usage` | REST | `days` int 1-365 (default 30; the UI offers 7/30/90), `profile?` | `{daily[], by_model[], by_task[], totals, period_days, skills:{summary, top_skills[]}, tools[]}` |
| Per-model cards | `GET /api/analytics/models` | REST | `days` 1-365, `profile?` | `{models:[{model, provider, input_tokens, output_tokens, cache_read_tokens, reasoning_tokens, estimated_cost, actual_cost, sessions, api_calls, tool_calls, last_used_at, avg_tokens_per_session, capabilities:{supports_tools, supports_vision, supports_reasoning, context_window, max_output_tokens, model_family}}], totals:{…, distinct_models}, period_days}` |
| TUI `/insights` counts | `insights.get` | WS legacy | `days?: number` (30), `profile?` | `InsightsGetResult {days, sessions, messages}` (rows with `started_at >= now - days`, at most 500). The Desktop does not use it. |
| Live session usage | `session.usage` | WS legacy | `session_id` (required), `profile?` | `SessionUsageResult {model, input, output, reasoning, prompt, completion, total, calls, compressions, context_used, context_max, context_percent, context_source, context_estimated, cache_hit_pct, cache_read, cache_write, avg_latency_s, avg_tps, active_subagents, cost_usd, cost_status, plan_gauge?, credits_lines?, …}` |
| Raw session counters | `session.stats` | WS canonical | `session_id` | `SessionStats` |
| Two-bar billing view (TUI) | `usage.bars` | WS legacy | `profile?` | `UsageModel {available, status, plan_name, renews_at, plan_bar?: UsageBar, topup_bar?: UsageBar, …}`. The Desktop does not call it. |

- Error: a corrupt `state.db` answers with a status error through `corrupt_store_as_status`. Values outside `days` 1-365 get a 422 from FastAPI.
- `daily[].day` is the local calendar day (`date(started_at,'unixepoch','localtime')`).
- `by_model` merges in auxiliary-model usage (vision, compression…) from `session_model_usage`.
- `by_task[]` = `{task, input_tokens, output_tokens, estimated_cost, api_calls, models[]}`.

Example `GET /api/analytics/usage?days=7`:
```json
{"daily":[{"day":"2026-10-25","input_tokens":182340,"output_tokens":9120,"cache_read_tokens":120000,"reasoning_tokens":2100,"estimated_cost":0.84,"actual_cost":0,"sessions":6,"api_calls":41}],
 "by_model":[{"model":"claude-sonnet-4-5","input_tokens":170000,"output_tokens":8800,"estimated_cost":0.8,"sessions":6,"api_calls":40}],
 "by_task":[{"task":"compression","input_tokens":12340,"output_tokens":320,"estimated_cost":0.04,"api_calls":1,"models":["gemini-2.5-flash"]}],
 "totals":{"total_input":182340,"total_output":9120,"total_cache_read":120000,"total_reasoning":2100,"total_estimated_cost":0.84,"total_actual_cost":0,"total_sessions":6,"total_api_calls":41},
 "period_days":7,
 "skills":{"summary":{"distinct_skills_used":2,"total_skill_actions":5,"total_skill_edits":1,"total_skill_loads":4},"top_skills":[{"skill":"pdf","view_count":3,"manage_count":0,"total_count":3,"percentage":60,"last_used_at":1761400000}]},
 "tools":[{"tool":"terminal","count":22,"percentage":53.6}]}
```

**Events**: `session.usage` (`SessionUsagePayload`: read `usage`; the top-level counters are deprecated) mid-turn, then final usage on `message.complete`. Example frame: `{"jsonrpc":"2.0","method":"event","params":{"type":"session.usage","session_id":"rt-1","payload":{"usage":{"input":5200,"output":310,"total":5510,"calls":2,"context_used":18000,"context_max":200000,"context_percent":9}}}}`.

**Notes**: browser-capable (plain REST). No restart. The Desktop sends `?profile=` through `capabilityScoped`.

---

#### Shared metrics (telemetry consent and counters)

**UI**: `app/settings/shared-metrics-settings.tsx` (toggle). Stores: `store/shared-metrics.ts` (consent), `store/desktop-metrics.ts` (fire-and-forget counters, gated by the consent last read), `app/gateway/report-startup-latency.ts`.

**Calls**

| Call | Transport | Params | Result |
|---|---|---|---|
| `GET /api/shared-metrics/consent` | REST | `profile?` | `{enabled, send, decided, managed}` (a managed install can't save) |
| `PUT /api/shared-metrics/consent` | REST | body `{enabled: bool (req), send?: bool=false}`, `profile?` | same as GET |
| `shared_metrics.status` | WS legacy | `profile?` | `SharedMetricsConsentResult {enabled, send, decided, reask?}` |
| `shared_metrics.set` | WS legacy | `{enabled: bool (req), send?: bool, first_run?: bool, profile?}` (send requires enabled) | `SharedMetricsConsentResult` |
| `shared_metrics.slash_command` | WS legacy | `{command: string (req), session_id?, profile?}` | `{ok}` |
| `shared_metrics.startup_latency` | WS legacy | `{elapsed_ms: number (req), surface?: 'desktop_attach'\|'tui', launch_id?, profile?}` | `{ok}` |
| `shared_metrics.update_run` | WS legacy | `{outcome: string (req), failed_stage?, mechanism?, duration_ms?, from_commit_date?, profile?}` | `{ok}` |
| `shared_metrics.desktop_feature_use` | WS legacy | `{area: string (req)}` (once per area per UTC day) | `{ok}` |
| `shared_metrics.desktop_friction` | WS legacy | `{kind, detail}` (both req) | `{ok}` |
| `shared_metrics.desktop_onboarding` | WS legacy | `{step, event}` (both req) | `{ok}` |
| `shared_metrics.desktop_dislike` | WS legacy | `{signal (req), target?, setting?}` | `{ok}` |
| `shared_metrics.desktop_daily` | WS legacy | `{day: 'YYYY-MM-DD' (req), bot_count?, modes?: [{mode:'bots'\|'sessions', active_ms?, messages_sent?}], actions?: [{action, via:'click'\|'menu'\|'palette'\|'shortcut', count}]}` | `{recorded: bool}` (false = keep the day and retry) |

**Notes**: all counters are no-ops unless consent is on. They are optional for a native client. An older backend has no `shared_metrics.*` methods (-32601); keep the control disabled in that case.

---

#### Gateway status, health and logs (Command Center → System)

**UI**: `app/command-center/index.tsx` (`section === 'system'`: status card, Restart/Update buttons, log viewer with `LOG_FILES = ['agent','errors','gateway','desktop']` and `LOG_LEVELS = ['ALL','INFO','WARNING','ERROR']`, 200 lines). Command palette entry "Restart gateway" (`app/command-palette/index.tsx` ~986). Wrappers: `api/config.ts::getStatus/getLogs`, `api/system.ts`.

**Calls**

| Call | Transport | Params | Result |
|---|---|---|---|
| `GET /api/status` | REST (public, no auth) | `profile?` (named profile view; it is not merged into the machine rollup) | see example |
| `GET /api/health` | REST (public) | none | `{ok, version, displayVersion, commit, auth_required}`. `commit` = code the process booted from. Desktop refuses to attach on a mismatch. |
| `GET /api/system/stats` | REST | none | `{system, release, version, platform…, arch, hostname, python_version, python_impl, hermes_version, cpu_count, memory?{total,available,used,percent}, disk?{total,used,free,percent}, cpu_percent?, load_avg?, uptime_seconds?, process?{pid,rss,create_time,num_threads}, psutil: bool}` |
| `GET /api/logs` | REST | `file` ∈ `agent\|errors\|gateway\|desktop\|…` (`hermes_cli.logs.LOG_FILES`, 400 if unknown), `lines` (≤500), `level?` (min level; `ALL`/omitted = none), `component?` (prefix group, 400 if unknown), `search?` (case-insensitive substring), `profile?` | `{file, lines: string[]}` (`[]` when the log file is missing) |
| `GET /api/host/identity` | REST (token) | none | `{ok, protocolVersion:1, pid, role, servesSpa}`. Used for host rendezvous; not needed by a client. |
| `GET /api/health/idle`, `POST /api/health/retirement {action:'prepare'\|'commit'\|'cancel', token?}` | REST (token) | | Backend-retirement handshake for Electron (`app/retirement-view.tsx`). Not needed natively. |

Example `GET /api/status` (loopback/insecure bind; gated binds omit `hermes_home`, `config_path`, `env_path`, `gateway_pid`, `gateway_health_url`, `gateways`):
```json
{"version":"0.42.0","release_date":"2026-10-20","config_version":7,"latest_config_version":7,"can_update_hermes":true,
 "gateway_running":true,"gateway_state":"running","gateway_platforms":{"telegram":{"state":"connected","updated_at":"2026-10-26T10:00:00Z"}},
 "gateway_exit_reason":null,"gateway_updated_at":"2026-10-26T10:00:05Z","gateway_heartbeat_stale_s":null,"gateway_shared_with":null,
 "active_agents":1,"gateway_busy":true,"gateway_drainable":true,"restart_drain_timeout":60.0,"active_sessions":3,
 "auth_required":false,"auth_providers":[],"auth_flows":[],"nous_session_valid":"unknown","install_id":"c0ffee…",
 "shared_profile_warning":false,
 "components":{"gateway":{"status":"ok","state":"running"},"dashboard":{"status":"ok"},"storage":{"status":"ok"},"platforms":{"status":"ok","configured":1,"connected":1}},
 "overall":"ok","memory":{"pressure":"ok","gateway_rss_mb":410,"system_total_mb":32000,"system_available_mb":18000},"disk":{"pressure":"ok","total_mb":950000,"free_mb":400000,"used_percent":58},
 "profiles":["default","coder"],"parked_profiles":[],"gateway_mode":"multiplex","multiplex_standalone_reason":null,
 "hermes_home":"C:\\Users\\me\\.hermes","config_path":"…\\config.yaml","env_path":"…\\.env","gateway_pid":12345,"gateway_health_url":"http://127.0.0.1:8642/health","gateways":[]}
```
- `gateway_state`: `running`, `draining`, `stopped`, … (unverified full enum). `components.*.status`: `ok` or `degraded`. `storage.reason: "corrupt"` when state.db is corrupt. Healthy platform states: `connected`, `running`, `ok`. `disabled` platforms are skipped.
- `gateway_shared_with` is non-null when the profile is served by the shared multiplexer. The Desktop then asks before restarting (`confirmSharedGatewayRestart`), because a restart restarts every bot on the device.

**Events**: `platforms.changed` (`ChangeSignalPayload`, empty): gateway_state.json moved, so refetch status / messaging platforms.

**Notes**: browser-capable. `/api/status` is in `PUBLIC_API_PATHS`. There is no WS equivalent for status, so poll it (the Desktop polls only while the panel is open).

---

#### Gateway restart, start/stop, drain, update (background actions)

**UI**: Command Center System buttons (`runSystemAction('restart'|'update')`). The restart banner on Messaging (`app/messaging/index.tsx::restartGatewayNow`). The Updates overlay and pill (`app/updates-overlay.tsx`, `store/updates.ts`): backend update check, apply and poll (`BACKEND_ACTION_POLL_MS = 1500`). Electron app self-update runs through `window.hermesDesktop.updates` (native only). Action rows appear in the status rail through `store/activity.ts::upsertDesktopActionTask`.

**Calls** (all REST; each long command spawns a detached `hermes …` subprocess and is then tailed):

| Call | Params | Result |
|---|---|---|
| `POST /api/gateway/restart` | `profile?` | `{ok, pid, name:"gateway-restart"}`. Runs `hermes gateway restart --interrupt`: a forced restart that overrides a drain and interrupts live turns. |
| `POST /api/gateway/start` | `profile?` | `{ok, pid, name:"gateway-start"}`; 409 `detail` when the profile is multiplexed (served by the shared gateway) |
| `POST /api/gateway/stop` | `profile?`, `interrupt?: bool=false` | `{ok, pid, name:"gateway-stop"}`; 409 when multiplexed, or when live sessions exist and `interrupt=false` |
| `POST /api/gateway/drain` | body `{action:'drain'\|'cancel' = 'drain', suppress_notification?: bool}` | drain → `{ok, action:"drain", requested_at, draining, suppress_notification}`; cancel → `{ok, action:"cancel", was_draining}`. Writes `.drain_request.json`, which the gateway's watcher turns into `gateway_state: draining` within about 1 s. |
| `GET /api/gateway/migrate/plan` | none | migration plan dict (fold per-profile gateways into one multiplexer) |
| `POST /api/gateway/migrate` | none | `{ok, pid, name:"gateway-migrate"}` |
| `POST /api/hermes/update` | none | `{ok:true, pid, name:"hermes-update", action_id, already_running?}`, or refused: `{ok:false, pid:null, name:"hermes-update", error:'commit-build'\|'dashboard_update_managed_externally'\|…, message, update_command}` |
| `GET /api/hermes/update/check` | `force?: bool`, `profile?` | `{install_method:'git'\|'apt'\|'docker'\|'nix'\|'nixos'\|'desktop-app'\|'managed-runtime'\|'unknown', current_version, behind: int\|null (−1 unknown), update_available, can_apply (git only), update_command, message, channel?, branch?, source_version?, rollback_to?, held?, commits?:[{sha(7), summary, author, at(s)}]}` |
| `GET /api/hermes/update/receipt` | none | `{receipt, summary}`; 404 when no update has run since receipts landed |
| `GET /api/actions/{name}/status` | `lines` 1-2000 (default 200) | `{name, running, exit_code: int\|null, pid, lines: string[], action_id?, receipt?: UpdateReceiptSummary}`; 404 for an unknown name |

Action names (`web_server_gateway._ACTION_LOG_FILES`): `gateway-restart`, `gateway-start`, `gateway-stop`, `gateway-migrate`, `hermes-update`, `doctor`, `security-audit`, `backup`, `import`, `checkpoints-prune`, `skills-install`, `skills-uninstall`, `skills-update`, `curator-run`, `prompt-size`, `dump`, `config-migrate`, `tools-post-setup`.

Poll pattern (Desktop): start, then every 1.2 s `GET /api/actions/<name>/status?lines=180`, up to 18 times, until `running:false`. Success = `exit_code === 0`. For `hermes-update`, read `receipt.outcome` (`running|success|partial|failed|refused`) and do not infer success from backend liveness. The update restarts the backend, so expect the socket to drop and reconnect (`gateway.ready.replay_epoch` changes).

Example action status:
```json
{"name":"gateway-restart","running":false,"exit_code":0,"pid":4242,"lines":["Stopping gateway…","Gateway started (pid 4300)"]}
```
`UpdateReceiptSummary`: `{outcome, started_at, finished_at, pre_sha, post_sha, post_version, fleet_states[], followups:[{step, reason}], user_action:{step, reason}|null, action_id}`.

**Auto-restart after a save**: `web_server_gateway._restart_gateway_after()` is called by Telegram onboarding apply, WhatsApp onboarding apply and `POST /api/webhooks/enable`. Their responses carry `{restart_started: bool, restart_action?:"gateway-restart", restart_pid?, restart_error?, restart_busy?: true}`. On `restart_started:false`, show the manual restart banner. `restart_busy` means live sessions refused the restart.

**Notes**: browser-capable REST. Start, stop, drain and migrate are used only by the web dashboard and NAS flows, not by the Desktop renderer.

---

#### Maintenance and diagnostics

**UI**: Command Center → Maintenance (`app/command-center/maintenance.tsx`: Doctor, Security audit, Backup, Debug share, Curator, Memory reset). Send-diagnostics dialog (`components/send-diagnostics-dialog.tsx`, `store/send-diagnostics.ts`), reached from the error boundary and from an assistant error message.

**Calls**

| Call | Transport | Params | Result |
|---|---|---|---|
| `POST /api/ops/doctor` | REST | `profile?` | `{ok, pid, name:"doctor"}`, then tail `/api/actions/doctor/status` |
| `POST /api/ops/security-audit` | REST | `profile?` | `{ok, pid, name:"security-audit"}` |
| `POST /api/ops/backup` | REST | body `{output?: string}`, `profile?` | `{ok, pid, name:"backup", archive?}` (default `<home>/backups/hermes-backup-<stamp>-<hex>.zip`) |
| `GET /api/ops/backup/download` | REST | `archive` (abs path inside `<home>/backups`), `profile?` | `application/zip` attachment; 403 outside the dir, 404 missing |
| `POST /api/ops/import` | REST | body `{archive: string (req), force?: bool}`, `profile?` (destructive-profile guard) | `{ok, pid, name:"import"}`; 404 when the archive is missing |
| `POST /api/ops/import-upload` | REST multipart | `file` (zip), `force` form bool, `profile?` | `{ok, pid, name:"import", …}` (unverified extra keys); rejects a non-zip file |
| `POST /api/ops/prompt-size` / `dump` / `config-migrate` | REST | `profile?` | `{ok, pid, name}` |
| `POST /api/ops/debug-share` | REST (synchronous, up to 120 s) | body `{redact?: bool=true, lines?: int=200 (1-5000)}`, `profile?` | `{ok, urls:{label:url}, failures:{label:err}, redacted, auto_delete_seconds}`; 502 when the upload fails |
| `GET /api/ops/hooks` | REST | `profile?` | `{hooks:[{event, matcher, command, timeout, allowed, approved_at, executable}], valid_events[]}` |
| `POST /api/ops/hooks` | REST | `{event, command, matcher?, timeout?, approve?: bool=true}` | `{ok, event, command, approved}` |
| `DELETE /api/ops/hooks` | REST | body `{event, command}` | `{ok}` |
| `GET /api/ops/checkpoints` | REST | `profile?` | `{sessions:[{session, files, bytes}], total_bytes}` |
| `POST /api/ops/checkpoints/prune` | REST | `profile?` | `{ok, pid, name:"checkpoints-prune"}` (unverified shape) |
| `diagnostics.share_nous` | WS legacy | `{error_context?: string (≤8000, redacted), extra_files?: {label: text} (≤4 files, 512 KB each), log_lines?: 10-2000 (200)}` | `{ok, view_url?, upload_id?, expires_at?, error?}`. Redaction is forced; the client cannot turn it off. Show a privacy notice first. |

Curator, memory, credential pool and webhooks routes are listed in `50-automation-knowledge.md` and `90-rest-catalog.md`.

---

#### Host and connection RPCs (system.*, machine.*, gateway.*, client.*)

| Method | Transport | Params | Result | Used by |
|---|---|---|---|---|
| `client.capabilities` | WS legacy | `{server_requests?: bool, desktop_local?: bool}` | `{server_requests: string[] (all request methods), declines_not_shown: true}` | **Required** to receive server→client requests (approval, clarify, sudo, secret, preview.*, terminal.read…). Without `server_requests:true` the gateway fails every such request fast, so no needs-you card ever appears. |
| `gateway.ping` | WS (answered on the reader thread) | `{}` | `{ok:true}` | Keepalive. Start it only if `gateway.ready.heartbeat === true`. Interval 15 s, deadline 45 s (`apps/shared/src/json-rpc-channel.ts`). |
| `ping` | WS legacy | `{}` | `{pong:true}` | Cheap liveness after sleep/wake |
| `gateway.capabilities` | WS legacy | `{}` | `{per_session_exclusive_submit: bool}` | What this build enforces |
| `runtime.describe` | WS canonical | `{}` | `{instance_id, profile_id, authority_epoch, capabilities[], session_create, skin?}` | Canonical-authority capability probe (see `00-connection.md`) |
| `system.battery` | WS legacy | `{profile?}` | `{available, percent?, plugged?, category?: 'good'\|'warn'\|'bad'\|'critical'\|'dim'}` (always resolves) | TUI status bar; the Desktop does not use it |
| `machine.facts` | WS legacy (unscoped) | `{}` | `{machine:{os_family, os_release, native_arch, cpu_model, ram_gb, gpu_class, vendor, wsl, container}, machine_kind, has_nvidia_gpu, nvidia_platform?: 'rtx_spark'\|'dgx_spark'\|'discrete'\|null, locale?}` | First-run onboarding branching |
| `setup.status` / `setup.runtime_check` | WS legacy | `{profile?}` / `{profile?, provider?}` | `SetupStatusResult` / `SetupRuntimeCheckResult {ok, provider, model, source, error, free_tier_route}` | Readiness gate (`lib/runtime-readiness.ts`). Covered by the settings slice. |
| `reload.env` | WS legacy | `{}` | `{updated: int}` | Re-read `~/.hermes/.env` (CLI `/reload`) |

**Events**: `gateway.ready` is the first frame on every socket: `{"type":"gateway.ready","payload":{"skin":{…},"change_events":true,"heartbeat":true,"replay_epoch":"e-…","session_authority":true}}`. A new `replay_epoch` means the backend restarted, so reset per-session seq watermarks.

---

#### Notifications (in-app toasts and OS notifications)

**UI / stores**
- In-app toast feed: `store/notifications.ts` (`notify({id?, kind:'error'|'warning'|'info'|'success', title?, message, detail?, meta?, icon?, accentColor?, action?, secondaryAction?, durationMs?, placement?, persistent?})`). Default duration: error/warning 0 (sticky), info/success 5 s. The same `id` replaces the earlier toast.
- Agent notices → toasts: `store/agent-notices.ts` (`showAgentNotice`, `clearAgentNotice`, `noticeToToast`, `noticeAccent`).
- OS notifications: `store/native-notifications.ts` (`dispatchNativeNotification`), `store/notify-baseline.ts`. Settings: `app/settings/notifications-settings.tsx` (master toggle, a toggle per kind, test button). Prefs live in localStorage `hermes:native-notifications`.
- Electron: `electron/notification-ipc.ts`. A click focuses the window and sends `hermes:focus-session` with `focusSessionId`. Action buttons send `hermes:notification-action {sessionId, actionId}`, which feeds `respondToApprovalAction`. Browser bridge (`lib/browser-desktop-bridge.ts::notify`): plain `new Notification(title,{body})` after `Notification.requestPermission()`, with no actions and no click routing.

**Backend events that drive notices**

| Event | Payload | Emitted by | UI handling |
|---|---|---|---|
| `notification.show` | `NotificationShowPayload {text, level: info\|warn\|error\|success, kind: 'sticky'\|'ttl'\|'agent', ttl_ms?, key?, id?, detail?}` | credits tracker (`agent_callbacks._agent_notice_update`), model switch (`model_switch.dropped`, `model.large_notice`), slow agent build (`session_registry`, key cleared later), compression (`session_compression`), low-disk prune advisor (`sessions.auto_prune`) | Toast keyed by `key` (sticky → duration 0; ttl → `ttl_ms`). Leading glyph `•⚠✕✓` stripped. `credits.window_limit` gets no toast (the error card says it). Keys `credits.depleted`, `credits.window_limit`, `credits.restored` also fire an OS notification of kind `credits` (global). `credits.*` invalidates billing state. |
| `notification.clear` | `{key}` | same modules | Remove the toast with that key |
| `status.update` | `StatusUpdatePayload {kind, text}`; kinds: `status`, `lifecycle`, `compacting`/`compressing`, `compacted`/`ready`, `process`, `goal`, `loop`, `heartbeat`, `fallback`, … | `server._status_update`, `prompt_turn`, `session_notifications` (loop/heartbeat/kanban/process), `session_auto_continue` | Status line. `compacting` → compaction spinner. `process` → refetch background processes. `goal` → goal banner. A fallback-switch text adds a system row. |
| `notice` | `{message}` | capability refresh | One-line info |
| `error` | `{message}` | session-level failure | Error toast plus a failed assistant message; OS notification of kind `turnError` |
| `review.summary` | `{text}` | background self-review of the turn | System row `review:<text>` |
| `background.complete` / `btw.complete` | `SideAgentCompletePayload {text, task_id?, question?}` | `/background`, `/btw` side agents | System row |
| `tip.show` | `{selector, text, title?, side?}` | `tip` tool | Accent bubble on a DOM selector; active session only |

Example: `{"jsonrpc":"2.0","method":"event","params":{"type":"notification.show","session_id":"rt-1","payload":{"text":"⚠ You've used $45.00 of your $50.00 cap · $5.00 left","level":"warn","kind":"sticky","ttl_ms":null,"key":"credits.usage","id":"credits.usage"}}}`

**OS notification kinds and triggers** (`NativeNotificationKind`):

| Kind | Trigger (renderer) | Fires when |
|---|---|---|
| `approval` | `approval` server request (not replayed). Actions `approve:<request_id>` / `reject:<request_id>` → `approval.respond` choice `once`/`deny`. | Window backgrounded, or the request belongs to a session other than the active one |
| `input` | server requests `clarify`, `sudo`, `secret`, `vault.code`, `vault.save_login`, `vault.unlock_prompt`, `theme.pick`, `setup_beat`, `display.install.sudo`; event `connection.request` | same as approval |
| `turnDone` | `message.complete` handled (`use-message-stream/index.ts` ~1141) | Backgrounded **and** it is the active session |
| `turnError` | `error` event | same as turnDone |
| `backgroundDone` | A background process moves out of `running` (`store/composer-status.ts`; title "failed" when state=failed, body = title + exit code + last 3 output lines) | same as turnDone |
| `credits` | `notification.show` with a credits key | Backgrounded (global) |
| `plugin` | `ctx.os.notify` from plugins | Backgrounded (global) |

Guards: master toggle and per-kind toggle; a 4 s quiet window after every socket open (replayed state does not notify); a 1 s throttle per `kind:sessionId`; sound muted when app sounds are muted. Approval/input titles name the session (title → preview → `#id6`).

**Notes**: an OS notification is a client decision. The backend only emits events and requests. A native app can reproduce this rule set exactly.

---

#### "Needs you" badges, status dots and unread

**UI**: `store/session-dot-state.ts` (`$sessionDotStateById`, a single priority resolver), `app/chat/session-status-dot.tsx`, sidebar filter (`app/chat/sidebar/filter-menu.tsx`), profile switcher counts (`profile-switcher.tsx: needsInputCount`), browser tab title (`store/browser-tab-title.ts`: `(N) ⚠ Title · Hermes`, where N = unread count, ⚠ = something blocked, ● = a turn running). Bots: `plugins/hermes-bots/bot-row.tsx` (attention badge plus the group "needs your input" `?` icon), `plugins/hermes-bots/data.ts::$botAttention`.

**Dot states** (`SessionDotState`). Priority, weakest first: `draft` < `unread` < `background` < `working` (→`stalled`) < `needs-input`. Sort buckets: needs-input 0, working 1, unread 2, draft 3, idle 4.

| State | Source of truth |
|---|---|
| `needs-input` (amber) | `state.needsInput = true` when any blocking server request parks for the session: `clarify`, `approval`, `sudo`, `secret`, `vault.*`, `theme.pick`, `setup_beat`; also event `connection.request` (session-owned). Cleared on answer, on `approval.settled` / `clarify.settled {prompt_id}`, on `request.cancel {id}`, on `connection.update {settled:true}`, on `error`, and when the turn ends. After a reconnect: `session.active_list` rows with `status:"waiting"`. The backend sets `waiting` when `server_requests.pending_kind(sid)` is non-empty (`tui_gateway/session_registry._session_live_status`). |
| `working` / `stalled` | `state.busy` from the turn stream (`message.start` … `message.complete`), or `session.active_list` `status ∈ {working, waiting}`. The renderer only arms its watchdog clock for `starting`; `streaming` and `resuming` are not mapped. `stalled` means no event for `LIVE_TURN_EVENT_SILENCE_MS = 45000` while still busy. |
| `background` | A background process is running (`$backgroundRunningSessionIds`), or async delegation subagents are still running after the parent turn ended (`subagent.*` events). |
| `unread` (emerald) | (a) runtime: a busy→idle edge while the user was not viewing the session (`$unreadFinishedSessionIds`, persisted per profile in localStorage as message_count watermarks plus explicit markers, `store/session-unread.ts`); (b) backend watermark: session row `unread: true` from the session list. Mark read/unread with `PATCH /api/sessions/{id}?profile=` body `{unread: bool}` (fenced REST write, see `10-sessions.md`). Opening a session clears both, for every lineage alias. |
| `draft` | Local session with no messages and no stored row / `message_count === 0` |

Live snapshot used to rebuild state (`app/contrib/hooks/use-background-sync.ts`):
- `session.active_list` (WS; the authority serves it first, then the legacy fallback). Params `{profile?, current_session_id?}`.
- Polled every 1.5 s, or every 30 s as a backstop when `gateway.ready.change_events` is true. It also re-pulls on every `sessions.changed` tick.

```json
{"sessions":[{"id":"rt-1","session_key":"20261026_101500_ab12","title":"Fix login","preview":"the login button…","model":"claude-sonnet-4-5","message_count":14,"started_at":1761470100,"last_active":1761471234,"current":true,"source":"desktop","status":"waiting"}]}
```
`LiveSessionStatus = 'idle'|'starting'|'waiting'|'working'|'streaming'|'resuming'`. A session missing from the snapshot that had been busy has ended, so reap it and light unread.

Bot "needs attention" badge (client-only, not persisted): after a failed bot turn or delivery, the error text or reason code is classified as `provider_auth_or_access` (401/403/invalid key), `provider_quota_limit` (402/quota/billing), `missing_config` (no provider/API key), or `agent_blocked`. Transient errors (429, 5xx, timeout) never badge. The bot's next good turn clears it. Group rows show "needs your input" when a member session has a pending clarify.

**Change-signal broadcasts** (payload `{}`; refetch the named list): `sessions.changed`, `projects.changed`, `cron.changed`, `skills.changed`, `pairing.changed`, `platforms.changed`, `bot_relay.outbox.pending`. They are sent only when `gateway.ready.change_events: true`.

---

#### Wake word ("Hey Hermes") and wake indicator

**UI**
- Composer ear button: `store/wake-word.ts` (`armWakeWord` on gateway-ready, `toggleWakeWord`), `lib/wake-client-capture.ts` (client mic capture).
- `app/chat/composer/hooks/use-composer-voice.ts` pauses and resumes the wake listener around voice capture.
- Detection handling: `app/contrib/wiring.tsx` ~828.
- Wake indicator: a separate transparent Electron window, `app/wake-indicator/wake-indicator-app.tsx`. State `'hidden'|'detected'|'capturing'` comes from `window.hermesDesktop.wakeIndicator.{getState,onState,setState}` (`lib/wake-indicator.ts`). It is native-only, with no backend calls. A native app draws its own overlay.
- Slash `/wake` (`use-prompt-actions/slash.ts` ~1088).

**Calls** (all WS legacy; one process-global detector, owned by the first transport that arms it):

| Method | Params | Result |
|---|---|---|
| `wake.status` | `{surface?: 'gui'\|'tui'\|'auto', client_capture?: bool, profile?}` | `{listening, owned_by_caller, owner_surface?, phrase, provider, configured_surface, input_device{selector,name,error,…}, available, hint, enabled, audio_silent, capture: 'local'\|'client'\|…, local_input_available, sample_rate, frame_length}` |
| `wake.start` | `{surface?: 'gui', persist?: bool (true only on an explicit user click; writes wake_word.enabled), client_capture?: bool, session_id?, profile?}` (client timeout 180 s, because the first use lazy-installs onnxruntime) | `{started, reason?: 'disabled'\|'disabled_for_surface'\|'not_owner'\|'owned'\|'unavailable'\|…, hint?, phrase?, provider?, owner_surface?, enabled_persisted?, capture?, sample_rate?, frame_length?}` |
| `wake.stop` | `{persist?: bool, profile?}` | `{stopped, disabled_persisted, reason?}` (`not_owner` still means "not listening for us") |
| `wake.pause` / `wake.resume` | `{profile?}` | `{paused\|resumed: bool, reason?}`. Releases or reclaims the mic, e.g. while the app's own voice capture runs. |
| `wake.feed` | `{pcm: base64 int16 LE mono 16 kHz (or pcm_b64), sample_rate: 16000}`, ≤64000 bytes per call | `{fed: bool, reason?}`; error 4001 on bad or oversized PCM |

Client capture (`capture:"client"`, the case for a remote or headless backend): the client opens the mic, resamples to 16 kHz, and sends frames of `frame_length` samples (1280 = 80 ms). It batches up to 4 frames per `wake.feed` (about 3 RPC/s) and drops the oldest frames when more than 24 are queued. After repeated `fed:false` the capture is declared deaf; re-arm it.

**Events**: `wake.detected` `{phrase, profile?: string|null, start_new_session: bool}` goes only to the owning transport. The detector is already paused server-side. The client then (1) stops client PCM, (2) plays the wake sound and shows the indicator, (3) switches profile if `profile` differs from the active one, (4) opens a fresh session unless `start_new_session === false`, (5) starts voice conversation.
```json
{"jsonrpc":"2.0","method":"event","params":{"type":"wake.detected","session_id":"rt-1","payload":{"phrase":"hey hermes","profile":null,"start_new_session":true}}}
```

**Notes**: browser-capable when `capture` is client (needs getUserMedia). Local capture needs a mic on the backend host. Config: `wake_word.enabled`, `wake_word.surface`. No gateway restart.

---

#### Workspace panes, right sidebar, HUD and agent-driven UI

**UI**
- Right sidebar (`app/right-sidebar/index.tsx`): Files tree (`files/`), Review (`review/`, git), Terminal (`terminal/`). The terminal pane opens with `store.ts::setTerminalTakeover`, and `runInTerminal(cmd)` queues a command.
- Preview tabs: `app/chat/right-rail/preview-*.tsx`.
- The Files, Git review, Terminal rail and Preview sections above cover the calls of these panes.
- Pane registry for agent reveals: `store/pane-focus.ts` (`chat`→workspace, `files`→file browser, `review`→review, `sessions`→sidebar, `terminal`→terminal takeover). `files` and `review` need a project cwd.
- HUD (Electron-only): `store/hud.ts`, `app/hud/*` (`hud-shell.tsx`, `transcript-band.ts`, `click-through.ts`, `composer-drag.ts`, `resize-handle.ts`, `game-overlay.ts`). It is a transparent always-on-top window running the real `ChatView` in its `hud` variant, a Spotlight-style bar with the transcript below. Window control: `window.hermesDesktop.hud.{open, onChanged, onGoto, onCursor, onGameOverlay, beginMove, moveBy, endMove, resetLayout}`.
- Floating command palette / session switcher chrome: `app/floating-hud.ts` (pure styling).
- Overlays: `app/overlays/*` (generic overlay chrome, split layout, breadcrumb header; no backend calls).

**Calls**
- `prompt.submit` (canonical) with `surface: 'hud'` when typed in the HUD. The backend validates against `gateway/session_surface.SURFACES = {'hud','voice-live'}`, stores `session.client_surface` per submit, and prepends a per-turn surface note: read the window below and work in it. `voice-live` also takes `voice_context`.
- Server request `window.read` (`EmptyRequestParams` → `{value: JSON text}`) asks the client to enumerate the native window under the app. It is answered by Electron. The browser bridge stubs it (see `70-native-only.md`).

**Events** (from `tools/desktop_ui.py`; routed to the window owning the turn; all ignored unless it is the active session):
- `pane.reveal` `{pane: 'chat'|'files'|'terminal'|'review'|'sessions'}` (`focus_pane` tool). Example: `{"type":"pane.reveal","session_id":"rt-1","payload":{"pane":"review"}}`.
- `tip.show` `{selector, text, title?, side?}`.
- `preview.open` / `preview.close` (see Preview pane).
- `message.reaction` `{row_id, role, reactions[]}`.
- `todo.updated` (HUD/todo strip).
- `agent.terminal.output` `{process_id, chunk}` and `terminal.close` `{process_id}` (agent terminal tabs).

**Notes**
- The desktop-only tools (`focus_pane`, `tip`, `desktop_preview`, react) answer "only available in the Hermes desktop app" when no UI emitter is wired.
- A native client receives these events once it is connected through `tui_gateway` and owns the turn's session.
- HUD, wake indicator and pet overlay are Electron windows. A native app re-implements them as its own windows or overlays; there is no backend contract beyond `surface:'hud'` and `window.read`.

---

## 3. What only native Desktop can do, and what needs a gateway restart

### 3.1 Native-Desktop-only capabilities (and the native app's equivalent)

Hermes Desktop is Electron. It splits authority three ways (`apps/desktop/AGENTS.md`): **Electron owns the machine** (process lifecycle, native filesystem/git/windows, install/update), the renderer owns presentation, the gateway owns the work. The browser Webapp installs a reduced bridge (`apps/desktop/src/lib/browser-desktop-bridge.ts`, `browser-bridge-stubs.ts`) in place of Electron's preload `window.hermesDesktop` (`apps/desktop/src/global.d.ts`). Everything the stubs refuse is Electron-only because it acts on **the machine the app runs on**, not the gateway. Below: each such capability, why it is native-only, and what the Kotlin app should do.

| Capability (bridge member) | Why Electron-only | Webapp behaviour | Native app plan |
|---|---|---|---|
| Connection registry: local owner via `hermes gateway ensure --json`, SSH tunnels (`sshConfigHosts`, `sshResolveHost`), URL+token, Nous Cloud discovery/sign-in (`cloud.*`), `saveConnectionConfig`/`applyConnectionConfig`, secret-storage encryption | needs to spawn the local `hermes` CLI and SSH processes, and the OS keychain | one connection only: the serving origin (`browser-connection.ts::requireBrowserConnection`) | keep a list of `{baseUrl, auth}` connections; each is an independent client of section 1. SSH/Cloud are optional extras |
| Runtime bootstrap, install, repair, uninstall, app self-update (`getBootstrapState`, `repairBootstrap`, `uninstall`, `updates.*`, `updateHold`) | installs a Python venv and the app package on that machine | stubbed: "run `hermes update` on the server host" | Hermes runtime update is available over REST: `GET /api/hermes/update/check`, `POST /api/hermes/update` (see 2.6). The app's own update is the Play Store / MSIX |
| Gateway process ownership (`recycleBackend`, `probeLocalBackend`, `onBackendExit`) | the app may start the local gateway | n/a | use `POST /api/gateway/restart`, `/api/gateway/drain` and `/api/status`. Never start the gateway another way on Windows (buddyverse rule: Defender quarantines it) |
| Multiple native windows (`openSessionWindow`, `openWindow`, `windowRelay`, `windowControls`, `onWindowStateChanged`) | OS windows | new browser tabs via `POST /api/webapp/window-ticket` → `/webapp/window` handoff | Compose multi-window on desktop; separate activities/tasks on Android. Any number of sockets per login is fine (one ticket per dial) |
| Quick Entry global-hotkey popup (`quickEntry`), HUD overlay (`hud`, `hudModifier`), desktop pet overlay window (`petOverlay`), wake indicator overlay (`wakeIndicator`), tray / minimize-to-tray, keep-awake (`setKeepAwake`, `onKeepAwakeOffer`), translucency/glass, native title bar theme | OS-level windows, global shortcuts, power APIs | absent | Windows: Compose Desktop tray + global hotkey (JNativeHook) + always-on-top window. Android: a quick-settings tile, notification actions, an overlay only if wanted. The data behind them comes from the same RPCs (`pet.*`, `wake.*`, `prompt.submit`) |
| `screenshot`, `capturePreview`, `readWindowBelow` (answers the `window.read` server request: "enumerate the native window below the app") | OS screen capture | declined | answer `window.read` with `-32601` or a decline (`4404`), unless you implement screen capture on Windows |
| In-app browser preview `<webview>` that the agent drives (`preview.read`/`preview.act` server requests, `normalizePreviewTarget`, `watchPreviewFile`, `reachPreviewUrl`, `onPreviewNav`) | Electron `<webview>` guest with a trusted-click preload (GHSA-9f4c-93c8-jc8g) | limited: previews open as same-origin blobs or new tabs; `preview.*` requests are not answered by a webview | Android WebView / JCEF on desktop can answer `preview.read` (page text) and `preview.act` (click/type/scroll). Keep the advisory's rule: guest content never opens external apps by itself |
| Client-machine filesystem and git (`readDir`, `readFileText`, `writeTextFile`, `renamePath`, `trashPath`, `revealPath`, `openDir`, `watchDirectory`, `gitRoot`, `git.*`, `selectPaths`, `selectSavePath`) | node `fs`/`git` on the machine the app runs on (the gateway host when the connection is local) | mapped to the **server's** files: `/api/fs/list`, `/api/fs/read-text`, `/api/fs/read-data-url`, `/api/fs/download`, `/api/files/stream`, and `/api/git/*` | always use the REST routes (server files). Use platform pickers only to choose local files to upload (`/api/chat/file-upload`, `/api/chat/image-upload`) |
| Local terminal via node-pty (`terminal`, `openSessionInTerminal`) | PTY on the local machine | host terminal over the `/api/host-terminal` sibling WS (shell on the gateway host) | use `/api/host-terminal` (section 2.6) |
| Renderer plugins (`apps/desktop/src/plugins/*`, `src/sdk`, `app/contrib`; `installDesktopPlugin`, `reconcileDesktopPlugins`, `probePluginRepo`) | they are React/JS modules loaded into the renderer | bundled ones only | not portable. Re-implement the bundled ones natively (Bot Mode = `hermes-bots`, Kanban); their backend APIs (`profiles.*`, `/api/plugins/kanban/*`) are portable. Gateway-side plugins (Python, `plugins.*` RPC) work unchanged |
| OS notifications with actions (`notify`, `onNotificationAction`, `onNotificationActivate`, `onFocusSession`), deep links (`hermes://`, `onDeepLink`), power resume / battery (`onPowerResume`, `getOnBattery`) | OS integration | browser `Notification` API, no actions | Android notification channels and actions (FCM is not available from the gateway: notify from a foreground service holding the socket, or poll `/api/status` / `session.list` on resume). Windows toast notifications |
| Spellcheck dictionary, context-menu edit/copy-image, find-in-page, zoom | Chromium/Electron | browser defaults | platform defaults |
| Local models runtime toggle (`localModelsEnabled`) | the machine's GPU/runtime | follows the server | REST `/api/local-models/*` covers it server-side (section 2.3) |
| MCP OAuth and free-tier challenge windows (`mcpOauth`, `freeTierChallenge`) | an Electron auth window with its own `persist:` partition | browser redirect / new tab | open the authorize URL in Custom Tabs / the system browser. The callback lands on the gateway (`/api/mcp/oauth/callback/…`, public route), so the app only polls status |
| `desktop_local: true` capability (`client.capabilities`) | the backend offers first-run onboarding and the welcome trial only to the Desktop on its own machine | not sent | send it only when the app runs on the gateway's own PC (Windows build, base URL `127.0.0.1`) |

Everything else in the Desktop UI is renderer-over-gateway and portable through sections 1 and 2.

### 3.2 Changes that need a gateway restart (and the many that don't)

When Hermes knows a change needs a restart, it restarts the gateway itself: `hermes_cli/web_server_gateway.py::_restart_gateway_after` spawns `hermes gateway restart`. The response then carries `{"restart_started": true, "restart_action": "gateway-restart", "restart_pid": …}`. If the spawn failed it carries `restart_started: false` with `needs_restart: true`, `restart_error?` or `restart_busy: true` (live sessions refused it). Then show a "Restart gateway" banner that calls `POST /api/gateway/restart` and polls `GET /api/actions/gateway-restart/status`. After any restart the socket drops: reconnect with a new ticket. A new `gateway.ready.replay_epoch` means re-resume the open sessions. Acknowledge lost turns with `prompt.resolve_unknown`. While draining, the authority refuses state changes with `4001 runtime_draining` and only accepts answers to parked turns and queued input. Before restarting, check `GET /api/status` → `gateway_shared_with`: on a multiplexed gateway a restart restarts **every** bot on the device, so confirm with the user, as the Desktop does (`confirmSharedGatewayRestart`).

**Needs a restart**

| Change | Endpoint | Restart |
|---|---|---|
| User-commanded restart | `POST /api/gateway/restart?profile=` → `{ok, pid, name:"gateway-restart"}` (`hermes gateway restart --interrupt`: interrupts live turns, supersedes a drain) | yes |
| Start/stop a non-multiplexed profile gateway | `POST /api/gateway/start`, `POST /api/gateway/stop?interrupt=` (409 when multiplexed or busy) | n/a |
| Fold per-profile gateways into one multiplexer | `GET /api/gateway/migrate/plan`, `POST /api/gateway/migrate` (`hermes gateway migrate --multiplex --yes`, detached) | yes |
| External (NAS) drain | `POST /api/gateway/drain {"action":"drain"\|"cancel"}` (non-interactive token-auth seam) | `gateway_state` becomes `draining` within about 1 s; a restart supersedes it |
| Web Access on/off/apply (`webapp.enabled/host/port/public_url`) | `POST /api/webapp-access/start\|stop\|restart` (`hermes_cli/web_routers/webapp_access.py`) | automatic, for the gateway serving that profile |
| **Quick-tunnel hostname changed** | update `webapp.public_url` (Web Access) | **yes**: the trusted Host list is resolved once when the listener starts (`gateway/run_api.py:228`, `hermes_cli/web_server.py:958`). Until then the new hostname is refused (1.2) |
| Telegram / WhatsApp onboarding apply | `POST /api/messaging/{telegram\|whatsapp}/onboarding/{pairing_id}/apply` | automatic |
| Enable webhooks | `POST /api/webhooks/enable` | automatic. Creating, toggling or deleting a route hot-reloads |
| Messaging platform enable / credential change | messaging routes, `PUT /api/env` | yes, unless the platform status reports `hot_served: true` (the multiplexer rebuilt the adapters; re-read status after about 4 s) or the 30 s adapter rescan picked it up. Platform `state: pending_restart` tells you |
| Gateway hooks | `hermes_cli/web_routers/ops.py` hooks routes | "takes effect on the next session / gateway restart" |
| Plugin toggle/install/update with `restart_required: true` | `plugins.manage` (WS legacy) / plugin REST | only when the response says so (`gateway_reloaded` / `activation` otherwise mean live now or next chat) |
| Gateway running stale code | any `/api/model/*` answering `503 Restart required: …` | yes |
| Hermes runtime update | `POST /api/hermes/update` → poll `/api/actions/hermes-update/status`, read `receipt.outcome` | the update restarts the backend |

**No restart needed** (from the slices in section 2): profile create/rename/delete (the multiplexer's `rescan-profiles` signal, with a 30 s watcher as a safety net), bot `ui_meta`/title/pin/hide/sections/avatar, SOUL.md, skills, toolsets, MCP servers (save, then `reload.mcp`), memory providers, model/provider/reasoning defaults, personality, display settings, cron jobs, kanban, projects, pets. These follow one rule: **a running session keeps its frozen launch policy**. Config edits apply to **new sessions**, or to sessions created with `follow_profile_config: true` (every Bot Chat) at their next turn. A per-session change goes through `session.mutate` (model, reasoning, workspace) or `reload.mcp` / `skills.reload` / `tools.configure {session_id}`.

---

## 4. Live verification (2026-10-26, read-only)

Run with `node verify-hermes-api.mjs <base> %LOCALAPPDATA%\hermes\webapp-login.txt [--native]` (Node ≥ 22; masks every cookie, token and ticket). It ran against `http://127.0.0.1:9120` and against the Cloudflare quick tunnel (`--native`). It sends no chat, runs nothing, changes no setting, creates or deletes nothing, and never stops the gateway, cloudflared or Codync. Results (latencies: local / tunnel):

| Check | Result |
|---|---|
| `GET /api/health`, `/api/status`, `/api/auth/providers` without credentials | 200 / 200 / 200; `auth_required: true`; one provider `basic` (password) |
| `GET /api/profiles` without credentials | `401 {"error":"unauthenticated","detail":"Unauthorized","reason":"no_cookie","login_url":"/login"}` |
| `POST /auth/password-login` (flow A) | `200 {"ok":true,"next":"/"}` + 4 cookies. Names `hermes_session_{at,rt,provider}_651bcb348b6cffda`, `hermes_webapp_signin_651bcb348b6cffda`; over HTTPS prefixed `__Host-` and `Secure`; AT Max-Age 43200, others 2592000; `HttpOnly; Path=/; SameSite=lax` |
| Native flow B over the tunnel | `/auth/native/authorize` → 302 `/login` + PKCE cookie (600 s, `SameSite=none`); `password-login` with that cookie → `next: http://127.0.0.1:53682/callback?code=<43>&state=verify`, no session cookies; `/auth/native/token` → `{access_token, refresh_token, token_type:"Bearer", expires_at (+43199 s), provider, user_id}`; `GET /api/auth/me` with `Authorization: Bearer` → 200 |
| `GET /api/auth/me` (cookie) | `{user_id, email, display_name, org_id, provider:"basic", expires_at}` |
| `GET /api/profiles` | 2 profiles; row keys `bot_title, description, description_auto, display_name, distribution_name, distribution_source, distribution_version, gateway_running, has_alias, has_env, is_default, model, name, path, provider, skill_count` |
| `GET /api/sessions?limit=3&order=recent` | `{sessions, total, limit, offset, storage}`; session row has 70 keys (id, title, preview, profile, source, model, cwd, hidden, pinned, archived, unread, message_count, started_at, last_activity_at, runtime_generation, runtime_revision, transcript_revision, token and cost counters, …) |
| `GET /api/sessions/{id}/messages?limit=5&order=latest` (a session's history) | `{session_id, profile, messages, pagination}`; message keys `id, message_uid, role, content, reasoning, reasoning_content, reasoning_details, tool_calls, tool_call_id, tool_call_uid(s), tool_name, timestamp, token_count, finish_reason, display_kind, display_metadata, compacted, active, observed, effect_disposition, platform_message_id, absorbed_message_uids, api_content, codex_message_items, codex_reasoning_items, session_id` |
| `POST /api/webapp/socket-ticket?socket=gateway` | `{"ticket":"<43 chars>","ttl_seconds":30}` |
| WS `/api/ws?native_dial=browser` with subprotocols `hermes-gateway-v1, hermes-gateway-ticket.<t>` | open; negotiated protocol `hermes-gateway-v1`. First frame `gateway.ready` with payload keys `change_events, heartbeat, replay_epoch, session_authority, skin` (`heartbeat:true, change_events:true, session_authority:true`) |
| `client.capabilities {server_requests:true}` | the 16 server-request methods + `declines_not_shown:true` (4 / 123 ms) |
| `gateway.ping` | `{"ok":true}` (0 / 116 ms); repeated every 15 s during a 35 s listen, all answered |
| `runtime.describe` | `capabilities: durable-admission-v1, event-replay-v1, local-cli-create-v1, acp-editor-policy-v1, acp-session-mcp-v1, clarify-batch-v1` |
| canonical `session.list` | no `scope` → defaults to `live`. `{scope:"stored"}` → 0 rows on this gateway. `{title:"Bot Chat", include_hidden:true}` → 1 row |
| canonical `profiles.list {include_sessions:true}` | 2 rows; keys `canonical_session, description, display_name, has_avatar, is_default, last_session, model, name, path, provider, skill_count, ui_meta, ui_meta_revisions, worker_session`; `bot_mode_protocol: true` |
| authority `commands.catalog` | 130 commands, 112 skills (165 / 293 ms) |
| authority `model.options {}` | 3 providers; provider keys `authenticated, capabilities, featured_models, is_current, is_user_defined, models, name, slug, source, total_models` |
| canonical `config.get {key:"full"}` | `{config:{display, voice}}`. `{key:"model"}` → `4001 invalid_params` (not a canonical key) |
| canonical `session.events.since {session_id, last_seen:0}` on a non-running session | `truncated:true, snapshot_required:true, events:[]` → client must `session.resume` |
| legacy fallback `projects.list`, `mcp.servers.list`, `pet.info` | answered (`{projects, active_id}`, `{servers}`, `{enabled}`) |
| unknown method | `-32601 {"message":"unknown method: no.such.method","data":{"reason":"unknown_method"}}` |
| live event stream | `platforms.changed` (payload `{}`) arrived unprompted while listening; no server-originated pings (the heartbeat is client-driven) |

Not exercised live (they would mutate state or start a turn): `prompt.submit` and the streamed turn events, approvals/clarify answers, `session.mutate`, `session.create`, `session.resume` (it attaches a runtime viewer), cron runs, uploads, profile and settings writes, `/api/host-terminal`, the audio streams. Their shapes in section 2 come from source and the generated contract.

## 5. Known gaps and open questions

Things this contract could not pin down from source or the read-only live run. Settle them with a disposable test session before relying on them.

- **Not exercised live**: the whole turn (`prompt.submit` → events → `message.complete`), approval/clarify answers, every `session.mutate`, `session.create`/`resume`, uploads, cron runs, settings and profile writes, the host terminal and audio sockets. Their shapes come from source and the generated contract.
- **Ordering**: whether the `prompt.submit` result frame arrives before `message.start`, or the `approval.respond` reply before `approval.settled`, is not fixed in code. Handle either order.
- **Untyped extras**: some events and fields are sent but missing from the generated contract (`agent.step`, some `message.complete` keys). Decode leniently: ignore unknown event types and unknown keys (`ignoreUnknownKeys = true`). Never fail a frame on an unknown field.
- **Not served on the canonical gateway** (`-32601`): `session.close`, `session.usage`, `session.status`, `handoff.*`. Sessionless `reload.env` is refused. Legacy-only attach verbs (`pdf.attach`, `image.attach`, the `voice.*` recorder) may fail for authority-owned sessions; prefer `image.attach_bytes`, `file.attach` and the REST uploads. Probe once at connect and hide features that answer `-32601`.
- **Group rooms have no push**: the Desktop polls `groups.state` + `groups.log` every 2 s. Some room event kinds (`authority.lost`, `turn.reassigned`, `room.created`) have no emitter in this build.
- **Profile import/export** take file paths on the gateway machine, so a remote client can't move the archive through these routes. Use the gateway's files REST to fetch or stage the archive.
- **Memory**: there is no API to add a new memory entry; only the agent's memory tool writes new ones. Editing and deleting existing entries works (2.5).
- **Webhook URLs for non-default profiles**: the REST list shows no `/p/<profile>` prefix while the CLI adds one. Unverified which form the gateway accepts.
- **Preview automation in the Webapp**: whether `preview.read`/`preview.act` work against browser iframe tabs is unverified. A native client implementing its own WebView must answer them itself (3.1).
- **When a toolset, SOUL or skill edit reaches a live chat**: it applies to new sessions for sure. For a running session, only at the next agent rebuild (unverified exact point; `tools.configure {session_id}` forces one).
- **`profiles.list` on canonical**: rows for non-launch profiles lack `ui_meta_revisions`, `has_avatar` and `previous_names`. Fetch them per profile (2.4).
- **Quick tunnel**: the `trycloudflare.com` hostname changes whenever cloudflared restarts. The gateway then refuses the new Host until `webapp.public_url` is updated and the gateway restarted (3.2). A named Cloudflare tunnel (stable hostname) removes this failure mode.

---

## Appendix A. REST and WebSocket route catalog (generated)

Generated from FastAPI decorators in the checkout (368 routes). Paths are relative to the Webapp base URL. Every `/api/*` route except the public ones (section 1.3) needs the sign-in cookie or a native bearer token; routes that read profile state take `?profile=<name>`. "WS" rows are WebSocket upgrades (one-use ticket, section 1.5).

| Method | Path | Handler | Source | Summary |
|---|---|---|---|---|
| POST | `/{provider}/oauth/start` | `start_memory_oauth` | hermes_cli/memory_oauth.py:51 | Begin a provider's zero-CLI OAuth flow (browser + loopback listener); returns immediately, poll status. |
| GET | `/{provider}/oauth/status` | `memory_oauth_status` | hermes_cli/memory_oauth.py:65 | Poll a provider's OAuth flow: idle \| pending \| connected \| error. |
| GET | `/api/actions/{name}/status` | `get_action_status` | hermes_cli/web_routers/actions.py:385 | Tail an action log and report whether the process is still running. |
| GET | `/api/analytics/models` | `get_models_analytics` | hermes_cli/web_routers/analytics.py:410 | Return model analytics without blocking the serving event loop. |
| GET | `/api/analytics/usage` | `get_usage_analytics` | hermes_cli/web_routers/analytics.py:145 | ``days`` is clamped to 1-365 (idea from #74778): huge or non-positive |
| GET | `/api/audio/elevenlabs/voices` | `get_elevenlabs_voices` | hermes_cli/web_routers/audio.py:220 | Return ElevenLabs voices when an API key is configured. |
| POST | `/api/audio/speak` | `speak_text` | hermes_cli/web_routers/audio.py:295 | Synthesize speech and return audio as base64 data URL. |
| WS | `/api/audio/speak-stream` | `speak_stream_ws` | hermes_cli/web_routers/audio.py:601 | Streaming TTS for the desktop: text in, raw int16 PCM frames out. |
| POST | `/api/audio/stt-lease` | `stt_lease` | hermes_cli/web_routers/audio.py:484 | Desktop voice-input sessions as STT warm-up / release signals. |
| POST | `/api/audio/transcribe` | `transcribe_audio_upload` | hermes_cli/web_routers/audio.py:88 |  |
| WS | `/api/audio/transcribe-stream` | `transcribe_stream_ws` | hermes_cli/web_routers/audio.py:518 | Live STT for Desktop dictation: mic PCM in while the user speaks, partial + final text out. |
| POST | `/api/audio/tts-lease` | `tts_lease` | hermes_cli/web_routers/audio.py:352 | Desktop TTS-output toggles as warm-up / release signals. |
| GET | `/api/audio/voice-config` | `get_client_voice_config` | hermes_cli/web_routers/audio.py:151 | The active profile's STT/TTS config for CLIENT-DIRECT voice. |
| POST | `/api/audio/voice-live/session` | `create_voice_live_session` | hermes_cli/web_routers/audio.py:190 | Exchange the renderer's WebRTC SDP offer for a GPT-Live session answer. |
| GET | `/api/audio/voice-live/status` | `get_voice_live_status` | hermes_cli/web_routers/audio.py:180 | Which voice chat mode the profile selected (``chained`` \| ``gpt-live``) and whether GPT-Live |
| GET | `/api/auth/me` | `api_auth_me` | hermes_cli/dashboard_auth/routes.py:531 | Return the verified session as JSON. |
| GET | `/api/auth/providers` | `api_auth_providers` | hermes_cli/dashboard_auth/routes.py:211 |  |
| POST | `/api/auth/ws-ticket` | `api_auth_ws_ticket` | hermes_cli/dashboard_auth/routes.py:563 | Mint a 30s single-use ticket for a WS upgrade (browsers cannot set |
| POST | `/api/chat/file-upload` | `upload_chat_file` | hermes_cli/web_routers/uploads.py:22 | Stage one user-selected browser file under the active Hermes profile. |
| POST | `/api/chat/image-upload` | `upload_chat_image` | hermes_cli/web_routers/files.py:565 | Persist a browser clipboard image where the embedded TUI can read it. |
| GET | `/api/chat/workspaces` | `get_chat_workspaces` | hermes_cli/web_routers/chat_workspaces.py:72 | Projects + discovered repos a fresh chat may start in; ``scan=1`` rescans the |
| GET | `/api/config` | `get_config` | hermes_cli/web_routers/config_env.py:80 |  |
| PUT | `/api/config` | `update_config` | hermes_cli/web_routers/config_env.py:117 |  |
| GET | `/api/config/defaults` | `get_defaults` | hermes_cli/web_routers/config_env.py:94 |  |
| GET | `/api/config/raw` | `get_config_raw` | hermes_cli/web_routers/analytics.py:34 | Raw config.yaml text plus its resolved path. |
| PUT | `/api/config/raw` | `update_config_raw` | hermes_cli/web_routers/analytics.py:53 |  |
| GET | `/api/config/schema` | `get_schema` | hermes_cli/web_routers/config_env.py:99 | Dashboard/Desktop-readable egress proxy status and remediation text. |
| WS | `/api/console` | `console_ws` | hermes_cli/web_routers/chat_ws.py:241 |  |
| GET | `/api/credentials/pool` | `list_credential_pool` | hermes_cli/web_routers/ops.py:351 |  |
| POST | `/api/credentials/pool` | `add_credential_pool_entry` | hermes_cli/web_routers/ops.py:380 |  |
| DELETE | `/api/credentials/pool/{provider}/{index}` | `remove_credential_pool_entry` | hermes_cli/web_routers/ops.py:438 | Remove a pool entry (``index`` is 1-based, as listed). |
| GET | `/api/cron/blueprints` | `list_cron_blueprints` | hermes_cli/web_routers/cron.py:891 | Blueprint catalog (built-ins + the profile's plugin blueprints) as form schemas. |
| POST | `/api/cron/blueprints/instantiate` | `instantiate_blueprint` | hermes_cli/web_routers/cron.py:910 | Fill a blueprint's slots and create the cron job (form-submit path). |
| GET | `/api/cron/delivery-targets` | `get_cron_delivery_targets` | hermes_cli/web_routers/cron.py:721 | Delivery targets for the cron dropdown: implicit ``local`` plus the |
| POST | `/api/cron/fire` | `cron_fire_webhook` | hermes_cli/web_routers/cron.py:772 | Chronos managed-cron fire webhook (NAS -> agent) — gateway forwarder. |
| GET | `/api/cron/jobs` | `list_cron_jobs` | hermes_cli/web_routers/cron.py:701 |  |
| POST | `/api/cron/jobs` | `create_cron_job` | hermes_cli/web_routers/cron.py:716 | Delivery targets for the cron dropdown: implicit ``local`` plus the |
| DELETE | `/api/cron/jobs/{job_id}` | `delete_cron_job` | hermes_cli/web_routers/cron.py:767 | Chronos managed-cron fire webhook (NAS -> agent) — gateway forwarder. |
| GET | `/api/cron/jobs/{job_id}` | `get_cron_job` | hermes_cli/web_routers/cron.py:706 |  |
| PUT | `/api/cron/jobs/{job_id}` | `update_cron_job` | hermes_cli/web_routers/cron.py:747 |  |
| POST | `/api/cron/jobs/{job_id}/pause` | `pause_cron_job` | hermes_cli/web_routers/cron.py:752 |  |
| POST | `/api/cron/jobs/{job_id}/resume` | `resume_cron_job` | hermes_cli/web_routers/cron.py:757 |  |
| GET | `/api/cron/jobs/{job_id}/runs` | `list_cron_job_runs` | hermes_cli/web_routers/cron.py:711 | Delivery targets for the cron dropdown: implicit ``local`` plus the |
| POST | `/api/cron/jobs/{job_id}/trigger` | `trigger_cron_job` | hermes_cli/web_routers/cron.py:762 | Chronos managed-cron fire webhook (NAS -> agent) — gateway forwarder. |
| GET | `/api/curator` | `get_curator_status` | hermes_cli/web_routers/status.py:645 |  |
| PUT | `/api/curator/paused` | `set_curator_paused` | hermes_cli/web_routers/status.py:665 | Spawn a background ``hermes -p <profile> <argv>`` action; a spawn failure is |
| POST | `/api/curator/run` | `run_curator` | hermes_cli/web_routers/status.py:684 | Trigger a curator review now (backgrounded; tail via action status). The curator |
| DELETE | `/api/dashboard/agent-plugins/{name:path}` | `delete_agent_plugin` | hermes_cli/web_routers/dashboard_ui.py:266 | ``hermes plugins install`` / ``enable`` in another process asks this backend to load the plugin |
| POST | `/api/dashboard/agent-plugins/{name:path}/disable` | `post_agent_plugin_disable` | hermes_cli/web_routers/dashboard_ui.py:242 |  |
| POST | `/api/dashboard/agent-plugins/{name:path}/enable` | `post_agent_plugin_enable` | hermes_cli/web_routers/dashboard_ui.py:235 |  |
| POST | `/api/dashboard/agent-plugins/{name:path}/update` | `post_agent_plugin_update` | hermes_cli/web_routers/dashboard_ui.py:249 |  |
| POST | `/api/dashboard/agent-plugins/activate` | `post_agent_plugin_activate` | hermes_cli/web_routers/dashboard_ui.py:272 | ``hermes plugins install`` / ``enable`` in another process asks this backend to load the plugin |
| POST | `/api/dashboard/agent-plugins/install` | `post_agent_plugin_install` | hermes_cli/web_routers/dashboard_ui.py:201 |  |
| GET | `/api/dashboard/font` | `get_dashboard_font` | hermes_cli/web_routers/dashboard_ui.py:91 | Return the active font override (``"theme"`` = use the theme's font). |
| PUT | `/api/dashboard/font` | `set_dashboard_font` | hermes_cli/web_routers/dashboard_ui.py:101 | Set the font override (config.yaml). Unknown ids coerce to ``"theme"`` rather than |
| PUT | `/api/dashboard/plugin-providers` | `put_plugin_providers` | hermes_cli/web_routers/dashboard_ui.py:299 | Persist memory provider / context engine selection (writes config.yaml). |
| GET | `/api/dashboard/plugins` | `get_dashboard_plugins` | hermes_cli/web_routers/dashboard_ui.py:110 | Return discovered dashboard plugins (excludes user-hidden and non-enabled ones). |
| POST | `/api/dashboard/plugins/{name:path}/visibility` | `post_plugin_visibility` | hermes_cli/web_routers/dashboard_ui.py:327 | Toggle a plugin's sidebar visibility (persists to config.yaml dashboard.hidden_plugins). |
| GET | `/api/dashboard/plugins/catalog` | `get_plugins_catalog` | hermes_cli/web_routers/dashboard_ui.py:160 | Curated plugin catalog merged with installed state (session protected). |
| GET | `/api/dashboard/plugins/hub` | `get_plugins_hub` | hermes_cli/web_routers/dashboard_ui.py:131 | Unified agent plugins + dashboard extension metadata (session protected). |
| GET | `/api/dashboard/plugins/rescan` | `rescan_dashboard_plugins` | hermes_cli/web_routers/dashboard_ui.py:124 | Force re-scan of the selected profile's dashboard plugins (its own cache, PLG-3). |
| PUT | `/api/dashboard/theme` | `set_dashboard_theme` | hermes_cli/web_routers/dashboard_ui.py:72 | Set the active dashboard theme (persists to config.yaml). |
| GET | `/api/dashboard/themes` | `get_dashboard_themes` | hermes_cli/web_routers/dashboard_ui.py:52 | Available themes + the active one. Built-ins ship name/label/description only |
| PUT | `/api/delegation/backend` | `select_delegation_backend` | hermes_cli/web_routers/delegation.py:119 | Persist ``delegation.backend`` through core's ``set_global_backend``: only ``default`` or a backend |
| PUT | `/api/delegation/backends/model_map` | `save_model_routes` | hermes_cli/web_routers/delegation.py:215 | Replace ``delegation.backends.model_map`` (``options`` + ``default``) after validating every route. |
| PUT | `/api/delegation/backends/profile_routes` | `save_profile_routes` | hermes_cli/web_routers/delegation.py:236 | Replace ``delegation.backends.profile_routes`` (``profiles`` + ``default``); every name must be an |
| GET | `/api/delegation/routing` | `get_delegation_routing` | hermes_cli/web_routers/delegation.py:92 | The profile's delegation backend (``configured``) and, when it cannot run here, why |
| WS | `/api/display/ws` | `display_ws` | hermes_cli/web_routers/display.py:76 |  |
| GET | `/api/egress/status` | `get_egress_status` | hermes_cli/web_routers/config_env.py:109 | Dashboard/Desktop-readable egress proxy status and remediation text. |
| DELETE | `/api/env` | `remove_env_var` | hermes_cli/web_routers/config_env.py:1067 |  |
| GET | `/api/env` | `get_env_vars` | hermes_cli/web_routers/config_env.py:259 |  |
| PUT | `/api/env` | `set_env_var` | hermes_cli/web_routers/config_env.py:328 |  |
| POST | `/api/env/reveal` | `reveal_env_var` | hermes_cli/web_routers/config_env.py:1085 | Return the real (unredacted) value of a single env var. |
| WS | `/api/events` | `events_ws` | hermes_cli/web_routers/chat_ws.py:726 |  |
| DELETE | `/api/files` | `delete_managed_file` | hermes_cli/web_routers/files.py:958 |  |
| GET | `/api/files` | `list_managed_files` | hermes_cli/web_routers/files.py:613 |  |
| GET | `/api/files/download` | `download_managed_file` | hermes_cli/web_routers/files.py:806 | Stream a managed file as an attachment download. |
| POST | `/api/files/mkdir` | `create_managed_directory` | hermes_cli/web_routers/files.py:948 |  |
| GET | `/api/files/read` | `read_managed_file` | hermes_cli/web_routers/files.py:670 |  |
| GET | `/api/files/stream` | `stream_managed_file` | hermes_cli/web_routers/files.py:826 | Stream managed audio/video inline with HTTP Range support — Electron's |
| POST | `/api/files/ticket` | `issue_file_ticket` | hermes_cli/web_routers/files.py:759 | Ticket a browser download/media URL instead of putting the session token in it. |
| POST | `/api/files/upload` | `upload_managed_file` | hermes_cli/web_routers/files.py:873 |  |
| POST | `/api/files/upload-stream` | `upload_managed_file_stream` | hermes_cli/web_routers/files.py:927 | Chunked multipart upload: constant memory and no base64 inflation, unlike |
| GET | `/api/fs/default-cwd` | `fs_default_cwd` | hermes_cli/web_routers/files.py:1372 |  |
| GET | `/api/fs/download` | `fs_download` | hermes_cli/web_routers/files.py:1328 | A workspace file as an attachment: from the profile's SSH workspace when it has one, else |
| GET | `/api/fs/git-root` | `fs_git_root` | hermes_cli/web_routers/files.py:1355 |  |
| GET | `/api/fs/list` | `fs_list` | hermes_cli/web_routers/files.py:989 |  |
| GET | `/api/fs/read-data-url` | `fs_read_data_url` | hermes_cli/web_routers/files.py:1184 |  |
| GET | `/api/fs/read-text` | `fs_read_text` | hermes_cli/web_routers/files.py:1019 |  |
| POST | `/api/fs/write-text` | `fs_write_text` | hermes_cli/web_routers/files.py:1069 | Overwrite (or create) a UTF-8 text file for the in-app spot editor. |
| POST | `/api/gateway/drain` | `gateway_drain` | hermes_cli/web_routers/actions.py:187 | Begin or cancel an external (NAS-driven) gateway drain. |
| POST | `/api/gateway/migrate` | `gateway_migrate` | hermes_cli/web_routers/actions.py:177 | Run ``hermes gateway migrate --multiplex --yes`` detached; the CLI re-runs the preflight and |
| GET | `/api/gateway/migrate/plan` | `gateway_migrate_plan` | hermes_cli/web_routers/actions.py:169 | Preflight for folding per-profile gateways into one multiplexer (same JSON as the CLI plan). |
| POST | `/api/gateway/restart` | `restart_gateway` | hermes_cli/web_routers/actions.py:160 | Kick off a ``hermes gateway restart --interrupt`` in the background: the user-commanded force |
| POST | `/api/gateway/start` | `start_gateway` | hermes_cli/web_routers/ops.py:296 |  |
| POST | `/api/gateway/stop` | `stop_gateway` | hermes_cli/web_routers/ops.py:309 |  |
| GET | `/api/git/base-branches` | `git_base_branches_route` | hermes_cli/web_routers/git.py:120 |  |
| POST | `/api/git/branch/switch` | `git_branch_switch_route` | hermes_cli/web_routers/git.py:219 |  |
| GET | `/api/git/branches` | `git_branches_route` | hermes_cli/web_routers/git.py:115 |  |
| GET | `/api/git/file-diff` | `git_file_diff_route` | hermes_cli/web_routers/git.py:141 |  |
| GET | `/api/git/gh-auth` | `gh_auth_status_route` | hermes_cli/web_routers/git.py:84 | ``{"available", "authenticated"}`` for the `gh` CLI; cached 5 min |
| POST | `/api/git/review/commit` | `git_commit_route` | hermes_cli/web_routers/git.py:187 |  |
| GET | `/api/git/review/commit-context` | `git_commit_context_route` | hermes_cli/web_routers/git.py:149 |  |
| POST | `/api/git/review/create-pr` | `git_create_pr_route` | hermes_cli/web_routers/git.py:197 |  |
| GET | `/api/git/review/diff` | `git_review_diff_route` | hermes_cli/web_routers/git.py:130 |  |
| GET | `/api/git/review/list` | `git_review_list_route` | hermes_cli/web_routers/git.py:125 |  |
| POST | `/api/git/review/pr-list` | `git_pr_list_route` | hermes_cli/web_routers/git.py:167 |  |
| POST | `/api/git/review/push` | `git_push_route` | hermes_cli/web_routers/git.py:192 |  |
| GET | `/api/git/review/rev-parse` | `git_rev_parse_route` | hermes_cli/web_routers/git.py:157 |  |
| POST | `/api/git/review/revert` | `git_revert_route` | hermes_cli/web_routers/git.py:182 |  |
| GET | `/api/git/review/ship-info` | `git_ship_info_route` | hermes_cli/web_routers/git.py:162 |  |
| POST | `/api/git/review/stage` | `git_stage_route` | hermes_cli/web_routers/git.py:172 |  |
| POST | `/api/git/review/unstage` | `git_unstage_route` | hermes_cli/web_routers/git.py:177 |  |
| GET | `/api/git/status` | `git_status_route` | hermes_cli/web_routers/git.py:50 |  |
| POST | `/api/git/worktree/add` | `git_worktree_add_route` | hermes_cli/web_routers/git.py:202 |  |
| POST | `/api/git/worktree/remove` | `git_worktree_remove_route` | hermes_cli/web_routers/git.py:212 |  |
| GET | `/api/git/worktrees` | `git_worktrees_route` | hermes_cli/web_routers/git.py:110 |  |
| GET | `/api/health` | `get_health` | hermes_cli/web_routers/status.py:119 | Lightweight process liveness for desktop/backend readiness probes. |
| GET | `/api/health/idle` | `get_health_idle` | hermes_cli/web_routers/status.py:152 | Token-gated diagnostic snapshot; never a retirement permit. None means cannot prove idle. |
| POST | `/api/health/retirement` | `post_health_retirement` | hermes_cli/web_routers/status.py:160 |  |
| POST | `/api/hermes/update` | `update_hermes` | hermes_cli/web_routers/actions.py:241 | Kick off ``hermes update`` in the background. |
| GET | `/api/hermes/update/check` | `check_hermes_update` | hermes_cli/web_routers/actions.py:289 | Report whether a Hermes update is available, without applying it. |
| GET | `/api/hermes/update/receipt` | `get_update_receipt` | hermes_cli/web_routers/actions.py:491 | The FULL latest update receipt (steps, skips, gateway restart outcome, fleet |
| WS | `/api/host-terminal` | `host_terminal_ws` | hermes_cli/web_routers/webapp.py:41 |  |
| GET | `/api/host/identity` | `get_host_identity` | hermes_cli/web_routers/status.py:133 | Prove to an attaching `hermes serve`/`dashboard` WHO owns this port. |
| GET | `/api/learning/graph` | `get_learning_graph` | hermes_cli/web_routers/status.py:693 | Learning graph for the desktop panel: profile-scoped learned skills + memory chunks. |
| DELETE | `/api/learning/node` | `delete_learning_node` | hermes_cli/web_routers/status.py:727 | Delete a journey node — skills are archived (restorable), memories removed. |
| GET | `/api/learning/node` | `get_learning_node` | hermes_cli/web_routers/status.py:720 | Current content of a journey node (skill SKILL.md or memory chunk), for an edit prefill. |
| PUT | `/api/learning/node` | `update_learning_node` | hermes_cli/web_routers/status.py:738 | Rewrite a journey node's content (SKILL.md or memory chunk); profile as for DELETE. |
| POST | `/api/local-models/activate` | `local_models_activate` | hermes_cli/web_routers/local_models.py:952 | Make a downloaded model the default for new chats: a config write via the same machinery as |
| GET | `/api/local-models/catalog` | `local_models_catalog` | hermes_cli/web_routers/local_models.py:664 | Every entry answers up front: how big is the download, will it fit, what context/speed shape will I |
| POST | `/api/local-models/download` | `local_models_download` | hermes_cli/web_routers/local_models.py:756 | Accepts either a family id (downloads this machine's selected variant) or an exact variant model_id. |
| POST | `/api/local-models/download-browsed` | `local_models_download_browsed` | hermes_cli/web_routers/local_models.py:1017 | Download an arbitrary HF GGUF into the managed models dir. Once landed it is a normal staged model (the |
| POST | `/api/local-models/download/pause` | `local_models_download_pause` | hermes_cli/web_routers/local_models.py:773 | Pause the download phase of a model, component or quickstart job. |
| POST | `/api/local-models/download/resume` | `local_models_download_resume` | hermes_cli/web_routers/local_models.py:786 | Restart the same job; PM reuses verified files and durable ranges. |
| POST | `/api/local-models/eject` | `local_models_eject` | hermes_cli/web_routers/local_models.py:934 | Free a loaded model's GPU memory now; only demand (the next message) reloads it — residency v2 has no |
| GET | `/api/local-models/hardware` | `local_models_hardware` | hermes_cli/web_routers/local_models.py:585 | The budget as plain facts, polled by the pane and statusbar. Sync def: shells out to nvidia-smi — threadpool. |
| GET | `/api/local-models/jobs` | `local_models_jobs` | hermes_cli/web_routers/local_models.py:979 | All recent jobs, running first — the pane and app-level poller rediscover in-flight work here after a remount. |
| GET | `/api/local-models/jobs/{job_id}` | `local_models_job` | hermes_cli/web_routers/local_models.py:989 | Full-text HF search over GGUF models — the firehose behind the curated catalog; fit pills come from /search/files. |
| DELETE | `/api/local-models/models/{model_id}` | `local_models_delete` | hermes_cli/web_routers/local_models.py:795 | Remove every split part plus private assets, then bounce the router off the request thread (deleting |
| POST | `/api/local-models/quickstart` | `local_models_quickstart` | hermes_cli/web_routers/local_models.py:849 | One job: install the runtime (if missing), download this machine's build of the recommended model (if |
| POST | `/api/local-models/runtime/install` | `local_models_runtime_install` | hermes_cli/web_routers/local_models.py:710 |  |
| GET | `/api/local-models/search` | `local_models_search` | hermes_cli/web_routers/local_models.py:999 | Full-text HF search over GGUF models — the firehose behind the curated catalog; fit pills come from /search/files. |
| GET | `/api/local-models/search/files` | `local_models_search_files` | hermes_cli/web_routers/local_models.py:1008 | Servable GGUFs in one HF repo with a rough pre-download fit verdict per quant (file size + conservative |
| POST | `/api/local-models/server` | `local_models_server` | hermes_cli/web_routers/local_models.py:917 | Turn the local engine off (stop the server, free ALL GPU memory, disable auto-start) or back on. Unlike |
| POST | `/api/local-models/sideload` | `local_models_sideload` | hermes_cli/web_routers/local_models.py:1040 | Register a GGUF already on this machine: link it into the managed models dir (copy only when linking is |
| GET | `/api/local-models/status` | `local_models_status` | hermes_cli/web_routers/local_models.py:534 | Cheap, immediate: config state + installed runtime + staged models + supervisor state (GPU facts live |
| GET | `/api/logs` | `get_logs` | hermes_cli/web_routers/status.py:841 |  |
| GET | `/api/mcp/catalog` | `list_mcp_catalog` | hermes_cli/web_routers/mcp.py:439 | Browse the Nous-approved MCP catalog (optional-mcps/ manifests), each |
| POST | `/api/mcp/catalog/install` | `install_mcp_catalog_entry` | hermes_cli/web_routers/mcp.py:471 | Install a catalog MCP into config.yaml (declared env vars go to .env |
| GET | `/api/mcp/oauth/callback/{server_name:path}` | `mcp_oauth_callback` | hermes_cli/web_routers/mcp.py:339 |  |
| DELETE | `/api/mcp/oauth/flows/{flow_id}` | `cancel_mcp_oauth_flow` | hermes_cli/web_routers/mcp.py:325 | Cancel an in-flight flow. mark_error unblocks the worker so it frees the |
| GET | `/api/mcp/oauth/flows/{flow_id}` | `mcp_oauth_flow_status` | hermes_cli/web_routers/mcp.py:313 |  |
| GET | `/api/mcp/servers` | `list_mcp_servers` | hermes_cli/web_routers/mcp.py:94 |  |
| POST | `/api/mcp/servers` | `add_mcp_server` | hermes_cli/web_routers/mcp.py:110 |  |
| PUT | `/api/mcp/servers` | `replace_mcp_servers` | hermes_cli/web_routers/mcp.py:160 | Replace the entire ``mcp_servers`` map (the mcp.json editor's save) — |
| DELETE | `/api/mcp/servers/{name}` | `remove_mcp_server` | hermes_cli/web_routers/mcp.py:183 |  |
| POST | `/api/mcp/servers/{name}/auth` | `auth_mcp_server` | hermes_cli/web_routers/mcp.py:254 | Start MCP OAuth and hand the authorization URL to the dashboard browser. |
| PUT | `/api/mcp/servers/{name}/enabled` | `set_mcp_server_enabled` | hermes_cli/web_routers/mcp.py:372 | Toggle ``enabled`` (takes effect on next session/gateway); disabled |
| POST | `/api/mcp/servers/{name}/test` | `test_mcp_server` | hermes_cli/web_routers/mcp.py:204 | Connect to the server, list its tools, disconnect. |
| GET | `/api/media` | `get_media` | hermes_cli/web_routers/files.py:424 | Return a gateway-local image as a base64 data URL for remote clients |
| GET | `/api/media/proxy` | `proxy_remote_media` | hermes_cli/web_routers/files.py:468 | Fetch a remote image URL the gateway can reach but the client cannot |
| GET | `/api/memory` | `get_memory_status` | hermes_cli/web_routers/ops.py:498 |  |
| PUT | `/api/memory/provider` | `set_memory_provider` | hermes_cli/web_routers/ops.py:514 |  |
| GET | `/api/memory/providers/{name}/config` | `get_memory_provider_config` | hermes_cli/web_routers/memory_providers.py:508 |  |
| PUT | `/api/memory/providers/{name}/config` | `update_memory_provider_config` | hermes_cli/web_routers/memory_providers.py:551 |  |
| POST | `/api/memory/providers/{name}/setup` | `setup_memory_provider` | hermes_cli/web_routers/memory_providers.py:528 |  |
| POST | `/api/memory/reset` | `reset_memory` | hermes_cli/web_routers/ops.py:535 |  |
| GET | `/api/messaging/platforms` | `get_messaging_platforms` | hermes_cli/web_routers/messaging.py:916 |  |
| PUT | `/api/messaging/platforms/{platform_id}` | `update_messaging_platform` | hermes_cli/web_routers/messaging.py:975 |  |
| POST | `/api/messaging/platforms/{platform_id}/test` | `test_messaging_platform` | hermes_cli/web_routers/messaging.py:1181 |  |
| DELETE | `/api/messaging/telegram/onboarding/{pairing_id}` | `cancel_telegram_onboarding` | hermes_cli/web_routers/messaging.py:906 |  |
| GET | `/api/messaging/telegram/onboarding/{pairing_id}` | `get_telegram_onboarding_status` | hermes_cli/web_routers/messaging.py:827 |  |
| POST | `/api/messaging/telegram/onboarding/{pairing_id}/apply` | `apply_telegram_onboarding` | hermes_cli/web_routers/messaging.py:866 |  |
| POST | `/api/messaging/telegram/onboarding/start` | `start_telegram_onboarding` | hermes_cli/web_routers/messaging.py:804 |  |
| DELETE | `/api/messaging/whatsapp/onboarding/{pairing_id}` | `cancel_whatsapp_onboarding` | hermes_cli/web_routers/messaging.py:739 |  |
| GET | `/api/messaging/whatsapp/onboarding/{pairing_id}` | `get_whatsapp_onboarding_status` | hermes_cli/web_routers/messaging.py:679 |  |
| POST | `/api/messaging/whatsapp/onboarding/{pairing_id}/apply` | `apply_whatsapp_onboarding` | hermes_cli/web_routers/messaging.py:688 |  |
| POST | `/api/messaging/whatsapp/onboarding/start` | `start_whatsapp_onboarding` | hermes_cli/web_routers/messaging.py:629 |  |
| GET | `/api/model/auxiliary` | `get_auxiliary_models` | hermes_cli/web_routers/models.py:219 | Current auxiliary task assignments: ``{"tasks": [{task, provider, model, |
| GET | `/api/model/info` | `get_model_info` | hermes_cli/web_routers/models.py:90 | Resolved metadata for the configured model: auto-detected vs configured |
| GET | `/api/model/moa` | `get_moa_models` | hermes_cli/web_routers/models.py:272 | Return the configured Mixture-of-Agents provider/model slots. |
| PUT | `/api/model/moa` | `set_moa_models` | hermes_cli/web_routers/models.py:303 | Persist the Mixture-of-Agents provider/model slots. |
| GET | `/api/model/options` | `get_model_options` | hermes_cli/web_routers/models.py:138 | Authenticated providers + curated model lists — REST twin of the ``model.options`` |
| GET | `/api/model/recommended-default` | `get_recommended_default_model` | hermes_cli/web_routers/models.py:176 | Recommended default model for a freshly-authenticated provider, mirroring |
| POST | `/api/model/set` | `set_model_assignment` | hermes_cli/web_routers/models.py:376 | Assign a model to the main slot or an auxiliary task slot. Writes |
| POST | `/api/ops/backup` | `run_backup` | hermes_cli/web_routers/ops.py:583 |  |
| GET | `/api/ops/backup/download` | `download_dashboard_backup` | hermes_cli/web_routers/ops.py:602 |  |
| GET | `/api/ops/checkpoints` | `list_checkpoints` | hermes_cli/web_routers/ops.py:808 | /rollback shadow-store checkpoints (read-only): count + size per session |
| POST | `/api/ops/checkpoints/prune` | `prune_checkpoints` | hermes_cli/web_routers/ops.py:838 |  |
| POST | `/api/ops/config-migrate` | `run_config_migrate` | hermes_cli/web_routers/status.py:809 | Upload a redacted debug report + full logs and return the paste URLs. Synchronous, |
| POST | `/api/ops/debug-share` | `run_debug_share_endpoint` | hermes_cli/web_routers/status.py:814 | Upload a redacted debug report + full logs and return the paste URLs. Synchronous, |
| POST | `/api/ops/doctor` | `run_doctor` | hermes_cli/web_routers/ops.py:563 |  |
| POST | `/api/ops/dump` | `run_dump` | hermes_cli/web_routers/status.py:804 |  |
| DELETE | `/api/ops/hooks` | `delete_hook` | hermes_cli/web_routers/ops.py:773 | Remove a hook from config.yaml and revoke its consent allowlist entry. |
| GET | `/api/ops/hooks` | `list_hooks` | hermes_cli/web_routers/ops.py:673 | Configured shell hooks with consent (allowlist) status, whether the |
| POST | `/api/ops/hooks` | `create_hook` | hermes_cli/web_routers/ops.py:722 | Add a shell hook to config.yaml and optionally record consent. |
| POST | `/api/ops/import` | `run_import` | hermes_cli/web_routers/ops.py:629 |  |
| POST | `/api/ops/import-upload` | `run_import_upload` | hermes_cli/web_routers/ops.py:648 |  |
| POST | `/api/ops/prompt-size` | `run_prompt_size` | hermes_cli/web_routers/status.py:799 |  |
| POST | `/api/ops/security-audit` | `run_security_audit` | hermes_cli/web_routers/ops.py:569 | ``<profile home>/backups`` — the archive belongs to the profile it backs up. |
| GET | `/api/pairing` | `list_pairing` | hermes_cli/web_routers/ops.py:84 |  |
| POST | `/api/pairing/approve` | `approve_pairing` | hermes_cli/web_routers/ops.py:91 |  |
| POST | `/api/pairing/clear-pending` | `clear_pending_pairing` | hermes_cli/web_routers/ops.py:131 |  |
| POST | `/api/pairing/revoke` | `revoke_pairing` | hermes_cli/web_routers/ops.py:120 |  |
| GET | `/api/plugins/hermes-achievements/achievements` | `achievements` | plugins/hermes-achievements/dashboard/plugin_api.py:846 |  |
| GET | `/api/plugins/hermes-achievements/recent-unlocks` | `recent_unlocks` | plugins/hermes-achievements/dashboard/plugin_api.py:860 |  |
| POST | `/api/plugins/hermes-achievements/rescan` | `rescan` | plugins/hermes-achievements/dashboard/plugin_api.py:877 |  |
| POST | `/api/plugins/hermes-achievements/reset-state` | `reset_state` | plugins/hermes-achievements/dashboard/plugin_api.py:882 |  |
| GET | `/api/plugins/hermes-achievements/scan-status` | `scan_status` | plugins/hermes-achievements/dashboard/plugin_api.py:855 |  |
| GET | `/api/plugins/hermes-achievements/sessions/{session_id}/badges` | `session_badges` | plugins/hermes-achievements/dashboard/plugin_api.py:866 |  |
| GET | `/api/plugins/kanban/assignees` | `get_assignees` | plugins/kanban/dashboard/plugin_api.py:1247 | Union of on-disk profiles and assignees used on the board, so a fresh |
| DELETE | `/api/plugins/kanban/attachments/{attachment_id}` | `remove_attachment` | plugins/kanban/dashboard/plugin_api.py:523 |  |
| GET | `/api/plugins/kanban/attachments/{attachment_id}` | `download_attachment` | plugins/kanban/dashboard/plugin_api.py:505 |  |
| GET | `/api/plugins/kanban/board` | `get_board_endpoint` | plugins/kanban/dashboard/plugin_api.py:359 |  |
| GET | `/api/plugins/kanban/boards` | `list_boards` | plugins/kanban/dashboard/plugin_api.py:1411 | Every board on disk with task counts and the active slug. |
| POST | `/api/plugins/kanban/boards` | `create_board_endpoint` | plugins/kanban/dashboard/plugin_api.py:1440 | Create a board. Idempotent — ``slug`` collision returns the existing one. |
| DELETE | `/api/plugins/kanban/boards/{slug}` | `delete_board` | plugins/kanban/dashboard/plugin_api.py:1480 | Archive (default) or hard-delete a board. |
| PATCH | `/api/plugins/kanban/boards/{slug}` | `rename_board` | plugins/kanban/dashboard/plugin_api.py:1457 | Update display metadata / default workdir / project scope (slug is immutable). |
| POST | `/api/plugins/kanban/boards/{slug}/export` | `export_board_endpoint` | plugins/kanban/dashboard/plugin_api.py:1502 | Write ``slug`` to a portable archive; return the path written. |
| POST | `/api/plugins/kanban/boards/{slug}/switch` | `switch_board` | plugins/kanban/dashboard/plugin_api.py:1534 | Persist ``slug`` as the active board for CLI / slash-command parity |
| POST | `/api/plugins/kanban/boards/import` | `import_board_endpoint` | plugins/kanban/dashboard/plugin_api.py:1520 | Import a board archive as a NEW board; return the landed board. |
| GET | `/api/plugins/kanban/config` | `get_config` | plugins/kanban/dashboard/plugin_api.py:1149 | Kanban dashboard preferences from the ``dashboard.kanban`` config section. |
| GET | `/api/plugins/kanban/diagnostics` | `list_diagnostics` | plugins/kanban/dashboard/plugin_api.py:882 | Tasks with an active diagnostic, highest severity first then most recent; also |
| POST | `/api/plugins/kanban/dispatch` | `dispatch` | plugins/kanban/dashboard/plugin_api.py:1269 | Dispatch nudge so the UI doesn't wait out the 60 s dispatcher tick. |
| POST | `/api/plugins/kanban/estimate` | `estimate_text_endpoint` | plugins/kanban/dashboard/plugin_api.py:1071 | Estimate from raw title/body (create dialog, before a task exists). |
| WS | `/api/plugins/kanban/events` | `stream_events` | plugins/kanban/dashboard/plugin_api.py:1802 |  |
| GET | `/api/plugins/kanban/home-channels` | `get_home_channels` | plugins/kanban/dashboard/plugin_api.py:1196 | Every platform with a home channel plus whether *task_id* (if given) is |
| DELETE | `/api/plugins/kanban/links` | `delete_link` | plugins/kanban/dashboard/plugin_api.py:824 | Apply the bulk patch to one task, recording refusals in ``entry`` without aborting the |
| POST | `/api/plugins/kanban/links` | `add_link` | plugins/kanban/dashboard/plugin_api.py:817 |  |
| GET | `/api/plugins/kanban/model-options` | `model_options` | plugins/kanban/dashboard/plugin_api.py:1280 | Providers + curated models for the override dropdown via ``inventory.build_models_payload`` |
| GET | `/api/plugins/kanban/orchestration` | `get_orchestration_settings` | plugins/kanban/dashboard/plugin_api.py:1633 | Current orchestration knobs from config.yaml plus the resolved effective |
| PUT | `/api/plugins/kanban/orchestration` | `set_orchestration_settings` | plugins/kanban/dashboard/plugin_api.py:1675 | Update orchestration knobs in config.yaml. Only fields explicitly passed |
| GET | `/api/plugins/kanban/profiles` | `list_profile_roster` | plugins/kanban/dashboard/plugin_api.py:1553 | Every installed profile with its description (profiles without one are |
| PATCH | `/api/plugins/kanban/profiles/{profile_name}` | `update_profile_description` | plugins/kanban/dashboard/plugin_api.py:1567 | Set (``description_auto: false`` so the auto-describer won't overwrite it |
| POST | `/api/plugins/kanban/profiles/{profile_name}/describe-auto` | `auto_describe_profile` | plugins/kanban/dashboard/plugin_api.py:1586 | ``hermes profile describe <name> --auto``: persist with ``description_auto: true``. |
| GET | `/api/plugins/kanban/projects` | `list_kanban_projects` | plugins/kanban/dashboard/plugin_api.py:1398 | Live (non-archived) projects available for board scoping. |
| GET | `/api/plugins/kanban/runs/{run_id}` | `get_run_endpoint` | plugins/kanban/dashboard/plugin_api.py:936 | ``{run: {...}}`` with the same serialisation as ``GET /tasks/{id}``; 404 if unknown. |
| GET | `/api/plugins/kanban/runs/{run_id}/inspect` | `inspect_run_endpoint` | plugins/kanban/dashboard/plugin_api.py:943 | Live psutil stats for a run's worker; ``{alive: false, reason}`` when unavailable and |
| POST | `/api/plugins/kanban/runs/{run_id}/terminate` | `terminate_run_endpoint` | plugins/kanban/dashboard/plugin_api.py:985 | Terminate an in-flight run via ``reclaim_task`` (same SIGTERM->SIGKILL flow, bookkeeping |
| GET | `/api/plugins/kanban/stats` | `get_stats` | plugins/kanban/dashboard/plugin_api.py:1240 | Per-status + per-assignee counts + oldest-ready age (HUD and router profiles). |
| POST | `/api/plugins/kanban/tasks` | `create_task` | plugins/kanban/dashboard/plugin_api.py:435 |  |
| DELETE | `/api/plugins/kanban/tasks/{task_id}` | `delete_task` | plugins/kanban/dashboard/plugin_api.py:719 | Parent rows (id, title, status) not ``done`` that block promotion to ``ready``. |
| GET | `/api/plugins/kanban/tasks/{task_id}` | `get_task` | plugins/kanban/dashboard/plugin_api.py:379 |  |
| PATCH | `/api/plugins/kanban/tasks/{task_id}` | `update_task` | plugins/kanban/dashboard/plugin_api.py:692 |  |
| GET | `/api/plugins/kanban/tasks/{task_id}/attachments` | `list_task_attachments` | plugins/kanban/dashboard/plugin_api.py:464 |  |
| POST | `/api/plugins/kanban/tasks/{task_id}/attachments` | `upload_task_attachment` | plugins/kanban/dashboard/plugin_api.py:471 | Store an upload under ``attachments_root(board)/<task_id>/`` (sanitised, |
| POST | `/api/plugins/kanban/tasks/{task_id}/comments` | `add_comment` | plugins/kanban/dashboard/plugin_api.py:802 |  |
| POST | `/api/plugins/kanban/tasks/{task_id}/decompose` | `decompose_task_endpoint` | plugins/kanban/dashboard/plugin_api.py:1602 | Fan a triage task out into child tasks via the auxiliary LLM (``hermes kanban decompose``). |
| POST | `/api/plugins/kanban/tasks/{task_id}/estimate` | `estimate_task_endpoint` | plugins/kanban/dashboard/plugin_api.py:1077 | Estimate for an existing task; ``{ok, est_tokens, complexity, rationale, model}``. |
| DELETE | `/api/plugins/kanban/tasks/{task_id}/home-subscribe/{platform}` | `unsubscribe_home` | plugins/kanban/dashboard/plugin_api.py:1228 | Remove any notify subscription on *task_id* matching *platform*'s home. |
| POST | `/api/plugins/kanban/tasks/{task_id}/home-subscribe/{platform}` | `subscribe_home` | plugins/kanban/dashboard/plugin_api.py:1211 | Subscribe *task_id* to *platform*'s home channel. Idempotent at the DB |
| GET | `/api/plugins/kanban/tasks/{task_id}/log` | `get_task_log` | plugins/kanban/dashboard/plugin_api.py:1255 | Worker stdout/stderr log. ``tail`` caps the response bytes; 404 if the |
| POST | `/api/plugins/kanban/tasks/{task_id}/reassign` | `reassign_task_endpoint` | plugins/kanban/dashboard/plugin_api.py:1039 | Reassign to another profile, optionally reclaiming first |
| POST | `/api/plugins/kanban/tasks/{task_id}/reclaim` | `reclaim_task_endpoint` | plugins/kanban/dashboard/plugin_api.py:1008 | Release an active worker claim without waiting for the claim TTL |
| POST | `/api/plugins/kanban/tasks/{task_id}/specify` | `specify_task_endpoint` | plugins/kanban/dashboard/plugin_api.py:1025 | Flesh out a triage task via the auxiliary LLM (``hermes kanban specify``). Non-OK is NOT |
| POST | `/api/plugins/kanban/tasks/bulk` | `bulk_update` | plugins/kanban/dashboard/plugin_api.py:858 | Apply the same patch to every id. Independent iteration — per-task |
| GET | `/api/plugins/kanban/workers/active` | `list_active_workers` | plugins/kanban/dashboard/plugin_api.py:920 | Every running worker: an open ``task_runs`` row with a ``worker_pid`` whose |
| GET | `/api/plugins/kanban/workflow` | `get_workflow` | plugins/kanban/dashboard/plugin_api.py:1625 | Board columns (order, label, icon, drag target) and the manual move |
| GET | `/api/portal` | `get_portal_status` | hermes_cli/web_routers/status.py:749 |  |
| GET | `/api/profiles` | `list_profiles_endpoint` | hermes_cli/web_routers/profiles.py:807 |  |
| POST | `/api/profiles` | `create_profile_endpoint` | hermes_cli/web_routers/profiles.py:812 |  |
| DELETE | `/api/profiles/{name}` | `delete_profile_endpoint` | hermes_cli/web_routers/profiles.py:979 | The dashboard collects the user's confirmation in its own dialog, so ``yes=True`` |
| PATCH | `/api/profiles/{name}` | `rename_profile_endpoint` | hermes_cli/web_routers/profiles.py:960 |  |
| POST | `/api/profiles/{name}/describe-auto` | `describe_profile_auto_endpoint` | hermes_cli/web_routers/profiles.py:1066 | Auto-generate a profile's description via the auxiliary LLM (mirrors ``hermes profile |
| PUT | `/api/profiles/{name}/description` | `update_profile_description_endpoint` | hermes_cli/web_routers/profiles.py:1037 | Set or clear a profile's role description (kanban routing signal), stored as |
| GET | `/api/profiles/{name}/desktop-overlay` | `get_profile_desktop_overlay` | hermes_cli/web_routers/profiles.py:1144 | The desktop appearance/interface overlay bundled with an imported profile |
| POST | `/api/profiles/{name}/export` | `export_profile_endpoint` | hermes_cli/web_routers/profiles.py:1097 |  |
| PUT | `/api/profiles/{name}/model` | `update_profile_model_endpoint` | hermes_cli/web_routers/profiles.py:1051 | Set the main model for a specific profile's config.yaml without touching the dashboard's |
| POST | `/api/profiles/{name}/open-terminal` | `open_profile_terminal_endpoint` | hermes_cli/web_routers/profiles.py:938 |  |
| GET | `/api/profiles/{name}/setup-command` | `get_profile_setup_command` | hermes_cli/web_routers/profiles.py:917 |  |
| GET | `/api/profiles/{name}/soul` | `get_profile_soul` | hermes_cli/web_routers/profiles.py:1000 |  |
| PUT | `/api/profiles/{name}/soul` | `update_profile_soul` | hermes_cli/web_routers/profiles.py:1015 |  |
| GET | `/api/profiles/active` | `get_active_profile_endpoint` | hermes_cli/web_routers/profiles.py:885 | ``active`` is the sticky default written by ``hermes profile use`` (what new CLI |
| POST | `/api/profiles/active` | `set_active_profile_endpoint` | hermes_cli/web_routers/profiles.py:906 | Set the sticky active profile (mirrors ``hermes profile use``); does not retarget the |
| POST | `/api/profiles/import` | `import_profile_endpoint` | hermes_cli/web_routers/profiles.py:1115 |  |
| GET | `/api/profiles/projects/tree` | `get_profiles_projects_tree` | hermes_cli/web_routers/profiles.py:716 | Project tree for every profile at once, for the all-profiles sidebar. |
| GET | `/api/profiles/sessions` | `get_profiles_sessions` | hermes_cli/web_routers/profiles.py:453 | Unified, read-only session list aggregated across ALL profiles: opens each profile's |
| POST | `/api/profiles/sessions/pull-requests` | `post_profiles_sessions_pull_requests` | hermes_cli/web_routers/profiles.py:769 | The PR each of these sessions opened, recovered from its own transcript: a session |
| GET | `/api/profiles/sessions/sidebar` | `get_profiles_sessions_sidebar` | hermes_cli/web_routers/profiles.py:521 | Batched sidebar session slices (recents / cron / messaging) — one profile-DB open per |
| GET | `/api/providers/custom-endpoints` | `list_custom_endpoints` | hermes_cli/web_routers/config_env.py:728 | Return configured OpenAI-compatible custom endpoints for Desktop. |
| POST | `/api/providers/custom-endpoints` | `upsert_custom_endpoint` | hermes_cli/web_routers/config_env.py:741 | Create or update a v12+ ``providers`` custom endpoint entry. |
| DELETE | `/api/providers/custom-endpoints/{endpoint_id}` | `delete_custom_endpoint` | hermes_cli/web_routers/config_env.py:824 | Remove a configured custom endpoint from ``providers``. |
| POST | `/api/providers/custom-endpoints/{endpoint_id}/activate` | `activate_custom_endpoint` | hermes_cli/web_routers/config_env.py:772 | Set a configured custom endpoint as the default model provider. |
| POST | `/api/providers/custom-endpoints/validate` | `validate_custom_endpoint` | hermes_cli/web_routers/config_env.py:852 | Probe a custom endpoint by calling its OpenAI-compatible /models URL. |
| GET | `/api/providers/oauth` | `list_oauth_providers` | hermes_cli/web_routers/oauth.py:718 | Every OAuth-capable provider with current status (token_preview is the last |
| DELETE | `/api/providers/oauth/{provider_id}` | `disconnect_oauth_provider` | hermes_cli/web_routers/oauth.py:774 | Disconnect an OAuth provider. Token-protected (matches /env/reveal). |
| GET | `/api/providers/oauth/{provider_id}/poll/{session_id}` | `poll_oauth_session` | hermes_cli/web_routers/oauth.py:921 | Poll a session's status (no auth — read-only state). One endpoint serves |
| POST | `/api/providers/oauth/{provider_id}/start` | `start_oauth_login` | hermes_cli/web_routers/oauth.py:841 | Initiate an OAuth login flow. Token-protected. |
| POST | `/api/providers/oauth/{provider_id}/submit` | `submit_oauth_code` | hermes_cli/web_routers/oauth.py:896 | Submit the auth code for PKCE flows. Token-protected. |
| GET | `/api/providers/oauth/nous/handoff` | `nous_handoff_status` | hermes_cli/web_routers/oauth.py:956 | The profile's latest free-tier handoff (``guest_handoff.wire_status``). Token-protected. A |
| DELETE | `/api/providers/oauth/sessions/{session_id}` | `cancel_oauth_session` | hermes_cli/web_routers/oauth.py:975 | Cancel a pending OAuth session. Token-protected. |
| POST | `/api/providers/validate` | `validate_provider_credential` | hermes_cli/web_routers/config_env.py:997 | Live-probe a provider credential before it's saved. |
| WS | `/api/pty` | `pty_ws` | hermes_cli/web_routers/chat_ws.py:475 |  |
| WS | `/api/pub` | `pub_ws` | hermes_cli/web_routers/chat_ws.py:714 |  |
| GET | `/api/sessions` | `get_sessions` | hermes_cli/web_routers/sessions.py:182 | List sessions. |
| DELETE | `/api/sessions/{session_id}` | `delete_session_endpoint` | hermes_cli/web_routers/sessions.py:846 |  |
| GET | `/api/sessions/{session_id}` | `get_session_detail` | hermes_cli/web_routers/sessions.py:560 |  |
| PATCH | `/api/sessions/{session_id}` | `rename_session_endpoint` | hermes_cli/web_routers/sessions.py:926 | Title and sidebar flags share one authoritative revision and receipt. |
| GET | `/api/sessions/{session_id}/export` | `export_session_endpoint` | hermes_cli/web_routers/sessions.py:941 | Stream a single session (metadata + messages) as JSON. |
| GET | `/api/sessions/{session_id}/latest-descendant` | `get_session_latest_descendant` | hermes_cli/web_routers/sessions.py:601 |  |
| GET | `/api/sessions/{session_id}/messages` | `get_session_messages` | hermes_cli/web_routers/sessions.py:739 |  |
| GET | `/api/sessions/{session_id}/messages/around` | `get_session_messages_around` | hermes_cli/web_routers/sessions.py:823 | Bounded display page starting at a timeline prompt; no intervening payloads. |
| GET | `/api/sessions/{session_id}/mutation-snapshot` | `get_session_mutation_snapshot` | hermes_cli/web_routers/sessions.py:586 |  |
| GET | `/api/sessions/{session_id}/timeline` | `get_session_timeline` | hermes_cli/web_routers/sessions.py:801 | Prompt metadata only, including compacted display history (never rewind rows). |
| POST | `/api/sessions/bulk-delete` | `bulk_delete_sessions_endpoint` | hermes_cli/web_routers/sessions.py:466 | Delete every session in ``body.ids`` in one transaction (POST: many |
| DELETE | `/api/sessions/empty` | `delete_empty_sessions_endpoint` | hermes_cli/web_routers/sessions.py:519 | Delete every empty, ended, non-archived session in one transaction. |
| GET | `/api/sessions/empty/count` | `count_empty_sessions_endpoint` | hermes_cli/web_routers/sessions.py:511 | Count of empty, ended, non-archived sessions (the "Delete empty (N)" button). |
| POST | `/api/sessions/import` | `import_sessions_endpoint` | hermes_cli/web_routers/sessions.py:492 | Import sessions exported from the dashboard or CLI (session rows only — |
| POST | `/api/sessions/owner-backfill` | `backfill_session_owner_profiles` | hermes_cli/web_routers/sessions.py:890 | Stamp legacy ``profile_name = NULL`` rows with the serving-profile identity. |
| POST | `/api/sessions/prune` | `prune_sessions_endpoint` | hermes_cli/web_routers/sessions.py:977 | Delete ended sessions matching filters without blocking the event loop. |
| GET | `/api/sessions/search` | `search_sessions` | hermes_cli/web_routers/sessions.py:286 | Search sessions by ID (first) plus FTS5 message content. |
| GET | `/api/sessions/stats` | `get_session_stats` | hermes_cli/web_routers/sessions.py:538 | Session-store statistics (mirrors `hermes sessions stats`). |
| GET | `/api/shared-metrics/consent` | `get_shared_metrics_consent` | hermes_cli/web_routers/shared_metrics.py:34 |  |
| PUT | `/api/shared-metrics/consent` | `put_shared_metrics_consent` | hermes_cli/web_routers/shared_metrics.py:39 |  |
| GET | `/api/skills` | `get_skills` | hermes_cli/web_routers/skills.py:343 |  |
| POST | `/api/skills` | `create_skill` | hermes_cli/web_routers/skills.py:418 | Create a skill via the agent's ``skill_manage`` write path, minus the |
| GET | `/api/skills/content` | `get_skill_content` | hermes_cli/web_routers/skills.py:397 | Raw SKILL.md text for the dashboard editor. |
| PUT | `/api/skills/content` | `update_skill_content` | hermes_cli/web_routers/skills.py:433 | Replace the SKILL.md of an existing skill (full rewrite) from the editor. |
| POST | `/api/skills/hub/install` | `install_skill_hub` | hermes_cli/web_routers/skills.py:97 |  |
| GET | `/api/skills/hub/official` | `list_official_skills` | hermes_cli/web_routers/skills.py:123 | The ENTIRE optional-skills catalog (local scan), marked installed for ``profile``. |
| GET | `/api/skills/hub/preview` | `preview_skill_hub` | hermes_cli/web_routers/skills.py:227 | A hub skill's SKILL.md + file manifest WITHOUT installing it; scoped to |
| GET | `/api/skills/hub/scan` | `scan_skill_hub` | hermes_cli/web_routers/skills.py:265 | Install-time security scan of a hub skill WITHOUT installing it (the CLI's |
| GET | `/api/skills/hub/search` | `search_skills_hub` | hermes_cli/web_routers/skills.py:185 | Search the skill hub across all configured sources (network-bound). |
| GET | `/api/skills/hub/sources` | `list_skills_hub_sources` | hermes_cli/web_routers/skills.py:147 | Configured skill-hub sources + installed-skill provenance (scoped to |
| POST | `/api/skills/hub/uninstall` | `uninstall_skill_hub` | hermes_cli/web_routers/skills.py:106 |  |
| POST | `/api/skills/hub/update` | `update_skills_hub` | hermes_cli/web_routers/skills.py:115 | The ENTIRE optional-skills catalog (local scan), marked installed for ``profile``. |
| PUT | `/api/skills/toggle` | `toggle_skill` | hermes_cli/web_routers/skills.py:379 |  |
| GET | `/api/ssh/ownership` | `get_ssh_ownership` | hermes_cli/web_routers/status.py:109 | Lightweight process liveness for desktop/backend readiness probes. |
| GET | `/api/status` | `get_status` | hermes_cli/web_routers/status.py:482 | Public machine-level liveness probe (``PUBLIC_API_PATHS``): version, gateway state, |
| GET | `/api/system/stats` | `get_system_stats` | hermes_cli/web_routers/status.py:585 | Host + process system stats for the System page (stdlib identity; psutil CPU/memory/ |
| POST | `/api/tools/computer-use/permissions/grant` | `grant_computer_use_permissions` | hermes_cli/web_routers/tools.py:705 | Spawn ``hermes computer-use permissions grant`` (macOS-only: launches |
| GET | `/api/tools/computer-use/status` | `get_computer_use_status` | hermes_cli/web_routers/tools.py:696 | Computer Use readiness for the desktop card (payload shape: see |
| PUT | `/api/tools/terminal/backend` | `select_terminal_backend` | hermes_cli/web_routers/tools.py:672 | Persist ``terminal.backend``.  A backend that still needs setup is |
| GET | `/api/tools/terminal/backends` | `get_terminal_backends` | hermes_cli/web_routers/tools.py:645 | Terminal backend rows with health probes: ``status`` is ``ready`` / |
| GET | `/api/tools/toolsets` | `get_toolsets` | hermes_cli/web_routers/tools.py:223 |  |
| PUT | `/api/tools/toolsets/{name}` | `toggle_toolset` | hermes_cli/web_routers/tools.py:273 | Enable/disable a configurable toolset for its configuration platform |
| GET | `/api/tools/toolsets/{name}/config` | `get_toolset_config` | hermes_cli/web_routers/tools.py:338 | Provider matrix + key status for a toolset's config panel (the CLI picker |
| PUT | `/api/tools/toolsets/{name}/env` | `save_toolset_env` | hermes_cli/web_routers/tools.py:581 | Persist API keys to ``.env`` via ``save_env_value``.  Keys are validated |
| PUT | `/api/tools/toolsets/{name}/model` | `select_toolset_model` | hermes_cli/web_routers/tools.py:464 | Persist a backend model selection (``image_gen.model`` / |
| GET | `/api/tools/toolsets/{name}/models` | `get_toolset_models` | hermes_cli/web_routers/tools.py:424 | Model catalog for a toolset backend (image/video gen) — the GUI |
| POST | `/api/tools/toolsets/{name}/post-setup` | `run_toolset_post_setup` | hermes_cli/web_routers/tools.py:621 | Spawn ``hermes tools post-setup <key>`` (long-running installs) as a |
| PUT | `/api/tools/toolsets/{name}/provider` | `select_toolset_provider` | hermes_cli/web_routers/tools.py:496 | Persist a provider selection via ``apply_provider_selection`` (shared with |
| POST | `/api/webapp-access/restart` | `restart_webapp_access` | hermes_cli/web_routers/webapp_access.py:146 | Apply the saved config to a gateway that has not restarted onto it yet: restart that gateway |
| POST | `/api/webapp-access/start` | `start_webapp_access` | hermes_cli/web_routers/webapp_access.py:119 |  |
| GET | `/api/webapp-access/status` | `webapp_access_status` | hermes_cli/web_routers/webapp_access.py:114 |  |
| POST | `/api/webapp-access/stop` | `stop_webapp_access` | hermes_cli/web_routers/webapp_access.py:134 |  |
| POST | `/api/webapp/socket-ticket` | `issue_socket_ticket` | hermes_cli/web_routers/webapp.py:227 | The browser's one-use ticket for one dial of *socket* on this same origin: ``gateway`` for |
| POST | `/api/webapp/window-ticket` | `issue_window_ticket` | hermes_cli/web_routers/webapp.py:155 | ``({user_id, provider, issuer}, session_token)`` of the caller: its verified login (no |
| GET | `/api/webhooks` | `list_webhooks` | hermes_cli/web_routers/ops.py:158 |  |
| POST | `/api/webhooks` | `create_webhook` | hermes_cli/web_routers/ops.py:194 |  |
| DELETE | `/api/webhooks/{name}` | `delete_webhook` | hermes_cli/web_routers/ops.py:258 |  |
| PUT | `/api/webhooks/{name}/enabled` | `set_webhook_enabled` | hermes_cli/web_routers/ops.py:274 | Disabled routes stay on disk (re-enable later) but the gateway rejects |
| POST | `/api/webhooks/enable` | `enable_webhooks` | hermes_cli/web_routers/ops.py:176 |  |
| WS | `/api/ws` | `gateway_ws` | hermes_cli/web_routers/chat_ws.py:650 |  |
| GET | `/auth/callback` | `auth_callback` | hermes_cli/dashboard_auth/routes.py:346 |  |
| GET | `/auth/login` | `auth_login` | hermes_cli/dashboard_auth/routes.py:225 |  |
| POST | `/auth/logout` | `auth_logout` | hermes_cli/dashboard_auth/routes.py:503 |  |
| GET | `/auth/native/authorize` | `auth_native_authorize` | hermes_cli/dashboard_auth/routes.py:297 | Begin an RFC 8252 native-app login: stash a pending broker authorization keyed by an |
| POST | `/auth/native/refresh` | `auth_native_refresh` | hermes_cli/dashboard_auth/routes.py:606 | Rotate a desktop-held refresh token (mirrors the gate's ``_attempt_refresh``): every |
| POST | `/auth/native/token` | `auth_native_token` | hermes_cli/dashboard_auth/routes.py:585 | Exchange a loopback gateway code + PKCE verifier for bearer tokens. The code is consumed |
| POST | `/auth/password-login` | `auth_password_login` | hermes_cli/dashboard_auth/routes.py:454 | Authenticate a username/password against a password provider. |
| GET | `/dashboard-plugins/{plugin_name}/{file_path:path}` | `serve_plugin_asset` | hermes_cli/web_routers/dashboard_ui.py:372 | Serve static assets from a dashboard plugin's ``dashboard/`` directory. |
| GET | `/dashboard-plugins/@{profile}/{plugin_name}/{file_path:path}` | `serve_profile_plugin_asset` | hermes_cli/web_routers/dashboard_ui.py:365 | ``serve_plugin_asset`` with the profile as a path segment (PLG-3): URLs relative to a stylesheet |
| GET | `/login` | `login_page` | hermes_cli/dashboard_auth/routes.py:203 |  |
| GET | `/webapp/launch` | `launch_page` | hermes_cli/web_routers/webapp.py:337 | Where the server's own browser-open lands: spends the one-use launch ticket from the |
| GET | `/webapp/window` | `child_window_page` | hermes_cli/web_routers/webapp.py:402 |  |
| POST | `/webapp/window-session` | `redeem_window_ticket` | hermes_cli/web_routers/webapp.py:253 |  |

## Appendix B. JSON-RPC catalog (generated from tui_gateway/contracts)

Source: `contract/gateway-contract.generated.ts` (copied verbatim beside this doc; `contract/gateway-contract.openrpc.json` is the same contract as OpenRPC, usable for Kotlin code generation). Field lists are flattened one level; open the TS file for nested types. `?` = optional.

### B.1 Canonical session-authority methods (`hermes-gateway-v1`)

48 entries.

- **`a2a.forward`** — Forward one A2A input into the conversation its identity tuple names.
  - params `A2aForwardParams`: agent: string; tenant: string; peer: string; context_id: string; input_id: string; text: string
  - result `A2aForwardResult`: admission_id: string; ref: CanonicalSessionRef; sequence: number; status: 'queued' \| 'started' \| 'unknown' \| 'terminal'; outcome: string \| null; authority_epoch: number; execution_generation: number \| null; session_id: string; result: unknown \| null
- **`approval.respond`**
  - params `CanonicalApprovalParams`: profile?: string \| null; session_id: string; execution_generation: number; prompt_id: string; choice: string
  - result `PromptResponseResult`: status: 'resolved' \| 'already_resolved'; prompt_id: string
- **`bot_relay.deliver`**
  - params `CanonicalBotDeliverParams`: profile?: string \| null; message: string; id: string; session_id?: string \| null; author?: Record<string, unknown> \| null; notification_category?: string \| null
  - result `CanonicalBotDeliverResult`: status: string; delivery_id: string; profile_home: string; session_id: string; admission_id?: string \| null; refused?: true \| null; message: string; reply?: string \| null; retry_admission_id?: string \| null; error?: string \| null; reason?: string \| null
- **`bot_relay.settle`**
  - params `CanonicalBotDeliverParams`: profile?: string \| null; message: string; id: string; session_id?: string \| null; author?: Record<string, unknown> \| null; notification_category?: string \| null
  - result `CanonicalBotDeliverResult`: status: string; delivery_id: string; profile_home: string; session_id: string; admission_id?: string \| null; refused?: true \| null; message: string; reply?: string \| null; retry_admission_id?: string \| null; error?: string \| null; reason?: string \| null
- **`clarify.lock`** — Lock one answer of the owner's batch clarify request (editable until every question is locked); only a viewer attached to that session with session:respond may lock.
  - params `ClarifyLockParams`: request_id: string; question_id: string; answer?: unknown; profile?: string \| null
  - result `ClarifyLockResult`: status: ClarifyLockStatus; remaining?: string[] \| null
- **`clarify.respond`** — Generation-fenced answer to a pending clarify prompt (empty answer = skipped).
  - params `ClarifyRespondParams`: session_id: string; execution_generation: number; prompt_id: string; answer: string
  - result `PromptResponseResult`: status: 'resolved' \| 'already_resolved'; prompt_id: string
- **`command.observe`** — Fire-only: a recognized command the classic CLI or the TUI runs itself; the gateway fires `pre_command` for it in the session's profile after replying (`queued` false: not fired).
  - params `CommandObserveParams`: session_id: string; name: string; arg?: string \| null; profile?: string \| null
  - result `CommandObserveResult`: queued: boolean
- **`config.get`**
  - params `ConfigGetParams`: profile?: string \| null; key: string; cwd?: string \| null; session_id?: string \| null
  - result `CanonicalConfigGetResult`: value?: string \| null; display?: string \| null; tool_progress?: string \| null; model?: string \| null; provider?: string \| null; providers?: ConfigProviderRef[] \| null; home?: string \| null; cwd?: string \| null; branch?: string \| null; config?: Record<string, unknown> \| null; prompt?: string \| null; mtime?: number \| null; mcp_rev?: string \| null; key?: string \| null; scope?: string \| null
- **`config.set`**
  - params `CanonicalConfigSetParams`: profile?: string \| null; session_id?: string \| null; key: string; value?: unknown; confirm_expensive_model?: boolean \| null; scope?: 'session' \| 'global' \| null
  - result `ConfigSetResult`: key: string; value?: string \| boolean \| null; warning?: string \| null; confirm_required?: boolean \| null; confirm_message?: string \| null; scope?: string \| null; deferred?: boolean \| null; tool_progress?: string \| null; cwd?: string \| null; branch?: string \| null; history_reset?: boolean \| null; info?: SessionLiveInfo \| null
- **`cron.cancel`** — Cancel a queued cron admission or latch cancellation on a started one.
  - params `CronAdmissionParams`: session_id: string; admission_id: string
  - result `CronCancelResult`: ok: boolean
- **`cron.recover`** — Re-observe a firing after a scheduler restart (same request identity).
  - params `CronRunParams`: job_id: string; request_id: string; extra_prompt: string \| null
  - result `CronStatusResult`: status: string; result: unknown[] \| null; job_flags?: Record<string, unknown> \| null; job?: Record<string, unknown> \| null
- **`cron.status`** — Status and, once terminal, the result of one cron admission.
  - params `CronAdmissionParams`: session_id: string; admission_id: string
  - result `CronStatusResult`: status: string; result: unknown[] \| null; job_flags?: Record<string, unknown> \| null; job?: Record<string, unknown> \| null
- **`cron.submit`** — Admit one cron firing into the owning profile's durable FIFO.
  - params `CronRunParams`: job_id: string; request_id: string; extra_prompt: string \| null
  - result `CronSubmitResult`: session_id: string; admission_id: string
- **`groups.approve`**
  - params `CanonicalRoomApproveParams`: profile?: string \| null; room_id: string; member_id: string; task_id: string; execution_generation: number; choice: 'once' \| 'deny'; request_id: string
  - result `GroupsApproveResult`: approved?: boolean; result: Record<string, unknown>
- **`groups.attachment.download`** — Read one committed attachment for a live room viewer.
  - params `GroupsAttachmentDownloadParams`: profile?: string \| null; room_id: string; event_id: string; attachment_id: string
  - result `RoomAttachmentBytes`: attachment_id: string; kind: string; name: string; size: number; mime: string; sha256: string; state: string; created_at: number; idempotent: boolean; event_id?: string \| null; data_base64: string
- **`groups.attachment.upload`** — Store one attachment for a later groups.send manifest (idempotent per upload_id).
  - params `GroupsAttachmentUploadParams`: profile?: string \| null; room_id: string; upload_id: string; kind: string; name: string; mime: string; data_base64: string
  - result `RoomAttachment`: attachment_id: string; kind: string; name: string; size: number; mime: string; sha256: string; state: string; created_at: number; idempotent: boolean; event_id?: string \| null
- **`groups.create`**
  - params `CanonicalRoomCreateParams`: profile?: string \| null; room_id: string; name: string; members: RoomMemberInput[]
  - result `GroupsCreateResult`: room: Room
- **`groups.discard`** — Discard one indeterminate room task after explicit user confirmation.
  - params `GroupsDiscardParams`: profile?: string \| null; room_id: string; member_id: string; task_id: string; execution_generation: number
  - result `GroupsDiscardResult`: discarded?: boolean; task: RoomTaskReceipt
- **`groups.retry`**
  - params `CanonicalRoomAttemptParams`: profile?: string \| null; room_id: string; member_id: string; task_id: string; execution_generation: number
  - result `GroupsRetryResult`: retried?: boolean; task: RoomTaskReceipt
- **`image.attach_bytes`**
  - params `ImageAttachBytesParams`: session_id: string; profile?: string \| null; content_base64?: string \| null; data?: string \| null; filename?: string \| null; ext?: string \| null
  - result `CanonicalAttachedImageResult`: name?: string \| null; width?: number \| null; height?: number \| null; token_estimate?: number \| null; attached: boolean; path?: string \| null; count?: number \| null; remainder?: string \| null; text?: string \| null; bytes?: number \| null; message?: string \| null; mime: string
- **`kanban.run`** — Native-owner only: admit the dispatcher's current claim on a kanban task.
  - params `KanbanRunParams`: board: string; task_id: string; run_id: number; claim_lock: string; db?: string \| null
  - result `KanbanRunResult`: session_id: string; receipt: AdmissionStatus
- **`profiles.list`**
  - params `CanonicalProfilesParams`: profile?: string \| null; include_sessions?: boolean
  - result `CanonicalProfilesResult`: profiles: CanonicalProfileRow[]; bot_mode_protocol?: boolean; install_id?: string
- **`prompt.cancel`** — Retire a queued admission; a started one is interrupted in the same call.
  - params `PromptCancelParams`: session_id: string; admission_id: string
  - result `AdmissionStatus`: admission_id: string; ref: CanonicalSessionRef; sequence: number; status: 'queued' \| 'started' \| 'unknown' \| 'terminal'; outcome: string \| null; authority_epoch: number; execution_generation: number \| null
- **`prompt.receipt`** — Current receipt of one admission the caller submitted (or controls).
  - params `PromptReceiptParams`: session_id: string; admission_id: string; include_result?: boolean \| null
  - result `PromptReceiptResult`: admission_id: string; ref: CanonicalSessionRef; sequence: number; status: 'queued' \| 'started' \| 'unknown' \| 'terminal'; outcome: string \| null; authority_epoch: number; execution_generation: number \| null; result?: unknown \| null; usage?: Record<string, unknown> \| null
- **`prompt.replace_queued`** — Cancel a still-queued admission and admit the edited prompt as a new admission at the back of the FIFO, in one write bound to the replaced admission. Refusals: admission_started, admission_conflict (the submission_id replaced another admission), queue_full, unsupported_control, not_found.
  - params `CanonicalReplaceQueuedParams`: profile?: string \| null; session_id: string; admission_id: string; submission_id: string; text: string; attachments?: CanonicalAttachment[] \| null; display_kind?: 'hidden' \| null; title_preview?: string \| null
  - result `AdmissionStatus`: admission_id: string; ref: CanonicalSessionRef; sequence: number; status: 'queued' \| 'started' \| 'unknown' \| 'terminal'; outcome: string \| null; authority_epoch: number; execution_generation: number \| null
- **`prompt.resolve_unknown`** — Acknowledge a turn lost across an owner restart; the FIFO behind it resumes. Never requeues the lost input.
  - params `PromptResolveUnknownParams`: session_id: string; admission_id: string; execution_generation: number
  - result `AdmissionStatus`: admission_id: string; ref: CanonicalSessionRef; sequence: number; status: 'queued' \| 'started' \| 'unknown' \| 'terminal'; outcome: string \| null; authority_epoch: number; execution_generation: number \| null
- **`prompt.steer_queued`** — Hand a still-queued admission's text to the running turn's agent as a steer. The admission reads 'started' with that turn (pending snapshots mark it 'steering') and never runs on its own; it settles terminal 'steered' when the turn completes with its text, else 'interrupted' (its completion frame says 'cancelled', as an interrupted turn's does); a text the turn never consumed goes back to its queue place. Refusals: admission_started, session_idle, steer_unavailable (no agent to take it: the admission stays queued), unsupported_control, not_found.
  - params `CanonicalSteerQueuedParams`: profile?: string \| null; session_id: string; admission_id: string
  - result `AdmissionStatus`: admission_id: string; ref: CanonicalSessionRef; sequence: number; status: 'queued' \| 'started' \| 'unknown' \| 'terminal'; outcome: string \| null; authority_epoch: number; execution_generation: number \| null
- **`prompt.submit`**
  - params `CanonicalSubmitParams`: profile?: string \| null; session_id: string; text: string; submission_id?: string \| null; input_id?: string \| null; queued?: boolean; attachments?: CanonicalAttachment[] \| null; finite?: boolean; unattended?: boolean; surface?: string \| null; voice_context?: string \| null; interrupted?: boolean; voice_turn?: boolean; display_kind?: 'hidden' \| null; title_preview?: string \| null; turn_author?: CanonicalTurnAuthor \| null; api_text?: string \| null; model_override?: CanonicalModelOverride \| null; clarify_batch?: boolean
  - result `CanonicalSubmitResult`: `AdmissionStatus \| CanonicalVoiceStopped`
- **`rollback.session_diff`** — The classic CLI's /diff session: everything changed since the session's earliest retained checkpoint, as data for its terminal renderer.
  - params `RollbackSessionDiffParams`: session_id: string; profile?: string \| null
  - result `RollbackSessionDiffResult`: enabled: boolean; success?: boolean; stat?: string; diff?: string; diff_lines?: number; empty?: boolean; error?: string \| null
- **`runtime.describe`** — Owner identity and the canonical capabilities a client may rely on.
  - params `RuntimeDescribeParams`: `Record<string, never>`
  - result `RuntimeDescribeResult`: instance_id: string; profile_id: string; authority_epoch: number; capabilities: string[]; session_create: SessionCreateDescriptor; skin?: SkinPayload \| null
- **`session.automation`** — The classic CLI's /goal, /subgoal, /loop, /heartbeat manager calls (state writes, the goal judge, quality gates), run by the owner on the session's row.
  - params `SessionAutomationParams`: session_id: string; kind: 'goal' \| 'loop' \| 'heartbeat'; call: string; args?: unknown[] \| null; kwargs?: Record<string, unknown> \| null
  - result `SessionAutomationResult`: state: string \| null; value?: unknown \| null; error?: Record<string, string> \| null
- **`session.create`**
  - params `CanonicalCreateParams`: profile?: string \| null; request_id?: string \| null; source?: 'cli' \| 'tui' \| 'gui' \| 'acp' \| 'tool' \| 'oneshot'; cwd?: string \| null; row_cwd?: string \| null; model?: string \| null; provider?: string \| null; base_url?: string \| null; api_key?: string \| null; toolsets?: string[] \| null; reasoning?: string \| null; max_turns?: number \| string \| null; ignore_rules?: boolean; yolo?: boolean; safe_mode?: boolean; ignore_user_config?: boolean; skills?: string[] \| null; checkpoints?: boolean; accept_hooks?: boolean; pass_session_id?: boolean; editor?: Record<string, unknown> \| null …
  - result `CanonicalSnapshot`: session_id: string; stored_session_id: string; messages: TranscriptMessage[]; display_messages?: TranscriptMessage[] \| null; message_count: number; running: boolean; authority_epoch: number; replay_epoch: string; last_sequence: number; subscription_id: string; revision: number; execution_generation: number; pending: CanonicalPendingAdmission[]; prompts: CanonicalPendingPrompt[]; info: CanonicalSnapshotInfo; pending_connection?: ConnectionRequestPayload \| null; todo_state?: TodoState \| null; open_requests?: OpenRequestEntry[] \| null
- **`session.detach`** — Drop this connection's subscription; a stale subscription id answers detached=false. end_reason (snake_case): the viewer is closing the conversation; the last viewer leaving an idle local session ends its row with that reason.
  - params `SessionDetachParams`: session_id: string; subscription_id: string; end_reason?: string \| null
  - result `SessionDetachResult`: session_id: string; subscription_id: string; detached: boolean
- **`session.events.since`**
  - params `CanonicalEventsParams`: profile?: string \| null; session_id: string; last_sequence?: number; last_seen?: number; replay_epoch?: string \| null
  - result `CanonicalEventsResult`: events: CanonicalReplayEvent[]; latest_seq: number; last_sequence: number; epoch: string; replay_epoch: string; truncated: boolean; snapshot_required: boolean; count: number; open_requests?: OpenRequestEntry[] \| null
- **`session.export`** — The /save transcript rendered for the client to write (the gateway writes nothing).
  - params `SessionExportParams`: session_id: string; profile?: string \| null; format?: string; redact?: boolean
  - result `SessionExportResult`: format: string; filename: string; content: string
- **`session.info`** — Frozen launch policy projection of one local session.
  - params `CanonicalSessionInfoParams`: session_id: string
  - result `CanonicalSessionInfo`: provider?: string \| null; tools?: Record<string, string[]> \| null; skills?: Record<string, string[]> \| null; mcp_servers?: McpServerStatus[] \| null; version?: string \| null; release_date?: string \| null; update_behind?: unknown \| null; update_command?: string \| null; branch?: string \| null; project?: ProjectRef \| null; system_prompt?: string \| null; context_length?: number \| null; enabled_toolsets?: string[] \| null; tool_availability?: Record<string, unknown> \| null; tool_availability_warnings?: string[] \| null; unknown_toolsets?: string[] \| null; source: string; model?: string …
- **`session.interrupt`**
  - params `CanonicalControlParams`: profile?: string \| null; session_id: string; execution_generation: number
  - result `CanonicalSessionHandle`: ref: CanonicalSessionRef; instance_id: string; authority_epoch: number; revision: number; execution_generation: number; execution_state: 'idle' \| 'running' \| 'waiting' \| 'unknown' \| 'terminal'
- **`session.list`**
  - params `CanonicalListParams`: profile?: string \| null; scope?: 'live' \| 'stored'; limit?: number; title?: string \| null; include_hidden?: boolean
  - result `CanonicalListResult`: sessions: CanonicalSessionListRow[]; scope: 'stored' \| 'live'
- **`session.mutate`** — Revision-fenced, retry-idempotent session edit (rename, archive, sidebar, branch, delete, rewind, reset, model, reasoning, workspace, edit_policy, compress, import).
  - params `SessionMutateParams`: session_id: string; request_id: string; expected_revision: number; operation: string; payload: Record<string, unknown>; expected_generation?: number \| null
  - result `SessionMutateResult`: session_id: string; operation: string; revision?: number \| null
- **`session.prune`** — Local operator only: the owner deletes the ended sessions `hermes sessions prune` selected, skipping busy sessions and retained reset owners.
  - params `SessionPruneParams`: filters?: Record<string, unknown> \| null; never_active_days?: number \| null
  - result `SessionPruneResult`: deleted: number; routing_deleted?: number \| null; skipped?: number \| null
- **`session.redirect`**
  - params `CanonicalCorrectionParams`: profile?: string \| null; session_id: string; execution_generation: number; text: string
  - result `CanonicalCorrectionResult`: `CanonicalCorrectionAck \| CanonicalVoiceStopped`
- **`session.resume`**
  - params `CanonicalResumeParams`: profile?: string \| null; session_id?: string; title?: string \| null; latest?: 'cli' \| 'tui' \| null; workspace?: string \| null; source?: string \| null; editor?: Record<string, unknown> \| null; api_key?: string \| null; defer_history?: boolean; omit_messages?: boolean; cols?: number \| null; display_projection?: boolean
  - result `CanonicalSnapshot`: session_id: string; stored_session_id: string; messages: TranscriptMessage[]; display_messages?: TranscriptMessage[] \| null; message_count: number; running: boolean; authority_epoch: number; replay_epoch: string; last_sequence: number; subscription_id: string; revision: number; execution_generation: number; pending: CanonicalPendingAdmission[]; prompts: CanonicalPendingPrompt[]; info: CanonicalSnapshotInfo; pending_connection?: ConnectionRequestPayload \| null; todo_state?: TodoState \| null; open_requests?: OpenRequestEntry[] \| null
- **`session.stats`** — The session.usage dict on demand: what main's classic CLI read off its agent.
  - params `SessionStatsParams`: session_id: string
  - result `SessionStats`: input_tokens: number; output_tokens: number; cache_read_tokens: number; cache_write_tokens: number; prompt_tokens: number; completion_tokens: number; total_tokens: number; reasoning_tokens: number; api_calls: number; last_prompt_tokens: number; context_tokens: number; context_length?: number \| null; config_context_length?: number \| null; context_source?: string \| null; compression_count: number; avg_latency_s?: number \| null; tokens_per_s?: number \| null; last_turn_tokens: number; rate_limit?: Record<string, unknown> \| null; active_subagents: number; model: string; provider: string; fall …
- **`session.steer`**
  - params `CanonicalCorrectionParams`: profile?: string \| null; session_id: string; execution_generation: number; text: string
  - result `CanonicalCorrectionResult`: `CanonicalCorrectionAck \| CanonicalVoiceStopped`
- **`shell.exec`** — Ink ``!cmd`` / ``{!cmd}`` on the shared owner: one safe command for a local session, in its frozen launch cwd (``gateway/session_shell.py``).
  - params `CanonicalShellExecParams`: session_id: string; command: string; profile?: string \| null
  - result `ShellExecResult`: stdout: string; stderr: string; code: number
- **`worker.adopt`** — Adopt a registered execution after verifying the live producer claim.
  - params `WorkerScopeParams`: profile_id: string; session_id: string; execution_id: string; generation: number; pid: number; birth: number; secret: string
  - result `WorkerExecution`: execution_id: string; session_id: string; kind: string; owner_epoch: number; generation: number; status: string; last_sequence: number
- **`worker.persist`** — Sequence-fenced typed persistence write from an adopted worker.
  - params `WorkerPersistParams`: profile_id: string; session_id: string; execution_id: string; generation: number; pid: number; birth: number; secret: string; epoch: number; sequence: number; operation: string; payload: Record<string, unknown>
  - result `WorkerPersistResult`: `Record<string, unknown>`
- **`worker.register`** — Register a compute worker execution on an idle session.
  - params `WorkerRegisterParams`: profile_id: string; session_id: string; execution_id: string; generation: number; pid: number; birth: number; secret: string; kind: string
  - result `WorkerExecution`: execution_id: string; session_id: string; kind: string; owner_epoch: number; generation: number; status: string; last_sequence: number

### B.2 Sidecar-contract methods (on the gateway, the names section 1.6 lists as authority-served are answered by the authority with these shapes unless B.1 overrides them; other names reach the legacy sidecar through the fallback, except the remaining `session.*`/`prompt.*`/`message.*` names and the process-global verbs, which answer -32601)

254 entries.

- **`agents.list`** — Registry-wide background process summary for ``/agents``.
  - params `AgentsListParams`: profile?: string \| null
  - result `AgentsListResult`: processes?: AgentProcessRow[]
- **`approval.pending`** — Replay the approvals still waiting on this session (reconnect / polling).
  - params `ApprovalPendingParams`: session_id: string; profile?: string \| null
  - result `ApprovalPendingResult`: approvals: PendingApproval[]
- **`approval.received`** — Tell the backend the card is on screen, so its timeout clock starts.
  - params `ApprovalReceivedParams`: session_id: string; profile?: string \| null; request_id: string
  - result `ApprovalReceivedResult`: acknowledged: boolean
- **`approval.respond`** — Deliver the user's decision on a dangerous command (falls back to durable identity on a stale sid).
  - params `ApprovalRespondParams`: session_id: string; profile?: string \| null; choice?: string \| null; all?: boolean \| null; request_id?: string \| null
  - result `ApprovalRespondResult`: resolved: number
- **`billing.auto_reload`** — Enable/disable auto top-up with its threshold and reload amount (billing:manage).
  - params `BillingAutoReloadParams`: profile?: string \| null; enabled?: boolean; threshold?: number \| string \| null; top_up_amount?: number \| string \| null
  - result `BillingMutationResult`: ok: boolean; error?: string \| null; message?: string \| null; portal_url?: string \| null; retry_after?: number \| null; payload?: Record<string, unknown> \| null; actor?: string \| null; code?: string \| null; recovery?: string \| null
- **`billing.charge`** — Start a one-off top-up charge (billing:manage, idempotent).
  - params `BillingChargeParams`: profile?: string \| null; amount_usd?: number \| string \| null; idempotency_key?: string \| null
  - result `BillingChargeResult`: ok: boolean; error?: string \| null; message?: string \| null; portal_url?: string \| null; retry_after?: number \| null; payload?: Record<string, unknown> \| null; actor?: string \| null; code?: string \| null; recovery?: string \| null; charge_id?: string \| null; idempotency_key?: string \| null
- **`billing.charge_status`** — Poll one charge by id.
  - params `BillingChargeStatusParams`: profile?: string \| null; charge_id?: string \| null
  - result `BillingChargeStatusResult`: ok: boolean; error?: string \| null; message?: string \| null; portal_url?: string \| null; retry_after?: number \| null; payload?: Record<string, unknown> \| null; actor?: string \| null; code?: string \| null; recovery?: string \| null; status?: string \| null; amount_usd?: string \| number \| null; settled_at?: string \| null; reason?: string \| null
- **`billing.keep_going_on_credits`** — Window plans: turn 'Keep going on credits' on/off (billing:manage).
  - params `BillingKeepGoingOnCreditsParams`: profile?: string \| null; enabled?: boolean
  - result `BillingMutationResult`: ok: boolean; error?: string \| null; message?: string \| null; portal_url?: string \| null; retry_after?: number \| null; payload?: Record<string, unknown> \| null; actor?: string \| null; code?: string \| null; recovery?: string \| null
- **`billing.state`** — Read-only billing view (no scope); the Nous free tier is answered locally without a portal call.
  - params `ProfileParams`: profile?: string \| null
  - result `BillingStateResult`: ok: boolean; logged_in: boolean; free_tier_account?: boolean; free_tier_model?: string \| null; org_name?: string \| null; org_slug?: string \| null; role?: string \| null; is_admin?: boolean \| null; can_change_plan?: boolean \| null; can_charge?: boolean \| null; balance_usd?: string \| null; balance_display?: string \| null; cli_billing_enabled?: boolean \| null; charge_presets?: string[] \| null; charge_presets_display?: string[] \| null; min_usd?: string \| null; max_usd?: string \| null; card?: BillingCardInfo \| null; payment_method?: BillingPaymentMethod \| null; monthly_cap?: BillingM …
- **`billing.step_up`** — Run the billing:manage device flow; the URL/code arrive via billing.step_up.verification.
  - params `BillingStepUpParams`: profile?: string \| null; session_id?: string \| null
  - result `BillingStepUpResult`: ok: boolean; error?: string \| null; message?: string \| null; portal_url?: string \| null; retry_after?: number \| null; payload?: Record<string, unknown> \| null; actor?: string \| null; code?: string \| null; recovery?: string \| null; granted?: boolean \| null
- **`bot_relay.deliver`** — Deliver a relayed DM into a Bot Chat on this gateway and return the one-turn reply (blocking).
  - params `BotRelayDeliverParams`: profile: string; message: string; from_profile?: string \| null; from_handle?: string \| null; from_connection?: string \| null
  - result `BotRelayDeliverResult`: reply: string
- **`bot_relay.outbox.drain`** — Atomically claim every pending cross-connection envelope queued on this gateway.
  - params `BotRelayOutboxDrainParams`: profile?: string \| null
  - result `BotRelayOutboxDrainResult`: envelopes: RelayEnvelope[]
- **`bot_relay.reply`** — Write a relayed reply and/or typed error for an envelope so the sender-side waiter resolves.
  - params `BotRelayReplyParams`: profile?: string \| null; id: string; reply?: string \| null; error?: string \| null; reason?: string \| null
  - result `OkResult`: ok?: boolean
- **`bot_relay.roster.sync`** — Replace this gateway's view of agents on other connections; answers the accepted row count.
  - params `BotRelayRosterSyncParams`: profile?: string \| null; agents?: RelayAgentRow[] \| null
  - result `BotRelayRosterSyncResult`: count: number
- **`browser.manage`** — Inspect, attach to, or drop the CDP browser the tools use; ``messages`` narrate a connect.
  - params `BrowserManageParams`: action?: BrowserAction; url?: string \| null; session_id?: string \| null; profile?: string \| null
  - result `BrowserManageResult`: connected: boolean; url?: string \| null; messages?: string[] \| null
- **`clarify.lock`** — Lock one answer of a batch clarify request (editable until every question is locked).
  - params `ClarifyLockParams`: request_id: string; question_id: string; answer?: unknown; profile?: string \| null
  - result `ClarifyLockResult`: status: ClarifyLockStatus; remaining?: string[] \| null
- **`cli.exec`** — Run ``hermes <argv>`` non-interactively and capture its output; ``blocked`` explains a refusal.
  - params `CliExecParams`: argv: string[]; timeout?: number \| null; profile?: string \| null
  - result `CliExecResult`: blocked: boolean; code: number; output: string; hint?: string \| null
- **`client.capabilities`** — What the calling client handles, sent once per connection (after gateway.ready); returns the server→client request methods this backend may send.
  - params `ClientCapabilitiesParams`: server_requests?: boolean; desktop_local?: boolean
  - result `ClientCapabilitiesResult`: server_requests: string[]; declines_not_shown?: boolean
- **`clipboard.paste`** — Save the host clipboard image into the session and queue it for the next turn.
  - params `ClipboardPasteParams`: session_id: string; profile?: string \| null
  - result `AttachedImageResult`: name?: string \| null; width?: number \| null; height?: number \| null; token_estimate?: number \| null; attached: boolean; path?: string \| null; count?: number \| null; remainder?: string \| null; text?: string \| null; bytes?: number \| null; message?: string \| null
- **`command.dispatch`** — Run a quick/plugin/bundle/skill/built-in slash command and answer a structured directive.
  - params `CommandDispatchParams`: name: string; arg?: string \| null; session_id?: string \| null; profile?: string \| null
  - result `CommandDispatchResult`: type: DispatchType; output?: string \| null; target?: string \| null; message?: string \| null; notice?: string \| null; display?: string \| null; name?: string \| null; status?: string \| null
- **`command.resolve`** — Canonical registry command for a name or alias.
  - params `CommandResolveParams`: name?: string \| null; profile?: string \| null
  - result `CommandResolveResult`: canonical: string; description: string; category: string
- **`commands.catalog`** — Categorized slash metadata (registry, quick, plugin, skill) for completion menus.
  - params `CommandsCatalogParams`: session_id?: string \| null; profile?: string \| null
  - result `CommandsCatalogResult`: pairs?: string[][]; sub?: Record<string, string[]>; canon?: Record<string, string>; commands?: Record<string, CommandCatalogMeta>; categories?: CommandCategory[]; skills?: Record<string, SkillCatalogEntry>; skill_count?: number; warning?: string; plugin_discovery_error?: string; plugin_commands?: Record<string, string>
- **`complete.path`** — Path / @-reference completions for the composer (files, folders, profiles, plugin providers).
  - params `CompletePathParams`: profile?: string \| null; word?: string \| null; cwd?: string \| null; session_id?: string \| null
  - result `CompletionItemsResult`: items?: CompletionItem[]
- **`complete.slash`** — Ranked slash-command / skill completions for a ``/`` token.
  - params `CompleteSlashParams`: text?: string \| null; session_id?: string \| null; profile?: string \| null
  - result `CompleteSlashResult`: items?: CompletionItem[]; replace_from?: number \| null
- **`config.get`** — Read one normalised config value (or the whole effective config) the way the UIs render it.
  - params `ConfigGetParams`: profile?: string \| null; key: string; cwd?: string \| null; session_id?: string \| null
  - result `ConfigGetResult`: value?: string \| null; display?: string \| null; tool_progress?: string \| null; model?: string \| null; provider?: string \| null; providers?: ConfigProviderRef[] \| null; home?: string \| null; cwd?: string \| null; branch?: string \| null; config?: Record<string, unknown> \| null; prompt?: string \| null; mtime?: number \| null; mcp_rev?: string \| null
- **`config.set`** — Change one config key (persisted or session-scoped) and read back the normalised value.
  - params `ConfigSetParams`: profile?: string \| null; key: string; value?: unknown; session_id?: string \| null; scope?: string \| null; confirm_expensive_model?: boolean
  - result `ConfigSetResult`: key: string; value?: string \| boolean \| null; warning?: string \| null; confirm_required?: boolean \| null; confirm_message?: string \| null; scope?: string \| null; deferred?: boolean \| null; tool_progress?: string \| null; cwd?: string \| null; branch?: string \| null; history_reset?: boolean \| null; info?: SessionLiveInfo \| null
- **`config.show`** — Masked, display-ready config summary (model / agent / environment rows).
  - params `ConfigShowParams`: profile?: string \| null
  - result `ConfigShowResult`: sections?: ConfigSection[]
- **`connection.respond`** — Per-target outcomes from the card, and an optional Continue.
  - params `ConnectionRespondParams`: profile?: string \| null; owner: SessionOwner \| AccountOwner; op_id: string; result: ConnectionAnswer
  - result `ConnectionRespondResult`: status: 'ok'; settled: boolean
- **`connectors.accounts`** — The scoped member's hosted connector accounts, optionally filtered by connector slug.
  - params `ConnectorAccountsParams`: profile?: string \| null; connector?: string \| null
  - result `ConnectorAccountsResult`: accounts: ConnectorAccountRow[]
- **`connectors.accounts.remove`** — Remove one hosted connector account owned by the scoped member.
  - params `ConnectorAccountsRemoveParams`: profile?: string \| null; connection_id: string
  - result `ConnectorAccountsRemoveResult`: connection_id: string; connector: string; status: 'removed'
- **`connectors.accounts.rename`** — Name or rename one hosted connector account owned by the scoped member.
  - params `ConnectorAccountsRenameParams`: profile?: string \| null; connection_id: string; alias: string
  - result `ConnectorAccountRow`: connection_id: string; connector: string; status: ConnectorAccountStatus; status_reason?: string \| null; label: string; alias?: string \| null; active: boolean; disabled?: boolean; created_at: string; updated_at: string
- **`connectors.catalog`** — The hosted connector catalog available to the scoped member.
  - params `ProfileParams`: profile?: string \| null
  - result `ConnectorsCatalogResult`: connectors: ConnectorCatalogRow[]
- **`connectors.connect`** — Start or re-initiate authorization for named connectors on a session or account operation.
  - params `ConnectorsConnectParams`: profile?: string \| null; owner: SessionOwner \| AccountOwner; connectors: string[]; reconnect?: boolean; alias?: string \| null; connection_id?: string \| null
  - result `ConnectorsConnectResult`: op_id: string; seq: number; deadline_at: number; settled: boolean; settled_at?: number \| null; settled_by?: ConnectionSettleReason \| null; targets: ConnectionOperationTarget[]; status?: 'initiated' \| 'settled' \| null; note?: string \| null
- **`connectors.list`** — Connector catalog + connection state for one session or profile account owner.
  - params `ConnectorsListParams`: profile?: string \| null; owner: SessionOwner \| AccountOwner
  - result `ConnectorsListResult`: available: boolean; connectors: ConnectorRow[]
- **`connectors.operation.status`** — The current snapshot of one open session or account operation.
  - params `ConnectionOperationParams`: profile?: string \| null; owner: SessionOwner \| AccountOwner; op_id: string
  - result `ConnectionOperationStatus`: op_id: string; seq: number; deadline_at: number; settled: boolean; settled_at?: number \| null; settled_by?: ConnectionSettleReason \| null; targets: ConnectionOperationTarget[]
- **`connectors.operation.wake`** — The browser leg came back (hermes://connections/done): read the accounts now, not at the next tick.
  - params `ConnectionOperationParams`: profile?: string \| null; owner: SessionOwner \| AccountOwner; op_id: string
  - result `ConnectionWakeResult`: status: 'ok'
- **`connectors.policy.get`** — Policy layers for the scoped member, from organization to member scope.
  - params `ProfileParams`: profile?: string \| null
  - result `ConnectorPolicyGetResult`: layers: ConnectorPolicyLayer[]; effective: ConnectorPolicyEffectiveUnrestricted \| ConnectorPolicyEffec
- **`connectors.policy.set`** — Apply one scoped member connector or tool-list policy change.
  - params `ConnectorPolicySetParams`: profile?: string \| null; change: ToolsChange \| ConnectorChange; expected_revision: string
  - result `ConnectorPolicySetResult`: revision: string; effective: ConnectorPolicyEffectiveUnrestricted \| ConnectorPolicyEffec
- **`connectors.tools`** — The scoped profile's cached or current tool list for one connector.
  - params `ConnectorToolsParams`: profile?: string \| null; slug: string; refresh?: boolean
  - result `ConnectorToolsResult`: connector: string; toolkit_version: string; etag: string; fetched_at: number; source: ConnectorToolsSource; stale: boolean; tools: ConnectorToolRow[]
- **`cron.manage`** — List/add/remove/pause/resume cron jobs in the (optionally profile-scoped) cron store.
  - params `CronManageParams`: action?: CronAction; name?: string \| null; include_disabled?: boolean \| string \| null; schedule?: string \| null; prompt?: string \| null; repeat?: number \| string \| null; continuity?: boolean \| string \| null; deliver?: string \| null; profile?: string \| null
  - result `CronManageResult`: success?: boolean \| null; error?: string \| null; count?: number \| null; jobs?: CronJobRow[] \| null; scoped?: string \| null; gateway_running?: boolean \| null; warning?: string \| null; job_id?: string \| null; name?: string \| null; skill?: string \| null; skills?: string[] \| null; schedule?: string \| null; repeat?: number \| string \| null; deliver?: string \| null; next_run_at?: string \| null; job?: CronJobRow \| null; message?: string \| null; guidance?: unknown \| null; removed_job?: CronRemovedJob \| null
- **`delegation.pause`** — Block/unblock NEW spawns globally (active children keep running); returns the new state.
  - params `DelegationPauseParams`: profile?: string \| null; paused?: boolean
  - result `DelegationPauseResult`: paused: boolean
- **`delegation.status`** — Running subagent tree plus the spawn pause flag and limits.
  - params `ProfileParams`: profile?: string \| null
  - result `DelegationStatusResult`: active: ActiveSubagent[]; paused: boolean; max_spawn_depth: number; max_concurrent_children: number
- **`diagnostics.share_nous`** — Upload a force-redacted debug bundle to Nous-internal diagnostics storage.
  - params `DiagnosticsShareNousParams`: error_context?: string \| null; extra_files?: Record<string, string> \| null; log_lines?: number \| null
  - result `DiagnosticsShareNousResult`: ok: boolean; view_url?: string \| null; upload_id?: string \| null; expires_at?: string \| null; error?: string \| null
- **`display.install`** — Run the distro package install on the gateway host; progress streams as display.install.log/.done.
  - params `ProfileParams`: profile?: string \| null
  - result `DisplayInstallResult`: started: boolean; command?: string \| null; profile_key: string
- **`display.lease.acquire`** — Take over: the human named by a viewer id this connection minted controls the screen.
  - params `DisplayLeaseAcquireParams`: profile?: string \| null; viewer_id: string; reason?: string \| null
  - result `DisplayLeaseResult`: lease: DisplayLease
- **`display.lease.release`** — Hand back. Without a viewer id the release is refused while a human holds unless force.
  - params `DisplayLeaseReleaseParams`: profile?: string \| null; viewer_id?: string \| null; force?: boolean \| null
  - result `DisplayLeaseResult`: lease: DisplayLease
- **`display.observe`** — Mint a single-use ticket for /api/display/ws and the server-minted viewer id for this connection.
  - params `DisplayObserveParams`: profile?: string \| null; viewer_id?: string \| null
  - result `DisplayObserveResult`: profile: string; supported: boolean; installed: boolean; missing: string[]; running: boolean; pid?: number \| null; display?: string \| null; socket?: string \| null; geometry: string; install_command?: string \| null; browser?: string \| null; blocker?: string \| null; memory_available_mb?: number \| null; memory_limit_mb?: number \| null; placement?: string; image_switch?: DisplayImageSwitch \| null; lease: DisplayLease; profile_key: string; ticket: string; path: string; viewer_id: string
- **`display.start`** — Start this profile's Xvnc + Xfce (idempotent); blocks until the display is published.
  - params `ProfileParams`: profile?: string \| null
  - result `DisplayStatus`: profile: string; supported: boolean; installed: boolean; missing: string[]; running: boolean; pid?: number \| null; display?: string \| null; socket?: string \| null; geometry: string; install_command?: string \| null; browser?: string \| null; blocker?: string \| null; memory_available_mb?: number \| null; memory_limit_mb?: number \| null; placement?: string; image_switch?: DisplayImageSwitch \| null; lease: DisplayLease; profile_key: string
- **`display.status`** — Runtime + lease snapshot for this profile's screen.
  - params `ProfileParams`: profile?: string \| null
  - result `DisplayStatus`: profile: string; supported: boolean; installed: boolean; missing: string[]; running: boolean; pid?: number \| null; display?: string \| null; socket?: string \| null; geometry: string; install_command?: string \| null; browser?: string \| null; blocker?: string \| null; memory_available_mb?: number \| null; memory_limit_mb?: number \| null; placement?: string; image_switch?: DisplayImageSwitch \| null; lease: DisplayLease; profile_key: string
- **`display.stop`** — Stop the screen. Refused (5300, code viewer_mismatch) while a human holds unless force.
  - params `DisplayStopParams`: profile?: string \| null; force?: boolean \| null
  - result `DisplayStopResult`: profile: string; supported: boolean; installed: boolean; missing: string[]; running: boolean; pid?: number \| null; display?: string \| null; socket?: string \| null; geometry: string; install_command?: string \| null; browser?: string \| null; blocker?: string \| null; memory_available_mb?: number \| null; memory_limit_mb?: number \| null; placement?: string; image_switch?: DisplayImageSwitch \| null; lease: DisplayLease; profile_key: string; stopped: boolean
- **`display.switchSandboxImage`** — Decide the pending default sandbox image switch for this profile; refused when none is pending.
  - params `DisplaySwitchSandboxImageParams`: profile?: string \| null; approve?: boolean
  - result `DisplaySwitchSandboxImageResult`: profile: string; supported: boolean; installed: boolean; missing: string[]; running: boolean; pid?: number \| null; display?: string \| null; socket?: string \| null; geometry: string; install_command?: string \| null; browser?: string \| null; blocker?: string \| null; memory_available_mb?: number \| null; memory_limit_mb?: number \| null; placement?: string; image_switch?: DisplayImageSwitch \| null; lease: DisplayLease; profile_key: string; docker_image: string
- **`display.thumbnail`** — One JPEG grab of the bot's screen; read-only, never changes the lease.
  - params `ProfileParams`: profile?: string \| null
  - result `DisplayThumbnailResult`: data_url?: string \| null; suppressed?: string \| null
- **`file.attach`** — Stage a non-image file into the session workspace and hand back its @file: ref.
  - params `FileAttachParams`: session_id: string; profile?: string \| null; path?: string \| null; data_url?: string \| null; name?: string \| null; staged_upload?: StagedFileUpload \| null
  - result `FileAttachResult`: attached: boolean; name: string; path: string; ref_path: string; ref_text: string; uploaded: boolean
- **`free_tier.ack_notice`** — Mark the one-time availability notice as shown on the free-tier identity.
  - params `ProfileParams`: profile?: string \| null
  - result `FreeTierAckNoticeResult`: acked: boolean
- **`free_tier.challenge_result`** — Report a browser window outcome for the matching pending attempt; mint remains authoritative.
  - params `FreeTierChallengeResultParams`: profile?: string \| null; url: string; attempt?: number; outcome: 'done' \| 'failed' \| 'closed' \| 'timeout' \| 'refused' \| 
  - result `FreeTierChallengeResult`: accepted: boolean
- **`free_tier.claim_nudge`** — Claim the due sign-in offer; true for exactly one caller each time an offer comes due.
  - params `FreeTierClaimNudgeParams`: profile?: string \| null; kind?: 'trial' \| 'connector'
  - result `FreeTierClaimNudgeResult`: claimed: boolean
- **`free_tier.provision`** — Explicit retry of the free-tier identity mint when the boot bootstrap could not create it.
  - params `ProfileParams`: profile?: string \| null
  - result `FreeTierProvisionResult`: has_guest: boolean; enabled: boolean; error?: string \| null; error_code?: string \| null; retryable?: boolean \| null; retry_after?: number \| null
- **`free_tier.status`** — Pure read of the focused profile's free-tier identity state (no network, no side effects).
  - params `ProfileParams`: profile?: string \| null
  - result `FreeTierStatusResult`: has_guest: boolean; enabled: boolean; available: boolean; notice_pending: boolean; model: string; label: string; error?: string \| null; error_code?: string \| null; retryable?: boolean \| null; retry_after?: number \| null; challenge?: FreeTierChallengePayload \| null; nudge_due_in?: number \| null; connector_nudge_due_in?: number \| null
- **`gateway.capabilities`** — What THIS build enforces (a client withholds a feature unless advertised).
  - params `PingParams`: `Record<string, never>`
  - result `GatewayCapabilitiesResult`: per_session_exclusive_submit: boolean
- **`groups.approve`** — Resolve one exact pending approval raised by a local or peer room member.
  - params `GroupsApproveParams`: profile?: string \| null; room_id: string; member_id: string; task_id: string; execution_generation: number; choice: ApprovalChoice; request_id: string
  - result `GroupsApproveResult`: approved?: boolean; result: Record<string, unknown>
- **`groups.capabilities`** — Describe the hosted-room protocol implemented by this gateway.
  - params `GroupsCapabilitiesParams`: profile?: string \| null
  - result `GroupsCapabilitiesResult`: protocol_version: number; driver: boolean; persistent_process: boolean; authority_gateway_id: string; room_link: RoomLinkStatus; features: string[]; methods: string[]; max_log_limit: number; seatable_profiles?: string[] \| null
- **`groups.create`** — Create a hosted room idempotently; authority is this gateway's stable install identity.
  - params `GroupsCreateParams`: profile?: string \| null; room_id: string; name: string; members: RoomMemberInput[]; authority_gateway_id?: string \| null
  - result `GroupsCreateResult`: room: Room
- **`groups.demote`** — Fence this gateway's stale room authority against a proven newer epoch.
  - params `GroupsDemoteParams`: profile?: string \| null; room_id: string; observed_gateway_id: string; observed_epoch: number
  - result `GroupsDemoteResult`: room_id: string; authority_gateway_id: string; authority_epoch: number; idempotent: boolean
- **`groups.disband`** — Permanently tombstone a hosted room id after stopping its work and revoking peer routes.
  - params `GroupsDisbandParams`: profile?: string \| null; room_id: string; cancel_id?: string \| null
  - result `GroupsDisbandResult`: tombstone: RoomTombstone
- **`groups.list`** — List rooms hosted by this gateway, most recently changed first.
  - params `GroupsListParams`: profile?: string \| null; include_disbanded?: boolean \| null; limit?: number \| null; offset?: number \| null
  - result `GroupsListResult`: rooms: Room[]; next_offset?: number \| null
- **`groups.log`** — A monotonic room-log delta after since_seq, bounded by count and page bytes.
  - params `GroupsLogParams`: profile?: string \| null; room_id: string; since_seq?: number \| null; limit?: number \| null; include_disbanded?: boolean \| null
  - result `GroupsLogResult`: events: RoomEvent[]; cursor: number; latest_seq: number; has_more: boolean; authority: RoomAuthority
- **`groups.peer.invite`** — Mint one target-issued room/profile grant for a prospective room home.
  - params `GroupsPeerInviteParams`: profile?: string \| null; room_id?: string \| null; home_install_id?: string \| null; authority_gateway_id?: string \| null; authority_epoch?: number \| null; member_id?: string \| null; grant_id?: string \| null; ttl_seconds?: number \| null
  - result `GroupsPeerInviteResult`: grant: string; target_profile: string; catalog: RoomLinkCatalog; endpoint: RoomLinkEndpoint
- **`groups.peer.register`** — Register and probe one scoped peer route on the room home.
  - params `GroupsPeerRegisterParams`: profile?: string \| null; room_id: string; member_id: string; target_url: string; target_profile: string; grant: string; catalog: Record<string, unknown>; cancellation_scope_id?: string \| null; trace_id?: string \| null
  - result `GroupsPeerRegisterResult`: registered?: boolean; mode: string; transport_security: string; target_install_id: string; target_profile: string
- **`groups.peer.revoke`** — Revoke one target-issued grant using its exact profile scope.
  - params `GroupsPeerRevokeParams`: profile?: string \| null; grant: string
  - result `GroupsPeerRevokeResult`: revoked?: boolean
- **`groups.promote`** — Continue a replicated room on this gateway at epoch + 1; requires confirm=true.
  - params `GroupsPromoteParams`: profile?: string \| null; room_id: string; confirm?: boolean \| null; reason?: string \| null
  - result `GroupsPromoteResult`: room_id: string; authority_gateway_id: string; authority_epoch: number; previous_gateway_id: string; previous_epoch: number; claim_seq: number; latest_seq: number
- **`groups.rename`** — Rename one hosted room atomically with its replay event.
  - params `GroupsRenameParams`: profile?: string \| null; room_id: string; event_id: string; name: string
  - result `GroupsRenameResult`: room: Room
- **`groups.replica_state`** — The local replica's coverage and authority lineage for one room.
  - params `GroupsReplicaStateParams`: profile?: string \| null; room_id: string
  - result `GroupsReplicaStateResult`: room_id: string; name: string; members: RoomMember[]; authority: RoomAuthority; last_seq: number; latest_seq: number; event_bytes: number; created_at: number; updated_at: number
- **`groups.replicate`** — Persist one authority-stamped replay page into the local replica store; idempotent.
  - params `GroupsReplicateParams`: profile?: string \| null; room_id: string; room_name: string; members: RoomMemberInput[]; page: Record<string, unknown>
  - result `GroupsReplicateResult`: room_id: string; stored_seq: number; ingested: number; authority: RoomAuthority; caught_up: boolean
- **`groups.retry`** — Retry one indeterminate room task after explicit user confirmation.
  - params `GroupsRetryParams`: profile?: string \| null; room_id: string; task_id: string; member_id?: string \| null; execution_generation?: number \| null
  - result `GroupsRetryResult`: retried?: boolean; task: RoomTaskReceipt
- **`groups.send`** — Append one inert message.user event idempotently; the actor is server-owned.
  - params `GroupsSendParams`: profile?: string \| null; room_id: string; event_id?: string \| null; payload: Record<string, unknown>
  - result `GroupsSendResult`: event: RoomEvent; client_event_id?: string \| null; accepted?: boolean; driver_started?: boolean
- **`groups.state`** — One hosted room's replay cursor and fenced authority state, plus live driver status.
  - params `GroupsStateParams`: profile?: string \| null; room_id: string; include_disbanded?: boolean \| null
  - result `GroupsStateResult`: room: Room; driver_status?: RoomDriverStatus \| null
- **`groups.stop`** — Durably cancel queued or running work for one hosted room.
  - params `GroupsStopParams`: profile?: string \| null; room_id: string; cancel_id?: string \| null
  - result `GroupsStopResult`: cancelled: number
- **`handoff.fail`** — Fail a not-yet-claimed handoff (client poll timeout); CAS against the watcher.
  - params `HandoffFailParams`: session_id: string; profile?: string \| null; error?: string \| null
  - result `HandoffFailResult`: failed: boolean; state: string
- **`handoff.request`** — Queue a handoff to a messaging platform's home channel; the gateway watcher claims it.
  - params `HandoffRequestParams`: session_id: string; profile?: string \| null; platform: string
  - result `HandoffRequestResult`: queued: boolean; session_key: string; platform: string; home_name: string
- **`handoff.state`** — Poll the handoff row for this session.
  - params `SessionParams`: session_id: string; profile?: string \| null
  - result `HandoffStateResult`: state: string; platform: string; error: string
- **`i18n.catalog`** — Pack + overlay messages for one language and surface; the renderer merges them over its bundled catalog.
  - params `I18nCatalogParams`: profile?: string \| null; lang: string; surface?: LocaleSurface
  - result `I18nCatalogResult`: lang: string; surface: LocaleSurface; messages: Record<string, string>
- **`i18n.languages`** — Every language some layer supplies (bundled ∪ user overlay ∪ plugin packs), en first.
  - params `ProfileParams`: profile?: string \| null
  - result `I18nLanguagesResult`: languages: LanguageOption[]
- **`image.attach`** — Queue a gateway-visible image file for the next turn.
  - params `ImageAttachParams`: session_id: string; profile?: string \| null; path: string
  - result `AttachedImageResult`: name?: string \| null; width?: number \| null; height?: number \| null; token_estimate?: number \| null; attached: boolean; path?: string \| null; count?: number \| null; remainder?: string \| null; text?: string \| null; bytes?: number \| null; message?: string \| null
- **`image.attach_bytes`** — Queue an image uploaded as base64 (remote client); reply mirrors image.attach.
  - params `ImageAttachBytesParams`: session_id: string; profile?: string \| null; content_base64?: string \| null; data?: string \| null; filename?: string \| null; ext?: string \| null
  - result `AttachedImageResult`: name?: string \| null; width?: number \| null; height?: number \| null; token_estimate?: number \| null; attached: boolean; path?: string \| null; count?: number \| null; remainder?: string \| null; text?: string \| null; bytes?: number \| null; message?: string \| null
- **`image.detach`** — Drop a queued image before the turn is sent.
  - params `ImageDetachParams`: session_id: string; profile?: string \| null; path: string
  - result `ImageDetachResult`: detached: boolean; count: number
- **`image.generate`** — Generate an image through the tool's provider dispatcher and hand the renderer a data URL.
  - params `ImageGenerateParams`: prompt?: string \| null; aspect_ratio?: string \| null; probe?: unknown \| null; max_bytes?: number \| null
  - result `ImageGenerateResult`: available: boolean; success?: boolean \| null; image?: string \| null; image_data?: string \| null; error?: string \| null
- **`input.detect_drop`** — Recognise a terminal file drop pasted into the composer and turn it into an attachment.
  - params `InputDetectDropParams`: session_id: string; profile?: string \| null; text?: string \| null
  - result `InputDetectDropResult`: name?: string \| null; width?: number \| null; height?: number \| null; token_estimate?: number \| null; matched: boolean; is_image?: boolean \| null; path?: string \| null; count?: number \| null; text?: string \| null
- **`insights.get`** — Session/message counts over the last ``days`` for the (optionally scoped) profile store.
  - params `InsightsGetParams`: days?: number \| null; profile?: string \| null
  - result `InsightsGetResult`: days: number; sessions: number; messages: number
- **`learning.delete`** — Archive a skill (restorable via curator) or remove a memory chunk.
  - params `LearningNodeParams`: id?: string \| null
  - result `LearningMutationResult`: ok: boolean; message?: string \| null
- **`learning.detail`** — Node content (SKILL.md or memory chunk) for an edit prefill.
  - params `LearningNodeParams`: id?: string \| null
  - result `LearningDetailResult`: ok: boolean; message?: string \| null; kind?: string \| null; id?: string \| null; label?: string \| null; content?: string \| null
- **`learning.edit`** — Rewrite a node's content (SKILL.md or memory chunk).
  - params `LearningEditParams`: id?: string \| null; content?: string \| null
  - result `LearningMutationResult`: ok: boolean; message?: string \| null
- **`learning.frames`** — Pre-render the /journey timeline (frames + legend/summary) so the TUI walks it locally.
  - params `LearningFramesParams`: cols?: number \| null; rows?: number \| null; frames?: number \| null
  - result `LearningFramesResult`: frames: LearningFrame[]; legend: LearningLegendItem[]; categories: LearningLegendItem[]; buckets: LearningBucketRow[]; summary: string[]; axis: LearningAxis; count: number; cols: number; rows: number
- **`llm.oneshot`** — Stateless one-shot LLM completion (titles, ideas) on the session's or the task backend.
  - params `LlmOneshotParams`: profile?: string \| null; template?: string \| null; instructions?: string \| null; input?: string \| null; variables?: Record<string, unknown> \| null; task?: string \| null; temperature?: number \| null; max_tokens?: number \| null; session_id?: string \| null
  - result `LlmOneshotResult`: text: string
- **`machine.facts`** — Facts about the machine running this backend and its OS account, for the desktop first run.
  - params `Params`: `Record<string, never>`
  - result `MachineFactsResult`: machine: MachineInfo; machine_kind: string; has_nvidia_gpu: boolean; nvidia_platform?: 'rtx_spark' \| 'dgx_spark' \| 'discrete' \| null; locale?: string \| null
- **`mcp.catalog`** — Curated MCP presets with per-profile installed/enabled state and the env keys each needs.
  - params `ProfileParams`: profile?: string \| null
  - result `McpCatalogResult`: servers: McpCatalogEntry[]
- **`mcp.servers.add`** — Add a server to the profile's config from a catalog preset and/or an explicit config.
  - params `McpServersAddParams`: profile?: string \| null; name: string; preset?: string \| null; config?: Record<string, unknown> \| null; bearer_token?: string \| null
  - result `McpServersAddResult`: ok: boolean; name: string; server: McpServerSummary
- **`mcp.servers.list`** — Configured MCP servers for the (scoped) profile, secrets redacted to env-key names.
  - params `ProfileParams`: profile?: string \| null
  - result `McpServersListResult`: servers: McpServerSummary[]
- **`mcp.servers.oauth.callback`** — Relay a client-captured redirect into a client_redirect_uri flow.
  - params `McpOauthCallbackParams`: profile?: string \| null; name: string; session_id: string; code?: string \| null; state?: string \| null; error?: string \| null; iss?: string \| null
  - result `McpOauthCallbackResult`: ok: boolean; session_id?: string \| null; error_message?: string \| null
- **`mcp.servers.oauth.cancel`** — Cancel a flow owned by the resolved profile, waking its callback worker.
  - params `McpOauthFlowParams`: profile?: string \| null; name: string; session_id: string
  - result `McpOauthCancelResult`: ok: boolean; status?: string \| null; error_message?: string \| null
- **`mcp.servers.oauth.poll`** — Poll a flow; approved persists tokens for the profile and returns the probed tools.
  - params `McpOauthFlowParams`: profile?: string \| null; name: string; session_id: string
  - result `McpOauthPollResult`: ok: boolean; status: McpOauthPollStatus; session_id?: string \| null; error_message?: string \| null; auth_url?: string \| null; tools?: McpProbeTool[] \| null
- **`mcp.servers.oauth.start`** — Begin a PKCE OAuth flow; the client opens auth_url and polls mcp.servers.oauth.poll.
  - params `McpOauthStartParams`: profile?: string \| null; name: string; client_redirect_uri?: string \| null
  - result `McpOauthStartResult`: ok: boolean; session_id: string; auth_url: string; flow: string
- **`mcp.servers.remove`** — Drop a server from the profile's config.yaml.
  - params `McpServerNameParams`: profile?: string \| null; name: string
  - result `McpServersRemoveResult`: ok: boolean; removed: boolean
- **`mcp.servers.set_api_key`** — Store a credential in the profile's .env and reference it from the server config (header or env).
  - params `McpServersSetApiKeyParams`: profile?: string \| null; name: string; value: string; env_var?: string \| null
  - result `McpServersSetApiKeyResult`: ok: boolean; name: string; env_var: string; server: McpServerSummary
- **`mcp.servers.status`** — Cached runtime state per configured server; never connects, probes, or starts auth.
  - params `ProfileParams`: profile?: string \| null
  - result `McpServersStatusResult`: servers: McpServerRuntimeRow[]; checked_at: number
- **`mcp.servers.test`** — Connect, list tools, disconnect — an OAuth server with no token on disk is reported as not ok.
  - params `McpServerNameParams`: profile?: string \| null; name: string
  - result `McpServersTestResult`: ok: boolean; tools: McpProbeTool[]; error?: string \| null; prompts?: number \| null; resources?: number \| null; oauth_needed: boolean; oauth_tokens_present?: boolean \| null
- **`message.react`** — Set/clear one author's emoji reaction on a message; returns the row's full reaction list.
  - params `MessageReactParams`: session_id: string; profile?: string \| null; row_id?: number \| null; newest_role?: string \| null; emoji?: string \| null; author?: ReactionAuthor \| null
  - result `MessageReactResult`: row_id: number; reactions: MessageReaction[]
- **`model.disconnect`** — Remove every credential (env keys and OAuth state) for a provider.
  - params `ModelDisconnectParams`: slug: string; session_id?: string \| null
  - result `ModelDisconnectResult`: slug: string; name: string; disconnected: boolean
- **`model.options`** — Provider/model inventory for the picker, layered over the session's live provider when given.
  - params `ModelOptionsParams`: profile?: string \| null; session_id?: string \| null; explicit_only?: boolean; include_unconfigured?: boolean; refresh?: boolean
  - result `ModelOptionsResult`: providers: ModelOptionProvider[]; model?: string; provider?: string
- **`model.save_key`** — Save an API key for a provider and return its refreshed inventory row.
  - params `ModelSaveKeyParams`: slug: string; api_key: string; session_id?: string \| null
  - result `ModelSaveKeyResult`: provider: ModelOptionProvider
- **`onboarding.set_run`** — Write onboarding.run in the root profile's config.yaml; answers the new state.
  - params `OnboardingSetRunParams`: run: boolean; mark_profile_offered?: boolean
  - result `OnboardingRunStateResult`: run: boolean; eligible: boolean; session_id?: string \| null; run_id?: string \| null
- **`onboarding.skip`** — Skip setup: end the session's onboarding run and close the card that waits.
  - params `OnboardingSkipParams`: session_id: string; profile?: string \| null
  - result `OnboardingSkipResult`: skipped: boolean
- **`onboarding.start`** — Open the onboarding chat in the default profile: create it, write its run record and play the opening.
  - params `OnboardingStartParams`: rerun?: boolean; fresh_launch?: boolean; assume_fresh?: boolean; facts_source?: OnboardingFactsSource; locale?: string \| null
  - result `OnboardingStartResult`: session_id: string; stored_session_id: string; resumed: boolean; run_id: string
- **`onboarding.state`** — Whether desktop first-run onboarding is due, and the chat in progress; reads the root profile's config.
  - params `OnboardingStateParams`: assume_fresh?: boolean
  - result `OnboardingRunStateResult`: run: boolean; eligible: boolean; session_id?: string \| null; run_id?: string \| null
- **`paste.collapse`** — Spill a large paste to a file and hand back the inline placeholder.
  - params `PasteCollapseParams`: text?: string \| null
  - result `PasteCollapseResult`: placeholder: string; path: string; lines: number
- **`pdf.attach`** — Render a PDF's pages to PNG and queue them as images for the next turn.
  - params `PdfAttachParams`: session_id: string; profile?: string \| null; path?: string \| null; content_base64?: string \| null; data?: string \| null; filename?: string \| null; first_page?: number \| null; last_page?: number \| null
  - result `PdfAttachResult`: attached: boolean; filename: string; pages_attached: number; pages: PdfPage[]; count: number; text: string
- **`pet.cancel`** — Stop an in-flight pet generate/hatch by token (idempotent).
  - params `PetCancelParams`: profile?: string \| null; token?: string \| null
  - result `PetCancelResult`: ok: boolean
- **`pet.cells`** — Half-block cell frames (or a kitty placement) for one pet state.
  - params `PetCellsParams`: profile?: string \| null; state?: string \| null; cols?: number \| null; graphics?: boolean
  - result `PetCellsResult`: enabled: boolean; slug?: string \| null; displayName?: string \| null; state?: string \| null; cols?: number \| null; frameMs?: number \| null; frames?: number[][][][] \| string[] \| null; scale?: number \| null; graphics?: string \| null; imageId?: number \| null; color?: string \| null; rows?: number \| null; placeholder?: string[] \| null
- **`pet.disable`** — Turn the pet display off from the desktop picker.
  - params `ProfileParams`: profile?: string \| null
  - result `OkResult`: ok?: boolean
- **`pet.export`** — Export an installed pet as a re-importable .zip.
  - params `PetSlugParams`: profile?: string \| null; slug: string
  - result `PetExportResult`: ok: boolean; filename: string; zipBase64: string
- **`pet.gallery`** — Petdex gallery + local install state (installed-only offline); localOnly skips the remote manifest.
  - params `PetGalleryParams`: profile?: string \| null; localOnly?: boolean
  - result `PetGalleryResult`: enabled: boolean; active?: string; pets?: PetGalleryEntry[]
- **`pet.generate`** — Candidate base looks for a new pet (draft step); drafts also stream via pet.generate.progress.
  - params `PetGenerateParams`: profile?: string \| null; prompt?: string \| null; referenceImage?: string \| null; count?: number \| null; style?: string \| null; provider?: string \| null
  - result `PetGenerateResult`: ok: boolean; token: string; drafts: PetDraft[]
- **`pet.generate.status`** — Whether pet generation is possible (a reference-capable image backend) and which providers.
  - params `ProfileParams`: profile?: string \| null
  - result `PetGenerateStatusResult`: available: boolean; providers: PetGenProvider[]
- **`pet.hatch`** — Turn a base draft into a full spritesheet pet; progress streams via pet.hatch.progress.
  - params `PetHatchParams`: profile?: string \| null; token: string; name: string; cancelToken?: string \| null; index?: number \| null; description?: string \| null; prompt?: string \| null; style?: string \| null; provider?: string \| null
  - result `PetHatchResult`: ok: boolean; slug: string; displayName: string; warnings?: unknown[]; pet: PetSpritePayload
- **`pet.info`** — Active pet for sprite renderers: spritesheet (base64) + frame geometry + state-row taxonomy.
  - params `PetInfoParams`: profile?: string \| null; knownRevision?: string \| null
  - result `PetInfoResult`: enabled: boolean; slug?: string \| null; displayName?: string \| null; mime?: string \| null; spritesheetBase64?: string \| null; spritesheetRevision?: string \| null; spritesheetUnchanged?: boolean \| null; frameW?: number \| null; frameH?: number \| null; framesPerState?: number \| null; framesByState?: Record<string, number> \| null; framesByRow?: Record<string, number> \| null; loopMs?: number \| null; scale?: number \| null; stateRows?: string[] \| null
- **`pet.info.meta`** — Cheap active-pet metadata used to avoid full payload refreshes.
  - params `ProfileParams`: profile?: string \| null
  - result `PetInfoMetaResult`: enabled: boolean; slug?: string \| null; displayName?: string \| null; scale?: number \| null; spritesheetRevision?: string \| null
- **`pet.remove`** — Uninstall a pet (delete its directory); if it was active, turn the display off.
  - params `PetSlugParams`: profile?: string \| null; slug: string
  - result `PetSlugResult`: ok: boolean; slug: string; displayName?: string \| null
- **`pet.rename`** — Rename a pet's display name + realign its slug/dir; follows the active slug in config.
  - params `PetRenameParams`: profile?: string \| null; slug: string; name: string
  - result `PetSlugResult`: ok: boolean; slug: string; displayName?: string \| null
- **`pet.scale`** — Persist display.pet.scale (clamped to engine bounds) from the desktop slider.
  - params `PetScaleParams`: profile?: string \| null; scale?: unknown
  - result `PetScaleResult`: ok: boolean; scale: number
- **`pet.select`** — Adopt a pet: install (if needed) + activate; writes display.pet.* to config.
  - params `PetSlugParams`: profile?: string \| null; slug: string
  - result `PetSlugResult`: ok: boolean; slug: string; displayName?: string \| null
- **`pet.thumb`** — Idle-frame PNG data URI for the picker (desktop CSP breaks CDN <img>).
  - params `PetThumbParams`: profile?: string \| null; slug: string; url?: string \| null
  - result `PetThumbResult`: ok: boolean; slug: string; dataUri?: string \| null
- **`ping`** — Cheapest liveness probe; answered on the WS reader thread even while every agent is mid-turn.
  - params `PingParams`: `Record<string, never>`
  - result `PingResult`: pong: boolean
- **`plugins.catalog.lookup`** — One curated plugin-catalog entry by name, from the backend's live catalog (cached, with the in-tree fallback and kill list); backs hermes://plugin/install?catalog= deep links.
  - params `PluginCatalogLookupParams`: profile?: string \| null; name: string
  - result `PluginCatalogLookupResult`: status: PluginCatalogLookupStatus; entry?: PluginCatalogLookupEntry \| null
- **`plugins.list`** — Loaded plugin manager entries (legacy flat view); the Plugins Hub uses plugins.manage list.
  - params `PluginsListParams`: `Record<string, never>`
  - result `PluginsListResult`: plugins: LegacyPluginRow[]; toolsets?: PluginToolsetRow[] \| null; portable_mcp_servers?: string[] \| null
- **`plugins.manage`** — Plugins Hub backend: list installed plugins, toggle, git-install, re-pin a catalog install, or remove a user install.
  - params `PluginsManageParams`: profile?: string \| null; action?: PluginsAction; key?: string \| null; name?: string \| null; enable?: boolean \| null; identifier?: string \| null; repo?: string \| null; catalog_name?: string \| null; force?: boolean \| null; ref?: string \| null; accept_capabilities?: boolean \| null; values?: Record<string, unknown> \| null; names?: string[] \| null
  - result `PluginsManageResult`: plugins?: AgentPluginRow[] \| null; user_count?: number \| null; bundled_count?: number \| null; ok?: boolean \| null; unchanged?: boolean \| null; restart_required?: boolean \| null; gateway_reloaded?: boolean \| null; activation?: PluginActivation \| null; cleared_memory_provider?: boolean \| null; name?: string \| null; plugin?: AgentPluginRow \| null; plugin_name?: string \| null; warnings?: string[] \| null; missing_env?: string[] \| null; python_dependencies?: string[] \| null; known_issues?: string[] \| null; after_install_path?: string \| null; enabled?: boolean \| null; sha?: string \ …
- **`preview.restart`** — Spawn a hidden agent that brings the desktop preview's dev server back up.
  - params `PreviewRestartParams`: session_id: string; profile?: string \| null; url: string; cwd?: string \| null; context?: string \| null
  - result `TaskIdResult`: task_id: string
- **`process.kill`** — Kill one background process the caller's session owns and return its output snapshot.
  - params `ProcessKillParams`: session_id: string; process_id: string; profile?: string \| null
  - result `ProcessKillResult`: status: ProcessKillStatus; session_id?: string \| null; command?: string \| null; exit_code?: number \| null; completion_reason?: string \| null; termination_source?: string \| null; output?: string \| null; error?: string \| null
- **`process.list`** — Background processes owned by the caller's session (desktop status stack poll).
  - params `ProcessListParams`: session_id: string; profile?: string \| null
  - result `ProcessListResult`: processes?: ProcessEntry[]
- **`process.stop`** — ``/stop``: on the shared owner, kill the background processes ``session_id`` owns (required); a standalone sidecar kills every process it holds. Answers the count killed.
  - params `ProcessStopParams`: session_id?: string \| null; profile?: string \| null
  - result `ProcessStopResult`: killed: number; delegations?: number
- **`profiles.configure`** — Editor Save: apply any subset of a profile's sections and report each one.
  - params `ProfilesConfigureParams`: profile?: string \| null; name?: string \| null; ui_meta?: Record<string, unknown> \| null; ui_meta_expected_revisions?: Record<string, number> \| null; soul?: string \| null; description?: string \| null; model?: string \| null; provider?: string \| null; confirm_expensive_model?: boolean \| string \| null; disabled_skills?: string[] \| null; enabled_toolsets?: string[] \| null; enabled_mcp_servers?: string[] \| null
  - result `ProfilesConfigureResult`: ok: boolean; applied: ProfilesConfigureApplied; confirm_required?: boolean \| null; confirm_message?: string \| null
- **`profiles.create`** — Create a profile (ws twin of POST /api/profiles), mirroring launch credentials by default.
  - params `ProfilesCreateParams`: profile?: string \| null; name: string; description?: string \| null; clone_from?: string \| null; clone_all?: boolean \| string \| null; clone_channels?: boolean \| string \| null; no_skills?: boolean \| string \| null; no_alias?: boolean \| string \| null; soul?: string \| null; model?: string \| null; provider?: string \| null; share_auth?: boolean \| string \| null; mirror_credentials?: boolean \| string \| null
  - result `ProfilesCreateResult`: ok?: boolean; name: string; path: string; soul_written?: boolean; model_set?: boolean; mirrored: ProfileMirrored
- **`profiles.describe`** — Everything the profile editor shows: soul, model pin, skills, toolsets, MCP servers.
  - params `ProfileNameParams`: profile?: string \| null; name?: string \| null
  - result `ProfilesDescribeResult`: name: string; description?: string; soul?: string; model: ProfileModelPin; skills?: CapabilityEntry[]; toolsets?: ToolsetEntry[]; toolsets_pinned?: boolean; mcp_servers?: McpServerEntry[]
- **`profiles.get_asset`** — A profile asset as a data URL.
  - params `ProfilesGetAssetParams`: profile?: string \| null; name?: string \| null; asset?: string \| null
  - result `ProfilesGetAssetResult`: found: boolean; mime?: string \| null; size?: number \| null; data?: string \| null
- **`profiles.list`** — Roster of profiles with previews so a client paints without N follow-up calls.
  - params `ProfilesListParams`: profile?: string \| null; include_sessions?: boolean \| string \| null
  - result `ProfilesListResult`: profiles?: ProfileRow[]; bot_mode_protocol?: boolean; install_id?: string
- **`profiles.set_asset`** — Store or clear a profile asset (avatar) atomically.
  - params `ProfilesSetAssetParams`: profile?: string \| null; name?: string \| null; asset?: string \| null; data?: string \| null; clear?: boolean \| string \| null
  - result `ProfilesSetAssetResult`: ok?: boolean; asset: string; size?: number; removed?: number \| null
- **`project.facts`** — Structured project facts for a cwd so UIs don't re-sniff the workspace.
  - params `ProjectFactsParams`: profile?: string \| null; cwd?: string \| null
  - result `ProjectFactsResult`: facts?: ProjectFacts \| null
- **`projects.add_folder`** — Attach a folder to a project (optionally as its primary path).
  - params `ProjectsAddFolderParams`: profile?: string \| null; id: string; path: string; label?: string \| null; is_primary?: boolean
  - result `ProjectResult`: project: ProjectInfo
- **`projects.archive`** — Archive (or with ``restore`` un-archive) a project; answers the full listing.
  - params `ProjectsArchiveParams`: profile?: string \| null; id: string; restore?: boolean
  - result `ProjectsPayload`: projects: ProjectInfo[]; active_id?: string \| null
- **`projects.create`** — Create a project from a name + folders; duplicate primary paths are refused (5063).
  - params `ProjectsCreateParams`: profile?: string \| null; name: string; folders?: string[] \| null; slug?: string \| null; primary_path?: string \| null; description?: string \| null; icon?: string \| null; color?: string \| null; board_slug?: string \| null; use?: boolean
  - result `OptionalProjectResult`: project?: ProjectInfo \| null
- **`projects.delete`** — Delete a project and its folders; answers the full listing.
  - params `ProjectIdParams`: profile?: string \| null; id: string
  - result `ProjectsPayload`: projects: ProjectInfo[]; active_id?: string \| null
- **`projects.discover_repos`** — Repos for the desktop overview: scanned-from-disk (cached) ∪ session-derived.
  - params `ProjectsDiscoverReposParams`: profile?: string \| null; scan?: boolean
  - result `ProjectsDiscoverReposResult`: repos: DiscoveredRepo[]; discovery_policy?: RepoDiscoveryPolicy \| null
- **`projects.for_cwd`** — Which project (if any) owns a directory, plus the resolved cwd and its git branch.
  - params `ProjectsForCwdParams`: profile?: string \| null; cwd?: string \| null
  - result `ProjectsForCwdResult`: project?: ProjectInfo \| null; cwd: string; branch?: string
- **`projects.get`** — One stored project with its folders.
  - params `ProjectIdParams`: profile?: string \| null; id: string
  - result `ProjectResult`: project: ProjectInfo
- **`projects.list`** — Every project of the profile (archived included) plus which one is active.
  - params `ProfileParams`: profile?: string \| null
  - result `ProjectsPayload`: projects: ProjectInfo[]; active_id?: string \| null
- **`projects.project_sessions`** — Fully hydrated lanes for one project, from the same grouping as projects.tree.
  - params `ProjectsProjectSessionsParams`: profile?: string \| null; project_id: string; session_limit?: number \| null
  - result `ProjectsProjectSessionsResult`: project?: ProjectTreeNode \| null
- **`projects.record_repos`** — Persist repo roots found by the client's (desktop-side) scan; return the merged list.
  - params `ProjectsRecordReposParams`: profile?: string \| null; repos?: (RecordRepoItem \| string)[] \| null; discovery_policy?: RepoDiscoveryPolicyParams \| null
  - result `ProjectsRecordReposResult`: repos: DiscoveredRepo[]; discovery_policy?: RepoDiscoveryPolicy \| null; accepted: boolean
- **`projects.remove_folder`** — Detach a folder from a project.
  - params `ProjectFolderParams`: profile?: string \| null; id: string; path: string
  - result `ProjectResult`: project: ProjectInfo
- **`projects.set_active`** — Switch (or clear) the active project for the profile.
  - params `ProjectsSetActiveParams`: profile?: string \| null; id?: string \| null
  - result `ActiveIdResult`: active_id?: string \| null
- **`projects.set_primary`** — Make one attached folder the project's primary path.
  - params `ProjectFolderParams`: profile?: string \| null; id: string; path: string
  - result `ProjectResult`: project: ProjectInfo
- **`projects.tree`** — Project → repo → lane overview with counts and a few preview sessions per project.
  - params `ProjectsTreeParams`: profile?: string \| null; preview_limit?: number \| null; session_limit?: number \| null
  - result `ProjectsTreeResult`: projects: ProjectTreeNode[]; active_id?: string \| null; scoped_session_ids?: string[]
- **`projects.update`** — Patch a project's display fields; answers the refreshed project.
  - params `ProjectsUpdateParams`: profile?: string \| null; id: string; name?: string \| null; description?: string \| null; icon?: string \| null; color?: string \| null; board_slug?: string \| null
  - result `ProjectResult`: project: ProjectInfo
- **`prompt.background`** — Run a task on a fresh agent in the background; the answer arrives as background.complete.
  - params `SideAgentParams`: session_id: string; profile?: string \| null; text: string
  - result `TaskIdResult`: task_id: string
- **`prompt.btw`** — Side question over a snapshot of the live conversation; the answer arrives as btw.complete.
  - params `SideAgentParams`: session_id: string; profile?: string \| null; text: string
  - result `TaskIdResult`: task_id: string
- **`prompt.submit`** — Send a user turn to a live session; busy sessions queue / steer / redirect instead of refusing.
  - params `PromptSubmitParams`: session_id: string; profile?: string \| null; text?: unknown; display_kind?: string \| null; interrupted?: boolean \| null; queued?: boolean \| null; surface?: string \| null; voice_context?: string \| null; voice_turn?: boolean \| null; title_preview?: string \| null; truncate_before_user_ordinal?: number \| null; truncate_before_row_id?: number \| null; truncate_before_message_id?: string \| null; confirm_truncate?: boolean \| null; confirm_empty_truncate?: boolean \| null; confirm_deep_truncate?: boolean \| null; rebind_survivor_row_ids?: number[] \| null
  - result `PromptSubmitResult`: status?: PromptSubmitStatus \| null; voice_stopped?: boolean \| null; user_row_id?: number \| null; survivor_user_row_ids?: (number \| null)[] \| null; survivor_row_id_map?: Record<string, number \| null> \| null; turn_isolation?: boolean \| null; admission_id?: string \| null; forwarded?: boolean \| null
- **`reload.env`** — Re-read ~/.hermes/.env (CLI /reload parity); built agents keep their pool until /new.
  - params `ReloadEnvParams`: session_id?: string \| null; profile?: string \| null
  - result `ReloadEnvResult`: updated: number
- **`reload.mcp`** — Tear down and rediscover MCP servers for every live session (prompt cache is invalidated).
  - params `ReloadMcpParams`: session_id?: string \| null; confirm?: boolean; always?: boolean; rev?: string \| null
  - result `ReloadMcpResult`: status: ReloadMcpStatus; message?: string \| null; loaded_rev?: string \| null; coalesced?: boolean \| null; turn_isolation?: boolean \| null; host_ack?: unknown \| null; session?: ReloadMcpSession \| null; unavailable?: string[] \| null; session_servers?: string[] \| null
- **`request.answer`** — Answer an open server→client request from a client that never received the frame.
  - params `RequestAnswerParams`: id: string; result: Record<string, unknown>; profile?: string \| null
  - result `RequestAnswerResult`: status: ClarifyLockStatus
- **`rollback.diff`** — Diff between a checkpoint and the working tree, with an ANSI rendering sized to the TUI.
  - params `RollbackDiffParams`: session_id: string; hash: string; profile?: string \| null
  - result `RollbackDiffResult`: stat?: string; diff?: string; rendered?: string \| null
- **`rollback.list`** — Checkpoints for the session's cwd; ``enabled: false`` when checkpointing is off.
  - params `RollbackListParams`: session_id: string; profile?: string \| null
  - result `RollbackListResult`: enabled: boolean; checkpoints?: RollbackCheckpoint[]
- **`rollback.restore`** — Restore the working tree (or one file) to a checkpoint by hash or 1-based index.
  - params `RollbackRestoreParams`: session_id: string; hash: string; file_path?: string \| null; profile?: string \| null; all?: boolean \| null
  - result `RollbackRestoreResult`: success: boolean; restored_to?: string \| null; reason?: string \| null; directory?: string \| null; file?: string \| null; restored_files?: string[] \| null; skipped_user_edits?: string[] \| null; skipped_oversize?: string[] \| null; failed_deletes?: string[] \| null; history_removed?: number \| null; error?: string \| null; debug?: unknown \| null
- **`session.activate`** — Attach the frontend to a live session without closing the previously focused one.
  - params `SessionActivateParams`: session_id: string; profile?: string \| null; cols?: number \| null; omit_messages?: boolean
  - result `SessionActivateResult`: session_id: string; message_count: number; messages: TranscriptMessage[]; info: SessionLiveInfo; stored_session_id?: string \| null; resumed?: string \| null; session_key?: string \| null; messages_omitted?: boolean \| null; hydrating?: boolean \| null; running?: boolean \| null; turn_started_at?: number \| null; started_at?: number \| null; status?: string \| null; inflight?: InflightTurn \| null; queued?: QueuedPrompt \| null; pending_approval?: PendingApproval \| null; open_requests?: OpenRequestEntry[] \| null; pending_connection?: ConnectionRequestPayload \| null; todo_state?: TodoState \ …
- **`session.active_list`** — Live sessions in this process, insertion order (not a DB browser).
  - params `SessionActiveListParams`: profile?: string \| null; current_session_id?: string \| null
  - result `SessionActiveListResult`: sessions: SessionActiveItem[]
- **`session.archive`** — Set/clear archived (soft-hide, messages kept) on a session + lineage; Desktop PATCH parity.
  - params `SessionArchiveParams`: session_id?: string \| null; session_key?: string \| null; archived?: boolean; profile?: string \| null
  - result `SessionArchiveResult`: archived: boolean; session_key: string
- **`session.branch`** — Fork a live session into a new stored child that shares the parent's history so far.
  - params `SessionBranchParams`: session_id: string; profile?: string \| null; name?: string \| null; count?: number \| null; idempotency_key?: string \| null
  - result `SessionBranchResult`: session_id: string; stored_session_id: string; title: string; parent: string; message_count: number; messages: TranscriptMessage[]; info: SessionLiveInfo
- **`session.branch_stored`** — Whole-session branch of a stored parent: the owning backend reads and copies the transcript, which never crosses the wire (a separate method so an older gateway fails loudly, not with an empty branch).
  - params `SessionBranchStoredParams`: profile?: string \| null; parent_session_id: string; cols?: number \| null; source?: string \| null; cwd?: string \| null; idempotency_key?: string \| null
  - result `SessionBranchStoredResult`: session_id: string; stored_session_id: string; message_count: number; messages_omitted: boolean; info: SessionLiveInfo
- **`session.branch_whole`** — session.branch of the whole history without echoing the copied transcript back.
  - params `SessionBranchWholeParams`: session_id: string; profile?: string \| null; name?: string \| null; idempotency_key?: string \| null
  - result `SessionBranchWholeResult`: session_id: string; stored_session_id: string; title: string; parent: string; message_count: number; messages_omitted: boolean; info: SessionLiveInfo
- **`session.close`** — Tear down a live session (its stored row stays resumable).
  - params `SessionCloseParams`: session_id: string; profile?: string \| null
  - result `SessionCloseResult`: closed: boolean; messages?: string[]
- **`session.compress`** — Manual /compress of an idle session, optionally focused on a topic.
  - params `SessionCompressParams`: session_id: string; profile?: string \| null; focus_topic?: string \| null
  - result `SessionCompressResult`: status?: string \| null; removed?: number \| null; before_messages?: number \| null; after_messages?: number \| null; before_tokens?: number \| null; after_tokens?: number \| null; summary?: CompressionSummary \| null; usage?: Usage \| null; info?: SessionLiveInfo \| null; messages?: TranscriptMessage[] \| null; compressed?: boolean \| null; lock_held?: boolean \| null; message?: string \| null; turn_isolation?: boolean \| null; host_ack?: Record<string, unknown> \| null
- **`session.context_breakdown`** — Cursor-style split of the context window by category.
  - params `SessionContextBreakdownParams`: session_id: string; profile?: string \| null
  - result `SessionContextBreakdownResult`: categories: ContextCategory[]; context_max: number; context_percent: number; context_used: number; estimated_total: number; context_estimated: boolean; context_source: string; model: string; context_files?: ContextFileSource[]
- **`session.control`** — Run one allowlisted goal / loop / subgoal / heartbeat action and return the exact resulting snapshot.
  - params `SessionControlParams`: profile?: string \| null; session_id: string; action: string; args?: SessionControlArgs \| null
  - result `SessionControlResult`: control: SessionControlSnapshot; dispatch: SessionControlDispatch
- **`session.control.read`** — Stable, allowlisted snapshot of one live session's goal / loop / heartbeat state.
  - params `SessionControlReadParams`: profile?: string \| null; session_id: string
  - result `SessionControlReadResult`: control: SessionControlSnapshot
- **`session.create`** — Mint a live session (agent builds after the reply); a DB row appears on the first prompt unless seeded.
  - params `SessionCreateParams`: profile?: string \| null; cols?: number \| null; source?: string \| null; cwd?: string \| null; cwd_explicit?: boolean \| null; messages?: SeedMessage[] \| null; parent_session_id?: string \| null; title?: string \| null; model?: string \| null; provider?: string \| null; reasoning_effort?: string \| null; fast?: boolean \| null; service_tier?: string \| null; close_on_disconnect?: boolean; hidden?: boolean; room_plumbing?: boolean; follow_profile_config?: boolean; idempotency_key?: string \| null
  - result `SessionCreateResult`: session_id: string; stored_session_id: string; message_count: number; messages: TranscriptMessage[]; info: SessionLiveInfo
- **`session.cwd.set`** — Change a live, idle session's working directory.
  - params `SessionCwdSetParams`: session_id: string; profile?: string \| null; cwd: string
  - result `SessionCwdSetResult`: model?: string; provider?: string; reasoning_effort?: string; reasoning_effort_wire?: string; service_tier?: string; fast?: boolean; yolo?: boolean; approval_mode?: string; tools?: Record<string, string[]>; skills?: Record<string, string[]>; cwd?: string; branch?: string \| null; project?: ProjectRef \| null; terminal_backend?: string; personality?: string; running?: boolean; turn_started_at?: number \| null; title?: string; stored_session_id?: string; desktop_contract?: number \| string \| null; version?: string; release_date?: string; update_behind?: unknown \| null; update_command?: string; …
- **`session.delete`** — Delete a stored session + transcripts; refused while it is live here.
  - params `SessionDeleteParams`: session_id: string; profile?: string \| null
  - result `SessionDeleteResult`: deleted: string
- **`session.events.since`** — Replay events after a seq watermark on WS reconnect; truncated means refetch state.
  - params `SessionEventsSinceParams`: session_id: string; profile?: string \| null; last_seen?: number \| null; replay_epoch?: string \| null
  - result `SessionEventsSinceResult`: events: Record<string, unknown>[]; latest_seq: number; truncated: boolean; count: number; epoch: string; open_requests: OpenRequestEntry[]
- **`session.events.stats`** — Replay-buffer occupancy telemetry (ops/debug).
  - params `SessionEventsStatsParams`: profile?: string \| null
  - result `SessionEventsStatsResult`: sessions: number; events: number; bytes: number; max_per_session: number; max_bytes_per_session: number; max_bytes_process: number
- **`session.foreign.import`** — Import a foreign session into this profile's history (idempotent per origin).
  - params `SessionForeignIdParams`: profile?: string \| null; id?: string \| null
  - result `SessionForeignImportResult`: session_id: string; already_imported?: boolean
- **`session.foreign.list`** — One page of Claude Code / Codex sessions found on the serving backend.
  - params `SessionForeignListParams`: profile?: string \| null; source?: ForeignSource \| null; offset?: number \| null; limit?: number \| null
  - result `SessionForeignListResult`: sessions?: ForeignSessionRow[]; next_offset?: number \| null; host: string; unreadable?: number
- **`session.foreign.preview`** — Preview a foreign session's tail before importing it.
  - params `SessionForeignIdParams`: profile?: string \| null; id?: string \| null
  - result `SessionForeignPreviewResult`: messages?: ForeignTurn[]; total?: number; truncated?: boolean; already_imported?: string \| null; cwd?: string \| null
- **`session.history`** — The durable display transcript (ancestors included, row ids attached).
  - params `SessionHistoryParams`: session_id: string; profile?: string \| null
  - result `SessionHistoryResult`: count: number; messages: TranscriptMessage[]
- **`session.interrupt`** — Stop the running turn (and streaming TTS); retires the crash-recovery marker.
  - params `SessionInterruptParams`: session_id: string; profile?: string \| null; expected_hosted_task_id?: string \| null
  - result `SessionInterruptResult`: status: InterruptStatus; interrupted?: boolean \| null; turn_isolation?: boolean \| null
- **`session.list`** — Human-facing stored sessions, most recent first (sub-agent / kanban sources denied).
  - params `SessionListParams`: profile?: string \| null; title?: string \| null; limit?: number \| null; include_hidden?: boolean
  - result `SessionListResult`: sessions: SessionListRow[]
- **`session.most_recent`** — Most recent human-facing session; errors fold into a null session_id.
  - params `SessionMostRecentParams`: profile?: string \| null
  - result `SessionMostRecentResult`: session_id: string \| null; title?: string \| null; started_at?: number \| null; source?: string \| null
- **`session.redirect`** — Redirect the active turn (queued for the next turn while the agent is still building).
  - params `SessionCorrectionParams`: session_id: string; profile?: string \| null; text: string
  - result `SessionCorrectionResult`: status: CorrectionStatus; text: string
- **`session.resume`** — Attach to a stored session: reuse it if live here, else lazy / deferred / cold / eager rebuild.
  - params `SessionResumeParams`: session_id: string; profile?: string \| null; cols?: number \| null; source?: string \| null; lazy?: boolean; defer_history?: boolean; omit_messages?: boolean; eager_build?: boolean; close_on_disconnect?: boolean; inline_images?: boolean
  - result `SessionResumeResult`: session_id: string; message_count: number; messages: TranscriptMessage[]; info: SessionLiveInfo; stored_session_id?: string \| null; resumed?: string \| null; session_key?: string \| null; messages_omitted?: boolean \| null; hydrating?: boolean \| null; running?: boolean \| null; turn_started_at?: number \| null; started_at?: number \| null; status?: string \| null; inflight?: InflightTurn \| null; queued?: QueuedPrompt \| null; pending_approval?: PendingApproval \| null; open_requests?: OpenRequestEntry[] \| null; pending_connection?: ConnectionRequestPayload \| null; todo_state?: TodoState \ …
- **`session.save`** — Export the transcript to ~/.hermes/sessions/saved (classic /save).
  - params `SessionSaveParams`: session_id: string; profile?: string \| null
  - result `SessionSaveResult`: file?: string \| null
- **`session.set_hidden`** — Set/clear hidden (out of the default list, still resumable by its owner) on a session + lineage.
  - params `SessionSetHiddenParams`: session_id: string; hidden: boolean; profile?: string \| null
  - result `SessionSetHiddenResult`: hidden: boolean; session_key: string
- **`session.status`** — Rendered /status text for the session.
  - params `SessionStatusParams`: session_id: string; profile?: string \| null
  - result `SessionStatusResult`: output: string
- **`session.steer`** — Inject text into the next tool result without interrupting the turn.
  - params `SessionCorrectionParams`: session_id: string; profile?: string \| null; text: string
  - result `SessionCorrectionResult`: status: CorrectionStatus; text: string
- **`session.title`** — Read or set a live session's title; a title set before the row exists is queued.
  - params `SessionTitleParams`: session_id: string; profile?: string \| null; title?: string \| null
  - result `SessionTitleResult`: title: string; session_key?: string \| null; pending?: boolean \| null
- **`session.undo`** — Drop the last user turn (and everything after it) from an idle session.
  - params `SessionUndoParams`: session_id: string; profile?: string \| null; intent?: UndoIntent \| null
  - result `SessionUndoResult`: removed: number
- **`session.usage`** — Token / context / cost counters for the session (+ Nous credit lines when available).
  - params `SessionUsageParams`: session_id: string; profile?: string \| null
  - result `SessionUsageResult`: model?: string; input?: number; output?: number; reasoning?: number; prompt?: number; completion?: number; total?: number; calls?: number; compressions?: number \| null; context_used?: number \| null; context_max?: number \| null; context_percent?: number \| null; context_source?: string \| null; context_estimated?: boolean \| null; cache_hit_pct?: number \| null; cache_read?: number \| null; cache_write?: number \| null; avg_latency_s?: number \| null; avg_tps?: number \| null; active_subagents?: number \| null; dev_credits_spent_micros?: number \| null; cost_usd?: number \| null; cost_status …
- **`session.workspace.move`** — Re-home a stored session's workspace; git identity is replaced and a live agent follows.
  - params `SessionWorkspaceMoveParams`: profile?: string \| null; session_key: string; cwd: string
  - result `SessionWorkspaceMoveResult`: cwd: string; branch?: string \| null; git_repo_root?: string \| null
- **`setup.runtime_check`** — Strict provider check through the same runtime resolution the agent uses on session creation.
  - params `SetupRuntimeCheckParams`: profile?: string \| null; provider?: string \| null
  - result `SetupRuntimeCheckResult`: ok: boolean; provider?: string \| null; model?: string \| null; source?: string \| null; error?: string \| null; free_tier_route?: boolean \| null; profile?: string \| null
- **`setup.status`** — Loose provider check: is ANY provider auth state discoverable for the (launch or named) profile.
  - params `ProfileParams`: profile?: string \| null
  - result `SetupStatusResult`: provider_configured?: boolean \| null; ready?: boolean \| null; free_tier_account?: boolean \| null; free_tier_route?: boolean \| null; other_providers?: boolean \| null; inference_provider?: string \| null; profile?: string \| null; ok?: boolean \| null; error?: string \| null; error_code?: string \| null; retryable?: boolean \| null; retry_after?: number \| null
- **`shared_metrics.desktop_daily`** — Record one finished Desktop day (mode use + button presses); recorded=false keeps it for a retry.
  - params `SharedMetricsDesktopDailyParams`: profile?: string \| null; day: string; bot_count?: number; modes?: SharedMetricsDesktopModeDay[]; actions?: SharedMetricsDesktopActionDay[]
  - result `SharedMetricsDesktopDailyResult`: recorded: boolean
- **`shared_metrics.desktop_dislike`** — Count one Desktop dislike signal (fire-and-forget; capped per signal per day; a no-op unless on).
  - params `SharedMetricsDesktopDislikeParams`: profile?: string \| null; signal: string; target?: string; setting?: string \| null
  - result `OkResult`: ok?: boolean
- **`shared_metrics.desktop_feature_use`** — Count one Desktop area used today (fire-and-forget; once per area per UTC day; a no-op unless on).
  - params `SharedMetricsDesktopFeatureUseParams`: profile?: string \| null; area: string
  - result `OkResult`: ok?: boolean
- **`shared_metrics.desktop_friction`** — Count one Desktop friction event (fire-and-forget; capped per day; a no-op unless on).
  - params `SharedMetricsDesktopFrictionParams`: profile?: string \| null; kind: string; detail: string
  - result `OkResult`: ok?: boolean
- **`shared_metrics.desktop_onboarding`** — Count one Desktop first-run step transition (fire-and-forget; once per step+event; a no-op unless on).
  - params `SharedMetricsDesktopOnboardingParams`: profile?: string \| null; step: string; event: string
  - result `OkResult`: ok?: boolean
- **`shared_metrics.set`** — Write both shared-metrics opt-ins at once (send requires collection) and reconcile consent windows.
  - params `SharedMetricsSetParams`: profile?: string \| null; enabled: boolean; send?: boolean; first_run?: boolean
  - result `SharedMetricsConsentResult`: enabled: boolean; send: boolean; decided: boolean; reask?: boolean
- **`shared_metrics.slash_command`** — Count one user-typed slash command (fire-and-forget; a no-op unless shared metrics are on).
  - params `SharedMetricsSlashCommandParams`: profile?: string \| null; command: string; session_id?: string \| null
  - result `SharedMetricsSlashCommandResult`: ok: boolean
- **`shared_metrics.startup_latency`** — Record one client launch-to-ready latency (fire-and-forget; a no-op unless shared metrics are on).
  - params `SharedMetricsStartupLatencyParams`: profile?: string \| null; elapsed_ms: number; surface?: 'desktop_attach' \| 'tui' \| null; launch_id?: string \| null
  - result `SharedMetricsStartupLatencyResult`: ok: boolean
- **`shared_metrics.status`** — Pure read of the focused profile's shared-metrics opt-ins (collection, upload, answered).
  - params `ProfileParams`: profile?: string \| null
  - result `SharedMetricsConsentResult`: enabled: boolean; send: boolean; decided: boolean; reask?: boolean
- **`shared_metrics.update_run`** — Count one Desktop packaged self-update outcome (fire-and-forget; a no-op unless shared metrics are on).
  - params `SharedMetricsUpdateRunParams`: profile?: string \| null; outcome: string; failed_stage?: string \| null; mechanism?: string \| null; duration_ms?: number \| null; from_commit_date?: number \| null
  - result `SharedMetricsUpdateRunResult`: ok: boolean
- **`shell.exec`** — Run a safe (non-dangerous) shell command captured for ``!cmd`` / inline substitution.
  - params `ShellExecParams`: command: string; profile?: string \| null
  - result `ShellExecResult`: stdout: string; stderr: string; code: number
- **`skills.manage`** — Skills hub backend: list the profile's skills or search / browse / inspect / install from the hub.
  - params `SkillsManageParams`: profile?: string \| null; action?: SkillsAction; query?: string \| null; page?: number \| null; page_size?: number \| null
  - result `SkillsManageResult`: skills?: Record<string, string[]> \| null; results?: SkillHubHit[] \| null; installed?: boolean \| null; name?: string \| null; items?: SkillBrowseItem[] \| null; page?: number \| null; total_pages?: number \| null; total?: number \| null; info?: SkillInspectInfo \| null
- **`skills.reload`** — Re-scan skill dirs; the pre-rendered ``output`` is what /reload-skills prints.
  - params `SkillsReloadParams`: session_id?: string \| null; profile?: string \| null
  - result `SkillsReloadResult`: output: string; result: SkillsReloadDiff
- **`slash.exec`** — Execute a slash command against the session's slash worker (or a live/plugin shortcut).
  - params `SlashExecParams`: session_id: string; command: string; profile?: string \| null
  - result `SlashExecResult`: output?: string \| null; warning?: string \| null; type?: DispatchType \| null; target?: string \| null; message?: string \| null; notice?: string \| null; display?: string \| null; name?: string \| null; status?: string \| null
- **`spawn_tree.list`** — Saved spawn-tree snapshots, newest first.
  - params `SpawnTreeListParams`: profile?: string \| null; session_id?: string \| null; cross_session?: boolean; limit?: number \| null
  - result `SpawnTreeListResult`: entries: SpawnTreeEntry[]
- **`spawn_tree.load`** — Read one saved spawn-tree snapshot (path must be under the spawn-trees root).
  - params `SpawnTreeLoadParams`: profile?: string \| null; path: string
  - result `SpawnTreeLoadResult`: session_id?: string \| null; started_at?: number \| null; finished_at?: number \| null; label?: string \| null; subagents?: Record<string, unknown>[]
- **`spawn_tree.save`** — Persist a finished delegation tree snapshot under the session's spawn-trees dir.
  - params `SpawnTreeSaveParams`: profile?: string \| null; subagents: Record<string, unknown>[]; session_id?: string \| null; started_at?: number \| null; finished_at?: number \| null; label?: string \| null
  - result `SpawnTreeSaveResult`: path: string; session_id: string
- **`subagent.interrupt`** — Hard-interrupt one owned child; ``found`` is false when it already finished.
  - params `SubagentIdParams`: session_id: string; profile?: string \| null; subagent_id: string
  - result `SubagentInterruptResult`: found: boolean; subagent_id: string
- **`subagent.list`** — Live children owned by this session (other sessions' children never leak).
  - params `SessionParams`: session_id: string; profile?: string \| null
  - result `SubagentListResult`: subagents?: SubagentSnapshot[]; delegations?: FailedDelegation[]
- **`subagent.steer`** — Queue steering text into a live delegated child owned by this session.
  - params `SubagentSteerParams`: session_id: string; profile?: string \| null; subagent_id: string; text: string
  - result `SubagentSteerResult`: status: SteerStatus; subagent_id: string; text: string
- **`subagent.tail`** — Last 16KB of an owned child's live transcript.
  - params `SubagentIdParams`: session_id: string; profile?: string \| null; subagent_id: string
  - result `SubagentTailResult`: subagent_id: string; available?: boolean; text?: string; truncated?: boolean
- **`subscription.change`** — Schedule a downgrade / same-price change or a period-end cancellation.
  - params `SubscriptionChangeParams`: profile?: string \| null; subscription_type_id?: string \| null; cancel?: boolean
  - result `BillingPendingChangeResult`: ok: boolean; error?: string \| null; message?: string \| null; portal_url?: string \| null; retry_after?: number \| null; payload?: Record<string, unknown> \| null; actor?: string \| null; code?: string \| null; recovery?: string \| null
- **`subscription.preview`** — Chargeless quote of what a plan change would do (billing:manage).
  - params `SubscriptionPreviewParams`: profile?: string \| null; subscription_type_id?: string \| null
  - result `SubscriptionPreviewResult`: ok: boolean; error?: string \| null; message?: string \| null; portal_url?: string \| null; retry_after?: number \| null; payload?: Record<string, unknown> \| null; actor?: string \| null; code?: string \| null; recovery?: string \| null; effect?: SubscriptionChangeEffect \| null; reason?: string \| null; current_tier_id?: string \| null; current_tier_name?: string \| null; target_tier_id?: string \| null; target_tier_name?: string \| null; monthly_credits_delta?: string \| null; amount_due_now_cents?: number \| null; effective_at?: string \| null
- **`subscription.resume`** — Clear a scheduled downgrade / cancellation (re-enables recurring spend).
  - params `ProfileParams`: profile?: string \| null
  - result `BillingPendingChangeResult`: ok: boolean; error?: string \| null; message?: string \| null; portal_url?: string \| null; retry_after?: number \| null; payload?: Record<string, unknown> \| null; actor?: string \| null; code?: string \| null; recovery?: string \| null
- **`subscription.state`** — Current plan, tier catalog and usage for the picker; fail-open when logged out.
  - params `ProfileParams`: profile?: string \| null
  - result `SubscriptionStateResult`: ok: boolean; logged_in: boolean; is_admin?: boolean \| null; can_change_plan?: boolean \| null; org_name?: string \| null; org_id?: string \| null; role?: string \| null; context?: SubscriptionContext \| null; current?: CurrentSubscription \| null; tiers?: SubscriptionTierOption[] \| null; portal_url?: string \| null; error?: string \| null; usage?: UsageModel \| null; plan_usage?: PlanUsage \| null
- **`subscription.upgrade`** — Prorate, charge and flip the plan (billing:manage, idempotent).
  - params `SubscriptionUpgradeParams`: profile?: string \| null; subscription_type_id?: string \| null; idempotency_key?: string \| null
  - result `SubscriptionUpgradeResult`: ok: boolean; error?: string \| null; message?: string \| null; portal_url?: string \| null; retry_after?: number \| null; payload?: Record<string, unknown> \| null; actor?: string \| null; code?: string \| null; recovery?: string \| null; status?: string \| null; target_tier_name?: string \| null; recovery_url?: string \| null; reason?: string \| null; idempotency_key?: string \| null
- **`system.battery`** — Host battery for the status bar; always resolves, ``available: false`` when unreadable.
  - params `SystemBatteryParams`: profile?: string \| null
  - result `SystemBatteryResult`: available: boolean; percent?: number \| null; plugged?: boolean \| null; category?: BatteryCategory
- **`terminal.resize`** — Record the client's column width for server-side rendering.
  - params `TerminalResizeParams`: session_id: string; profile?: string \| null; cols?: number \| null
  - result `TerminalResizeResult`: cols: number
- **`tools.configure`** — Persist a toolset / MCP enable-disable change and rebuild the session agent so it takes effect now.
  - params `ToolsConfigureParams`: action: ToolsAction; names: string[]; session_id?: string \| null; profile?: string \| null
  - result `ToolsConfigureResult`: changed: string[]; enabled_toolsets: string[]; info?: SessionLiveInfo \| null; missing_servers: string[]; reset: boolean; unknown: string[]
- **`tools.list`** — Every toolset with its resolved tool names, flagged against the session's (or config's) enabled set.
  - params `_SessionScoped`: session_id?: string \| null
  - result `ToolsetsListResult`: toolsets: ToolsetRow[]
- **`tools.show`** — The /tools listing grouped by toolset, including tools deferred behind the tool_search bridge.
  - params `_SessionScoped`: session_id?: string \| null
  - result `ToolsShowResult`: sections: ToolShowSection[]; total: number
- **`toolsets.list`** — Toolset summaries (no tool names) for the desktop Toolsets tab.
  - params `_SessionScoped`: session_id?: string \| null
  - result `ToolsetsListResult`: toolsets: ToolsetRow[]
- **`usage.bars`** — Two-bar dollar usage view shared by /usage, /topup and /subscription; fail-open to unavailable.
  - params `ProfileParams`: profile?: string \| null
  - result `UsageModel`: ok?: boolean \| null; available: boolean; status?: string \| null; plan_name?: string \| null; renews_at?: string \| null; renews_display?: string \| null; subscription_remaining_display?: string \| null; topup_remaining_display?: string \| null; total_spendable_display?: string \| null; has_topup?: boolean \| null; plan_bar?: UsageBar \| null; topup_bar?: UsageBar \| null
- **`vault.add`** — Add a login / payment / address item to the local vault.
  - params `VaultAddParams`: profile?: string \| null; kind?: VaultKind \| null; label?: string \| null; origin?: string \| null; secret?: Record<string, unknown> \| null
  - result `VaultAddResult`: id: string
- **`vault.list`** — Metadata-only listing across the local vault and every unlocked password manager.
  - params `ProfileParams`: profile?: string \| null
  - result `VaultListResult`: items?: VaultItem[]
- **`vault.lock`** — Forget a manager's session token (every manager when no name is given).
  - params `VaultLockParams`: profile?: string \| null; name?: string \| null
  - result `VaultLockResult`: locked?: boolean
- **`vault.remove`** — Remove a local vault item by id.
  - params `VaultRemoveParams`: profile?: string \| null; id?: string \| null
  - result `VaultRemoveResult`: removed: boolean
- **`vault.source.set`** — Enable or disable an external password manager (disabling also locks it).
  - params `VaultSourceSetParams`: profile?: string \| null; name?: string \| null; enabled?: boolean \| null
  - result `VaultSourceSetResult`: name: string; enabled: boolean
- **`vault.sources`** — Status of every login source (local vault + detected password managers).
  - params `ProfileParams`: profile?: string \| null
  - result `VaultSourcesResult`: sources?: VaultSource[]
- **`vault.unlock`** — Unlock a password manager for this session with its master password.
  - params `VaultUnlockParams`: profile?: string \| null; name?: string \| null; password?: string \| null
  - result `VaultUnlockResult`: name: string; unlocked?: boolean
- **`verification.status`** — Best known verification evidence for a cwd/session; read-only, never runs checks.
  - params `VerificationStatusParams`: profile?: string \| null; session_id?: string \| null; session_key?: string \| null; cwd?: string \| null
  - result `VerificationStatusResult`: verification: VerificationStatusInfo
- **`voice.record`** — VAD-bounded push-to-talk; the transcript arrives as a voice.transcript event.
  - params `VoiceRecordParams`: action?: VoiceRecordAction; session_id?: string \| null; profile?: string \| null
  - result `VoiceRecordResult`: status: VoiceRecordStatus; reason?: string \| null
- **`voice.toggle`** — /voice parity: report, flip voice mode on/off, or toggle speech output.
  - params `VoiceToggleParams`: action?: VoiceToggleAction; profile?: string \| null
  - result `VoiceToggleResult`: enabled: boolean; record_key: string; tts: boolean; stop_hint?: string \| null; available?: boolean \| null; audio_available?: boolean \| null; stt_available?: boolean \| null; details?: string \| null
- **`voice.tts`** — Speak text through the backend TTS engine (barge-in aware).
  - params `VoiceTtsParams`: text: string; profile?: string \| null
  - result `VoiceTtsResult`: status: string
- **`wake.feed`** — Push client-captured PCM into the armed detector (mic-less remote backends).
  - params `WakeFeedParams`: pcm?: string \| null; pcm_b64?: string \| null; sample_rate?: number \| null; profile?: string \| null
  - result `WakeFeedResult`: reason?: string \| null; fed: boolean
- **`wake.pause`** — Release the mic (e.g. while the desktop's browser captures audio).
  - params `WakeControlParams`: profile?: string \| null
  - result `WakePauseResult`: reason?: string \| null; paused: boolean
- **`wake.resume`** — Reclaim the mic after a pause; no-op if the listener isn't armed.
  - params `WakeControlParams`: profile?: string \| null
  - result `WakeResumeResult`: reason?: string \| null; resumed: boolean
- **`wake.start`** — Arm the wake-word listener for the calling surface; refusals explain why.
  - params `WakeStartParams`: surface?: string \| null; persist?: boolean \| null; client_capture?: boolean \| null; session_id?: string \| null; profile?: string \| null
  - result `WakeStartResult`: started: boolean; reason?: string \| null; hint?: string \| null; phrase?: string \| null; provider?: string \| null; owner_surface?: string \| null; enabled_persisted?: boolean \| null; capture?: string \| null; sample_rate?: number \| null; frame_length?: number \| null
- **`wake.status`** — Everything a client needs to draw the wake-word state and decide whether to (re)arm.
  - params `WakeStatusParams`: surface?: string \| null; client_capture?: boolean \| null; profile?: string \| null
  - result `WakeStatusResult`: listening: boolean; owned_by_caller: boolean; owner_surface?: string \| null; phrase: string; provider: string; configured_surface: string; input_device: WakeInputDevice; available: boolean; hint: string; enabled: boolean; audio_silent: boolean; capture: string; local_input_available: boolean; sample_rate: number; frame_length: number
- **`wake.stop`** — Stop this surface's listener; persist also writes wake_word.enabled: false.
  - params `WakeStopParams`: persist?: boolean \| null; profile?: string \| null
  - result `WakeStopResult`: reason?: string \| null; stopped: boolean; disabled_persisted: boolean

### B.3 Server→client requests (the client must answer)

16 entries.

- **`approval`** — A dangerous command awaits the user's decision.
  - params `ApprovalRequestParams`: session_id: string; request_id: string; command?: string; description?: string; choices?: ApprovalChoice[]; allow_permanent?: boolean \| null; allow_session?: boolean \| null; smart_denied?: boolean \| null; tool_name?: string \| null; gateway_session_id?: string \| null
  - result `ApprovalResult`: choice: ApprovalChoice; all?: boolean \| null
- **`clarify`** — The clarify tool: ask the user 1-5 questions.
  - params `ClarifyRequestParams`: session_id: string; questions: ClarifyQuestion[]; answers?: Record<string, string \| null> \| null
  - result `ClarifyResult`: answers?: Record<string, string \| null> \| null; outcome?: 'submitted' \| 'timed_out' \| 'cancelled' \| null
- **`display.install.sudo`** — Masked sudo password for the Bot Screen package install; app-level (empty session).
  - params `DisplayInstallSudoParams`: session_id: string; profile_key: string
  - result `ValueResult`: value: string
- **`preview.act`** — Click / type / scroll / annotate inside the in-app browser preview.
  - params `PreviewActRequestParams`: session_id: string; action: string; ref?: string \| null; selector?: string \| null; text?: string \| null; key?: string \| null; submit?: boolean \| null; full?: boolean \| null; to?: string \| null; amount?: number \| null; max?: number \| null; allow_shortcut?: boolean \| null
  - result `ValueResult`: value: string
- **`preview.read`** — Read the in-app browser preview's text (JSON text answer).
  - params `ReadRangeRequestParams`: session_id: string; start?: number \| null; count?: number \| null
  - result `ValueResult`: value: string
- **`secret`** — Masked value for a named env var (skills / setup flows).
  - params `SecretRequestParams`: session_id: string; env_var: string; prompt: string; metadata?: Record<string, unknown> \| null
  - result `ValueResult`: value: string
- **`setup_beat`** — The onboarding chat's card for one beat; blocks setup_beat until answered (300 s).
  - params `SetupBeatRequestParams`: session_id: string; beat: SetupBeat; question?: string \| null; tool_call_id?: string \| null; card: SetupNameCard \| SetupThemeCard \| SetupOptionsCard \| Setup
  - result `SetupBeatResult`: answer?: SetupNameAnswer \| SetupThemeAnswer \| SetupLayoutAnswer \| ; said?: string \| null; wrote_instead?: boolean
- **`sudo`** — Masked sudo password for the terminal tool.
  - params `SudoRequestParams`: session_id: string; command?: string
  - result `ValueResult`: value: string
- **`terminal.read`** — Read the visible in-app terminal buffer (JSON text answer).
  - params `ReadRangeRequestParams`: session_id: string; start?: number \| null; count?: number \| null
  - result `ValueResult`: value: string
- **`theme.apply`** — Apply set_theme values in the window showing the session (JSON text answer).
  - params `ThemeApplyRequestParams`: session_id: string; accent?: string \| null; mode?: ThemeMode \| null; layout?: string \| null
  - result `ValueResult`: value: string
- **`theme.pick`** — Show an accent or layout picker card in the chat and wait for the pick.
  - params `ThemePickRequestParams`: session_id: string; picker: ThemePicker
  - result `ThemePickResult`: picked?: ThemeValues \| null; said?: string \| null; cancelled?: boolean \| null
- **`tour`** — Drive a guided tour highlight in the desktop renderer.
  - params `TourRequestParams`: session_id: string; action: string; surface?: string \| null; selector?: string \| null; title?: string \| null; text?: string \| null; side?: string \| null; steps?: TourStep[] \| null; step_index?: number \| null; preset?: TourPreset \| null
  - result `ValueResult`: value: string
- **`vault.code`** — A one-time / 2FA code the user reads from their device.
  - params `VaultCodeRequestParams`: session_id: string; site?: string \| null; hint?: string \| null
  - result `ValueResult`: value: string
- **`vault.save_login`** — Save a login for a site the agent is about to fill; the answer is JSON {identifier, password}.
  - params `VaultSaveLoginRequestParams`: session_id: string; origin: string; site: string
  - result `ValueResult`: value: string
- **`vault.unlock_prompt`** — Master password to unlock an external password manager for this session.
  - params `VaultUnlockRequestParams`: session_id: string; backend: string; display_name: string
  - result `ValueResult`: value: string
- **`window.read`** — Enumerate the native window below the app (JSON text answer).
  - params `EmptyRequestParams`: session_id: string
  - result `ValueResult`: value: string

### B.4 Server→client notifications (`{"method":"event","params":{"type":…,"payload":…}}`)

78 event types.

- **`agent.print`** — A status line the agent printed during a local viewer's turn.
  - payload `AgentPrintPayload`: text: string
- **`agent.terminal.output`** — Output chunk from an agent-owned background process.
  - payload `TerminalOutputPayload`: process_id: string; chunk: string
- **`approval.cancelled`** — Pending gateway approvals were dropped by interrupt/reap/teardown; the wait resolved as deny (not a user refusal).
  - payload `ApprovalCancelledPayload`: session_id: string; stored_session_id: string; reason: string; cancelled_count: number; request_ids: string[]
- **`background.complete`** — A /background side agent finished.
  - payload `SideAgentCompletePayload`: task_id: string; text: string; question?: string \| null
- **`billing.step_up.verification`** — Device-flow URL + code for the billing scope step-up; the client opens the browser.
  - payload `BillingStepUpVerificationPayload`: verification_url: string; user_code: string
- **`bot_relay.outbox.pending`** — A bot-relay outbox envelope is queued; drain it.
  - payload `ChangeSignalPayload`: `Record<string, unknown>`
- **`browser.progress`** — Browser (CDP) connect / install progress line.
  - payload `BrowserProgressPayload`: message: string; level: string
- **`btw.complete`** — A /btw side question was answered.
  - payload `SideAgentCompletePayload`: task_id: string; text: string; question?: string \| null
- **`connection.request`** — A connection operation opened on this session; the desktop renders its card.
  - payload `ConnectionRequestPayload`: op_id: string; seq: number; deadline_at: number; timeout_seconds: number; targets: ConnectionOperationTarget[]; tool_call_id?: string \| null; display?: ConnectionDisplay \| null
- **`connection.update`** — One transition or the settlement of an open connection operation.
  - payload `ConnectionUpdatePayload`: op_id: string; seq: number; deadline_at: number; settled: boolean; settled_at?: number \| null; settled_by?: ConnectionSettleReason \| null; targets: ConnectionOperationTarget[]; owner: SessionOwner \| AccountOwner; target?: string \| null; from?: ConnectionTargetState \| null; to?: ConnectionTargetState \| null; actor?: ConnectionActor \| null; detail?: string \| null
- **`cron.changed`** — cron/jobs.json moved; refetch the cron list.
  - payload `ChangeSignalPayload`: `Record<string, unknown>`
- **`display.install.done`** — The install ended (0 ok, -1 cancelled, -2 no sudo: the command to run by hand was streamed).
  - payload `DisplayInstallDonePayload`: profile_key: string; code: number; status: DisplayStatus
- **`display.install.log`** — One line of package-manager output.
  - payload `DisplayInstallLogPayload`: profile_key: string; line: string
- **`display.lease`** — The takeover lease changed hands; every client repaints.
  - payload `DisplayLeasePayload`: profile_key: string; lease: DisplayLease
- **`display.status`** — This profile's screen started or stopped (also for transitions made outside hermes serve).
  - payload `DisplayStatusPayload`: profile: string; supported: boolean; installed: boolean; missing: string[]; running: boolean; pid?: number \| null; display?: string \| null; socket?: string \| null; geometry: string; install_command?: string \| null; browser?: string \| null; blocker?: string \| null; memory_available_mb?: number \| null; memory_limit_mb?: number \| null; placement?: string; image_switch?: DisplayImageSwitch \| null; lease: DisplayLease; profile_key: string
- **`error`** — A session-level failure outside a turn (agent init, model switch, compression, resume).
  - payload `ErrorPayload`: message: string
- **`free_tier.challenge`** — The account service wants a browser challenge cleared before the free-tier token exchange (broadcast); the desktop loads ``url`` in a hidden window.
  - payload `FreeTierChallengePayload`: type: 'browser'; url: string; required: boolean; expires_in: number; message: string; attempt?: number
- **`gateway.ready`** — First frame of a connection: the resolved skin, the change-event capability and the replay epoch.
  - payload `GatewayReadyPayload`: skin: SkinPayload; change_events: boolean; replay_epoch: string; heartbeat?: boolean \| null; session_authority?: boolean \| null
- **`message.complete`** — The turn ended: final text, usage and outcome.
  - payload `MessageCompletePayload`: text?: string \| unknown; usage?: Usage \| null; status?: TurnStatus \| null; reasoning?: string \| null; warning?: string \| null; response_previewed?: boolean \| null; response_reused?: boolean \| null; response_transformed?: boolean \| null; billing?: BillingBlock \| null; failure_reason?: string \| null; rendered?: string \| null; error?: string \| null; recoverable?: boolean \| null; error_surface?: ErrorSurface \| null; partial?: boolean \| null; persisted_turn?: PersistedTurn \| null
- **`message.delta`** — One streamed chunk of the assistant reply (null text: segment boundary).
  - payload `MessageDeltaPayload`: text: string \| null; rendered?: string \| null; verbose?: boolean \| null
- **`message.interim`** — Interim assistant commentary (text beside tool calls) sealed as its own segment.
  - payload `MessageInterimPayload`: text: string; already_streamed: boolean
- **`message.reaction`** — The agent reacted to a message; paint it live.
  - payload `MessageReactionPayload`: row_id: number; reactions: MessageReaction[]; role: string
- **`message.start`** — A turn began streaming; an automation turn carries its async-result card.
  - payload `MessageStartPayload`: display_kind?: string \| null; display_text?: string \| null; notification_category?: string \| null; text?: string \| null
- **`moa.aggregating`** — The MoA aggregator started.
  - payload `MoaAggregatingPayload`: aggregator: string
- **`moa.phase`** — MoA phase transition (currently only ``aggregator``).
  - payload `MoaPhasePayload`: phase: string; refs_done?: number \| null; refs_total?: number \| null; aggregator?: string \| null
- **`moa.progress`** — MoA reference fan-out progress (n/total).
  - payload `MoaProgressPayload`: label: string; refs_done: number; refs_total: number
- **`moa.reference`** — One MoA reference model's output.
  - payload `MoaReferencePayload`: label: string; text: string; index?: number \| null; count?: number \| null
- **`notice`** — Informational one-liner for the session (capabilities refreshed).
  - payload `NoticePayload`: message: string
- **`notification.clear`** — Withdraw the notice with this key.
  - payload `NotificationClearPayload`: key: string
- **`notification.show`** — Show / replace a keyed out-of-band notice (toast or status bar).
  - payload `NotificationShowPayload`: text: string; level: string; kind: string; ttl_ms?: number \| null; key?: string \| null; id?: string \| null; detail?: string \| null
- **`onboarding.ended`** — A first-run onboarding run ended; sent to every connected window.
  - payload `OnboardingEndedPayload`: run_id: string; stored_session_id: string; outcome: OnboardingOutcome
- **`pairing.changed`** — Pairing state moved; refetch pairing.
  - payload `ChangeSignalPayload`: `Record<string, unknown>`
- **`pane.reveal`** — Focus / reveal a named desktop pane.
  - payload `PaneRevealPayload`: pane: string
- **`pet.changed`** — The active pet / its spritesheet changed (watcher).
  - payload `PetChangedPayload`: enabled: boolean; slug?: string \| null; displayName?: string \| null; scale?: number \| null; spritesheetRevision?: string \| null
- **`pet.generate.progress`** — Pet base-draft generation progress.
  - payload `PetGenerateProgressPayload`: token: string; count: number; index?: number \| null; dataUri?: string \| null
- **`pet.hatch.progress`** — Pet hatch (row drawing) progress.
  - payload `PetHatchProgressPayload`: event: string; detail?: string \| null; state?: string \| null; done?: string \| null; total?: string \| null
- **`platforms.changed`** — gateway_state.json moved; refetch platform status.
  - payload `ChangeSignalPayload`: `Record<string, unknown>`
- **`preview.close`** — Close the preview pane or one tab.
  - payload `PreviewClosePayload`: url?: string
- **`preview.open`** — Open a URL / file in the desktop preview pane.
  - payload `PreviewOpenPayload`: url: string; label?: string
- **`preview.restart.complete`** — The hidden preview-restart agent finished.
  - payload `SideAgentCompletePayload`: task_id: string; text: string; question?: string \| null
- **`preview.restart.progress`** — Progress line from the preview-restart agent.
  - payload `PreviewRestartProgressPayload`: task_id: string; text: string; level?: string \| null
- **`projects.changed`** — projects.db moved; refetch the project list + tree.
  - payload `ChangeSignalPayload`: `Record<string, unknown>`
- **`reaction`** — Affection reaction detected in the user's message (hearts etc.).
  - payload `ReactionPayload`: kind: string
- **`reasoning.available`** — A completed reasoning block (non-streaming providers).
  - payload `StreamDeltaPayload`: text: string; rendered?: string \| null; verbose?: boolean \| null
- **`reasoning.delta`** — One streamed chunk of the model's reasoning.
  - payload `StreamDeltaPayload`: text: string; rendered?: string \| null; verbose?: boolean \| null
- **`request.cancel`** — The backend withdrew an open server→client request; clear the matching card only.
  - payload `RequestCancelPayload`: id: string; method: string; reason: string
- **`review.summary`** — Background review of the last turn finished.
  - payload `ReviewSummaryPayload`: text: string
- **`session.control.update`** — Persisted goal / loop / heartbeat state changed.
  - payload `SessionControlUpdatePayload`: control: SessionControlSnapshot
- **`session.info`** — Live session settings snapshot (``server._session_info``); also the ``info`` of create/resume/activate.
  - payload `SessionLiveInfo`: model?: string; provider?: string; reasoning_effort?: string; reasoning_effort_wire?: string; service_tier?: string; fast?: boolean; yolo?: boolean; approval_mode?: string; tools?: Record<string, string[]>; skills?: Record<string, string[]>; cwd?: string; branch?: string \| null; project?: ProjectRef \| null; terminal_backend?: string; personality?: string; running?: boolean; turn_started_at?: number \| null; title?: string; stored_session_id?: string; desktop_contract?: number \| string \| null; version?: string; release_date?: string; update_behind?: unknown \| null; update_command?: string; …
- **`session.reclaimed`** — The backend reclaimed a live session out from under its clients.
  - payload `SessionReclaimedPayload`: session_id: string; stored_session_id: string; reason: string
- **`session.resume_progress`** — Deferred resume hydration progress.
  - payload `SessionResumeProgressPayload`: phase: string; status: ResumePhaseStatus; message_count?: number \| null; message?: string \| null
- **`session.title`** — Auto-titling renamed the session (``session_id`` is the stored key).
  - payload `SessionTitlePayload`: session_id: string; title: string
- **`session.usage`** — Mid-turn usage tick; message.complete carries the authoritative final usage.
  - payload `SessionUsagePayload`: usage?: Usage \| null; input_tokens?: number \| null; output_tokens?: number \| null; cache_read_tokens?: number \| null; cache_write_tokens?: number \| null; prompt_tokens?: number \| null; completion_tokens?: number \| null; total_tokens?: number \| null; reasoning_tokens?: number \| null; api_calls?: number \| null; last_prompt_tokens?: number \| null; context_tokens?: number \| null; context_length?: number \| null; config_context_length?: number \| null; context_source?: string \| null; compression_count?: number \| null; avg_latency_s?: number \| null; tokens_per_s?: number \| null; last …
- **`sessions.changed`** — state.db moved; refetch the session list.
  - payload `ChangeSignalPayload`: `Record<string, unknown>`
- **`setup.ready`** — The free-tier bootstrap finished (broadcast); the desktop's setup gate reads the record.
  - payload `SetupReadyPayload`: provider_configured: boolean; inference_provider: string; free_tier_account: boolean; free_tier_route: boolean; has_identity: boolean; other_providers: boolean; error?: string; error_code?: string \| null; retryable?: boolean \| null; retry_after?: number \| null; finished_at: number
- **`skills.changed`** — A skill root moved (installed/removed elsewhere); refetch commands.catalog from the gateway.
  - payload `ChangeSignalPayload`: `Record<string, unknown>`
- **`skin.changed`** — The active skin moved (name switch or live colour edit); repaint from this palette.
  - payload `SkinPayload`: name?: string; description?: string; colors?: Record<string, string>; light_colors?: Record<string, string>; dark_colors?: Record<string, string>; branding?: Record<string, string>; banner_logo?: string; banner_hero?: string; tool_prefix?: string; help_header?: string
- **`status.update`** — Transient status line (kind: status, lifecycle, compacting, goal, loop, heartbeat, process, …).
  - payload `StatusUpdatePayload`: kind: string; text: string
- **`subagent.complete`** — A child finished (status + observability rollup).
  - payload `SubagentEventPayload`: goal: string; task_count: number; task_index: number; subagent_id?: string \| null; parent_id?: string \| null; child_session_id?: string \| null; delegation_id?: string \| null; depth?: number \| null; model?: string \| null; tool_count?: number \| null; toolsets?: string[] \| null; input_tokens?: number \| null; output_tokens?: number \| null; reasoning_tokens?: number \| null; api_calls?: number \| null; files_read?: string[] \| null; files_written?: string[] \| null; output_tail?: SubagentOutputTailEntry[] \| null; tool_name?: string \| null; text?: string \| null; status?: SubagentStatus  …
- **`subagent.progress`** — Batched tool-name progress from a child.
  - payload `SubagentEventPayload`: goal: string; task_count: number; task_index: number; subagent_id?: string \| null; parent_id?: string \| null; child_session_id?: string \| null; delegation_id?: string \| null; depth?: number \| null; model?: string \| null; tool_count?: number \| null; toolsets?: string[] \| null; input_tokens?: number \| null; output_tokens?: number \| null; reasoning_tokens?: number \| null; api_calls?: number \| null; files_read?: string[] \| null; files_written?: string[] \| null; output_tail?: SubagentOutputTailEntry[] \| null; tool_name?: string \| null; text?: string \| null; status?: SubagentStatus  …
- **`subagent.spawn_requested`** — delegate_task accepted a child goal (before the child starts).
  - payload `SubagentEventPayload`: goal: string; task_count: number; task_index: number; subagent_id?: string \| null; parent_id?: string \| null; child_session_id?: string \| null; delegation_id?: string \| null; depth?: number \| null; model?: string \| null; tool_count?: number \| null; toolsets?: string[] \| null; input_tokens?: number \| null; output_tokens?: number \| null; reasoning_tokens?: number \| null; api_calls?: number \| null; files_read?: string[] \| null; files_written?: string[] \| null; output_tail?: SubagentOutputTailEntry[] \| null; tool_name?: string \| null; text?: string \| null; status?: SubagentStatus  …
- **`subagent.start`** — A delegated child started running.
  - payload `SubagentEventPayload`: goal: string; task_count: number; task_index: number; subagent_id?: string \| null; parent_id?: string \| null; child_session_id?: string \| null; delegation_id?: string \| null; depth?: number \| null; model?: string \| null; tool_count?: number \| null; toolsets?: string[] \| null; input_tokens?: number \| null; output_tokens?: number \| null; reasoning_tokens?: number \| null; api_calls?: number \| null; files_read?: string[] \| null; files_written?: string[] \| null; output_tail?: SubagentOutputTailEntry[] \| null; tool_name?: string \| null; text?: string \| null; status?: SubagentStatus  …
- **`subagent.thinking`** — A child's reasoning chunk.
  - payload `SubagentEventPayload`: goal: string; task_count: number; task_index: number; subagent_id?: string \| null; parent_id?: string \| null; child_session_id?: string \| null; delegation_id?: string \| null; depth?: number \| null; model?: string \| null; tool_count?: number \| null; toolsets?: string[] \| null; input_tokens?: number \| null; output_tokens?: number \| null; reasoning_tokens?: number \| null; api_calls?: number \| null; files_read?: string[] \| null; files_written?: string[] \| null; output_tail?: SubagentOutputTailEntry[] \| null; tool_name?: string \| null; text?: string \| null; status?: SubagentStatus  …
- **`subagent.tool`** — A child called a tool.
  - payload `SubagentEventPayload`: goal: string; task_count: number; task_index: number; subagent_id?: string \| null; parent_id?: string \| null; child_session_id?: string \| null; delegation_id?: string \| null; depth?: number \| null; model?: string \| null; tool_count?: number \| null; toolsets?: string[] \| null; input_tokens?: number \| null; output_tokens?: number \| null; reasoning_tokens?: number \| null; api_calls?: number \| null; files_read?: string[] \| null; files_written?: string[] \| null; output_tail?: SubagentOutputTailEntry[] \| null; tool_name?: string \| null; text?: string \| null; status?: SubagentStatus  …
- **`terminal.close`** — An agent-owned background process closed.
  - payload `TerminalClosePayload`: process_id: string
- **`thinking.delta`** — Legacy thinking-text chunk (thinking_callback).
  - payload `StreamDeltaPayload`: text: string; rendered?: string \| null; verbose?: boolean \| null
- **`tip.show`** — Point at a desktop element with a one-line tip bubble.
  - payload `TipShowPayload`: selector: string; text: string; title?: string \| null; side?: string \| null
- **`todo.updated`** — Full todo snapshot after a todo tool ran.
  - payload `TodoUpdatedPayload`: todos: unknown[]; revision: number
- **`tool.complete`** — A tool call finished: parsed result, summary, optional diff / todo snapshot.
  - payload `ToolCompletePayload`: tool_id: string; name: string; args?: Record<string, unknown> \| null; duration_s?: number \| null; result?: unknown; summary?: string \| null; result_text?: string \| null; inline_diff?: string \| null; todos?: unknown[] \| null; revision?: number \| null; labels?: ToolLabel[] \| null
- **`tool.generating`** — The model is emitting a tool call's arguments.
  - payload `ToolGeneratingPayload`: name: string
- **`tool.output_risk`** — Tool output was classified as risky (prompt-injection / secret findings).
  - payload `ToolOutputRiskPayload`: tool_id: string; name: string; risk: string; findings: string[]; redacted: boolean
- **`tool.progress`** — Live progress of a running tool call (MCP notifications/progress).
  - payload `ToolProgressPayload`: tool_id: string; name: string; progress: number; total?: number \| null; percent?: number \| null; message?: string \| null
- **`tool.start`** — A tool call began (stable id + full args).
  - payload `ToolStartPayload`: tool_id: string; name: string; context?: string \| null; args?: Record<string, unknown> \| null; args_text?: string \| null; preview?: string \| null; labels?: ToolLabel[] \| null
- **`voice.interrupted`** — Barge-in: the spoken interjection interrupted the turn; no payload.
  - payload `Record<string, never>`: `Record<string, never>`
- **`voice.partial`** — Live STT text so far while the user is still speaking.
  - payload `VoicePartialPayload`: text: string
- **`voice.status`** — Voice recorder state changed.
  - payload `VoiceStatusPayload`: state: string
- **`voice.transcript`** — A voice capture produced text (or a stop phrase / silence limit).
  - payload `VoiceTranscriptPayload`: text?: string \| null; stop_phrase?: boolean \| null; typed?: boolean \| null; no_speech_limit?: boolean \| null
- **`wake.detected`** — A wake phrase fired.
  - payload `WakeDetectedPayload`: phrase: string; profile?: string \| null; start_new_session: boolean
