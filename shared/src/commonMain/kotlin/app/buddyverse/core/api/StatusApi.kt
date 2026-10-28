package app.buddyverse.core.api

// Owner: More ▸ PC status (ui/more/StatusPage.kt, ui/more/status). hermes-api.md §2.6 "Gateway status, health and logs",
// "Gateway restart, start/stop, drain, update (background actions)", "Maintenance and diagnostics" (doctor, security audit,
// config migrate) and §2.3 "Updates and version". Extension functions only; Hermes.kt keeps status() and logs().

import app.buddyverse.core.Hermes
import app.buddyverse.core.RestError
import app.buddyverse.core.decodeAs
import app.buddyverse.core.obj
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun q(v: String) = v.encodeURLParameter()

// ---------------------------------------------------------------------------------------------------- GET /api/status

@Serializable
data class PcComponent(
    val status: String? = null,
    val state: String? = null,
    val reason: String? = null,
    val configured: Int? = null,
    val connected: Int? = null,
    @SerialName("recent_unhandled_errors") val recentErrors: Int? = null,
)

@Serializable
data class PcMemory(
    val pressure: String? = null,
    @SerialName("gateway_rss_mb") val gatewayRssMb: Double? = null,
    @SerialName("system_available_mb") val availableMb: Double? = null,
    @SerialName("last_boot_unclean") val lastBootUnclean: Boolean = false,
    @SerialName("last_boot_suspected_oom") val lastBootOom: Boolean = false,
    /** The gateway's `started_at` (ISO), i.e. its uptime origin. */
    @SerialName("boot_id") val bootId: String? = null,
)

@Serializable
data class PcDisk(
    val pressure: String? = null,
    @SerialName("total_mb") val totalMb: Double? = null,
    @SerialName("free_mb") val freeMb: Double? = null,
    @SerialName("used_percent") val usedPercent: Double? = null,
)

/** `GET /api/status` (public). Keys verified live 2026-10-26. */
@Serializable
data class PcHealth(
    val version: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("config_version") val configVersion: Int? = null,
    @SerialName("latest_config_version") val latestConfigVersion: Int? = null,
    @SerialName("can_update_hermes") val canUpdate: Boolean = false,
    @SerialName("gateway_running") val running: Boolean = false,
    /** running, draining, stopped, starting, fatal, startup_failed… */
    @SerialName("gateway_state") val state: String? = null,
    @SerialName("gateway_platforms") val platforms: Map<String, PcComponent> = emptyMap(),
    @SerialName("gateway_exit_reason") val exitReason: String? = null,
    @SerialName("gateway_heartbeat_stale_s") val heartbeatStaleS: Double? = null,
    /** A list of profiles on this build (the docs say a name); read through [sharedWith]. */
    @SerialName("gateway_shared_with") val sharedWithRaw: JsonElement? = null,
    @SerialName("active_agents") val activeAgents: Int = 0,
    @SerialName("active_sessions") val activeSessions: Int = 0,
    @SerialName("gateway_busy") val busy: Boolean = false,
    val components: Map<String, PcComponent> = emptyMap(),
    val overall: String? = null,
    val memory: PcMemory? = null,
    val disk: PcDisk? = null,
    val profiles: List<String> = emptyList(),
    @SerialName("gateway_mode") val mode: String? = null,
) {
    /** Profiles a restart takes down together (the shared multiplexer); empty when standalone. */
    val sharedWith: List<String> get() = when (val r = sharedWithRaw) {
        is JsonArray -> r.mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content }
        is JsonPrimitive -> if (r is JsonNull) emptyList() else listOf(r.content)
        else -> emptyList()
    }
    val configBehind: Boolean get() = configVersion != null && latestConfigVersion != null && configVersion < latestConfigVersion
}

suspend fun Hermes.pcHealth(): PcHealth? = status()?.decodeAs()

// ---------------------------------------------------------------------------------------------------- GET /api/system/stats

@Serializable
data class PcBytes(val total: Long? = null, val used: Long? = null, val available: Long? = null, val free: Long? = null, val percent: Double? = null)

@Serializable
data class PcProcess(val pid: Long? = null, val rss: Long? = null, @SerialName("num_threads") val threads: Int? = null)

/** `GET /api/system/stats`. Live keys are `os`/`os_release`; the docs say `system`/`release`, so both are read. */
@Serializable
data class PcStats(
    val os: String? = null,
    val system: String? = null,
    @SerialName("os_release") val osRelease: String? = null,
    val release: String? = null,
    val hostname: String? = null,
    @SerialName("cpu_count") val cpuCount: Int? = null,
    @SerialName("cpu_percent") val cpuPercent: Double? = null,
    val memory: PcBytes? = null,
    val disk: PcBytes? = null,
    @SerialName("uptime_seconds") val uptimeSeconds: Long? = null,
    val process: PcProcess? = null,
) {
    val osName: String? get() = listOfNotNull(os ?: system, osRelease ?: release).joinToString(" ").ifBlank { null }
}

suspend fun Hermes.pcStats(): PcStats? = rest.get("/api/system/stats").obj?.decodeAs()

// ---------------------------------------------------------------------------------------------------- logs

/**
 * `GET /api/logs`: the last [lines] (≤ 500) of [file] (`agent`, `errors`, `gateway`, `mcp`…), at or above [level]
 * (`INFO`/`WARNING`/`ERROR`; null = all), containing [search] (case-insensitive). Oldest first, line breaks trimmed.
 */
suspend fun Hermes.logTail(file: String, lines: Int = 300, level: String? = null, search: String? = null): List<String> {
    val path = buildString {
        append("/api/logs?file=").append(q(file)).append("&lines=").append(lines.coerceIn(1, 500))
        if (level != null) append("&level=").append(q(level))
        if (!search.isNullOrBlank()) append("&search=").append(q(search.trim()))
    }
    return rest.get(path).obj?.get("lines").let { it as? JsonArray }.orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.content?.trimEnd('\n', '\r') }
}

// ---------------------------------------------------------------------------------------------------- updates

@Serializable
data class PcCommit(val sha: String? = null, val summary: String? = null, val author: String? = null, val at: Double? = null)

/** `GET /api/hermes/update/check`. [behind]: 0 up to date, −1 unknown, null not checked. Only `git` installs [canApply]. */
@Serializable
data class PcUpdate(
    @SerialName("install_method") val installMethod: String? = null,
    @SerialName("current_version") val currentVersion: String? = null,
    val behind: Int? = null,
    @SerialName("update_available") val available: Boolean = false,
    @SerialName("can_apply") val canApply: Boolean = false,
    @SerialName("update_command") val command: String? = null,
    val message: String? = null,
    val channel: String? = null,
    val branch: String? = null,
    val commits: List<PcCommit> = emptyList(),
)

/** [force] re-fetches upstream (slower); false answers from the cached check. */
suspend fun Hermes.updateCheck(force: Boolean): PcUpdate = rest.get("/api/hermes/update/check?force=$force").decodeAs()

@Serializable
data class PcStep(val step: String? = null, val reason: String? = null)

/** `UpdateReceiptSummary`. [outcome]: running, success, partial, failed, refused (this build also writes `ok`). */
@Serializable
data class PcReceipt(
    val outcome: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    @SerialName("post_version") val postVersion: String? = null,
    val followups: List<PcStep> = emptyList(),
    @SerialName("user_action") val userAction: PcStep? = null,
    @SerialName("action_id") val actionId: String? = null,
) {
    val succeeded: Boolean get() = outcome == "success" || outcome == "partial" || outcome == "ok"
}

/** `GET /api/hermes/update/receipt` summary; null when no update has run (404). */
suspend fun Hermes.updateReceipt(): PcReceipt? = try {
    rest.get("/api/hermes/update/receipt").obj?.get("summary")?.let { if (it is JsonObject) it.decodeAs<PcReceipt>() else null }
} catch (e: RestError) {
    if (e.status == 404) null else throw e
}

// ---------------------------------------------------------------------------------------------------- background actions

/** What a start call answered: `{ok, pid, name, action_id?, already_running?}` or a refusal `{ok:false, error, message, update_command}`. */
@Serializable
data class PcActionStart(
    val ok: Boolean = true,
    val pid: Long? = null,
    val name: String? = null,
    @SerialName("action_id") val actionId: String? = null,
    @SerialName("already_running") val alreadyRunning: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    @SerialName("update_command") val command: String? = null,
)

/** `GET /api/actions/{name}/status`. */
@Serializable
data class PcActionStatus(
    val name: String? = null,
    val running: Boolean = false,
    @SerialName("exit_code") val exitCode: Int? = null,
    val pid: Long? = null,
    val lines: List<String> = emptyList(),
    @SerialName("action_id") val actionId: String? = null,
    val receipt: PcReceipt? = null,
)

suspend fun Hermes.actionStatus(name: String, lines: Int = 300): PcActionStatus =
    rest.get("/api/actions/${q(name)}/status?lines=${lines.coerceIn(1, 2000)}").decodeAs()

private suspend fun Hermes.startAt(path: String): PcActionStart = rest.post(path).obj?.decodeAs() ?: PcActionStart()

/** `POST /api/gateway/restart` (no profile = the shared engine): a forced restart that interrupts live turns of every bot. */
suspend fun Hermes.gatewayRestart(): PcActionStart = startAt("/api/gateway/restart")

/** `POST /api/gateway/start` (action `gateway-start`). */
suspend fun Hermes.gatewayStartAction(): PcActionStart = startAt("/api/gateway/start")

/** `POST /api/hermes/update` (action `hermes-update`); restarts the backend at the end. */
suspend fun Hermes.updateApply(): PcActionStart = startAt("/api/hermes/update")

/** `POST /api/ops/doctor` (action `doctor`). */
suspend fun Hermes.runDoctor(): PcActionStart = startAt("/api/ops/doctor")

/** `POST /api/ops/security-audit` (action `security-audit`). */
suspend fun Hermes.runSecurityAudit(): PcActionStart = startAt("/api/ops/security-audit")

/** `POST /api/ops/config-migrate` (action `config-migrate`): brings config.yaml up to the latest config version. */
suspend fun Hermes.runConfigMigrate(): PcActionStart = startAt("/api/ops/config-migrate")
