package app.buddyverse.core.api

import androidx.compose.runtime.Immutable
import app.buddyverse.core.Hermes
import app.buddyverse.core.bool
import app.buddyverse.core.child
import app.buddyverse.core.list
import app.buddyverse.core.long
import app.buddyverse.core.obj
import app.buddyverse.core.params
import app.buddyverse.core.str
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Owner: More ▸ Models (ui/more/ModelsPage.kt, ui/more/models). hermes-api.md §2.3 Model picker, Providers (OAuth, keys,
// custom endpoints), Keys/.env, Global model assignment, Local models. REST only; every profile-scoped call names the
// bot (query and, on writes, body). Shapes checked against the live gateway and its source 2026-10-26.

private fun q(v: String) = v.encodeURLParameter()
private fun texts(e: List<JsonElement>) = e.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/** Vendor names that never reach the UI (the user's own app): rows naming them are hidden, free text is reworded. */
private val VENDOR = Regex("""\bnous(?:\s+portal)?\b""", RegexOption.IGNORE_CASE)
private val ENGINE = Regex("""\bHermes(?: Agent)?\b""", RegexOption.IGNORE_CASE)

/** Server free text (descriptions, errors, job steps) without the engine's brand. */
fun modelsDebrand(text: String): String = ENGINE.replace(text, "the engine").replace(VENDOR, "the portal")

private fun isVendorRow(vararg fields: String?): Boolean = fields.any { it != null && VENDOR.containsMatchIn(it) }

/** design §9.5: Western digits; 1000000 → "1M", 262144 → "256K". */
fun modelTokens(n: Long): String = when {
    n <= 0 -> ""
    n >= 1_000_000 && n % 1_000_000 == 0L -> "${n / 1_000_000}M"
    n >= 1_000_000 -> "${(n / 100_000) / 10.0}M"
    n >= 1024 && n % 1024 == 0L -> "${n / 1024}K"
    n >= 1000 -> "${n / 1000}K"
    else -> "$n"
}

/** Reasoning levels, ascending; `none` = thinking off (hermes-api.md §2.3 Reasoning effort). */
val MODEL_REASONING_LEVELS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

@Immutable
data class PickedModel(val provider: String, val model: String)

/** `GET /api/model/info`. */
@Immutable
data class DefaultModelInfo(val model: String, val provider: String, val autoContext: Long, val configContext: Long, val effectiveContext: Long, val vision: Boolean)

@Immutable
data class QuotaWindow(val label: String, val usedPercent: Int, val resetsAt: String?)

/** One `model.options` provider row (REST twin `GET /api/model/options`). */
@Immutable
data class ProviderRow(
    val slug: String,
    val name: String,
    val authenticated: Boolean,
    val current: Boolean,
    /** `api_key` · `oauth_external` · `oauth_minimax` · `vertex` · `aws_sdk` … */
    val authType: String,
    val keyEnv: String?,
    val models: List<String>,
    val totalModels: Int,
    val userDefined: Boolean,
    val fast: Set<String>,
    val ultrafast: Set<String>,
    val prices: Map<String, String>,
    val usage: List<QuotaWindow>,
) {
    companion object {
        fun from(o: JsonObject?): ProviderRow? {
            val slug = o.str("slug") ?: return null
            val caps = o.child("capabilities")
            val pricing = o.child("pricing")
            return ProviderRow(
                slug = slug, name = o.str("name")?.takeIf { it.isNotBlank() } ?: slug,
                authenticated = o.bool("authenticated") == true, current = o.bool("is_current") == true,
                authType = o.str("auth_type").orEmpty(), keyEnv = o.str("key_env")?.takeIf { it.isNotBlank() },
                models = texts(o.list("models")), totalModels = (o.long("total_models") ?: 0L).toInt(),
                userDefined = o.bool("is_user_defined") == true,
                fast = caps?.filterValues { it.obj.bool("fast") == true }?.keys.orEmpty(),
                ultrafast = caps?.filterValues { it.obj.bool("ultrafast") == true }?.keys.orEmpty(),
                prices = pricing?.mapNotNull { (m, v) ->
                    val p = v.obj
                    val label = when {
                        p.bool("free") == true -> "free"
                        !p.str("input").isNullOrBlank() && !p.str("output").isNullOrBlank() -> "${p.str("input")} · ${p.str("output")}"
                        else -> null
                    }
                    label?.let { m to it }
                }?.toMap().orEmpty(),
                usage = o.child("usage").list("windows").mapNotNull { w ->
                    val x = w.obj
                    QuotaWindow(x.str("label") ?: return@mapNotNull null, (x.long("used_percent") ?: 0L).toInt().coerceIn(0, 100), x.str("resets_at"))
                },
            )
        }
    }
}

@Immutable
data class ProviderCatalog(val providers: List<ProviderRow>, val current: PickedModel?)

/** `GET /api/providers/oauth` row. [flow]: `device_code` (sign in from the phone) or `external` (a command on the PC). */
@Immutable
data class ProviderLogin(
    val id: String,
    val name: String,
    val flow: String,
    val cliCommand: String?,
    val docsUrl: String?,
    val disconnectable: Boolean,
    val loggedIn: Boolean,
    val account: String?,
)

@Immutable
data class DeviceCodeStart(val sessionId: String, val userCode: String, val verificationUrl: String, val expiresIn: Long)

/** [status]: pending · approved · error · expired · cancelled. */
@Immutable
data class DeviceCodePoll(val status: String, val error: String?, val account: String?)

/** One `GET /api/env` row (`EnvVarInfo`); the value never comes back in clear. */
@Immutable
data class EnvKey(
    val key: String,
    val isSet: Boolean,
    val preview: String?,
    val description: String,
    val url: String?,
    /** provider · tool · skill · messaging · setting · custom */
    val category: String,
    val isPassword: Boolean,
    val advanced: Boolean,
    val provider: String?,
    val primary: Boolean,
) {
    /** Shown name: the engine prefix is dropped (the real key is still what gets written). */
    val label: String get() = key.removePrefix("HERMES_")
}

/** Keys the app never offers: the vendor's own, and the PC's web sign-in (changing it from the phone locks the phone out). */
private fun hiddenKey(key: String, description: String) =
    isVendorRow(key, description) || key.startsWith("HERMES_DASHBOARD_") || key == "HERMES_ANON_API_SECRET"

@Immutable
data class CredentialCheck(val ok: Boolean, val reachable: Boolean, val message: String, val models: List<String>)

/** `GET /api/model/auxiliary` task row; [provider] `auto` = follows the main model. */
@Immutable
data class HelperSlot(val task: String, val provider: String, val model: String, val label: String?, val plugin: String?) {
    val auto: Boolean get() = provider.isBlank() || provider == "auto"
}

/** Global defaults saved in the bot's `config.yaml` (`GET /api/config`). */
@Immutable
data class ModelDefaults(
    val reasoning: String,
    val serviceTier: String,
    val contextLength: Long,
    val fallbacks: List<PickedModel>,
    /** `delegation.provider/model`: null = subagents use the bot's own model. */
    val subagent: PickedModel? = null,
    /** `delegation.reasoning_effort`: "" = same as the bot. */
    val subReasoning: String = "",
    /** `delegation.max_concurrent_children` (floor 1). */
    val subParallel: Int = 10,
    /** `delegation.max_spawn_depth`: 1 = flat, 2 = a subagent may start its own. */
    val subDepth: Int = 1,
)

/** One way subagents can run (`/api/delegation/routing` backend); [reason] says why it can't here. */
@Immutable
data class SubagentRoute(val name: String, val label: String, val description: String, val available: Boolean, val reason: String?)

/** `GET /api/delegation/routing`. While [blocked] is set every subagent is refused (it fails closed). */
@Immutable
data class SubagentRouting(val configured: String, val blocked: String?, val routes: List<SubagentRoute>, val mappedModels: Int, val mappedBots: Int)

/** One Mixture-of-Agents slot. [raw] keeps fields this app doesn't edit (reasoning effort) so a save never drops them. */
@Immutable
data class TeamSlot(val provider: String, val model: String, val enabled: Boolean = true, val raw: JsonObject? = null) {
    val complete: Boolean get() = provider.isNotBlank() && model.isNotBlank()
}

/** A named MoA preset: advisors answer, the [combiner] writes the reply. [raw] keeps its other knobs (temperatures, fan-out…). */
@Immutable
data class Team(val name: String, val advisors: List<TeamSlot>, val combiner: TeamSlot, val enabled: Boolean, val raw: JsonObject?)

/** `GET /api/model/moa` (normalized: never empty). */
@Immutable
data class Teams(val defaultTeam: String, val activeTeam: String, val teams: List<Team>)

/** `GET /api/providers/custom-endpoints` row. */
@Immutable
data class OwnEndpoint(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val models: List<String>,
    val apiMode: String,
    val contextLength: Long?,
    val hasKey: Boolean,
    val current: Boolean,
)

/** `CustomEndpointUpdate`. A blank [apiKey] keeps the saved key. */
@Immutable
data class OwnEndpointDraft(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
    val apiMode: String = "",
    val contextLength: String = "",
)

/** `AssignResult`: [confirm] holds the warning when the server asks first (cost, data policy, a big context jump). */
@Immutable
data class AssignResult(val ok: Boolean, val confirm: String?)

// ---- local models (host-wide runtime; activation writes the bot's default)

@Immutable
data class LocalModelFile(val id: String, val sizeLabel: String)

@Immutable
data class LocalRuntime(
    val enabled: Boolean,
    val runtimeInstalled: Boolean,
    val updateAvailable: Boolean,
    val serverRunning: Boolean,
    val activeModelId: String?,
    val loaded: Set<String>,
    val files: List<LocalModelFile>,
)

@Immutable
data class PcHardware(val gpu: String?, val vramLabel: String?, val ramTotal: Long)

@Immutable
data class LocalCatalogEntry(
    val id: String,
    val name: String,
    val description: String,
    val contextLabel: String,
    val recommended: Boolean,
    val downloaded: Boolean,
    val vision: Boolean,
    val needsEngine: Boolean,
    val fits: Boolean,
    val sizeLabel: String,
    val fitSummary: String,
)

/** A long local-models operation. [status]: running · paused · done · error. */
@Immutable
data class LocalModelJob(
    val id: String,
    val kind: String,
    val target: String,
    val modelId: String?,
    val status: String,
    val detail: String,
    val percent: Int?,
    val canPause: Boolean,
    val canResume: Boolean,
    val error: String?,
    val etaSeconds: Long?,
) {
    val active: Boolean get() = status == "running" || status == "paused"
}

@Immutable
data class HubHit(val repo: String, val downloads: Long, val gated: Boolean)

/** One quant of a repo; [fit]: fits-gpu · needs-ram · too-big · unknown. */
@Immutable
data class HubFiles(val label: String, val paths: List<String>, val totalBytes: Long, val fit: String)

fun modelBytes(b: Long): String = when {
    b >= 1L shl 30 -> "${(b * 10 / (1L shl 30)) / 10.0} GB"
    b >= 1L shl 20 -> "${b / (1L shl 20)} MB"
    else -> "${b / 1024} KB"
}

// ---- calls

/** `store.api.modelsApi.x(...)`: every call of this area in one place (no top-level names to clash with other areas). */
val Hermes.modelsApi: ModelsApi get() = ModelsApi(this)

class ModelsApi(private val h: Hermes) {
    private val rest get() = h.rest


    suspend fun defaultModel(profile: String): DefaultModelInfo {
        val o = rest.get("/api/model/info?profile=${q(profile)}").obj
        return DefaultModelInfo(
            model = o.str("model").orEmpty(), provider = o.str("provider").orEmpty(),
            autoContext = o.long("auto_context_length") ?: 0L, configContext = o.long("config_context_length") ?: 0L,
            effectiveContext = o.long("effective_context_length") ?: 0L, vision = o.child("capabilities").bool("supports_vision") == true,
        )
    }

    /** Every provider, signed in or not ([refresh] probes custom endpoints and busts the catalog cache). Vendor rows are dropped. */
    suspend fun catalog(profile: String, refresh: Boolean = false): ProviderCatalog {
        val o = rest.get("/api/model/options?profile=${q(profile)}&include_unconfigured=1" + (if (refresh) "&refresh=1" else "")).obj
        val providers = o.list("providers").mapNotNull { ProviderRow.from(it.obj) }
            .filter { !isVendorRow(it.slug, it.name) && it.slug != "custom" }
        val model = o.str("model")
        return ProviderCatalog(providers, model?.let { PickedModel(o.str("provider").orEmpty(), it) })
    }

    /** `POST /api/model/set`. [scope] `main` | `auxiliary` ([task] "" = every helper, `__reset__` = all back to auto). New chats only. */
    suspend fun assign(profile: String, scope: String, provider: String, model: String, task: String = "", confirmed: Boolean = false): AssignResult {
        val r = rest.post("/api/model/set?profile=${q(profile)}", params("scope" to scope, "provider" to provider, "model" to model, "task" to task,
            "confirm_expensive_model" to confirmed, "profile" to profile)).obj
        return if (r.bool("confirm_required") == true) AssignResult(false, modelsDebrand(r.str("confirm_message").orEmpty())) else AssignResult(true, null)
    }

    suspend fun helpers(profile: String): List<HelperSlot> =
        rest.get("/api/model/auxiliary?profile=${q(profile)}").obj.list("tasks").mapNotNull { e ->
            val t = e.obj
            HelperSlot(t.str("task") ?: return@mapNotNull null, t.str("provider") ?: "auto", t.str("model").orEmpty(),
                t.str("label")?.takeIf { it.isNotBlank() }, t.str("plugin")?.takeIf { it.isNotBlank() })
        }

    suspend fun defaults(profile: String): ModelDefaults {
        val c = rest.get("/api/config?profile=${q(profile)}&include_defaults=true").obj
        val agent = c.child("agent")
        return ModelDefaults(
            reasoning = agent.str("reasoning_effort")?.takeIf { it.isNotBlank() } ?: "medium",
            serviceTier = agent.str("service_tier").orEmpty(),
            contextLength = c.long("model_context_length") ?: 0L,
            fallbacks = c.list("fallback_providers").mapNotNull { f ->
                val x = f.obj
                val p = x.str("provider")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                PickedModel(p, x.str("model")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null)
            },
            subagent = c.child("delegation").let { d -> d.str("model")?.takeIf { it.isNotBlank() }?.let { PickedModel(d.str("provider").orEmpty(), it) } },
            subReasoning = c.child("delegation").str("reasoning_effort").orEmpty(),
            subParallel = (c.child("delegation").long("max_concurrent_children") ?: 10L).toInt().coerceAtLeast(1),
            subDepth = (c.child("delegation").long("max_spawn_depth") ?: 1L).toInt().coerceAtLeast(1),
        )
    }

    // ---- subagents (delegation) and team answers (Mixture of Agents)

    suspend fun subagentRouting(profile: String): SubagentRouting {
        val o = rest.get("/api/delegation/routing?profile=${q(profile)}").obj
        return SubagentRouting(
            configured = o.str("configured") ?: "default",
            blocked = o.str("blocked_reason")?.takeIf { it.isNotBlank() }?.let(::modelsDebrand),
            routes = o.list("backends").mapNotNull { e ->
                val b = e.obj
                val name = b.str("name") ?: return@mapNotNull null
                SubagentRoute(name, modelsDebrand(b.str("label") ?: name), modelsDebrand(b.str("description").orEmpty()), b.bool("available") != false,
                    b.str("reason")?.takeIf { it.isNotBlank() }?.let(::modelsDebrand))
            },
            mappedModels = o.child("model_map").list("options").size,
            mappedBots = o.child("profile_routes").list("profiles").size,
        )
    }

    /** `PUT /api/delegation/backend`: only `default` or a route that can run for this bot (400 says why not). */
    suspend fun setSubagentRoute(profile: String, name: String): JsonElement =
        rest.put("/api/delegation/backend?profile=${q(profile)}", params("backend" to name, "profile" to profile))

    private fun slotOf(e: JsonElement?): TeamSlot {
        val o = e.obj
        return TeamSlot(o.str("provider").orEmpty(), o.str("model").orEmpty(), o.bool("enabled") != false, o)
    }

    private fun teamsOf(o: JsonObject?): Teams {
        val teams = o.child("presets")?.map { (name, v) ->
            val p = v.obj
            Team(name, p.list("reference_models").map(::slotOf), slotOf(p?.get("aggregator")), p.bool("enabled") != false, p)
        }.orEmpty()
        return Teams(o.str("default_preset") ?: teams.firstOrNull()?.name.orEmpty(), o.str("active_preset").orEmpty(), teams)
    }

    suspend fun teams(profile: String): Teams = teamsOf(rest.get("/api/model/moa?profile=${q(profile)}").obj)

    private fun slotJson(s: TeamSlot, withEnabled: Boolean): JsonObject = JsonObject(buildMap {
        s.raw?.forEach { (k, v) -> if (k != "enabled" || withEnabled) put(k, v) }
        put("provider", JsonPrimitive(s.provider))
        put("model", JsonPrimitive(s.model))
        if (withEnabled) put("enabled", JsonPrimitive(s.enabled))
    })

    /** `PUT /api/model/moa` with every team (an omitted name is deleted). 422 explains an incomplete slot. Returns the saved form. */
    suspend fun saveTeams(profile: String, t: Teams): Teams {
        val presets = JsonObject(t.teams.associate { team ->
            team.name to JsonObject(buildMap {
                team.raw?.forEach { (k, v) -> put(k, v) }
                put("reference_models", JsonArray(team.advisors.map { slotJson(it, withEnabled = true) }))
                put("aggregator", slotJson(team.combiner, withEnabled = false))
                put("enabled", JsonPrimitive(team.enabled))
            })
        })
        val r = rest.put("/api/model/moa?profile=${q(profile)}",
            params("default_preset" to t.defaultTeam, "active_preset" to t.activeTeam, "presets" to presets, "profile" to profile)).obj
        return teamsOf(r)
    }

    /** Sparse `PUT /api/config` patch (deep-merged on the PC; lists replace). Applies to new chats. */
    suspend fun patchConfig(profile: String, patch: Map<String, Any?>): JsonElement =
        rest.put("/api/config?profile=${q(profile)}", params("config" to patch, "profile" to profile))

    suspend fun logins(profile: String): List<ProviderLogin> =
        rest.get("/api/providers/oauth?profile=${q(profile)}").obj.list("providers").mapNotNull { e ->
            val o = e.obj
            val id = o.str("id") ?: return@mapNotNull null
            val name = o.str("name") ?: id
            if (isVendorRow(id, name)) return@mapNotNull null
            val st = o.child("status")
            ProviderLogin(id, name, o.str("flow").orEmpty(), o.str("cli_command")?.takeIf { it.isNotBlank() }, o.str("docs_url")?.takeIf { it.isNotBlank() },
                o.bool("disconnectable") == true, st.bool("logged_in") == true, st.str("account_email") ?: st.str("email"))
        }

    suspend fun loginStart(id: String, profile: String): DeviceCodeStart {
        val o = rest.post("/api/providers/oauth/${q(id)}/start?profile=${q(profile)}").obj
        return DeviceCodeStart(o.str("session_id").orEmpty(), o.str("user_code").orEmpty(), o.str("verification_url").orEmpty(), o.long("expires_in") ?: 900L)
    }

    /** Long-poll (the server holds it up to [wait] s). */
    suspend fun loginPoll(id: String, sessionId: String, profile: String, wait: Int = 25): DeviceCodePoll {
        val o = rest.get("/api/providers/oauth/${q(id)}/poll/${q(sessionId)}?profile=${q(profile)}&wait=$wait").obj
        return DeviceCodePoll(o.str("status") ?: "pending", (o.str("error_message") ?: o.str("reason"))?.takeIf { it.isNotBlank() }?.let(::modelsDebrand), o.str("account_email"))
    }

    suspend fun loginCancel(sessionId: String): JsonElement = rest.delete("/api/providers/oauth/sessions/${q(sessionId)}")

    suspend fun logout(id: String, profile: String): JsonElement = rest.delete("/api/providers/oauth/${q(id)}?profile=${q(profile)}")

    suspend fun envKeys(profile: String): List<EnvKey> =
        rest.get("/api/env?profile=${q(profile)}").obj.orEmpty().mapNotNull { (key, v) ->
            val o = v.obj ?: return@mapNotNull null
            val description = o.str("description").orEmpty()
            if (hiddenKey(key, description)) return@mapNotNull null
            EnvKey(
                key = key, isSet = o.bool("is_set") == true, preview = o.str("redacted_value")?.takeIf { it.isNotBlank() },
                description = modelsDebrand(description), url = o.str("url")?.takeIf { it.isNotBlank() }, category = o.str("category") ?: "custom",
                isPassword = o.bool("is_password") == true, advanced = o.bool("advanced") == true,
                provider = o.str("provider")?.takeIf { it.isNotBlank() }, primary = o.bool("provider_primary") == true,
            )
        }

    /** Writes the PC's `.env` and its mirrors in `config.yaml`. [providerSetup] marks a provider sign-up. */
    suspend fun setEnv(profile: String, key: String, value: String, providerSetup: Boolean = false): JsonElement =
        rest.put("/api/env?profile=${q(profile)}", params("key" to key, "value" to value, "profile" to profile, "provider_setup" to providerSetup.takeIf { it }))

    suspend fun deleteEnv(profile: String, key: String): JsonElement = rest.delete("/api/env?profile=${q(profile)}", params("key" to key, "profile" to profile))

    /** The real value (rate-limited to 5 per 30 s, audit-logged on the PC). */
    suspend fun revealEnv(profile: String, key: String): String? =
        rest.post("/api/env/reveal?profile=${q(profile)}", params("key" to key, "profile" to profile)).obj.str("value")

    /** Live probe for the keys the PC knows how to test; others answer `ok, !reachable` (unknown). */
    suspend fun checkKey(key: String, value: String): CredentialCheck {
        val o = rest.post("/api/providers/validate", params("key" to key, "value" to value)).obj
        return CredentialCheck(o.bool("ok") == true, o.bool("reachable") == true, modelsDebrand(o.str("message").orEmpty()), texts(o.list("models")))
    }

    private fun endpointOf(o: JsonObject?): OwnEndpoint? {
        val id = o.str("id") ?: return null
        return OwnEndpoint(id, o.str("name") ?: id, o.str("base_url").orEmpty(), o.str("model").orEmpty(), texts(o.list("models")),
            o.str("api_mode").orEmpty(), o.long("context_length"), o.bool("has_api_key") == true, o.bool("is_current") == true)
    }

    suspend fun endpoints(profile: String): List<OwnEndpoint> =
        rest.get("/api/providers/custom-endpoints?profile=${q(profile)}").obj.list("endpoints").mapNotNull { endpointOf(it.obj) }

    private fun endpointBody(d: OwnEndpointDraft, makeDefault: Boolean = false) = params(
        "id" to d.id, "name" to d.name.trim(), "base_url" to d.baseUrl.trim(), "model" to d.model.trim(),
        "api_key" to d.apiKey.trim().takeIf { it.isNotEmpty() }, "api_mode" to d.apiMode,
        "context_length" to d.contextLength.trim().toLongOrNull()?.takeIf { it > 0 }, "discover_models" to true, "make_default" to makeDefault,
    )

    /** Create or update; returns the endpoint id. 400 for a missing name, address or model. */
    suspend fun saveEndpoint(profile: String, d: OwnEndpointDraft): String? =
        rest.post("/api/providers/custom-endpoints?profile=${q(profile)}", endpointBody(d)).obj.str("id")

    suspend fun checkEndpoint(d: OwnEndpointDraft): CredentialCheck {
        val o = rest.post("/api/providers/custom-endpoints/validate", endpointBody(d)).obj
        return CredentialCheck(o.bool("ok") == true, o.bool("reachable") == true, modelsDebrand(o.str("message").orEmpty()), texts(o.list("models")))
    }

    suspend fun activateEndpoint(profile: String, id: String): JsonElement = rest.post("/api/providers/custom-endpoints/${q(id)}/activate?profile=${q(profile)}")

    suspend fun deleteEndpoint(profile: String, id: String): JsonElement = rest.delete("/api/providers/custom-endpoints/${q(id)}?profile=${q(profile)}")

    // ---- local models

    suspend fun localStatus(): LocalRuntime {
        val o = rest.get("/api/local-models/status").obj
        return LocalRuntime(
            enabled = o.bool("enabled") == true, runtimeInstalled = o.bool("runtime_installed") == true, updateAvailable = o.bool("update_available") == true,
            serverRunning = o.bool("server_running") == true, activeModelId = o.str("active_model_id"),
            loaded = o.child("loaded_models")?.keys.orEmpty(),
            files = o.list("models").mapNotNull { e -> e.obj?.let { f -> LocalModelFile(f.str("id") ?: return@mapNotNull null, f.str("size_label").orEmpty()) } },
        )
    }

    suspend fun localHardware(): PcHardware {
        val o = rest.get("/api/local-models/hardware").obj
        return PcHardware(o.str("gpu_name"), o.str("vram_label"), o.long("ram_total_bytes") ?: 0L)
    }

    suspend fun localCatalog(): List<LocalCatalogEntry> =
        rest.get("/api/local-models/catalog").obj.list("models").mapNotNull { e ->
            val m = e.obj
            val id = m.str("id") ?: return@mapNotNull null
            LocalCatalogEntry(id, m.str("display_name") ?: id, modelsDebrand(m.str("description").orEmpty()), m.str("native_context_label").orEmpty(),
                m.bool("recommended") == true, m.bool("downloaded") == true, m.bool("vision") == true, m.bool("needs_engine") == true,
                m.bool("fits") == true, m.str("size_label").orEmpty(), modelsDebrand(m.str("fit_summary").orEmpty()))
        }

    suspend fun localJobs(): List<LocalModelJob> =
        rest.get("/api/local-models/jobs").obj.list("jobs").mapNotNull { e ->
            val j = e.obj
            LocalModelJob(
                id = j.str("job_id") ?: return@mapNotNull null, kind = j.str("kind").orEmpty(), target = j.str("target").orEmpty(),
                modelId = j.str("model_id"), status = j.str("status") ?: "running", detail = modelsDebrand(j.str("detail").orEmpty()),
                percent = j.long("percent")?.toInt(), canPause = j.bool("can_pause") == true, canResume = j.bool("can_resume") == true,
                error = j.str("error")?.takeIf { it.isNotBlank() }?.let(::modelsDebrand), etaSeconds = j.long("eta_seconds"),
            )
        }

    suspend fun localInstallRuntime(profile: String): String? = rest.post("/api/local-models/runtime/install?profile=${q(profile)}", params()).obj.str("job_id")

    /** One job from nothing to a working default: engine, this PC's best model, made the bot's default. 409 explains why not. */
    suspend fun localQuickstart(profile: String, modelId: String? = null): String? =
        rest.post("/api/local-models/quickstart?profile=${q(profile)}", params("model_id" to modelId)).obj.str("job_id")

    /** Null job id = already on disk. */
    suspend fun localDownload(modelId: String): String? = rest.post("/api/local-models/download", params("model_id" to modelId)).obj.str("job_id")

    suspend fun localPause(jobId: String): JsonElement = rest.post("/api/local-models/download/pause", params("job_id" to jobId))
    suspend fun localResume(jobId: String): JsonElement = rest.post("/api/local-models/download/resume", params("job_id" to jobId))
    suspend fun localDelete(modelId: String): JsonElement = rest.delete("/api/local-models/models/${q(modelId)}")
    suspend fun localServer(start: Boolean): JsonElement = rest.post("/api/local-models/server", params("action" to if (start) "start" else "stop"))
    suspend fun localEject(modelId: String): JsonElement = rest.post("/api/local-models/eject", params("model_id" to modelId))
    suspend fun localActivate(profile: String, modelId: String): String? =
        rest.post("/api/local-models/activate?profile=${q(profile)}", params("model_id" to modelId)).obj.str("job_id")

    suspend fun localSearch(query: String): List<HubHit> =
        rest.get("/api/local-models/search?q=${q(query)}&limit=20").obj.list("hits").mapNotNull { e ->
            val h = e.obj
            HubHit(h.str("repo") ?: return@mapNotNull null, h.long("downloads") ?: 0L, h.bool("gated") == true)
        }

    suspend fun localRepoFiles(repo: String): List<HubFiles> =
        rest.get("/api/local-models/search/files?repo=${q(repo)}").obj.list("files").mapNotNull { e ->
            val f = e.obj
            val paths = texts(f.list("paths")).ifEmpty { return@mapNotNull null }
            HubFiles(f.str("label") ?: paths.first(), paths, f.long("total_bytes") ?: 0L, f.str("fit") ?: "unknown")
        }

    suspend fun localDownloadBrowsed(repo: String, paths: List<String>): String? =
        rest.post("/api/local-models/download-browsed", params("repo" to repo, "paths" to paths)).obj.str("job_id")

    /** Registers a `.gguf` already on the PC (a path on the PC, not the phone). */
    suspend fun localSideload(path: String): String? = rest.post("/api/local-models/sideload", params("path" to path)).obj.str("model_id")
}
