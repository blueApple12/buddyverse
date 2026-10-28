package app.buddyverse.ui.more.connectors

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.buddyverse.core.AppStore
import app.buddyverse.core.Disconnected
import app.buddyverse.core.RpcError
import app.buddyverse.core.api.ConnectionOp
import app.buddyverse.core.api.ConnectorAccount
import app.buddyverse.core.api.ConnectorApp
import app.buddyverse.core.api.ConnectorPolicy
import app.buddyverse.core.api.ConnectorState
import app.buddyverse.core.api.ConnectorTool
import app.buddyverse.core.api.accountsOf
import app.buddyverse.core.api.connectConnector
import app.buddyverse.core.api.connectionRespond
import app.buddyverse.core.api.connectionStatus
import app.buddyverse.core.api.connectionWake
import app.buddyverse.core.api.connectorAccounts
import app.buddyverse.core.api.connectorCatalog
import app.buddyverse.core.api.connectorPolicy
import app.buddyverse.core.api.connectorStates
import app.buddyverse.core.api.connectorTitle
import app.buddyverse.core.api.connectorTools
import app.buddyverse.core.api.removeConnectorAccount
import app.buddyverse.core.api.renameConnectorAccount
import app.buddyverse.core.api.setConnectorDisabledTools
import app.buddyverse.core.api.setConnectorEnabled
import app.buddyverse.core.decodeAs
import app.buddyverse.core.nowMillis
import app.buddyverse.core.str
import app.buddyverse.i18n.ConnectorsPageStrings
import app.buddyverse.i18n.Strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why a connectors call failed, worded by [text]. */
enum class Problem { Unavailable, SignIn, NotAllowed, NameTaken, Offline, ReadOnly, Other }

fun problemOf(e: Throwable): Problem = when (e) {
    is Disconnected, is TimeoutCancellationException -> Problem.Offline
    is RpcError -> when (e.reason) {
        "CONNECTORS_UNAVAILABLE" -> Problem.Unavailable
        "NEEDS_NOUS_AUTH" -> Problem.SignIn
        "ORG_REQUIRED", "FORBIDDEN_SCOPE", "NOT_OWNER" -> Problem.NotAllowed
        "ALIAS_TAKEN" -> Problem.NameTaken
        else -> Problem.Other
    }
    else -> Problem.Other
}

fun Problem.text(s: ConnectorsPageStrings, base: Strings): String = when (this) {
    Problem.Unavailable -> s.unavailable
    Problem.SignIn -> s.needsSignIn
    Problem.NotAllowed -> s.notAllowed
    Problem.NameTaken -> s.nameTaken
    Problem.Offline -> s.offline
    Problem.ReadOnly -> s.rulesReadOnly
    Problem.Other -> base.error
}

/** A card's state (desktop `types.ts`: available | connecting | connected | off | expired | broken). */
enum class Status { Available, Connecting, Connected, Off, Expired, Broken }

/** One-time things the page shows as a toast, a sound and a buddy one-shot; a new [n] fires it. */
sealed interface Flash {
    val n: Long
    data class Connected(val slug: String, override val n: Long) : Flash
    data class NotConnected(val slug: String, override val n: Long) : Flash
    data class Removed(override val n: Long) : Flash
    data class Failed(val problem: Problem, override val n: Long) : Flash
}

private object ReadOnlyRules : Exception("rules are read-only")

/** A Result that lets a timeout through as a failure but never swallows a real cancellation. */
private suspend inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: TimeoutCancellationException) {
    Result.failure(e)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/**
 * State and intents of More ▸ Connectors for one profile scope (hermes-api.md §2.6 Connectors).
 * Writes are optimistic: switches flip at touch-down through overrides that the next read reconciles.
 */
@Stable
class ConnectorsModel(private val store: AppStore, private val scope: CoroutineScope) {
    private val api get() = store.api

    /** null = the gateway's default profile. */
    var profile by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(true); private set
    var available by mutableStateOf(true); private set
    var loadProblem by mutableStateOf<Problem?>(null); private set
    var catalog by mutableStateOf<List<ConnectorApp>>(emptyList()); private set
    var states by mutableStateOf<Map<String, ConnectorState>>(emptyMap()); private set
    var accounts by mutableStateOf<List<ConnectorAccount>>(emptyList()); private set
    /** null = the rules could not be read (no account yet): no switches. */
    var policy by mutableStateOf<ConnectorPolicy?>(null); private set
    var flash by mutableStateOf<Flash?>(null); private set

    /** slug → its open sign-in operation. */
    val ops = mutableStateMapOf<String, ConnectionOp>()
    /** slug → the sign-in link (account `connection.update` frames strip it). */
    val signInUrls = mutableStateMapOf<String, String>()
    /** slugs and connection ids with a write in flight. */
    val busy = mutableStateMapOf<String, Boolean>()
    val tools = mutableStateMapOf<String, List<ConnectorTool>>()
    val toolsProblem = mutableStateMapOf<String, Problem>()

    private val enabledOverride = mutableStateMapOf<String, Boolean>()
    private val toolsOverride = mutableStateMapOf<String, List<String>>()
    private val startedIds = mutableMapOf<String, List<String>>()
    /** slug → the existing account a reconnect is repairing: stopping the wait never deletes it. */
    private val repairing = mutableMapOf<String, String>()
    private val followers = mutableMapOf<String, Job>()
    private val writeLock = Mutex()
    private var loadJob: Job? = null
    private var serial = 0L

    // ------------------------------------------------------------------ reads

    fun title(slug: String): String = catalog.firstOrNull { it.slug == slug }?.name ?: connectorTitle(slug)
    fun app(slug: String): ConnectorApp = catalog.firstOrNull { it.slug == slug } ?: ConnectorApp(slug, connectorTitle(slug), "", "")
    fun accountsOf(slug: String): List<ConnectorAccount> = accounts.accountsOf(slug)
    val rulesWritable: Boolean get() = policy?.revision != null

    fun enabled(slug: String): Boolean = enabledOverride[slug] ?: ((policy?.enabled(slug) ?: true) && states[slug]?.enabled != false)

    /** The member's disabled tools when the rules are readable, else the gateway's own list (`join.ts`). */
    fun disabledTools(slug: String): List<String> = toolsOverride[slug] ?: policy?.disabledTools(slug) ?: states[slug]?.gatewayDisabledTools.orEmpty()

    fun status(slug: String): Status {
        if (slug in ops) return Status.Connecting
        val acc = accountsOf(slug)
        val row = states[slug]
        if (acc.isEmpty() && row?.connected != true && row?.status != "pending") return Status.Available
        return when (acc.firstOrNull()?.status ?: row?.status) {
            "pending" -> Status.Connecting
            "expired", "revoked" -> Status.Expired
            "failed" -> Status.Broken
            else -> if (enabled(slug)) Status.Connected else Status.Off
        }
    }

    /** Everything connected, connecting or broken, by name. */
    fun connectedSlugs(): List<String> {
        val set = LinkedHashSet<String>()
        states.values.filter { it.connected || it.status == "pending" }.mapTo(set) { it.connector }
        accounts.filter { !it.disabled }.mapTo(set) { it.connector }
        set += ops.keys
        return set.sortedBy { title(it).lowercase() }
    }

    fun load(newProfile: String? = profile) {
        if (newProfile != profile) {
            followers.values.forEach { it.cancel() }; followers.clear()
            ops.clear(); signInUrls.clear(); tools.clear(); toolsProblem.clear(); enabledOverride.clear(); toolsOverride.clear()
            accounts = emptyList(); states = emptyMap(); policy = null
        }
        profile = newProfile
        loadJob?.cancel()
        loadJob = scope.launch {
            loading = true
            val p = newProfile
            coroutineScope {
                val cat = async { attempt { api.connectorCatalog(p) } }
                val rest = async { readState(p) }
                cat.await().onSuccess { catalog = it }
                val problem = cat.await().exceptionOrNull() ?: rest.await()
                loadProblem = problem?.let(::problemOf)
                if (loadProblem == Problem.Unavailable) available = false
            }
            loading = false
        }
    }

    /** list + accounts + rules; returns the list's failure, if any. */
    private suspend fun readState(p: String? = profile): Throwable? = coroutineScope {
        val list = async { attempt { api.connectorStates(p) } }
        val acc = async { attempt { api.connectorAccounts(p) } }
        val pol = async { attempt { api.connectorPolicy(p) } }
        list.await().onSuccess { (on, rows) -> available = on; states = rows.associateBy { it.connector } }
        acc.await().onSuccess { accounts = it }
        pol.await().onSuccess { policy = it }.onFailure { if (problemOf(it) == Problem.SignIn) policy = null }
        list.await().exceptionOrNull()
    }

    private fun refreshQuiet() { scope.launch { readState() } }

    fun loadTools(slug: String, refresh: Boolean = false) {
        if (!refresh && slug in tools) return
        toolsProblem.remove(slug)
        scope.launch {
            attempt { api.connectorTools(profile, slug, refresh) }
                .onSuccess { tools[slug] = it }
                .onFailure { toolsProblem[slug] = problemOf(it) }
        }
    }

    // ------------------------------------------------------------------ sign-in

    /**
     * Starts (or with [reconnect] repairs) a sign-in, opens its link in the system browser through [open],
     * then follows the operation by its `connection.update` frames and a 2.5 s status poll until it ends.
     */
    fun connect(slug: String, open: (String) -> Unit, reconnect: Boolean = false, alias: String? = null, connectionId: String? = null) {
        if (busy[slug] == true) return
        busy[slug] = true
        scope.launch {
            attempt { api.connectConnector(profile, listOf(slug), reconnect, alias, connectionId) }
                .onSuccess { op ->
                    op.connectUrl?.let { signInUrls[slug] = it; open(it) }
                    startedIds[slug] = op.targets.mapNotNull { it.connectionId }
                    if (connectionId != null) repairing[slug] = connectionId else repairing.remove(slug)
                    if (op.done) finish(slug, op)
                    else {
                        ops[slug] = op
                        followers.remove(slug)?.cancel()
                        followers[slug] = scope.launch { follow(slug, op) }
                        refreshQuiet()
                    }
                }
                .onFailure { fail(it) }
            busy.remove(slug)
        }
    }

    private suspend fun follow(slug: String, first: ConnectionOp) {
        val until = if (first.deadlineAt > 0) (first.deadlineAt * 1000).toLong() + 5_000 else nowMillis() + 15 * 60_000
        coroutineScope {
            val live = launch {
                store.gateway.events.collect { e ->
                    if (e.type == "connection.update" && e.payload.str("op_id") == first.opId)
                        runCatching { e.payload.decodeAs<ConnectionOp>() }.getOrNull()?.let { absorb(slug, it) }
                }
            }
            while (ops[slug]?.done == false && nowMillis() < until) {
                delay(2_500)
                attempt { api.connectionStatus(profile, first.opId) }.onSuccess { absorb(slug, it) }
            }
            live.cancel()
        }
        val last = ops.remove(slug) ?: return
        followers.remove(slug)
        finish(slug, last)
    }

    private fun absorb(slug: String, op: ConnectionOp) {
        val held = ops[slug] ?: return
        if (op.opId == held.opId && op.seq >= held.seq) ops[slug] = op
    }

    private fun finish(slug: String, op: ConnectionOp) {
        signInUrls.remove(slug)
        if (op.connected) emit { Flash.Connected(slug, it) }
        else if (op.settledBy != "continue") emit { Flash.NotConnected(slug, it) }
        refreshQuiet()
    }

    /** The browser leg came back (app resumed): read the accounts now, not at the next tick. */
    fun wake() {
        for ((slug, op) in ops.toMap()) scope.launch {
            attempt { api.connectionWake(profile, op.opId) }
            attempt { api.connectionStatus(profile, op.opId) }.onSuccess { absorb(slug, it) }
        }
    }

    /** Gives up a sign-in: settles the operation (`continue`) and removes the accounts it left half made (desktop `giveUp`). */
    fun stopWaiting(slug: String) {
        val op = ops.remove(slug)
        followers.remove(slug)?.cancel()
        signInUrls.remove(slug)
        val ids = buildSet<String> {
            op?.targets?.filter { it.state != "connected" }?.mapNotNullTo(this) { it.connectionId }
            startedIds.remove(slug)?.let { if (op != null && !op.connected) addAll(it) }
            if (op == null) accountsOf(slug).filter { it.status == "pending" }.mapTo(this) { it.connectionId }
        } - setOfNotNull(repairing.remove(slug))
        accounts = accounts.filterNot { it.connectionId in ids }
        scope.launch {
            if (op != null) attempt { api.connectionRespond(profile, op.opId, settledBy = "continue") }
            for (id in ids) attempt { api.removeConnectorAccount(profile, id) }
            readState()
        }
    }

    // ------------------------------------------------------------------ accounts

    /** Returns null when renamed, else why not (the sheet shows it inline). */
    suspend fun rename(account: ConnectorAccount, alias: String): Problem? {
        val before = accounts
        accounts = accounts.map { if (it.connectionId == account.connectionId) it.copy(alias = alias) else it }
        return attempt { api.renameConnectorAccount(profile, account.connectionId, alias) }.fold(
            onSuccess = { row -> if (row != null) accounts = accounts.map { if (it.connectionId == row.connectionId) row else it }; null },
            onFailure = { accounts = before; problemOf(it) },
        )
    }

    fun remove(account: ConnectorAccount) {
        val before = accounts
        accounts = accounts.filterNot { it.connectionId == account.connectionId }
        busy[account.connectionId] = true
        scope.launch {
            attempt { api.removeConnectorAccount(profile, account.connectionId) }
                .onSuccess { emit { Flash.Removed(it) } }
                .onFailure { accounts = before; fail(it) }
            busy.remove(account.connectionId)
            readState()
        }
    }

    // ------------------------------------------------------------------ rules (policy)

    fun setEnabled(slug: String, on: Boolean) {
        if (!rulesWritable) return fail(ReadOnlyRules)
        enabledOverride[slug] = on
        scope.launch {
            writePolicy { rev -> api.setConnectorEnabled(profile, slug, on, rev) }.onFailure { fail(it) }
            if (enabledOverride[slug] == on) enabledOverride.remove(slug)
        }
    }

    fun setTool(slug: String, tool: String, on: Boolean) {
        if (!rulesWritable) return fail(ReadOnlyRules)
        val current = disabledTools(slug)
        val next = if (on) current - tool else (current + tool).distinct()
        toolsOverride[slug] = next
        scope.launch {
            writePolicy { rev -> api.setConnectorDisabledTools(profile, slug, next, rev) }.onFailure { fail(it) }
            if (toolsOverride[slug] == next) toolsOverride.remove(slug)
        }
    }

    /** One write at a time; `POLICY_CONFLICT` → reread the rules and retry once; then reread rules and list. */
    private suspend fun writePolicy(change: suspend (String) -> String?): Result<Unit> = writeLock.withLock {
        val first = policy?.revision ?: return@withLock Result.failure(ReadOnlyRules)
        var r = attempt { change(first) }
        if ((r.exceptionOrNull() as? RpcError)?.reason == "POLICY_CONFLICT") {
            val fresh = attempt { api.connectorPolicy(profile) }.getOrNull()
            if (fresh != null) policy = fresh
            fresh?.revision?.let { rev -> r = attempt { change(rev) } }
        }
        readState()
        r.map { }
    }

    // ------------------------------------------------------------------ flashes

    private fun emit(make: (Long) -> Flash) { flash = make(++serial) }
    private fun fail(e: Throwable) {
        emit { Flash.Failed(if (e === ReadOnlyRules) Problem.ReadOnly else problemOf(e), it) }
    }
}
