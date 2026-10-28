package app.buddyverse.core.api

// Owner: Bot profile & avatar studio (ui/bot). Extra Hermes calls and store intents for this area go here as extension functions
// (`suspend fun Hermes.x(...)`, `fun AppStore.y(...)`), never into Hermes.kt or AppStore.kt.

import androidx.compose.runtime.Immutable
import app.buddyverse.core.Hermes
import app.buddyverse.core.bool
import app.buddyverse.core.child
import app.buddyverse.core.list
import app.buddyverse.core.obj
import app.buddyverse.core.params
import app.buddyverse.core.str
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The `ui_meta` namespace that holds a bot's title and look (a wire key only, never shown). */
private const val LOOK_NS = "hermes-bots"

/** One skill, toolset or MCP server of a bot and whether it is on. [note] = toolset tools / MCP transport. */
@Immutable
data class BotCap(val name: String, val label: String, val note: String?, val enabled: Boolean)

/** A bot's persona, model and capabilities (`profiles.describe`, hermes-api.md §2.4 SOUL.md / Per-bot skills / Model per bot). */
@Immutable
data class BotDetails(
    val soul: String,
    val model: String?,
    val provider: String?,
    val skills: List<BotCap>,
    val toolsets: List<BotCap>,
    val mcp: List<BotCap>,
    /** Tools pinned to a composite preset: the per-toolset switches can't express it, so they are read-only here. */
    val toolsPreset: Boolean = false,
)

/** One pickable model of a signed-in provider (`model.options`). */
@Immutable
data class ModelChoice(val provider: String, val providerName: String, val model: String)

suspend fun Hermes.describeBot(name: String): BotDetails {
    val o = gateway.call("profiles.describe", params("name" to name)).obj
    val m = o.child("model")
    var toolsets = caps(o, "toolsets") { it.str("description") }
    // Pinned to a composite set (e.g. `hermes-cli`) reads as "0 on" here though every tool works: take the real state from
    // the same source as More > Tools, or show all on. Never let that state turn one switch into a one-item pin.
    val preset = o.bool("toolsets_pinned") == true && toolsets.isNotEmpty() && toolsets.none { it.enabled }
    if (preset) {
        val live = runCatching { toolsets(name).associate { it.str("name").orEmpty() to (it.bool("enabled") == true) } }.getOrDefault(emptyMap())
        toolsets = toolsets.map { it.copy(enabled = live[it.name] ?: true) }.let { l -> if (l.none { it.enabled }) l.map { it.copy(enabled = true) } else l }
    }
    return BotDetails(
        soul = o.str("soul").orEmpty(),
        model = m.str("default")?.takeIf { it.isNotBlank() },
        provider = m.str("provider")?.takeIf { it.isNotBlank() },
        skills = caps(o, "skills") { null },
        toolsets = toolsets,
        mcp = caps(o, "mcp_servers") { it.str("transport") },
        toolsPreset = preset,
    )
}

private fun caps(o: JsonObject?, key: String, note: (JsonObject) -> String?): List<BotCap> = o.list(key).mapNotNull { e ->
    val c = e.obj ?: return@mapNotNull null
    val name = c.str("name") ?: return@mapNotNull null
    BotCap(name, c.str("label")?.takeIf { it.isNotBlank() } ?: name, note(c)?.takeIf { it.isNotBlank() }, c.bool("enabled") != false)
}.distinctBy { it.name } // list keys must be unique

/** `profiles.configure` that says whether everything sent was applied (`ok` = all(applied), hermes-api.md §2.4 Edit bot). */
suspend fun Hermes.configureBot(name: String, fields: Map<String, Any?>): Boolean = configureProfile(name, fields).obj.bool("ok") != false

/**
 * First look (and persona) of a profile made a moment ago (§2.4 Create a bot, step 4). The server has no look to merge with
 * yet, so no CAS read; later edits go through [saveBotMeta]'s merge.
 */
suspend fun Hermes.initBot(name: String, look: Map<String, Any?>, soul: String? = null): Boolean =
    configureBot(name, mapOf("ui_meta" to mapOf(LOOK_NS to look), "soul" to soul))

/** An LLM writes the bot's role and the server keeps it (`POST /api/profiles/{name}/describe-auto`, §2.4 Edit bot); null = nothing written. */
suspend fun Hermes.autoDescribe(name: String): String? =
    rest.post("/api/profiles/${name.encodeURLParameter()}/describe-auto", params("overwrite" to true)).obj.str("description")?.takeIf { it.isNotBlank() }

/** Models of every signed-in provider, the bot's current provider first (`model.options` routed to the bot, hermes-api.md §2.4). */
suspend fun Hermes.modelChoices(profile: String): List<ModelChoice> {
    val o = gateway.call("model.options", params("profile" to profile, "include_unconfigured" to true, "explicit_only" to false)).obj
    val current = o.str("provider")
    return o.list("providers").mapNotNull { it.obj }
        // ponytail: brand rule (no Nous/Hermes names in the UI) hides the engine vendor's own portal; other providers show their name.
        .filter { it.bool("authenticated") == true && it.str("slug") != "nous" }
        .sortedByDescending { it.str("slug") == current }
        .flatMap { p ->
            val slug = p.str("slug") ?: return@flatMap emptyList()
            val label = p.str("name")?.takeIf { it.isNotBlank() } ?: slug
            p.list("models").mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let { m -> ModelChoice(slug, label, m) } }
        }
        .distinct() // list keys must be unique
}

/**
 * Pins the bot's model (`profiles.configure {model, provider}`). Returns null when written; when the model is guarded the server
 * writes nothing and this returns its warning: call again with [confirmed] after the user agrees.
 */
suspend fun Hermes.setBotModel(name: String, model: String, provider: String, confirmed: Boolean = false): String? {
    val r = configureProfile(name, mapOf("model" to model, "provider" to provider, "confirm_expensive_model" to if (confirmed) true else null)).obj
    if (r.bool("confirm_required") == true) return r.str("confirm_message") ?: ""
    if (r.child("applied").bool("model") == false) error(r.str("error") ?: "model not set")
    return null
}

/** Clone [base] (config, skills, persona, memory) into a new profile [name] (hermes-api.md §2.4 Duplicate). */
suspend fun Hermes.duplicateBot(base: String, name: String, description: String?) {
    gateway.call("profiles.create", params("name" to name, "clone_from" to base, "description" to description), timeoutMs = 120_000)
}

/** Renames the profile id (`PATCH /api/profiles/{name}`); sessions move with it. Returns the normalized new name. */
suspend fun Hermes.renameBot(name: String, newName: String): String =
    rest.patch("/api/profiles/${name.encodeURLParameter()}", params("new_name" to newName)).obj.str("name") ?: newName
