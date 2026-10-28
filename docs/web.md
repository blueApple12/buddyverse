# The web app (PWA, iPhone)

The `shared` module also builds for the browser (`wasmJs`). Each install serves it from its **own** gateway, through
its own tunnel, so the page and the API share one origin: no CORS, no third-party host, nothing of anyone else's.

## Build and install

```
powershell -File tools\compile.ps1 :shared:webPlugin        # -Online the first time (downloads Node, webpack)
```
Output: `shared/build/hermes-plugin/buddyverse/` — a Hermes user plugin (static files only):
- `plugin.yaml`, `__init__.py` (no-op `register`), `dashboard/manifest.json` (hidden tab, no-op `dist/index.js`).
- `dashboard/`: `index.html`, `buddyverse.js`, two `*.wasm.json`, `composeResources/` fonts, `pwa.json` (web manifest),
  `sw.js`, icons (`tools/set-logo.py` renders them, with every other app icon, from `branding/logo.png`).

Deploy to this PC (every ship, with the PC and phone builds; the web copy goes stale otherwise):
```
powershell -File ..\bin\deploy-web.ps1 [-Build] [-Tree ..\app-rel]
```
It swaps `%LOCALAPPDATA%\hermes\plugins\buddyverse` in one folder rename (staging next to `plugins\`, never inside
it), checks the gateway now serves the new `dashboard/build.json`, and calls `GET /api/dashboard/plugins/rescan` with
the PC app's stored token (`~/.buddyverse/settings.properties`, never printed). It always targets the default home
`%LOCALAPPDATA%\hermes`, not `$HERMES_HOME` (agents run with their own profile's home there). First install only:
`HERMES_HOME="$LOCALAPPDATA/hermes" hermes plugins enable buddyverse` (hot-reloads, no restart).
URL: `<gateway or tunnel>/dashboard-plugins/buddyverse/index.html`. The PC app's Phone link sheet has the web link.

The PC installer carries the same build: `:desktopApp:setupResources` copies `:shared:webPlugin`'s output into the
jar (`setup/plugin`, first-run setup on a friend's PC), and `jvmProcessResources` depends on it, so `packageExe`
always rebuilds the web plugin from the same tree.

Version: `:shared:appVersion` generates `core/AppVersion.kt` (Android `versionName`, PC `packageVersion`, short git
hash, `+` = uncommitted changes) for every target; the More tab's last line shows it on phone, PC and web. The web
build also writes `dashboard/build.json` (`{build, version, git}`).

## How it is served (Hermes contract)

- `GET /dashboard-plugins/<plugin>/<path>` (`hermes_cli/web_routers/dashboard_ui.py::serve_plugin_asset`) serves a
  user plugin's `dashboard/` dir **without auth** (the `/dashboard-plugins/` prefix is public in
  `dashboard_auth/middleware.py`) once the plugin is in `plugins.enabled`. `Cache-Control: no-store`.
- Only browser-asset suffixes are served (`.js .json .html .png .ttf …`, no `.wasm`/`.webmanifest`): the build renames
  `*.wasm` to `*.wasm.json` and patches `buddyverse.js`; `index.html` replaces `WebAssembly.instantiateStreaming`
  with an ArrayBuffer compile so the MIME type doesn't matter. The web manifest is `pwa.json`.
- `sw.js` (scope = the plugin folder): the build stamps `__BUILD__` in `sw.js` and `index.html` with
  `<versionName>-<hash of the build's files>`, so each deploy is a byte-different `sw.js`, a new cache
  (`buddyverse-<build>`) and a new `buddyverse.js?v=<build>` URL. Pages (`index.html`, `sw.js`) are network-first
  (cache only offline); the rest is cache-first inside the build's cache (wasm names are content hashes). The new SW
  `skipWaiting`s, `clients.claim`s, deletes older `buddyverse-*` caches (and Compose's `compose_web_resources_cache`)
  and, on a real update, reloads open windows into the new build. `index.html` registers it with
  `updateViaCache: 'none'` and calls `reg.update()` whenever the page becomes visible, so a home-screen PWA resumed
  from the background (iPhone) finds a deploy without a cold start. API calls and sockets bypass it.

## Platform notes (wasmJsMain)

- Sign-in is the same native PKCE bearer flow as the APK. In a browser the authorize 302 is opaque (status 0) and the
  PKCE cookie is kept and resent by the browser itself (same origin); `HermesAuth` accepts that. Tokens live in
  `localStorage` (`buddyverse.*`); the base URL is always `location.origin`.
- Ktor-JS turns each `Sec-WebSocket-Protocol` header value into one subprotocol, so the web client splits the joined
  list (`SplitWsProtocols` plugin). Native keeps one joined header: Hermes reads only the first header line.
- SHA-256 is pure Kotlin (SubtleCrypto is async). Files: `<input type=file>` (photos/camera/files), downloads as blob
  links. Image/file paste: the DOM `paste` event (Ctrl/Cmd+V, iOS Paste in the text field; Compose's input element
  fires it) and, from the composer's Paste item, the async Clipboard API. File drop: HTML5 drag/drop listeners on the
  document while a chat is open (`ChatDevice.web.kt`). OpenRouter's catalog sends `Access-Control-Allow-Origin: *`,
  so the free-model list is fetched straight from the browser.
- Not on web: voice (no recorder actual yet; MediaRecorder could do it), QR scanning (the camera page needs
  getUserMedia + a decoder; the web is already opened from the link), in-app updater (the SW is the updater),
  notifications/push (Hermes has no Web Push endpoint), changing the gateway password (only the PC can restart the
  gateway).
- Needs WasmGC + exception handling: Safari/iOS 18.2+ or a current Chrome/Edge/Firefox. Emoji glyphs aren't bundled.
- Check it in a real browser: `node tools/web-check.mjs <url> desktop|mobile <port>` (headless Chrome over CDP, touch
  emulation on mobile, signs in with the PC app's access token only; ops in the file header).
