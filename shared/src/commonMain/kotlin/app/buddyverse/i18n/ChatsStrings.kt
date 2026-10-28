package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by Chats (ui/chats). Same pattern as [Strings]: English base, Hebrew overrides, nothing hard-coded in screens.
 * Shared words (Back, Close, Cancel…) stay in [Strings] (read-only for areas); add this area's own words here.
 */
open class ChatsStrings {
    open val earlier = "Earlier"
    open val pinned = "Pinned"
    open val searchHint = "Search chats"
    open val noMatches = "No matching chats"
    open val noLive = "Nothing running right now"
    open val noArchived = "No closed chats"
    open val loadFailed = "Couldn't load your chats"
    open val liveNow = "live"
    open val restore = "Restore"
    open val restored = "Back in your chats"
    open val deleted = "Chat deleted"
    open val copyText = "Copy as text"
    open val copied = "Copied"
    open val me = "Me"
    /** Filter for internal work: one-shots, tool and helper runs, kanban workers, routine runs. */
    open val background = "Background"
    open val noBackground = "No background work"
}

private object HebrewChatsStrings : ChatsStrings() {
    override val earlier = "קודם"
    override val pinned = "מוצמדים"
    override val searchHint = "חיפוש בצ'אטים"
    override val noMatches = "לא נמצאו צ'אטים"
    override val noLive = "שום דבר לא רץ כרגע"
    override val noArchived = "אין צ'אטים סגורים"
    override val loadFailed = "לא הצלחתי לטעון את הצ'אטים"
    override val liveNow = "פעיל"
    override val restore = "החזרה"
    override val restored = "הצ'אט חזר לרשימה"
    override val deleted = "הצ'אט נמחק"
    override val copyText = "העתקה כטקסט"
    override val copied = "הועתק"
    override val me = "אני"
    override val background = "ברקע"
    override val noBackground = "אין עבודה ברקע"
}

private val EnglishChatsStrings = ChatsStrings()

/** `S.chats.x` in a composable. */
val S.chats: ChatsStrings @Composable get() = if (current.rtl) HebrewChatsStrings else EnglishChatsStrings
