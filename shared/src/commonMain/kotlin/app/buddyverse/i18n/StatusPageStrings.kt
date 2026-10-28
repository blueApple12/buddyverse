package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by More ▸ PC status (ui/more/StatusPage.kt, ui/more/status). Same pattern as [Strings]: English base,
 * Hebrew overrides. Shared words (Back, Close, Cancel, Copy, Try again…) stay in [Strings].
 */
open class StatusPageStrings {
    open val pcName = "PC"
    open val refresh = "Refresh"
    // hero
    open val engineRunning = "Engine running"
    open val engineBusy = "Engine at work"
    open val engineStopped = "Engine stopped"
    open val engineStarting = "Engine starting"
    open val engineDraining = "Engine finishing up"
    open val engineTrouble = "Engine needs a look"
    open val engineFailed = "Engine stopped with an error"
    open val unreachable = "Can't reach your PC"
    open val upFor = { d: String -> "up $d" }
    open val version = { v: String -> "version $v" }
    open val working = { n: Int -> if (n == 1) "1 bot working" else "$n bots working" }
    open val liveChats = { n: Int -> if (n == 1) "1 live chat" else "$n live chats" }
    open val start = "Start engine"
    open val restart = "Restart engine"
    // restart confirm
    open val restartTitle = "Restart the engine?"
    open val restartAll = "Every bot restarts with it."
    open val restartShared = { bots: String -> "These bots restart with it: $bots" }
    open val restartBusy = { n: Int -> if (n == 1) "1 bot is mid-turn and gets cut off." else "$n bots are mid-turn and get cut off." }
    open val restartNow = "Restart"
    open val restarting = "Restarting…"
    open val starting = "Starting…"
    open val restarted = "Engine is back"
    open val started = "Engine started"
    // warnings
    open val attention = "Needs a look"
    open val uncleanBoot = "The engine didn't shut down cleanly last time"
    open val oomBoot = "The engine ran out of memory last time"
    open val heartbeatStale = { s: String -> "No heartbeat for $s" }
    open val storageCorrupt = "The chat database is damaged"
    open val exitReason = { r: String -> "Last stop: $r" }
    open val memoryPressure = "The PC is low on memory"
    open val diskPressure = "The PC is low on disk space"
    open val configBehind = "The settings file is from an older version"
    open val migrate = "Update settings file"
    open val migrateTitle = "Update the settings file?"
    open val migrateBody = "It adds the new settings and keeps yours. A backup is kept."
    open val migrated = "Settings file updated"
    // parts
    open val parts = "Parts"
    open val part = { k: String ->
        when (k) {
            "gateway" -> "Engine"
            "dashboard" -> "Web server"
            "storage" -> "Chat database"
            "platforms" -> "Messaging"
            "cron" -> "Routines"
            "memory" -> "Memory"
            else -> k.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }
    open val ok = "OK"
    open val degraded = "trouble"
    open val connectedOf = { c: Int, n: Int -> "$c of $n connected" }
    open val recentErrors = { n: Int -> if (n == 1) "1 recent error" else "$n recent errors" }
    open val platforms = "Messaging apps"
    open val platformState = { s: String ->
        when (s) {
            "connected", "running", "ok" -> "connected"
            "connecting", "starting" -> "connecting…"
            "disconnected" -> "disconnected"
            "error", "failed", "fatal" -> "error"
            "stopped" -> "stopped"
            else -> s
        }
    }
    // machine
    open val machine = "This PC"
    open val cpu = "Processor"
    open val ram = "Memory"
    open val disk = "Disk"
    open val engineRam = "Engine memory"
    open val pcUptime = "PC on for"
    open val cores = { n: Int -> "$n cores" }
    open val usedOf = { used: String, total: String -> "$used of $total" }
    // updates
    open val updates = "Updates"
    open val upToDate = "Up to date"
    open val behind = { n: Int -> if (n == 1) "1 update ready" else "$n updates ready" }
    open val updateReady = "An update is ready"
    open val updateUnknown = "Couldn't tell if there's an update"
    open val notChecked = "Not checked yet"
    open val checkNow = "Check now"
    open val checking = "Checking…"
    open val updateNow = "Update now"
    open val updateTitle = "Update the engine?"
    open val updateBody = "The engine restarts at the end; live chats on every bot stop."
    open val updating = "Updating…"
    open val updated = "Update finished"
    open val updateFailed = "The update didn't finish"
    open val updateRefused = "This PC updates another way"
    open val copyCommand = "Copy update command"
    open val commandCopied = "Command copied"
    open val lastUpdate = "Last update"
    open val outcome = { o: String ->
        when (o) {
            "success", "ok" -> "done"
            "partial" -> "mostly done"
            "failed" -> "failed"
            "refused" -> "refused"
            "running" -> "running…"
            else -> o
        }
    }
    open val stillToDo = "Still to do"
    open val youNeedTo = "You need to"
    open val changes = "What's new"
    // checks
    open val checks = "Checks"
    open val doctor = "Health check"
    open val audit = "Security check"
    open val run = "Run"
    open val runAgain = "Run again"
    open val runningNow = "Running…"
    open val finished = "Finished"
    open val failedCode = { c: Int -> "Stopped with code $c" }
    open val noOutput = "Nothing to show yet"
    // logs
    open val logs = "Logs"
    open val logFile = { f: String ->
        when (f) {
            "agent" -> "Bots"
            "errors" -> "Errors"
            "gateway" -> "Engine"
            "mcp" -> "MCP"
            else -> f
        }
    }
    open val levelAll = "All"
    open val levelInfo = "Info"
    open val levelWarning = "Warnings"
    open val levelError = "Errors"
    open val searchLogs = "Search the log"
    open val follow = "Follow"
    open val noLines = "Nothing in this log"
    open val copyAll = "Copy all"
    open val copied = "Copied"
}

private object HebrewStatusPageStrings : StatusPageStrings() {
    override val pcName = "המחשב"
    override val refresh = "רענון"
    override val engineRunning = "המנוע פועל"
    override val engineBusy = "המנוע עובד"
    override val engineStopped = "המנוע כבוי"
    override val engineStarting = "המנוע מתניע"
    override val engineDraining = "המנוע מסיים"
    override val engineTrouble = "המנוע צריך בדיקה"
    override val engineFailed = "המנוע נעצר עם שגיאה"
    override val unreachable = "אין חיבור למחשב"
    override val upFor = { d: String -> "פועל $d" }
    override val version = { v: String -> "גרסה $v" }
    override val working = { n: Int -> if (n == 1) "בוט אחד עובד" else "$n בוטים עובדים" }
    override val liveChats = { n: Int -> if (n == 1) "צ'אט פעיל אחד" else "$n צ'אטים פעילים" }
    override val start = "הפעלת המנוע"
    override val restart = "הפעלה מחדש"
    override val restartTitle = "להפעיל את המנוע מחדש?"
    override val restartAll = "כל הבוטים יופעלו מחדש איתו."
    override val restartShared = { bots: String -> "הבוטים האלה יופעלו מחדש איתו: $bots" }
    override val restartBusy = { n: Int -> if (n == 1) "בוט אחד באמצע עבודה וייקטע." else "$n בוטים באמצע עבודה וייקטעו." }
    override val restartNow = "הפעלה מחדש"
    override val restarting = "מופעל מחדש…"
    override val starting = "מתניע…"
    override val restarted = "המנוע חזר"
    override val started = "המנוע פועל"
    override val attention = "צריך בדיקה"
    override val uncleanBoot = "המנוע לא נסגר כראוי בפעם הקודמת"
    override val oomBoot = "למנוע נגמר הזיכרון בפעם הקודמת"
    override val heartbeatStale = { s: String -> "אין סימן חיים כבר $s" }
    override val storageCorrupt = "מסד הצ'אטים פגום"
    override val exitReason = { r: String -> "עצירה אחרונה: $r" }
    override val memoryPressure = "למחשב חסר זיכרון"
    override val diskPressure = "למחשב חסר מקום בדיסק"
    override val configBehind = "קובץ ההגדרות מגרסה ישנה"
    override val migrate = "עדכון קובץ ההגדרות"
    override val migrateTitle = "לעדכן את קובץ ההגדרות?"
    override val migrateBody = "ההגדרות החדשות נוספות ושלך נשמרות. נשמר גיבוי."
    override val migrated = "קובץ ההגדרות עודכן"
    override val parts = "חלקים"
    override val part = { k: String ->
        when (k) {
            "gateway" -> "מנוע"
            "dashboard" -> "שרת הרשת"
            "storage" -> "מסד הצ'אטים"
            "platforms" -> "הודעות"
            "cron" -> "שגרות"
            "memory" -> "זיכרון"
            else -> k.replace('_', ' ')
        }
    }
    override val ok = "תקין"
    override val degraded = "תקלה"
    override val connectedOf = { c: Int, n: Int -> "$c מתוך $n מחוברים" }
    override val recentErrors = { n: Int -> if (n == 1) "שגיאה אחת לאחרונה" else "$n שגיאות לאחרונה" }
    override val platforms = "אפליקציות הודעות"
    override val platformState = { s: String ->
        when (s) {
            "connected", "running", "ok" -> "מחובר"
            "connecting", "starting" -> "מתחבר…"
            "disconnected" -> "מנותק"
            "error", "failed", "fatal" -> "שגיאה"
            "stopped" -> "כבוי"
            else -> s
        }
    }
    override val machine = "המחשב הזה"
    override val cpu = "מעבד"
    override val ram = "זיכרון"
    override val disk = "דיסק"
    override val engineRam = "זיכרון המנוע"
    override val pcUptime = "המחשב דלוק"
    override val cores = { n: Int -> "$n ליבות" }
    override val usedOf = { used: String, total: String -> "$used מתוך $total" }
    override val updates = "עדכונים"
    override val upToDate = "מעודכן"
    override val behind = { n: Int -> if (n == 1) "עדכון אחד מוכן" else "$n עדכונים מוכנים" }
    override val updateReady = "יש עדכון מוכן"
    override val updateUnknown = "לא ברור אם יש עדכון"
    override val notChecked = "עוד לא נבדק"
    override val checkNow = "בדיקה עכשיו"
    override val checking = "בודק…"
    override val updateNow = "עדכון עכשיו"
    override val updateTitle = "לעדכן את המנוע?"
    override val updateBody = "בסוף המנוע מופעל מחדש; צ'אטים פעילים בכל הבוטים ייעצרו."
    override val updating = "מעדכן…"
    override val updated = "העדכון הסתיים"
    override val updateFailed = "העדכון לא הסתיים"
    override val updateRefused = "המחשב הזה מתעדכן בדרך אחרת"
    override val copyCommand = "העתקת פקודת העדכון"
    override val commandCopied = "הפקודה הועתקה"
    override val lastUpdate = "עדכון אחרון"
    override val outcome = { o: String ->
        when (o) {
            "success", "ok" -> "הסתיים"
            "partial" -> "הסתיים ברובו"
            "failed" -> "נכשל"
            "refused" -> "נדחה"
            "running" -> "רץ…"
            else -> o
        }
    }
    override val stillToDo = "נשאר לעשות"
    override val youNeedTo = "צריך שתעשו"
    override val changes = "מה חדש"
    override val checks = "בדיקות"
    override val doctor = "בדיקת תקינות"
    override val audit = "בדיקת אבטחה"
    override val run = "הרצה"
    override val runAgain = "הרצה שוב"
    override val runningNow = "רץ…"
    override val finished = "הסתיים"
    override val failedCode = { c: Int -> "נעצר עם קוד $c" }
    override val noOutput = "עוד אין מה להראות"
    override val logs = "יומנים"
    override val logFile = { f: String ->
        when (f) {
            "agent" -> "בוטים"
            "errors" -> "שגיאות"
            "gateway" -> "מנוע"
            "mcp" -> "MCP"
            else -> f
        }
    }
    override val levelAll = "הכול"
    override val levelInfo = "מידע"
    override val levelWarning = "אזהרות"
    override val levelError = "שגיאות"
    override val searchLogs = "חיפוש ביומן"
    override val follow = "מעקב"
    override val noLines = "היומן הזה ריק"
    override val copyAll = "העתקת הכול"
    override val copied = "הועתק"
}

private val EnglishStatusPageStrings = StatusPageStrings()

/** `S.statusPage.x` in a composable. */
val S.statusPage: StatusPageStrings @Composable get() = if (current.rtl) HebrewStatusPageStrings else EnglishStatusPageStrings
