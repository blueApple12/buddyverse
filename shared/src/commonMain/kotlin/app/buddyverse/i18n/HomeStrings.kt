package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by Bots home (ui/home). Same pattern as [Strings]: English base, Hebrew overrides, nothing hard-coded in screens.
 * Shared words (Back, Close, Cancel…) stay in [Strings] (read-only for areas); add this area's own words here.
 */
open class HomeStrings {
    open val makeFirstBot = "Make your first bot"
    open val wakeIt = "Wake it"
    open val messageAction = "Message"
    open val profile = "Profile"
    open val hide = "Hide"
    open val show = "Show"
    open val markRead = "Mark read"
    open val newGroup = "New group chat"
    open val showHidden = { n: Int -> "Show $n hidden" }
    open val tuckHidden = "Hide them again"
    open val botHidden = { name: String -> "$name hidden" }
    open val botDeleted = { name: String -> "$name deleted" }
    open val deleteTitle = { name: String -> "Delete $name?" }
    open val deleteBody = "Its chats, memory and skills are deleted from the PC too."
    open val helpers = { n: Int -> if (n == 1) "1 helper" else "$n helpers" }
    open val helper = "Helper"
    open val helperGone = "That helper's chat isn't available (it may have just finished)"
    open val play = "Play"
}

private fun iso(s: String) = "⁨$s⁩"

private object HebrewHomeStrings : HomeStrings() {
    override val makeFirstBot = "ליצור את הבוט הראשון"
    override val wakeIt = "להעיר"
    override val messageAction = "הודעה"
    override val profile = "פרופיל"
    override val hide = "הסתרה"
    override val show = "הצגה"
    override val markRead = "סימון כנקרא"
    override val newGroup = "צ'אט קבוצתי חדש"
    override val showHidden = { n: Int -> "להציג מוסתרים ($n)" }
    override val tuckHidden = "להסתיר שוב"
    override val botHidden = { name: String -> "${iso(name)} הוסתר" }
    override val botDeleted = { name: String -> "${iso(name)} נמחק" }
    override val deleteTitle = { name: String -> "למחוק את ${iso(name)}?" }
    override val deleteBody = "גם הצ'אטים, הזיכרון והמיומנויות שלו יימחקו מהמחשב."
    override val helpers = { n: Int -> if (n == 1) "עוזר אחד" else "$n עוזרים" }
    override val helper = "עוזר"
    override val helperGone = "הצ'אט של העוזר לא זמין (אולי הוא בדיוק סיים)"
    override val play = "משחקים"
}

private val EnglishHomeStrings = HomeStrings()

/** `S.home.x` in a composable. */
val S.home: HomeStrings @Composable get() = if (current.rtl) HebrewHomeStrings else EnglishHomeStrings
