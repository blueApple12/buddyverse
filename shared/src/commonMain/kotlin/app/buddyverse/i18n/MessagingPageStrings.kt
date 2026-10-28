package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by More ▸ Messaging (ui/more/MessagingPage.kt, ui/more/messaging). Same pattern as [Strings]:
 * English base, Hebrew overrides. Shared words (Allow, Save, Cancel, Copy, Try again, Advanced…) stay in [Strings].
 */
open class MessagingPageStrings {
    open val heroTitle = { bot: String -> "Where $bot chats" }
    open val connectedCount = { n: Int -> if (n == 1) "On 1 app" else "On $n apps" }
    open val noneConnected = "Not on any chat app yet"
    open val askingSection = "Asking to chat"
    open val connectedSection = "Connected"
    open val addSection = "Add a chat app"
    open val searchApps = "Search apps"
    open val noMatches = "No apps match"
    open val onFor = { bot: String -> "On for $bot" }
    open val clearRequests = "Clear all requests"
    open val askedOn = { platform: String, ago: String -> "$platform · $ago" }
    open val homeChat = { name: String -> "Home chat: $name" }
    // states
    open val stConnected = "Connected"
    open val stConnecting = "Connecting…"
    open val stFatal = "Not working"
    open val stDegraded = "Having trouble"
    open val stDraining = "Finishing up"
    open val stOff = "Off"
    open val stNotSetUp = "Not set up"
    open val stPendingRestart = "Restart to apply"
    open val stStartupFailed = "Didn't start"
    open val stEngineOff = "Engine is off"
    // restart
    open val restartTitle = "Restart to apply changes"
    open val restartBody = "Takes a few seconds."
    open val restartBusy = "Some bots are busy right now."
    open val restartNow = "Restart"
    open val restarting = "Restarting…"
    open val restartConfirmTitle = "Restart the engine?"
    open val restartConfirmBody = "Every bot pauses for a few seconds. Replies in progress stop."
    open val restarted = "Restarted"
    open val restartFailed = "The restart didn't finish"
    // platform sheet
    open val settings = "Settings"
    open val optional = "optional"
    open val savedValue = { preview: String? -> if (preview.isNullOrBlank()) "Saved" else "Saved · $preview" }
    open val show = "Show"
    open val hide = "Hide"
    open val aboutField = { field: String -> "About $field" }
    open val openPage = "Open page"
    open val openSite = { app: String -> "Open $app site" }
    open val removeSaved = "Remove saved value"
    open val saving = "Saving…"
    open val test = "Test"
    open val testing = "Testing…"
    open val testOk = "Works"
    open val testFailed = "Didn't work"
    open val postureOpen = "Anyone can message it"
    open val postureAllowlist = "Only people on the list can message it"
    open val posturePairing = "New people ask first"
    open val postureDenyAll = "Nobody can message it yet"
    open val people = "People who can message it"
    open val removePerson = { name: String -> "Remove $name" }
    open val remove = "Remove"
    open val incoming = "Address for incoming messages"
    open val copied = "Copied"
    open val saved = "Saved"
    open val approved = { name: String -> "$name can message now" }
    open val removedPerson = "Removed"
    open val requestsCleared = "Requests cleared"
    open val connectedToast = { app: String -> "$app connected" }
    // Telegram QR setup
    open val tgQuick = "Create a Telegram bot"
    open val tgNew = "Make a new Telegram bot"
    open val tgBotName = "Bot name"
    open val tgCreate = "Create"
    open val tgOpen = "Open Telegram"
    open val tgWaiting = "Waiting for you in Telegram…"
    open val scanElsewhere = "Or scan with another device"
    open val tgReady = { handle: String -> "$handle is ready" }
    open val tgMoreIds = "More people (Telegram user IDs, comma-separated)"
    open val finish = "Finish"
    open val finishing = "Finishing…"
    // WhatsApp QR setup
    open val waQuick = "Link with a QR code"
    open val waRelink = "Link again"
    open val waModeBot = "Its own number"
    open val waModeSelf = "My number"
    open val waAllowed = "Who may message it (phone numbers)"
    open val waShowQr = "Show QR code"
    open val waPreparing = "Getting WhatsApp ready…"
    open val waScan = "WhatsApp › Linked devices › Link a device"
    open val waLinked = { who: String -> "Linked: $who" }
    open val setupExpired = "The code expired"
    open val setupFailed = "Setup didn't finish"
    // problems
    open val offline = "Can't reach your PC"
    open val conflict = "That clashes with another bot's setting"
    open val tooMany = "Too many tries. Wait a minute."
    open val gone = "That's no longer there"
    open val invalid = "That value isn't right"
}

private object HebrewMessagingPageStrings : MessagingPageStrings() {
    override val heroTitle = { bot: String -> "איפה $bot מדבר" }
    override val connectedCount = { n: Int -> if (n == 1) "באפליקציה אחת" else "ב־$n אפליקציות" }
    override val noneConnected = "עוד לא באף אפליקציית צ׳אט"
    override val askingSection = "מבקשים לדבר"
    override val connectedSection = "מחוברות"
    override val addSection = "הוספת אפליקציית צ׳אט"
    override val searchApps = "חיפוש אפליקציות"
    override val noMatches = "אין אפליקציות מתאימות"
    override val onFor = { bot: String -> "פעיל אצל $bot" }
    override val clearRequests = "ניקוי כל הבקשות"
    override val askedOn = { platform: String, ago: String -> "$platform · $ago" }
    override val homeChat = { name: String -> "צ׳אט הבית: $name" }
    override val stConnected = "מחובר"
    override val stConnecting = "מתחבר…"
    override val stFatal = "לא עובד"
    override val stDegraded = "יש בעיות"
    override val stDraining = "מסיים"
    override val stOff = "כבוי"
    override val stNotSetUp = "לא מוגדר"
    override val stPendingRestart = "צריך הפעלה מחדש"
    override val stStartupFailed = "לא עלה"
    override val stEngineOff = "המנוע כבוי"
    override val restartTitle = "הפעלה מחדש כדי להחיל"
    override val restartBody = "לוקח כמה שניות."
    override val restartBusy = "כמה בוטים עסוקים עכשיו."
    override val restartNow = "הפעלה מחדש"
    override val restarting = "מופעל מחדש…"
    override val restartConfirmTitle = "להפעיל מחדש את המנוע?"
    override val restartConfirmBody = "כל הבוטים עוצרים לכמה שניות. תשובות שבאמצע ייעצרו."
    override val restarted = "הופעל מחדש"
    override val restartFailed = "ההפעלה מחדש לא הסתיימה"
    override val settings = "הגדרות"
    override val optional = "רשות"
    override val savedValue = { preview: String? -> if (preview.isNullOrBlank()) "שמור" else "שמור · $preview" }
    override val show = "הצגה"
    override val hide = "הסתרה"
    override val aboutField = { field: String -> "על $field" }
    override val openPage = "פתיחת הדף"
    override val openSite = { app: String -> "פתיחת האתר של $app" }
    override val removeSaved = "מחיקת הערך השמור"
    override val saving = "שומר…"
    override val test = "בדיקה"
    override val testing = "בודק…"
    override val testOk = "עובד"
    override val testFailed = "לא עבד"
    override val postureOpen = "כל אחד יכול לכתוב לו"
    override val postureAllowlist = "רק מי שברשימה יכול לכתוב לו"
    override val posturePairing = "אנשים חדשים מבקשים קודם"
    override val postureDenyAll = "עוד אף אחד לא יכול לכתוב לו"
    override val people = "מי יכול לכתוב לו"
    override val removePerson = { name: String -> "הסרת $name" }
    override val remove = "הסרה"
    override val incoming = "כתובת להודעות נכנסות"
    override val copied = "הועתק"
    override val saved = "נשמר"
    override val approved = { name: String -> "$name יכול לכתוב עכשיו" }
    override val removedPerson = "הוסר"
    override val requestsCleared = "הבקשות נוקו"
    override val connectedToast = { app: String -> "$app מחובר" }
    override val tgQuick = "יצירת בוט בטלגרם"
    override val tgNew = "יצירת בוט טלגרם חדש"
    override val tgBotName = "שם הבוט"
    override val tgCreate = "יצירה"
    override val tgOpen = "פתיחת טלגרם"
    override val tgWaiting = "מחכה לך בטלגרם…"
    override val scanElsewhere = "או לסרוק ממכשיר אחר"
    override val tgReady = { handle: String -> "$handle מוכן" }
    override val tgMoreIds = "אנשים נוספים (מזהי משתמש בטלגרם, מופרדים בפסיק)"
    override val finish = "סיום"
    override val finishing = "מסיים…"
    override val waQuick = "קישור עם קוד QR"
    override val waRelink = "קישור מחדש"
    override val waModeBot = "מספר משלו"
    override val waModeSelf = "המספר שלי"
    override val waAllowed = "מי יכול לכתוב לו (מספרי טלפון)"
    override val waShowQr = "הצגת קוד QR"
    override val waPreparing = "מכין את וואטסאפ…"
    override val waScan = "וואטסאפ › מכשירים מקושרים › קישור מכשיר"
    override val waLinked = { who: String -> "מקושר: $who" }
    override val setupExpired = "פג תוקף הקוד"
    override val setupFailed = "ההגדרה לא הסתיימה"
    override val offline = "אין חיבור למחשב"
    override val conflict = "זה מתנגש בהגדרה של בוט אחר"
    override val tooMany = "יותר מדי ניסיונות. כדאי לחכות דקה."
    override val gone = "זה כבר לא קיים"
    override val invalid = "הערך הזה לא נכון"
}

private val EnglishMessagingPageStrings = MessagingPageStrings()

/** `S.messagingPage.x` in a composable. */
val S.messagingPage: MessagingPageStrings @Composable get() = if (current.rtl) HebrewMessagingPageStrings else EnglishMessagingPageStrings
