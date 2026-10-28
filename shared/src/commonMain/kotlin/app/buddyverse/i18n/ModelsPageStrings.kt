package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by More ▸ Models (ui/more/ModelsPage.kt, ui/more/models). Same pattern as [Strings]: English base, Hebrew
 * overrides. Shared words (Cancel, Save, Delete, Undo, Copy, Try again…) stay in [Strings].
 */
open class ModelsPageStrings {
    open val refresh = "Refresh"
    open val looking = "Looking…"
    open val botModel = { bot: String, model: String -> "$bot · $model" }
    open val defaultModel = "Default model"
    open val none = "none"
    open val context = "Context"
    open val tokens = "Tokens"
    open val auto = "Auto"
    open val autoWith = { n: String -> if (n.isBlank()) "Auto" else "Auto · $n" }
    open val thinking = "Thinking"
    open val speed = "Speed"
    open val level = { l: String ->
        when (l) {
            "none" -> "Off"; "minimal" -> "Minimal"; "low" -> "Low"; "medium" -> "Medium"; "high" -> "High"
            "xhigh" -> "Extra high"; "max" -> "Max"; "ultra" -> "Ultra"; else -> l
        }
    }
    open val speedName = { tier: String -> when (tier) { "priority" -> "Fast"; "ultrafast" -> "Ultra fast"; else -> "Normal" } }
    open val newChatsUse = { model: String -> "New chats use $model" }
    open val saved = "Saved"
    open val confirmTitle = "Use this model?"
    open val confirmCost = "This model costs more or changes how your data is handled."
    open val useAnyway = "Use it"
    open val searchModels = "Search models"
    open val noProviders = "Connect a provider first"
    open val noMatches = "Nothing found"

    open val fallbacks = "Backups"
    open val addFallback = "Add a backup"
    open val remove = "Remove"

    open val helpers = "Helpers"
    open val allAutomatic = "All follow the default"
    open val pinnedCount = { n: Int -> if (n == 1) "1 helper set" else "$n helpers set" }
    open val followsMain = "Follows the default"
    open val allToAuto = "All back to the default"
    open val helperLabel = { task: String, label: String? ->
        when (task) {
            "vision" -> "Seeing images"; "compression" -> "Shortening long chats"; "skills_hub" -> "Skills search"
            "approval" -> "Safety checks"; "mcp" -> "Tool servers"; "title_generation" -> "Chat titles"; "review" -> "Reviews"
            "voice_chat" -> "Voice chat"; "triage_specifier" -> "Task triage"; "kanban_decomposer" -> "Splitting tasks"
            "profile_describer" -> "Bot descriptions"; "curator" -> "Memory curator"
            else -> label ?: task.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    open val providers = "Providers"
    open val addProvider = "Add a provider"
    open val searchProviders = "Search providers"
    open val inUse = "In use"
    open val connectedWord = "Connected"
    open val modelsCount = { n: Int -> if (n == 1) "1 model" else "$n models" }
    open val needsKey = "Needs a key"
    open val signInHere = "Sign in from the phone"
    open val signInOnPcShort = "Sign in on the PC"
    open val setUpOnPcShort = "Set up on the PC"
    open val setUpOnPc = "This one is set up on the PC."
    open val signInOnPc = "This sign-in runs on the PC. Copy the command and run it in a terminal there."
    open val copyCommand = "Copy the command"
    open val copied = "Copied"
    open val signIn = "Sign in"
    open val signOut = "Sign out"
    open val enterCode = "Enter this code"
    open val openPage = "Open the sign-in page"
    open val waiting = "Waiting for you to finish…"
    open val signedIn = { n: String -> "$n is connected" }
    open val signedOut = { n: String -> "$n signed out" }
    open val signInFailed = "Sign-in didn't finish"
    open val resetsIn = { s: String -> "resets in $s" }
    open val pickModel = "Pick one of its models"
    open val connect = "Connect"
    open val checking = "Checking…"
    open val connected = { n: String -> "$n is connected" }
    open val keyRefused = "The provider refused this key"
    open val removeKey = "Remove the key"
    open val pasteKey = "Paste the key"
    open val replaceKey = "New key (empty keeps the current one)"
    open val optional = "Optional"
    open val getKey = "Get a key"
    open val removed = { n: String -> "$n removed" }
    open val added = { n: String -> "$n added" }

    open val endpoints = "Your endpoints"
    open val addEndpoint = "Add an endpoint"
    open val name = "Name"
    open val address = "Address, e.g. http://192.168.1.5:8080/v1"
    open val modelId = "Model"
    open val keepKey = "API key (empty keeps the current one)"
    open val keyOptional = "API key (optional)"
    open val contextOptional = "Context in tokens (optional)"
    open val apiFormat = "Format"
    open val apiMode = { mode: String ->
        when (mode) { "chat_completions" -> "Chat"; "codex_responses" -> "Responses"; "anthropic_messages" -> "Messages"; else -> "Auto" }
    }
    open val check = "Check"
    open val saving = "Saving…"
    open val works = { n: Int -> if (n == 1) "Works · 1 model" else "Works · $n models" }
    open val cantReach = "Can't reach it"
    open val useIt = "Use it"
    open val needName = "Give it a name"
    open val needAddress = "Add the address"
    open val needModel = "Add a model"

    open val onThisPc = "On this PC"
    open val thisPc = "This PC"
    open val gpuMemory = { label: String -> "$label GPU" }
    open val localEngine = "Local engine"
    open val engineMissing = "engine not installed"
    open val engineOn = "engine on"
    open val engineOff = "engine off"
    open val installEngine = "Install the local engine"
    open val updateEngine = "Update the local engine"
    open val getModel = "Get a model"
    open val forThisPc = "For this PC"
    open val searchHub = "Hugging Face"
    open val searchHubHint = "Search GGUF models"
    open val fromPc = "File on the PC"
    open val bestForPc = "Best for this PC"
    open val setUpForMe = "Set it up for me"
    open val recommended = "Best pick"
    open val contextOf = { n: String -> "$n context" }
    open val seesImages = "sees images"
    open val needsNewerEngine = "Needs a newer local engine"
    open val tooBig = "Too big for this PC"
    open val get = "Get"
    open val downloading = "Downloading"
    open val downloads = { n: Long -> if (n >= 1_000_000) "${n / 1_000_000}M downloads" else if (n >= 1000) "${n / 1000}K downloads" else "$n downloads" }
    open val gated = "needs access"
    open val noFiles = "No model files here"
    open val fit = { f: String -> when (f) { "fits-gpu" -> "fits the GPU"; "needs-ram" -> "uses RAM too, slower"; "too-big" -> "too big"; else -> "" } }
    open val pcPath = "Path on the PC, e.g. D:\\models\\model.gguf"
    open val addFile = "Add it"
    open val alreadyHere = { n: String -> "$n is already on the PC" }
    open val ready = { n: String -> "$n is ready" }
    open val failedJob = { n: String -> "$n failed" }
    open val paused = "Paused"
    open val pause = "Pause"
    open val resume = "Resume"
    open val loaded = "Loaded"
    open val unload = "Unload from the GPU"
    open val unloaded = "Unloaded"

    open val keys = "Keys"
    open val allKeys = "All keys"
    open val keysSet = { n: Int -> if (n == 1) "1 saved" else "$n saved" }
    open val setOnly = "Saved"
    open val keyKind = { i: Int -> listOf("Models", "Tools", "Skills", "Messaging", "Other").getOrElse(i) { "Other" } }
    open val searchKeys = "Search keys"
    open val show = "Show"
    open val value = "Value"

    // subagents (the helpers a bot starts with delegate_task)
    open val subagents = "Subagents"
    open val subModel = "Their model"
    open val sameAsBot = "Same as the bot"
    open val subThinking = "Their thinking"
    open val same = "Same"
    open val atOnce = "At once"
    open val depth = "Levels"
    open val depthName = { n: Int -> when (n) { 1 -> "Flat"; 2 -> "Two levels"; else -> "$n levels" } }
    open val route = "Where they run"
    open val routeBlocked = { why: String -> "Subagents are off: $why" }
    open val routesSetOnPc = { models: Int, bots: Int -> "Routes set on the PC: $models by model, $bots by bot" }

    // team answers (Mixture of Agents presets)
    open val teams = "Team answers"
    open val addTeam = "New team"
    open val teamName = "Team name"
    open val teamSummary = { n: Int, combiner: String -> if (n == 1) "1 advisor · $combiner combines" else "$n advisors · $combiner combines" }
    open val advisors = "Advisors"
    open val addAdvisor = "Add an advisor"
    open val combiner = "Combines the answers"
    open val teamOn = "Team on"
    open val defaultTeam = "Default team"
    open val makeDefaultTeam = "Make it the default team"
    open val useTeam = "Answer as this team"
    open val deleteTeam = "Delete the team"
    open val teamNeedsAdvisor = "A team needs at least one advisor"
    open val teamExists = "That name is taken"
    open val lastTeam = "Keep at least one team"
    open val off = "off"
}

private object HebrewModelsPageStrings : ModelsPageStrings() {
    override val refresh = "רענון"
    override val looking = "בודק…"
    override val defaultModel = "מודל ברירת מחדל"
    override val none = "אין"
    override val context = "הקשר"
    override val tokens = "טוקנים"
    override val auto = "אוטומטי"
    override val autoWith = { n: String -> if (n.isBlank()) "אוטומטי" else "אוטומטי · $n" }
    override val thinking = "חשיבה"
    override val speed = "מהירות"
    override val level = { l: String ->
        when (l) {
            "none" -> "כבויה"; "minimal" -> "מינימלית"; "low" -> "נמוכה"; "medium" -> "בינונית"; "high" -> "גבוהה"
            "xhigh" -> "גבוהה מאוד"; "max" -> "מקסימלית"; "ultra" -> "אולטרה"; else -> l
        }
    }
    override val speedName = { tier: String -> when (tier) { "priority" -> "מהיר"; "ultrafast" -> "מהיר במיוחד"; else -> "רגיל" } }
    override val newChatsUse = { model: String -> "שיחות חדשות ישתמשו ב-$model" }
    override val saved = "נשמר"
    override val confirmTitle = "להשתמש במודל הזה?"
    override val confirmCost = "המודל הזה יקר יותר או משנה את הטיפול במידע שלך."
    override val useAnyway = "להשתמש"
    override val searchModels = "חיפוש מודלים"
    override val noProviders = "קודם צריך לחבר ספק"
    override val noMatches = "לא נמצא כלום"

    override val fallbacks = "גיבויים"
    override val addFallback = "הוספת גיבוי"
    override val remove = "הסרה"

    override val helpers = "עוזרים"
    override val allAutomatic = "כולם לפי ברירת המחדל"
    override val pinnedCount = { n: Int -> if (n == 1) "עוזר אחד מוגדר" else "$n עוזרים מוגדרים" }
    override val followsMain = "לפי ברירת המחדל"
    override val allToAuto = "כולם חזרה לברירת המחדל"
    override val helperLabel = { task: String, label: String? ->
        when (task) {
            "vision" -> "ראיית תמונות"; "compression" -> "קיצור שיחות ארוכות"; "skills_hub" -> "חיפוש מיומנויות"
            "approval" -> "בדיקות בטיחות"; "mcp" -> "שרתי כלים"; "title_generation" -> "כותרות לשיחות"; "review" -> "סקירות"
            "voice_chat" -> "שיחה קולית"; "triage_specifier" -> "מיון משימות"; "kanban_decomposer" -> "פירוק משימות"
            "profile_describer" -> "תיאורי בוטים"; "curator" -> "סידור הזיכרון"
            else -> label ?: task.replace('_', ' ')
        }
    }

    override val providers = "ספקים"
    override val addProvider = "הוספת ספק"
    override val searchProviders = "חיפוש ספקים"
    override val inUse = "בשימוש"
    override val connectedWord = "מחובר"
    override val modelsCount = { n: Int -> if (n == 1) "מודל אחד" else "$n מודלים" }
    override val needsKey = "צריך מפתח"
    override val signInHere = "התחברות מהטלפון"
    override val signInOnPcShort = "התחברות במחשב"
    override val setUpOnPcShort = "הגדרה במחשב"
    override val setUpOnPc = "את הספק הזה מגדירים במחשב."
    override val signInOnPc = "ההתחברות הזו רצה במחשב. העתיקו את הפקודה והריצו אותה בטרמינל שם."
    override val copyCommand = "העתקת הפקודה"
    override val copied = "הועתק"
    override val signIn = "התחברות"
    override val signOut = "התנתקות"
    override val enterCode = "הקלידו את הקוד"
    override val openPage = "פתיחת דף ההתחברות"
    override val waiting = "מחכה שתסיימו…"
    override val signedIn = { n: String -> "$n מחובר" }
    override val signedOut = { n: String -> "$n נותק" }
    override val signInFailed = "ההתחברות לא הסתיימה"
    override val resetsIn = { s: String -> "מתאפס בעוד $s" }
    override val pickModel = "בחירת אחד המודלים שלו"
    override val connect = "חיבור"
    override val checking = "בודק…"
    override val connected = { n: String -> "$n מחובר" }
    override val keyRefused = "הספק דחה את המפתח"
    override val removeKey = "הסרת המפתח"
    override val pasteKey = "הדביקו את המפתח"
    override val replaceKey = "מפתח חדש (ריק משאיר את הנוכחי)"
    override val optional = "לא חובה"
    override val getKey = "השגת מפתח"
    override val removed = { n: String -> "$n הוסר" }
    override val added = { n: String -> "$n נוסף" }

    override val endpoints = "כתובות משלך"
    override val addEndpoint = "הוספת כתובת"
    override val name = "שם"
    override val address = "כתובת, למשל http://192.168.1.5:8080/v1"
    override val modelId = "מודל"
    override val keepKey = "מפתח API (ריק משאיר את הנוכחי)"
    override val keyOptional = "מפתח API (לא חובה)"
    override val contextOptional = "הקשר בטוקנים (לא חובה)"
    override val apiFormat = "פורמט"
    override val apiMode = { mode: String ->
        when (mode) { "chat_completions" -> "Chat"; "codex_responses" -> "Responses"; "anthropic_messages" -> "Messages"; else -> "אוטומטי" }
    }
    override val check = "בדיקה"
    override val saving = "שומר…"
    override val works = { n: Int -> if (n == 1) "עובד · מודל אחד" else "עובד · $n מודלים" }
    override val cantReach = "אין גישה לכתובת"
    override val useIt = "להשתמש בזה"
    override val needName = "צריך לתת שם"
    override val needAddress = "צריך כתובת"
    override val needModel = "צריך מודל"

    override val onThisPc = "על המחשב הזה"
    override val thisPc = "המחשב הזה"
    override val gpuMemory = { label: String -> "$label זיכרון גרפי" }
    override val localEngine = "מנוע מקומי"
    override val engineMissing = "המנוע לא מותקן"
    override val engineOn = "המנוע פועל"
    override val engineOff = "המנוע כבוי"
    override val installEngine = "התקנת המנוע המקומי"
    override val updateEngine = "עדכון המנוע המקומי"
    override val getModel = "הורדת מודל"
    override val forThisPc = "למחשב הזה"
    override val searchHubHint = "חיפוש מודלי GGUF"
    override val fromPc = "קובץ במחשב"
    override val bestForPc = "הכי מתאים למחשב הזה"
    override val setUpForMe = "תגדירו לי"
    override val recommended = "הבחירה הטובה"
    override val contextOf = { n: String -> "הקשר $n" }
    override val seesImages = "רואה תמונות"
    override val needsNewerEngine = "צריך מנוע מקומי חדש יותר"
    override val tooBig = "גדול מדי למחשב הזה"
    override val get = "הורדה"
    override val downloading = "מוריד"
    override val downloads = { n: Long -> if (n >= 1_000_000) "${n / 1_000_000}M הורדות" else if (n >= 1000) "${n / 1000}K הורדות" else "$n הורדות" }
    override val gated = "צריך הרשאה"
    override val noFiles = "אין כאן קובצי מודל"
    override val fit = { f: String -> when (f) { "fits-gpu" -> "נכנס לכרטיס הגרפי"; "needs-ram" -> "משתמש גם ב-RAM, איטי יותר"; "too-big" -> "גדול מדי"; else -> "" } }
    override val pcPath = "נתיב במחשב, למשל D:\\models\\model.gguf"
    override val addFile = "הוספה"
    override val alreadyHere = { n: String -> "$n כבר נמצא במחשב" }
    override val ready = { n: String -> "$n מוכן" }
    override val failedJob = { n: String -> "$n נכשל" }
    override val paused = "מושהה"
    override val pause = "השהיה"
    override val resume = "המשך"
    override val loaded = "טעון"
    override val unload = "פריקה מהכרטיס הגרפי"
    override val unloaded = "נפרק"

    override val keys = "מפתחות"
    override val allKeys = "כל המפתחות"
    override val keysSet = { n: Int -> if (n == 1) "אחד שמור" else "$n שמורים" }
    override val setOnly = "שמורים"
    override val keyKind = { i: Int -> listOf("מודלים", "כלים", "מיומנויות", "הודעות", "אחר").getOrElse(i) { "אחר" } }
    override val searchKeys = "חיפוש מפתחות"
    override val show = "הצגה"
    override val value = "ערך"

    override val botModel = { bot: String, model: String -> "$bot · $model" }
    override val searchHub = "Hugging Face"

    override val subagents = "תת־סוכנים"
    override val subModel = "המודל שלהם"
    override val sameAsBot = "כמו של הבוט"
    override val subThinking = "החשיבה שלהם"
    override val same = "אותו דבר"
    override val atOnce = "בבת אחת"
    override val depth = "רמות"
    override val depthName = { n: Int -> when (n) { 1 -> "שטוח"; 2 -> "שתי רמות"; else -> "$n רמות" } }
    override val route = "איפה הם רצים"
    override val routeBlocked = { why: String -> "תת־הסוכנים כבויים: $why" }
    override val routesSetOnPc = { models: Int, bots: Int -> "ניתובים שהוגדרו במחשב: $models לפי מודל, $bots לפי בוט" }

    override val teams = "תשובות צוות"
    override val addTeam = "צוות חדש"
    override val teamName = "שם הצוות"
    override val teamSummary = { n: Int, combiner: String -> if (n == 1) "יועץ אחד · $combiner מאחד" else "$n יועצים · $combiner מאחד" }
    override val advisors = "יועצים"
    override val addAdvisor = "הוספת יועץ"
    override val combiner = "מאחד את התשובות"
    override val teamOn = "הצוות פעיל"
    override val defaultTeam = "צוות ברירת המחדל"
    override val makeDefaultTeam = "להפוך לצוות ברירת המחדל"
    override val useTeam = "לענות כצוות הזה"
    override val deleteTeam = "מחיקת הצוות"
    override val teamNeedsAdvisor = "צוות צריך לפחות יועץ אחד"
    override val teamExists = "השם הזה תפוס"
    override val lastTeam = "צריך להשאיר לפחות צוות אחד"
    override val off = "כבוי"
}

private val EnglishModelsPageStrings = ModelsPageStrings()

/** `S.modelsPage.x` in a composable. */
val S.modelsPage: ModelsPageStrings @Composable get() = if (current.rtl) HebrewModelsPageStrings else EnglishModelsPageStrings
