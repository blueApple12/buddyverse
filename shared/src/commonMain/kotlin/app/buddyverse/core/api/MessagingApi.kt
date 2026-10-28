package app.buddyverse.core.api

import androidx.compose.runtime.Immutable
import app.buddyverse.core.Hermes
import app.buddyverse.core.decodeAs
import app.buddyverse.core.list
import app.buddyverse.core.long
import app.buddyverse.core.obj
import app.buddyverse.core.params
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Owner: More ▸ Messaging (ui/more/MessagingPage.kt, ui/more/messaging). hermes-api.md §2.6 "Messaging platforms (Channels)".
// All REST with `?profile=`. A Telegram / WhatsApp QR setup lives in the memory of the backend that started it:
// keep the same profile for its start, status and apply.

private fun q(v: String) = v.encodeURLParameter()

/** Every wire model here has defaults, so a missing body or key never fails the decode. */
private inline fun <reified T> JsonElement?.model(): T = (this.obj ?: JsonObject(emptyMap())).decodeAs()

/** One setting of a platform (`env_vars[]`). [value] is sent back only for allowlist keys ([isList]). */
@Immutable
@Serializable
data class MsgEnvVar(
    val key: String = "",
    val required: Boolean = false,
    @SerialName("is_set") val isSet: Boolean = false,
    @SerialName("redacted_value") val redactedValue: String? = null,
    val value: String? = null,
    val description: String = "",
    val prompt: String = "",
    val help: String = "",
    val url: String? = null,
    @SerialName("is_password") val isPassword: Boolean = false,
    @SerialName("is_list") val isList: Boolean = false,
    val advanced: Boolean = false,
)

@Immutable
@Serializable
data class MsgHomeChannel(val platform: String? = null, @SerialName("chat_id") val chatId: String? = null, val name: String? = null)

@Immutable
@Serializable
data class MsgWhatsappSetup(
    val mode: String? = null,
    @SerialName("allowed_users_set") val allowedUsersSet: Boolean = false,
    @SerialName("home_channel_set") val homeChannelSet: Boolean = false,
)

/**
 * `MessagingPlatformInfo`. [state]: runtime (`connected`, `connecting`, `fatal`, `degraded`, `draining`, …) or computed
 * (`disabled`, `not_configured`, `pending_restart`, `startup_failed`, `gateway_stopped`).
 */
@Immutable
@Serializable
data class MsgPlatform(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    @SerialName("docs_url") val docsUrl: String? = null,
    val enabled: Boolean = false,
    val configured: Boolean = false,
    @SerialName("gateway_running") val gatewayRunning: Boolean = false,
    val state: String = "",
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("home_channel") val homeChannel: MsgHomeChannel? = null,
    @SerialName("ingress_url") val ingressUrl: String? = null,
    @SerialName("env_vars") val envVars: List<MsgEnvVar> = emptyList(),
    @SerialName("whatsapp_setup") val whatsappSetup: MsgWhatsappSetup? = null,
)

/** `PUT /api/messaging/platforms/{id}`. [hotServed] = applied live; otherwise a gateway restart applies it. */
@Serializable
data class MsgSaved(val ok: Boolean = false, @SerialName("hot_served") val hotServed: Boolean = false)

/** `POST …/test`. [authPosture]: open | allowlist | pairing | deny_all. */
@Immutable
@Serializable
data class MsgTest(val ok: Boolean = false, val state: String? = null, val message: String? = null, @SerialName("auth_posture") val authPosture: String? = null)

/** A person who asked to chat (pending, approve by [requestId]) or may chat (approved, revoke by [userId]). */
@Immutable
@Serializable
data class MsgPairingUser(
    val platform: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("user_name") val userName: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("age_minutes") val ageMinutes: Double? = null,
)

@Serializable
data class MsgPairing(val pending: List<MsgPairingUser> = emptyList(), val approved: List<MsgPairingUser> = emptyList())

/** Telegram QR setup: the start answer and every status answer (`waiting` → `ready`). */
@Serializable
data class MsgTelegramSetup(
    @SerialName("pairing_id") val pairingId: String? = null,
    @SerialName("suggested_username") val suggestedUsername: String? = null,
    @SerialName("deep_link") val deepLink: String? = null,
    @SerialName("qr_payload") val qrPayload: String? = null,
    val status: String? = null,
    @SerialName("bot_username") val botUsername: String? = null,
    @SerialName("owner_user_id") val ownerUserId: String? = null,
)

/** WhatsApp QR setup. [status]: starting → installing → waiting (show [qrPayload]) → connected | error | expired | cancelled. */
@Serializable
data class MsgWhatsappSession(
    @SerialName("pairing_id") val pairingId: String? = null,
    val status: String? = null,
    @SerialName("qr_payload") val qrPayload: String? = null,
    val mode: String? = null,
    @SerialName("allowed_users") val allowedUsers: String? = null,
    @SerialName("account_name") val accountName: String? = null,
    @SerialName("account_phone") val accountPhone: String? = null,
    val error: String? = null,
)

/** A QR setup's apply. [restartStarted] = the backend restarts itself; [needsRestart] and not started = offer a restart. */
@Serializable
data class MsgSetupApplied(
    val ok: Boolean = false,
    @SerialName("bot_username") val botUsername: String? = null,
    @SerialName("needs_restart") val needsRestart: Boolean = false,
    @SerialName("restart_started") val restartStarted: Boolean = false,
    @SerialName("restart_pid") val restartPid: Long? = null,
    @SerialName("restart_error") val restartError: String? = null,
    @SerialName("restart_busy") val restartBusy: Boolean = false,
)

/** `GET /api/actions/{name}/status`. */
@Serializable
data class MsgActionStatus(val running: Boolean = false, @SerialName("exit_code") val exitCode: Int? = null, val pid: Long? = null, val lines: List<String> = emptyList())

// ---------------------------------------------------------------------------------------------------- platforms

/** Every platform (built-in and plugin) with its state and settings, through the existing [Hermes.platforms]. */
suspend fun Hermes.messagingPlatforms(profile: String): List<MsgPlatform> =
    platforms(profile).list("platforms").mapNotNull { e -> runCatching { e.model<MsgPlatform>() }.getOrNull()?.takeIf { it.id.isNotBlank() } }.distinctBy { it.id }

/**
 * Turns a platform on or off and/or saves settings. [env] values must be real (blank ones are skipped; a redacted preview is
 * refused with 400); [clearEnv] removes saved keys. 409 = port conflict on a secondary profile.
 */
suspend fun Hermes.messagingUpdate(profile: String, id: String, enabled: Boolean? = null, env: Map<String, String> = emptyMap(), clearEnv: List<String> = emptyList()): MsgSaved =
    rest.put("/api/messaging/platforms/${q(id)}?profile=${q(profile)}", params(
        "enabled" to enabled, "env" to env.takeIf { it.isNotEmpty() }, "clear_env" to clearEnv.takeIf { it.isNotEmpty() }, "profile" to profile,
    )).model()

suspend fun Hermes.messagingTest(profile: String, id: String): MsgTest =
    rest.post("/api/messaging/platforms/${q(id)}/test?profile=${q(profile)}", params("profile" to profile)).model()

// ---------------------------------------------------------------------------------------------------- Telegram QR setup

suspend fun Hermes.messagingTelegramStart(profile: String, botName: String?): MsgTelegramSetup =
    rest.post("/api/messaging/telegram/onboarding/start?profile=${q(profile)}", params("bot_name" to botName?.takeIf { it.isNotBlank() })).model()

/** 410 = expired or already claimed, 404 = unknown. */
suspend fun Hermes.messagingTelegramStatus(profile: String, pairingId: String): MsgTelegramSetup =
    rest.get("/api/messaging/telegram/onboarding/${q(pairingId)}?profile=${q(profile)}").model()

/** [allowedUserIds]: numeric Telegram user ids, at least one (the owner's). */
suspend fun Hermes.messagingTelegramApply(profile: String, pairingId: String, allowedUserIds: List<String>): MsgSetupApplied =
    rest.post("/api/messaging/telegram/onboarding/${q(pairingId)}/apply?profile=${q(profile)}",
        params("allowed_user_ids" to allowedUserIds, "profile" to profile)).model()

suspend fun Hermes.messagingTelegramCancel(profile: String, pairingId: String) {
    rest.delete("/api/messaging/telegram/onboarding/${q(pairingId)}?profile=${q(profile)}")
}

// ---------------------------------------------------------------------------------------------------- WhatsApp QR setup

/** [mode]: `bot` (its own number) or `self-chat` (your number, message yourself). [reset] re-pairs (409 while the bridge runs). */
suspend fun Hermes.messagingWhatsappStart(profile: String, mode: String, allowedUsers: String?, reset: Boolean): MsgWhatsappSession =
    rest.post("/api/messaging/whatsapp/onboarding/start?profile=${q(profile)}", params(
        "mode" to mode, "allowed_users" to allowedUsers?.takeIf { it.isNotBlank() }, "profile" to profile, "reset_session" to reset.takeIf { it },
    )).model()

suspend fun Hermes.messagingWhatsappStatus(profile: String, pairingId: String): MsgWhatsappSession =
    rest.get("/api/messaging/whatsapp/onboarding/${q(pairingId)}?profile=${q(profile)}").model()

/** Needs `status: connected` (else 409). */
suspend fun Hermes.messagingWhatsappApply(profile: String, pairingId: String, mode: String, allowedUsers: String?): MsgSetupApplied =
    rest.post("/api/messaging/whatsapp/onboarding/${q(pairingId)}/apply?profile=${q(profile)}",
        params("mode" to mode, "allowed_users" to allowedUsers?.takeIf { it.isNotBlank() }, "profile" to profile)).model()

suspend fun Hermes.messagingWhatsappCancel(profile: String, pairingId: String) {
    rest.delete("/api/messaging/whatsapp/onboarding/${q(pairingId)}?profile=${q(profile)}")
}

// ---------------------------------------------------------------------------------------------------- pairing (who may chat)

suspend fun Hermes.messagingPairing(profile: String): MsgPairing = rest.get("/api/pairing?profile=${q(profile)}").model()

/** Approve by request id (the code is never returned by the API). 429 = locked out after too many tries. */
suspend fun Hermes.messagingApprove(profile: String, platform: String, requestId: String) {
    rest.post("/api/pairing/approve?profile=${q(profile)}", params("platform" to platform, "request_id" to requestId, "profile" to profile))
}

suspend fun Hermes.messagingRevoke(profile: String, platform: String, userId: String) {
    rest.post("/api/pairing/revoke?profile=${q(profile)}", params("platform" to platform, "user_id" to userId, "profile" to profile))
}

suspend fun Hermes.messagingClearPending(profile: String) {
    rest.post("/api/pairing/clear-pending?profile=${q(profile)}", params("profile" to profile))
}

// ---------------------------------------------------------------------------------------------------- restart

/** `POST /api/gateway/restart`: a forced restart (interrupts live turns of every bot it serves); returns its pid. Poll [messagingRestartStatus]. */
suspend fun Hermes.messagingRestart(profile: String): Long? = rest.post("/api/gateway/restart?profile=${q(profile)}").obj.long("pid")

suspend fun Hermes.messagingRestartStatus(): MsgActionStatus = rest.get("/api/actions/gateway-restart/status?lines=20").model()
