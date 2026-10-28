package app.buddyverse.ui.more.mcp

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.AppStore
import app.buddyverse.core.RestError
import app.buddyverse.core.RpcError
import app.buddyverse.core.api.forOtherBots
import app.buddyverse.core.api.mcpAdd
import app.buddyverse.core.api.mcpCatalog
import app.buddyverse.core.api.mcpInstall
import app.buddyverse.core.api.mcpOAuthCancel
import app.buddyverse.core.api.mcpOAuthFlow
import app.buddyverse.core.api.mcpOAuthStart
import app.buddyverse.core.api.mcpPutServers
import app.buddyverse.core.api.mcpRawServers
import app.buddyverse.core.api.mcpRemove
import app.buddyverse.core.api.mcpSetApiKey
import app.buddyverse.core.api.mcpSetEnabled
import app.buddyverse.core.api.mcpStatus
import app.buddyverse.core.api.mcpTest
import app.buddyverse.core.api.reloadMcp
import app.buddyverse.core.bool
import app.buddyverse.core.child
import app.buddyverse.core.list
import app.buddyverse.core.long
import app.buddyverse.core.nowMillis
import app.buddyverse.core.obj
import app.buddyverse.core.str
import app.buddyverse.core.toJsonValue
import app.buddyverse.design.Feedback
import app.buddyverse.design.Haptic
import app.buddyverse.design.Sound
import app.buddyverse.i18n.McpPageStrings
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.Toasts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One `mcp.servers.list` row (RPC shape: env = key names only, so no secret ever reaches the phone here). */
@Immutable
data class McpServer(
    val name: String,
    val url: String?,
    val command: String?,
    val args: List<String>,
    val envKeys: List<String>,
    val auth: String?,
    val enabled: Boolean,
    val include: List<String>?,
    val exclude: List<String>,
    val plugin: String?,
) {
    val isUrl: Boolean get() = command == null
    val readOnly: Boolean get() = plugin != null
    val target: String get() = url ?: (listOfNotNull(command) + args).joinToString(" ")
    fun toolOn(tool: String) = (include == null || tool in include) && tool !in exclude

    /** The filter after switching [tool]: an `include` list stays an include list, otherwise it goes to `exclude`. */
    fun withTool(tool: String, on: Boolean): McpServer = when {
        include != null -> copy(include = if (on) (include + tool).distinct() else include - tool, exclude = if (on) exclude - tool else exclude)
        on -> copy(exclude = exclude - tool)
        else -> copy(exclude = (exclude + tool).distinct())
    }

    companion object {
        fun from(o: JsonObject): McpServer? {
            val name = o.str("name") ?: return null
            val tools = o.child("tools")
            return McpServer(
                name = name, url = o.str("url"), command = o.str("command"),
                args = o.list("args").mapNotNull { (it as? JsonPrimitive)?.content },
                envKeys = o.list("env").mapNotNull { (it as? JsonPrimitive)?.content },
                auth = o.str("auth"), enabled = o.bool("enabled") != false,
                include = (tools?.get("include") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content },
                exclude = tools.list("exclude").mapNotNull { (it as? JsonPrimitive)?.content },
                plugin = o.str("plugin")?.ifBlank { null } ?: "plugin".takeIf { o.str("source") == "plugin" },
            )
        }
    }
}

@Immutable
data class McpTool(val name: String, val description: String)

/** A `mcp.servers.test` run: [ok] is null while it runs. */
@Immutable
data class McpProbe(val ok: Boolean? = null, val tools: List<McpTool> = emptyList(), val error: String? = null, val oauthNeeded: Boolean = false)

enum class McpState { Off, Connected, Connecting, Failed, NeedsAuth, Idle }

private val AUTH_ERROR = Regex("""\b(401|unauthorized|forbidden|invalid[_ ]?token|authentication|oauth)\b""", RegexOption.IGNORE_CASE)

/** Desktop `mcp-status.ts statusOf`, plus the cached runtime row from `mcp.servers.status`. */
fun mcpStateOf(s: McpServer, runtime: String?, probe: McpProbe?): McpState = when {
    !s.enabled -> McpState.Off
    probe != null -> when (probe.ok) {
        null -> McpState.Connecting
        true -> McpState.Connected
        false -> if (probe.oauthNeeded || AUTH_ERROR.containsMatchIn(probe.error.orEmpty())) McpState.NeedsAuth else McpState.Failed
    }
    runtime == "connected" -> McpState.Connected
    runtime == "failed" -> McpState.Failed
    runtime == "connecting" -> McpState.Connecting
    runtime == "disabled" -> McpState.Off
    else -> McpState.Idle
}

enum class McpAuth { None, Key, OAuth }

/** The add / edit form. [args] one per line; [env] `KEY=value` lines where [MASK] keeps the saved value. */
@Immutable
data class McpDraft(
    val name: String = "",
    val useUrl: Boolean = true,
    val url: String = "",
    val command: String = "",
    val args: String = "",
    val env: String = "",
    val auth: McpAuth = McpAuth.None,
    val key: String = "",
)

/** Shown instead of a saved secret; saving it unchanged keeps the saved value. */
const val MASK = "•••"

fun argLines(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

/** `KEY=value` lines → env map; a [MASK] value keeps [old]'s value (lines without `=` are ignored). */
fun envMap(text: String, old: JsonObject? = null): Map<String, String> = buildMap {
    for (line in text.lines()) {
        val i = line.indexOf('=')
        if (i <= 0) continue
        val k = line.substring(0, i).trim()
        val v = line.substring(i + 1).trim()
        if (k.isEmpty()) continue
        val kept = if (v == MASK) (old?.get(k) as? JsonPrimitive)?.content else null
        put(k, kept ?: v)
    }
}

/** Saved config entry → form (secrets masked). */
fun draftOf(name: String, raw: JsonObject): McpDraft {
    val url = raw.str("url")
    return McpDraft(
        name = name, useUrl = url != null, url = url.orEmpty(), command = raw.str("command").orEmpty(),
        args = raw.list("args").mapNotNull { (it as? JsonPrimitive)?.content }.joinToString("\n"),
        env = raw.child("env")?.keys?.joinToString("\n") { "$it=$MASK" }.orEmpty(),
        auth = when {
            raw.str("auth") == "oauth" -> McpAuth.OAuth
            raw.str("auth") == "header" || raw.child("headers")?.isNotEmpty() == true -> McpAuth.Key
            else -> McpAuth.None
        },
    )
}

/** Form → saved entry; every other key of [old] (enabled, tools, timeouts…) is kept. */
fun entryOf(d: McpDraft, old: JsonObject): JsonObject {
    val e = old.toMutableMap()
    if (d.useUrl) {
        e["url"] = JsonPrimitive(d.url.trim())
        listOf("command", "args", "env").forEach { e.remove(it) }
        when (d.auth) {
            // OAuth drops a stale bearer header; a key server keeps its own auth value unless it was "oauth".
            McpAuth.OAuth -> { e["auth"] = JsonPrimitive("oauth"); e.remove("headers") }
            McpAuth.Key -> { if (old.str("auth") == "oauth") e.remove("auth") }
            McpAuth.None -> { e.remove("auth"); e.remove("headers") }
        }
    } else {
        e["command"] = JsonPrimitive(d.command.trim())
        e["args"] = argLines(d.args).toJsonValue()
        val env = envMap(d.env, old.child("env"))
        if (env.isEmpty()) e.remove("env") else { e["env"] = env.toJsonValue() }
        listOf("url", "headers", "auth").forEach { e.remove(it) }
    }
    return JsonObject(e)
}

/** Saved entry with the tool filter of [s]; no filter left = no `tools` key. */
fun withToolFilter(old: JsonObject, s: McpServer): JsonObject {
    val e = old.toMutableMap()
    val f = buildMap<String, Any> {
        if (s.include != null) put("include", s.include)
        if (s.exclude.isNotEmpty()) put("exclude", s.exclude)
    }
    if (f.isEmpty()) e.remove("tools") else { e["tools"] = f.toJsonValue() }
    return JsonObject(e)
}

fun Throwable.why(): String = when (this) {
    is RestError -> detail
    is RpcError -> message ?: reason
    else -> message ?: toString()
}

/**
 * State and intents of More ▸ MCP servers: one global list, read from the default bot ([profile]). Hermes keeps `mcp_servers` per
 * profile, so every add / edit / delete / switch / key is written to the default bot, then to every other bot (a miss is toasted).
 * Test and sign-in stay on the default bot. Writes run on the store's scope (they survive leaving the page) and one at a time,
 * since every edit is a read-modify-write of the whole `mcp_servers` map.
 */
@Stable
class McpModel(private val store: AppStore, private val toasts: Toasts, private val fb: Feedback) {
    private val api get() = store.api
    private val writes = Mutex()
    lateinit var t: McpPageStrings
    var undoLabel = "Undo"

    var profile by mutableStateOf<String?>(null); private set
    var servers by mutableStateOf<List<McpServer>>(emptyList()); private set
    var runtime by mutableStateOf<Map<String, JsonObject>>(emptyMap()); private set
    var loaded by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var unsupported by mutableStateOf(false); private set
    /** Rows waiting out their undo window. */
    var hidden by mutableStateOf<Set<String>>(emptySet()); private set
    var catalog by mutableStateOf<List<JsonObject>?>(null); private set
    var catalogError by mutableStateOf<String?>(null); private set
    var installing by mutableStateOf<Set<String>>(emptySet()); private set
    /** Bumps on a success or failure the buddy should react to. */
    var cue by mutableStateOf<BuddyCue?>(null); private set
    var failedAt by mutableStateOf(0L); private set
    val probes = mutableStateMapOf<String, McpProbe>()
    /** Server name → OAuth flow id ("" while the flow is starting). */
    val oauth = mutableStateMapOf<String, String>()

    val busy: Boolean get() = probes.values.any { it.ok == null } || installing.isNotEmpty() || oauth.isNotEmpty()

    fun stateOf(s: McpServer): McpState = mcpStateOf(s, runtime[s.name]?.str("status"), probes[s.name])
    fun toolCount(s: McpServer): Int? = probes[s.name]?.takeIf { it.ok == true }?.tools?.size ?: runtime[s.name]?.long("tools")?.toInt()?.takeIf { it > 0 }

    /** Serial = timestamp, so a buddy composed later can skip a stale cue. */
    private fun react(shot: OneShot) { cue = BuddyCue(nowMillis(), shot) }
    private fun ouch() { failedAt = nowMillis(); fb.sound(Sound.Bonk); fb.haptic(Haptic.Reject) }
    private fun fail(msg: String) { ouch(); toasts.show(ToastData(msg)) }

    fun load(p: String) {
        if (p != profile) {
            profile = p; servers = emptyList(); runtime = emptyMap(); loaded = false; error = null; catalog = null
            probes.clear(); hidden = emptySet()
        }
        store.scope.launch { refresh(p) }
    }

    private suspend fun refresh(p: String) {
        try {
            val list = api.mcpServers(p).list("servers").mapNotNull { it.obj?.let(McpServer::from) }
            if (p != profile) return
            servers = list; loaded = true; error = null; unsupported = false
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (p != profile) return
            loaded = true
            if (e is RpcError && e.unknownMethod) { unsupported = true } else { error = e.why() }
        }
        refreshStatus()
    }

    suspend fun refreshStatus() {
        val p = profile ?: return
        val rows = runCatching { api.mcpStatus(p).list("servers").mapNotNull { it.obj } }.getOrNull() ?: return
        if (p == profile) runtime = rows.associateBy { it.str("name").orEmpty() }
    }

    /** After a write: apply to live sessions, say which servers didn't start, then re-read. */
    private suspend fun applied(p: String) {
        val r = runCatching { api.reloadMcp() }.getOrNull()
        val bad = r.list("unavailable").mapNotNull { (it as? JsonPrimitive)?.content }
        if (bad.isNotEmpty()) toasts.show(ToastData(t.couldntStart(bad.joinToString(", "))))
        refresh(p)
    }

    /** [block] on the default bot; once it worked, [others] (the same change) on every other bot. */
    private fun write(onError: (String) -> Unit = { fail(it) }, others: (suspend (String) -> Unit)? = null, block: suspend (String) -> Unit) {
        val p = profile ?: return
        store.scope.launch {
            try { writes.withLock { block(p) } } catch (e: CancellationException) { throw e } catch (e: Exception) {
                onError(e.why()); refresh(p); return@launch
            }
            if (others != null) {
                val missed = writes.withLock { store.forOtherBots(p, others) }
                if (missed.isNotEmpty()) fail(t.notOnBots(missed.joinToString(", ")))
            }
            try { applied(p) } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e.why()); refresh(p) }
        }
    }

    fun toggle(s: McpServer, on: Boolean) {
        servers = servers.map { if (it.name == s.name) it.copy(enabled = on) else it }
        if (!on) probes.remove(s.name)
        val set: suspend (String) -> Unit = { p -> api.mcpSetEnabled(p, s.name, on) }
        write(others = set, block = set)
    }

    fun setTool(s: McpServer, tool: String, on: Boolean) {
        val next = (servers.firstOrNull { it.name == s.name } ?: s).withTool(tool, on)
        servers = servers.map { if (it.name == s.name) next else it }
        val put: suspend (String) -> Unit = put@{ p ->
            val raw = api.mcpRawServers(p)
            val old = raw[s.name]?.obj ?: return@put
            api.mcpPutServers(p, JsonObject(raw + (s.name to withToolFilter(old, next))))
        }
        write(others = put, block = put)
    }

    fun test(s: McpServer) {
        val p = profile ?: return
        if (probes[s.name]?.ok == null && s.name in probes) return
        probes[s.name] = McpProbe()
        store.scope.launch {
            val probe = try {
                val r = api.mcpTest(p, s.name)
                McpProbe(
                    ok = r.bool("ok") == true,
                    tools = r.list("tools").mapNotNull { it.obj }.map { McpTool(it.str("name").orEmpty(), it.str("description").orEmpty()) },
                    error = r.str("error"), oauthNeeded = r.bool("oauth_needed") == true,
                )
            } catch (e: CancellationException) { throw e } catch (e: Exception) { McpProbe(ok = false, error = e.why()) }
            if (p != profile) return@launch
            probes[s.name] = probe
            if (probe.ok == true) { fb.haptic(Haptic.Confirm); react(OneShot.Done) } else ouch()
        }
    }

    fun saveKey(s: McpServer, value: String, done: () -> Unit) = write(others = { p -> api.mcpSetApiKey(p, s.name, value.trim()) }) { p ->
        api.mcpSetApiKey(p, s.name, value.trim())
        toasts.show(ToastData(t.saved)); fb.haptic(Haptic.Confirm); done(); probes.remove(s.name)
    }

    /** Remote-safe OAuth: open the provider page in the phone's browser, then poll until approved (6 min, like the Desktop). */
    fun signIn(s: McpServer, open: (String) -> Unit) {
        val p = profile ?: return
        if (s.name in oauth) return
        oauth[s.name] = ""
        store.scope.launch {
            var id: String? = null
            var approved = false
            try {
                var snap = api.mcpOAuthStart(p, s.name)
                val flow = snap.str("flow_id") ?: throw IllegalStateException(snap.str("error") ?: "no sign-in flow")
                id = flow
                oauth[s.name] = flow
                var opened = false
                val until = nowMillis() + 6 * 60_000
                while (oauth[s.name] == flow && nowMillis() < until) {
                    when (snap.str("status")) {
                        "approved" -> { approved = true; break }
                        "error" -> throw IllegalStateException(snap.str("error") ?: "sign-in failed")
                    }
                    snap.str("authorization_url")?.takeIf { !opened }?.let { open(it); opened = true }
                    delay(1_500)
                    snap = api.mcpOAuthFlow(flow)
                }
                if (approved) {
                    toasts.show(ToastData(t.signedIn(s.name))); fb.sound(Sound.Bloop); fb.haptic(Haptic.Confirm); react(OneShot.Done)
                    probes.remove(s.name)
                    applied(p)
                    test(s)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e.why()) }
            finally {
                val started = id
                if (!approved && started != null) runCatching { api.mcpOAuthCancel(started) }
                oauth.remove(s.name)
            }
        }
    }

    fun cancelSignIn(s: McpServer) { oauth.remove(s.name) }

    /** Optimistic delete with an undo window; the server call happens only once the toast is gone. */
    fun delete(s: McpServer) {
        val p = profile ?: return
        hidden = hidden + s.name
        fb.haptic(Haptic.Reject)
        val job = store.scope.launch {
            delay(4_500)
            try {
                writes.withLock { api.mcpRemove(p, s.name) }
                probes.remove(s.name)
                // A bot that never had it counts as done.
                val missed = writes.withLock { store.forOtherBots(p) { q -> if (s.name in api.mcpRawServers(q)) api.mcpRemove(q, s.name) } }
                if (missed.isNotEmpty()) fail(t.notOnBots(missed.joinToString(", ")))
                applied(p)
            }
            catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e.why()); refresh(p) }
            hidden = hidden - s.name
        }
        toasts.show(ToastData(t.removed(s.name), undoLabel, onAction = { job.cancel(); hidden = hidden - s.name }))
    }

    /** The saved entry as a form, secrets masked. */
    suspend fun draftFor(s: McpServer): McpDraft? {
        val p = profile ?: return null
        return try { draftOf(s.name, api.mcpRawServers(p)[s.name]?.obj ?: JsonObject(emptyMap())) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e.why()); null }
    }

    /** Add ([editing] null) or edit. [done] gets null on success or the reason it failed. */
    fun save(d: McpDraft, editing: McpServer?, done: (String?) -> Unit) {
        val err = when {
            editing == null && d.name.isBlank() -> t.needName
            (if (d.useUrl) d.url else d.command).isBlank() -> t.needTarget
            else -> null
        }
        if (err != null) { done(err); return }
        val name = editing?.name ?: d.name.trim()
        // The default bot's saved entry: the base for a bot that lacks this server (masked secrets need real values).
        var base: JsonObject? = null
        val apply: suspend (String) -> Unit = { p ->
            if (editing == null) {
                api.mcpAdd(
                    p, name, url = d.url.trim().takeIf { d.useUrl }, command = d.command.trim().takeIf { !d.useUrl },
                    args = argLines(d.args), env = envMap(d.env),
                    auth = if (!d.useUrl) null else when (d.auth) { McpAuth.OAuth -> "oauth"; McpAuth.Key -> "header".takeIf { d.key.isNotBlank() }; McpAuth.None -> null },
                    bearerToken = d.key.takeIf { d.useUrl && d.auth == McpAuth.Key },
                )
            } else {
                val raw = api.mcpRawServers(p)
                val old = raw[name]?.obj ?: base ?: JsonObject(emptyMap())
                if (base == null) base = old
                api.mcpPutServers(p, JsonObject(raw + (name to entryOf(d, old))))
                if (d.useUrl && d.auth == McpAuth.Key && d.key.isNotBlank()) api.mcpSetApiKey(p, name, d.key.trim())
            }
        }
        write({ ouch(); done(it) }, others = apply) { p ->
            apply(p)
            if (editing == null) { toasts.show(ToastData(t.added(name))); fb.sound(Sound.Bloop); react(OneShot.Learned) }
            else { toasts.show(ToastData(t.saved)); react(OneShot.Nod) }
            fb.haptic(Haptic.Confirm)
            probes.remove(name)
            done(null)
        }
    }

    fun loadCatalog() {
        val p = profile ?: return
        if (catalog != null) return
        catalogError = null
        store.scope.launch {
            try { val c = api.mcpCatalog(p); if (p == profile) { catalog = c.sortedBy { it.str("name") } } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { catalogError = e.why() }
        }
    }

    /** Install a catalog entry with its required keys. [done] gets null on success or the reason. */
    fun install(entry: JsonObject, env: Map<String, String>, done: (String?) -> Unit) {
        val name = entry.str("name") ?: return
        if (name in installing) return
        installing = installing + name
        write({ installing = installing - name; ouch(); done(it) }, others = { p -> api.mcpInstall(p, name, env) }) { p ->
            api.mcpInstall(p, name, env)
            installing = installing - name
            catalog = catalog?.map { if (it.str("name") == name) JsonObject(it + ("installed" to JsonPrimitive(true))) else it }
            toasts.show(ToastData(t.added(name))); fb.sound(Sound.Bloop); fb.haptic(Haptic.Confirm); react(OneShot.Learned)
            done(null)
        }
    }
}
