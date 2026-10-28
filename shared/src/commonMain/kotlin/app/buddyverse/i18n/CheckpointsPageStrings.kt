package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by More ▸ Checkpoints (ui/more/CheckpointsPage.kt, ui/more/checkpoints). Same pattern as [Strings]:
 * English base, Hebrew overrides. Shared words (Cancel, Undo, Retry, Loading…, Close…) stay in [Strings].
 */
open class CheckpointsPageStrings {
    // tabs and header
    open val checkpointsTab = "Checkpoints"
    open val changesTab = "Changes"
    open val pickChat = "Which chat"
    open val mainChats = "Main chats"
    open val recentChats = "Recent chats"
    open val noFolder = "No folder"
    open val noChats = "No chats yet"
    open val mainChatOf = { bot: String -> "$bot · main chat" }
    open val refresh = "Refresh"

    // checkpoints
    open val checkpointsOff = "Checkpoints are off for this bot"
    open val noCheckpoints = "No checkpoints yet. One is saved before the bot changes a file."
    open val allChanges = "Everything this chat changed"
    open val noChanges = "Nothing changed"
    open val goBack = "Go back to here"
    open val goBackTitle = "Go back to this checkpoint?"
    open val goBackBody = "Files go back to how they were then, and the chat's last turn is undone. What's there now is saved as a new checkpoint first."
    open val alsoMyEdits = "Also undo my own edits"
    open val putFileBack = "Put this file back"
    open val putFileTitle = { f: String -> "Put $f back?" }
    open val putFileBody = "Only this file changes. The chat stays as it is."
    open val putBack = "Put back"
    open val wentBack = { hash: String -> "Back to $hash" }
    open val fileBack = { f: String -> "$f is back" }
    open val keptYourEdits = { n: Int -> if (n == 1) "kept 1 of your edits" else "kept $n of your edits" }
    open val turnUndone = "last turn undone"
    open val filesTitle = "Files"
    open val sinceThen = "Changes since then"
    open val before = { what: String -> "Before: $what" }
    open val beforeGoingBack = { hash: String -> "Before going back to $hash" }
    open val autoSnapshot = "Saved automatically"
    open val linesCut = { shown: Int, all: Int -> "Showing $shown of $all lines" }
    open val busyChat = "The bot is working in this chat. Try when it's done."
    open val container = "This chat runs in a container, so its files aren't on your PC"
    open val checkpointGone = "That checkpoint is gone"
    open val chatGone = "That chat is gone"
    open val notAllowed = "Not allowed from here"

    // git
    open val notRepo = "This folder isn't a git repo"
    open val clean = "Nothing to commit"
    open val notCommitted = "Not committed"
    open val wholeBranch = "This branch"
    open val stageAll = "Stage all"
    open val unstageAll = "Unstage all"
    open val stage = "Stage"
    open val unstage = "Unstage"
    open val staged = "staged"
    open val viewChanges = "View changes"
    open val discard = "Discard changes"
    open val discardTitle = { f: String -> "Discard changes to $f?" }
    open val discardBody = "The file goes back to its last commit. A new file is deleted. This can't be undone."
    open val discarded = { f: String -> "Changes to $f discarded" }
    open val commit = "Commit"
    open val commitFiles = { n: Int -> if (n == 1) "Commit 1 file" else "Commit $n files" }
    open val commitMessage = "What changed?"
    open val writeForMe = "Write it for me"
    open val writeAgain = "Another one"
    open val pushToo = "Push too"
    open val committed = "Committed"
    open val committedPushed = "Committed and pushed"
    open val needMessage = "Write a message first"
    open val push = { n: Int -> if (n == 1) "Push 1 commit" else "Push $n commits" }
    open val pushed = "Pushed"
    open val openPr = "Open a pull request"
    open val prMade = "Pull request opened"
    open val pr = { n: Int, state: String -> "Pull request #$n · $state" }
    open val ahead = { n: Int -> "↑$n" }
    open val behind = { n: Int -> "↓$n" }
    open val detached = "detached"
    open val filesCount = { n: Int -> if (n == 1) "1 file" else "$n files" }
    open val conflicted = { n: Int -> "$n in conflict" }

    // branches and worktrees
    open val branches = "Branches"
    open val remote = "Remote"
    open val defaultTag = "default"
    open val inOtherTree = "open in another worktree"
    open val switched = { b: String -> "On $b now" }
    open val worktrees = "Worktrees"
    open val mainTree = "main"
    open val newWorktree = "New worktree"
    open val worktreeName = "Name (also the branch)"
    open val from = "From"
    open val fromHere = "Here"
    open val makeWorktree = "Create"
    open val worktreeMade = { p: String -> "Worktree ready: $p" }
    open val removeWorktree = "Remove worktree"
    open val removeWorktreeTitle = { p: String -> "Remove $p?" }
    open val removeWorktreeBody = "The folder goes away. Its branch stays."
    open val evenUnsaved = "Even with unsaved changes"
    open val remove = "Remove"
    open val removed = "Worktree removed"
}

private object HebrewCheckpointsPageStrings : CheckpointsPageStrings() {
    override val checkpointsTab = "נקודות שמירה"
    override val changesTab = "שינויים"
    override val pickChat = "איזה צ'אט"
    override val mainChats = "צ'אטים ראשיים"
    override val recentChats = "צ'אטים אחרונים"
    override val noFolder = "בלי תיקייה"
    override val noChats = "אין עדיין צ'אטים"
    override val mainChatOf = { bot: String -> "$bot · צ'אט ראשי" }
    override val refresh = "רענון"

    override val checkpointsOff = "נקודות שמירה כבויות לבוט הזה"
    override val noCheckpoints = "אין עדיין נקודות שמירה. נשמרת אחת לפני שהבוט משנה קובץ."
    override val allChanges = "כל מה שהצ'אט הזה שינה"
    override val noChanges = "שום דבר לא השתנה"
    override val goBack = "לחזור לכאן"
    override val goBackTitle = "לחזור לנקודת השמירה הזו?"
    override val goBackBody = "הקבצים חוזרים למצב שהיו בו אז, והתור האחרון בצ'אט מתבטל. המצב הנוכחי נשמר קודם כנקודת שמירה חדשה."
    override val alsoMyEdits = "לבטל גם עריכות שלי"
    override val putFileBack = "להחזיר את הקובץ הזה"
    override val putFileTitle = { f: String -> "להחזיר את $f?" }
    override val putFileBody = "רק הקובץ הזה משתנה. הצ'אט נשאר כמו שהוא."
    override val putBack = "להחזיר"
    override val wentBack = { hash: String -> "חזרנו ל־$hash" }
    override val fileBack = { f: String -> "$f חזר" }
    override val keptYourEdits = { n: Int -> if (n == 1) "עריכה אחת שלך נשמרה" else "$n עריכות שלך נשמרו" }
    override val turnUndone = "התור האחרון בוטל"
    override val filesTitle = "קבצים"
    override val sinceThen = "שינויים מאז"
    override val before = { what: String -> "לפני: $what" }
    override val beforeGoingBack = { hash: String -> "לפני החזרה ל־$hash" }
    override val autoSnapshot = "נשמר אוטומטית"
    override val linesCut = { shown: Int, all: Int -> "מוצגות $shown מתוך $all שורות" }
    override val busyChat = "הבוט עובד בצ'אט הזה. נסו כשהוא יסיים."
    override val container = "הצ'אט הזה רץ בקונטיינר, אז הקבצים שלו לא במחשב שלך"
    override val checkpointGone = "נקודת השמירה הזו כבר לא קיימת"
    override val chatGone = "הצ'אט הזה כבר לא קיים"
    override val notAllowed = "אי אפשר לעשות את זה מכאן"

    override val notRepo = "התיקייה הזו היא לא מאגר git"
    override val clean = "אין מה לשמור ב־commit"
    override val notCommitted = "לא שמור"
    override val wholeBranch = "הענף הזה"
    override val stageAll = "להכין הכול"
    override val unstageAll = "לבטל הכנה להכול"
    override val stage = "להכין"
    override val unstage = "לבטל הכנה"
    override val staged = "מוכן"
    override val viewChanges = "הצגת השינויים"
    override val discard = "ביטול השינויים"
    override val discardTitle = { f: String -> "לבטל את השינויים ב־$f?" }
    override val discardBody = "הקובץ חוזר ל־commit האחרון. קובץ חדש נמחק. אי אפשר לבטל את זה."
    override val discarded = { f: String -> "השינויים ב־$f בוטלו" }
    override val commit = "Commit"
    override val commitFiles = { n: Int -> if (n == 1) "Commit לקובץ אחד" else "Commit ל־$n קבצים" }
    override val commitMessage = "מה השתנה?"
    override val writeForMe = "לכתוב בשבילי"
    override val writeAgain = "עוד אחד"
    override val pushToo = "גם push"
    override val committed = "נשמר ב־commit"
    override val committedPushed = "נשמר ונדחף"
    override val needMessage = "צריך לכתוב הודעה קודם"
    override val push = { n: Int -> if (n == 1) "Push ל־commit אחד" else "Push ל־$n commits" }
    override val pushed = "נדחף"
    override val openPr = "לפתוח pull request"
    override val prMade = "ה־pull request נפתח"
    override val pr = { n: Int, state: String -> "Pull request #$n · $state" }
    override val detached = "מנותק"
    override val filesCount = { n: Int -> if (n == 1) "קובץ אחד" else "$n קבצים" }
    override val conflicted = { n: Int -> "$n בהתנגשות" }

    override val branches = "ענפים"
    override val remote = "מרוחקים"
    override val defaultTag = "ברירת מחדל"
    override val inOtherTree = "פתוח ב־worktree אחר"
    override val switched = { b: String -> "עכשיו על $b" }
    override val worktrees = "Worktrees"
    override val mainTree = "ראשי"
    override val newWorktree = "Worktree חדש"
    override val worktreeName = "שם (גם של הענף)"
    override val from = "מתוך"
    override val fromHere = "כאן"
    override val makeWorktree = "יצירה"
    override val worktreeMade = { p: String -> "ה־worktree מוכן: $p" }
    override val removeWorktree = "הסרת worktree"
    override val removeWorktreeTitle = { p: String -> "להסיר את $p?" }
    override val removeWorktreeBody = "התיקייה נמחקת. הענף נשאר."
    override val evenUnsaved = "גם אם יש שינויים לא שמורים"
    override val remove = "הסרה"
    override val removed = "ה־worktree הוסר"
}

private val EnglishCheckpointsPageStrings = CheckpointsPageStrings()

/** `S.checkpointsPage.x` in a composable. */
val S.checkpointsPage: CheckpointsPageStrings @Composable get() = if (current.rtl) HebrewCheckpointsPageStrings else EnglishCheckpointsPageStrings
