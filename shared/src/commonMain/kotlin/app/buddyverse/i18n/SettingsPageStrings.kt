package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by More ▸ Settings (ui/more/SettingsPage.kt, ui/more/settings). Same pattern as [Strings]: English base,
 * Hebrew overrides. Shared words (Theme, Language, Sounds, Haptics, Motion, Paired PC, Sign out, Cancel…) stay in [Strings].
 */
open class SettingsPageStrings {
    // sections
    open val thisApp = "This app"
    open val yourPc = "Your PC"
    open val engine = "Engine settings"
    open val planCredits = "Plan & credits"
    // app and PC
    open val copyLink = "Copy link"
    open val linkCopied = "Link copied"
    open val signOutAsk = "Sign out of this PC? You'll need the link and password to come back."
    // engine
    open val searchSettings = "Search settings"
    open val noMatch = "No setting matches"
    open val fieldsCount = { n: Int -> if (n == 1) "1 setting" else "$n settings" }
    open val rawFile = "Settings file"
    open val rawSaved = "Settings file saved"
    open val saved = "Saved · applies to new chats"
    open val notSaved = { why: String -> "Not saved: $why" }
    open val resetDefault = "Reset to default"
    open val wasReset = "Back to default"
    open val copyKey = "Copy the key"
    open val keyCopied = "Key copied"
    open val none = "None"
    open val notANumber = "Numbers only"
    open val badJson = "That isn't valid JSON"
    open val onePerLine = "One per line"
    open val shareCounts = "Share anonymous usage counts"
    open val category = { id: String ->
        when (id) {
            "general" -> "General"
            "agent" -> "Agent"
            "terminal" -> "Terminal"
            "display" -> "Display"
            "delegation" -> "Helpers (subagents)"
            "memory" -> "Memory"
            "compression" -> "Compression"
            "security" -> "Safety and privacy"
            "browser" -> "Browser"
            "voice" -> "Voice"
            "tts" -> "Speech"
            "stt" -> "Transcription"
            "logging" -> "Logs"
            "discord" -> "Discord and Telegram"
            "auxiliary" -> "Side models"
            else -> id.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }
    // billing
    open val notSignedIn = "No paid plan is connected on this PC"
    open val freeTier = { model: String? -> listOfNotNull("Free plan", model).joinToString(" · ") }
    open val balance = "Balance"
    open val plan = "Plan"
    open val renews = { d: String -> "renews $d" }
    open val planBar = "Plan credits"
    open val topupBar = "Extra credits"
    open val left = { remaining: String, total: String -> "$remaining of $total left" }
    open val addCredits = "Add credits"
    open val buy = { amount: String -> "Add $amount" }
    open val charging = "Charging…"
    open val chargeDone = "Credits added"
    open val chargeFailed = { why: String -> "The charge failed: $why" }
    open val chargeUnknown = "The charge isn't confirmed yet. Check again in a minute before trying again."
    open val autoReload = "Top up automatically"
    open val threshold = "When below ($)"
    open val reloadTo = "Fill up to ($)"
    open val keepGoing = "Keep going on credits"
    open val payment = "Payment"
    open val monthlyCap = "Monthly limit"
    open val plans = "Plans"
    open val current = "current"
    open val upgrade = "Upgrade"
    open val switchTo = "Switch"
    open val perMonth = { price: String -> "$price / month" }
    open val previewing = "Checking what changes…"
    open val effect = { effect: String, target: String, whenText: String? ->
        when (effect) {
            "scheduled" -> "Switches to $target" + (whenText?.let { " on $it" } ?: " at the end of this period")
            "charge_now" -> "Switching to $target charges now: finish it in the browser"
            "no_op" -> "You're already on $target"
            else -> "Can't switch to $target now"
        }
    }
    open val confirmSwitch = "Switch plan"
    open val pendingSwitch = { tier: String, whenText: String? -> "Switching to $tier" + (whenText?.let { " · $it" } ?: "") }
    open val pendingCancel = { whenText: String? -> "Plan ends" + (whenText?.let { " · $it" } ?: "") }
    open val keepPlan = "Keep my plan"
    open val portal = "Manage in the browser"
    open val stepUp = "Approve billing changes"
    open val stepUpCode = { code: String -> "Enter this code in the browser: $code" }
    open val stepUpWaiting = "Waiting for you to approve in the browser…"
    open val stepUpDone = "Approved. Try it again."
    open val openLink = "Open the link"
    // listening with the app closed (phone)
    open val listen = "Keep listening when closed"
    open val listenHint = "A quiet notification keeps alerts coming after you swipe the app away"
    open val listening = "Buddyverse is listening"
    open val listenChannel = "Listening"
    open val guideTitle = "Make sure alerts get through"
    open val guideHyper = "HyperOS stops apps in the background. Turn on Autostart and set Battery saver to No restrictions."
    open val guideBattery = "Battery: no limits"
    open val guideAutostart = "Autostart: on"
    open val guideSaver = "Battery saver: No restrictions"
    open val guideDone = "All set"
    // change password (Webapp login; PC app only)
    open val changePassword = "Change password"
    open val pwCurrent = "Current password"
    open val pwNew = "New password"
    open val pwRepeat = "New password again"
    open val pwStrength = { n: Int -> listOf("Too short (at least 10 characters)", "Weak", "OK", "Good", "Strong")[n.coerceIn(0, 4)] }
    open val pwLookAlikes = { chars: String -> "It has look-alike characters ($chars): O and 0, l, 1 and I are easy to mix up when you type it on the phone." }
    open val pwSpaces = "No spaces at the start or the end"
    open val pwMismatch = "The two new passwords don't match"
    open val pwSame = "That's the current password"
    open val pwApplyNote = "Apply now: the Hermes gateway restarts, so the phone and running chats disconnect for about 30 seconds, then reconnect by themselves. If the phone asks, sign in with the new password."
    open val pwApply = "Change and restart"
    open val pwWorking = "Changing… the gateway is restarting"
    open val pwDone = "Password changed · other devices sign in with the new one"
    open val pwWrongCurrent = "The current password is wrong"
    open val pwFailed = { why: String -> "Nothing changed: your old password still works. Try again. ($why)" }
    open val pwOnPc = "Change the password from the PC app (Settings ▸ Change password there): it restarts the gateway, and only the PC can do that."
    // temp chat model
    open val tempModel = "Temp chat model"
    open val aiModel = "AI model"
    open val connectModel = "Connect a model"
    open val connectModelSub = "API keys, sign-ins, default model"
    open val tempModelNote = "New temp chats start on it. The model button in a chat still switches that chat."
    open val tempAuto = "Automatic"
    open val tempAutoNote = "Sonnet on your Claude subscription when it's signed in, otherwise your default model"
    open val tempAutoWith = { m: String -> "Automatic · $m" }
}

/** The words outside a composable (the phone's listening notification). */
fun settingsPageStrings(hebrew: Boolean): SettingsPageStrings = if (hebrew) HebrewSettingsPageStrings else EnglishSettingsPageStrings

private object HebrewSettingsPageStrings : SettingsPageStrings() {
    override val thisApp = "האפליקציה"
    override val yourPc = "המחשב שלך"
    override val engine = "הגדרות המנוע"
    override val planCredits = "תוכנית וקרדיטים"
    override val copyLink = "העתקת הקישור"
    override val linkCopied = "הקישור הועתק"
    override val signOutAsk = "להתנתק מהמחשב הזה? כדי לחזור יהיה צורך בקישור ובסיסמה."
    override val searchSettings = "חיפוש בהגדרות"
    override val noMatch = "אין הגדרה כזו"
    override val fieldsCount = { n: Int -> if (n == 1) "הגדרה אחת" else "$n הגדרות" }
    override val rawFile = "קובץ ההגדרות"
    override val rawSaved = "קובץ ההגדרות נשמר"
    override val saved = "נשמר · חל על שיחות חדשות"
    override val notSaved = { why: String -> "לא נשמר: $why" }
    override val resetDefault = "חזרה לברירת המחדל"
    override val wasReset = "חזר לברירת המחדל"
    override val copyKey = "העתקת המפתח"
    override val keyCopied = "המפתח הועתק"
    override val none = "ללא"
    override val notANumber = "מספרים בלבד"
    override val badJson = "זה לא JSON תקין"
    override val onePerLine = "אחד בכל שורה"
    override val shareCounts = "שיתוף ספירות שימוש אנונימיות"
    override val category = { id: String ->
        when (id) {
            "general" -> "כללי"
            "agent" -> "סוכן"
            "terminal" -> "טרמינל"
            "display" -> "תצוגה"
            "delegation" -> "עוזרים (תת־סוכנים)"
            "memory" -> "זיכרון"
            "compression" -> "דחיסה"
            "security" -> "בטיחות ופרטיות"
            "browser" -> "דפדפן"
            "voice" -> "קול"
            "tts" -> "דיבור"
            "stt" -> "תמלול"
            "logging" -> "יומנים"
            "discord" -> "דיסקורד וטלגרם"
            "auxiliary" -> "מודלים משניים"
            else -> id.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }
    override val notSignedIn = "לא מחוברת תוכנית בתשלום במחשב הזה"
    override val freeTier = { model: String? -> listOfNotNull("תוכנית חינמית", model).joinToString(" · ") }
    override val balance = "יתרה"
    override val plan = "תוכנית"
    override val renews = { d: String -> "מתחדשת ב־$d" }
    override val planBar = "קרדיטים של התוכנית"
    override val topupBar = "קרדיטים נוספים"
    override val left = { remaining: String, total: String -> "נשארו $remaining מתוך $total" }
    override val addCredits = "הוספת קרדיטים"
    override val buy = { amount: String -> "הוספת $amount" }
    override val charging = "מחייב…"
    override val chargeDone = "הקרדיטים נוספו"
    override val chargeFailed = { why: String -> "החיוב נכשל: $why" }
    override val chargeUnknown = "החיוב עוד לא אושר. כדאי לבדוק שוב בעוד דקה לפני שמנסים שוב."
    override val autoReload = "טעינה אוטומטית"
    override val threshold = "כשיורד מתחת ל־($)"
    override val reloadTo = "למלא עד ($)"
    override val keepGoing = "להמשיך על חשבון הקרדיטים"
    override val payment = "תשלום"
    override val monthlyCap = "מגבלה חודשית"
    override val plans = "תוכניות"
    override val current = "נוכחית"
    override val upgrade = "שדרוג"
    override val switchTo = "מעבר"
    override val perMonth = { price: String -> "$price לחודש" }
    override val previewing = "בודק מה משתנה…"
    override val effect = { effect: String, target: String, whenText: String? ->
        when (effect) {
            "scheduled" -> "יעבור ל־$target" + (whenText?.let { " ב־$it" } ?: " בסוף התקופה")
            "charge_now" -> "מעבר ל־$target מחייב עכשיו: אפשר לסיים בדפדפן"
            "no_op" -> "כבר נמצאים ב־$target"
            else -> "אי אפשר לעבור ל־$target עכשיו"
        }
    }
    override val confirmSwitch = "החלפת תוכנית"
    override val pendingSwitch = { tier: String, whenText: String? -> "עובר ל־$tier" + (whenText?.let { " · $it" } ?: "") }
    override val pendingCancel = { whenText: String? -> "התוכנית מסתיימת" + (whenText?.let { " · $it" } ?: "") }
    override val keepPlan = "להשאיר את התוכנית"
    override val portal = "ניהול בדפדפן"
    override val stepUp = "אישור שינויים בחיוב"
    override val stepUpCode = { code: String -> "להזין בדפדפן את הקוד: $code" }
    override val stepUpWaiting = "מחכה לאישור בדפדפן…"
    override val stepUpDone = "אושר. אפשר לנסות שוב."
    override val openLink = "פתיחת הקישור"
    override val listen = "להמשיך להאזין כשהאפליקציה סגורה"
    override val listenHint = "התראה שקטה שומרת שהתראות יגיעו גם אחרי שסוגרים את האפליקציה"
    override val listening = "Buddyverse מאזין"
    override val listenChannel = "האזנה"
    override val guideTitle = "לוודא שההתראות מגיעות"
    override val guideHyper = "‏HyperOS עוצרת אפליקציות ברקע. כדאי להפעיל „הפעלה אוטומטית” ולהגדיר את „חיסכון בסוללה” ל„ללא הגבלות”."
    override val guideBattery = "סוללה: ללא הגבלה"
    override val guideAutostart = "הפעלה אוטומטית: פועלת"
    override val guideSaver = "חיסכון בסוללה: ללא הגבלות"
    override val guideDone = "הכול מוכן"
    override val changePassword = "שינוי סיסמה"
    override val pwCurrent = "הסיסמה הנוכחית"
    override val pwNew = "סיסמה חדשה"
    override val pwRepeat = "הסיסמה החדשה שוב"
    override val pwStrength = { n: Int -> listOf("קצרה מדי (לפחות 10 תווים)", "חלשה", "סבירה", "טובה", "חזקה")[n.coerceIn(0, 4)] }
    override val pwLookAlikes = { chars: String -> "יש בה תווים שנראים דומים ($chars): קל להתבלבל בין O ל־0 ובין l, 1 ו־I כשמקלידים אותה בטלפון." }
    override val pwSpaces = "בלי רווחים בהתחלה או בסוף"
    override val pwMismatch = "שתי הסיסמאות החדשות לא זהות"
    override val pwSame = "זו הסיסמה הנוכחית"
    override val pwApplyNote = "החלה עכשיו: שער Hermes מופעל מחדש, אז הטלפון ושיחות פעילות מתנתקים לכ־30 שניות ואז מתחברים שוב לבד. אם הטלפון מבקש, מתחברים עם הסיסמה החדשה."
    override val pwApply = "לשנות ולהפעיל מחדש"
    override val pwWorking = "משנה… השער מופעל מחדש"
    override val pwDone = "הסיסמה שונתה · במכשירים אחרים מתחברים עם החדשה"
    override val pwWrongCurrent = "הסיסמה הנוכחית שגויה"
    override val pwFailed = { why: String -> "שום דבר לא השתנה: הסיסמה הישנה עדיין עובדת. נסו שוב. ($why)" }
    override val pwOnPc = "את הסיסמה משנים מאפליקציית המחשב (שם: הגדרות ▸ שינוי סיסמה): זה מפעיל מחדש את השער, ורק המחשב יכול לעשות את זה."
    override val tempModel = "מודל לצ׳אט זמני"
    override val aiModel = "מודל AI"
    override val connectModel = "חיבור מודל"
    override val connectModelSub = "מפתחות API, התחברויות, מודל ברירת מחדל"
    override val tempModelNote = "צ׳אטים זמניים חדשים מתחילים בו. כפתור המודל בצ׳אט עדיין מחליף את המודל של אותו צ׳אט."
    override val tempAuto = "אוטומטי"
    override val tempAutoNote = "Sonnet במנוי Claude שלך כשהוא מחובר, אחרת מודל ברירת המחדל שלך"
    override val tempAutoWith = { m: String -> "אוטומטי · $m" }
}

private val EnglishSettingsPageStrings = SettingsPageStrings()

/** `S.settingsPage.x` in a composable. */
val S.settingsPage: SettingsPageStrings @Composable get() = if (current.rtl) HebrewSettingsPageStrings else EnglishSettingsPageStrings
