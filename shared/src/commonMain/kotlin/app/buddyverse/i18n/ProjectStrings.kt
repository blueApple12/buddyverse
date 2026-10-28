package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by Project (ui/project). Same pattern as [Strings]: English base, Hebrew overrides, nothing hard-coded in screens.
 * Shared words (Back, Close, Cancel…) stay in [Strings] (read-only for areas); add this area's own words here.
 */
open class ProjectStrings {
    open val info = "Project info"
    open val folder = "Folder"
    open val subprojects = "Subprojects"
    open val openInObsidian = "Open in Obsidian"
    // screen
    open val notFound = "This project is gone"
    open val agents = "Agents"
    open val helpers = "Helpers"
    open val startRoom = "Start the project chat"
    open val noRoom = "No project chat yet"
    open val watch = "Watch"
    // info sheet
    open val edit = "Edit project"
    open val name = "Name"
    open val about = "What it's about"
    open val folders = "Folders"
    open val mainFolder = "main"
    open val makeMain = "Make it the main folder"
    open val removeFolder = "Remove from the project"
    open val addFolder = "Add a folder (path on the PC)"
    open val copyPath = "Copy path"
    open val copied = "Copied"
    open val page = "Project page"
    open val now = "Now"
    open val next = "Next"
    open val noPage = "No project page in the brain yet"
    open val sessions = "Sessions"
    open val noSessions = "No sessions yet"
    open val stats = { sessions: Int, tokens: String, cost: String? -> listOfNotNull("$sessions sessions", "$tokens tokens", cost).joinToString(" · ") }
    open val archive = "Archive project"
    open val restore = "Bring back"
    open val delete = "Delete project"
    open val archiveAsk = "Archive this project? You can bring it back from More ▸ Projects."
    open val deleteAsk = "Delete this project for good? Its folders and chats stay on the PC."
    open val archived = "Project archived"
    open val deleted = "Project deleted"
    open val isArchived = "archived"
    open val saved = "Saved"
}

private object HebrewProjectStrings : ProjectStrings() {
    override val info = "פרטי הפרויקט"
    override val folder = "תיקייה"
    override val subprojects = "תת־פרויקטים"
    override val openInObsidian = "פתיחה ב־Obsidian"
    override val notFound = "הפרויקט הזה כבר לא קיים"
    override val agents = "סוכנים"
    override val helpers = "עוזרים"
    override val startRoom = "פתיחת שיחת הפרויקט"
    override val noRoom = "עדיין אין שיחת פרויקט"
    override val watch = "צפייה"
    override val edit = "עריכת הפרויקט"
    override val name = "שם"
    override val about = "על מה הפרויקט"
    override val folders = "תיקיות"
    override val mainFolder = "ראשית"
    override val makeMain = "להפוך לתיקייה הראשית"
    override val removeFolder = "להסיר מהפרויקט"
    override val addFolder = "הוספת תיקייה (נתיב במחשב)"
    override val copyPath = "העתקת הנתיב"
    override val copied = "הועתק"
    override val page = "דף הפרויקט"
    override val now = "עכשיו"
    override val next = "הבא"
    override val noPage = "עדיין אין דף לפרויקט במוח"
    override val sessions = "שיחות"
    override val noSessions = "עדיין אין שיחות"
    override val stats = { sessions: Int, tokens: String, cost: String? -> listOfNotNull("$sessions שיחות", "$tokens טוקנים", cost).joinToString(" · ") }
    override val archive = "העברת הפרויקט לארכיון"
    override val restore = "החזרה"
    override val delete = "מחיקת הפרויקט"
    override val archiveAsk = "להעביר את הפרויקט לארכיון? אפשר להחזיר אותו מעוד ▸ פרויקטים."
    override val deleteAsk = "למחוק את הפרויקט לתמיד? התיקיות והשיחות נשארות במחשב."
    override val archived = "הפרויקט הועבר לארכיון"
    override val deleted = "הפרויקט נמחק"
    override val isArchived = "בארכיון"
    override val saved = "נשמר"
}

private val EnglishProjectStrings = ProjectStrings()

/** `S.project.x` in a composable. */
val S.project: ProjectStrings @Composable get() = if (current.rtl) HebrewProjectStrings else EnglishProjectStrings
