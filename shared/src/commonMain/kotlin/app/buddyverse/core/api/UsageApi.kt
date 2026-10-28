package app.buddyverse.core.api

import androidx.compose.runtime.Immutable
import app.buddyverse.core.Hermes
import app.buddyverse.core.child
import app.buddyverse.core.double
import app.buddyverse.core.list
import app.buddyverse.core.long
import app.buddyverse.core.obj
import app.buddyverse.core.params
import app.buddyverse.core.str
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

// Usage at a glance (Bots home) and per chat. Sources, all read-only:
// - plan limits: `model.options` → provider `usage.windows` (hermes_cli/inventory.py; the engine caches each provider's
//   account usage and refreshes it in the background, so the first call after a while may come back empty)
// - tokens/cost per bot: `GET /api/analytics/usage` (the same call the Insights page makes)
// - one chat: `GET /api/sessions/{id}` (token counters + `estimated_cost_usd`) and `session.usage` (context window fill)

/** One rate-limit window: "Current session" (5 h), "Current week", "Gemini 7d"… */
@Immutable
data class LimitWindow(val label: String, val usedPercent: Double, val resetsAt: String?)

/** A provider's plan windows. */
@Immutable
data class PlanLimits(val provider: String, val name: String, val windows: List<LimitWindow>)

/** The Claude Max subscription provider (also shown as `anthropic` with the same windows; keep only this one). */
const val CLAUDE_SUB = "claude-subscription-directsdk-experimental"

fun parsePlanLimits(o: JsonObject?): List<PlanLimits> {
    val all = o.list("providers").mapNotNull { it.obj }.mapNotNull { p ->
        val slug = p.str("slug") ?: return@mapNotNull null
        val w = p.child("usage").list("windows").mapNotNull { e ->
            val x = e.obj ?: return@mapNotNull null
            val label = x.str("label")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            LimitWindow(label, x.double("used_percent") ?: return@mapNotNull null, x.str("resets_at"))
        }
        if (w.isEmpty()) null else PlanLimits(slug, p.str("name") ?: slug, w)
    }
    // Only the Claude subscription (the owner's call): other plans (Antigravity…) stay off the card.
    return all.filter { it.provider == CLAUDE_SUB }.ifEmpty { all.filter { it.provider == "anthropic" } }
}

/** Claude Max's limit windows (session, week…). One retry when the engine's cache was still cold. */
suspend fun Hermes.planLimits(profile: String): List<PlanLimits> {
    suspend fun once() = parsePlanLimits(gateway.call("model.options", params("profile" to profile, "explicit_only" to true), 30_000).obj)
    val first = once()
    if (first.any { it.provider == CLAUDE_SUB || it.provider == "anthropic" }) return first
    delay(4_000)
    return once().ifEmpty { first }
}

private fun q(v: String) = v.encodeURLParameter()

/** Tokens and API-price value: the last 24 hours and the last 7 days. */
@Immutable
data class UsageTotals(val day: Spend, val week: Spend)

/** All bots' tokens and cost (bots whose call fails are skipped). */
suspend fun Hermes.allBotsUsage(profiles: List<String>): UsageTotals = coroutineScope {
    fun period(days: Int) = profiles.distinct().map { p ->
        async {
            try { parseInsights(days, usage(days, p), null, null).totals } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        }
    }
    val d = period(1)
    val w = period(7)
    UsageTotals(d.awaitAll().filterNotNull().fold(Spend()) { a, b -> a + b }, w.awaitAll().filterNotNull().fold(Spend()) { a, b -> a + b })
}

/** One chat's counters. Tokens: everything the model read and wrote ([cacheRead] is re-read context, billed cheaper). */
@Immutable
data class ChatUsage(
    val input: Long = 0, val output: Long = 0, val cacheRead: Long = 0, val cacheWrite: Long = 0, val reasoning: Long = 0,
    val calls: Long = 0,
    /** API-price equivalent (USD) the engine estimates; on a subscription nothing is billed per call. */
    val costUsd: Double? = null,
    val contextUsed: Long? = null, val contextMax: Long? = null,
    val model: String? = null,
) {
    val total: Long get() = input + output + cacheRead + cacheWrite
    val contextFill: Float? get() = if (contextUsed != null && contextMax != null && contextMax > 0) (contextUsed.toFloat() / contextMax).coerceIn(0f, 1f) else null
}

fun parseChatUsage(row: JsonObject?, live: JsonObject?): ChatUsage {
    val r = row?.child("session") ?: row
    return ChatUsage(
        input = r.long("input_tokens") ?: live.long("input") ?: 0,
        output = r.long("output_tokens") ?: live.long("output") ?: 0,
        cacheRead = r.long("cache_read_tokens") ?: live.long("cache_read") ?: 0,
        cacheWrite = r.long("cache_write_tokens") ?: live.long("cache_write") ?: 0,
        reasoning = r.long("reasoning_tokens") ?: live.long("reasoning") ?: 0,
        calls = r.long("api_call_count") ?: live.long("calls") ?: 0,
        costUsd = r.double("estimated_cost_usd") ?: live.double("cost_usd"),
        contextUsed = live.long("context_used")?.takeIf { it > 0 },
        contextMax = live.long("context_max")?.takeIf { it > 0 },
        model = r.str("model") ?: live.str("model"),
    )
}

/** This chat's tokens, cost and context fill. The live part (`session.usage`) is optional: a closed chat has none. */
suspend fun Hermes.chatUsage(sessionId: String, profile: String): ChatUsage = coroutineScope {
    val live = async {
        try { gateway.call("session.usage", params("session_id" to sessionId, "profile" to profile), 15_000).obj }
        catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }
    val row = try { rest.get("/api/sessions/${q(sessionId)}?profile=${q(profile)}").obj } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    parseChatUsage(row, live.await())
}
