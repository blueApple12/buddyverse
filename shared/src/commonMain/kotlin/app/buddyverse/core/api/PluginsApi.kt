package app.buddyverse.core.api

// Owner: More ▸ Plugins (ui/more/PluginsPage.kt, ui/more/plugins). hermes-api.md §2.5 "Plugins and plugin catalog" and §2.6 "Plugins".
// Every call is WS legacy `plugins.manage` (or `plugins.catalog.lookup`) scoped to one bot's profile, plus `PUT /api/env` for secrets.

import app.buddyverse.core.Hermes
import app.buddyverse.core.RestError
import app.buddyverse.core.WireJson
import app.buddyverse.core.arr
import app.buddyverse.core.bool
import app.buddyverse.core.child
import app.buddyverse.core.list
import app.buddyverse.core.long
import app.buddyverse.core.nowMillis
import app.buddyverse.core.obj
import app.buddyverse.core.params
import app.buddyverse.core.platformHttpClient
import app.buddyverse.core.str
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun strings(o: JsonObject?, key: String): List<String> = o.list(key).mapNotNull { (it as? JsonPrimitive)?.content }

/** `servers[]` of a row: an app-backed MCP server and why it is not connected (`sentence`). */
data class PluginServer(val name: String, val state: String, val sentence: String)

/** One manifest `config_schema` key. Secrets carry no value: [env] names the `.env` variable, [hasValue] whether it is set. */
data class PluginField(
    val key: String, val type: String, val label: String, val description: String, val required: Boolean,
    val value: JsonElement?, val default: JsonElement?, val choices: List<String>, val env: String?, val hasValue: Boolean,
)

/** One `plugins.manage list` row (`AgentPluginRow`). */
data class PluginRow(
    val name: String,
    /** Canonical key (`image_gen/fal`); toggles and settings are addressed by it. Blank on old engines = read-only switch. */
    val key: String,
    val version: String,
    val description: String,
    /** bundled · user · git · project · entrypoint */
    val source: String,
    /** enabled · disabled · not enabled */
    val status: String,
    val catalogName: String?,
    val tier: String?,
    val updateAvailable: Boolean,
    /** What Update moves to: the catalog version, its short pin, or a git target. */
    val updateTo: String?,
    val settings: List<PluginField>,
    val servers: List<PluginServer>,
    val tools: List<String>,
    val portable: Boolean,
    val hasDesktopHalf: Boolean,
) {
    val on: Boolean get() = status == "enabled"
    val id: String get() = key.ifBlank { name }
    val toggleable: Boolean get() = key.isNotBlank()
    /** Only what lives in the bot's own plugins folder can be removed; bundled ones are refused, pip ones go with their package. */
    val removable: Boolean get() = source == "user" || source == "git"

    /**
     * The rows the page lists (the Desktop's curation, `isDesktopRelevantPlugin`): what the user installed, plus the bundled
     * plugins that use the plain on/off contract. Other built-ins (platforms, providers, sign-in) are set up on their own pages;
     * switching e.g. a sign-in provider off here could lock the phone out.
     */
    val listed: Boolean get() =
        if (source == "bundled") id in MANAGEABLE_BUNDLED else HIDDEN_PREFIXES.none { key.startsWith(it) }

    companion object {
        private val HIDDEN_PREFIXES = listOf("dashboard_auth/", "model-providers/", "platforms/")
        private val MANAGEABLE_BUNDLED = setOf("disk-cleanup", "security-guidance")
        private val SHA = Regex("^[0-9a-f]{40}$", RegexOption.IGNORE_CASE)

        fun from(o: JsonObject): PluginRow? {
            val name = o.str("name") ?: return null
            val target = o.str("update_target")
            return PluginRow(
                name = name, key = o.str("key").orEmpty(), version = o.str("version").orEmpty(), description = o.str("description").orEmpty(),
                source = o.str("source").orEmpty(), status = o.str("status").orEmpty(),
                catalogName = o.str("catalog_name")?.ifBlank { null }, tier = o.str("catalog_tier")?.ifBlank { null },
                updateAvailable = o.bool("update_available") == true,
                updateTo = o.str("catalog_version") ?: o.str("catalog_sha")?.take(8) ?: target?.let { if (SHA.matches(it)) it.take(8) else it },
                settings = o.list("settings_schema").mapNotNull { it.obj?.let(::fieldOf) },
                servers = o.list("servers").mapNotNull { it.obj }.map { PluginServer(it.str("name").orEmpty(), it.str("state").orEmpty(), it.str("sentence").orEmpty()) },
                tools = strings(o, "tools"),
                portable = o.bool("portable") == true, hasDesktopHalf = o.bool("has_desktop_half") == true,
            )
        }

        private fun fieldOf(o: JsonObject): PluginField? = PluginField(
            key = o.str("key") ?: return null, type = o.str("type") ?: "string", label = o.str("label") ?: o.str("key").orEmpty(),
            description = o.str("description").orEmpty(), required = o.bool("required") == true,
            value = o["value"]?.takeUnless { it is JsonNull }, default = o["default"]?.takeUnless { it is JsonNull },
            choices = strings(o, "choices"), env = o.str("env"), hasValue = o.bool("has_value") == true,
        )
    }
}

/** One entry of the public plugin catalog snapshot (the same feed the Desktop's catalog browser reads). */
data class CatalogPlugin(
    val name: String, val description: String,
    /** official · verified · community */
    val tier: String,
    val category: String, val maintainer: String, val version: String, val stars: Long?,
    val tools: List<String>, val requiresEnv: List<String>, val platforms: List<String>,
    val repo: String?, val docsUrl: String?,
) {
    /** Lower-cased text the search box matches. */
    val search: String = listOf(name, description, maintainer, category, tools.joinToString(" "), requiresEnv.joinToString(" ")).joinToString(" ").lowercase()

    companion object {
        fun from(o: JsonObject): CatalogPlugin? {
            val name = o.str("name")?.takeIf { n -> n.any { it.isLetterOrDigit() } } ?: return null
            val caps = o.child("capabilities")
            return CatalogPlugin(
                name = name, description = o.str("description").orEmpty(), tier = o.str("tier") ?: "community",
                category = o.str("category")?.ifBlank { null } ?: "general", maintainer = o.str("maintainer").orEmpty(),
                version = o.str("version").orEmpty(), stars = o.long("stars"),
                tools = strings(caps, "providesTools"), requiresEnv = strings(caps, "requiresEnv"), platforms = strings(o, "platforms"),
                repo = o.str("repo")?.takeIf { it.startsWith("https://") }, docsUrl = o.str("docsUrl")?.takeIf { it.startsWith("https://") },
            )
        }
    }
}

/** A curated catalog plugin this PC can run (`plugins.manage onboarding`); [sentence] names what is missing ("needs NVIDIA App"). */
data class SuggestedPlugin(val name: String, val title: String, val description: String, val tier: String, val sentence: String)

private suspend fun Hermes.plugins(profile: String, action: String, vararg p: Pair<String, Any?>, timeoutMs: Long = 30_000): JsonObject =
    gateway.call("plugins.manage", params("action" to action, "profile" to profile, *p), timeoutMs).obj ?: JsonObject(emptyMap())

/** `ok:false` with an `error` (toggle, install, remove, settings) becomes an exception carrying the engine's reason. */
private fun JsonObject.orFail(): JsonObject {
    if (bool("ok") == false) throw IllegalStateException(str("error") ?: str("message") ?: "failed")
    return this
}

/** Installs clone a repo and resolve Python dependencies: minutes, not the usual 30 s (hermes-api.md §2.6 Plugins, Notes). */
const val PLUGIN_INSTALL_TIMEOUT_MS = 240_000L

/** `{plugins: AgentPluginRow[], user_count, bundled_count}`. */
suspend fun Hermes.pluginList(profile: String): List<PluginRow> =
    plugins(profile, "list").list("plugins").mapNotNull { it.obj?.let(PluginRow::from) }

/** `{ok, unchanged, restart_required, gateway_reloaded, activation, name, plugin}`; addressed by canonical key only (names collide). */
suspend fun Hermes.pluginToggle(profile: String, key: String, enable: Boolean): JsonObject =
    plugins(profile, "toggle", "key" to key, "enable" to enable).orFail()

/**
 * `install {identifier, catalog_name?, enable}` → `{plugin_name, enabled, warnings, missing_env, python_dependencies, known_issues,
 * after_install_path, gateway_reloaded?, activation?}`. A catalog install passes its name as both; a link install only the identifier.
 */
suspend fun Hermes.pluginInstall(profile: String, identifier: String, catalogName: String?, force: Boolean = false): JsonObject =
    plugins(profile, "install", "identifier" to identifier, "catalog_name" to catalogName, "enable" to true, "force" to force,
        timeoutMs = PLUGIN_INSTALL_TIMEOUT_MS).orFail()

/** `{ok, unchanged, sha}`, or `ok:false` + `consent_required`/`delta_lines`, or `ok:false` + `scan_blocked`/`scan_verdict`/`scan_findings`. */
suspend fun Hermes.pluginUpdate(profile: String, name: String, acceptCapabilities: Boolean, force: Boolean): JsonObject =
    plugins(profile, "update", "name" to name, "accept_capabilities" to acceptCapabilities.takeIf { it }, "force" to force.takeIf { it },
        timeoutMs = PLUGIN_INSTALL_TIMEOUT_MS)

/** User installs only → `{ok, name, cleared_memory_provider?}`. */
suspend fun Hermes.pluginRemove(profile: String, name: String): JsonObject = plugins(profile, "remove", "name" to name).orFail()

/** Non-secret `config_schema` values, already typed → `{ok, name, written[], plugin}`. */
suspend fun Hermes.pluginSettings(profile: String, key: String, values: JsonObject): JsonObject =
    plugins(profile, "settings", "key" to key, "values" to values).orFail()

suspend fun Hermes.pluginSuggestions(profile: String): List<SuggestedPlugin> =
    plugins(profile, "onboarding").list("onboarding").mapNotNull { it.obj }.mapNotNull { o ->
        val name = o.str("name") ?: return@mapNotNull null
        SuggestedPlugin(name, o.str("title") ?: name, o.str("description").orEmpty(), o.str("tier").orEmpty(), o.str("sentence").orEmpty())
    }

/** `presence {names}` → name → `{state, sentence}` (what this PC lacks to run a catalog plugin, e.g. its app). */
suspend fun Hermes.pluginPresence(profile: String, names: List<String>): Map<String, JsonObject> =
    plugins(profile, "presence", "names" to names).list("presence").mapNotNull { it.obj }.associateBy { it.str("name").orEmpty() }

/** `plugins.catalog.lookup` → `{status: found|invalid_name|unknown|unavailable, entry?}`: resolves a name the user typed. */
suspend fun Hermes.pluginCatalogLookup(profile: String, name: String): JsonObject =
    gateway.call("plugins.catalog.lookup", params("name" to name, "profile" to profile)).obj ?: JsonObject(emptyMap())

/** A secret setting or a missing key: `PUT /api/env {key, value, profile}` (never through `plugins.manage`). */
suspend fun Hermes.setEnvKey(profile: String, key: String, value: String): JsonElement =
    rest.put("/api/env?profile=${profile.encodeURLParameter()}", params("key" to key, "value" to value, "profile" to profile))

// ---- the public catalog snapshot (no engine endpoint lists the catalog; the Desktop reads this same file)

private const val CATALOG_URL = "https://nousresearch.github.io/hermes-agent/docs/api/plugins.json"
private val catalogHttp by lazy { platformHttpClient() }
private var catalogCache: Pair<Long, List<CatalogPlugin>>? = null

/**
 * The catalog (~850 entries, 1.5 MB), cached in memory for 10 minutes like its `Cache-Control`. Entries in the `desktop` category
 * are renderer add-ons of the Desktop app: this app can't run them, so they are left out.
 */
suspend fun pluginCatalog(): List<CatalogPlugin> {
    catalogCache?.let { (at, rows) -> if (nowMillis() - at < 10 * 60_000) return rows }
    // The shared client never times out reads (long engine calls need that): bound this download here.
    val rows = withTimeoutOrNull(90_000) {
        val r = catalogHttp.get(CATALOG_URL)
        if (!r.status.isSuccess()) throw RestError(r.status.value, "catalog")
        withContext(Dispatchers.Default) {
            WireJson.parseToJsonElement(r.bodyAsText()).arr.orEmpty().mapNotNull { it.obj?.let(CatalogPlugin::from) }
                .filter { it.category != "desktop" }.distinctBy { it.name }
        }
    } ?: throw IllegalStateException("catalog timed out")
    catalogCache = nowMillis() to rows
    return rows
}
