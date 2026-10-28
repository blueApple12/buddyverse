package app.buddyverse.core.api

// Owner: More ▸ Webhooks (ui/more/WebhooksPage.kt). hermes-api.md §2.5 "Webhooks" (server: hermes_cli/web_routers/ops.py).
// Every call names the profile: DELETE refuses an unnamed one on a gateway that serves several bots.

import app.buddyverse.core.Hermes
import app.buddyverse.core.RestError
import app.buddyverse.core.bool
import app.buddyverse.core.list
import app.buddyverse.core.long
import app.buddyverse.core.obj
import app.buddyverse.core.params
import app.buddyverse.core.str
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun q(v: String) = v.encodeURLParameter()

private fun JsonObject?.strings(key: String): List<String> = list(key).mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }

/** One subscription as `GET /api/webhooks` lists it. The secret itself is never listed ([secretSet] only). */
data class WebhookRoute(
    val name: String,
    val description: String,
    /** Empty = every event. */
    val events: List<String>,
    /** `log, telegram, discord, slack, email, github_comment` (or anything the CLI wrote). */
    val deliver: String,
    /** Sends the payload straight to [deliver] without the bot. */
    val deliverOnly: Boolean,
    val prompt: String,
    val script: String,
    val skills: List<String>,
    val createdAt: String?,
    /** Where senders POST (the PC's webhook listener, default port 8644), not the app's link. */
    val url: String,
    val secretSet: Boolean,
    val enabled: Boolean,
) {
    companion object {
        fun from(o: JsonObject): WebhookRoute? {
            val name = o.str("name")?.takeIf { it.isNotBlank() } ?: return null
            return WebhookRoute(
                name = name, description = o.str("description").orEmpty(), events = o.strings("events"),
                deliver = o.str("deliver")?.ifBlank { null } ?: "log", deliverOnly = o.bool("deliver_only") == true,
                prompt = o.str("prompt").orEmpty(), script = o.str("script").orEmpty(), skills = o.strings("skills"),
                createdAt = o.str("created_at"), url = o.str("url").orEmpty(), secretSet = o.bool("secret_set") == true,
                enabled = o.bool("enabled") != false,
            )
        }
    }
}

/** [platformOn] = the webhook listener is switched on for this bot; routes can only be added while it is. */
data class WebhookList(val platformOn: Boolean, val baseUrl: String, val routes: List<WebhookRoute>)

/**
 * The link senders use. REST lists it bare; the listener routes a non-default bot only under `/p/<profile>/` (the CLI's
 * `_route_url` form; a bare link would hit the default bot), so that form is shown for every other bot.
 */
fun hookUrl(url: String, profile: String): String =
    if (profile == "default" || url.isEmpty() || "/p/" in url) url else url.replaceFirst("/webhooks/", "/p/$profile/webhooks/")

private fun WebhookRoute.forProfile(profile: String) = copy(url = hookUrl(url, profile))

suspend fun Hermes.webhookList(profile: String): WebhookList {
    val r = rest.get("/api/webhooks?profile=${q(profile)}").obj
    return WebhookList(r.bool("enabled") == true, r.str("base_url").orEmpty(),
        r.list("subscriptions").mapNotNull { it.obj?.let(WebhookRoute::from)?.forProfile(profile) })
}

const val WEBHOOK_RESTART_ACTION = "gateway-restart"

/** `POST /api/webhooks/enable`: the server restarts the engine itself when it can ([restartStarted]); [busy] = live chats refused it. */
data class WebhookEnableResult(val restartStarted: Boolean, val action: String, val error: String?, val busy: Boolean)

suspend fun Hermes.webhookEnablePlatform(profile: String): WebhookEnableResult {
    val r = rest.post("/api/webhooks/enable?profile=${q(profile)}").obj
    return WebhookEnableResult(
        restartStarted = r.bool("restart_started") == true, action = r.str("restart_action") ?: WEBHOOK_RESTART_ACTION,
        error = r.str("restart_error")?.ifBlank { null }, busy = r.bool("restart_busy") == true,
    )
}

/** The `WebhookCreate` body. Blank optional fields are left out (the server generates a secret when [secret] is blank). */
data class WebhookDraft(
    val name: String,
    val description: String = "",
    val events: List<String> = emptyList(),
    val prompt: String = "",
    val script: String = "",
    val skills: List<String> = emptyList(),
    val deliver: String = "log",
    val deliverOnly: Boolean = false,
    val chatId: String = "",
    val secret: String = "",
)

/** The new route and its secret, which the server returns only this once. */
data class WebhookCreated(val route: WebhookRoute, val secret: String)

/**
 * `POST /api/webhooks`. An existing [WebhookDraft.name] is REPLACED (new secret, CLI-only keys dropped), so callers check
 * the name is free first. 400 = platform off, bad name, or direct delivery to `log`; 409 = changed meanwhile.
 */
suspend fun Hermes.webhookCreate(profile: String, d: WebhookDraft): WebhookCreated {
    val r = rest.post("/api/webhooks?profile=${q(profile)}", params(
        "name" to d.name, "description" to d.description.trim().ifBlank { null }, "events" to d.events,
        "prompt" to d.prompt.trim().ifBlank { null }, "script" to d.script.trim().ifBlank { null }, "skills" to d.skills,
        "deliver" to d.deliver, "deliver_only" to d.deliverOnly, "deliver_chat_id" to d.chatId.trim().ifBlank { null },
        "secret" to d.secret.trim().ifBlank { null },
    )).obj
    val route = r?.let(WebhookRoute::from)?.forProfile(profile) ?: throw RestError(500, "no route in reply")
    return WebhookCreated(route, r.str("secret").orEmpty())
}

/** Disabled routes stay saved; the listener answers their senders 403. Hot-reloads, no restart. */
suspend fun Hermes.webhookSetEnabled(profile: String, name: String, enabled: Boolean): JsonElement =
    rest.put("/api/webhooks/${q(name)}/enabled?profile=${q(profile)}", params("enabled" to enabled))

suspend fun Hermes.webhookDelete(profile: String, name: String): JsonElement = rest.delete("/api/webhooks/${q(name)}?profile=${q(profile)}")

/**
 * User-commanded `POST /api/gateway/restart` (`hermes gateway restart --interrupt`: live turns stop). Only offered when
 * turning webhooks on could not restart the engine by itself. Returns the action name to wait on.
 */
suspend fun Hermes.webhookRestartEngine(profile: String): String =
    rest.post("/api/gateway/restart?profile=${q(profile)}").obj.str("name") ?: WEBHOOK_RESTART_ACTION

/**
 * Waits out a restart of the engine that serves these very calls (Desktop `awaitAction`): refusals mid-window are
 * expected; a fresh process answers `running:false, exit_code:null` (or 404) and counts as back. True once an answer
 * proves it is back; throws [RestError] on a recorded non-zero exit.
 */
suspend fun Hermes.webhookAwaitRestart(profile: String, action: String): Boolean {
    var answered = false
    // ponytail: fixed 1.5 s × 40 window (1 min); the restart reports no progress worth streaming.
    repeat(40) {
        delay(1_500)
        val s = try { rest.get("/api/actions/${q(action)}/status?lines=1&profile=${q(profile)}").obj }
        catch (e: CancellationException) { throw e }
        catch (e: RestError) { if (e.status == 404) return true; return@repeat }
        catch (e: Exception) { return@repeat }
        answered = true
        if (s.bool("running") == false) {
            val code = s.long("exit_code") ?: return true
            if (code != 0L) throw RestError(500, s.strings("lines").lastOrNull() ?: "exit $code")
            return true
        }
    }
    return answered
}
