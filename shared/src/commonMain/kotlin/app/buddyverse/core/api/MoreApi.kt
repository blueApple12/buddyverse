package app.buddyverse.core.api

// Owner: More hub (ui/more/MoreScreen.kt). Extra Hermes calls and store intents for this area go here as extension functions
// (`suspend fun Hermes.x(...)`, `fun AppStore.y(...)`), never into Hermes.kt or AppStore.kt.

import app.buddyverse.core.Hermes
import app.buddyverse.core.bool
import app.buddyverse.core.child
import app.buddyverse.core.list
import app.buddyverse.core.long
import app.buddyverse.core.obj
import app.buddyverse.core.params
import app.buddyverse.core.str
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The host gateway as the engine card shows it (`GET /api/status`, hermes-api.md §2.6 Gateway status). */
data class Engine(
    val running: Boolean,
    /** `gateway_state`: running, draining, stopped, fatal, startup_failed… */
    val state: String,
    /** Gateway boot time, ISO (`memory.boot_id` = the sentinel's `started_at`), for the uptime. */
    val sinceIso: String?,
    val activeAgents: Int,
    /** `overall == "ok"`. */
    val healthy: Boolean,
    val platformsConnected: Int,
    val platformsConfigured: Int,
    /** Nothing on the PC status page's "needs a look" list (same rule, so the tile and the page agree). */
    val allGood: Boolean = healthy,
)

fun JsonObject.toEngine(): Engine {
    val platforms = child("components").child("platforms")
    return Engine(
        running = bool("gateway_running") == true,
        state = str("gateway_state").orEmpty(),
        sinceIso = child("memory").str("boot_id"),
        activeAgents = (long("active_agents") ?: 0L).toInt(),
        healthy = str("overall") == "ok",
        platformsConnected = (platforms.long("connected") ?: 0L).toInt(),
        platformsConfigured = (platforms.long("configured") ?: 0L).toInt(),
    )
}

/** `POST /api/gateway/start` (no profile = the host gateway that serves every bot). Spawns `hermes gateway start`; poll [Hermes.status]. */
suspend fun Hermes.engineStart(): JsonElement = rest.post("/api/gateway/start")

/** `POST /api/gateway/stop`. Throws `RestError(409)` while live sessions exist unless [interrupt]. */
suspend fun Hermes.engineStop(interrupt: Boolean): JsonElement = rest.post("/api/gateway/stop?interrupt=$interrupt", timeoutMs = 120_000)

// ---- tile counts (one cheap list call each; the hub shows nothing for a tile whose call fails)

suspend fun Hermes.skillsOn(profile: String): Int = skills(profile).count { it.bool("enabled") != false }
suspend fun Hermes.toolsetsOn(profile: String): Int = toolsets(profile).count { it.bool("enabled") == true }
suspend fun Hermes.mcpCount(profile: String): Int = mcpServers(profile).list("servers").size
suspend fun Hermes.projectsCount(profile: String): Int = projects(profile).list("projects").count { it.obj.bool("archived") != true }
suspend fun Hermes.webhooksCount(profile: String): Int =
    rest.get("/api/webhooks?profile=${profile.encodeURLParameter()}").obj.list("subscriptions").size
suspend fun Hermes.pluginsOn(profile: String): Int =
    gateway.call("plugins.manage", params("action" to "list", "profile" to profile)).obj.list("plugins").mapNotNull { it.obj?.let(PluginRow::from) }.count { it.listed && it.on }
