package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by More ▸ Tools (ui/more/ToolsPage.kt, ui/more/tools). Same pattern as [Strings]: English base, Hebrew overrides.
 * Shared words (Save, Search, Tools, Try again…) stay in [Strings].
 */
open class ToolsPageStrings {
    open val refresh = "Refresh"
    open val general = "General"
    open val toolsOn = { on: Int, all: Int -> "$on of $all on" }
    open val toolsCount = { n: Int -> if (n == 1) "1 tool" else "$n tools" }
    open val usedTimes = { n: Long -> "used $n×" }
    open val needsSetup = "Needs setup"
    open val needsKey = "Needs a key"
    open val needsSignIn = "Needs sign-in on the PC"
    open val unavailable = "Unavailable"
    open val provider = "Provider"
    open val readPages = "Read pages"
    open val model = "Model"
    open val runsOn = "Where commands run"
    open val useThis = "Use this"
    open val keySaved = "•••••••• saved"
    open val getKey = "Get a key"
    open val setUp = "Set up on PC"
    open val settingUp = "Setting up on your PC…"
    open val setupDone = "Finished"
    open val setupFailed = "Setup failed"
    open val ready = "Ready"
    open val notReady = "Not ready"
    open val grant = "Grant access"
    /** A translated toolset name; null = the engine's own label. */
    open fun toolsetName(name: String): String? = null
}

private object HebrewToolsPageStrings : ToolsPageStrings() {
    override val refresh = "רענון"
    override val general = "כללי"
    override val toolsOn = { on: Int, all: Int -> "$on מתוך $all פעילים" }
    override val toolsCount = { n: Int -> if (n == 1) "כלי אחד" else "$n כלים" }
    override val usedTimes = { n: Long -> "$n שימושים" }
    override val needsSetup = "צריך התקנה"
    override val needsKey = "צריך מפתח"
    override val needsSignIn = "צריך התחברות במחשב"
    override val unavailable = "לא זמין"
    override val provider = "ספק"
    override val readPages = "קריאת דפים"
    override val model = "מודל"
    override val runsOn = "איפה פקודות רצות"
    override val useThis = "להשתמש בזה"
    override val keySaved = "•••••••• נשמר"
    override val getKey = "השגת מפתח"
    override val setUp = "התקנה במחשב"
    override val settingUp = "מתקין במחשב שלך…"
    override val setupDone = "הסתיים"
    override val setupFailed = "ההתקנה נכשלה"
    override val ready = "מוכן"
    override val notReady = "לא מוכן"
    override val grant = "מתן הרשאה"

    private val names = mapOf(
        "web" to "חיפוש ברשת", "browser" to "דפדפן", "terminal" to "טרמינל ותהליכים", "file" to "קבצים",
        "code_execution" to "הרצת קוד", "vision" to "ראייה וניתוח תמונות", "video" to "ניתוח וידאו", "image_gen" to "יצירת תמונות",
        "video_gen" to "יצירת וידאו", "x_search" to "חיפוש ב־X", "tts" to "הקראה", "stt" to "תמלול דיבור", "skills" to "מיומנויות",
        "todo" to "תכנון משימות", "kanban" to "קנבן", "memory" to "זיכרון", "context_engine" to "מנוע הקשר",
        "session_search" to "חיפוש בצ'אטים", "connections" to "חיבורים", "clarify" to "שאלות הבהרה", "delegation" to "האצלת משימות",
        "cronjob" to "תזמונים", "discord" to "דיסקורד", "discord_admin" to "ניהול שרת דיסקורד", "computer_use" to "שליטה במחשב",
        "a2a" to "סוכן לסוכן (A2A)",
    )

    override fun toolsetName(name: String): String? = names[name]
}

private val EnglishToolsPageStrings = ToolsPageStrings()

/** `S.toolsPage.x` in a composable. */
val S.toolsPage: ToolsPageStrings @Composable get() = if (current.rtl) HebrewToolsPageStrings else EnglishToolsPageStrings
