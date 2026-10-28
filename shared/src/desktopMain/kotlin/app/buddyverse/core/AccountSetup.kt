package app.buddyverse.core

/**
 * First-run login on disk, the same mechanism Hermes' basic dashboard auth reads (`plugins/dashboard_auth/basic`
 * `_settings`: env wins over `config.yaml dashboard.basic_auth`): `<hermes>\.env` gets the username, a signing secret and
 * only the scrypt hash of the password ([DashboardPassword]). No plaintext copy is written anywhere.
 * Passwords are never logged.
 */
object AccountSetup {
    /** The username of a login Hermes already has (`.env` first, then `config.yaml` `dashboard.basic_auth.username`), or null. */
    fun existingUser(env: String, configYaml: String?): String? =
        DashboardPassword.envValue(env, WebappLogin.USER_KEY) ?: configYaml?.let(::configUser)

    private fun configUser(yaml: String): String? {
        var inDash = false
        var inBasic = false
        var basicIndent = -1
        for (raw in yaml.lines()) {
            if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
            val indent = raw.length - raw.trimStart().length
            val t = raw.trim()
            if (indent == 0) { inDash = t.startsWith("dashboard:"); inBasic = false; continue }
            if (!inDash) continue
            if (t.startsWith("basic_auth:")) { inBasic = true; basicIndent = indent; continue }
            if (inBasic && indent <= basicIndent) inBasic = false
            if (inBasic && t.startsWith("username:")) return t.substringAfter(':').trim().trim('"', '\'').takeIf { it.isNotEmpty() }
        }
        return null
    }

    /**
     * True when [password] is the existing login's: checked against the `.env` hash or plaintext; true when neither is in
     * `.env` (a config.yaml-only login can't be checked here: the local sign-in will say if it's wrong).
     */
    fun matchesExisting(env: String, password: String): Boolean {
        DashboardPassword.envValue(env, DashboardPassword.HASH_KEY)?.let { return DashboardPassword.verify(password, it) }
        DashboardPassword.envValue(env, WebappLogin.PASS_KEY)?.let { return it == password }
        return true
    }

    /** `.env` text with a new login: username + secret appended, the password as its hash only (every old password line goes). */
    fun envWithNewLogin(env: String, user: String, passwordHash: String, secret: String): String {
        val nl = if ("\r\n" in env) "\r\n" else "\n"
        val sep = if (env.isEmpty() || env.endsWith("\n")) "" else nl
        return DashboardPassword.envWithHash(env + sep + "${WebappLogin.USER_KEY}=$user$nl${WebappLogin.SECRET_KEY}=$secret$nl", passwordHash)
    }
}
