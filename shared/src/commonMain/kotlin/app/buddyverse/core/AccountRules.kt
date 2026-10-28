package app.buddyverse.core

/**
 * The app login a new user picks in first-run setup (the PC's Webapp login: phone and web sign in with it).
 * The username lands unquoted in `<hermes>\.env`, so it stays to a safe set; the password is stored there only as a
 * scrypt hash. It can't hold a line break or start/end with a space (a password file is read by its first line).
 */
object AccountRules {
    const val MIN_PASSWORD = 8
    const val MAX_USER = 64
    const val MAX_PASSWORD = 256
    private val USER = Regex("^[A-Za-z0-9._@-]+$")

    enum class Problem { UserEmpty, UserChars, UserLong, PassShort, PassLong, PassChars, Mismatch }

    fun checkUser(user: String): Problem? = when {
        user.isEmpty() -> Problem.UserEmpty
        user.length > MAX_USER -> Problem.UserLong
        !USER.matches(user) -> Problem.UserChars
        else -> null
    }

    fun checkPassword(password: String): Problem? = when {
        password.length < MIN_PASSWORD -> Problem.PassShort
        password.length > MAX_PASSWORD -> Problem.PassLong
        password.trim() != password || password.any { it.isISOControl() } -> Problem.PassChars
        else -> null
    }

    /** The first problem with a new login, or null when it can be saved. [confirm] null = no confirm field (headless). */
    fun check(user: String, password: String, confirm: String?): Problem? =
        checkUser(user) ?: checkPassword(password) ?: if (confirm != null && confirm != password) Problem.Mismatch else null
}
