package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/**
 * Strings owned by More ▸ Insights (ui/more/InsightsPage.kt, ui/more/insights). Same pattern as [Strings]: English base,
 * Hebrew overrides. Shared words (Back, Close, Try again…) stay in [Strings].
 */
open class InsightsPageStrings {
    open val allBots = "All bots"
    open val period = { n: Int -> "$n days" }
    open val headline = { amount: String, n: Int -> "$amount in $n days" }
    open val refresh = "Refresh"
    // stat tiles (each picks what the chart shows)
    open val spent = "Spent"
    open val tokens = "Tokens"
    open val chats = "Chats"
    open val calls = "Model calls"
    open val messages = { n: String -> "$n messages" }
    open val billed = { amount: String -> "$amount billed" }
    open val billedLabel = "Billed"
    open val noUse = "Nothing ran in these days"
    // sections
    open val byDay = "By day"
    open val models = "Models"
    open val background = "Background work"
    open val tools = "Tools"
    open val skills = "Skills"
    open val plan = "Plan"
    open val showAll = "Show all"
    open val showLess = "Show less"
    // rows
    open val tokensShort = { n: String -> "$n tokens" }
    open val chatsShort = { n: String -> "$n chats" }
    open val timesShort = { n: String -> "$n×" }
    open val skillUse = { loads: String, edits: String -> "opened $loads× · edited $edits×" }
    // model sheet
    open val input = "Input"
    open val output = "Output"
    open val cacheRead = "From cache"
    open val reasoning = "Thinking"
    open val toolCalls = "Tool calls"
    open val perChat = "Per chat"
    open val contextWindow = "Context window"
    open val maxOutput = "Longest reply"
    open val canUseTools = "Uses tools"
    open val seesImages = "Sees images"
    open val thinks = "Thinks first"
    open val lastUsed = "Last used"
    open val yes = "Yes"
    open val no = "No"
    open val cost = "Cost"
    // plan (usage.bars)
    open val planStatus = { s: String ->
        when (s) {
            "free" -> "Free"
            "healthy" -> "Plenty left"
            "low" -> "Running low"
            "depleted" -> "Used up"
            else -> s
        }
    }
    open val renews = { d: String -> "renews $d" }
    open val planBar = "Plan"
    open val topUpBar = "Top-up"
    open val leftOf = { left: String, total: String -> "$left left of $total" }
    // background task names (`by_task[].task`)
    open val task = { t: String ->
        when (t) {
            "compression" -> "Shortening long chats"
            "vision" -> "Looking at images"
            "title_generation", "title" -> "Naming chats"
            "session_search" -> "Searching chats"
            "web_extract" -> "Reading pages"
            "skills_hub", "curator" -> "Tidying skills"
            "approval", "smart_approval" -> "Checking commands"
            "memory", "memory_flush" -> "Remembering"
            "mcp" -> "MCP helpers"
            "delegation" -> "Helpers"
            else -> t.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }
}

private object HebrewInsightsPageStrings : InsightsPageStrings() {
    override val allBots = "כל הבוטים"
    override val period = { n: Int -> if (n <= 10) "$n ימים" else "$n יום" }
    override val headline = { amount: String, n: Int -> "$amount ב־${if (n <= 10) "$n ימים" else "$n יום"}" }
    override val refresh = "רענון"
    override val spent = "הוצאה"
    override val tokens = "טוקנים"
    override val chats = "צ'אטים"
    override val calls = "קריאות למודל"
    override val messages = { n: String -> "$n הודעות" }
    override val billed = { amount: String -> "חויב $amount" }
    override val billedLabel = "חויב"
    override val noUse = "שום דבר לא רץ בימים האלה"
    override val byDay = "לפי יום"
    override val models = "מודלים"
    override val background = "עבודת רקע"
    override val tools = "כלים"
    override val skills = "מיומנויות"
    override val plan = "תוכנית"
    override val showAll = "הצגת הכול"
    override val showLess = "הצגת פחות"
    override val tokensShort = { n: String -> "$n טוקנים" }
    override val chatsShort = { n: String -> "$n צ'אטים" }
    override val skillUse = { loads: String, edits: String -> "נפתחה $loads× · נערכה $edits×" }
    override val input = "קלט"
    override val output = "פלט"
    override val cacheRead = "מהמטמון"
    override val reasoning = "חשיבה"
    override val toolCalls = "קריאות לכלים"
    override val perChat = "לצ'אט"
    override val contextWindow = "חלון הקשר"
    override val maxOutput = "תשובה הכי ארוכה"
    override val canUseTools = "משתמש בכלים"
    override val seesImages = "רואה תמונות"
    override val thinks = "חושב קודם"
    override val lastUsed = "שימוש אחרון"
    override val yes = "כן"
    override val no = "לא"
    override val cost = "עלות"
    override val planStatus = { s: String ->
        when (s) {
            "free" -> "חינם"
            "healthy" -> "נשאר הרבה"
            "low" -> "עומד להיגמר"
            "depleted" -> "נגמר"
            else -> s
        }
    }
    override val renews = { d: String -> "מתחדש ב־$d" }
    override val planBar = "תוכנית"
    override val topUpBar = "טעינה"
    override val leftOf = { left: String, total: String -> "נשארו $left מתוך $total" }
    override val task = { t: String ->
        when (t) {
            "compression" -> "קיצור צ'אטים ארוכים"
            "vision" -> "הסתכלות בתמונות"
            "title_generation", "title" -> "מתן שמות לצ'אטים"
            "session_search" -> "חיפוש בצ'אטים"
            "web_extract" -> "קריאת דפים"
            "skills_hub", "curator" -> "סידור מיומנויות"
            "approval", "smart_approval" -> "בדיקת פקודות"
            "memory", "memory_flush" -> "זכירה"
            "mcp" -> "עוזרי MCP"
            "delegation" -> "עוזרים"
            else -> t.replace('_', ' ')
        }
    }
}

private val EnglishInsightsPageStrings = InsightsPageStrings()

/** `S.insightsPage.x` in a composable. */
val S.insightsPage: InsightsPageStrings @Composable get() = if (current.rtl) HebrewInsightsPageStrings else EnglishInsightsPageStrings
