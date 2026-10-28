package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by Bot profile & avatar studio (ui/bot). Same pattern as [Strings]: English base, Hebrew overrides, nothing hard-coded in screens.
 * Shared words (Back, Close, Cancel…) stay in [Strings] (read-only for areas); add this area's own words here.
 * Lambdas that take a handle get it already wrapped LTR (`ltr("@name")`).
 */
open class BotStrings {
    open val editLook = "Edit look"
    open val shape = "Shape"
    open val color = "Color"
    open val name = "Name"
    open val role = "Role"
    open val surpriseMe = "Surprise me"
    open val namePlaceholder = "nova"
    open val rolePlaceholder = "What it does"
    open val writeRole = "Write the role for me"
    open val moodIdle = "Idle"
    open val moodThink = "Think"
    open val moodWork = "Work"
    open val moodTalk = "Talk"
    open val moodNeed = "Need"
    open val moodOops = "Oops"
    open val moodSleep = "Sleep"
    open val creating = "Making it…"
    open val messageBot = "Message"
    open val persona = "Persona"
    open val toolsPreset = "This bot uses a preset tool set. Change it in More > Tools."
    open val model = "Model"
    open val defaultModel = "Default"
    open val duplicate = "Duplicate"
    open val changeHandle = "Change handle"
    open val handleRule = "Use a-z, 0-9, - and _"
    open val handleTaken = "That handle is taken"
    open val searchModels = "Search models"
    open val filter = "Filter"
    open val confirm = "Confirm"
    open val saved = "Saved"
    open val nothingHere = "Nothing here yet"
    open val onOf = { on: Int, total: Int -> "$on of $total on" }
    open val deleteAsk = { name: String -> "Delete $name?" }
    open val deleteBody = "Its chats, memory, skills and settings are removed from the PC."
    open val deleted = { name: String -> "$name deleted" }
    open val made = { name: String -> "Made $name" }
    open val copyOf = { title: String -> "$title (copy)" }
    open val renamed = { handle: String -> "Handle is now $handle" }
}

private object HebrewBotStrings : BotStrings() {
    override val editLook = "עריכת מראה"
    override val shape = "צורה"
    override val color = "צבע"
    override val name = "שם"
    override val role = "תפקיד"
    override val surpriseMe = "הפתיעו אותי"
    override val namePlaceholder = "נובה"
    override val rolePlaceholder = "מה הוא עושה"
    override val writeRole = "לכתוב לי את התפקיד"
    override val moodIdle = "רגוע"
    override val moodThink = "חושב"
    override val moodWork = "עובד"
    override val moodTalk = "מדבר"
    override val moodNeed = "צריך אותך"
    override val moodOops = "אופס"
    override val moodSleep = "ישן"
    override val creating = "יוצר…"
    override val messageBot = "הודעה"
    override val persona = "אישיות"
    override val toolsPreset = "לבוט הזה יש סט כלים מוכן. משנים אותו בעוד > כלים."
    override val model = "מודל"
    override val defaultModel = "ברירת מחדל"
    override val duplicate = "שכפול"
    override val changeHandle = "שינוי כינוי"
    override val handleRule = "רק a-z, 0-9, - ו-_"
    override val handleTaken = "הכינוי הזה תפוס"
    override val searchModels = "חיפוש מודלים"
    override val filter = "סינון"
    override val confirm = "אישור"
    override val saved = "נשמר"
    override val nothingHere = "אין כאן עדיין כלום"
    override val onOf = { on: Int, total: Int -> "$on מתוך $total פעילים" }
    override val deleteAsk = { name: String -> "למחוק את $name?" }
    override val deleteBody = "השיחות, הזיכרון, המיומנויות וההגדרות שלו יימחקו מהמחשב."
    override val deleted = { name: String -> "$name נמחק" }
    override val made = { name: String -> "נוצר $name" }
    override val copyOf = { title: String -> "$title (עותק)" }
    override val renamed = { handle: String -> "הכינוי עכשיו $handle" }
}

private val EnglishBotStrings = BotStrings()

/** `S.bot.x` in a composable. */
val S.bot: BotStrings @Composable get() = if (current.rtl) HebrewBotStrings else EnglishBotStrings
