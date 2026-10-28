package app.buddyverse.core.api

// Owner: More ▸ Insights (ui/more/InsightsPage.kt). hermes-api.md §2.6 Insights, usage and analytics:
// `GET /api/analytics/usage` (existing [Hermes.usage]), `GET /api/analytics/models`, `insights.get` and `usage.bars` (WS legacy).

import androidx.compose.runtime.Immutable
import app.buddyverse.core.Hermes
import app.buddyverse.core.bool
import app.buddyverse.core.child
import app.buddyverse.core.double
import app.buddyverse.core.list
import app.buddyverse.core.long
import app.buddyverse.core.obj
import app.buddyverse.core.params
import app.buddyverse.core.str
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Tokens, cost and call counters: one shape for a day, a model, a background task and the period totals. */
@Immutable
data class Spend(
    val input: Long = 0, val output: Long = 0, val cacheRead: Long = 0, val reasoning: Long = 0,
    /** `estimated_cost` (USD), priced by the engine for every call. */
    val cost: Double = 0.0,
    /** `actual_cost` (USD), only where the provider reports a billed amount. */
    val billed: Double = 0.0,
    val sessions: Long = 0, val apiCalls: Long = 0,
) {
    val tokens: Long get() = input + output
    operator fun plus(o: Spend) = Spend(input + o.input, output + o.output, cacheRead + o.cacheRead, reasoning + o.reasoning,
        cost + o.cost, billed + o.billed, sessions + o.sessions, apiCalls + o.apiCalls)
}

/** `daily[]`: [day] is the PC's local calendar day, `YYYY-MM-DD`. */
@Immutable
data class DayUse(val day: String, val use: Spend)

/** `/api/analytics/models` card (or a `by_model` row from an older engine, without provider and capabilities). */
@Immutable
data class ModelUse(
    val model: String, val provider: String, val use: Spend, val toolCalls: Long, val lastUsedAt: Double,
    val contextWindow: Long?, val maxOutput: Long?, val tools: Boolean?, val vision: Boolean?, val thinks: Boolean?,
) {
    val perSession: Long get() = if (use.sessions > 0) use.tokens / use.sessions else 0
}

/** `by_task[]`: auxiliary model work (compression, vision, titles…) outside the main turns. */
@Immutable
data class TaskUse(val task: String, val use: Spend, val models: List<String>)

@Immutable
data class ToolUse(val tool: String, val count: Long)

/** `skills.top_skills[]`: [loads] = `skill_view`, [edits] = `skill_manage`. */
@Immutable
data class SkillUse(val skill: String, val loads: Long, val edits: Long, val lastUsedAt: Double) {
    val total: Long get() = loads + edits
}

@Immutable
data class Insights(
    val days: Int,
    val daily: List<DayUse>,
    val totals: Spend,
    val models: List<ModelUse>,
    val tasks: List<TaskUse>,
    val tools: List<ToolUse>,
    val skills: List<SkillUse>,
    /** `insights.get` message count; null when the engine doesn't serve it. */
    val messages: Long?,
)

/** `usage.bars` bar: [fill] is the share still left (0–1); the amounts are server-formatted. */
@Immutable
data class PlanBar(val topUp: Boolean, val left: String, val total: String, val spent: String, val fill: Float)

/** `usage.bars` when a paid plan is signed in (`available:true`); [status] is free, healthy, low or depleted. */
@Immutable
data class Plan(val name: String?, val status: String, val renews: String?, val left: String?, val bars: List<PlanBar>)

private fun q(v: String) = v.encodeURLParameter()

/** `GET /api/analytics/models` (days 1–365). */
suspend fun Hermes.analyticsModels(days: Int, profile: String): JsonObject? =
    rest.get("/api/analytics/models?days=$days&profile=${q(profile)}").obj

/** `insights.get {days, profile}` → `{days, sessions, messages}` (at most 500 sessions are counted). */
suspend fun Hermes.insightsCounts(days: Int, profile: String): JsonObject? =
    gateway.call("insights.get", params("days" to days, "profile" to profile), timeoutMs = 30_000).obj

/** `usage.bars`: account-wide, fail-open (`available:false` when no paid plan is signed in). */
suspend fun Hermes.usageBars(): JsonObject? = gateway.call("usage.bars", timeoutMs = 30_000).obj

/** One bot's period: usage, model cards and the message count, fetched together. Only the usage call is required. */
suspend fun Hermes.insights(days: Int, profile: String): Insights = coroutineScope {
    val models = async { runCatching { analyticsModels(days, profile) }.getOrNull() }
    val counts = async { if ("insights.get" in gateway.unsupported) null else runCatching { insightsCounts(days, profile) }.getOrNull() }
    val usage = usage(days, profile)
    parseInsights(days, usage, models.await(), counts.await())
}

private fun JsonObject?.n(key: String): Long = long(key) ?: 0L
private fun JsonObject?.usd(key: String): Double = double(key) ?: 0.0

private fun JsonObject?.spend() = Spend(n("input_tokens"), n("output_tokens"), n("cache_read_tokens"), n("reasoning_tokens"),
    usd("estimated_cost"), usd("actual_cost"), n("sessions"), n("api_calls"))

private fun JsonObject?.totals() = Spend(n("total_input"), n("total_output"), n("total_cache_read"), n("total_reasoning"),
    usd("total_estimated_cost"), usd("total_actual_cost"), n("total_sessions"), n("total_api_calls"))

private fun JsonObject.toModel(): ModelUse? {
    val name = str("model")?.takeIf { it.isNotBlank() } ?: return null
    val caps = child("capabilities")
    return ModelUse(
        model = name, provider = modelsDebrand(str("provider").orEmpty()), use = spend(), toolCalls = n("tool_calls"),
        lastUsedAt = double("last_used_at") ?: 0.0,
        contextWindow = caps.long("context_window")?.takeIf { it > 0 }, maxOutput = caps.long("max_output_tokens")?.takeIf { it > 0 },
        tools = caps.bool("supports_tools"), vision = caps.bool("supports_vision"), thinks = caps.bool("supports_reasoning"),
    )
}

fun parseInsights(days: Int, usage: JsonObject?, models: JsonObject?, counts: JsonObject?): Insights = Insights(
    days = days,
    daily = usage.list("daily").mapNotNull { e -> e.obj?.let { o -> o.str("day")?.let { DayUse(it, o.spend()) } } }.sortedBy { it.day },
    totals = usage.child("totals").totals(),
    models = (if (models != null) models.list("models") else usage.list("by_model")).mapNotNull { it.obj?.toModel() },
    tasks = usage.list("by_task").mapNotNull { e ->
        e.obj?.let { o ->
            TaskUse(o.str("task").orEmpty(), o.spend(), o.list("models").mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { m -> m.isNotBlank() } })
        }
    },
    tools = usage.list("tools").mapNotNull { e -> e.obj?.let { o -> (o.str("tool") ?: o.str("name"))?.let { ToolUse(it, o.n("count")) } } }
        .filter { it.count > 0 },
    skills = usage.child("skills").list("top_skills").mapNotNull { e ->
        e.obj?.let { o -> o.str("skill")?.let { SkillUse(it, o.n("view_count"), o.n("manage_count"), o.double("last_used_at") ?: 0.0) } }
    },
    messages = counts.long("messages"),
)

fun JsonObject?.toPlan(): Plan? {
    if (bool("available") != true) return null
    val bars = listOfNotNull(child("plan_bar"), child("topup_bar")).map { b ->
        PlanBar(b.str("kind") == "topup", b.str("remaining_display").orEmpty(), b.str("total_display").orEmpty(),
            b.str("spent_display").orEmpty(), (b.double("fill_fraction") ?: 0.0).toFloat().coerceIn(0f, 1f))
    }
    return Plan(str("plan_name")?.takeIf { it.isNotBlank() }, str("status").orEmpty(), str("renews_display")?.takeIf { it.isNotBlank() },
        str("total_spendable_display")?.takeIf { it.isNotBlank() }, bars)
}

/** Several bots' periods as one ("All bots"): counters add up, rows of the same day / model / task / tool / skill merge. */
fun List<Insights>.merged(): Insights? {
    if (size <= 1) return firstOrNull()
    fun List<Spend>.sum() = fold(Spend()) { a, b -> a + b }
    return Insights(
        days = first().days,
        daily = flatMap { it.daily }.groupBy { it.day }.map { (d, l) -> DayUse(d, l.map { it.use }.sum()) }.sortedBy { it.day },
        totals = map { it.totals }.sum(),
        models = flatMap { it.models }.groupBy { it.model to it.provider }.values.map { l ->
            l.reduce { a, b ->
                a.copy(use = a.use + b.use, toolCalls = a.toolCalls + b.toolCalls, lastUsedAt = maxOf(a.lastUsedAt, b.lastUsedAt),
                    contextWindow = a.contextWindow ?: b.contextWindow, maxOutput = a.maxOutput ?: b.maxOutput,
                    tools = a.tools ?: b.tools, vision = a.vision ?: b.vision, thinks = a.thinks ?: b.thinks)
            }
        }.sortedByDescending { it.use.tokens },
        tasks = flatMap { it.tasks }.groupBy { it.task }.map { (t, l) -> TaskUse(t, l.map { it.use }.sum(), l.flatMap { it.models }.distinct()) }
            .sortedByDescending { it.use.tokens },
        tools = flatMap { it.tools }.groupBy { it.tool }.map { (t, l) -> ToolUse(t, l.sumOf { it.count }) }.sortedByDescending { it.count },
        skills = flatMap { it.skills }.groupBy { it.skill }
            .map { (s, l) -> SkillUse(s, l.sumOf { it.loads }, l.sumOf { it.edits }, l.maxOf { it.lastUsedAt }) }
            .sortedWith(compareByDescending<SkillUse> { it.total }.thenByDescending { it.lastUsedAt }),
        messages = mapNotNull { it.messages }.takeIf { it.isNotEmpty() }?.sum(),
    )
}

// ---- calendar days (no java.time in commonMain)

/** `YYYY-MM-DD` → days since 1970-01-01; null when malformed. */
fun epochDay(iso: String): Long? {
    val m = Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(iso) ?: return null
    val (y0, mo, d) = m.destructured.toList().map { it.toInt() }
    val y = if (mo <= 2) y0 - 1 else y0
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (mo + (if (mo > 2) -3 else 9)) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468
}

/** Days since 1970-01-01 → `YYYY-MM-DD`. */
fun isoDay(epochDay: Long): String {
    val z = epochDay + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + if (m <= 2) 1 else 0
    return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}

/**
 * One bar per calendar day, oldest first: the last [Insights.days] days up to [today] (epoch day), zero where nothing ran.
 * Widened to any day the server returned outside that window (its cutoff is `now - days·24h`, and the PC's clock may lead).
 */
fun Insights.series(today: Long): List<DayUse> {
    val byDay = daily.associateBy { it.day }
    val known = daily.mapNotNull { epochDay(it.day) }
    val from = minOf(today - (days - 1), known.minOrNull() ?: today)
    val to = maxOf(today, known.maxOrNull() ?: today)
    return (from..to).map { d -> isoDay(d).let { byDay[it] ?: DayUse(it, Spend()) } }
}
