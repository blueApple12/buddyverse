package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by More ▸ Connectors (ui/more/ConnectorsPage.kt, ui/more/connectors). Same pattern as [Strings]:
 * English base, Hebrew overrides, nothing hard-coded in screens. Shared words (Cancel, Save, Rename, Try again…) stay in [Strings].
 */
open class ConnectorsPageStrings {
    open val heroTitle = { bot: String -> "Apps $bot can use" }
    open val connectedCount = { n: Int -> if (n == 1) "1 app connected" else "$n apps connected" }
    open val noneConnected = "Nothing connected yet"
    open val connectedSection = "Connected"
    open val appsSection = "Apps"
    open val searchApps = "Search apps"
    open val noMatches = "No apps match"
    open val connect = "Connect"
    open val addAccount = "Add another account"
    open val reconnect = "Reconnect"
    open val waiting = "Waiting for sign-in…"
    open val openSignIn = "Open sign-in page"
    open val stopWaiting = "Stop waiting"
    open val statusConnected = "Connected"
    open val statusPending = "Waiting for sign-in"
    open val statusExpired = "Signed out"
    open val statusFailed = "Not working"
    open val statusOff = "Off"
    open val onFor = { bot: String -> "On for $bot" }
    open val lockedByOrg = "Locked by your organization"
    open val accounts = "Accounts"
    open val accountOptions = "Account options"
    open val inUse = "in use"
    open val tools = "Tools"
    open val toolsOn = { on: Int, all: Int -> "$on of $all on" }
    open val noTools = "No tools listed"
    open val refreshTools = "Refresh tools"
    open val facetRead = "reads"
    open val facetWrite = "writes"
    open val facetDestructive = "deletes"
    open val accountName = "Account name (a-z, 0-9, -)"
    open val nameBad = "Use a-z, 0-9 and -"
    open val nameTaken = "That name is taken"
    open val remove = "Remove"
    open val removeAccount = { name: String -> "Remove $name?" }
    open val removed = "Account removed"
    open val connectedToast = { app: String -> "$app connected" }
    open val didntConnect = { app: String -> "$app didn't connect" }
    open val unavailable = "Connected apps are off on your PC"
    open val needsSignIn = "Sign in on your PC first"
    open val notAllowed = "Your account can't do that"
    open val offline = "Can't reach your PC"
    open val rulesReadOnly = "These switches can't be changed here"
}

private object HebrewConnectorsPageStrings : ConnectorsPageStrings() {
    override val heroTitle = { bot: String -> "אפליקציות ש־$bot יכול להשתמש בהן" }
    override val connectedCount = { n: Int -> if (n == 1) "אפליקציה אחת מחוברת" else "$n אפליקציות מחוברות" }
    override val noneConnected = "עוד לא חיברת כלום"
    override val connectedSection = "מחוברות"
    override val appsSection = "אפליקציות"
    override val searchApps = "חיפוש אפליקציות"
    override val noMatches = "אין אפליקציות מתאימות"
    override val connect = "חיבור"
    override val addAccount = "הוספת חשבון נוסף"
    override val reconnect = "חיבור מחדש"
    override val waiting = "מחכה להתחברות…"
    override val openSignIn = "פתיחת דף ההתחברות"
    override val stopWaiting = "הפסקת ההמתנה"
    override val statusConnected = "מחובר"
    override val statusPending = "מחכה להתחברות"
    override val statusExpired = "התנתק"
    override val statusFailed = "לא עובד"
    override val statusOff = "כבוי"
    override val onFor = { bot: String -> "פעיל אצל $bot" }
    override val lockedByOrg = "נעול על ידי הארגון"
    override val accounts = "חשבונות"
    override val accountOptions = "אפשרויות חשבון"
    override val inUse = "בשימוש"
    override val tools = "כלים"
    override val toolsOn = { on: Int, all: Int -> "$on מתוך $all פעילים" }
    override val noTools = "אין כלים ברשימה"
    override val refreshTools = "רענון הכלים"
    override val facetRead = "קורא"
    override val facetWrite = "כותב"
    override val facetDestructive = "מוחק"
    override val accountName = "שם החשבון (a-z, 0-9, -)"
    override val nameBad = "אפשר רק a-z, 0-9 ו־-"
    override val nameTaken = "השם הזה תפוס"
    override val remove = "הסרה"
    override val removeAccount = { name: String -> "להסיר את $name?" }
    override val removed = "החשבון הוסר"
    override val connectedToast = { app: String -> "$app מחובר" }
    override val didntConnect = { app: String -> "$app לא התחבר" }
    override val unavailable = "אפליקציות מחוברות כבויות במחשב"
    override val needsSignIn = "צריך קודם להתחבר במחשב"
    override val notAllowed = "לחשבון שלך אין הרשאה לזה"
    override val offline = "אין חיבור למחשב"
    override val rulesReadOnly = "אי אפשר לשנות את המתגים האלה מכאן"
}

private val EnglishConnectorsPageStrings = ConnectorsPageStrings()

/** `S.connectorsPage.x` in a composable. */
val S.connectorsPage: ConnectorsPageStrings @Composable get() = if (current.rtl) HebrewConnectorsPageStrings else EnglishConnectorsPageStrings
