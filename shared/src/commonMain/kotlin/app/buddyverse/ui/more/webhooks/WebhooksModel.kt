package app.buddyverse.ui.more.webhooks

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.AppStore
import app.buddyverse.core.RestError
import app.buddyverse.core.api.WEBHOOK_RESTART_ACTION
import app.buddyverse.core.api.WebhookCreated
import app.buddyverse.core.api.WebhookDraft
import app.buddyverse.core.api.WebhookRoute
import app.buddyverse.core.api.webhookAwaitRestart
import app.buddyverse.core.api.webhookCreate
import app.buddyverse.core.api.webhookDelete
import app.buddyverse.core.api.webhookEnablePlatform
import app.buddyverse.core.api.webhookList
import app.buddyverse.core.api.webhookRestartEngine
import app.buddyverse.core.api.webhookSetEnabled
import app.buddyverse.core.nowMillis
import app.buddyverse.design.Feedback
import app.buddyverse.design.Haptic
import app.buddyverse.design.Sound
import app.buddyverse.i18n.WebhooksPageStrings
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.Toasts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** `deliver` targets the Desktop offers (hermes-api.md §2.5); a CLI-made route may carry another one. */
val DELIVER_TARGETS = listOf("log", "telegram", "discord", "slack", "email", "github_comment")

private val NAME_OK = Regex("^[a-z0-9][a-z0-9_-]*$")

/** What the server does to a name (`strip().lower().replace(" ", "-")`), applied as you type. */
fun webhookName(raw: String): String = raw.lowercase().replace(' ', '-')

/** Comma or space separated words → list (events and skill names have no spaces). */
fun words(text: String): List<String> = text.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

private fun Throwable.why(): String = (this as? RestError)?.detail?.takeIf { it.isNotBlank() } ?: message ?: toString()

/**
 * State and intents of More ▸ Webhooks for one bot ([profile]). Writes run on the store's scope, so they survive leaving
 * the page. Webhook CRUD hot-reloads on the PC; only turning the platform on needs an engine restart.
 */
@Stable
class WebhooksModel(private val store: AppStore, private val toasts: Toasts, private val fb: Feedback) {
    private val api get() = store.api
    lateinit var t: WebhooksPageStrings
    var undoLabel = "Undo"

    var profile by mutableStateOf<String?>(null); private set
    var loaded by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var unsupported by mutableStateOf(false); private set
    var platformOn by mutableStateOf(false); private set
    var routes by mutableStateOf<List<WebhookRoute>>(emptyList()); private set
    /** Rows waiting out their undo window. */
    var hidden by mutableStateOf<Set<String>>(emptySet()); private set
    var enabling by mutableStateOf(false); private set
    var restarting by mutableStateOf(false); private set
    /** Non-null = the engine still needs a restart; the text says why. */
    var restartNeeded by mutableStateOf<String?>(null); private set
    var creating by mutableStateOf(false); private set
    /** Bumps on a success the buddy should react to (serial = timestamp, so a later buddy skips a stale cue). */
    var cue by mutableStateOf<BuddyCue?>(null); private set
    var failedAt by mutableStateOf(0L); private set

    val visible: List<WebhookRoute> get() = routes.filter { it.name !in hidden }
    fun route(name: String?): WebhookRoute? = routes.firstOrNull { it.name == name }

    private fun react(shot: OneShot) { cue = BuddyCue(nowMillis(), shot) }
    private fun ouch() { failedAt = nowMillis(); fb.sound(Sound.Bonk); fb.haptic(Haptic.Reject) }
    private fun fail(msg: String) { ouch(); toasts.show(ToastData(msg)) }

    fun load(p: String) {
        if (p != profile) {
            profile = p; routes = emptyList(); loaded = false; error = null; unsupported = false; platformOn = false; hidden = emptySet()
        }
        store.scope.launch { refresh(p) }
    }

    /** Silent re-read (the page polls; agents can add routes from the PC). Skipped while the engine restarts. */
    suspend fun poll() { val p = profile ?: return; if (!restarting && !enabling) refresh(p) }

    private suspend fun refresh(p: String) {
        try {
            val l = api.webhookList(p)
            if (p != profile) return
            routes = l.routes; platformOn = l.platformOn; loaded = true; error = null; unsupported = false
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (p != profile) return
            loaded = true
            when {
                e is RestError && e.status == 404 -> unsupported = true
                routes.isEmpty() -> error = e.why()   // with rows on screen a failed re-read stays quiet
            }
        }
    }

    /** Turns the webhook listener on. The server restarts the engine itself when it can; otherwise we ask the user. */
    fun enable() {
        val p = profile ?: return
        if (enabling || restarting) return
        enabling = true
        restartNeeded = null
        store.scope.launch {
            var turnedOn = false
            try {
                val r = api.webhookEnablePlatform(p)
                turnedOn = true
                if (p == profile) platformOn = true
                if (r.restartStarted) {
                    enabling = false
                    if (awaitEngine(p, r.action)) { done(t.webhooksOn) } else { restartNeeded = t.restartNeeded }
                } else {
                    restartNeeded = r.error?.let(t.restartFailed) ?: if (r.busy) t.restartBusy else t.restartNeeded
                    fb.haptic(Haptic.Tick)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (turnedOn) restartNeeded = t.restartFailed(e.why())
                fail(e.why())
            } finally { enabling = false; refresh(p) }
        }
    }

    /** User-confirmed forced restart (live turns stop). */
    fun restart() {
        val p = profile ?: return
        if (restarting) return
        restarting = true
        store.scope.launch {
            try {
                // The engine may drop this very request as it goes down: wait for it anyway.
                val action = try { api.webhookRestartEngine(p) } catch (e: RestError) { throw e } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { WEBHOOK_RESTART_ACTION }
                if (awaitEngine(p, action)) {
                    restartNeeded = null; done(t.restarted)
                } else {
                    restartNeeded = t.restartNeeded; ouch()
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { restartNeeded = t.restartFailed(e.why()); ouch() }
            finally { restarting = false; refresh(p) }
        }
    }

    private suspend fun awaitEngine(p: String, action: String): Boolean {
        restarting = true
        return try { api.webhookAwaitRestart(p, action) } finally { restarting = false }
    }

    private fun done(text: String) {
        toasts.show(ToastData(text)); fb.sound(Sound.Bloop); fb.haptic(Haptic.Confirm); react(OneShot.Done)
    }

    fun toggle(r: WebhookRoute, on: Boolean) {
        val p = profile ?: return
        routes = routes.map { if (it.name == r.name) it.copy(enabled = on) else it }
        react(if (on) OneShot.Nod else OneShot.LookAround)
        store.scope.launch {
            try { api.webhookSetEnabled(p, r.name, on) } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e.why()) }
            refresh(p)
        }
    }

    /** Optimistic delete with an undo window; the server call happens only once the toast is gone. */
    fun delete(r: WebhookRoute) {
        val p = profile ?: return
        hidden = hidden + r.name
        fb.haptic(Haptic.Reject)
        val job = store.scope.launch {
            delay(4_500)
            try { api.webhookDelete(p, r.name) } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e.why()) }
            refresh(p)
            hidden = hidden - r.name
        }
        toasts.show(ToastData(t.removed(r.name), undoLabel, onAction = { job.cancel(); hidden = hidden - r.name }))
    }

    /** Checks the draft, then creates it. [onError] gets the reason; [onCreated] the route and its one-time secret. */
    fun create(d: WebhookDraft, onError: (String) -> Unit, onCreated: (WebhookCreated) -> Unit) {
        val p = profile ?: return
        if (creating) return
        val name = webhookName(d.name.trim())
        val err = when {
            name.isEmpty() -> t.needName
            !NAME_OK.matches(name) -> t.badName
            routes.any { it.name == name } -> t.nameTaken   // a POST with a used name would replace that route and its secret
            d.deliverOnly && d.deliver == "log" -> t.directNeedsTarget
            else -> null
        }
        if (err != null) { fb.haptic(Haptic.Reject); onError(err); return }
        creating = true
        store.scope.launch {
            try {
                val made = api.webhookCreate(p, d.copy(name = name))
                if (p == profile) routes = routes.filter { it.name != made.route.name } + made.route
                fb.sound(Sound.Bloop); fb.haptic(Haptic.Confirm); react(OneShot.Learned)
                onCreated(made)
                refresh(p)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { ouch(); onError(e.why()) }
            finally { creating = false }
        }
    }
}
