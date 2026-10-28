package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by Routines (ui/routines). Same pattern as [Strings]: English base, Hebrew overrides, nothing hard-coded in screens.
 * Shared words (Back, Close, Cancel…) stay in [Strings] (read-only for areas); add this area's own words here.
 */
open class RoutinesStrings {
    open val nextIn = { t: String -> "next in $t" }
    open val everyMinutes = { n: Long -> "Every $n min" }
    open val everyHours = { n: Long -> "Every $n hours" }
    open val weekends = "Weekends"
    open val monthly = { day: String -> "Day $day of each month" }
    /** Sunday first, matching cron's 0. */
    open val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    open val sendTo = "Send to"
    open val botChat = "Its chat"
    open val justSave = "Just save"
    open val model = "Model"
    open val modelDefault = "Bot's default"
    open val fromTemplate = "Start from a template"
    open val templates = "Templates"
    /** next_run_at passed more than 15 min ago: the PC's scheduler isn't ticking (hermes-api.md §2.5). */
    open val overdue = "late · is the PC awake?"
    open val homeChannel = "Home channel"
}

private object HebrewRoutinesStrings : RoutinesStrings() {
    override val nextIn = { t: String -> "הבאה בעוד $t" }
    override val everyMinutes = { n: Long -> "כל $n דקות" }
    override val everyHours = { n: Long -> "כל $n שעות" }
    override val weekends = "סופי שבוע"
    override val monthly = { day: String -> "כל חודש ב־$day" }
    override val dayNames = listOf("ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת")
    override val sendTo = "לשלוח אל"
    override val botChat = "הצ׳אט שלו"
    override val justSave = "רק לשמור"
    override val model = "מודל"
    override val modelDefault = "ברירת המחדל של הבוט"
    override val fromTemplate = "להתחיל מתבנית"
    override val templates = "תבניות"
    override val overdue = "באיחור · המחשב ער?"
    override val homeChannel = "ערוץ הבית"
}

private val EnglishRoutinesStrings = RoutinesStrings()

/** `S.routines.x` in a composable. */
val S.routines: RoutinesStrings @Composable get() = if (current.rtl) HebrewRoutinesStrings else EnglishRoutinesStrings
