package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by More ▸ Webhooks (ui/more/WebhooksPage.kt, ui/more/webhooks). Same pattern as [Strings]: English base,
 * Hebrew overrides. Shared words (Back, Close, Cancel, Delete, Undo, Copy, Try again…) stay in [Strings].
 */
open class WebhooksPageStrings {
    open val newWebhook = "New webhook"
    open val noWebhooks = "No webhooks yet"
    open val countOf = { bot: String, n: Int -> if (n == 1) "$bot · 1 webhook" else "$bot · $n webhooks" }
    // platform switch and engine restart
    open val platformOff = "Webhooks are off"
    open val platformOffBody = "Turning them on restarts the engine."
    open val turnOn = "Turn on"
    open val turningOn = "Turning on…"
    open val restarting = "Engine restarting…"
    open val restartNeeded = "Restart the engine to finish turning webhooks on"
    open val restartBusy = "Live chats kept the engine from restarting"
    open val restartFailed = { why: String -> "The engine didn't restart: $why" }
    open val restartEngine = "Restart engine"
    open val restartWarn = "Live chats on every bot stop."
    open val restart = "Restart"
    open val restarted = "Engine is back"
    open val webhooksOn = "Webhooks are on"
    // rows and detail
    open val allEvents = "all events"
    open val off = "Off"
    open val on = "On"
    open val direct = "Direct"
    open val link = "Link"
    open val secret = "Secret"
    open val secretSet = "Set"
    open val secretNone = "None"
    open val events = "Events"
    open val skills = "Skills"
    open val deliverTo = "Send to"
    open val prompt = "Prompt"
    open val script = "Script"
    open val about = "About"
    open val created = "Created"
    open val linkCopied = "Link copied"
    open val secretCopied = "Secret copied"
    open val copyLink = "Copy link"
    open val turnOff = "Turn off"
    open val removed = { n: String -> "$n removed" }
    // deliver targets (`WebhookCreate.deliver`)
    open val deliverLabel = { d: String ->
        when (d) {
            "log" -> "Log only"
            "telegram" -> "Telegram"
            "discord" -> "Discord"
            "slack" -> "Slack"
            "email" -> "Email"
            "github_comment" -> "GitHub comment"
            else -> d
        }
    }
    // create form
    open val name = "Name (a-z, 0-9, -)"
    open val description = "What it's for"
    open val promptHint = "What the bot does with each event"
    open val eventsHint = "Events, e.g. push, pull_request (empty = all)"
    open val skillsHint = "Skills, comma separated"
    open val directSwitch = "Send straight, skip the bot"
    open val moreOptions = "More options"
    open val chatId = "Chat ID"
    open val scriptHint = "Script file"
    open val secretHint = "Secret (empty = make one)"
    open val create = "Create"
    open val creating = "Creating…"
    open val needName = "Give it a name"
    open val badName = "Use a-z, 0-9, - and _ only"
    open val nameTaken = "That name is taken"
    open val directNeedsTarget = "Pick where to send it first"
    // created
    open val createdTitle = { n: String -> "$n is ready" }
    open val secretOnce = "Copy the secret now. It's shown only once."
}

private object HebrewWebhooksPageStrings : WebhooksPageStrings() {
    override val newWebhook = "Webhook חדש"
    override val noWebhooks = "עוד אין Webhooks"
    override val countOf = { bot: String, n: Int -> if (n == 1) "$bot · Webhook אחד" else "$bot · $n Webhooks" }
    override val platformOff = "ה-Webhooks כבויים"
    override val platformOffBody = "ההפעלה מפעילה מחדש את המנוע."
    override val turnOn = "הפעלה"
    override val turningOn = "מפעיל…"
    override val restarting = "המנוע עולה מחדש…"
    override val restartNeeded = "צריך להפעיל מחדש את המנוע כדי לסיים"
    override val restartBusy = "צ'אטים פעילים מנעו את ההפעלה מחדש"
    override val restartFailed = { why: String -> "המנוע לא עלה מחדש: $why" }
    override val restartEngine = "הפעלה מחדש של המנוע"
    override val restartWarn = "צ'אטים פעילים בכל הבוטים ייעצרו."
    override val restart = "הפעלה מחדש"
    override val restarted = "המנוע חזר"
    override val webhooksOn = "ה-Webhooks פועלים"
    override val allEvents = "כל האירועים"
    override val off = "כבוי"
    override val on = "פועל"
    override val direct = "ישיר"
    override val link = "קישור"
    override val secret = "סוד"
    override val secretSet = "מוגדר"
    override val secretNone = "אין"
    override val events = "אירועים"
    override val skills = "מיומנויות"
    override val deliverTo = "שליחה אל"
    override val prompt = "הנחיה"
    override val script = "סקריפט"
    override val about = "תיאור"
    override val created = "נוצר"
    override val linkCopied = "הקישור הועתק"
    override val secretCopied = "הסוד הועתק"
    override val copyLink = "העתקת קישור"
    override val turnOff = "כיבוי"
    override val removed = { n: String -> "$n הוסר" }
    override val deliverLabel = { d: String ->
        when (d) {
            "log" -> "רק ללוג"
            "telegram" -> "Telegram"
            "discord" -> "Discord"
            "slack" -> "Slack"
            "email" -> "אימייל"
            "github_comment" -> "תגובה ב-GitHub"
            else -> d
        }
    }
    override val name = "שם (a-z, 0-9, -)"
    override val description = "בשביל מה זה"
    override val promptHint = "מה הבוט עושה עם כל אירוע"
    override val eventsHint = "אירועים, למשל push, pull_request (ריק = הכול)"
    override val skillsHint = "מיומנויות, מופרדות בפסיק"
    override val directSwitch = "שליחה ישירה, בלי הבוט"
    override val moreOptions = "אפשרויות נוספות"
    override val chatId = "מזהה צ'אט"
    override val scriptHint = "קובץ סקריפט"
    override val secretHint = "סוד (ריק = ייווצר אחד)"
    override val create = "יצירה"
    override val creating = "יוצר…"
    override val needName = "צריך לתת שם"
    override val badName = "רק a-z, 0-9, - ו-_"
    override val nameTaken = "השם הזה תפוס"
    override val directNeedsTarget = "קודם לבחור לאן לשלוח"
    override val createdTitle = { n: String -> "$n מוכן" }
    override val secretOnce = "כדאי להעתיק את הסוד עכשיו. הוא מוצג רק פעם אחת."
}

private val EnglishWebhooksPageStrings = WebhooksPageStrings()

/** `S.webhooksPage.x` in a composable. */
val S.webhooksPage: WebhooksPageStrings @Composable get() = if (current.rtl) HebrewWebhooksPageStrings else EnglishWebhooksPageStrings
