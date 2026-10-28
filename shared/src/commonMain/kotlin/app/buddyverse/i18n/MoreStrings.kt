package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by More hub (ui/more/MoreScreen.kt). Same pattern as [Strings]: English base, Hebrew overrides, nothing hard-coded in screens.
 * Shared words (Back, Close, Cancel…) stay in [Strings] (read-only for areas); add this area's own words here.
 */
open class MoreStrings {
    open val files = "Files"
    // engine card
    open val engineStopped = "stopped"
    open val engineStarting = "starting…"
    open val engineStopping = "stopping…"
    open val engineDraining = "finishing up"
    open val engineTrouble = "needs a look"
    open val engineOn = "Turn the engine on"
    open val engineOff = "Turn the engine off"
    open val engineFailed = "The engine didn't switch"
    /** The phone talks to the PC through the engine: turning it off from here could never be undone from here. */
    open val engineOffPhone = "Turn the engine off on the PC. From the phone it couldn't be turned back on."
    open val working = { n: Int -> "$n working" }
    open val stopTitle = "Stop the engine?"
    open val stopBody = "Your bots can't answer until it's back on."
    open val stop = "Stop"
    open val busyTitle = "Bots are mid-turn"
    open val busyBody = "Stopping now cuts them off."
    open val stopAnyway = "Stop anyway"
    // phone link
    open val linkActive = "active"
    open val linkConnecting = "connecting…"
    open val linkOffline = "offline"
    open val copyLink = "Copy link"
    open val copied = "Link copied"
    // tile counts
    open val allGood = "All good"
    open val notAllGood = "Needs a look"
    open val on = { n: Int -> "$n on" }
}

private object HebrewMoreStrings : MoreStrings() {
    override val files = "קבצים"
    override val engineStopped = "כבוי"
    override val engineStarting = "מתניע…"
    override val engineStopping = "נכבה…"
    override val engineDraining = "מסיים"
    override val engineTrouble = "צריך בדיקה"
    override val engineOn = "הדלקת המנוע"
    override val engineOff = "כיבוי המנוע"
    override val engineFailed = "המנוע לא התחלף"
    override val engineOffPhone = "את המנוע מכבים במחשב. מהטלפון אי אפשר יהיה להדליק אותו שוב."
    override val working = { n: Int -> "$n עובדים" }
    override val stopTitle = "לכבות את המנוע?"
    override val stopBody = "הבוטים לא יענו עד שהמנוע יפעל שוב."
    override val stop = "כיבוי"
    override val busyTitle = "בוטים באמצע עבודה"
    override val busyBody = "כיבוי עכשיו יקטע אותם."
    override val stopAnyway = "לכבות בכל זאת"
    override val linkActive = "פעיל"
    override val linkConnecting = "מתחבר…"
    override val linkOffline = "מנותק"
    override val copyLink = "העתקת הקישור"
    override val copied = "הקישור הועתק"
    override val allGood = "הכול תקין"
    override val notAllGood = "צריך בדיקה"
    override val on = { n: Int -> "$n פעילים" }
}

private val EnglishMoreStrings = MoreStrings()

/** `S.more.x` in a composable. */
val S.more: MoreStrings @Composable get() = if (current.rtl) HebrewMoreStrings else EnglishMoreStrings
