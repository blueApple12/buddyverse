package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by More ▸ Kanban (ui/more/KanbanPage.kt, ui/more/kanban). Same pattern as [Strings]: English base, Hebrew overrides.
 * Shared words (Back, Close, Cancel, Save, Edit, Delete, Undo, Rename…) stay in [Strings].
 */
open class KanbanPageStrings {
    open val newTask = "New task"
    open val editTask = "Edit task"
    open val newBoard = "New board"
    open val renameBoard = "Rename board"
    open val boardName = "Board name"
    open val title = "What needs doing"
    open val body = "Details"
    open val nobody = "Nobody"
    open val who = "Who"
    open val priority = "Priority"
    open val prioNormal = "Normal"
    open val prioHigh = "High"
    open val prioTop = "Top"
    open val startInTriage = "Plan it first"
    open val add = "Add"
    open val all = "All"
    open val emptyBoard = "No tasks on this board"
    open val emptyColumn = "Nothing here"
    open val moveTo = "Move to"
    open val stopWorker = "Stop"
    open val start = "Start"
    open val markDone = "Done"
    open val reopen = "Reopen"
    open val specify = "Write it out"
    open val decompose = "Split into tasks"
    open val archiveTask = "Archive"
    open val openTask = "Open"
    open val comments = "Comments"
    open val writeComment = "Write a comment"
    open val runs = "Runs"
    open val activity = "Activity"
    open val showLog = "Worker log"
    open val noLog = "No log yet"
    open val summary = "Summary"
    open val result = "Result"
    open val lastError = "Last error"
    open val dependsOn = "Waits for"
    open val subtasks = "Sub-tasks"
    open val files = "Files"
    open val orchestration = "Team setup"
    open val planner = "Planner"
    open val defaultWorker = "Default worker"
    open val autoSplit = "Split big tasks"
    open val autoStart = "Start sub-tasks on their own"
    open val setMain = "Make main board"
    open val deleteBoard = "Delete board"
    open val confirmDeleteBoard = { n: String -> "Delete the board $n and its tasks?" }
    open val needTitle = "Write what needs doing"
    open val needName = "Give it a name"
    open val added = { n: String -> "$n added" }
    open val moved = { n: String, col: String -> "$n · $col" }
    open val deleted = { n: String -> "$n deleted" }
    open val archived = { n: String -> "$n archived" }
    open val finished = { n: String -> "$n is done" }
    open val finishedMany = { n: Int -> "$n tasks done" }
    open val blocked = { n: String -> "$n is stuck" }
    open val failed = { n: String -> "$n failed" }
    open val stopped = { n: String -> "$n stopped" }
    open val splitInto = { n: Int -> if (n == 1) "Split into 1 task" else "Split into $n tasks" }
    open val written = "Written out"
    open val boardCreated = { n: String -> "Board $n created" }
    open val tasksCount = { n: Int -> if (n == 1) "1 task" else "$n tasks" }
    open val runningCount = { n: Int -> "$n running" }
    open val stuckCount = { n: Int -> "$n stuck" }
    open val showAllDone = { n: Int -> "All done ($n)" }
    open val failures = { n: Int -> if (n == 1) "failed once" else "failed $n times" }
    open val runFor = { s: String -> "for $s" }
    open val noWorker = "Nobody is set to work on it"

    open fun column(key: String): String = when (key) {
        "triage" -> "Triage"
        "todo" -> "To do"
        "scheduled" -> "Scheduled"
        "ready" -> "Ready"
        "running" -> "Running"
        "blocked" -> "Stuck"
        "review" -> "Review"
        "done" -> "Done"
        "archived" -> "Archived"
        else -> key.replaceFirstChar { it.uppercase() }
    }

    open fun event(kind: String): String = when (kind) {
        "created" -> "created"
        "edited" -> "edited"
        "assigned" -> "assigned"
        "status" -> "moved"
        "blocked" -> "got stuck"
        "unblocked" -> "unstuck"
        "completed" -> "done"
        "commented" -> "comment"
        "spawned" -> "worker started"
        "crashed" -> "worker crashed"
        "timed_out" -> "timed out"
        "gave_up" -> "gave up"
        "reclaimed" -> "stopped"
        "review_requested" -> "sent to review"
        "archived" -> "archived"
        "reprioritized" -> "priority changed"
        "linked" -> "linked"
        "unlinked" -> "unlinked"
        "promoted" -> "ready"
        "scheduled" -> "scheduled"
        else -> kind.replace('_', ' ')
    }
}

private object HebrewKanbanPageStrings : KanbanPageStrings() {
    override val newTask = "משימה חדשה"
    override val editTask = "עריכת משימה"
    override val newBoard = "לוח חדש"
    override val renameBoard = "שינוי שם הלוח"
    override val boardName = "שם הלוח"
    override val title = "מה צריך לעשות"
    override val body = "פרטים"
    override val nobody = "אף אחד"
    override val who = "מי"
    override val priority = "עדיפות"
    override val prioNormal = "רגילה"
    override val prioHigh = "גבוהה"
    override val prioTop = "דחופה"
    override val startInTriage = "לתכנן קודם"
    override val add = "הוספה"
    override val all = "הכול"
    override val emptyBoard = "אין משימות בלוח הזה"
    override val emptyColumn = "אין כאן כלום"
    override val moveTo = "העברה אל"
    override val stopWorker = "עצירה"
    override val start = "התחלה"
    override val markDone = "בוצע"
    override val reopen = "פתיחה מחדש"
    override val specify = "לפרט"
    override val decompose = "לפרק למשימות"
    override val archiveTask = "לארכיון"
    override val openTask = "פתיחה"
    override val comments = "תגובות"
    override val writeComment = "כתיבת תגובה"
    override val runs = "הרצות"
    override val activity = "פעילות"
    override val showLog = "יומן העבודה"
    override val noLog = "עוד אין יומן"
    override val summary = "סיכום"
    override val result = "תוצאה"
    override val lastError = "שגיאה אחרונה"
    override val dependsOn = "תלויה ב"
    override val subtasks = "תת-משימות"
    override val files = "קבצים"
    override val orchestration = "הגדרות הצוות"
    override val planner = "מתכנן"
    override val defaultWorker = "עובד ברירת מחדל"
    override val autoSplit = "לפרק משימות גדולות"
    override val autoStart = "להתחיל תת-משימות לבד"
    override val setMain = "לוח ראשי"
    override val deleteBoard = "מחיקת הלוח"
    override val confirmDeleteBoard = { n: String -> "למחוק את הלוח $n ואת המשימות שבו?" }
    override val needTitle = "צריך לכתוב מה לעשות"
    override val needName = "צריך לתת שם"
    override val added = { n: String -> "$n נוספה" }
    override val moved = { n: String, col: String -> "$n · $col" }
    override val deleted = { n: String -> "$n נמחקה" }
    override val archived = { n: String -> "$n הועברה לארכיון" }
    override val finished = { n: String -> "$n בוצעה" }
    override val finishedMany = { n: Int -> "$n משימות בוצעו" }
    override val blocked = { n: String -> "$n תקועה" }
    override val failed = { n: String -> "$n נכשלה" }
    override val stopped = { n: String -> "$n נעצרה" }
    override val splitInto = { n: Int -> if (n == 1) "פורקה למשימה אחת" else "פורקה ל-$n משימות" }
    override val written = "פורט"
    override val boardCreated = { n: String -> "הלוח $n נוצר" }
    override val tasksCount = { n: Int -> if (n == 1) "משימה אחת" else "$n משימות" }
    override val runningCount = { n: Int -> "$n רצות" }
    override val stuckCount = { n: Int -> "$n תקועות" }
    override val showAllDone = { n: Int -> "כל מה שבוצע ($n)" }
    override val failures = { n: Int -> if (n == 1) "נכשלה פעם אחת" else "נכשלה $n פעמים" }
    override val runFor = { s: String -> "כבר $s" }
    override val noWorker = "אף אחד לא מוגדר לעבוד עליה"

    override fun column(key: String): String = when (key) {
        "triage" -> "מיון"
        "todo" -> "לעשות"
        "scheduled" -> "מתוזמן"
        "ready" -> "מוכן"
        "running" -> "רץ"
        "blocked" -> "תקוע"
        "review" -> "לבדיקה"
        "done" -> "בוצע"
        "archived" -> "ארכיון"
        else -> key
    }

    override fun event(kind: String): String = when (kind) {
        "created" -> "נוצרה"
        "edited" -> "נערכה"
        "assigned" -> "הוקצתה"
        "status" -> "הועברה"
        "blocked" -> "נתקעה"
        "unblocked" -> "שוחררה"
        "completed" -> "בוצעה"
        "commented" -> "תגובה"
        "spawned" -> "עובד התחיל"
        "crashed" -> "העובד קרס"
        "timed_out" -> "נגמר הזמן"
        "gave_up" -> "ויתר"
        "reclaimed" -> "נעצרה"
        "review_requested" -> "נשלחה לבדיקה"
        "archived" -> "בארכיון"
        "reprioritized" -> "עדיפות שונתה"
        "linked" -> "קושרה"
        "unlinked" -> "הקישור הוסר"
        "promoted" -> "מוכנה"
        "scheduled" -> "תוזמנה"
        else -> kind.replace('_', ' ')
    }
}

private val EnglishKanbanPageStrings = KanbanPageStrings()

/** `S.kanbanPage.x` in a composable. */
val S.kanbanPage: KanbanPageStrings @Composable get() = if (current.rtl) HebrewKanbanPageStrings else EnglishKanbanPageStrings
