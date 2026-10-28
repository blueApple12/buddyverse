package app.buddyverse.desktop

import app.buddyverse.core.AppStore
import app.buddyverse.core.InstallPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit

sealed interface Startup {
    data object Starting : Startup
    data object Ready : Startup
    data class Failed(val detail: String) : Startup
}

/**
 * The PC side the app needs: the gateway on 127.0.0.1 and the Cloudflare link the phone uses. When either is down it runs
 * `<home>\buddyverse\bin\start-hermes.ps1` ([InstallPaths]) hidden (that script starts only what's down) and signs in locally with the Webapp login.
 * Nothing here registers to run at sign-in.
 */
object PcSide {
    /** This install's gateway (127.0.0.1, port 9120 unless BUDDYVERSE_PORT says otherwise). */
    val LOCAL: String get() = InstallPaths.current.local
    private val paths get() = InstallPaths.current
    val state = MutableStateFlow<Startup>(Startup.Starting)

    /** The web app (PWA, iPhone) the `buddyverse` Hermes plugin serves on this gateway (app/docs/web.md). */
    const val WEB_APP_PATH = "/dashboard-plugins/buddyverse/index.html"
    fun webAppInstalled(): Boolean = paths.webAppIndex.isFile

    /** The phone link (Cloudflare quick tunnel), as the start script last wrote it. */
    fun phoneLink(): String? = paths.phoneLinkFile.takeIf { it.isFile }
        ?.readText()?.replace("﻿", "")?.trim()?.takeIf { it.startsWith("http") }

    private fun status(url: String): Int? = runCatching {
        (URI("$url/api/status").toURL().openConnection() as HttpURLConnection).run {
            connectTimeout = 4_000; readTimeout = 8_000; instanceFollowRedirects = false
            responseCode.also { disconnect() }
        }
    }.getOrNull()

    private fun gatewayUp() = status(LOCAL) in 200..299

    /** Same rule as the script: cloudflared runs and the link answers anything but Cloudflare's 530 (tunnel gone). */
    private fun tunnelUp(): Boolean {
        val link = phoneLink() ?: return false
        val ours = paths.cloudflared.absolutePath
        val running = ProcessHandle.allProcesses().anyMatch { p -> p.info().command().orElse("").equals(ours, ignoreCase = true) }
        return running && status(link).let { it != null && it != 530 }
    }

    /** Starts what's down and waits for it. Null when all is up, else a short reason. */
    private fun ensure(noTunnel: Boolean = !paths.cloudflared.isFile): String? {
        if (gatewayUp() && (noTunnel || tunnelUp())) return null
        val script = paths.startScript
        if (!script.isFile) return if (gatewayUp()) null else "start-hermes.ps1 not found"
        // Java starts console programs with CREATE_NO_WINDOW: no window flashes.
        // Output goes to a file, not a pipe: reading a pipe would block past the timeout if anything kept it open.
        val log = File.createTempFile("start-hermes", ".log")
        try {
            val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-File", script.path, *(if (noTunnel) arrayOf("-NoTunnel") else emptyArray()))
                .hostEnv().redirectErrorStream(true).redirectOutput(log).start()
            if (!p.waitFor(5, TimeUnit.MINUTES)) {
                p.destroyForcibly()
                return if (app.buddyverse.core.HermesCanary.lacksWebapp(paths.hermesHome))
                    "the PC side took too long to start: this PC's Hermes has no Webapp (Buddyverse's beta needs a Hermes canary build; see the README)"
                else "the PC side took too long to start"
            }
            val out = runCatching { log.readText() }.getOrDefault("")
            return if (p.exitValue() == 0 || gatewayUp()) null else out.lines().lastOrNull { it.isNotBlank() }?.take(200) ?: "exit ${p.exitValue()}"
        } finally { log.delete() }
    }

    /**
     * Old installs only: a plaintext login copy (`webapp-login.txt`: `username: …` / `password: …`). Never logged.
     * New installs keep only the hash in Hermes' `.env`; the app stays signed in with its own tokens (30-day rolling
     * refresh) and the sign-in screen asks when they expire.
     */
    private fun credentials(): Pair<String, String>? {
        val f = paths.webappLogin
        if (!f.isFile) return null
        val m = f.readLines().associate { l -> l.replace("﻿", "").substringBefore(':').trim().lowercase() to l.substringAfter(':', "").trim() }
        return (m["username"] ?: return null) to (m["password"] ?: return null)
    }

    /** Bring the PC side up, then keep the app signed in locally (also after a sign-out or an expired session). */
    fun start(store: AppStore) = store.scope.launch {
        store.presetLink(LOCAL) // the sign-in screen starts with this PC's own address
        run()
        if (store.paired.value) store.gateway.retryNow() // it was backing off while the gateway was down
        store.paired.collect { paired -> if (!paired || store.savedLink != LOCAL) signInLocal(store) }
    }

    /** The overlay's Retry: start what's down again, then the local sign-in if that is what failed. */
    suspend fun retry(store: AppStore) {
        if (!run()) return
        if (!store.paired.value || store.savedLink != LOCAL) signInLocal(store)
        store.gateway.retryNow()
    }

    private suspend fun signInLocal(store: AppStore) {
        if (store.paired.value && store.savedLink == LOCAL) return
        val (user, pass) = withContext(Dispatchers.IO) { credentials() } ?: return // no login file: the Connect screen asks
        val err = store.signIn(LOCAL, user, pass) ?: return
        state.value = Startup.Failed("The saved PC login was rejected (${err.message}). Check webapp-login.txt or sign in by hand.")
    }

    /**
     * Only for a button the user pressed (Settings ▸ Change password): stop the gateway, then bring it back the usual way
     * ([run] → start-hermes.ps1 starts what's down; the tunnel and the phone link stay). Null when it's up again.
     */
    suspend fun restartGateway(): String? {
        // Bot profiles must stay served: remember which were parked BEFORE, and re-serve any this restart parked.
        val parkedBefore = profileDirs().filter { File(it, "gateway.parked").isFile }.map { it.name }.toSet()
        val stopped = withContext(Dispatchers.IO) {
            runCatching {
                // The HOST gateway (default profile, clean env): with a bot's HERMES_HOME inherited, `gateway stop` only
                // parks that bot (multiplex mode) and the host keeps running with the old password.
                val p = ProcessBuilder(paths.hermesExe.path, "-p", "default", "gateway", "stop").hostEnv().redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                if (!p.waitFor(60, TimeUnit.SECONDS)) p.destroyForcibly()
            }
            var down = false
            for (i in 0 until 30) { if (!gatewayUp()) { down = true; break }; Thread.sleep(1_000) }
            down
        }
        if (!stopped) { reserve(parkedBefore); return "the gateway did not stop" }
        while (!run()) kotlinx.coroutines.delay(1_000) // a start already in progress: wait for it, then run once more
        reserve(parkedBefore)
        return (state.value as? Startup.Failed)?.detail
    }

    private fun profileDirs(): List<File> = File(paths.hermesHome, "profiles").listFiles { f -> f.isDirectory }?.toList().orEmpty()

    /** Re-serves every bot profile that is parked now but wasn't before (hot re-serve; the host isn't touched). */
    private suspend fun reserve(parkedBefore: Set<String>) = withContext(Dispatchers.IO) {
        for (d in profileDirs()) {
            if (d.name in parkedBefore || !File(d, "gateway.parked").isFile) continue
            runCatching {
                val p = ProcessBuilder(paths.hermesExe.path, "-p", d.name, "gateway", "start").hostEnv().redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                if (!p.waitFor(60, TimeUnit.SECONDS)) p.destroyForcibly()
            }
        }
    }

    /** The PC's own Hermes, whatever started this app: drop inherited HERMES_* (an agent's profile home, session ids). */
    private fun ProcessBuilder.hostEnv(): ProcessBuilder = apply { paths.childEnv(environment()) }

    private val running = Mutex()

    /** One start at a time: a second call (Retry while starting) returns false and does nothing. */
    suspend fun run(noTunnel: Boolean = false): Boolean {
        if (!running.tryLock()) return false
        try {
            state.value = Startup.Starting
            val err = withContext(Dispatchers.IO) { if (noTunnel) ensure(true) else ensure() }
            state.value = if (err == null) Startup.Ready else Startup.Failed(err)
        } finally { running.unlock() }
        return true
    }
}
