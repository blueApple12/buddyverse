package app.buddyverse.i18n

import androidx.compose.runtime.Composable
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot

/**
 * Strings owned by More ▸ Buddy gallery (ui/more/GalleryPage.kt, ui/more/gallery). Same pattern as [Strings]:
 * English base, Hebrew overrides, nothing hard-coded in screens.
 */
open class GalleryPageStrings {
    open val moods = "Moods"
    open val reactions = "Reactions"
    open val colors = "Colors"
    open val groups = "Groups"
    open val size = "Size"
    open val everyone = "Everyone"
    open val talk = "Talk"
    open val aliveCheck = "Alive check"
    open val resetCheck = "Start the check again"
    open val onScreen = { n: Int -> "$n buddies running" }
    open val longestStill = { s: String -> "longest still $s" }
    open val frames = { ms: String -> "frames p95 $ms" }
    open val allMoving = "All moving"
    open val someoneFroze = "One stood still over 4 s"
    open val sizeSmall = "S"
    open val sizeMedium = "M"
    open val sizeLarge = "L"

    open fun mood(m: Mood): String = when (m) {
        Mood.Offline -> "Offline"
        Mood.Sleeping -> "Sleeping"
        Mood.Idle -> "Idle"
        Mood.Listening -> "Listening"
        Mood.Thinking -> "Thinking"
        Mood.Working -> "Working"
        Mood.Talking -> "Talking"
        Mood.Error -> "Error"
        Mood.NeedsYou -> "Needs you"
    }

    open fun shot(o: OneShot): String = when (o) {
        OneShot.Tap -> "Tap"
        OneShot.Giggle -> "Giggle"
        OneShot.Dizzy -> "Dizzy"
        OneShot.Arrive -> "Arrive"
        OneShot.Leave -> "Leave"
        OneShot.Done -> "Done"
        OneShot.Nod -> "Nod"
        OneShot.Greeting -> "Hello"
        OneShot.Wave -> "Wave"
        OneShot.Spawn -> "Spawn"
        OneShot.Return -> "Return"
        OneShot.Startle -> "Startle"
        OneShot.Learned -> "Learned"
        OneShot.Hop -> "Hop"
        OneShot.LookAround -> "Look around"
        OneShot.Wiggle -> "Wiggle"
        OneShot.Stretch -> "Stretch"
        OneShot.Yawn -> "Yawn"
        OneShot.Pet -> "Pet"
        OneShot.Spin -> "Spin"
        OneShot.Dance -> "Dance"
        OneShot.Laugh -> "Laugh"
        OneShot.Lift -> "Carried"
        OneShot.Boing -> "Boing"
    }

    open fun shape(s: BuddyShape): String = when (s) {
        BuddyShape.Blob -> "Blob"
        BuddyShape.Pebble -> "Pebble"
        BuddyShape.Squircle -> "Squircle"
        BuddyShape.Tablet -> "Tablet"
        BuddyShape.Wedge -> "Wedge"
        BuddyShape.Hex -> "Hex"
        BuddyShape.Cloud -> "Cloud"
        BuddyShape.Teardrop -> "Teardrop"
    }

    /** Same order as `BuddyColors.palette`. */
    open val colorNames = listOf("Black", "Brown", "Red", "Orange", "Yellow", "Green", "Cyan", "Blue", "Violet", "Magenta", "Gray", "Cream")
}

private object HebrewGalleryPageStrings : GalleryPageStrings() {
    override val moods = "מצבי רוח"
    override val reactions = "תגובות"
    override val colors = "צבעים"
    override val groups = "קבוצות"
    override val size = "גודל"
    override val everyone = "כולם"
    override val talk = "דיבור"
    override val aliveCheck = "בדיקת חיות"
    override val resetCheck = "להתחיל את הבדיקה מחדש"
    override val onScreen = { n: Int -> "$n דמויות פועלות" }
    override val longestStill = { s: String -> "הכי הרבה בלי תזוזה $s" }
    override val frames = { ms: String -> "פריימים p95 $ms" }
    override val allMoving = "כולם זזים"
    override val someoneFroze = "מישהו עמד בלי תזוזה יותר מ־4 שניות"

    override fun mood(m: Mood): String = when (m) {
        Mood.Offline -> "מנותק"
        Mood.Sleeping -> "ישן"
        Mood.Idle -> "רגוע"
        Mood.Listening -> "מקשיב"
        Mood.Thinking -> "חושב"
        Mood.Working -> "עובד"
        Mood.Talking -> "מדבר"
        Mood.Error -> "שגיאה"
        Mood.NeedsYou -> "צריך אותך"
    }

    override fun shot(o: OneShot): String = when (o) {
        OneShot.Tap -> "נגיעה"
        OneShot.Giggle -> "צחקוק"
        OneShot.Dizzy -> "סחרחורת"
        OneShot.Arrive -> "הגעה"
        OneShot.Leave -> "יציאה"
        OneShot.Done -> "סיום"
        OneShot.Nod -> "הנהון"
        OneShot.Greeting -> "שלום"
        OneShot.Wave -> "נפנוף"
        OneShot.Spawn -> "עוזר יוצא"
        OneShot.Return -> "עוזר חוזר"
        OneShot.Startle -> "בהלה"
        OneShot.Learned -> "למד"
        OneShot.Hop -> "קפיצה"
        OneShot.LookAround -> "הסתכלות"
        OneShot.Wiggle -> "נענוע"
        OneShot.Stretch -> "מתיחה"
        OneShot.Yawn -> "פיהוק"
        OneShot.Pet -> "ליטוף"
        OneShot.Spin -> "סיבוב"
        OneShot.Dance -> "ריקוד"
        OneShot.Laugh -> "צחוק"
        OneShot.Lift -> "בידיים"
        OneShot.Boing -> "בוינג"
    }

    override fun shape(s: BuddyShape): String = when (s) {
        BuddyShape.Blob -> "כתם"
        BuddyShape.Pebble -> "חלוק"
        BuddyShape.Squircle -> "ריבוע מעוגל"
        BuddyShape.Tablet -> "טבלית"
        BuddyShape.Wedge -> "טריז"
        BuddyShape.Hex -> "משושה"
        BuddyShape.Cloud -> "ענן"
        BuddyShape.Teardrop -> "טיפה"
    }

    override val colorNames = listOf("שחור", "חום", "אדום", "כתום", "צהוב", "ירוק", "טורקיז", "כחול", "סגול", "מג׳נטה", "אפור", "שמנת")
}

private val EnglishGalleryPageStrings = GalleryPageStrings()

/** `S.gallery.x` in a composable. */
val S.gallery: GalleryPageStrings @Composable get() = if (current.rtl) HebrewGalleryPageStrings else EnglishGalleryPageStrings
