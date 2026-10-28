package app.buddyverse.ui.more.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddyGroup
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.nowMillis
import app.buddyverse.design.Chip
import app.buddyverse.design.Dot
import app.buddyverse.design.Feedback
import app.buddyverse.design.Haptic
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.gallery
import app.buddyverse.ui.ltr
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Grid buddy sizes: all at least 48dp, so every one is a full touch target (design §10). M and L are the fast-clock tiers. */
enum class GallerySize(val dp: Dp) { S(48.dp), M(64.dp), L(96.dp) }

/** A haptic tick at touch-down, before the buddy's own squash (design rule 3); observes without consuming. */
internal fun Modifier.touchHaptic(fb: Feedback): Modifier = pointerInput(fb) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        fb.haptic(Haptic.Press)
    }
}

/** "0.4 s", Western digits, LTR-isolated (design §9.6). */
internal fun seconds(x: Float): String = ltr("${(x * 10).roundToInt() / 10.0} s")
internal fun millis(x: Float): String = ltr("${(x * 10).roundToInt() / 10.0} ms")

/** One live grid buddy. [wave] plays on every cell, [index] × 40 ms apart, so a reaction ripples across the grid. */
@Composable
internal fun CellBuddy(cell: Cell, size: Dp, wave: BuddyCue?, index: Int, onPick: () -> Unit) {
    val fb = LocalFeedback.current
    var cue by remember { mutableStateOf<BuddyCue?>(null) }
    LaunchedEffect(wave) { if (wave != null) { delay(40L * index); cue = wave } }
    var talkAt by remember { mutableLongStateOf(0L) }
    // Talking buddies get a steady stream of chunks, as if a reply were coming in (design §4.4).
    if (cell.mood == Mood.Talking) LaunchedEffect(Unit) { while (true) { talkAt = nowMillis(); delay(110) } }
    Box(Modifier.size(size + 6.dp).touchHaptic(fb), contentAlignment = Alignment.Center) {
        Buddy(cell.spec, cell.mood, size, cue = cue, talkAt = talkAt, talkChars = 9, onTap = onPick)
    }
}

/** A mood's row: its name, then all 8 shapes in it (scrolls sideways on narrow screens). */
@Composable
internal fun MoodRow(mood: Mood, cells: List<Cell>, firstIndex: Int, size: Dp, wave: BuddyCue?, onPick: (Cell) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Txt(S.gallery.mood(mood).uppercase(), Theme.type.caps, Theme.colors.secondary, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 2.dp), maxLines = 1)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            cells.forEachIndexed { i, c -> CellBuddy(c, size, wave, firstIndex + i) { onPick(c) } }
        }
    }
}

/** The alive check readout: a green or red dot, the verdict, then running count · longest still · frame p95. */
@Composable
internal fun AliveReadout(check: AliveCheck, modifier: Modifier = Modifier) {
    val g = S.gallery
    val c = Theme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Dot(if (check.pass) c.success else c.error, 12.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Txt("${g.aliveCheck}: ${if (check.pass) g.allMoving else g.someoneFroze}", Theme.type.callout, c.text, maxLines = 2)
            Txt("${g.onScreen(check.running)} · ${g.longestStill(seconds(check.longest))} · ${g.frames(millis(check.p95))}", Theme.type.caption, c.secondary, maxLines = 2)
        }
    }
}

/** Every one-shot as a chip, plus Talk and the Everyone switch (design §4.5 one-shot table). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReactionChips(everyone: Boolean, onEveryone: (Boolean) -> Unit, onTalk: () -> Unit, onShot: (OneShot) -> Unit) {
    val g = S.gallery
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(g.everyone, everyone, { onEveryone(!everyone) })
        Chip(g.talk, false, onTalk)
        for (o in OneShot.entries) Chip(g.shot(o), false, { onShot(o) })
    }
}

/** The 12 palette colors, each a live buddy in the stage's shape; tap one to paint the stage buddy. */
@Composable
internal fun ColorRow(shape: BuddyShape, selected: Int, onPick: (Int) -> Unit) {
    val g = S.gallery
    val c = Theme.colors
    val fb = LocalFeedback.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BuddyColors.palette.forEachIndexed { i, color ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(56.dp).touchHaptic(fb), contentAlignment = Alignment.Center) {
                    Buddy(BuddySpec("gallery:color:$i", g.colorNames[i], shape, color), Mood.Idle, 48.dp, onTap = { onPick(i) })
                }
                Txt(g.colorNames[i], Theme.type.micro, if (i == selected) c.text else c.secondary, maxLines = 1)
                Box(Modifier.padding(top = 3.dp).size(18.dp, 4.dp).clip(CircleShape).background(if (i == selected) c.accent else c.ground))
            }
        }
    }
}

/** Group stacks (design §4.1): 2 and 3 members in two sizes; small members run on the slow clock tier. */
@Composable
internal fun GroupRow(cells: List<Cell>) {
    val moods = listOf(Mood.Idle, Mood.Working, Mood.Talking)
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        BuddyGroup(cells.take(2).map { it.spec }, moods, 52.dp)
        BuddyGroup(cells.drop(2).take(3).map { it.spec }, moods, 52.dp)
        BuddyGroup(cells.drop(5).take(2).map { it.spec }, moods.reversed(), 72.dp)
        BuddyGroup(cells.drop(9).take(3).map { it.spec }, moods.reversed(), 72.dp)
    }
    Spacer(Modifier.height(8.dp))
}
