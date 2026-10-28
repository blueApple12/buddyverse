package app.buddyverse.ui.more.insights

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.Hermes
import app.buddyverse.core.RestError
import app.buddyverse.core.api.Insights
import app.buddyverse.core.api.Plan
import app.buddyverse.core.api.Spend
import app.buddyverse.core.api.insights
import app.buddyverse.core.api.merged
import app.buddyverse.core.api.toPlan
import app.buddyverse.core.api.usageBars
import app.buddyverse.core.nowMillis
import app.buddyverse.design.Feedback
import app.buddyverse.design.Haptic
import app.buddyverse.design.Sound
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToLong

/** The periods the page offers (the server takes 1–365). */
val PERIODS = listOf(7, 30, 90)

/** What the day chart plots; picked by tapping a stat tile. */
enum class Metric {
    Cost, Tokens, Chats, Calls;

    fun of(s: Spend): Double = when (this) {
        Cost -> s.cost
        Tokens -> s.tokens.toDouble()
        Chats -> s.sessions.toDouble()
        Calls -> s.apiCalls.toDouble()
    }

    fun label(v: Double): String = if (this == Cost) money(v) else compact(v.roundToLong())
}

/**
 * State of More ▸ Insights. [load] shows the cached numbers for a choice at once, then fetches fresh ones (one bot, or every
 * bot in parallel for "All bots"). Read-only: Insights has nothing to create or delete.
 */
@Stable
class InsightsModel(private val api: Hermes, private val fb: Feedback) {
    var data by mutableStateOf<Insights?>(null); private set
    var plan by mutableStateOf<Plan?>(null); private set
    var loading by mutableStateOf(false); private set
    /** The last fetch failed (the page keeps showing older numbers when it has them). */
    var failed by mutableStateOf(false); private set
    var unsupported by mutableStateOf(false); private set
    /** A one-shot for the buddy (serial = timestamp, so a later buddy skips a stale cue). */
    var cue by mutableStateOf<BuddyCue?>(null); private set
    /** Bumps when numbers for a new choice land: the buddy mouths the headline. */
    var talkAt by mutableStateOf(0L); private set

    private val cache = mutableMapOf<String, Insights>()
    private var shown: String? = null
    private var planChecked = false

    fun react(shot: OneShot) { cue = BuddyCue(nowMillis(), shot) }

    suspend fun load(profiles: List<String>, days: Int, loud: Boolean = false) {
        val key = profiles.sorted().joinToString(",") + "|" + days
        val fresh = key != shown
        if (fresh) { shown = key; data = cache[key]; failed = false }
        loading = true
        try {
            val results = coroutineScope { profiles.map { p -> async { runCatching { api.insights(days, p) } } }.awaitAll() }
            currentCoroutineContext().ensureActive()
            val ok = results.mapNotNull { it.getOrNull() }
            if (ok.isEmpty()) throw results.firstNotNullOfOrNull { it.exceptionOrNull() } ?: IllegalStateException("no bots")
            // ponytail: in "All bots" a bot whose call failed is silently left out of the sum; flag it if that ever matters.
            val next = ok.merged() ?: return
            cache[key] = next
            val wasEmpty = data == null
            data = next; failed = false; unsupported = false
            if (fresh || wasEmpty) talkAt = nowMillis()
            if (loud) { react(OneShot.Done); fb.haptic(Haptic.Confirm) } else if (fresh || wasEmpty) react(OneShot.Nod)
            if ((loud || !planChecked) && "usage.bars" !in api.gateway.unsupported) {
                planChecked = true
                plan = runCatching { api.usageBars().toPlan() }.getOrNull()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is RestError && e.status == 404) { unsupported = true; return }
            failed = true
            if (loud || data == null) { react(OneShot.Startle); fb.haptic(Haptic.Reject) }
            if (loud) fb.sound(Sound.Bonk)
        } finally {
            loading = false
        }
    }
}

// ---- numbers (Western digits, design §9.6; wrap with `ltr()` inside Hebrew text)

private fun oneDecimal(v: Double): String {
    val r = (v * 10).roundToLong()
    return if (r % 10 == 0L) "${r / 10}" else "${r / 10}.${r % 10}"
}

/** 950 → "950", 1234 → "1.2K", 45_600 → "46K", 1_250_000 → "1.3M", 3.2e9 → "3.2B". */
fun compact(n: Long): String {
    fun f(v: Double, unit: String) = (if (v < 9.95) oneDecimal(v) else v.roundToLong().toString()) + unit
    return when {
        n < 1000 -> "$n"
        n < 999_500 -> f(n / 1e3, "K")
        n < 999_500_000 -> f(n / 1e6, "M")
        else -> f(n / 1e9, "B")
    }
}

/** 1234567 → "1,234,567". */
fun grouped(n: Long): String = n.toString().reversed().chunked(3).joinToString(",").reversed()

/** USD: "$0", "<$0.01", "$4.20", "$1,240". */
fun money(d: Double): String = when {
    d <= 0.0 -> "$0"
    d < 0.005 -> "<$0.01"
    d < 1000 -> (d * 100).roundToLong().let { "$" + "${it / 100}." + "${it % 100}".padStart(2, '0') }
    else -> "$" + grouped(d.roundToLong())
}

/** Share of [part] in [whole], 0–1. */
fun share(part: Double, whole: Double): Float = if (whole <= 0) 0f else (part / whole).toFloat().coerceIn(0f, 1f)
