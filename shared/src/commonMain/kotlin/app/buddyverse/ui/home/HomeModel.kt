package app.buddyverse.ui.home

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.Bot
import app.buddyverse.core.BotStatus
import app.buddyverse.core.ChatTarget
import app.buddyverse.core.Room
import app.buddyverse.core.api.LiveSubagent
import app.buddyverse.core.api.Project
import app.buddyverse.core.chat.ChatState
import app.buddyverse.core.chat.Prompt
import app.buddyverse.core.chat.PromptKind
import app.buddyverse.core.chat.StepStatus
import app.buddyverse.core.chat.WorkItem
import app.buddyverse.i18n.HomeStrings
import app.buddyverse.i18n.Strings
import app.buddyverse.ui.toolLabel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.abs
import kotlin.math.min

// Pure derivations for Bots home (design §3.1): no Compose state here, so they are unit-tested in commonTest.

/** What line 2 of a row says, by state (design §3.1 "Line 2"). */
internal enum class LineKind { Preview, NeedsYou, Working, Streaming, Error }

@Immutable
internal data class BotLive(
    val mood: Mood,
    val kind: LineKind,
    val text: String,
    val talkAt: Long = 0,
    val talkChars: Int = 0,
    val helpers: Int = 0,
    val unread: Boolean = false,
)

/** One needs-you card: a live prompt answered inline, or (prompt == null) "open the chat" while it isn't loaded yet. */
@Immutable
internal data class Ask(val bot: Bot, val target: ChatTarget, val prompt: Prompt?) {
    val key: String get() = "ask:" + (prompt?.id ?: bot.profile)
}

internal sealed interface Entry { val key: String }
internal data class BotEntry(val bot: Bot) : Entry { override val key: String get() = "bot:" + bot.profile }
internal data class RoomEntry(val room: Room) : Entry { override val key: String get() = "room:" + room.roomId }

/** Shared serial for every one-shot the home fires, so the newest cue on a buddy always wins. */
internal object Cues {
    private var serial = 0L
    fun next(): Long = ++serial
    fun cue(shot: OneShot) = BuddyCue(next(), shot)
}

/** Plays [shot] on every buddy on screen, [stepMs] apart by index: the third-tap wave (Hop, 40 ms). */
@Immutable
internal data class Ripple(val serial: Long, val shot: OneShot, val stepMs: Long, val atMs: Long)

/** Chats whose live state the home shows: busy bots' Bot Chats, plus a waiting bot's other live sessions (its prompt may be there). */
internal fun liveTargets(bots: List<Bot>): List<ChatTarget> = bots.filter { it.status == BotStatus.Working || it.status == BotStatus.NeedsYou }.flatMap { b ->
    listOf(ChatTarget(b.profile)) +
        if (b.status == BotStatus.NeedsYou) b.liveSessions.filter { it != b.botChatId }.map { ChatTarget(b.profile, it) } else emptyList()
}

/** A live subagent's look: the parent's shape in a lighter tint (design §3.2 "Subagents"). */
internal fun helperSpec(parent: BuddySpec, j: Int, goal: String?) =
    BuddySpec(parent.id + "~" + j, goal ?: parent.name, parent.shape, BuddyColors.mix(parent.color, Color.White, .35f))

/** A helper's own chat (read-only) when known, else the chat it streams into (its owner session); the Bot Chat when unknown. */
internal fun helperTarget(b: Bot, ownerSession: String?, child: String? = null): ChatTarget =
    if (child != null) ChatTarget(b.profile, child, botChat = false, watch = true)
    else if (ownerSession == null || ownerSession == b.botChatId) ChatTarget(b.profile) else ChatTarget(b.profile, ownerSession)

/** The group member whose live line the group row shows: the one with the strongest live mood (needs you > streaming > working). */
internal fun roomLead(members: List<Bot>, live: Map<String, BotLive>): Bot? =
    members.filter { live[it.profile]?.kind.let { k -> k != null && k != LineKind.Preview } }.maxByOrNull { live.getValue(it.profile).mood.priority }

/** Store order with not-yet-polled local edits applied (pin/hide/delete) and re-sorted. */
internal fun applyLocal(bots: List<Bot>, pinned: Map<String, Boolean>, hidden: Map<String, Boolean>, gone: Map<String, Boolean>): List<Bot> =
    bots.filter { gone[it.profile] != true }.map { b ->
        val p = pinned[b.profile] ?: b.pinned
        val h = hidden[b.profile] ?: b.hidden
        if (p == b.pinned && h == b.hidden) b else b.copy(pinned = p, hidden = h)
    }.sortedWith(Bot.order)

/** Brain first, then pinned, then needs-you, then most recent; groups sit in the same list by recency (design §3.1 "Order"). */
internal fun rosterOf(bots: List<Bot>, rooms: List<Room>): List<Entry> {
    fun rank(e: Entry) = when (e) {
        is BotEntry -> when { e.bot.isDefault -> 0; e.bot.pinned -> 1; e.bot.status == BotStatus.NeedsYou -> 2; else -> 3 }
        is RoomEntry -> 3
    }
    fun time(e: Entry) = when (e) { is BotEntry -> e.bot.lastActive; is RoomEntry -> e.room.updatedAt }
    return (bots.map(::BotEntry) + rooms.map(::RoomEntry)).sortedWith(compareBy<Entry> { rank(it) }.thenByDescending { time(it) })
}

/** Every prompt waiting in the open chats, deduped by id (Bot Chat first); a waiting bot with none loaded gets an "open" card. */
internal fun asksOf(bots: List<Bot>, states: List<Pair<ChatTarget, ChatState>>): List<Ask> {
    val seen = HashSet<String>()
    val out = ArrayList<Ask>()
    for (b in bots) {
        var any = false
        for ((t, st) in states.filter { it.first.profile == b.profile }.sortedByDescending { it.first.botChat }) {
            for (p in st.prompts) if (seen.add(p.id)) { out += Ask(b, t, p); any = true }
        }
        if (!any && b.status == BotStatus.NeedsYou) {
            val side = b.liveSessions.singleOrNull()?.takeIf { it != b.botChatId }
            out += Ask(b, if (side != null) ChatTarget(b.profile, side) else ChatTarget(b.profile), null)
        }
    }
    return out
}

/** The bot's mood and line 2 from the roster row plus whatever its open chats show live (design §3.1, §4.3 priority). */
internal fun liveOf(
    b: Bot, states: List<Pair<ChatTarget, ChatState>>, s: Strings, h: HomeStrings, readAt: Double?, helpers: Int, nowMs: Long,
): BotLive {
    val mine = states.filter { it.first.profile == b.profile }
    val st = (mine.firstOrNull { it.second.prompts.isNotEmpty() } ?: mine.firstOrNull { it.second.running } ?: mine.firstOrNull { it.first.botChat })?.second
    val prompt = mine.firstNotNullOfOrNull { it.second.prompts.firstOrNull() }
    val err = st?.let { x -> x.lastError?.takeIf { !x.running && nowMs - it.at < 30 * 60_000L } }
    val stream = st?.streamingText
    val sub = max(helpers, st?.activeSubagents ?: 0)
    return when {
        prompt != null || b.status == BotStatus.NeedsYou -> BotLive(Mood.NeedsYou, LineKind.NeedsYou, prompt?.let { askLine(it, s) } ?: s.needsYou, helpers = sub)
        err != null -> BotLive(Mood.Error, LineKind.Error, err.oops?.let(s.oops) ?: firstLine(err.text))
        stream != null -> BotLive(Mood.Talking, LineKind.Streaming, tail(stream), st?.lastDeltaAt ?: 0L, st?.lastDeltaChars ?: 0, sub)
        st?.running == true || b.status == BotStatus.Working -> {
            val step = (st?.items?.lastOrNull { it is WorkItem } as? WorkItem)?.steps?.lastOrNull { it.status == StepStatus.Running }
            val phrase = st?.phrase?.let(::firstLine)?.takeIf { it.isNotBlank() }
            val text = when {
                step != null -> toolLabel(step.tool, step.context, s)
                phrase != null && ' ' !in phrase && '_' in phrase -> toolLabel(phrase, null, s)
                phrase != null -> phrase
                st?.running == true -> s.thinking
                else -> s.working
            }
            val mood = if (step != null || st?.running != true) Mood.Working else Mood.Thinking
            BotLive(mood, LineKind.Working, if (sub > 0) "$text · ${h.helpers(sub)}" else text, helpers = sub)
        }
        // The turn is over but its helpers still work in the background (delegate_task always detaches).
        sub > 0 -> BotLive(Mood.Working, LineKind.Working, h.helpers(sub), helpers = sub)
        else -> BotLive(Mood.Idle, LineKind.Preview, b.preview?.let(::plainText)?.let(::firstLine).orEmpty(), unread = readAt != null && b.lastActive > readAt + 1)
    }
}

/**
 * Each live helper once, on the bot whose session spawned it ([owners]: session id → profile). One reported by a single
 * profile's call stays there; an unknown owner reported by several (a shared engine) goes to [fallback] (the brain).
 */
internal fun attributeHelpers(raw: Map<String, List<LiveSubagent>>, owners: Map<String, String>, fallback: String?): Map<String, List<LiveSubagent>> {
    val out = LinkedHashMap<String, MutableList<LiveSubagent>>()
    val all = raw.values.flatten().distinctBy { it.id }
    for (sa in all) {
        val reporters = raw.filterValues { l -> l.any { it.id == sa.id } }.keys
        val owner = sa.ownerSession?.let(owners::get)?.takeIf { it in raw.keys }
            ?: reporters.singleOrNull() ?: fallback?.takeIf { it in reporters } ?: reporters.first()
        out.getOrPut(owner) { mutableListOf() } += sa
    }
    return out
}

internal fun askLine(p: Prompt, s: Strings): String = when (p.kind) {
    PromptKind.Approval -> p.description?.takeIf { it.isNotBlank() }?.let(::firstLine) ?: p.command?.let { "${s.wantsToRun} ${firstLine(it)}" } ?: s.needsYou
    PromptKind.Clarify -> p.question?.takeIf { it.isNotBlank() }?.let(::firstLine) ?: s.asks
    PromptKind.Secret -> s.secretTitle
    PromptKind.Sudo -> s.sudoTitle
    PromptKind.Vault -> s.vaultTitle
}

/** A reply's markdown as plain words for a one-line preview (no table pipes, rules, quote marks or emphasis). */
internal fun plainText(t: String) = t.replace(Regex("""(?m)^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$"""), "")
    .replace(Regex(""":?-{3,}:?"""), " ").replace(Regex("""[|*`#>]+"""), " ").replace(Regex("""[ \t]+"""), " ").trim()

private fun firstLine(t: String) = t.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(120)

/** The streamed tail a row shows: last 60 characters on one line (design §4.4 "In rows"). */
internal fun tail(t: String) = t.replace('\n', ' ').trimEnd().takeLast(60)

// ---------------------------------------------------------------- stage graph (design §3.1 "The stage")

/** A folder as compared on the stage: case-insensitive, `/` and `\` alike, no trailing separator. */
internal fun folderKey(path: String): String = path.trim().replace('\\', '/').trimEnd('/').lowercase()

/**
 * The user's rule: a project is just an agent. Each bot's home is its shallowest non-archived project folder; its parent is
 * the other bot whose home is the nearest strict ancestor folder of that (ties by profile name), else the brain. Bots with
 * no project hang on the brain. Every bot but the brain gets an entry: profile → parent profile. No cycles: a parent's home
 * is always strictly shorter.
 */
internal fun agentParents(bots: List<Bot>, raw: Map<String, List<Project>>): Map<String, String> {
    val brain = bots.firstOrNull { it.isDefault } ?: bots.firstOrNull() ?: return emptyMap()
    val others = bots.filter { it.profile != brain.profile }
    val homes = others.associate { b ->
        b.profile to raw[b.profile].orEmpty().filter { !it.archived }.mapNotNull { p -> p.primaryPath?.let(::folderKey)?.takeIf { it.isNotEmpty() } }
            .minWithOrNull(compareBy<String> { k -> k.count { it == '/' } }.thenBy { it })
    }
    return others.associate { b ->
        val home = homes[b.profile]
        val parent = if (home == null) null else others.mapNotNull { o -> homes[o.profile]?.takeIf { o.profile != b.profile && home.startsWith("$it/") }?.let { o.profile to it } }
            .sortedWith(compareByDescending<Pair<String, String>> { it.second.length }.thenBy { it.first }).firstOrNull()?.first
        b.profile to (parent ?: brain.profile)
    }
}

internal enum class NodeKind { Brain, Agent }

/** A node centered at ([x], [y]) dp in the stage; [size] is the visual size; [via] is its parent node (-1 = the brain itself). */
@Immutable
internal data class StageNode(
    val kind: NodeKind, val key: String, val x: Float, val y: Float, val size: Float, val label: String?,
    val bot: Bot? = null, val via: Int = -1,
)

/** The 48 dp+ touch square of a node's mark, and the width of its column (mark plus the name under it). */
internal val StageNode.touch: Float get() = max(48f, size + 8f)
internal val StageNode.boxWidth: Float get() = if (label != null) max(touch, size + 40f) else touch
/** The name line under the mark: caption 18 for the brain, micro 16 for the rest. */
internal val StageNode.labelHeight: Float get() = if (label == null) 0f else if (kind == NodeKind.Brain) 18f else 16f

@Immutable
internal class StageGraph(val nodes: List<StageNode>, val height: Float = STAGE_HEIGHT)

internal const val STAGE_HEIGHT = 300f
/** A crowded stage grows in 40 dp steps up to this, so names never have to overlap. */
private const val MAX_STAGE_HEIGHT = 660f
/** Air between two nodes' boxes. */
private const val NODE_GAP = 6f
private const val EDGE = 38f
private const val REACH = .58f
/** An agent's sub-agents fan out beyond it, away from the brain: this far out, this many radians apart. */
private const val CREW_GAP = 60f
private const val CREW_FAN = .7
private const val START = -3 * PI / 4

/**
 * The user's rule: the brain in the middle, top agents on a ring around it, each agent's sub-agents fanned out beyond it
 * (recursively), every node linked to its parent ([agentParents]). No project nodes and no cap. Positions are absolute dp
 * (never mirrored in RTL).
 */
internal fun stageGraph(bots: List<Bot>, parents: Map<String, String>, width: Float, height: Float = STAGE_HEIGHT): StageGraph {
    var h = height
    while (true) {
        val placed = placeNodes(bots, parents, width, h)
        val spread = untangle(placed, width, h, lastTry = h >= MAX_STAGE_HEIGHT)
        if (spread != null) return StageGraph(spread, h)
        h += 40f
    }
}

/** The ideal spots (a ring around the brain, sub-agents beyond their parent), before [untangle] makes room for every name. */
private fun placeNodes(bots: List<Bot>, parents: Map<String, String>, width: Float, height: Float): List<StageNode> {
    val brain = bots.firstOrNull { it.isDefault } ?: bots.firstOrNull() ?: return emptyList()
    val cx = width / 2f
    val cy = height / 2f
    val rx = width / 2f - EDGE
    val ry = height / 2f - EDGE
    val nodes = mutableListOf(StageNode(NodeKind.Brain, "bot:" + brain.profile, cx, cy, 72f, brain.name, bot = brain))
    val others = bots.filter { it.profile != brain.profile }
    val known = others.mapTo(HashSet()) { it.profile }
    // A parent that isn't on the stage (hidden, gone) leaves its agent on the brain.
    fun parentOf(b: Bot) = parents[b.profile]?.takeIf { it in known && it != b.profile }
    val kids = others.groupBy { parentOf(it) }
    val seen = HashSet<String>()
    fun place(b: Bot, x: Float, y: Float, angle: Double, depth: Int, via: Int) {
        if (!seen.add(b.profile)) return
        val at = nodes.size
        nodes += StageNode(NodeKind.Agent, "bot:" + b.profile, x, y, if (depth == 0) 52f else if (depth == 1) 40f else 34f, b.name, bot = b, via = via)
        val crew = kids[b.profile].orEmpty()
        crew.forEachIndexed { j, c ->
            val t = angle + (j - (crew.size - 1) / 2.0) * CREW_FAN
            place(c, x + (cos(t) * CREW_GAP).toFloat(), y + (sin(t) * CREW_GAP).toFloat(), t, depth + 1, at)
        }
    }
    // Roots: agents on the brain, plus any caught in a parent loop (bad data), so every bot shows once.
    val roots = kids[null].orEmpty().toMutableList()
    val sector = 2 * PI / max(1, roots.size)
    roots.forEachIndexed { i, b ->
        val a = START + sector * i
        place(b, (cx + cos(a) * rx * REACH).toFloat(), (cy + sin(a) * ry * REACH).toFloat(), a, 0, 0)
    }
    others.filter { it.profile !in seen }.forEach { place(it, cx, cy + ry * REACH, PI / 2, 0, 0) }
    return nodes
}

/**
 * Pushes overlapping nodes apart (each node's box = its mark plus the name under it) until none touch, inside the stage.
 * The brain stays put. Null when they can't all fit at this height (on the [lastTry], the closest it got). Deterministic, so the stage never jumps.
 */
private fun untangle(nodes: List<StageNode>, width: Float, height: Float, lastTry: Boolean): List<StageNode>? {
    val n = nodes.size
    val x = FloatArray(n) { nodes[it].x }
    val y = FloatArray(n) { nodes[it].y }
    val half = FloatArray(n) { nodes[it].boxWidth / 2f }
    val up = FloatArray(n) { nodes[it].touch / 2f }
    val down = FloatArray(n) { nodes[it].touch / 2f + nodes[it].labelHeight }
    fun clamp(i: Int) {
        x[i] = x[i].coerceIn(half[i], max(half[i], width - half[i]))
        y[i] = y[i].coerceIn(up[i], max(up[i], height - down[i]))
    }
    // A deep chain of sub-agents can start past the edge.
    for (i in 1 until n) clamp(i)
    repeat(200) {
        var moved = false
        for (i in 0 until n) for (j in i + 1 until n) {
            val dx = x[j] - x[i]
            val ox = half[i] + half[j] + NODE_GAP - abs(dx)
            val oy = min(y[i] + down[i], y[j] + down[j]) - max(y[i] - up[i], y[j] - up[j]) + NODE_GAP
            if (ox <= 0f || oy <= 0f) continue
            moved = true
            // Half each way; whatever [i] can't take (the brain, node 0, never moves; or a wall) goes to [j].
            if (ox < oy) {
                val xi = x[i]
                val xj = x[j]
                val sx = if (dx > 0f || (dx == 0f && j % 2 == 0)) 1f else -1f
                if (i != 0) { x[i] -= sx * ox / 2f; clamp(i) }
                x[j] += sx * (ox - abs(x[i] - xi)); clamp(j)
                if (half[i] + half[j] + NODE_GAP - abs(x[j] - x[i]) <= .01f) continue
                // A wall took part of it (a row wider than the stage): go up or down instead.
                x[i] = xi; x[j] = xj
            }
            val dy = (y[j] + (down[j] - up[j]) / 2f) - (y[i] + (down[i] - up[i]) / 2f)
            val sy = if (dy > 0f || (dy == 0f && j % 2 == 0)) 1f else -1f
            val was = y[i]
            if (i != 0) { y[i] -= sy * oy / 2f; clamp(i) }
            y[j] += sy * (oy - abs(y[i] - was)); clamp(j)
        }
        if (!moved) return nodes.mapIndexed { i, nd -> nd.copy(x = x[i], y = y[i]) }
    }
    return if (lastTry) nodes.mapIndexed { i, nd -> nd.copy(x = x[i], y = y[i]) } else null
}

/** Each node drifts ±3 dp on its own seeded 5–8 s periods (design §3.1 "Alive"). Writes dx, dy into [out]; allocation-free. */
internal fun drift(key: String, seconds: Float, out: FloatArray) {
    val h = key.hashCode()
    val px = 5f + ((h ushr 3) and 0xFF) / 255f * 3f
    val py = 5f + ((h ushr 11) and 0xFF) / 255f * 3f
    val ph = ((h ushr 19) and 0xFF) / 255f * 2f * PI.toFloat()
    out[0] = 3f * sin(2f * PI.toFloat() * seconds / px + ph)
    out[1] = 3f * sin(2f * PI.toFloat() * seconds / py + ph * 1.7f)
}

/** Clock nanos → seconds, wrapped so a Float keeps millisecond precision. */
internal fun seconds(nanos: Long): Float = (nanos / 1_000_000L % 100_000_000L) / 1000f
