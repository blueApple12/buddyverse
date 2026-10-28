package app.buddyverse.core

/**
 * `Buddyverse.exe --setup ...` (headless first-run setup, desktopApp Setup.kt). Flags win over env vars.
 *   --setup                      run setup with no window, then exit 0 (all done) / 1 (a step failed)
 *   --username <u>               env BUDDYVERSE_USERNAME
 *   --password-file <path|->     env BUDDYVERSE_PASSWORD_FILE; `-` = read stdin. First line only.
 *                                env BUDDYVERSE_PASSWORD (the password itself, for CI secrets) when no file is given
 *   --skip-phone                 env BUDDYVERSE_SKIP_PHONE=1: no cloudflared, no PC-side start, no phone link
 */
data class SetupArgs(
    val username: String?,
    val passwordFile: String?,
    val passwordEnv: String?,
    val skipPhone: Boolean,
) {
    companion object {
        const val FLAG = "--setup"
        const val ENV_USER = "BUDDYVERSE_USERNAME"
        const val ENV_PASSWORD_FILE = "BUDDYVERSE_PASSWORD_FILE"
        const val ENV_PASSWORD = "BUDDYVERSE_PASSWORD"
        const val ENV_SKIP_PHONE = "BUDDYVERSE_SKIP_PHONE"

        fun wanted(args: Array<String>): Boolean = FLAG in args

        /** The parsed flags, or the error text for an unknown flag / a flag missing its value. */
        fun parse(args: List<String>, env: Map<String, String>): Result<SetupArgs> {
            var user: String? = null
            var file: String? = null
            var skip = false
            var i = 0
            while (i < args.size) {
                val a = args[i]
                fun value(): String = args.getOrNull(i + 1)?.takeIf { !it.startsWith("--") }
                    ?: throw IllegalArgumentException("$a needs a value")
                try {
                    when {
                        a == FLAG -> Unit
                        a == "--skip-phone" -> skip = true
                        a == "--username" -> { user = value(); i++ }
                        a == "--password-file" -> { file = value(); i++ }
                        a.startsWith("--username=") -> user = a.substringAfter('=')
                        a.startsWith("--password-file=") -> file = a.substringAfter('=')
                        a == "--password" || a.startsWith("--password=") ->
                            return Result.failure(IllegalArgumentException("--password is not accepted (it would show in the process list); use --password-file or $ENV_PASSWORD"))
                        else -> return Result.failure(IllegalArgumentException("unknown option $a"))
                    }
                } catch (e: IllegalArgumentException) { return Result.failure(e) }
                i++
            }
            fun e(k: String) = env[k]?.takeIf { it.isNotBlank() }
            return Result.success(SetupArgs(
                username = user ?: e(ENV_USER),
                passwordFile = file ?: e(ENV_PASSWORD_FILE),
                passwordEnv = env[ENV_PASSWORD]?.takeIf { it.isNotEmpty() },
                skipPhone = skip || e(ENV_SKIP_PHONE).let { it == "1" || it.equals("true", ignoreCase = true) },
            ))
        }

        /** The first line of a password file (a BOM and the line break dropped). */
        fun firstLine(text: String): String = text.removePrefix("\uFEFF").lineSequence().firstOrNull().orEmpty().removeSuffix("\r")
    }
}
