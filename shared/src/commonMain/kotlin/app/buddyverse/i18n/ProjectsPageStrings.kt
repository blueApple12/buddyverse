package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by More ▸ Projects (ui/more/ProjectsPage.kt, ui/more/projects). Same pattern as [Strings]: English base, Hebrew overrides.
 * Shared words (Open, Edit, Delete, Archive, Archived, Cancel, Save, Undo…) stay in [Strings].
 */
open class ProjectsPageStrings {
    open val newProject = "New project"
    open val editProject = "Edit project"
    open val active = "Active"
    open val noProjects = "No projects yet"
    open val noArchived = "Nothing archived"
    open val name = "Name"
    open val folder = "Folder on your PC, e.g. C:\\code\\app"
    open val description = "What is it about? (optional)"
    open val color = "Color"
    open val whose = "Whose project"
    open val onYourPc = "On your PC"
    open val folders = "Folders"
    open val addFolder = "Add folder"
    open val mainFolder = "main folder"
    open val makeMain = { path: String -> "Make $path the main folder" }
    open val removeFolder = "Remove folder"
    open val noFolder = "No folder"
    open val creating = "Creating…"
    open val restore = "Restore"
    open val current = "current"
    open val makeCurrent = "Start new chats here"
    open val stopCurrent = "Stop starting chats here"
    open val created = { n: String -> "$n is ready" }
    open val archivedName = { n: String -> "$n archived" }
    open val restored = { n: String -> "$n is back" }
    open val deleted = { n: String -> "$n deleted" }
    open val saved = "Saved"
    open val needName = "Give it a name"
    open val deleteTitle = { n: String -> "Delete $n?" }
    open val deleteBody = "The project and its folder list go away for good. The files on your PC stay."
    open val summary = { n: Int, bots: Int ->
        (if (n == 1) "1 project" else "$n projects") + if (bots > 1) " · $bots bots" else ""
    }
}

private object HebrewProjectsPageStrings : ProjectsPageStrings() {
    override val newProject = "פרויקט חדש"
    override val editProject = "עריכת פרויקט"
    override val active = "פעילים"
    override val noProjects = "אין עדיין פרויקטים"
    override val noArchived = "אין פרויקטים בארכיון"
    override val name = "שם"
    override val folder = "תיקייה במחשב, למשל C:\\code\\app"
    override val description = "על מה הוא? (לא חובה)"
    override val color = "צבע"
    override val whose = "של מי הפרויקט"
    override val onYourPc = "במחשב שלך"
    override val folders = "תיקיות"
    override val addFolder = "הוספת תיקייה"
    override val mainFolder = "תיקייה ראשית"
    override val makeMain = { path: String -> "להפוך את $path לתיקייה הראשית" }
    override val removeFolder = "הסרת תיקייה"
    override val noFolder = "בלי תיקייה"
    override val creating = "יוצר…"
    override val restore = "שחזור"
    override val current = "נוכחי"
    override val makeCurrent = "לפתוח כאן שיחות חדשות"
    override val stopCurrent = "להפסיק לפתוח כאן שיחות"
    override val created = { n: String -> "$n מוכן" }
    override val archivedName = { n: String -> "$n הועבר לארכיון" }
    override val restored = { n: String -> "$n חזר" }
    override val deleted = { n: String -> "$n נמחק" }
    override val saved = "נשמר"
    override val needName = "צריך לתת שם"
    override val deleteTitle = { n: String -> "למחוק את $n?" }
    override val deleteBody = "הפרויקט ורשימת התיקיות שלו יימחקו לתמיד. הקבצים במחשב נשארים."
    override val summary = { n: Int, bots: Int ->
        (if (n == 1) "פרויקט אחד" else "$n פרויקטים") + if (bots > 1) " · $bots בוטים" else ""
    }
}

private val EnglishProjectsPageStrings = ProjectsPageStrings()

/** `S.projectsPage.x` in a composable. */
val S.projectsPage: ProjectsPageStrings @Composable get() = if (current.rtl) HebrewProjectsPageStrings else EnglishProjectsPageStrings
