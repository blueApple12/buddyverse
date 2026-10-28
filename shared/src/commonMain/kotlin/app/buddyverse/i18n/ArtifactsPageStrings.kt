package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by More ▸ Artifacts (ui/more/ArtifactsPage.kt, ui/more/artifacts). Same pattern as [Strings]: English base,
 * Hebrew overrides. Shared words (Open, Copy, Cancel, Try again…) stay in [Strings].
 */
open class ArtifactsPageStrings {
    open val refresh = "Look again"
    open val search = "Find by name, path or chat"
    open val all = "All"
    open val images = "Images"
    open val files = "Files"
    open val links = "Links"
    open val made = "Made in chat"
    open val scanning = { done: Int, total: Int -> "Looking through chat $done of $total" }
    open val starting = "Looking through your chats…"
    open val found = { n: Int, chats: Int -> if (n == 1) "1 thing from your last $chats chats" else "$n things from your last $chats chats" }
    open val nothing = "Nothing made yet"
    open val noMatches = "Nothing matches"
    open val skipped = { n: Int -> if (n == 1) "Couldn't read 1 chat" else "Couldn't read $n chats" }
    open val failed = "Can't read your chats right now"
    open val untitled = "Untitled chat"
    open val download = "Download"
    open val save = "Save to Downloads"
    open val saved = "Saved to Downloads"
    open val cantSave = "This device can't save files"
    open val noApp = "No app here opens this"
    open val openLink = "Open the link"
    open val openFailed = "Couldn't open it"
    open val copyPath = "Copy path"
    open val copyLink = "Copy link"
    open val copySource = "Copy all"
    open val copied = "Copied"
    open val openChat = "Open the chat"
    open val cantShow = "Can't show this image"
    open val page = "Page"
    open val drawing = "Drawing"
    open val code = "Code"
    open val lines = { n: Int -> if (n == 1) "1 line" else "$n lines" }
    open val inChat = { title: String -> "in $title" }
}

private object HebrewArtifactsPageStrings : ArtifactsPageStrings() {
    override val refresh = "לחפש שוב"
    override val search = "חיפוש לפי שם, נתיב או צ'אט"
    override val all = "הכול"
    override val images = "תמונות"
    override val files = "קבצים"
    override val links = "קישורים"
    override val made = "נוצר בצ'אט"
    override val scanning = { done: Int, total: Int -> "עובר על צ'אט $done מתוך $total" }
    override val starting = "עובר על הצ'אטים שלך…"
    override val found = { n: Int, chats: Int -> if (n == 1) "דבר אחד מ־$chats הצ'אטים האחרונים" else "$n דברים מ־$chats הצ'אטים האחרונים" }
    override val nothing = "עוד לא נוצר כלום"
    override val noMatches = "לא נמצא כלום"
    override val skipped = { n: Int -> if (n == 1) "לא הצלחתי לקרוא צ'אט אחד" else "לא הצלחתי לקרוא $n צ'אטים" }
    override val failed = "אי אפשר לקרוא את הצ'אטים כרגע"
    override val untitled = "צ'אט בלי שם"
    override val download = "הורדה"
    override val save = "שמירה בהורדות"
    override val saved = "נשמר בהורדות"
    override val cantSave = "אי אפשר לשמור קבצים במכשיר הזה"
    override val noApp = "אין כאן אפליקציה שפותחת את זה"
    override val openLink = "פתיחת הקישור"
    override val openFailed = "לא הצלחתי לפתוח"
    override val copyPath = "העתקת הנתיב"
    override val copyLink = "העתקת הקישור"
    override val copySource = "העתקת הכול"
    override val copied = "הועתק"
    override val openChat = "פתיחת הצ'אט"
    override val cantShow = "אי אפשר להציג את התמונה"
    override val page = "דף"
    override val drawing = "ציור"
    override val code = "קוד"
    override val lines = { n: Int -> if (n == 1) "שורה אחת" else "$n שורות" }
    override val inChat = { title: String -> "ב־$title" }
}

private val EnglishArtifactsPageStrings = ArtifactsPageStrings()

/** `S.artifactsPage.x` in a composable. */
val S.artifactsPage: ArtifactsPageStrings @Composable get() = if (current.rtl) HebrewArtifactsPageStrings else EnglishArtifactsPageStrings
