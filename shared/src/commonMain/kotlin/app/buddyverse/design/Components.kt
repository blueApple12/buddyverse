package app.buddyverse.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalFocusManager
import app.buddyverse.i18n.S
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Replaces every ripple (design §5.1): scale down within one frame at touch-down with a haptic tick,
 * spring back on release, run the action on release.
 */
fun Modifier.squish(
    onClick: () -> Unit,
    scale: Float = .94f,
    haptic: Haptic? = Haptic.Press,
    enabled: Boolean = true,
    onLongPress: (() -> Unit)? = null,
    label: String? = null,
): Modifier = this.then(Modifier.squishInner(onClick, scale, haptic, enabled, onLongPress, label))

private fun Modifier.squishInner(onClick: () -> Unit, scaleTo: Float, haptic: Haptic?, enabled: Boolean, onLongPress: (() -> Unit)?, label: String?): Modifier =
    composed {
        val fb = LocalFeedback.current
        val scope = rememberCoroutineScope()
        val s = remember { Animatable(1f) }
        val click = rememberUpdatedState(onClick)
        val long = rememberUpdatedState(onLongPress)
        this
            .graphicsLayer { scaleX = s.value; scaleY = s.value }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (label != null) contentDescription = label
                onClick { if (enabled) click.value(); enabled }
            }
            .pointerInput(enabled, onLongPress != null) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        // Hold the squish and tick ~50 ms: a finger that starts a scroll gets neither (like clickable's press delay).
                        var fired = false
                        val down: Job = scope.launch { delay(50); fired = true; haptic?.let(fb::haptic); s.animateTo(scaleTo, tween(50)) }
                        val tapped = tryAwaitRelease()
                        down.cancel()
                        if (tapped && !fired) {
                            haptic?.let(fb::haptic) // a quick tap still gets its tick and a short squish
                            scope.launch { s.animateTo(scaleTo, tween(40)); s.animateTo(1f, Motion.snappy()) }
                        } else scope.launch { s.animateTo(1f, Motion.snappy()) }
                    },
                    onTap = { click.value() },
                    onLongPress = long.value?.let { l -> { fb.haptic(Haptic.LongPress); l() } },
                )
            }
    }

@Composable
fun Txt(
    text: String,
    style: TextStyle = Theme.type.body,
    color: Color = Theme.colors.text,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) = BasicText(text, modifier, style.copy(color = color), maxLines = maxLines, overflow = TextOverflow.Ellipsis)

@Composable
fun Icon(icon: ImageVector, tint: Color = Theme.colors.text, size: Dp = 24.dp, modifier: Modifier = Modifier, mirror: Boolean = false) {
    val flip = mirror && LocalLayoutDirection.current == LayoutDirection.Rtl
    Image(rememberVectorPainter(icon), null, modifier.size(size).graphicsLayer { if (flip) scaleX = -1f }, colorFilter = ColorFilter.tint(tint))
}

/** Icon-only button: 48dp touch, 24–28dp glyph (design §10). */
@Composable
fun IconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Theme.colors.text,
    fill: Color? = null,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    mirror: Boolean = false,
    enabled: Boolean = true,
    onLongPress: (() -> Unit)? = null,
) {
    Box(
        modifier.size(size).clip(CircleShape).then(if (fill != null) Modifier.background(fill) else Modifier)
            .squish(onClick, enabled = enabled, onLongPress = onLongPress, label = label),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, if (enabled) tint else tint.copy(alpha = .35f), iconSize, mirror = mirror) }
}

enum class ButtonKind { Primary, Outline, Quiet, Danger }

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    height: Dp = 56.dp,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    haptic: Haptic = Haptic.Press,
) {
    val c = Theme.colors
    val (bg, fg) = when (kind) {
        ButtonKind.Primary -> c.accentFill to c.onAccent
        ButtonKind.Outline -> Color.Transparent to c.text
        ButtonKind.Quiet -> c.surface2 to c.text
        ButtonKind.Danger -> c.error to Color.White
    }
    Row(
        modifier.heightIn(min = height).clip(CircleShape).background(if (enabled) bg else bg.copy(alpha = .4f))
            .then(if (kind == ButtonKind.Outline) Modifier.border(1.5.dp, c.hairline.copy(alpha = 1f).let { if (c.dark) c.tertiary else Color(0xFFCFC9C1) }, CircleShape) else Modifier)
            .squish(onClick, enabled = enabled, haptic = haptic, label = text)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, fg, 22.dp); Spacer(Modifier.width(8.dp)) }
        Txt(text, Theme.type.button, fg, maxLines = 1)
    }
}

/** Filter / option chip: 40dp visual inside a 48dp touch target (design §10). */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = Theme.colors
    Box(modifier.heightIn(min = 48.dp).squish(onClick, haptic = Haptic.Tick, label = text), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(40.dp).clip(CircleShape).background(if (selected) c.accentFill else c.surface2).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) { Icon(icon, if (selected) c.onAccent else c.text, 18.dp); Spacer(Modifier.width(6.dp)) }
            Txt(text, Theme.type.caption.copy(fontSize = Theme.type.sub.fontSize), if (selected) c.onAccent else c.text, maxLines = 1)
        }
    }
}

/** Squishy switch: 52×32 visual, 56×48 touch; the knob stretches 1.3× while travelling (design §3.6). */
@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Theme.colors
    val pos by animateFloatAsState(if (checked) 1f else 0f, Motion.snappy())
    val stretch = 1f + .3f * (1f - kotlin.math.abs(pos * 2f - 1f))
    Box(modifier.size(56.dp, 48.dp).squish({ onChange(!checked) }, haptic = Haptic.Tick, enabled = enabled, label = label), contentAlignment = Alignment.Center) {
        Box(Modifier.size(52.dp, 32.dp).clip(CircleShape).background(androidx.compose.ui.graphics.lerp(c.surface2.let { if (c.dark) Color(0xFF3A3842) else Color(0xFFDAD5CE) }, c.accentFill, pos))) {
            val knob = 24.dp
            Box(
                Modifier.padding(4.dp).offset { IntOffset(((52 - 8 - 24) * pos * density).roundToInt(), 0) }
                    .size(knob * stretch, knob).clip(CircleShape).background(Color.White).let { if (!enabled) it.graphicsLayer { alpha = .6f } else it },
            )
        }
    }
}

@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    password: Boolean = false,
    mono: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    minHeight: Dp = 56.dp,
    shape: Shape = RoundedCornerShape(18.dp),
) {
    val c = Theme.colors
    val style = (if (mono) Theme.type.code else Theme.type.callout).copy(color = c.text)
    BasicTextField(
        value, onValueChange,
        modifier.fillMaxWidth().heightIn(min = minHeight).clip(shape).background(c.surface2).padding(horizontal = 18.dp, vertical = 14.dp),
        textStyle = style,
        singleLine = singleLine,
        cursorBrush = SolidColor(c.accent),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, autoCorrectEnabled = !password && !mono),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Txt(placeholder, style, c.tertiary, maxLines = 1)
                inner()
            }
        },
    )
}

@Composable
fun Card(modifier: Modifier = Modifier, radius: Dp = Radius.card.dp, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = Theme.colors
    Column(
        modifier.shadow(if (c.dark) 0.dp else 10.dp, RoundedCornerShape(radius), ambientColor = c.shadow, spotColor = c.shadow)
            .clip(RoundedCornerShape(radius)).background(c.surface).padding(padding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Theme.colors.secondary) =
    Txt(text.uppercase(), Theme.type.caps, color, modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp), maxLines = 1)

/** A full-width row: the whole row is the target; pressed rows fill with surface2 at radius 20 (design §3.1). */
@Composable
fun PressRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp = 64.dp,
    onLongPress: (() -> Unit)? = null,
    label: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = minHeight).squish(onClick, scale = .97f, onLongPress = onLongPress, label = label)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)

/** Runs the platform Back (the same path as the system Back: top BackHandler first, else leave). */
@Composable expect fun rememberSystemBack(): () -> Unit

/** Transparent system bars with no system scrim; [dark] / [darkNavBar] = what the app paints under them (light icons on dark). */
@Composable expect fun SystemBars(dark: Boolean, darkNavBar: Boolean = dark)

/**
 * Bottom sheet that closes three ways: back gesture, scrim tap, the 48dp × (design rule 4). Drag the grabber down to dismiss too.
 */
@Composable
fun Sheet(visible: Boolean, onDismiss: () -> Unit, title: String? = null, heightFraction: Float? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Theme.colors
    val close = S.current.close
    val focus = LocalFocusManager.current
    BackHandler(visible, onDismiss)
    // A focused field (the composer) would swallow the first Back and leave the sheet open: let go of it as the sheet opens.
    LaunchedEffect(visible) { if (visible) focus.clearFocus() }
    // While open (and through its exit), the screen that owns it draws above the floating tab bar.
    var raised by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { if (visible) raised = true else { kotlinx.coroutines.delay(450); raised = false } }
    if (raised || visible) DisposableEffect(Unit) { SheetHost.open++; onDispose { SheetHost.open-- } }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
            Box(Modifier.fillMaxSize().background(c.scrim).pointerInput(Unit) { detectTapGestures { onDismiss() } }.semantics { contentDescription = close })
        }
        AnimatedVisibility(
            visible, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(Motion.sheet()) { it }, exit = slideOutVertically(Motion.sheet()) { it },
        ) {
            var drag by remember { mutableFloatStateOf(0f) }
            Column(
                Modifier.fillMaxWidth().then(if (heightFraction != null) Modifier.fillMaxHeight(heightFraction) else Modifier)
                    .offset { IntOffset(0, drag.roundToInt().coerceAtLeast(0)) }
                    .clip(RoundedCornerShape(topStart = Radius.sheet.dp, topEnd = Radius.sheet.dp)).background(c.surface)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .navigationBarsPadding().imePadding(),
            ) {
                Box(
                    Modifier.fillMaxWidth().height(24.dp).pointerInput(Unit) {
                        detectVerticalDragGestures(onDragEnd = { if (drag > 120f) onDismiss(); drag = 0f }) { _, dy -> drag += dy }
                    },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(40.dp, 5.dp).clip(CircleShape).background(c.hairline.let { if (c.dark) c.tertiary else Color(0xFFD5CFC7) })) }
                if (title != null) Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(title, Theme.type.title2, modifier = Modifier.weight(1f), maxLines = 1)
                    IconButton(Icons.close, close, onDismiss)
                }
                content()
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** How many sheets are up (or closing). The tab host raises its content above the tab bar while any is. */
object SheetHost {
    var open by mutableIntStateOf(0)
}

private fun Modifier.fillMaxHeight(f: Float): Modifier = layout { measurable, constraints ->
    val h = (constraints.maxHeight * f).roundToInt()
    val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
    layout(p.width, p.height) { p.place(0, 0) }
}

/** Dot under a pending message / small status marks. */
@Composable
fun Dot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) = Box(modifier.size(size).clip(CircleShape).background(color))

@Composable
fun Hairline(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(Theme.colors.hairline))

@Composable
fun BoxScope.Badge(count: Int, color: Color, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(modifier.align(Alignment.TopEnd).defaultMinSize(18.dp, 18.dp).clip(CircleShape).background(color).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
        Txt(if (count > 9) "9+" else "$count", Theme.type.micro, Color.White, maxLines = 1)
    }
}
