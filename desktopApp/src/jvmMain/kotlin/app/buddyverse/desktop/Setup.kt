package app.buddyverse.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.buddyverse.core.AccountRules
import app.buddyverse.core.AccountSetup
import app.buddyverse.core.AppStore
import app.buddyverse.core.AuthError
import app.buddyverse.core.DashboardPassword
import app.buddyverse.core.SetupArgs
import app.buddyverse.core.AppUpdate
import app.buddyverse.core.InstallPaths
import app.buddyverse.core.ReleaseFeed
import app.buddyverse.core.WebappLogin
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.Icons
import app.buddyverse.design.PillButton
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.HebrewStrings
import app.buddyverse.i18n.S
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * First run on a clean PC: the user picks this PC's login, then it installs this PC's own Hermes Agent (the official
 * installer, non-interactive), cloudflared, the start script, an empty vault, the login (scrypt hash only, in Hermes'
 * `.env`) and the web app plugin, starts the PC side, shows the phone links and offers the model connection. Each step
 * skips what's already there, so Retry (or a second run) resumes. Nothing registers to run at sign-in; nothing comes
 * from anyone else's PC. Passwords stay in memory until the first sign-in and are never logged.
 */
object Setup {
    const val HERMES_INSTALLER = "https://hermes-agent.nousresearch.com/install.ps1"
    const val CLOUDFLARED = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"
    private val p get() = InstallPaths.current

    /** True when this PC lacks a piece the app needs (a finished install has them all: no setup). */
    fun needed(): Boolean = with(p) {
        !hermesExe.isFile || !startScript.isFile || !cloudflared.isFile || existingUser() == null || !webAppIndex.isFile || !brain.isDirectory
    }

    enum class Step { Account, Hermes, Cloudflared, Files, Login, Plugin, Start, Phone, Model }
    sealed interface Status {
        data object Running : Status
        data object WaitingForUser : Status
        data class Failed(val detail: String) : Status
    }
    data class State(val step: Step = Step.Account, val status: Status = Status.WaitingForUser, val log: List<String> = emptyList())
    val state = MutableStateFlow(State())

    /** The login being set up: [isNew] = create it; else it's the login this PC's Hermes already has. */
    class Account(val user: String, val password: String, val isNew: Boolean)
    @Volatile private var account: Account? = null
    /** This run wrote a new login into `.env`: a gateway that was already running hasn't loaded it yet. */
    @Volatile private var createdLogin = false
    /** Headless `--skip-phone`: no cloudflared, gateway only. */
    @Volatile var skipPhone = false
    /** Extra log sink (headless: stdout + setup.log). */
    @Volatile var echo: ((String) -> Unit)? = null

    private fun log(line: String) {
        state.value = state.value.copy(log = (state.value.log + line).takeLast(14))
        echo?.invoke(line)
    }

    private fun envText() = runCatching { p.hermesEnv.takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()
    private fun configText() = runCatching { p.hermesConfig.takeIf { it.isFile }?.readText() }.getOrNull()

    /** The username of the login this PC's Hermes already has, or null (a new install). */
    fun existingUser(): String? = AccountSetup.existingUser(envText(), configText())

    /** True when [password] fits the login Hermes already has (checked against the `.env` hash). */
    fun existingMatches(password: String): Boolean = AccountSetup.matchesExisting(envText(), password)

    fun setAccount(a: Account) { account = a }

    /** Runs the automatic steps from [from] (default: right after the account form) up to the Start step. */
    suspend fun runFrom(from: Step = Step.Hermes): Boolean = withContext(Dispatchers.IO) {
        val auto = listOf(Step.Hermes, Step.Cloudflared, Step.Files, Step.Login, Step.Plugin)
        for (s in auto.dropWhile { it != from }) {
            state.value = state.value.copy(step = s, status = Status.Running)
            val err = runCatching { run(s) }.fold({ it }, { it.message ?: it.toString() })
            if (err != null) { state.value = state.value.copy(status = Status.Failed(err)); log("failed: $err"); return@withContext false }
        }
        true
    }

    /**
     * Start the gateway (and the phone link unless [skipPhone]), then check the login works on it. [signIn] = the app's
     * own sign-in (GUI); null = only check it (headless). The password is dropped from memory afterwards.
     */
    suspend fun start(signIn: (suspend (String, String) -> AuthError?)?): Boolean {
        state.value = state.value.copy(step = Step.Start, status = Status.Running)
        log("starting the PC side on ${PcSide.LOCAL}")
        PcSide.run(noTunnel = skipPhone)
        val s = PcSide.state.value
        if (s is Startup.Failed) { state.value = state.value.copy(status = Status.Failed(s.detail)); log("failed: ${s.detail}"); return false }
        val a = account
        if (a != null) {
            // The login provider registers during the gateway's plugin start-up, maybe just after /api/status answers.
            val attempt: suspend (String, String) -> AuthError? = signIn ?: { u, pw -> DashboardPassword.tryLogin(PcSide.LOCAL, u, pw) }
            suspend fun tryAll(): AuthError? {
                var e: AuthError? = null
                for (i in 0 until 8) {
                    e = attempt(a.user, a.password) ?: return null
                    if (e is AuthError.BadCredentials) return e
                    delay(2_000L + i * 1_000L)
                }
                return e
            }
            var err = tryAll()
            if (err != null && createdLogin && err !is AuthError.BadCredentials) {
                // An existing Hermes whose gateway was already running reads the login only when it starts. Setup never
                // stops a running gateway by itself (it may be serving other bots): it says what to do.
                val why = "Hermes was already running, so it hasn't loaded your new login yet. Restart it (in a terminal: " +
                    "hermes gateway restart), then press Retry."
                state.value = state.value.copy(status = Status.Failed(why)); log("failed: $why"); return false
            }
            if (err != null) {
                val why = if (err is AuthError.BadCredentials) "the gateway rejected the login" else (err.message ?: "sign-in failed")
                state.value = state.value.copy(status = Status.Failed(why)); log("failed: $why"); return false
            }
            log("signed in as ${a.user}")
            account = null
        }
        state.value = State(if (skipPhone) Step.Model else Step.Phone, Status.WaitingForUser)
        return true
    }

    fun toModelStep() { state.value = State(Step.Model, Status.WaitingForUser) }

    /** One step; null = done, else the reason it failed. */
    private fun run(s: Step): String? = when (s) {
        Step.Hermes -> if (p.hermesExe.isFile) null else installHermes()
        Step.Cloudflared -> if (skipPhone || p.cloudflared.isFile) null else {
            log("cloudflared-windows-amd64.exe ← github.com/cloudflare/cloudflared")
            download(CLOUDFLARED, p.cloudflared)
            if (p.cloudflared.length() < 1_000_000) { p.cloudflared.delete(); "cloudflared download is too small" } else null
        }
        Step.Files -> {
            p.bin.mkdirs()
            copyResource("bin/start-hermes.ps1", p.startScript) // always the current one: an update may change it
            if (!File(p.root, "AGENTS.md").isFile) copyResource("root/AGENTS.md", File(p.root, "AGENTS.md"))
            // An empty vault from the template, only when there is none: an existing brain is never touched.
            if (!p.brain.isDirectory) {
                files().filter { it.startsWith("vault/") }.forEach { copyResource(it, File(p.brain, it.removePrefix("vault/"))) }
                File(p.brain, "inbox\\done").mkdirs()
                log("empty vault → ${p.brain}")
            }
            null
        }
        Step.Login -> makeLogin()
        Step.Plugin -> {
            // Always the copy in this build: the web app must match the PC app's version.
            files().filter { it.startsWith("plugin/") }.forEach { copyResource(it, File(p.pluginDir, it.removePrefix("plugin/"))) }
            log("web app plugin → ${p.pluginDir}")
            hermes("plugins", "enable", "buddyverse")
        }
        Step.Account, Step.Start, Step.Phone, Step.Model -> null
    }

    private fun installHermes(): String? {
        val tmp = File.createTempFile("hermes-install", ".ps1")
        val out = File.createTempFile("hermes-install", ".log")
        try {
            log("install.ps1 ← hermes-agent.nousresearch.com")
            download(HERMES_INSTALLER, tmp)
            val pb = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", tmp.path,
                "-NonInteractive", "-HermesHome", p.hermesHome.path, "-InstallDir", File(p.hermesHome, "hermes-agent").path)
                .redirectErrorStream(true).redirectOutput(out)
            p.childEnv(pb.environment())
            pb.environment()["HERMES_NONINTERACTIVE"] = "1"
            val proc = pb.start()
            var shown = 0
            while (!proc.waitFor(1, TimeUnit.SECONDS)) shown = tail(out, shown)
            tail(out, shown)
            return when {
                proc.exitValue() != 0 -> "the Hermes installer stopped (exit ${proc.exitValue()})"
                !p.hermesExe.isFile -> "the Hermes installer finished but ${p.hermesExe} is missing"
                else -> null
            }
        } finally { tmp.delete(); out.delete() }
    }

    /** Streams new installer output lines into the visible log; returns the number of lines shown so far. */
    private fun tail(f: File, shown: Int): Int {
        val lines = runCatching { f.readLines() }.getOrDefault(emptyList()).map { it.replace(Regex("\u001B\\[[0-9;]*[A-Za-z]"), "").trimEnd() }
        lines.drop(shown).filter { it.isNotBlank() }.forEach { log(it.take(160)) }
        return lines.size
    }

    /** The login: a new one goes into `.env` as username + signing secret + scrypt hash; an existing one must match. */
    private fun makeLogin(): String? {
        val a = account
        val env = envText()
        val existing = AccountSetup.existingUser(env, configText())
        when {
            existing != null && a != null && !a.isNew -> if (!AccountSetup.matchesExisting(env, a.password)) return WRONG_PASSWORD
            existing != null && a != null && a.isNew -> return "this PC's Hermes already has a login (user \"$existing\"); use that one"
            existing == null && a == null -> return "no login was chosen"
            existing == null && a != null -> {
                p.hermesEnv.parentFile.mkdirs()
                DashboardPassword.writeAtomic(p.hermesEnv, AccountSetup.envWithNewLogin(env, a.user, DashboardPassword.hash(a.password), WebappLogin.randomToken(48)))
                log("login created for \"${a.user}\" (only a scrypt hash of the password is stored)")
                createdLogin = true
            }
        }
        hermes("config", "set", "webapp.enabled", "true")?.let { return it }
        if (p.port != InstallPaths.DEFAULT_PORT) hermes("config", "set", "webapp.port", p.port.toString())?.let { return it }
        return null
    }
    const val WRONG_PASSWORD = "that password doesn't match this PC's login"

    /** `hermes <args>` for this PC's default profile; null on success. */
    private fun hermes(vararg args: String): String? {
        val out = File.createTempFile("hermes", ".log")
        try {
            val pb = ProcessBuilder(listOf(p.hermesExe.path) + args).redirectErrorStream(true).redirectOutput(out)
            p.childEnv(pb.environment())
            pb.environment()["HERMES_NONINTERACTIVE"] = "1"
            val proc = pb.start()
            if (!proc.waitFor(3, TimeUnit.MINUTES)) { proc.destroyForcibly(); return "hermes ${args.joinToString(" ")} took too long" }
            log("hermes ${args.joinToString(" ")}: exit ${proc.exitValue()}")
            return if (proc.exitValue() == 0) null
            else "hermes ${args.joinToString(" ")} failed: " + runCatching { out.readLines().lastOrNull { it.isNotBlank() } }.getOrNull().orEmpty().take(200)
        } finally { out.delete() }
    }

    /** True once `config.yaml` names a model provider (the app's Connect a model, or `hermes model`, wrote it). */
    fun modelConfigured(): Boolean = runCatching {
        p.hermesConfig.readLines().any { Regex("^\\s+provider:\\s*\"?[A-Za-z0-9]").containsMatchIn(it) }
    }.getOrDefault(false)

    private fun files(): List<String> =
        Setup::class.java.getResourceAsStream("/setup/files.txt")?.bufferedReader()?.readLines()?.filter { it.isNotBlank() }
            ?: error("setup files are missing from this build")

    private fun copyResource(name: String, dest: File) {
        val input = Setup::class.java.getResourceAsStream("/setup/$name") ?: error("missing setup file $name")
        dest.parentFile.mkdirs()
        input.use { Files.copy(it, dest.toPath(), REPLACE_EXISTING) }
    }

    /** HTTPS download to a temp file, then moved over [dest] (follows GitHub's redirects). */
    fun download(url: String, dest: File) {
        var u = URI(url).toURL()
        repeat(6) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Buddyverse-Setup")
            }
            when (c.responseCode) {
                in 300..399 -> { u = URI(url).resolve(c.getHeaderField("Location")).toURL(); c.disconnect(); return@repeat }
                in 200..299 -> {
                    dest.parentFile.mkdirs()
                    val tmp = File(dest.parentFile, dest.name + ".part")
                    c.inputStream.use { Files.copy(it, tmp.toPath(), REPLACE_EXISTING) }
                    Files.move(tmp.toPath(), dest.toPath(), REPLACE_EXISTING)
                    return
                }
                else -> error("download failed (${c.responseCode}): $url")
            }
        }
        error("too many redirects: $url")
    }
}

/**
 * `Buddyverse.exe --setup --username <u> --password-file <path|-> [--skip-phone]`: the same first-run steps with no
 * window, for scripts, CI and AI agents (docs/AI-SETUP-PROMPT.md). Prints progress to stdout and `<root>\setup.log`.
 * Exit codes: 0 done (a missing model is not an error: it prints a note), 1 a setup step failed (run it again: it resumes),
 * 2 bad options or a login that breaks the rules, 3 this PC already has a different login or the password doesn't match.
 */
object HeadlessSetup {
    const val OK = 0
    const val STEP_FAILED = 1
    const val BAD_INPUT = 2
    const val LOGIN_MISMATCH = 3

    fun run(args: Array<String>): Int {
        val p = InstallPaths.current
        p.root.mkdirs()
        val logFile = p.setupLog
        fun say(raw: String) {
            // Plain ASCII: a redirected Windows console/log file may not be UTF-8.
            val line = raw.replace("←", "<-").replace("→", "->").replace("·", "|").replace("…", "...")
            println(line)
            runCatching { logFile.appendText(line + System.lineSeparator()) }
        }
        say("Buddyverse setup (headless) · install root ${p.root} · Hermes home ${p.hermesHome} · gateway ${p.local}")
        val a = SetupArgs.parse(args.toList(), System.getenv()).getOrElse { say("error: ${it.message}"); return BAD_INPUT }
        val password = when {
            a.passwordFile == "-" -> SetupArgs.firstLine(System.`in`.readBytes().toString(Charsets.UTF_8))
            a.passwordFile != null -> {
                val f = File(a.passwordFile)
                if (!f.isFile) { say("error: password file not found: ${a.passwordFile}"); return BAD_INPUT }
                SetupArgs.firstLine(f.readText(Charsets.UTF_8))
            }
            else -> a.passwordEnv ?: run { say("error: give --password-file <path|-> (or set ${SetupArgs.ENV_PASSWORD_FILE} / ${SetupArgs.ENV_PASSWORD})"); return BAD_INPUT }
        }
        val existing = Setup.existingUser()
        val user = a.username?.trim()
        if (existing != null) {
            if (user != null && user != existing) { say("error: this PC already has a login for user \"$existing\" (not \"$user\")"); return LOGIN_MISMATCH }
            if (!Setup.existingMatches(password)) { say("error: ${Setup.WRONG_PASSWORD}"); return LOGIN_MISMATCH }
            Setup.setAccount(Setup.Account(existing, password, isNew = false))
            say("using the existing login \"$existing\"")
        } else {
            if (user == null) { say("error: give --username <name> (or set ${SetupArgs.ENV_USER})"); return BAD_INPUT }
            AccountRules.check(user, password, null)?.let { say("error: ${problemText(it)}"); return BAD_INPUT }
            Setup.setAccount(Setup.Account(user, password, isNew = true))
        }
        Setup.skipPhone = a.skipPhone
        Setup.echo = { say("  $it") }
        val ok = kotlinx.coroutines.runBlocking { Setup.runFrom() && Setup.start(null) }
        if (!ok) {
            val st = Setup.state.value
            say("FAILED at step ${st.step}: ${(st.status as? Setup.Status.Failed)?.detail}")
            return if ((st.status as? Setup.Status.Failed)?.detail == Setup.WRONG_PASSWORD) LOGIN_MISMATCH else STEP_FAILED
        }
        if (!a.skipPhone) PcSide.phoneLink()?.let { say("phone link: $it (pair the phone from the PC app's Phone button)") }
        if (Setup.modelConfigured()) say("model: connected")
        else say("model: not connected yet. Open Buddyverse > More > Models > Connect a model, or run: hermes model")
        say("done. Open Buddyverse and sign in once with your username and password.")
        return OK
    }

    fun problemText(p: AccountRules.Problem): String = when (p) {
        AccountRules.Problem.UserEmpty -> "the username is empty"
        AccountRules.Problem.UserChars -> "the username may use only letters, digits and . _ @ -"
        AccountRules.Problem.UserLong -> "the username is longer than ${AccountRules.MAX_USER} characters"
        AccountRules.Problem.PassShort -> "the password needs at least ${AccountRules.MIN_PASSWORD} characters"
        AccountRules.Problem.PassLong -> "the password is longer than ${AccountRules.MAX_PASSWORD} characters"
        AccountRules.Problem.PassChars -> "the password can't start or end with a space or hold a line break"
        AccountRules.Problem.Mismatch -> "the two passwords don't match"
    }
}

/**
 * A PC that doesn't build the app keeps the phone's update files (`<home>\buddyverse\Buddyverse.apk` + version json)
 * current from the public release feed. The phone still reads them only through its own PC's gateway ([AppUpdate]).
 */
object ApkFeed {
    fun syncInBackground() {
        val p = InstallPaths.current
        if (p.buildsApp) return // this PC publishes its own APK (bin\publish-apk.ps1)
        Thread({
            runCatching {
                val tmpJson = File.createTempFile("aos-version", ".json")
                try {
                    Setup.download(ReleaseFeed.url(ReleaseFeed.VERSION_JSON), tmpJson)
                    val text = tmpJson.readText()
                    val remote = AppUpdate.parse(text) ?: return@runCatching
                    if (!ReleaseFeed.shouldDownload(remote, p.versionJson.takeIf { it.isFile }?.readText())) return@runCatching
                    val apk = File(p.root, "Buddyverse.apk.download")
                    Setup.download(ReleaseFeed.url(ReleaseFeed.APK), apk)
                    val sha = MessageDigest.getInstance("SHA-256").let { md -> apk.inputStream().use { s -> val b = ByteArray(65536); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }; AppUpdate.hex(md.digest()) }
                    if (sha != remote.sha256) { apk.delete(); return@runCatching }
                    // APK first, then the json that points at it (same order as publish-apk.ps1).
                    Files.move(apk.toPath(), p.apk.toPath(), REPLACE_EXISTING)
                    Files.copy(tmpJson.toPath(), p.versionJson.toPath(), REPLACE_EXISTING)
                } finally { tmpJson.delete() }
            }
        }, "apk-feed").apply { isDaemon = true; start() }
    }
}

private class SetupStrings(
    val title: String, val intro: String, val steps: Map<Setup.Step, String>, val failed: String, val retry: String,
    val accountTitle: String, val accountBody: String, val existingBody: (String) -> String, val user: String, val password: String,
    val repeat: String, val accountGo: String, val problem: (AccountRules.Problem) -> String, val wrongPassword: String,
    val modelTitle: String, val modelBody: String, val modelOpen: String, val modelLater: String, val modelDone: String, val finish: String,
    val phoneTitle: String, val phoneBody: String, val android: String, val androidHint: String, val pair: String, val pairHint: String,
    val web: String, val webHint: String, val noLink: String, val next: String, val signInHint: String,
)

private val setupEn = SetupStrings(
    "Set up Buddyverse on this PC", "Everything installs on this PC only, under your own Windows user. No admin rights. It takes a few minutes.",
    mapOf(
        Setup.Step.Account to "Choose your username and password", Setup.Step.Hermes to "Install Hermes Agent (the engine)",
        Setup.Step.Cloudflared to "Install the phone link (cloudflared)", Setup.Step.Files to "Create your empty brain (vault)",
        Setup.Step.Login to "Save your login", Setup.Step.Plugin to "Add the web app",
        Setup.Step.Start to "Start the PC side", Setup.Step.Phone to "Connect your phone", Setup.Step.Model to "Connect an AI model",
    ),
    "This step failed", "Retry",
    "Choose your username and password",
    "This is your Buddyverse login. You use it to sign in on your phone and in a browser. Only a scrambled (hashed) copy of the password is saved on this PC.",
    { u -> "This PC already has a Buddyverse login for \"$u\". Enter its password to continue." },
    "Username", "Password", "Password again", "Continue",
    { when (it) {
        AccountRules.Problem.UserEmpty -> "Choose a username."
        AccountRules.Problem.UserChars -> "Use only letters, digits and . _ @ - in the username."
        AccountRules.Problem.UserLong -> "The username is too long (${AccountRules.MAX_USER} characters at most)."
        AccountRules.Problem.PassShort -> "The password needs at least ${AccountRules.MIN_PASSWORD} characters."
        AccountRules.Problem.PassLong -> "The password is too long (${AccountRules.MAX_PASSWORD} characters at most)."
        AccountRules.Problem.PassChars -> "The password can't start or end with a space."
        AccountRules.Problem.Mismatch -> "The two passwords don't match."
    } },
    "That password doesn't match this PC's login.",
    "Connect an AI model",
    "Your bots need a model to think with. Add an API key or sign in to a provider (OpenRouter, Anthropic, OpenAI, Google Gemini and more), then pick a default model. You can change it any time in Settings.",
    "Connect a model", "Later", "A model is connected. You can change it any time in More > Models.", "Finish",
    "Connect your phone", "Your phone talks only to this PC, through its own link. You can skip this and do it later with the Phone button.",
    "Android app", "Scan to download the app, install it, then scan the pairing code below in the app.",
    "Pairing code", "In the app on your phone: scan this code, then sign in.",
    "iPhone or any browser", "Open in Safari → Share → Add to Home Screen.",
    "The phone link isn't ready yet…", "Next",
    "Sign in on the phone with the username and password you chose.",
)

private val setupHe = SetupStrings(
    "התקנת Buddyverse במחשב הזה", "הכל מותקן רק במחשב הזה, תחת המשתמש שלך ב-Windows. בלי הרשאות מנהל. זה לוקח כמה דקות.",
    mapOf(
        Setup.Step.Account to "בחירת שם משתמש וסיסמה", Setup.Step.Hermes to "התקנת Hermes Agent (המנוע)",
        Setup.Step.Cloudflared to "התקנת הקישור לטלפון (cloudflared)", Setup.Step.Files to "יצירת מוח ריק (כספת)",
        Setup.Step.Login to "שמירת ההתחברות", Setup.Step.Plugin to "הוספת אפליקציית הווב",
        Setup.Step.Start to "הפעלת צד המחשב", Setup.Step.Phone to "חיבור הטלפון", Setup.Step.Model to "חיבור מודל AI",
    ),
    "השלב הזה נכשל", "לנסות שוב",
    "בחירת שם משתמש וסיסמה",
    "זו ההתחברות שלך ל-Buddyverse. משתמשים בה כדי להתחבר בטלפון ובדפדפן. במחשב נשמר רק עותק מוצפן (hash) של הסיסמה.",
    { u -> "במחשב הזה כבר יש התחברות ל-Buddyverse בשם \"$u\". כדי להמשיך, מקלידים את הסיסמה שלה." },
    "שם משתמש", "סיסמה", "שוב את הסיסמה", "להמשיך",
    { when (it) {
        AccountRules.Problem.UserEmpty -> "צריך לבחור שם משתמש."
        AccountRules.Problem.UserChars -> "בשם המשתמש מותר רק אותיות באנגלית, ספרות ו- . _ @ -"
        AccountRules.Problem.UserLong -> "שם המשתמש ארוך מדי (עד ${AccountRules.MAX_USER} תווים)."
        AccountRules.Problem.PassShort -> "הסיסמה צריכה לפחות ${AccountRules.MIN_PASSWORD} תווים."
        AccountRules.Problem.PassLong -> "הסיסמה ארוכה מדי (עד ${AccountRules.MAX_PASSWORD} תווים)."
        AccountRules.Problem.PassChars -> "הסיסמה לא יכולה להתחיל או להסתיים ברווח."
        AccountRules.Problem.Mismatch -> "שתי הסיסמאות לא זהות."
    } },
    "הסיסמה לא מתאימה להתחברות של המחשב הזה.",
    "חיבור מודל AI",
    "הבוטים צריכים מודל כדי לחשוב. מוסיפים מפתח API או מתחברים לספק (OpenRouter, Anthropic, OpenAI, Google Gemini ועוד), ובוחרים מודל ברירת מחדל. אפשר לשנות בכל זמן בהגדרות.",
    "חיבור מודל", "אחר כך", "מודל מחובר. אפשר לשנות בכל זמן בעוד ← מודלים.", "סיום",
    "חיבור הטלפון", "הטלפון מדבר רק עם המחשב הזה, דרך הקישור שלו. אפשר לדלג ולעשות את זה אחר כך בכפתור הטלפון.",
    "אפליקציית אנדרואיד", "סורקים כדי להוריד את האפליקציה, מתקינים, ואז סורקים באפליקציה את קוד הצימוד למטה.",
    "קוד צימוד", "באפליקציה בטלפון: סורקים את הקוד ואז מתחברים.",
    "אייפון או כל דפדפן", "פותחים ב-Safari ← שיתוף ← הוספה למסך הבית.",
    "הקישור לטלפון עוד לא מוכן…", "הבא",
    "בטלפון מתחברים עם שם המשתמש והסיסמה שבחרת.",
)

/** The first-run screen, over the app until Finish. Failures show the step and a Retry. [onDone] true = open Connect a model. */
@Composable
fun SetupOverlay(store: AppStore, onDone: (connectModel: Boolean) -> Unit) {
    val c = Theme.colors
    val t = if (S.current === HebrewStrings) setupHe else setupEn
    val st by Setup.state.collectAsState()
    val scope = rememberCoroutineScope()
    val signIn: suspend (String, String) -> AuthError? = { u, pw -> store.signIn(PcSide.LOCAL, u, pw) }
    // Once the PC side runs and the app is signed in, PcSide keeps it that way (restarts what stops, signs in again).
    fun go(from: Setup.Step = Setup.Step.Hermes) = scope.launch { if (Setup.runFrom(from) && Setup.start(signIn)) PcSide.start(store) }
    Box(Modifier.fillMaxSize().background(c.ground).pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Txt(t.title, Theme.type.title, c.text)
                Spacer(Modifier.height(6.dp))
                Txt(t.intro, Theme.type.callout, c.secondary)
                Spacer(Modifier.height(20.dp))
                Setup.Step.entries.forEach { s ->
                    val done = s.ordinal < st.step.ordinal
                    val now = s == st.step
                    val failed = now && st.status is Setup.Status.Failed
                    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            when {
                                done -> Icon(Icons.check, c.accent, 22.dp)
                                failed -> Icon(Icons.error, c.error, 22.dp)
                                now -> Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(c.accent))
                                else -> Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(c.tertiary))
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Txt(t.steps.getValue(s), if (now) Theme.type.headline else Theme.type.callout, if (done || now) c.text else c.secondary)
                    }
                }
                Spacer(Modifier.height(16.dp))
                when (val status = st.status) {
                    is Setup.Status.Failed -> {
                        Txt("${t.failed}: ${t.steps.getValue(st.step)}", Theme.type.headline, c.errorText)
                        Spacer(Modifier.height(4.dp))
                        Txt(status.detail, Theme.type.code, c.text)
                        Spacer(Modifier.height(12.dp))
                        PillButton(t.retry, {
                            when {
                                // A wrong password for an existing login: back to the form.
                                status.detail == Setup.WRONG_PASSWORD -> Setup.state.value = Setup.State()
                                st.step == Setup.Step.Start -> scope.launch { if (Setup.start(signIn)) PcSide.start(store) }
                                else -> go(st.step)
                            }
                        }, haptic = Haptic.Confirm)
                    }
                    else -> when (st.step) {
                        Setup.Step.Account -> AccountStep(t) { go() }
                        Setup.Step.Phone -> PhoneStep(t) { Setup.toModelStep() }
                        Setup.Step.Model -> ModelStep(t, onDone)
                        else -> {}
                    }
                }
                if (st.log.isNotEmpty() && st.step.ordinal in Setup.Step.Hermes.ordinal..Setup.Step.Start.ordinal) {
                    Spacer(Modifier.height(16.dp))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(12.dp)) {
                        st.log.forEach { Txt(it, Theme.type.code, c.secondary, maxLines = 1) }
                    }
                }
            }
        }
    }
}

/** The login form: a new username + password (twice), or the password of the login this PC's Hermes already has. */
@Composable
private fun AccountStep(t: SetupStrings, onContinue: () -> Unit) {
    val c = Theme.colors
    val existing = remember { Setup.existingUser() }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit() {
        if (existing != null) {
            if (!Setup.existingMatches(pass)) { error = t.wrongPassword; return }
            Setup.setAccount(Setup.Account(existing, pass, isNew = false))
        } else {
            AccountRules.check(user.trim(), pass, again)?.let { error = t.problem(it); return }
            Setup.setAccount(Setup.Account(user.trim(), pass, isNew = true))
        }
        pass = ""; again = ""
        onContinue()
    }
    Txt(t.accountTitle, Theme.type.headline, c.text)
    Spacer(Modifier.height(4.dp))
    Txt(if (existing != null) t.existingBody(existing) else t.accountBody, Theme.type.callout, c.secondary)
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.widthIn(max = 420.dp)) {
        if (existing == null) Field(user, { user = it; error = null }, t.user)
        Field(pass, { pass = it; error = null }, t.password, password = true)
        if (existing == null) Field(again, { again = it; error = null }, t.repeat, password = true)
        error?.let { Txt(it, Theme.type.callout, c.errorText) }
        PillButton(t.accountGo, ::submit, haptic = Haptic.Confirm)
    }
}

@Composable
private fun ModelStep(t: SetupStrings, onDone: (Boolean) -> Unit) {
    val c = Theme.colors
    val configured by produceState(Setup.modelConfigured()) { while (true) { delay(3_000); value = withContext(Dispatchers.IO) { Setup.modelConfigured() } } }
    Txt(t.modelTitle, Theme.type.headline, c.text)
    Spacer(Modifier.height(4.dp))
    Txt(if (configured) t.modelDone else t.modelBody, Theme.type.callout, c.secondary)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (configured) PillButton(t.finish, { onDone(false) }, haptic = Haptic.Confirm)
        else {
            PillButton(t.modelOpen, { onDone(true) }, haptic = Haptic.Confirm)
            PillButton(t.modelLater, { onDone(false) }, kind = ButtonKind.Quiet)
        }
    }
}

@Composable
private fun PhoneStep(t: SetupStrings, onNext: () -> Unit) {
    val c = Theme.colors
    val link by produceState<String?>(null) { while (true) { value = withContext(Dispatchers.IO) { PcSide.phoneLink() }; delay(3_000) } }
    Txt(t.phoneTitle, Theme.type.headline, c.text)
    Txt(t.phoneBody, Theme.type.callout, c.secondary)
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        QrCard(t.android, t.androidHint, ReleaseFeed.url(ReleaseFeed.APK))
        val l = link
        if (l == null) Txt(t.noLink, Theme.type.callout, c.secondary, Modifier.padding(top = 40.dp))
        else QrCard(t.pair, t.pairHint, l)
    }
    link?.let { l ->
        Spacer(Modifier.height(16.dp))
        Txt(t.web, Theme.type.headline, c.text)
        Txt(l + PcSide.WEB_APP_PATH, Theme.type.code, c.text, maxLines = 2)
        Txt(t.webHint, Theme.type.caption, c.secondary)
    }
    Spacer(Modifier.height(16.dp))
    Txt(t.signInHint, Theme.type.callout, c.secondary)
    Spacer(Modifier.height(20.dp))
    PillButton(t.next, onNext, haptic = Haptic.Confirm)
}

@Composable
private fun QrCard(title: String, hint: String, text: String) {
    val c = Theme.colors
    Column(Modifier.width(240.dp)) {
        Txt(title, Theme.type.headline, c.text)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp)) { SetupQr(text, Modifier.size(180.dp)) }
        Spacer(Modifier.height(6.dp))
        Txt(hint, Theme.type.caption, c.secondary)
    }
}

@Composable
private fun SetupQr(text: String, modifier: Modifier) {
    val m = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0)) }
    Canvas(modifier) {
        val cell = kotlin.math.floor(size.minDimension / m.width)
        val o = (size.minDimension - cell * m.width) / 2
        for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) drawRect(Color.Black, Offset(o + x * cell, o + y * cell), Size(cell, cell))
    }
}
