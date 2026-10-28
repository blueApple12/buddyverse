package app.buddyverse.core.api

import app.buddyverse.core.Hermes
import app.buddyverse.core.RestError
import app.buddyverse.core.bool
import app.buddyverse.core.child
import app.buddyverse.core.list
import app.buddyverse.core.long
import app.buddyverse.core.obj
import app.buddyverse.core.params
import app.buddyverse.core.str
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Owner: More ▸ MCP servers (ui/more/McpPage.kt). hermes-api.md §2.6 "MCP servers": the Desktop's exact flow.

private fun q(v: String) = v.encodeURLParameter()

/** `mcp.servers.status`: cached runtime rows (`status`, `tools`, `connected`); never connects. */
suspend fun Hermes.mcpStatus(profile: String): JsonObject? = gateway.call("mcp.servers.status", params("profile" to profile)).obj

/** The richer REST catalog (`required_env` with prompts, `auth_type`, `needs_install`, `installed`). */
suspend fun Hermes.mcpCatalog(profile: String): List<JsonObject> =
    rest.get("/api/mcp/catalog?profile=${q(profile)}").obj.list("entries").mapNotNull { it.obj }

/** Installs a catalog entry; a background install is polled until it ends. Throws [RestError] with the reason. */
suspend fun Hermes.mcpInstall(profile: String, name: String, env: Map<String, String>) {
    val r = rest.post("/api/mcp/catalog/install?profile=${q(profile)}", params("name" to name, "env" to env, "enable" to true, "profile" to profile), timeoutMs = 300_000).obj
    val action = r.str("action")
    if (r.bool("background") != true || action == null) return
    // ponytail: fixed 1.5 s poll for at most 10 min; installs report no progress worth streaming.
    repeat(400) {
        delay(1_500)
        val s = rest.get("/api/actions/${q(action)}/status?lines=1&profile=${q(profile)}").obj
        if (s.bool("running") == false) {
            val code = s.long("exit_code") ?: 0L
            if (code != 0L) throw RestError(500, s.list("lines").lastOrNull()?.let { (it as? JsonPrimitive)?.content } ?: "exit $code")
            return
        }
    }
}

/** `POST /api/mcp/servers` (MCPServerCreate): exactly one of [url] / [command]. */
suspend fun Hermes.mcpAdd(profile: String, name: String, url: String?, command: String?, args: List<String>, env: Map<String, String>,
    auth: String?, bearerToken: String?): JsonObject? =
    rest.post("/api/mcp/servers?profile=${q(profile)}", params("name" to name, "url" to url, "command" to command,
        "args" to args.takeIf { command != null }, "env" to env.takeIf { command != null }, "auth" to auth,
        "bearer_token" to bearerToken?.takeIf { it.isNotBlank() }, "profile" to profile)).obj

/** The saved `mcp_servers` map with real values (`GET /api/config`); the base of every edit. */
suspend fun Hermes.mcpRawServers(profile: String): JsonObject =
    rest.get("/api/config?profile=${q(profile)}&include_defaults=false").obj.child("mcp_servers") ?: JsonObject(emptyMap())

/** `PUT /api/mcp/servers`: REPLACES the whole map. Always send [mcpRawServers] with one entry changed. */
suspend fun Hermes.mcpPutServers(profile: String, servers: JsonObject): JsonElement =
    rest.put("/api/mcp/servers?profile=${q(profile)}", params("servers" to servers, "profile" to profile))

suspend fun Hermes.mcpRemove(profile: String, name: String): JsonElement = rest.delete("/api/mcp/servers/${q(name)}?profile=${q(profile)}")

suspend fun Hermes.mcpSetEnabled(profile: String, name: String, enabled: Boolean): JsonElement =
    rest.put("/api/mcp/servers/${q(name)}/enabled?profile=${q(profile)}", params("enabled" to enabled, "profile" to profile))

/** `mcp.servers.test`: connects and lists tools (`ok, tools, error?, oauth_needed`). Runs on the RPC pool: long timeout. */
suspend fun Hermes.mcpTest(profile: String, name: String): JsonObject? =
    gateway.call("mcp.servers.test", params("name" to name, "profile" to profile), timeoutMs = 90_000).obj

/** HTTP bearer key (`Authorization: Bearer ${MCP_<NAME>_API_KEY}`); the value goes to the profile's .env only. */
suspend fun Hermes.mcpSetApiKey(profile: String, name: String, value: String): JsonObject? =
    gateway.call("mcp.servers.set_api_key", params("name" to name, "value" to value, "profile" to profile)).obj

/** Remote-safe OAuth (redirects to `<public_url>/api/mcp/oauth/callback/{name}`): `{flow_id, status, authorization_url, error}`. */
suspend fun Hermes.mcpOAuthStart(profile: String, name: String): JsonObject? =
    rest.post("/api/mcp/servers/${q(name)}/auth?profile=${q(profile)}", params("profile" to profile)).obj

suspend fun Hermes.mcpOAuthFlow(flowId: String): JsonObject? = rest.get("/api/mcp/oauth/flows/${q(flowId)}").obj

suspend fun Hermes.mcpOAuthCancel(flowId: String): JsonElement = rest.delete("/api/mcp/oauth/flows/${q(flowId)}")

/** Apply MCP changes to live sessions now (`ReloadMcpResult`: `status`, `session`, `unavailable[]`). */
suspend fun Hermes.reloadMcp(): JsonObject? = gateway.call("reload.mcp", params("confirm" to true)).obj
