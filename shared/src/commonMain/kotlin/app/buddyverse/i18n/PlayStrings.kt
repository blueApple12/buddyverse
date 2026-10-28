package app.buddyverse.i18n

import androidx.compose.runtime.Composable

/** What a duel bot says. */
enum class Talk { Hello, BotWins, BotLoses, Draw }

/** Strings of the mini-games (ui/play). English base, Hebrew overrides. */
open class PlayStrings {
    open val title = "Play"
    open val snakeName = "Bot snake"
    open val snakeHint = "Swipe to steer. Each task you eat brings another bot along"
    open val mergeName = "2048 bots"
    open val mergeHint = "Swipe to slide. Two matching bots merge into a bigger one"
    open val tttName = "Tic-tac-toe"
    open val tttHint = "Beat each of your bots to unlock the next"
    open val c4Name = "Four in a row"
    open val c4Hint = "Drop discs; four in a line wins. Same bot ladder"
    open val echoName = "Bot echo"
    open val echoHint = "Watch your bots chirp, then tap them in the same order"
    open val start = "Start"
    open val again = "Play again"
    open val newGame = "New game"
    open val newBest = "New best!"
    open val best = { n: Int -> "Best $n" }
    open val beaten = { n: Int, of: Int -> "Beaten $n of $of" }
    open val level = { n: Int, name: String -> "Level $n · $name" }
    open val locked = "Beat the bot before to unlock"
    open val yourTurn = "Your turn"
    open val thinking = { name: String -> "$name is thinking…" }
    open val youWin = "You win!"
    open val youLose = { name: String -> "$name wins" }
    open val draw = "Draw"
    open val next = { name: String -> "Next: $name" }
    open val unlocked = { name: String -> "$name unlocked" }
    open val cleared = "You held the brain. Ladder done!"
    open val watch = "Watch…"
    open val yourGo = "Your go"
    open val score = { you: Int, bot: Int -> "You $you · $bot bot" }
    open val collapse = "Fold"
    open val close = "Close games"
    open val back = "Games"

    /** A bot's line for [t]; [tier] 0 (easy, goofy) .. 3 (the brain, smug). Several per slot, picked at random. */
    open fun talk(tier: Int, t: Talk): List<String> = when (tier) {
        0 -> when (t) {
            Talk.Hello -> listOf("Go easy on me!", "I just learned this game", "Ooh, a game!")
            Talk.BotWins -> listOf("Wait, I won?!", "Beginner's luck!", "Did that count?")
            Talk.BotLoses -> listOf("Oops, you got me!", "Again? I'm learning!", "Wheee, nice one")
            Talk.Draw -> listOf("Nobody wins? Fun!", "We both win!")
        }
        1 -> when (t) {
            Talk.Hello -> listOf("Let's play fair and square", "Ready when you are")
            Talk.BotWins -> listOf("Got you that time!", "Blocked and won!")
            Talk.BotLoses -> listOf("Nice move!", "OK, you're good")
            Talk.Draw -> listOf("Tied! Good game", "Evenly matched")
        }
        2 -> when (t) {
            Talk.Hello -> listOf("I've been practicing", "Don't blink")
            Talk.BotWins -> listOf("Too slow!", "Saw that coming")
            Talk.BotLoses -> listOf("Hmm. Not bad.", "Lucky. Rematch?")
            Talk.Draw -> listOf("Can't beat me that easily", "Close one")
        }
        else -> when (t) {
            Talk.Hello -> listOf("I've seen every game. Every one.", "Shall we?")
            Talk.BotWins -> listOf("Calculated.", "As predicted.")
            Talk.BotLoses -> listOf("…Impossible.", "Recalculating.")
            Talk.Draw -> listOf("A draw. The best you'll get.", "Respectable.")
        }
    }
}

private object HebrewPlayStrings : PlayStrings() {
    override val title = "משחקים"
    override val snakeName = "נחש בוטים"
    override val snakeHint = "החליקו כדי לנווט. כל משימה שאוכלים מצרפת עוד בוט"
    override val mergeName = "2048 בוטים"
    override val mergeHint = "החליקו כדי להזיז. שני בוטים זהים מתאחדים לבוט גדול יותר"
    override val tttName = "איקס עיגול"
    override val tttHint = "נצחו כל בוט כדי לפתוח את הבא"
    override val c4Name = "ארבע בשורה"
    override val c4Hint = "הפילו דיסקיות; ארבע בקו מנצחות. אותו סולם בוטים"
    override val echoName = "הד בוטים"
    override val echoHint = "הקשיבו לבוטים מצייצים, ואז הקישו עליהם באותו סדר"
    override val start = "להתחיל"
    override val again = "עוד פעם"
    override val newGame = "משחק חדש"
    override val newBest = "שיא חדש!"
    override val best = { n: Int -> "שיא $n" }
    override val beaten = { n: Int, of: Int -> "ניצחתם $n מתוך $of" }
    override val level = { n: Int, name: String -> "שלב $n · $name" }
    override val locked = "נצחו את הבוט הקודם כדי לפתוח"
    override val yourTurn = "התור שלכם"
    override val thinking = { name: String -> "$name חושב…" }
    override val youWin = "ניצחתם!"
    override val youLose = { name: String -> "$name ניצח" }
    override val draw = "תיקו"
    override val next = { name: String -> "הבא: $name" }
    override val unlocked = { name: String -> "$name נפתח" }
    override val cleared = "החזקתם מול המוח. סיימתם את הסולם!"
    override val watch = "הקשיבו…"
    override val yourGo = "תורכם"
    override val score = { you: Int, bot: Int -> "אתם $you · $bot בוט" }
    override val collapse = "לקפל"
    override val close = "לסגור משחקים"
    override val back = "משחקים"

    override fun talk(tier: Int, t: Talk): List<String> = when (tier) {
        0 -> when (t) {
            Talk.Hello -> listOf("תהיו עדינים איתי!", "רק עכשיו למדתי את המשחק", "יש, משחק!")
            Talk.BotWins -> listOf("רגע, ניצחתי?!", "מזל של מתחילים!", "זה נחשב?")
            Talk.BotLoses -> listOf("אופס, תפסתם אותי!", "עוד פעם? אני לומד!", "ווי, יפה")
            Talk.Draw -> listOf("אף אחד לא ניצח? כיף!", "שנינו ניצחנו!")
        }
        1 -> when (t) {
            Talk.Hello -> listOf("בואו נשחק הוגן", "מוכן כשאתם מוכנים")
            Talk.BotWins -> listOf("הפעם תפסתי אתכם!", "חסמתי וניצחתי!")
            Talk.BotLoses -> listOf("מהלך יפה!", "טוב, אתם טובים")
            Talk.Draw -> listOf("תיקו! משחק טוב", "שקולים")
        }
        2 -> when (t) {
            Talk.Hello -> listOf("התאמנתי", "אל תמצמצו")
            Talk.BotWins -> listOf("לאט מדי!", "ראיתי את זה בא")
            Talk.BotLoses -> listOf("הממ. לא רע.", "מזל. עוד סיבוב?")
            Talk.Draw -> listOf("לא כל כך קל לנצח אותי", "היה קרוב")
        }
        else -> when (t) {
            Talk.Hello -> listOf("ראיתי כל משחק. כל אחד.", "נתחיל?")
            Talk.BotWins -> listOf("מחושב.", "כצפוי.")
            Talk.BotLoses -> listOf("…בלתי אפשרי.", "מחשב מחדש.")
            Talk.Draw -> listOf("תיקו. הכי טוב שתשיגו.", "מכובד.")
        }
    }
}

private val EnglishPlayStrings = PlayStrings()

/** `S.play.x` in a composable. */
val S.play: PlayStrings @Composable get() = if (current.rtl) HebrewPlayStrings else EnglishPlayStrings
