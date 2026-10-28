package app.buddyverse.core.api

import androidx.compose.runtime.Immutable
import app.buddyverse.core.Hermes
import app.buddyverse.core.bool
import app.buddyverse.core.child
import app.buddyverse.core.contract.ConnectionOperationTarget
import app.buddyverse.core.decodeAs
import app.buddyverse.core.list
import app.buddyverse.core.obj
import app.buddyverse.core.params
import app.buddyverse.core.str
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Owner: More ▸ Connectors (ui/more/ConnectorsPage.kt). hermes-api.md §2.6 "Connectors (hosted apps + connection cards)".
// All WS legacy RPC, optional `profile` on every call, 45 s client timeout.

private const val CONNECTOR_TIMEOUT = 45_000L

/** `{type:"account"}`: the Connectors page's own operations. */
val AccountOwner: Map<String, String> = mapOf("type" to "account")

/** `{type:"session", session_id}`: a chat's connection card (`connection.request` / `connection.update` events). */
fun sessionOwner(sessionId: String): Map<String, String> = mapOf("type" to "session", "session_id" to sessionId)

/** One app of `connectors.catalog`. */
@Immutable
data class ConnectorApp(val slug: String, val name: String, val description: String, val category: String)

/** One row of `connectors.list` (the profile's view of a connector). */
@Immutable
data class ConnectorState(
    val connector: String,
    val enabled: Boolean,
    val connected: Boolean,
    /** pending | active | failed | expired | revoked | inactive | null */
    val status: String?,
    val statusReason: String?,
    val gatewayDisabledTools: List<String>,
)

/** One hosted account (`ConnectorAccountRow`). */
@Immutable
data class ConnectorAccount(
    val connectionId: String,
    val connector: String,
    /** pending | active | failed | expired | revoked | inactive */
    val status: String,
    val statusReason: String?,
    val label: String,
    val alias: String?,
    val active: Boolean,
    val disabled: Boolean,
    val createdAt: String,
) {
    val name: String get() = alias?.takeIf { it.isNotBlank() } ?: label.ifBlank { connector }
}

/** One tool of `connectors.tools`. [facet] = read | write | destructive | unclassified. */
@Immutable
data class ConnectorTool(val slug: String, val name: String, val description: String, val facet: String, val hints: List<String>, val deprecated: Boolean)

/** One policy layer, read the way the desktop app reads it (`join.ts::rulesOf`): `tools` = disabled tools per connector. */
@Immutable
data class ConnectorRules(val allowed: Set<String>?, val denied: Set<String>, val revision: String, val tools: Map<String, List<String>>) {
    fun allows(slug: String) = (allowed == null || slug in allowed) && slug !in denied
}

/** `connectors.policy.get` split into the member's own layer (writable) and the organization/role layers above it. */
@Immutable
data class ConnectorPolicy(val member: ConnectorRules?, val others: List<ConnectorRules>) {
    fun enabled(slug: String) = member?.allows(slug) ?: true
    fun orgLocked(slug: String) = others.any { !it.allows(slug) }
    fun disabledTools(slug: String): List<String> = member?.tools?.get(slug).orEmpty()
    fun lockedTools(slug: String): Set<String> = others.flatMap { it.tools[slug].orEmpty() }.toSet()
    /** `expected_revision` for `connectors.policy.set`; null = the rules are read-only here. */
    val revision: String? get() = member?.revision
}

/** `ConnectionOperationStatus` / `ConnectorsConnectResult` / a `connection.update` payload (same snapshot shape). */
@Serializable
data class ConnectionOp(
    @SerialName("op_id") val opId: String = "",
    @SerialName("seq") val seq: Long = 0,
    @SerialName("deadline_at") val deadlineAt: Double = 0.0,
    @SerialName("settled") val settled: Boolean = false,
    @SerialName("settled_by") val settledBy: String? = null,
    @SerialName("targets") val targets: List<ConnectionOperationTarget> = emptyList(),
    @SerialName("status") val status: String? = null,
    @SerialName("note") val note: String? = null,
) {
    val connectUrl: String? get() = targets.firstNotNullOfOrNull { it.connectUrl?.takeIf { u -> u.isNotBlank() } }
    /** Nothing left to wait for: settled, or every target reached an end state. */
    val done: Boolean get() = settled || (targets.isNotEmpty() && targets.all { it.resolved == true || it.state in END_STATES })
    val connected: Boolean get() = targets.any { it.state == "connected" }

    companion object {
        val END_STATES = setOf("connected", "skipped", "failed", "expired", "not_connected")
    }
}

/** One row of a `connection.respond` answer. [status] = approved | skipped; [env] carries values an install asked for via `required_env`. */
data class ConnectionAnswerTarget(val name: String, val status: String, val detail: String? = null, val env: Map<String, String>? = null)

/** The gateway's account-name format (`ConnectorAlias`). */
val CONNECTOR_ALIAS = Regex("^[a-z0-9][a-z0-9-]{0,31}$")

/** Typed text as an alias candidate while typing: lower case, each space or underscore to `-`; [CONNECTOR_ALIAS] validates it on save. */
fun aliasDraft(text: String): String = text.lowercase().replace(Regex("[\\s_]"), "-").take(32)

/** The catalog's copy names the engine ("Use X through Hermes"): this app never shows that name (design rule: no engine branding). */
fun debrand(text: String): String = text
    .replace(Regex("\\s+(through|via|with|in|using)\\s+(hermes|nous)(\\s+(agent|research))?", RegexOption.IGNORE_CASE), "")
    .replace(Regex("\\b(hermes|nous)(\\s+(agent|research))?\\b", RegexOption.IGNORE_CASE), "")
    .replace(Regex("\\s{2,}"), " ").trim()

/** `google_drive_mcp` → `Google Drive` for a connector the catalog doesn't name. */
fun connectorTitle(slug: String): String =
    slug.removeSuffix("_mcp").split('_', '-').filter { it.isNotBlank() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

/** One app's live accounts, active first, then newest; retired (`disabled`) ones are left out (`join.ts::accountsFor`). */
fun List<ConnectorAccount>.accountsOf(slug: String): List<ConnectorAccount> =
    filter { it.connector == slug && !it.disabled }.sortedWith(compareBy<ConnectorAccount> { if (it.active) 0 else 1 }.thenByDescending { it.createdAt })

// ---------------------------------------------------------------------------------------------------- parsing

private fun strings(e: JsonElement?): List<String> = (e as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }

private fun toolMap(o: JsonObject?): Map<String, List<String>> = o?.mapValues { strings(it.value) }.orEmpty()

internal fun parseRules(layer: JsonObject?): ConnectorRules? {
    val revision = layer.str("revision") ?: return null
    val body = layer.child("body")
    return when (body.str("mode")) {
        "unrestricted" -> ConnectorRules(null, emptySet(), revision, emptyMap())
        "deny-all" -> ConnectorRules(emptySet(), emptySet(), revision, emptyMap())
        "allow" -> ConnectorRules(strings(body?.get("connectors")).toSet(), emptySet(), revision, toolMap(body.child("tools")))
        "deny" -> ConnectorRules(null, strings(body?.get("disabled_connectors")).toSet(), revision, toolMap(body.child("tools")))
        else -> null
    }
}

internal fun parsePolicy(o: JsonObject?): ConnectorPolicy {
    val layers = o.list("layers").mapNotNull { it.obj }
    val member = layers.firstOrNull { it.str("kind") == "member" }
    return ConnectorPolicy(parseRules(member), layers.filter { it.str("kind") != "member" }.mapNotNull(::parseRules))
}

internal fun parseAccount(o: JsonObject?): ConnectorAccount? = ConnectorAccount(
    connectionId = o.str("connection_id") ?: return null,
    connector = o.str("connector").orEmpty(),
    status = o.str("status") ?: "inactive",
    statusReason = o.str("status_reason")?.takeIf { it.isNotBlank() },
    label = o.str("label").orEmpty(),
    alias = o.str("alias")?.takeIf { it.isNotBlank() },
    active = o.bool("active") == true,
    disabled = o.bool("disabled") == true,
    createdAt = o.str("created_at").orEmpty(),
)

// ---------------------------------------------------------------------------------------------------- calls

private suspend fun Hermes.connectorCall(method: String, vararg p: Pair<String, Any?>): JsonObject? =
    gateway.call(method, params(*p), CONNECTOR_TIMEOUT).obj

/** `connectors.catalog`: every hosted app this member can connect. */
suspend fun Hermes.connectorCatalog(profile: String?): List<ConnectorApp> =
    connectorCall("connectors.catalog", "profile" to profile).list("connectors").mapNotNull { e ->
        val o = e.obj
        val slug = o.str("slug") ?: return@mapNotNull null
        ConnectorApp(slug, o.str("name")?.takeIf { it.isNotBlank() } ?: connectorTitle(slug), debrand(o.str("description").orEmpty()), o.str("category").orEmpty())
    }

/** `connectors.list`: `available=false` = the feature is off on this PC. */
suspend fun Hermes.connectorStates(profile: String?, owner: Map<String, String> = AccountOwner): Pair<Boolean, List<ConnectorState>> {
    val r = connectorCall("connectors.list", "profile" to profile, "owner" to owner)
    return (r.bool("available") != false) to r.list("connectors").mapNotNull { e ->
        val o = e.obj
        ConnectorState(
            connector = o.str("connector") ?: return@mapNotNull null,
            enabled = o.bool("enabled") != false,
            connected = o.bool("connected") == true,
            status = o.str("connection_status"),
            statusReason = o.str("status_reason")?.takeIf { it.isNotBlank() },
            gatewayDisabledTools = strings(o?.get("gateway_disabled_tools")),
        )
    }
}

/** `connectors.accounts`: the member's hosted accounts, optionally of one connector. */
suspend fun Hermes.connectorAccounts(profile: String?, connector: String? = null): List<ConnectorAccount> =
    connectorCall("connectors.accounts", "profile" to profile, "connector" to connector).list("accounts").mapNotNull { parseAccount(it.obj) }

/** `connectors.accounts.rename`; [alias] must match [CONNECTOR_ALIAS] (`ALIAS_TAKEN` when another account holds it). */
suspend fun Hermes.renameConnectorAccount(profile: String?, connectionId: String, alias: String): ConnectorAccount? =
    parseAccount(connectorCall("connectors.accounts.rename", "profile" to profile, "connection_id" to connectionId, "alias" to alias))

/** `connectors.accounts.remove`. */
suspend fun Hermes.removeConnectorAccount(profile: String?, connectionId: String) {
    connectorCall("connectors.accounts.remove", "profile" to profile, "connection_id" to connectionId)
}

/** `connectors.tools`: cached or current tool list of one connector. */
suspend fun Hermes.connectorTools(profile: String?, slug: String, refresh: Boolean = false): List<ConnectorTool> =
    connectorCall("connectors.tools", "profile" to profile, "slug" to slug, "refresh" to refresh).list("tools").mapNotNull { e ->
        val o = e.obj
        ConnectorTool(
            slug = o.str("slug") ?: return@mapNotNull null,
            name = o.str("name")?.takeIf { it.isNotBlank() } ?: o.str("slug").orEmpty(),
            description = debrand(o.str("description").orEmpty()),
            facet = o.str("facet") ?: "unclassified",
            hints = strings(o?.get("hints")),
            deprecated = o.bool("deprecated") == true,
        )
    }

/** `connectors.policy.get` (`NEEDS_NOUS_AUTH` before the first account exists). */
suspend fun Hermes.connectorPolicy(profile: String?): ConnectorPolicy = parsePolicy(connectorCall("connectors.policy.get", "profile" to profile))

/** `connectors.policy.set {type:"connector"}`; returns the new revision. `POLICY_CONFLICT` → refetch and retry. */
suspend fun Hermes.setConnectorEnabled(profile: String?, connector: String, enabled: Boolean, expectedRevision: String): String? =
    connectorCall("connectors.policy.set", "profile" to profile, "expected_revision" to expectedRevision,
        "change" to mapOf("type" to "connector", "connector" to connector, "enabled" to enabled)).str("revision")

/** `connectors.policy.set {type:"tools"}`: the full list of this connector's disabled tools (≤ 2000). */
suspend fun Hermes.setConnectorDisabledTools(profile: String?, connector: String, disabledTools: List<String>, expectedRevision: String): String? =
    connectorCall("connectors.policy.set", "profile" to profile, "expected_revision" to expectedRevision,
        "change" to mapOf("type" to "tools", "connector" to connector, "disabled_tools" to disabledTools.take(2000))).str("revision")

/**
 * `connectors.connect`: starts (or with [reconnect] re-initiates) sign-in. Open [ConnectionOp.connectUrl] in the system browser,
 * then follow `connection.update` events or poll [connectionStatus]. [alias] names a new account (exactly one connector);
 * [connectionId] repairs one account (needs [reconnect], no alias).
 */
suspend fun Hermes.connectConnector(
    profile: String?, connectors: List<String>, reconnect: Boolean = false, alias: String? = null, connectionId: String? = null,
    owner: Map<String, String> = AccountOwner,
): ConnectionOp = gateway.call("connectors.connect", params("profile" to profile, "owner" to owner, "connectors" to connectors,
    "reconnect" to reconnect, "alias" to alias, "connection_id" to connectionId), CONNECTOR_TIMEOUT).decodeAs()

/** `connectors.operation.status`: the operation's current snapshot. */
suspend fun Hermes.connectionStatus(profile: String?, opId: String, owner: Map<String, String> = AccountOwner): ConnectionOp =
    gateway.call("connectors.operation.status", params("profile" to profile, "owner" to owner, "op_id" to opId), CONNECTOR_TIMEOUT).decodeAs()

/** `connectors.operation.wake`: the browser leg came back; read the accounts now, not at the next tick. */
suspend fun Hermes.connectionWake(profile: String?, opId: String, owner: Map<String, String> = AccountOwner) {
    connectorCall("connectors.operation.wake", "profile" to profile, "owner" to owner, "op_id" to opId)
}

/**
 * `connection.respond`: per-target answers from a connection card, and/or `settledBy = "continue"` to stop waiting.
 * Returns whether the operation settled.
 */
suspend fun Hermes.connectionRespond(
    profile: String?, opId: String, targets: List<ConnectionAnswerTarget>? = null, settledBy: String? = null, said: String? = null,
    owner: Map<String, String> = AccountOwner,
): Boolean = connectorCall("connection.respond", "profile" to profile, "owner" to owner, "op_id" to opId,
    "result" to params(
        "targets" to targets?.map { params("name" to it.name, "status" to it.status, "detail" to it.detail, "env" to it.env) },
        "settled_by" to settledBy, "said" to said,
    )).bool("settled") == true
