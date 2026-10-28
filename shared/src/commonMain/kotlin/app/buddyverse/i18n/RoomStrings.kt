package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by Group room (ui/room). Same pattern as [Strings]: English base, Hebrew overrides, nothing hard-coded in screens.
 * Shared words (Back, Close, Cancel…) stay in [Strings] (read-only for areas); add this area's own words here.
 */
open class RoomStrings {
    open val messageTeam = "Message the team, @ to pick"
    open val add = "Add"
    open val you = "You"
    open val sayHi = "Say hi to the team"
    open val isWorking = { name: String -> "$name is working" }
    open val areWorking = { names: String -> "$names are working" }
    open val upNext = { name: String -> "$name is up next" }
    open val teamWorking = "The team is working"
    // create
    open val newRoom = "New group"
    open val roomName = "Name (optional)"
    open val picked = { n: Int -> "Pick 2 to 6 bots · $n picked" }
    open val upToSix = "Up to 6 bots in a group"
    open val cantJoin = "can't join groups"
    open val noSeatable = "Not enough bots can join a group yet"
    // header and options
    open val options = "Group options"
    open val renameRoom = "Rename group"
    open val disband = "Close the group"
    open val disbandAsk = "Close this group for good? Its conversation goes with it."
    open val disbanded = "Group closed"
    open val gone = "This group was closed"
    // pending actions
    open val putOff = { name: String -> "$name's turn was put off" }
    open val unknownResult = { name: String -> "$name's result is unknown" }
    open val discard = "Discard"
    open val discardAsk = "Discard the unknown result? The bot may have done it already."
    // notices
    open val failedNotice = { names: String -> "$names failed" }
    open val stoppedNotice = { names: String -> "$names stopped" }
    open val deferredNotice = { names: String -> "$names will go later" }
    open val awayNotice = { names: String -> "$names is away" }
    open val stopRequested = "You stopped the team"
    open val disbandedNotice = "The group was closed"
    open val authorityLost = "Your PC lost this group"
    open val bounded = "Work limit reached"
    // messages and files
    open val copied = "Copied"
    open val mention = "Mention"
    open val download = "Download"
    open val saved = "Saved to Downloads"
    open val tooBig = "Files can be up to 15 MB"
    open val tooMany = "Up to 8 files a message"
    open val saveFailed = "Couldn't save the file"
    // watching a member work
    open val watch = "Watch"
    open val watchWho = "Watch who?"
    open val nothingToWatch = "Nothing to watch right now"
    open val creating = "Making the group…"
}

private object HebrewRoomStrings : RoomStrings() {
    override val messageTeam = "הודעה לצוות, @ לבחירה"
    override val add = "הוספה"
    override val you = "את/ה"
    override val sayHi = "להגיד שלום לצוות"
    override val isWorking = { name: String -> "$name עובד" }
    override val areWorking = { names: String -> "$names עובדים" }
    override val upNext = { name: String -> "$name הבא בתור" }
    override val teamWorking = "הצוות עובד"
    override val newRoom = "קבוצה חדשה"
    override val roomName = "שם (לא חובה)"
    override val picked = { n: Int -> "לבחור 2 עד 6 בוטים · נבחרו $n" }
    override val upToSix = "עד 6 בוטים בקבוצה"
    override val cantJoin = "לא יכול להצטרף לקבוצות"
    override val noSeatable = "עדיין אין מספיק בוטים שיכולים להצטרף לקבוצה"
    override val options = "אפשרויות הקבוצה"
    override val renameRoom = "שינוי שם הקבוצה"
    override val disband = "סגירת הקבוצה"
    override val disbandAsk = "לסגור את הקבוצה לתמיד? השיחה תימחק איתה."
    override val disbanded = "הקבוצה נסגרה"
    override val gone = "הקבוצה הזו נסגרה"
    override val putOff = { name: String -> "התור של $name נדחה" }
    override val unknownResult = { name: String -> "התוצאה של $name לא ידועה" }
    override val discard = "לבטל"
    override val discardAsk = "לבטל את התוצאה הלא ידועה? ייתכן שהבוט כבר עשה את זה."
    override val failedNotice = { names: String -> "$names נכשל" }
    override val stoppedNotice = { names: String -> "$names עצר" }
    override val deferredNotice = { names: String -> "$names ימשיך אחר כך" }
    override val awayNotice = { names: String -> "$names לא זמין" }
    override val stopRequested = "עצרת את הצוות"
    override val disbandedNotice = "הקבוצה נסגרה"
    override val authorityLost = "המחשב איבד את הקבוצה הזו"
    override val bounded = "הגענו למגבלת העבודה"
    override val copied = "הועתק"
    override val mention = "אזכור"
    override val download = "הורדה"
    override val saved = "נשמר בהורדות"
    override val tooBig = "קבצים עד 15MB"
    override val tooMany = "עד 8 קבצים בהודעה"
    override val saveFailed = "לא הצלחתי לשמור את הקובץ"
    override val watch = "צפייה"
    override val watchWho = "לצפות במי?"
    override val nothingToWatch = "אין כרגע מה לראות"
    override val creating = "יוצר את הקבוצה…"
}

private val EnglishRoomStrings = RoomStrings()

/** `S.room.x` in a composable. */
val S.room: RoomStrings @Composable get() = if (current.rtl) HebrewRoomStrings else EnglishRoomStrings
