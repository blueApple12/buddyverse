package app.buddyverse.core

import java.io.File

/**
 * Where one install lives, derived from the PC user's own home: every install is its own system (its own Hermes,
 * gateway, tunnel, vault). Nothing here names a particular user.
 * - `<home>\buddyverse`: the install root (`bin\` start script + cloudflared + phone link, `brain\` vault, the phone APK).
 * - `<LOCALAPPDATA>\hermes`: Hermes Agent's home (its official installer's default).
 *
 * Overrides, for a second install beside an existing one (tests, CI, a clean-machine check). They are deliberately
 * not `HERMES_HOME`: an app started from an agent's shell inherits that agent's profile home.
 * - `BUDDYVERSE_HOME`: the install root.
 * - `BUDDYVERSE_HERMES_HOME`: Hermes' home (Setup passes it to the Hermes installer as its home).
 * - `BUDDYVERSE_PORT`: the local gateway port (default 9120).
 */
data class InstallPaths(val home: File, val localAppData: File, val rootOverride: File? = null, val hermesOverride: File? = null, val port: Int = DEFAULT_PORT) {
    val root = rootOverride ?: File(home, "buddyverse")
    val bin = File(root, "bin")
    val brain = File(root, "brain")
    val startScript = File(bin, "start-hermes.ps1")
    val cloudflared = File(bin, "cloudflared.exe")
    val phoneLinkFile = File(bin, "phone-link.txt")
    val setupLog = File(root, "setup.log")
    val versionJson = File(root, "Buddyverse-version.json")
    val apk = File(root, "Buddyverse.apk")

    val hermesHome = hermesOverride ?: File(localAppData, "hermes")
    val hermesExe = File(hermesHome, "bin\\hermes.exe")
    val hermesEnv = File(hermesHome, ".env")
    val hermesConfig = File(hermesHome, "config.yaml")
    /** Old installs only: a plaintext login copy. New installs keep just the scrypt hash in `.env`. */
    val webappLogin = File(hermesHome, "webapp-login.txt")
    val pluginDir = File(hermesHome, "plugins\\buddyverse")
    val webAppIndex = File(pluginDir, "dashboard\\index.html")

    /** This install's local gateway address. */
    val local: String get() = "http://127.0.0.1:$port"

    /** A PC that builds the app itself (its source sits in the root): it publishes its own APK, no release download. */
    val buildsApp: Boolean get() = File(root, "app\\androidApp").isDirectory

    /** Env for every Hermes / start-script child: this install's home and port, nothing inherited from an agent. */
    fun childEnv(env: MutableMap<String, String>) {
        env.keys.removeAll { it.startsWith("HERMES_") }
        env["HERMES_HOME"] = hermesHome.path
        env["BUDDYVERSE_HERMES_HOME"] = hermesHome.path
        env["BUDDYVERSE_PORT"] = port.toString()
    }

    companion object {
        const val DEFAULT_PORT = 9120

        fun of(home: String, localAppData: String?, env: Map<String, String> = emptyMap()): InstallPaths {
            fun e(k: String) = env[k]?.trim()?.takeIf { it.isNotEmpty() }
            return InstallPaths(
                File(home), File(localAppData?.takeIf { it.isNotBlank() } ?: File(home, "AppData\\Local").path),
                e("BUDDYVERSE_HOME")?.let(::File), e("BUDDYVERSE_HERMES_HOME")?.let(::File),
                e("BUDDYVERSE_PORT")?.toIntOrNull()?.takeIf { it in 1024..65535 } ?: DEFAULT_PORT,
            )
        }

        val current: InstallPaths by lazy { of(System.getProperty("user.home"), System.getenv("LOCALAPPDATA"), System.getenv()) }
    }
}

/**
 * The public release feed a friend's PC app pulls the phone APK from (GitHub Release asset URLs). Pinned to the beta
 * tag: GitHub's "latest" URLs skip pre-releases. Switch back to `releases/latest/download/` for the first stable release.
 * The phone itself still reads the APK only from its own PC, through its own gateway ([AppUpdate]).
 */
object ReleaseFeed {
    const val REPO = "blueApple12/buddyverse"
    const val TAG = "v0.1.0-beta"
    const val BASE = "https://github.com/$REPO/releases/download/$TAG/"
    const val APK = "Buddyverse.apk"
    const val VERSION_JSON = "Buddyverse-version.json"
    const val SETUP = "Buddyverse-Setup.exe"
    fun url(asset: String) = BASE + asset

    /** Download when the feed's release is valid and newer than what this PC already holds (null/garbage local = none). */
    fun shouldDownload(remote: AppRelease?, localJson: String?): Boolean {
        remote ?: return false
        val local = localJson?.let { AppUpdate.parse(it) } ?: return true
        return remote.versionCode > local.versionCode
    }
}

/** The Webapp's username/password login (Hermes' bundled basic dashboard auth), kept in `<hermes>\.env`. */
object WebappLogin {
    const val USER_KEY = "HERMES_DASHBOARD_BASIC_AUTH_USERNAME"
    const val PASS_KEY = "HERMES_DASHBOARD_BASIC_AUTH_PASSWORD"
    const val SECRET_KEY = "HERMES_DASHBOARD_BASIC_AUTH_SECRET"

    /** True when `.env` text already sets [key] to something non-empty (a comment or `KEY=` doesn't count). */
    fun hasKey(env: String, key: String): Boolean = env.lineSequence().any { l ->
        val t = l.trim().removePrefix("export ").trim()
        !t.startsWith("#") && t.substringBefore('=').trim() == key && t.substringAfter('=', "").trim().trim('"', '\'').isNotEmpty()
    }

    /** `.env` text with the three login lines appended (keeps everything that was there). */
    fun withLogin(env: String, user: String, password: String, secret: String): String {
        val sep = if (env.isEmpty() || env.endsWith("\n")) "" else "\n"
        return env + sep + "$USER_KEY=$user\n$PASS_KEY=$password\n$SECRET_KEY=$secret\n"
    }

    /** `webapp-login.txt`, the format the PC app's local sign-in reads. */
    fun loginFile(user: String, password: String) = "username: $user\npassword: $password\n"

    /** A random token from [alphabet] (URL/env safe), using a cryptographic RNG. */
    fun randomToken(length: Int, rng: java.security.SecureRandom = java.security.SecureRandom()): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        return buildString { repeat(length) { append(alphabet[rng.nextInt(alphabet.length)]) } }
    }
}
