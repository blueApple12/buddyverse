package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by More ▸ Processes (ui/more/ProcessesPage.kt, ui/more/processes). Same pattern as [Strings]: English base,
 * Hebrew overrides. Shared words (Back, Close, Copy, Try again…) stay in [Strings].
 */
open class ProcessesPageStrings {
    open val runningHeader = "Running"
    open val finishedHeader = "Finished"
    open val nothingRunning = "Nothing running"
    open val shell = "PC shell"
    open val openShell = "Open a PC shell"
    open val stop = "Stop"
    open val reallyStop = "Really stop?"
    open val stopping = "Stopping…"
    open val stopped = { cmd: String -> "Stopped $cmd" }
    open val alreadyDone = "It had already finished"
    open val gone = "It's not there anymore"
    open val stopAll = { n: Int -> "Stop all $n in this chat" }
    open val stoppedCount = { n: Int -> if (n == 1) "Stopped 1 process" else "Stopped $n processes" }
    open val openChat = "Open chat"
    open val up = { d: String -> "up $d" }
    open val pid = { p: Long -> "pid $p" }
    open val done = "done"
    open val failedExit = { code: Long -> "failed · exit $code" }
    open val ended = "ended"
    open val tellsWhenDone = "Tells the bot when done"
    open val watchHit = "Spotted what it waits for"
    open val noOutput = "No output yet"
    open val copied = "Copied"
    open val copyOutput = "Copy output"
    open val typeCommand = "Type a command"
    open val run = "Run"
    open val ctrlC = "Ctrl+C"
    open val tab = "Tab"
    open val up1 = "Previous"
    open val down1 = "Next"
    open val esc = "Esc"
    open val opening = "Opening…"
    open val shellExited = "The shell ended"
    open val shellBusy = "Too many shells are open on the PC"
    open val shellDenied = "The PC doesn't allow a shell from here"
    open val shellTakenOver = "This shell opened somewhere else"
    open val shellFailed = "The shell couldn't start"
    open val openAgain = "Open again"
}

private object HebrewProcessesPageStrings : ProcessesPageStrings() {
    override val runningHeader = "רצים"
    override val finishedHeader = "הסתיימו"
    override val nothingRunning = "שום דבר לא רץ"
    override val shell = "מסוף במחשב"
    override val openShell = "פתיחת מסוף במחשב"
    override val stop = "עצירה"
    override val reallyStop = "לעצור באמת?"
    override val stopping = "עוצר…"
    override val stopped = { cmd: String -> "נעצר: $cmd" }
    override val alreadyDone = "הוא כבר הסתיים"
    override val gone = "הוא כבר לא שם"
    override val stopAll = { n: Int -> "עצירת כל ה-$n בצ'אט הזה" }
    override val stoppedCount = { n: Int -> if (n == 1) "נעצר תהליך אחד" else "נעצרו $n תהליכים" }
    override val openChat = "פתיחת הצ'אט"
    override val up = { d: String -> "רץ $d" }
    override val pid = { p: Long -> "pid $p" }
    override val done = "הסתיים"
    override val failedExit = { code: Long -> "נכשל · קוד $code" }
    override val ended = "נגמר"
    override val tellsWhenDone = "יעדכן את הבוט כשיסתיים"
    override val watchHit = "זיהה את מה שחיכה לו"
    override val noOutput = "עוד אין פלט"
    override val copied = "הועתק"
    override val copyOutput = "העתקת הפלט"
    override val typeCommand = "הקלדת פקודה"
    override val run = "הרצה"
    override val up1 = "הקודמת"
    override val down1 = "הבאה"
    override val opening = "פותח…"
    override val shellExited = "המסוף נסגר"
    override val shellBusy = "יותר מדי מסופים פתוחים במחשב"
    override val shellDenied = "המחשב לא מאפשר מסוף מכאן"
    override val shellTakenOver = "המסוף הזה נפתח במקום אחר"
    override val shellFailed = "המסוף לא הצליח להיפתח"
    override val openAgain = "לפתוח שוב"
}

private val EnglishProcessesPageStrings = ProcessesPageStrings()

/** `S.processesPage.x` in a composable. */
val S.processesPage: ProcessesPageStrings @Composable get() = if (current.rtl) HebrewProcessesPageStrings else EnglishProcessesPageStrings
