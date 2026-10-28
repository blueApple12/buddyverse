package app.buddyverse.ui.home

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.LocalBuddyClock
import app.buddyverse.buddy.LocalMotion
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.MotionLevel
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.Bot
import app.buddyverse.core.api.LiveSubagent
import app.buddyverse.core.api.SLOT
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.Radius
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.home
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The living stage (design §3.1): brain in the middle, top agents on a ring around it, sub-agents fanned out beyond their
 * agent (projects are agents, [agentParents]), soft curved links with a dot running from the brain down to an agent while it
 * works, pulsing status rings, every node drifting on its own rhythm and
 * a working bot's live subagents as mini buddies beside it. The clock is read only in draw/placement, never in composition.
 */
@Composable
internal fun Stage(
    bots: List<Bot>,
    parents: Map<String, String>,
    live: Map<String, BotLive>,
    subagents: Map<String, List<LiveSubagent>>,
    order: Map<String, Int>,
    onOpenBot: (Bot) -> Unit,
    onMenu: (Bot) -> Unit,
    onOpenHelper: (Bot, LiveSubagent?) -> Unit,
    onPlay: () -> Unit,
) {
    val c = Theme.colors
    val clock = LocalBuddyClock.current
    val still = LocalMotion.current == MotionLevel.Off
    val cues = LocalHomeCues.current
    val awake = !cues.down && cues.wakeAt >= 0L
    val busy = awake && live.values.any { it.mood.busy || it.mood == Mood.NeedsYou }
    val tick: LongState = if (busy) clock.fast else clock.slow
    val shape = RoundedCornerShape(Radius.stage.dp)
    BoxWithConstraints(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        val width = maxWidth.value
        val graph = remember(bots, parents, width) { stageGraph(bots, parents, width) }
        Box(
            Modifier.fillMaxWidth().height(graph.height.dp)
                .shadow(if (c.dark) 0.dp else 10.dp, shape, ambientColor = c.shadow, spotColor = c.shadow).clip(shape).background(c.surface),
            contentAlignment = AbsoluteAlignment.TopLeft,
        ) {
            fun ring(m: Mood?) = if (!awake || m == null) 0 else if (m == Mood.NeedsYou) 2 else if (m.busy) 1 else 0
            val status = IntArray(graph.nodes.size) { i -> graph.nodes[i].bot?.let { ring(live[it.profile]?.mood) } ?: 0 }
            val tint = graph.nodes.map { it.bot?.spec?.color ?: c.working }
            // A dragged bot's offset per node (dp): its links stretch after it. Plain array; the links redraw on the tick.
            val pulls = remember(graph) { FloatArray(graph.nodes.size * 2) }
            var lifted by remember { mutableStateOf(-1) }
            StageLinks(graph, status, tint, tick, still, c.secondary.copy(alpha = .3f), c.working, c.needsYou, pulls)
            graph.nodes.forEachIndexed { i, n ->
                key(n.key) {
                    val bot = n.bot
                    val pull = remember(pulls, i) { Pull { x, y, up -> pulls[i * 2] = x; pulls[i * 2 + 1] = y; lifted = if (up) i else if (lifted == i) -1 else lifted } }
                    NodePlace(n, tick, still, Modifier.zIndex(if (lifted == i) 1f else 0f)) { touch ->
                        if (bot != null) TapBuddy(bot.spec, live[bot.profile], n.size.dp, touch.dp, order[bot.profile] ?: 0, bot.name, { onOpenBot(bot) }, { onMenu(bot) }, pull = pull)
                    }
                }
            }
            // Live subagents (delegate_task children): minis of the parent's shape in a lighter tint, beside it (design §3.2 "Subagents").
            // Tapping one opens the chat it streams into, so you can watch it work live.
            if (awake) graph.nodes.forEach { n ->
                val bot = n.bot ?: return@forEach
                val list = subagents[bot.profile].orEmpty()
                val count = min(3, max(list.size, live[bot.profile]?.helpers ?: 0))
                for (j in 0 until count) key(n.key + "~" + j) {
                    val size = n.size * .55f
                    val a = -PI / 4 + j * .8
                    val dist = n.size / 2f + size / 2f + 2f
                    // No live record (a helper run by another Hermes process): a slot the server's search resolves.
                    val sa = list.getOrNull(j) ?: LiveSubagent(SLOT + j, null, null)
                    Mini(bot.spec, j, sa?.goal, size, n.x + (cos(a) * dist).toFloat(), n.y + (sin(a) * dist).toFloat(), n.key, tick, still) { onOpenHelper(bot, sa) }
                }
            }
            // The way into the mini-games: a 48 dp icon in the stage's corner, above the nodes, so a bot (or a helper mini)
            // drifting into the corner on a narrow phone can't swallow the tap.
            IconButton(Icons.sportsEsports, S.home.play, onPlay, Modifier.align(AbsoluteAlignment.TopRight).zIndex(2f), tint = c.secondary, size = 48.dp, iconSize = 24.dp)
        }
    }
}

/** Links, status rings and link pulses for the whole stage in one draw pass (design §3.1, Codync `pulse()`: 1600 ms, 10 dp spread). */
@Composable
private fun StageLinks(graph: StageGraph, status: IntArray, tint: List<Color>, tick: LongState, still: Boolean, line: Color, working: Color, needs: Color, pulls: FloatArray) {
    Spacer(Modifier.fillMaxWidth().height(graph.height.dp).drawWithCache {
        val n = graph.nodes.size
        val px = FloatArray(n)
        val py = FloatArray(n)
        val d = FloatArray(2)
        val q = FloatArray(2)
        val path = Path()
        val dp = density
        val stroke = Stroke(1.5f * dp)
        onDrawBehind {
            val t = seconds(tick.longValue)
            for (i in 0 until n) {
                val node = graph.nodes[i]
                if (still) { d[0] = 0f; d[1] = 0f } else drift(node.key, t, d)
                px[i] = (node.x + d[0] + pulls[i * 2]) * dp; py[i] = (node.y + d[1] + pulls[i * 2 + 1]) * dp
            }
            for (i in 0 until n) {
                val v = graph.nodes[i].via
                if (v < 0) continue
                control(px[v], py[v], px[i], py[i], q)
                path.rewind(); path.moveTo(px[v], py[v]); path.quadraticTo(q[0], q[1], px[i], py[i])
                drawPath(path, line, style = stroke)
            }
            val e = LinearOutSlowInEasing.transform((t % 1.6f) / 1.6f)
            for (i in 0 until n) {
                val s = status[i]
                if (s == 0) continue
                val color = if (s == 2) needs else working
                val r = graph.nodes[i].size / 2f * dp
                if (still) drawCircle(color, r + 2f * dp, Offset(px[i], py[i]), style = Stroke(2f * dp))
                else {
                    val spread = 10f * dp * e
                    drawCircle(color.copy(alpha = .55f * (1f - e)), r + spread / 2f, Offset(px[i], py[i]), style = Stroke(max(spread, 1f)))
                }
            }
            // The dot runs brain → … → agent, one link after another, the whole path every 1.2 s.
            if (!still) for (i in 0 until n) {
                if (status[i] != 1 || graph.nodes[i].via < 0) continue
                var segs = 0
                var up = i
                while (graph.nodes[up].via >= 0 && segs < n) { up = graph.nodes[up].via; segs++ }
                val p = (t % 1.2f) / 1.2f * segs
                val seg = min(segs - 1, p.toInt())
                val k = p - seg
                // Link [seg] (0 = from the brain) ends at the node (segs - seg - 1) links above [i].
                var b = i
                repeat(segs - seg - 1) { b = graph.nodes[b].via }
                val a = graph.nodes[b].via
                control(px[a], py[a], px[b], py[b], q)
                val u = 1f - k
                val x = u * u * px[a] + 2f * u * k * q[0] + k * k * px[b]
                val y = u * u * py[a] + 2f * u * k * q[1] + k * k * py[b]
                drawCircle(tint[i], 2f * dp, Offset(x, y))
            }
        }
    })
}

/** The control point of a link's soft curve: the midpoint pushed sideways by 12% of its length. */
private fun control(x0: Float, y0: Float, x1: Float, y1: Float, out: FloatArray) {
    out[0] = (x0 + x1) / 2f - (y1 - y0) * .12f
    out[1] = (y0 + y1) / 2f + (x1 - x0) * .12f
}

/**
 * Places a node so its mark's center lands on the node (plus drift), in absolute coordinates: the links are drawn left to
 * right, so Hebrew must not mirror nodes away from them (design §9.1 exception). Drift is read at placement only.
 */
@Composable
private fun NodePlace(n: StageNode, tick: LongState, still: Boolean, modifier: Modifier = Modifier, content: @Composable (touch: Float) -> Unit) {
    val touch = n.touch
    val boxW = n.boxWidth
    val d = remember { FloatArray(2) }
    Column(
        modifier.width(boxW.dp).absoluteOffset {
            if (still) { d[0] = 0f; d[1] = 0f } else drift(n.key, seconds(tick.longValue), d)
            IntOffset(((n.x + d[0] - boxW / 2f) * density).roundToInt(), ((n.y + d[1] - touch / 2f) * density).roundToInt())
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content(touch)
        if (n.label != null) {
            Txt(n.label, if (n.kind == NodeKind.Brain) Theme.type.caption else Theme.type.micro, Theme.colors.text, maxLines = 1)
        }
    }
}

/** A live subagent: pops out with Arrive and works beside its parent, drifting with it; a 48 dp touch opens where it runs. */
@Composable
private fun Mini(parent: BuddySpec, j: Int, goal: String?, size: Float, x: Float, y: Float, driftKey: String, tick: LongState, still: Boolean, onOpen: () -> Unit) {
    val spec = remember(parent, j, goal) { helperSpec(parent, j, goal) }
    val pop = remember { Cues.cue(OneShot.Arrive) }
    var tap by remember { mutableStateOf<BuddyCue?>(null) }
    val d = remember { FloatArray(2) }
    val touch = max(48f, size + 8f)
    Box(
        Modifier.size(touch.dp).absoluteOffset {
            if (still) { d[0] = 0f; d[1] = 0f } else drift(driftKey, seconds(tick.longValue), d)
            IntOffset(((x + d[0] - touch / 2f) * density).roundToInt(), ((y + d[1] - touch / 2f) * density).roundToInt())
        }.squish({ tap = Cues.cue(OneShot.Tap); onOpen() }, label = goal ?: S.home.helper),
        contentAlignment = Alignment.Center,
    ) {
        val t = tap
        Buddy(spec, Mood.Working, size.dp, cue = if (t != null && t.serial > pop.serial) t else pop, interactive = false)
    }
}

/** The collapsed stage: one row of every character at 40 dp, all still alive (design §3.1 "Collapse"). */
@Composable
internal fun TeamStrip(bots: List<Bot>, live: Map<String, BotLive>, onOpen: (Bot) -> Unit, onMenu: (Bot) -> Unit, modifier: Modifier = Modifier) {
    val c = Theme.colors
    LazyRow(
        modifier.fillMaxWidth().height(64.dp).shadow(if (c.dark) 0.dp else 6.dp, RoundedCornerShape(0.dp), ambientColor = c.shadow, spotColor = c.shadow)
            .background(c.ground),
        contentPadding = PaddingValues(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(bots, key = { _, b -> b.profile }) { i, b ->
            TapBuddy(b.spec, live[b.profile], 40.dp, 56.dp, i, b.name, { onOpen(b) }, { onMenu(b) })
        }
    }
}
