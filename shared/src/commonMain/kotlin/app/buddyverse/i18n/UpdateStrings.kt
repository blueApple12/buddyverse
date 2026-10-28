package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/** Strings of the phone's self-update (ui/UpdateBanner.kt). Same pattern as [HomeStrings]. */
open class UpdateStrings {
    open val ready = { v: String -> "Update ready · v$v" }
    open val tapToInstall = "Tap to install"
    open val downloading = { pct: Int -> "Downloading $pct%" }
    open val later = "Later"
    open val badFile = "The update didn't download right. Try again."
    open val failed = "Couldn't download the update"
    open val allowTitle = "Allow updates"
    open val allowBody = "Once only: let Buddyverse install its own updates."
    open val allowSteps = listOf(
        "Tap Open settings.",
        "Turn on “Allow from this source”.",
        "Come back here: the update installs.",
    )
    open val openSettings = "Open settings"
}

private object HebrewUpdateStrings : UpdateStrings() {
    override val ready = { v: String -> "עדכון מוכן · ⁨v$v⁩" }
    override val tapToInstall = "הקשה להתקנה"
    override val downloading = { pct: Int -> "מוריד $pct%" }
    override val later = "אחר כך"
    override val badFile = "העדכון לא ירד כמו שצריך. נסו שוב."
    override val failed = "לא הצלחתי להוריד את העדכון"
    override val allowTitle = "אישור עדכונים"
    override val allowBody = "פעם אחת בלבד: לאפשר ל־Buddyverse להתקין את העדכונים שלו."
    override val allowSteps = listOf(
        "הקישו על פתיחת ההגדרות.",
        "הפעילו את „התרה מהמקור הזה”.",
        "חזרו לכאן: העדכון יותקן.",
    )
    override val openSettings = "פתיחת ההגדרות"
}

private val EnglishUpdateStrings = UpdateStrings()

/** `S.update.x` in a composable. */
val S.update: UpdateStrings @Composable get() = if (current.rtl) HebrewUpdateStrings else EnglishUpdateStrings
