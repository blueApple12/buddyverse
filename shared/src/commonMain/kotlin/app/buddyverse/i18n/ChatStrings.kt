package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by Bot chat (ui/chat). Same pattern as [Strings]: English base, Hebrew overrides, nothing hard-coded in screens.
 * Shared words (Back, Close, Cancel…) stay in [Strings] (read-only for areas); add this area's own words here.
 */
open class ChatStrings {
    open val whatItDid = "What it did"
    open val react = "React"
    open val selectText = "Select text"
    open val working = "Working"
    open val steps = { n: Int -> if (n == 1) "1 step" else "$n steps" }
    open val helper = "Helper"
    open val live = "Live"
    open val result = "Result"
    open val stopHelper = "Stop helper"
    open val openHelperChat = "Open its chat"
    open val helperReadOnly = "Helper chat · read only"
    open val options = "Chat options"
    open val messageTitle = "Message"
    open val modelAndThinking = "Model and thinking"
    open val model = "Model"
    open val auto = "Auto"
    open val thinkingLevel = "Thinking"
    open val effort = { e: String ->
        when (e) {
            "none" -> "Off"; "minimal" -> "Minimal"; "low" -> "Low"; "medium" -> "Medium"; "high" -> "High"
            "xhigh" -> "Extra high"; "max" -> "Max"; "ultra" -> "Ultra"; else -> e
        }
    }
    open val searchModels = "Search models"
    open val showAll = { n: Int -> "Show all $n" }
    open val switchAnyway = "Switch anyway"
    open val modelDeferred = "Applies after this turn"
    open val branch = "Branch into a new chat"
    open val branchHere = "Branch from here"
    open val summarized = "Earlier messages summarized"
    open val helperDone = "A helper finished"
    open val jobDone = "A background job finished"
    open val thoughtFor = { t: String -> "Thought for $t" }
    open val compressAsk = "Compress the chat?"
    open val compressGo = "Compress"
    open val compressBody = "Older messages are summarized to make room. This can't be undone."
    open val compress = "Compress the chat"
    open val compressing = "Compressing…"
    open val compressed = "Chat compressed"
    open val nothingToCompress = "Nothing to compress yet"
    open val busy = "It's working. Stop it first."
    open val editResend = "Edit and resend"
    open val regenerate = "New answer"
    open val copied = "Copied"
    open val moreChoices = "More choices"
    open val other = "Other…"
    open val commandsAndSkills = "Commands & skills"
    open val runIt = "Run"
    open val paste = "Paste"
    open val nothingToPaste = "Nothing to paste"
    open val tooBig = "That file is too big"
    open val denied = "Permission is off"
    open val listening = "Listening…"
    open val transcribing = "Writing it down…"
    open val noSpeech = "Didn't catch that"
    open val voiceFailed = "Voice didn't work"
    open val install = "Install"
    open val fileGone = "No longer on the PC"
}

private object HebrewChatStrings : ChatStrings() {
    override val whatItDid = "מה הוא עשה"
    override val react = "תגובה"
    override val selectText = "בחירת טקסט"
    override val working = "עובד"
    override val steps = { n: Int -> if (n == 1) "צעד אחד" else "$n צעדים" }
    override val helper = "עוזר"
    override val live = "בזמן אמת"
    override val result = "תוצאה"
    override val stopHelper = "עצירת העוזר"
    override val openHelperChat = "פתיחת הצ'אט שלו"
    override val helperReadOnly = "צ'אט של עוזר · קריאה בלבד"
    override val options = "אפשרויות הצ'אט"
    override val messageTitle = "הודעה"
    override val modelAndThinking = "מודל וחשיבה"
    override val model = "מודל"
    override val auto = "אוטומטי"
    override val thinkingLevel = "חשיבה"
    override val effort = { e: String ->
        when (e) {
            "none" -> "כבויה"; "minimal" -> "מינימלית"; "low" -> "נמוכה"; "medium" -> "בינונית"; "high" -> "גבוהה"
            "xhigh" -> "גבוהה מאוד"; "max" -> "מקסימלית"; "ultra" -> "אולטרה"; else -> e
        }
    }
    override val searchModels = "חיפוש מודלים"
    override val showAll = { n: Int -> "הצגת כל $n" }
    override val switchAnyway = "להחליף בכל זאת"
    override val modelDeferred = "ייכנס לתוקף אחרי התור הזה"
    override val branch = "פיצול לצ'אט חדש"
    override val branchHere = "פיצול מכאן"
    override val summarized = "הודעות קודמות סוכמו"
    override val helperDone = "עוזר סיים"
    override val jobDone = "משימת רקע הסתיימה"
    override val thoughtFor = { t: String -> "חשב $t" }
    override val compressAsk = "לדחוס את הצ'אט?"
    override val compressGo = "לדחוס"
    override val compressBody = "הודעות ישנות יסוכמו כדי לפנות מקום. אי אפשר לבטל את זה."
    override val compress = "דחיסת הצ'אט"
    override val compressing = "דוחס…"
    override val compressed = "הצ'אט נדחס"
    override val nothingToCompress = "אין עדיין מה לדחוס"
    override val busy = "הוא עובד. עצרו אותו קודם."
    override val editResend = "עריכה ושליחה מחדש"
    override val regenerate = "תשובה חדשה"
    override val copied = "הועתק"
    override val moreChoices = "עוד אפשרויות"
    override val other = "אחר…"
    override val commandsAndSkills = "פקודות ומיומנויות"
    override val runIt = "הרצה"
    override val paste = "הדבקה"
    override val nothingToPaste = "אין מה להדביק"
    override val tooBig = "הקובץ גדול מדי"
    override val denied = "ההרשאה כבויה"
    override val listening = "מקשיב…"
    override val transcribing = "רושם…"
    override val noSpeech = "לא שמעתי"
    override val voiceFailed = "הקול לא עבד"
    override val install = "התקנה"
    override val fileGone = "כבר לא במחשב"
}

private val EnglishChatStrings = ChatStrings()

/** `S.chat.x` in a composable. */
val S.chat: ChatStrings @Composable get() = if (current.rtl) HebrewChatStrings else EnglishChatStrings
