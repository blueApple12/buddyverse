package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by More ▸ Plugins (ui/more/PluginsPage.kt, ui/more/plugins). Same pattern as [Strings]: English base, Hebrew overrides.
 * Shared words (Back, Close, Cancel, Save, Undo, Try again, Loading…) stay in [Strings].
 */
open class PluginsPageStrings {
    open val installed = "Installed"
    open val browse = "Browse"
    open val noPlugins = "No plugins yet"
    open val browsePlugins = "Browse plugins"
    open val searchHint = "Search plugins"
    open val suggested = "Suggested for this PC"
    open val all = "All"
    open val noMatches = "Nothing found"
    open val catalogFailed = "Couldn't load the catalog"
    open val countLine = { bot: String, on: Int, total: Int -> "$bot · $on of $total on" }
    // provenance
    open val official = "Official"
    open val verified = "Verified"
    open val community = "Community"
    open val builtIn = "Built in"
    open val fromCatalog = "From the catalog"
    open val fromLink = "From a link"
    open val by = { who: String -> "by $who" }
    // states and actions
    open val on = "On"
    open val turnOn = "Turn on"
    open val turnOff = "Turn off"
    open val details = "Details"
    open val install = "Install"
    open val installing = "Installing…"
    open val update = "Update"
    open val updating = "Updating…"
    open val updateTo = { v: String -> "Update to $v" }
    open val updateReady = "Update ready"
    open val remove = "Remove"
    open val source = "Source"
    open val fixedOn = "Always on"
    // toasts
    open val installedToast = { n: String -> "$n installed" }
    open val stillInstalling = { n: String -> "$n is still installing on your PC" }
    open val updated = { n: String -> "$n updated" }
    open val upToDate = { n: String -> "$n is up to date" }
    open val removed = { n: String -> "$n removed" }
    open val saved = "Saved"
    open val restartNeeded = "Takes effect after the engine restarts"
    open val nextChat = "Works from the next chat"
    open val memoryCleared = "Memory went back to the built-in one"
    open val couldntStart = { names: String -> "Couldn't start $names" }
    // detail sections
    open val tools = "Tools"
    open val apps = "Apps"
    open val needsKeys = "Needs keys"
    open val runsOn = "Runs on"
    open val settings = "Settings"
    open val optional = "optional"
    open val secretSet = "Saved · type to replace"
    open val notes = "Notes"
    // validation
    open val needNumber = { label: String -> "$label: needs a number" }
    open val needChoice = { label: String -> "$label: pick one" }
    open val needJson = { label: String -> "$label: needs a JSON list or object" }
    open val needLink = "Paste a link first"
    // install from a link
    open val linkTitle = "Install from a link"
    open val linkHint = "Name, git link or owner/repo"
    open val unknownName = "No plugin by that name in the catalog"
    open val catalogUnavailable = "The catalog can't be reached right now"
    // after install: keys
    open val keysTitle = { n: String -> "$n needs keys" }
    open val keyValue = "Value"
    open val saveKeys = "Save keys"
    open val later = "Later"
    // update questions
    open val consentTitle = { n: String -> "$n wants more access" }
    open val consentBody = "The new version adds:"
    open val scanTitle = { n: String -> "Safety check flagged $n" }
    open val scanCaution = "The new code has risky parts. Update only if you trust it."
    open val scanBlocked = "The new code looks unsafe, so it was not installed."
    open val updateAnyway = "Update anyway"
    open val andMore = { n: Int -> "and $n more" }
    // app-backed servers
    open val serverState = { state: String ->
        when (state) {
            "connected" -> "connected"
            "app_not_running" -> "app not running"
            "hermes_not_connected" -> "not connected yet"
            "endpoint_unavailable" -> "can't be reached"
            "no_interactive_session" -> "needs someone signed in on the PC"
            "version_too_old" -> "app is too old"
            "missing_app" -> "app not installed"
            "unsupported_gpu" -> "graphics card not supported"
            else -> "unknown"
        }
    }
    open val category = { id: String ->
        when (id) {
            "memory" -> "Memory"
            "tools" -> "Tools"
            "web" -> "Web"
            "models" -> "Models"
            "voice" -> "Voice"
            "automation" -> "Automation"
            "platform" -> "Messaging"
            "general" -> "General"
            else -> id.replaceFirstChar { it.uppercase() }
        }
    }
}

private object HebrewPluginsPageStrings : PluginsPageStrings() {
    override val installed = "מותקנים"
    override val browse = "קטלוג"
    override val noPlugins = "עוד אין תוספים"
    override val browsePlugins = "לקטלוג התוספים"
    override val searchHint = "חיפוש תוספים"
    override val suggested = "מומלצים למחשב הזה"
    override val all = "הכול"
    override val noMatches = "לא נמצא כלום"
    override val catalogFailed = "לא הצלחנו לטעון את הקטלוג"
    override val countLine = { bot: String, on: Int, total: Int -> "$bot · $on מתוך $total פעילים" }
    override val official = "רשמי"
    override val verified = "מאומת"
    override val community = "קהילה"
    override val builtIn = "מובנה"
    override val fromCatalog = "מהקטלוג"
    override val fromLink = "מקישור"
    override val by = { who: String -> "מאת $who" }
    override val on = "פעיל"
    override val turnOn = "הפעלה"
    override val turnOff = "כיבוי"
    override val details = "פרטים"
    override val install = "התקנה"
    override val installing = "מתקין…"
    override val update = "עדכון"
    override val updating = "מעדכן…"
    override val updateTo = { v: String -> "עדכון ל־$v" }
    override val updateReady = "יש עדכון"
    override val remove = "הסרה"
    override val source = "קוד מקור"
    override val fixedOn = "תמיד פעיל"
    override val installedToast = { n: String -> "$n הותקן" }
    override val stillInstalling = { n: String -> "$n עדיין מותקן במחשב" }
    override val updated = { n: String -> "$n עודכן" }
    override val upToDate = { n: String -> "$n כבר מעודכן" }
    override val removed = { n: String -> "$n הוסר" }
    override val saved = "נשמר"
    override val restartNeeded = "ייכנס לתוקף אחרי הפעלה מחדש של המנוע"
    override val nextChat = "יעבוד מהצ'אט הבא"
    override val memoryCleared = "הזיכרון חזר למובנה"
    override val couldntStart = { names: String -> "לא הצליח להפעיל את $names" }
    override val tools = "כלים"
    override val apps = "אפליקציות"
    override val needsKeys = "צריך מפתחות"
    override val runsOn = "רץ על"
    override val settings = "הגדרות"
    override val optional = "רשות"
    override val secretSet = "שמור · הקלידו כדי להחליף"
    override val notes = "הערות"
    override val needNumber = { label: String -> "$label: צריך מספר" }
    override val needChoice = { label: String -> "$label: צריך לבחור" }
    override val needJson = { label: String -> "$label: צריך רשימה או אובייקט JSON" }
    override val needLink = "קודם מדביקים קישור"
    override val linkTitle = "התקנה מקישור"
    override val linkHint = "שם, קישור Git או owner/repo"
    override val unknownName = "אין בקטלוג תוסף בשם הזה"
    override val catalogUnavailable = "אי אפשר להגיע לקטלוג כרגע"
    override val keysTitle = { n: String -> "$n צריך מפתחות" }
    override val keyValue = "ערך"
    override val saveKeys = "שמירת מפתחות"
    override val later = "אחר כך"
    override val consentTitle = { n: String -> "$n מבקש יותר הרשאות" }
    override val consentBody = "הגרסה החדשה מוסיפה:"
    override val scanTitle = { n: String -> "בדיקת הבטיחות סימנה את $n" }
    override val scanCaution = "בקוד החדש יש חלקים מסוכנים. עדכנו רק אם אתם סומכים עליו."
    override val scanBlocked = "הקוד החדש נראה לא בטוח, ולכן הוא לא הותקן."
    override val updateAnyway = "לעדכן בכל זאת"
    override val andMore = { n: Int -> "ועוד $n" }
    override val serverState = { state: String ->
        when (state) {
            "connected" -> "מחובר"
            "app_not_running" -> "האפליקציה לא פועלת"
            "hermes_not_connected" -> "עוד לא מחובר"
            "endpoint_unavailable" -> "אי אפשר להגיע אליו"
            "no_interactive_session" -> "צריך משתמש מחובר במחשב"
            "version_too_old" -> "האפליקציה ישנה מדי"
            "missing_app" -> "האפליקציה לא מותקנת"
            "unsupported_gpu" -> "כרטיס המסך לא נתמך"
            else -> "לא ידוע"
        }
    }
    override val category = { id: String ->
        when (id) {
            "memory" -> "זיכרון"
            "tools" -> "כלים"
            "web" -> "רשת"
            "models" -> "מודלים"
            "voice" -> "קול"
            "automation" -> "אוטומציה"
            "platform" -> "הודעות"
            "general" -> "כללי"
            else -> id
        }
    }
}

private val EnglishPluginsPageStrings = PluginsPageStrings()

/** `S.pluginsPage.x` in a composable. */
val S.pluginsPage: PluginsPageStrings @Composable get() = if (current.rtl) HebrewPluginsPageStrings else EnglishPluginsPageStrings
