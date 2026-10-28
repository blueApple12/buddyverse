package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by More ▸ Files (ui/more/FilesPage.kt, ui/more/files). Same pattern as [Strings]: English base, Hebrew overrides.
 * Shared words (Cancel, Delete, Edit, Save, Open, Copy, Try again…) stay in [Strings].
 */
open class FilesPageStrings {
    open val add = "Add"
    open val refresh = "Refresh"
    open val up = "Up one folder"
    open val upload = "Upload a file"
    open val newFolder = "New folder"
    open val newFile = "New text file"
    open val folderName = "Folder name"
    open val fileName = "File name"
    open val badName = "A name can't have \\ / : * ? \" < > |"
    open val exists = "That name is already here"
    open val filter = "Find in this folder"
    open val hidden = "Hidden"
    open val empty = "Nothing in here"
    open val noMatches = "Nothing matches"
    open val download = "Download"
    open val downloading = "Downloading…"
    open val downloaded = "Saved to Downloads"
    open val cantSave = "This device can't save files"
    open val noApp = "No app here opens this file"
    open val copyPath = "Copy path"
    open val pathCopied = "Path copied"
    open val deleteTitle = { name: String -> "Delete $name?" }
    open val deleteFile = "It's gone from your PC for good."
    open val deleteFolder = "The folder and everything in it are gone from your PC for good."
    open val deleted = "Deleted"
    open val replaceTitle = { name: String -> "Replace $name?" }
    open val replaceBody = "A file with this name is already in this folder."
    open val replace = "Replace"
    open val keepBoth = "Keep both"
    open val uploading = "Uploading…"
    open val uploaded = { name: String -> "$name uploaded" }
    open val tooBigUpload = "Too big to upload (16 MB max)"
    open val saved = "Saved"
    open val saving = "Saving…"
    open val unsavedTitle = "Discard your changes?"
    open val unsavedBody = "What you typed here isn't saved yet."
    open val discard = "Discard"
    open val keepEditing = "Keep editing"
    open val noPreview = "No preview for this kind of file"
    open val tooBigPreview = "Too big to show here"
    open val startOnly = "Showing the start only. Download it to see all of it."
    open val folder = "Folder"
    open val brokenLink = "Broken link"
    open val notAllowed = "Not allowed here"
    open val notThere = "It isn't there anymore"
    open val tooBig = "Too big"
    open val offline = "Can't reach your PC"
    open val failed = "That didn't work"
}

private object HebrewFilesPageStrings : FilesPageStrings() {
    override val add = "הוספה"
    override val refresh = "רענון"
    override val up = "תיקייה אחת למעלה"
    override val upload = "העלאת קובץ"
    override val newFolder = "תיקייה חדשה"
    override val newFile = "קובץ טקסט חדש"
    override val folderName = "שם התיקייה"
    override val fileName = "שם הקובץ"
    override val badName = "בשם אי אפשר להשתמש ב־ \\ / : * ? \" < > |"
    override val exists = "השם הזה כבר קיים כאן"
    override val filter = "חיפוש בתיקייה"
    override val hidden = "מוסתרים"
    override val empty = "אין כאן כלום"
    override val noMatches = "לא נמצא כלום"
    override val download = "הורדה"
    override val downloading = "מוריד…"
    override val downloaded = "נשמר בהורדות"
    override val cantSave = "אי אפשר לשמור קבצים במכשיר הזה"
    override val noApp = "אין כאן אפליקציה שפותחת את הקובץ"
    override val copyPath = "העתקת הנתיב"
    override val pathCopied = "הנתיב הועתק"
    override val deleteTitle = { name: String -> "למחוק את $name?" }
    override val deleteFile = "הוא יימחק מהמחשב לתמיד."
    override val deleteFolder = "התיקייה וכל מה שבתוכה יימחקו מהמחשב לתמיד."
    override val deleted = "נמחק"
    override val replaceTitle = { name: String -> "להחליף את $name?" }
    override val replaceBody = "כבר יש בתיקייה קובץ בשם הזה."
    override val replace = "להחליף"
    override val keepBoth = "לשמור את שניהם"
    override val uploading = "מעלה…"
    override val uploaded = { name: String -> "$name הועלה" }
    override val tooBigUpload = "גדול מדי להעלאה (עד 16MB)"
    override val saved = "נשמר"
    override val saving = "שומר…"
    override val unsavedTitle = "לבטל את השינויים?"
    override val unsavedBody = "מה שהקלדת כאן עוד לא נשמר."
    override val discard = "לבטל"
    override val keepEditing = "להמשיך לערוך"
    override val noPreview = "אין תצוגה מקדימה לסוג הקובץ הזה"
    override val tooBigPreview = "גדול מדי להצגה כאן"
    override val startOnly = "מוצגת רק ההתחלה. אפשר להוריד כדי לראות הכול."
    override val folder = "תיקייה"
    override val brokenLink = "קישור שבור"
    override val notAllowed = "אין הרשאה כאן"
    override val notThere = "זה כבר לא שם"
    override val tooBig = "גדול מדי"
    override val offline = "אין חיבור למחשב"
    override val failed = "זה לא הצליח"
}

private val EnglishFilesPageStrings = FilesPageStrings()

/** `S.filesPage.x` in a composable. */
val S.filesPage: FilesPageStrings @Composable get() = if (current.rtl) HebrewFilesPageStrings else EnglishFilesPageStrings
