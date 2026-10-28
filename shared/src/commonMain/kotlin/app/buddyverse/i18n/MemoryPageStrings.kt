package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by More ▸ Memory (ui/more/MemoryPage.kt, ui/more/memory). Same pattern as [Strings]: English base, Hebrew overrides.
 * Shared words (Cancel, Edit, Save, Undo, Try again…) stay in [Strings].
 */
open class MemoryPageStrings {
    open val aboutYou = "About you"
    open val notes = "Notes"
    open val learned = "Learned"
    open val providers = "Providers"
    open val aboutYouOn = "Remembers things about you"
    open val notesOn = "Keeps its own notes"
    open val used = { n: Int, of: Int -> "$n / $of characters" }
    open val full = "Full. It has to forget something to learn more."
    open val search = "Search memory"
    open val noAbout = "Nothing about you yet"
    open val noNotes = "No notes yet"
    open val noLearned = "No learned skills yet"
    open val noMatches = "Nothing matches"
    open val teach = "Teach"
    open val refresh = "Refresh"
    open val teachTitle = { bot: String -> "Tell $bot to remember" }
    open val teachHint = "Something worth remembering"
    open val teachPrompt = { text: String -> "Please remember this: $text" }
    open val forget = "Forget"
    open val forgotten = "Forgotten"
    open val archive = "Archive"
    open val archived = "Archived"
    open val pinned = "It's pinned. Unpin it in Skills first."
    open val saved = "Saved"
    open val saving = "Saving…"
    open val emptyUseForget = "Empty? Use Forget instead."
    open val note = "Note"
    open val skill = "Learned skill"
    open val usedTimes = { n: Long -> if (n == 1L) "used once" else "used $n times" }
    open val forgetAll = "Forget all"
    open val forgetAllAbout = "Everything it knows about you is erased. This can't be undone."
    open val forgetAllNotes = "All its notes are erased. This can't be undone."
    open val erased = "Erased"
    open val askFirst = "Ask me before saving"
    open val builtIn = "Built-in"
    open val builtInOnly = "Built-in only"
    open val builtInDesc = "Notes and About you, kept on your PC"
    open val extra = "Extra memory"
    open val ready = "ready"
    open val needsSetup = "needs setup"
    open val unavailable = "unavailable"
    open val missing = "missing"
    open val inUse = "in use"
    open val use = "Use it"
    open val stopUsing = "Stop using"
    open val saveAndUse = "Save and use"
    open val install = "Install and save"
    open val installing = "Installing…"
    open val installFailed = "Install didn't finish"
    open val needs = "Needs"
    open val secretSet = "saved, type to replace"
    open val required = "required"
    open val connect = "Connect"
    open val connecting = "Finish signing in on your PC…"
    open val connectedTo = "Connected"
    open val newChats = "New chats use it"
    open val settingsSaved = "Saved. New chats use it."
}

private object HebrewMemoryPageStrings : MemoryPageStrings() {
    override val aboutYou = "עליך"
    override val notes = "הערות"
    override val learned = "נלמד"
    override val providers = "ספקים"
    override val aboutYouOn = "זוכר דברים עליך"
    override val notesOn = "שומר הערות משלו"
    override val used = { n: Int, of: Int -> "$n / $of תווים" }
    override val full = "מלא. צריך לשכוח משהו כדי ללמוד עוד."
    override val search = "חיפוש בזיכרון"
    override val noAbout = "עדיין אין כלום עליך"
    override val noNotes = "עדיין אין הערות"
    override val noLearned = "עדיין אין מיומנויות שנלמדו"
    override val noMatches = "אין התאמות"
    override val teach = "ללמד"
    override val refresh = "רענון"
    override val teachTitle = { bot: String -> "לבקש מ$bot לזכור" }
    override val teachHint = "משהו ששווה לזכור"
    override val teachPrompt = { text: String -> "תזכור את זה בבקשה: $text" }
    override val forget = "לשכוח"
    override val forgotten = "נשכח"
    override val archive = "לארכיון"
    override val archived = "הועבר לארכיון"
    override val pinned = "היא נעוצה. צריך לבטל נעיצה במיומנויות קודם."
    override val saved = "נשמר"
    override val saving = "שומר…"
    override val emptyUseForget = "ריק? עדיף ללחוץ לשכוח."
    override val note = "הערה"
    override val skill = "מיומנות שנלמדה"
    override val usedTimes = { n: Long -> if (n == 1L) "שימוש אחד" else "$n שימושים" }
    override val forgetAll = "לשכוח הכול"
    override val forgetAllAbout = "כל מה שהוא יודע עליך יימחק. אי אפשר לבטל."
    override val forgetAllNotes = "כל ההערות שלו יימחקו. אי אפשר לבטל."
    override val erased = "נמחק"
    override val askFirst = "לשאול אותי לפני ששומר"
    override val builtIn = "מובנה"
    override val builtInOnly = "רק המובנה"
    override val builtInDesc = "הערות ועליך, נשמרים במחשב שלך"
    override val extra = "זיכרון נוסף"
    override val ready = "מוכן"
    override val needsSetup = "צריך הגדרה"
    override val unavailable = "לא זמין"
    override val missing = "חסר"
    override val inUse = "בשימוש"
    override val use = "להשתמש"
    override val stopUsing = "להפסיק"
    override val saveAndUse = "לשמור ולהשתמש"
    override val install = "להתקין ולשמור"
    override val installing = "מתקין…"
    override val installFailed = "ההתקנה לא הסתיימה"
    override val needs = "צריך"
    override val secretSet = "שמור, אפשר להקליד כדי להחליף"
    override val required = "חובה"
    override val connect = "חיבור"
    override val connecting = "צריך לסיים את הכניסה במחשב…"
    override val connectedTo = "מחובר"
    override val newChats = "צ'אטים חדשים ישתמשו בזה"
    override val settingsSaved = "נשמר. צ'אטים חדשים ישתמשו בזה."
}

private val EnglishMemoryPageStrings = MemoryPageStrings()

/** `S.memoryPage.x` in a composable. */
val S.memoryPage: MemoryPageStrings @Composable get() = if (current.rtl) HebrewMemoryPageStrings else EnglishMemoryPageStrings
