package app.buddyverse.ui.play

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyClock
import app.buddyverse.buddy.LocalBuddyClock
import app.buddyverse.buddy.LocalMotion
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.MotionLevel
import app.buddyverse.design.BackHandler
import app.buddyverse.design.Icon
import app.buddyverse.design.Icons
import app.buddyverse.design.Motion
import app.buddyverse.design.Radius
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.play
import app.buddyverse.ui.LocalNav
import app.buddyverse.ui.LocalStore
import kotlin.math.max
import kotlin.math.min

/** Wide window: the games as a side panel at the end edge, beside the list and the open chat (AppRoot's Wide row). */
@Composable
fun GamesDocked(modifier: Modifier) {
    val nav = LocalNav.current
    CompositionLocalProvider(LocalPlayPaused provides false) {
        GamesPanel(modifier, onFold = null, onClose = { nav.play = false })
    }
}

private val frozen = BuddyClock()

/**
 * Phone (and a narrow PC window): the games as a card floating over whatever screen is open, chat included. Drag its
 * top edge to resize it; drag it right down (or tap the chevron, or Back) to fold it to a pill, which pauses the game
 * (frame loops stop, bot timers wait, its buddies freeze). Tap the pill to unfold. The game keeps its state while folded.
 */
@Composable
fun GamesFloat() {
    val nav = LocalNav.current
    val c = Theme.colors
    val p = S.play
    val still = LocalMotion.current == MotionLevel.Off
    val folded = nav.playFolded
    BackHandler(nav.play && !folded) { nav.playFolded = true }
    // Opening (or unfolding) the card puts the keyboard away: the game needs the room, and the chat box keeps no focus.
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(nav.play, folded) { if (nav.play && !folded) { focus.clearFocus(); keyboard?.hide() } }
    // Only the visible part of the window counts: status bar, gesture/button bar, cutout and keyboard are taken off.
    BoxWithConstraints(Modifier.fillMaxSize().zIndex(6f).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val wide = maxWidth >= 600.dp
        // The tallest the card's body may be (its 8dp margins off): measured each layout, so a keyboard or a smaller
        // window shrinks the card instead of pushing its bottom (and the board) out of sight.
        val room = max(0f, maxHeight.value - 16f)
        val maxH = max(160f, room - 48f) // dragging leaves a strip of the screen above it
        var h by remember { mutableFloatStateOf(min(maxHeight.value * .62f, 600f)) }
        val fold by animateFloatAsState(if (folded) 1f else 0f, if (still) snap() else Motion.sheet(), label = "fold")
        AnimatedVisibility(
            nav.play, Modifier.align(if (wide) Alignment.BottomEnd else Alignment.BottomCenter),
            enter = slideInVertically(Motion.sheet()) { it } + fadeIn(Motion.fade()),
            exit = slideOutVertically(Motion.sheet()) { it } + fadeOut(Motion.fade()),
        ) {
            val drag = Modifier.pointerInput(maxH) {
                detectVerticalDragGestures(
                    onDragEnd = { if (h < 240f) { nav.playFolded = true; h = 300f } },
                ) { ch, dy -> ch.consume(); h = (h - dy / density).coerceIn(160f, maxH) }
            }
            CompositionLocalProvider(LocalPlayPaused provides folded, LocalBuddyClock provides if (folded) frozen else LocalBuddyClock.current) {
                Column(
                    Modifier.padding(8.dp).then(if (wide) Modifier.width(420.dp) else Modifier.widthIn(max = 520.dp).fillMaxWidth())
                        .height(min(h, min(maxH, room)).dp)
                        // Folded: shrinks toward the pill corner and moves out of the way of touches.
                        .offset { IntOffset(0, if (fold >= .999f) 100_000 else 0) }
                        .graphicsLayer {
                            val s = 1f - .5f * fold; scaleX = s; scaleY = s; alpha = 1f - fold
                            transformOrigin = TransformOrigin(1f, 1f)
                        }
                        .shadow(18.dp, RoundedCornerShape(Radius.sheet.dp)).clip(RoundedCornerShape(Radius.sheet.dp))
                        .border(1.dp, c.hairline, RoundedCornerShape(Radius.sheet.dp)).background(c.ground),
                ) {
                    // Grabber: drag to resize.
                    Box(Modifier.fillMaxWidth().height(18.dp).then(drag), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(40.dp, 5.dp).clip(CircleShape).background(c.secondary.copy(alpha = .35f)))
                    }
                    GamesPanel(Modifier.weight(1f).fillMaxWidth(), onFold = { nav.playFolded = true }, onClose = { nav.play = false; nav.playFolded = false }, header = drag)
                }
            }
        }
        AnimatedVisibility(
            nav.play && folded, Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = if (wide) 16.dp else 96.dp),
            enter = scaleIn(Motion.bouncy(), .4f) + fadeIn(Motion.fade()), exit = scaleOut(Motion.snappy(), .4f) + fadeOut(Motion.fade()),
        ) { Pill(p.title) { nav.playFolded = false } }
    }
}

/** The folded games: a small capsule with the brain peeking out. */
@Composable
private fun Pill(label: String, onOpen: () -> Unit) {
    val c = Theme.colors
    val bots by LocalStore.current.bots.collectAsState()
    val brain = bots.firstOrNull { it.isDefault } ?: bots.firstOrNull()
    Row(
        Modifier.height(52.dp).shadow(12.dp, CircleShape).clip(CircleShape).background(c.surface).squish(onOpen, label = label)
            .padding(start = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (brain != null) Buddy(brain.spec, Mood.Sleeping, 36.dp, interactive = false) else Icon(Icons.sportsEsports, c.text, 24.dp)
        Spacer(Modifier.width(8.dp))
        Txt(label, Theme.type.headline, c.text, maxLines = 1)
    }
}
