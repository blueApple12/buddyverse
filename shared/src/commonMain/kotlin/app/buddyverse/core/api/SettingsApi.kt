package app.buddyverse.core.api

// Owner: More ▸ Settings (ui/more/SettingsPage.kt, ui/more/settings). hermes-api.md §2.3 "Settings: config REST
// (schema-driven pages)", "Billing, subscription, usage", "Gateway settings" (shared metrics). Extension functions only.

import androidx.compose.runtime.Immutable
import app.buddyverse.core.Hermes
import app.buddyverse.core.RpcError
import app.buddyverse.core.WireJson
import app.buddyverse.core.bool
import app.buddyverse.core.child
import app.buddyverse.core.decodeAs
import app.buddyverse.core.obj
import app.buddyverse.core.params
import app.buddyverse.core.str
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun q(v: String) = v.encodeURLParameter()

// ---------------------------------------------------------------------------------------------------- engine config (REST)

/** One `GET /api/config/schema` field. [type]: boolean, number, string, select, list or object. */
@Immutable
data class ConfigField(val key: String, val type: String, val description: String, val category: String, val options: List<String>, val searchable: Boolean) {
    /** The server's auto text is just the key title-cased (`Display → Busy Input Mode`); only real descriptions are worth showing. */
    val ownDescription: String? get() = description.takeIf { it.isNotBlank() && " → " !in it && !it.equals(key.replace('_', ' '), ignoreCase = true) }
}

/** The fields, plus the category order the server suggests (unlisted ones sort after it, alphabetically). */
@Immutable
data class ConfigSchema(val fields: List<ConfigField>, val order: List<String>) {
    val categories: List<String> get() {
        val present = fields.map { it.category }.distinct()
        return order.filter { it in present } + present.filter { it !in order }.sorted()
    }
}

suspend fun Hermes.configSchema(profile: String): ConfigSchema {
    val o = rest.get("/api/config/schema?profile=${q(profile)}").obj
    val fields = o.child("fields")?.entries.orEmpty().mapNotNull { (k, v) ->
        val f = v.obj ?: return@mapNotNull null
        ConfigField(
            key = k, type = f.str("type") ?: "string", description = f.str("description").orEmpty(), category = f.str("category") ?: "general",
            options = (f["options"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content },
            searchable = f.bool("searchable") == true,
        )
    }
    val order = (o?.get("category_order") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
    return ConfigSchema(fields, order)
}

/** `GET /api/config`: the merged config (saved values over defaults), `model` flattened to a string. */
suspend fun Hermes.configValues(profile: String): JsonObject = rest.get("/api/config?profile=${q(profile)}").obj ?: JsonObject(emptyMap())

suspend fun Hermes.configDefaults(): JsonObject = rest.get("/api/config/defaults").obj ?: JsonObject(emptyMap())

/** The value at a dotted key (`sessions.auto_archive_days`). */
fun JsonObject.at(dotted: String): JsonElement? {
    var cur: JsonElement? = this
    for (part in dotted.split('.')) cur = (cur as? JsonObject)?.get(part) ?: return null
    return cur
}

/** A copy with [value] at the dotted key (missing parents are made). */
fun JsonObject.withAt(dotted: String, value: JsonElement): JsonObject {
    val head = dotted.substringBefore('.')
    if (head == dotted) return JsonObject(this + (head to value))
    val child = (this[head] as? JsonObject) ?: JsonObject(emptyMap())
    return JsonObject(this + (head to child.withAt(dotted.substringAfter('.'), value)))
}

/** `PUT /api/config` with one sparse patch: the server deep-merges it over the file. Applies to new chats. */
suspend fun Hermes.configSave(profile: String, key: String, value: JsonElement) {
    rest.put("/api/config?profile=${q(profile)}", params("config" to JsonObject(emptyMap()).withAt(key, value), "profile" to profile))
}

/** `GET /api/config/raw`: the settings file as text, and where it lives. */
suspend fun Hermes.configRaw(profile: String): Pair<String, String> {
    val o = rest.get("/api/config/raw?profile=${q(profile)}").obj
    return o.str("yaml").orEmpty() to o.str("path").orEmpty()
}

/** `PUT /api/config/raw`: replaces the whole file (400 with the reason when it isn't a YAML mapping). */
suspend fun Hermes.configRawSave(profile: String, yaml: String) {
    rest.put("/api/config/raw?profile=${q(profile)}", params("yaml_text" to yaml, "profile" to profile))
}

// ---------------------------------------------------------------------------------------------------- usage-count sharing (WS legacy)

@Serializable
data class MetricsConsent(val enabled: Boolean = false, val send: Boolean = false, val decided: Boolean = false)

suspend fun Hermes.metricsConsent(profile: String): MetricsConsent? =
    gateway.call("shared_metrics.status", params("profile" to profile), 15_000).obj?.decodeAs()

/** Sending can't outlive collection: off turns both off. */
suspend fun Hermes.setMetricsConsent(profile: String, on: Boolean): MetricsConsent? =
    gateway.call("shared_metrics.set", params("profile" to profile, "enabled" to on, "send" to on), 15_000).obj?.decodeAs()

// ---------------------------------------------------------------------------------------------------- billing (WS legacy, fail-open)

/** A billing refusal (`ok:false`): [code] e.g. `insufficient_scope` (run [billingStepUp]), `no_payment_method`, `rate_limited`. */
class BillingRefusal(val code: String, message: String, val portalUrl: String?) : Exception(message) {
    val needsStepUp: Boolean get() = code == "insufficient_scope"
}

@Serializable
data class UsageBar(
    val kind: String? = null,
    @SerialName("remaining_display") val remaining: String? = null,
    @SerialName("total_display") val total: String? = null,
    @SerialName("spent_display") val spent: String? = null,
    @SerialName("pct_used") val pctUsed: Int? = null,
    @SerialName("fill_fraction") val fill: Double? = null,
)

@Serializable
data class UsageInfo(
    val available: Boolean = false,
    val status: String? = null,
    @SerialName("plan_name") val planName: String? = null,
    @SerialName("renews_display") val renews: String? = null,
    @SerialName("total_spendable_display") val spendable: String? = null,
    @SerialName("plan_bar") val planBar: UsageBar? = null,
    @SerialName("topup_bar") val topupBar: UsageBar? = null,
)

@Serializable
data class AutoReload(
    val enabled: Boolean = false,
    @SerialName("threshold_usd") val thresholdUsd: String? = null,
    @SerialName("threshold_display") val threshold: String? = null,
    @SerialName("reload_to_usd") val reloadToUsd: String? = null,
    @SerialName("reload_to_display") val reloadTo: String? = null,
)

@Serializable
data class PayMethod(val kind: String? = null, val brand: String? = null, val last4: String? = null, val wallet: String? = null, val email: String? = null)

@Serializable
data class MonthlyCap(@SerialName("limit_display") val limit: String? = null, @SerialName("spent_display") val spent: String? = null)

/** `billing.state` (read-only). Logged out answers `ok` with [loggedIn] false. */
@Serializable
data class BillingInfo(
    val ok: Boolean = true,
    @SerialName("logged_in") val loggedIn: Boolean = false,
    @SerialName("free_tier_account") val freeTier: Boolean = false,
    @SerialName("free_tier_model") val freeTierModel: String? = null,
    @SerialName("org_name") val org: String? = null,
    val role: String? = null,
    @SerialName("is_admin") val admin: Boolean? = null,
    @SerialName("can_change_plan") val canChangePlan: Boolean? = null,
    @SerialName("can_charge") val canCharge: Boolean? = null,
    @SerialName("balance_display") val balance: String? = null,
    @SerialName("charge_presets") val presets: List<String> = emptyList(),
    @SerialName("charge_presets_display") val presetsDisplay: List<String> = emptyList(),
    @SerialName("payment_method") val payment: PayMethod? = null,
    @SerialName("monthly_cap") val cap: MonthlyCap? = null,
    @SerialName("auto_reload") val autoReload: AutoReload? = null,
    @SerialName("keep_going_on_credits") val keepGoing: Boolean? = null,
    @SerialName("portal_url") val portal: String? = null,
    val usage: UsageInfo? = null,
)

@Serializable
data class CurrentPlan(
    @SerialName("tier_id") val tierId: String? = null,
    @SerialName("tier_name") val tierName: String? = null,
    @SerialName("credits_remaining") val creditsLeft: String? = null,
    @SerialName("cycle_ends_at") val cycleEnds: String? = null,
    @SerialName("pending_downgrade_tier_name") val pendingTier: String? = null,
    @SerialName("pending_downgrade_display") val pendingWhen: String? = null,
    @SerialName("cancel_at_period_end") val cancelAtEnd: Boolean = false,
    @SerialName("cancellation_effective_display") val cancelWhen: String? = null,
)

@Serializable
data class PlanTier(
    @SerialName("tier_id") val id: String,
    val name: String,
    @SerialName("tier_order") val order: Int = 0,
    @SerialName("dollars_per_month_display") val price: String = "",
    @SerialName("monthly_credits") val credits: String? = null,
    @SerialName("is_current") val current: Boolean = false,
    @SerialName("is_enabled") val enabled: Boolean = true,
)

/** `subscription.state`: the plan, the tiers to pick from, the portal link. */
@Serializable
data class PlanInfo(
    val ok: Boolean = true,
    @SerialName("logged_in") val loggedIn: Boolean = false,
    @SerialName("can_change_plan") val canChange: Boolean? = null,
    val current: CurrentPlan? = null,
    val tiers: List<PlanTier> = emptyList(),
    @SerialName("portal_url") val portal: String? = null,
)

/** `subscription.preview`: [effect] charge_now | scheduled | no_op | blocked. */
@Serializable
data class PlanPreview(
    val effect: String? = null,
    val reason: String? = null,
    @SerialName("target_tier_name") val target: String? = null,
    @SerialName("monthly_credits_delta") val creditsDelta: String? = null,
    @SerialName("amount_due_now_cents") val dueNowCents: Int? = null,
    @SerialName("effective_at") val effectiveAt: String? = null,
)

@Serializable
data class ChargeStatus(val status: String? = null, val reason: String? = null)

/** One billing call: `ok:false` becomes a [BillingRefusal] with the server's words. */
private suspend fun Hermes.billing(method: String, profile: String, vararg p: Pair<String, Any?>, timeoutMs: Long = 60_000): JsonObject {
    val o = try { gateway.call(method, params("profile" to profile, *p), timeoutMs).obj ?: JsonObject(emptyMap()) } catch (e: RpcError) {
        throw BillingRefusal(e.data.str("code") ?: e.reason, e.message ?: e.reason, e.data.str("portal_url"))
    }
    if (o.bool("ok") == false) {
        val err = o["error"]
        val errObj = err as? JsonObject
        val code = errObj.str("kind") ?: errObj.str("error") ?: (err as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content ?: o.str("code") ?: "error"
        throw BillingRefusal(code, errObj.str("message") ?: o.str("message") ?: code, errObj.str("portal_url") ?: o.str("portal_url"))
    }
    return o
}

suspend fun Hermes.billingInfo(profile: String): BillingInfo = WireJson.decodeFromJsonElement(BillingInfo.serializer(), billing("billing.state", profile))
suspend fun Hermes.planInfo(profile: String): PlanInfo = WireJson.decodeFromJsonElement(PlanInfo.serializer(), billing("subscription.state", profile))
suspend fun Hermes.planPreview(profile: String, tierId: String): PlanPreview =
    WireJson.decodeFromJsonElement(PlanPreview.serializer(), billing("subscription.preview", profile, "subscription_type_id" to tierId))

/** Schedules a chargeless downgrade / same-price change at period end (upgrades go through the portal). */
suspend fun Hermes.planChange(profile: String, tierId: String) { billing("subscription.change", profile, "subscription_type_id" to tierId) }

/** Clears a scheduled downgrade or cancellation. */
suspend fun Hermes.planResume(profile: String) { billing("subscription.resume", profile) }

/** Buys credits; keep [idempotencyKey] across retries of the same intent. Returns the charge id to poll. */
suspend fun Hermes.buyCredits(profile: String, amountUsd: String, idempotencyKey: String): String? =
    billing("billing.charge", profile, "amount_usd" to amountUsd, "idempotency_key" to idempotencyKey).str("charge_id")

suspend fun Hermes.chargeStatus(profile: String, chargeId: String): ChargeStatus =
    WireJson.decodeFromJsonElement(ChargeStatus.serializer(), billing("billing.charge_status", profile, "charge_id" to chargeId))

suspend fun Hermes.setAutoReload(profile: String, enabled: Boolean, thresholdUsd: String?, reloadToUsd: String?) {
    billing("billing.auto_reload", profile, "enabled" to enabled, "threshold" to thresholdUsd, "top_up_amount" to reloadToUsd)
}

suspend fun Hermes.setKeepGoing(profile: String, enabled: Boolean) { billing("billing.keep_going_on_credits", profile, "enabled" to enabled) }

/** The `billing:manage` device flow: blocks until the user approves in the browser (the link and code arrive as event `billing.step_up.verification`). */
suspend fun Hermes.billingStepUp(profile: String): Boolean = billing("billing.step_up", profile, timeoutMs = 600_000).bool("granted") == true
