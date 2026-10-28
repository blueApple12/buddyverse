package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by More ▸ MCP servers (ui/more/McpPage.kt, ui/more/mcp). Same pattern as [Strings]: English base, Hebrew overrides.
 * Shared words (Back, Close, Cancel, Save, Edit, Delete, Undo…) stay in [Strings].
 */
open class McpPageStrings {
    open val addServer = "Add server"
    open val noServers = "No MCP servers yet"
    open val catalog = "Catalog"
    open val custom = "Your own"
    open val editServer = "Edit server"
    open val name = "Name"
    open val kindLink = "Link"
    open val kindCommand = "Command"
    open val link = "https://…/mcp"
    open val command = "Command, e.g. npx"
    open val args = "Arguments, one per line"
    open val env = "KEY=value, one per line"
    open val authNone = "Open"
    open val authKey = "API key"
    open val authOAuth = "Sign in"
    open val apiKey = "API key"
    open val keepKey = "API key (empty keeps the current one)"
    open val saveKey = "Save key"
    open val add = "Add"
    open val adding = "Adding…"
    open val test = "Test"
    open val testing = "Testing…"
    open val signIn = "Sign in"
    open val finishInBrowser = "Finish signing in, in your browser"
    open val signedIn = { n: String -> "$n is signed in" }
    open val tools = { n: Int -> if (n == 1) "1 tool" else "$n tools" }
    open val toolsHeader = "Tools"
    open val keys = "Keys"
    open val connected = "connected"
    open val failed = "failed"
    open val connecting = "connecting"
    open val idle = "starts when needed"
    open val off = "off"
    open val needsSignIn = "needs sign-in"
    open val fromPlugin = { p: String -> "From plugin $p" }
    open val added = { n: String -> "$n added" }
    open val removed = { n: String -> "$n removed" }
    open val saved = "Saved"
    open val works = { n: Int -> if (n == 1) "Works · 1 tool" else "Works · $n tools" }
    open val couldntStart = { names: String -> "Couldn't start $names" }
    open val needName = "Give it a name"
    open val needTarget = "Add a link or a command"
    open val needValue = "Fill in every key"
    open val noMatches = "Nothing found"
    open val serversAll = { n: Int -> if (n == 1) "All bots · 1 server" else "All bots · $n servers" }
    open val notOnBots = { names: String -> "Didn't apply to $names" }
}

private object HebrewMcpPageStrings : McpPageStrings() {
    override val addServer = "הוספת שרת"
    override val noServers = "עוד אין שרתי MCP"
    override val catalog = "קטלוג"
    override val custom = "משלך"
    override val editServer = "עריכת שרת"
    override val name = "שם"
    override val kindLink = "קישור"
    override val kindCommand = "פקודה"
    override val command = "פקודה, למשל npx"
    override val args = "ארגומנטים, אחד בכל שורה"
    override val env = "KEY=value, אחד בכל שורה"
    override val authNone = "פתוח"
    override val authKey = "מפתח API"
    override val authOAuth = "התחברות"
    override val apiKey = "מפתח API"
    override val keepKey = "מפתח API (ריק משאיר את הנוכחי)"
    override val saveKey = "שמירת מפתח"
    override val add = "הוספה"
    override val adding = "מוסיף…"
    override val test = "בדיקה"
    override val testing = "בודק…"
    override val signIn = "התחברות"
    override val finishInBrowser = "סיימו את ההתחברות בדפדפן"
    override val signedIn = { n: String -> "$n מחובר" }
    override val tools = { n: Int -> if (n == 1) "כלי אחד" else "$n כלים" }
    override val toolsHeader = "כלים"
    override val keys = "מפתחות"
    override val connected = "מחובר"
    override val failed = "נכשל"
    override val connecting = "מתחבר"
    override val idle = "יופעל כשיהיה צורך"
    override val off = "כבוי"
    override val needsSignIn = "צריך להתחבר"
    override val fromPlugin = { p: String -> "מגיע מהתוסף $p" }
    override val added = { n: String -> "$n נוסף" }
    override val removed = { n: String -> "$n הוסר" }
    override val saved = "נשמר"
    override val works = { n: Int -> if (n == 1) "עובד · כלי אחד" else "עובד · $n כלים" }
    override val couldntStart = { names: String -> "לא הצליח להפעיל את $names" }
    override val needName = "צריך לתת שם"
    override val needTarget = "צריך קישור או פקודה"
    override val needValue = "צריך למלא את כל המפתחות"
    override val noMatches = "לא נמצא כלום"
    override val serversAll = { n: Int -> if (n == 1) "כל הבוטים · שרת אחד" else "כל הבוטים · $n שרתים" }
    override val notOnBots = { names: String -> "לא הוחל על $names" }
}

private val EnglishMcpPageStrings = McpPageStrings()

/** `S.mcpPage.x` in a composable. */
val S.mcpPage: McpPageStrings @Composable get() = if (current.rtl) HebrewMcpPageStrings else EnglishMcpPageStrings
