package app.buddyverse.core.api

import androidx.compose.runtime.Immutable
import app.buddyverse.core.Hermes
import app.buddyverse.core.bool
import app.buddyverse.core.list
import app.buddyverse.core.long
import app.buddyverse.core.obj
import app.buddyverse.core.params
import app.buddyverse.core.str
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Owner: More ▸ Tools (ui/more/ToolsPage.kt, ui/more/tools). hermes-api.md §2.6 "Tools and toolsets": plain REST under
// /api/tools, every call scoped to a profile (query and body). Shapes checked against the live gateway 2026-10-26.

private fun q(v: String) = v.encodeURLParameter()
private fun texts(e: List<JsonElement>) = e.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
private fun base(name: String) = "/api/tools/toolsets/${q(name)}"

/** One `GET /api/tools/toolsets` row. The server's `available` just mirrors `enabled`, so it isn't kept. */
@Immutable
data class Toolset(
    val name: String,
    val label: String,
    val description: String,
    val platform: String,
    val platformLabel: String,
    val enabled: Boolean,
    val configured: Boolean,
    val tools: List<String>,
) {
    companion object {
        fun from(o: JsonObject?): Toolset? {
            val name = o.str("name") ?: return null
            return Toolset(
                // The engine's labels lead with an emoji ("🔍 Web Search…"); the row has its own icon tile.
                name = name, label = o.str("label")?.trimStart { !it.isLetterOrDigit() }?.takeIf { it.isNotBlank() } ?: name,
                description = o.str("description").orEmpty(),
                platform = o.str("platform") ?: "cli", platformLabel = o.str("platform_label").orEmpty(),
                enabled = o.bool("enabled") == true, configured = o.bool("configured") != false, tools = texts(o.list("tools")),
            )
        }
    }
}

@Immutable
data class ToolEnvVar(val key: String, val prompt: String, val url: String?, val isSet: Boolean)

/** A `ToolProvider` row of a toolset's config. [status]: `ready` · `needs_keys` · `needs_setup` · `needs_auth`. */
@Immutable
data class ToolProvider(
    val name: String,
    val badge: String,
    val tag: String,
    val envVars: List<ToolEnvVar>,
    val postSetup: String?,
    /** Billed through the engine vendor's own subscription: the UI hides these rows (no vendor branding; no sign-in from here). */
    val vendorManaged: Boolean,
    val active: Boolean,
    val status: String,
    val webBackend: String?,
    val capabilities: List<String>,
) {
    val ready: Boolean get() = status == "ready"

    companion object {
        fun from(o: JsonObject?): ToolProvider? = ToolProvider(
            name = o.str("name") ?: return null,
            badge = o.str("badge").orEmpty(),
            tag = o.str("tag").orEmpty(),
            envVars = o.list("env_vars").mapNotNull { e ->
                val v = e.obj
                ToolEnvVar(v.str("key") ?: return@mapNotNull null, v.str("prompt") ?: v.str("key")!!, v.str("url")?.takeIf { it.isNotBlank() }, v.bool("is_set") == true)
            },
            postSetup = o.str("post_setup")?.takeIf { it.isNotBlank() },
            vendorManaged = o.bool("requires_nous_auth") == true || o.str("managed_nous_feature") != null,
            active = o.bool("is_active") == true,
            status = o.str("status") ?: "ready",
            webBackend = o.str("web_backend"),
            capabilities = texts(o.list("capabilities")),
        )
    }
}

/** `GET /api/tools/toolsets/{name}/config`. [activeSearch]/[activeExtract] are web-only backend keys. */
@Immutable
data class ToolsetConfig(
    val hasCategory: Boolean,
    val providers: List<ToolProvider>,
    val activeProvider: String?,
    val activeSearch: String?,
    val activeExtract: String?,
) {
    companion object {
        fun from(o: JsonObject?) = ToolsetConfig(
            hasCategory = o.bool("has_category") == true,
            providers = o.list("providers").mapNotNull { ToolProvider.from(it.obj) },
            activeProvider = o.str("active_provider"),
            activeSearch = o.str("active_search_backend"),
            activeExtract = o.str("active_extract_backend"),
        )
    }
}

@Immutable
data class ToolModel(val id: String, val display: String, val speed: String, val strengths: String, val price: String)

/** `GET /api/tools/toolsets/{name}/models` (image_gen, video_gen); empty [models] = no catalog. */
@Immutable
data class ToolModels(val provider: String?, val models: List<ToolModel>, val current: String?)

/** `GET /api/tools/terminal/backends` row. [status]: `ready` · `needs_setup` · `unavailable`. */
@Immutable
data class TerminalBackend(val name: String, val label: String, val description: String, val active: Boolean, val status: String, val detail: String)

@Immutable
data class ComputerUseCheck(val label: String, val status: String, val message: String)

@Immutable
data class ComputerUseStatus(val ready: Boolean, val canGrant: Boolean, val version: String?, val checks: List<ComputerUseCheck>, val error: String?)

/** `GET /api/actions/tools-post-setup/status`: the install running on the PC. */
@Immutable
data class SetupStatus(val running: Boolean, val exitCode: Long?, val lines: List<String>)

/** Toolset names that carry a model catalog. */
val MODEL_TOOLSETS = setOf("image_gen", "video_gen")

suspend fun Hermes.toolsetList(profile: String): List<Toolset> = toolsets(profile).mapNotNull { Toolset.from(it) }

/** Turns a toolset on or off; returns the post-setup key the server started installing on enable, if any. */
suspend fun Hermes.setToolset(name: String, enabled: Boolean, profile: String): String? =
    toggleToolset(name, enabled, profile).obj.str("post_setup_started")

suspend fun Hermes.toolsetConfig(name: String, profile: String): ToolsetConfig =
    ToolsetConfig.from(rest.get("${base(name)}/config?profile=${q(profile)}").obj)

/** [capability] (`search` | `extract`) is web-only; without it web writes the legacy whole-provider key. */
suspend fun Hermes.selectToolProvider(name: String, provider: String, profile: String, capability: String? = null): JsonObject? =
    rest.put("${base(name)}/provider?profile=${q(profile)}", params("provider" to provider, "capability" to capability, "profile" to profile)).obj

/** Saves API keys to the PC's `.env`; only keys listed by the toolset's providers are accepted. Blank = unchanged. */
suspend fun Hermes.saveToolKeys(name: String, env: Map<String, String>, profile: String): JsonObject? =
    rest.put("${base(name)}/env?profile=${q(profile)}", params("env" to env, "profile" to profile)).obj

/** [provider] names a provider row; null = the active one. */
suspend fun Hermes.toolModels(name: String, provider: String?, profile: String): ToolModels {
    val o = rest.get("${base(name)}/models?profile=${q(profile)}" + (provider?.let { "&provider=${q(it)}" } ?: "")).obj
    return ToolModels(
        provider = o.str("provider"),
        models = o.list("models").mapNotNull { e ->
            val m = e.obj
            val id = m.str("id") ?: return@mapNotNull null
            ToolModel(id, m.str("display")?.takeIf { it.isNotBlank() } ?: id, m.str("speed").orEmpty(), m.str("strengths").orEmpty(), m.str("price").orEmpty())
        },
        current = o.str("current"),
    )
}

suspend fun Hermes.selectToolModel(name: String, model: String, provider: String?, profile: String): JsonObject? =
    rest.put("${base(name)}/model?profile=${q(profile)}", params("model" to model, "provider" to provider, "profile" to profile)).obj

/** Spawns the provider's install on the PC (`post_setup` key); follow it with [toolSetupStatus]. */
suspend fun Hermes.runToolSetup(name: String, key: String, profile: String): JsonObject? =
    rest.post("${base(name)}/post-setup?profile=${q(profile)}", params("key" to key, "profile" to profile)).obj

suspend fun Hermes.toolSetupStatus(lines: Int = 40): SetupStatus {
    val o = rest.get("/api/actions/tools-post-setup/status?lines=$lines").obj
    return SetupStatus(o.bool("running") == true, o.long("exit_code"), texts(o.list("lines")))
}

suspend fun Hermes.terminalBackends(profile: String): List<TerminalBackend> =
    rest.get("/api/tools/terminal/backends?profile=${q(profile)}").obj.list("backends").mapNotNull { e ->
        val b = e.obj
        val name = b.str("name") ?: return@mapNotNull null
        TerminalBackend(name, b.str("label") ?: name, b.str("description").orEmpty(), b.bool("active") == true, b.str("status") ?: "ready", b.str("detail").orEmpty())
    }

suspend fun Hermes.selectTerminalBackend(backend: String, profile: String): JsonObject? =
    rest.put("/api/tools/terminal/backend?profile=${q(profile)}", params("backend" to backend, "profile" to profile)).obj

suspend fun Hermes.computerUseStatus(profile: String): ComputerUseStatus {
    val o = rest.get("/api/tools/computer-use/status?profile=${q(profile)}").obj
    return ComputerUseStatus(
        ready = o.bool("ready") == true,
        canGrant = o.bool("can_grant") == true,
        version = o.str("version"),
        checks = o.list("checks").mapNotNull { e -> e.obj?.let { ComputerUseCheck(it.str("label").orEmpty(), it.str("status").orEmpty(), it.str("message").orEmpty()) } },
        error = o.str("error")?.takeIf { it.isNotBlank() },
    )
}

/** macOS only (`can_grant`); 400 elsewhere. */
suspend fun Hermes.grantComputerUse(): JsonObject? = rest.post("/api/tools/computer-use/permissions/grant").obj

/** Calls per tool (usage analytics, the Desktop's per-tool badges). */
suspend fun Hermes.toolCallCounts(profile: String, days: Int = 365): Map<String, Long> =
    usage(days, profile).list("tools").mapNotNull { e ->
        val o = e.obj
        (o.str("tool") ?: o.str("name"))?.let { it to (o.long("count") ?: 0L) }
    }.toMap()
