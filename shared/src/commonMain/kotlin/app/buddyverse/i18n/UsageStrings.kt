package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/** Usage card (Bots home) and the chat Usage sheet. */
open class UsageStrings {
    open val title = "Usage"
    open val claudeMax = "Claude Max"
    /** The engine's window labels in plain words; unknown ones pass through. */
    open fun window(label: String): String = when (label.lowercase()) {
        "current session" -> "Session (5 h)"; "current week" -> "This week"; else -> label
    }
    open val resetsIn = { t: String -> "resets in $t" }
    open val used = { p: Int -> "$p% used" }
    open val noLimits = "Plan limits load from your PC…"
    open val day = "24 h"
    open val week = "7 days"
    open val allBots = "All bots"
    open val tokens = { n: String -> "$n tokens" }
    open val apiValue = { m: String -> "$m API value" }
    open val apiValueNote = "What the same tokens would cost at API prices. Your subscription covers it."
    open val details = "Details"
    // a chat
    open val chatUsage = "Chat usage"
    open val context = "Context window"
    open val contextOf = { used: String, max: String -> "$used of $max" }
    open val contextUnknown = "Shows after the next reply"
    open val tokensUsed = "Tokens used"
    open val total = "Total"
    open val input = "Input"
    open val output = "Output"
    open val cacheRead = "Re-read (cache)"
    open val cacheWrite = "Cache write"
    open val reasoning = "Thinking"
    open val calls = "Model calls"
    open val cost = "Cost (API price)"
    open val model = "Model"
    open val notStarted = "Send a message first"
}

private object HebrewUsageStrings : UsageStrings() {
    override val title = "שימוש"
    override val claudeMax = "Claude Max"
    override fun window(label: String): String = when (label.lowercase()) {
        "current session" -> "סשן (5 שעות)"; "current week" -> "השבוע"; "fable week" -> "Fable השבוע"; else -> label
    }
    override val resetsIn = { t: String -> "מתאפס בעוד $t" }
    override val used = { p: Int -> "$p% בשימוש" }
    override val noLimits = "מגבלות התוכנית נטענות מהמחשב…"
    override val day = "24 שעות"
    override val week = "7 ימים"
    override val allBots = "כל הבוטים"
    override val tokens = { n: String -> "$n טוקנים" }
    override val apiValue = { m: String -> "שווי $m במחירי API" }
    override val apiValueNote = "כמה אותם טוקנים היו עולים במחירי API. המנוי שלך מכסה את זה."
    override val details = "פרטים"
    override val chatUsage = "שימוש בצ׳אט"
    override val context = "חלון הקשר"
    override val contextOf = { used: String, max: String -> "$used מתוך $max" }
    override val contextUnknown = "יוצג אחרי התשובה הבאה"
    override val tokensUsed = "טוקנים בשימוש"
    override val total = "סה״כ"
    override val input = "קלט"
    override val output = "פלט"
    override val cacheRead = "קריאה חוזרת (מטמון)"
    override val cacheWrite = "כתיבה למטמון"
    override val reasoning = "חשיבה"
    override val calls = "קריאות למודל"
    override val cost = "עלות (מחיר API)"
    override val model = "מודל"
    override val notStarted = "שלחו הודעה קודם"
}

private val EnglishUsageStrings = UsageStrings()

/** `S.usage.x` in a composable. */
val S.usage: UsageStrings @Composable get() = if (current.rtl) HebrewUsageStrings else EnglishUsageStrings
