package app.buddyverse.core

/**
 * Changing the Webapp (Hermes dashboard basic-auth) password. The gateway hashes its password once, when it starts
 * (`plugins/dashboard_auth/basic` `register` → `_settings`), so a change is a local `.env` write plus a gateway
 * restart: only the PC app can do it, and it sets [onPc]. On the phone [onPc] stays null and Settings says so.
 */
object PasswordChange {
    sealed interface Result {
        data object Done : Result
        data object WrongCurrent : Result
        /** Nothing changed (or it was put back): [why] is a short reason. */
        data class Failed(val why: String) : Result
    }

    /** Verify [current], write [new], restart the gateway, sign back in. Set by the PC app. */
    var onPc: (suspend (current: String, new: String) -> Result)? = null

    const val MIN_LENGTH = 10

    /** 0 = too short … 4 = strong: length and the mix of letters, digits and symbols. */
    fun strength(pw: String): Int {
        if (pw.length < MIN_LENGTH) return 0
        val kinds = listOf(pw.any { it.isLowerCase() }, pw.any { it.isUpperCase() }, pw.any { it.isDigit() }, pw.any { !it.isLetterOrDigit() }).count { it }
        return (1 + (if (pw.length >= 14) 1 else 0) + (if (pw.length >= 18) 1 else 0) + (if (kinds >= 3) 1 else 0)).coerceAtMost(4)
    }

    /** Characters easy to misread when typing it on the phone (O/0, l/1/I, |). */
    fun lookAlikes(pw: String): String = pw.filter { it in "O0oIl1|" }.toSet().joinToString(" ")

    enum class Problem { TooShort, Spaces, Mismatch, SameAsOld }

    /** Why the form can't be sent yet, or null. */
    fun problem(current: String, new: String, repeat: String): Problem? = when {
        new.length < MIN_LENGTH -> Problem.TooShort
        new != new.trim() -> Problem.Spaces
        new != repeat -> Problem.Mismatch
        new == current -> Problem.SameAsOld
        else -> null
    }
}
