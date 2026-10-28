package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by More ▸ Skills (ui/more/SkillsPage.kt, ui/more/skills). Same pattern as [Strings]: English base, Hebrew overrides.
 * Shared words (Back, Close, Cancel, Edit, Delete…) stay in [Strings].
 */
open class SkillsPageStrings {
    open val installed = "Installed"
    open val hub = "Browse"
    open val upkeep = "Upkeep"
    open val searchSkills = "Search skills"
    open val searchHub = "Search the hub"
    open val newSkill = "New skill"
    open val noSkills = "No skills yet"
    open val noMatches = "Nothing matches"
    open val other = "Other"
    open val yours = "yours"
    open val builtIn = "built in"
    open val fromHub = "from hub"
    open val external = "external"
    open val used = { n: Long -> if (n == 1L) "used once" else "used $n times" }
    open val active = "On"
    open val view = "View"
    open val turnOn = "Turn on"
    open val turnOff = "Turn off"
    open val install = "Install"
    open val installing = "Installing…"
    open val uninstall = "Uninstall"
    open val installedName = { n: String -> "$n installed" }
    open val removedName = { n: String -> "$n removed" }
    open val deletedName = { n: String -> "$n deleted" }
    open val confirmDelete = "It moves to the archive and stops loading."
    open val confirmUninstall = "It's removed from this bot."
    open val pinned = "It's pinned. Unpin it first."
    open val updateAll = "Update all"
    open val updating = "Updating…"
    open val updated = "Skills updated"
    open val official = "Official"
    open val results = "Results"
    open val allSources = "Everywhere"
    open val trusted = "trusted"
    open val community = "community"
    open val index = "Index"
    open val link = "Link"
    open val scanning = "Checking safety…"
    open val scanAllow = "Looks safe"
    open val scanAsk = "Check before installing"
    open val scanBlock = "Blocked by the safety check"
    open val files = { n: Int -> "$n files" }
    open val name = "name (a-z, 0-9, -)"
    open val description = "What it's for, in one line"
    open val category = "Category (optional)"
    open val instructions = "Instructions for the bot"
    open val saving = "Saving…"
    open val saved = "Saved. New chats use it."
    open val created = { n: String -> "$n created" }
    open val curator = "Auto tidy"
    open val curatorOn = "on"
    open val curatorPaused = "paused"
    open val curatorOff = "Turned off in settings"
    open val every = { s: String -> "Runs every $s" }
    open val lastRun = { s: String -> "Last run $s" }
    open val neverRan = "Hasn't run yet"
    open val staleAfter = { d: Long -> "Unused $d days → marked stale" }
    open val archiveAfter = { d: Long -> "Stale $d days → archived" }
    open val idleFirst = { h: Long -> "Waits for ${h}h without activity" }
    open val tidyNow = "Tidy now"
    open val tidying = "Tidying…"
    open val tidied = "Skills tidied"
    open val confirmTidy = "It can archive and rewrite skills."
    open val hours = { n: Long -> "${n}h" }
    open val stillRunning = "Still running on the PC"
    open val allBots = "Shared by all bots"
    open val notOnBots = { names: String -> "Didn't apply to $names" }

    /** Hub source ids → plain labels (never a vendor name). */
    fun source(id: String): String = when (id) {
        "official" -> official
        "hermes-index" -> index
        "url" -> link
        "github" -> "GitHub"
        "clawhub" -> "ClawHub"
        "lobehub" -> "LobeHub"
        else -> id.removePrefix("hermes-").replace("-sh", ".sh")
    }
}

private object HebrewSkillsPageStrings : SkillsPageStrings() {
    override val installed = "מותקנות"
    override val hub = "עיון"
    override val upkeep = "תחזוקה"
    override val searchSkills = "חיפוש מיומנויות"
    override val searchHub = "חיפוש במאגר"
    override val newSkill = "מיומנות חדשה"
    override val noSkills = "עוד אין מיומנויות"
    override val noMatches = "אין התאמות"
    override val other = "אחר"
    override val yours = "שלך"
    override val builtIn = "מובנית"
    override val fromHub = "מהמאגר"
    override val external = "חיצונית"
    override val used = { n: Long -> if (n == 1L) "שומשה פעם אחת" else "שומשה $n פעמים" }
    override val active = "פעילה"
    override val view = "הצגה"
    override val turnOn = "הפעלה"
    override val turnOff = "כיבוי"
    override val install = "התקנה"
    override val installing = "מתקין…"
    override val uninstall = "הסרה"
    override val installedName = { n: String -> "$n הותקנה" }
    override val removedName = { n: String -> "$n הוסרה" }
    override val deletedName = { n: String -> "$n נמחקה" }
    override val confirmDelete = "היא תעבור לארכיון ולא תיטען יותר."
    override val confirmUninstall = "היא תוסר מהבוט הזה."
    override val pinned = "היא נעוצה. צריך לבטל את הנעיצה קודם."
    override val updateAll = "עדכון הכול"
    override val updating = "מעדכן…"
    override val updated = "המיומנויות עודכנו"
    override val official = "רשמיות"
    override val results = "תוצאות"
    override val allSources = "בכל מקום"
    override val trusted = "מהימנה"
    override val community = "קהילה"
    override val index = "אינדקס"
    override val link = "קישור"
    override val scanning = "בודק בטיחות…"
    override val scanAllow = "נראית בטוחה"
    override val scanAsk = "כדאי לבדוק לפני התקנה"
    override val scanBlock = "נחסמה בבדיקת הבטיחות"
    override val files = { n: Int -> "$n קבצים" }
    override val name = "שם (a-z, 0-9, -)"
    override val description = "למה היא משמשת, בשורה אחת"
    override val category = "קטגוריה (לא חובה)"
    override val instructions = "הוראות לבוט"
    override val saving = "שומר…"
    override val saved = "נשמר. צ'אטים חדשים ישתמשו בזה."
    override val created = { n: String -> "$n נוצרה" }
    override val curator = "סידור אוטומטי"
    override val curatorOn = "פועל"
    override val curatorPaused = "מושהה"
    override val curatorOff = "כבוי בהגדרות"
    override val every = { s: String -> "רץ כל $s" }
    override val lastRun = { s: String -> "ריצה אחרונה $s" }
    override val neverRan = "עוד לא רץ"
    override val staleAfter = { d: Long -> "$d ימים בלי שימוש ← מסומנת כישנה" }
    override val archiveAfter = { d: Long -> "ישנה $d ימים ← עוברת לארכיון" }
    override val idleFirst = { h: Long -> "מחכה $h שעות בלי פעילות" }
    override val tidyNow = "סידור עכשיו"
    override val tidying = "מסדר…"
    override val tidied = "המיומנויות סודרו"
    override val confirmTidy = "זה יכול להעביר לארכיון ולשכתב מיומנויות."
    override val hours = { n: Long -> "$n שעות" }
    override val stillRunning = "עדיין רץ במחשב"
    override val allBots = "משותף לכל הבוטים"
    override val notOnBots = { names: String -> "לא הוחל על $names" }
}

private val EnglishSkillsPageStrings = SkillsPageStrings()

/** `S.skillsPage.x` in a composable. */
val S.skillsPage: SkillsPageStrings @Composable get() = if (current.rtl) HebrewSkillsPageStrings else EnglishSkillsPageStrings
