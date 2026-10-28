package app.buddyverse.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.Mood
import app.buddyverse.core.Attachment
import app.buddyverse.core.Bot
import app.buddyverse.core.SendMode
import app.buddyverse.core.api.transcribe
import app.buddyverse.core.nowMillis
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.PressRow
import app.buddyverse.design.Sound
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.chat
import app.buddyverse.ui.LocalStore
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.ltr
import app.buddyverse.ui.modelLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The draft, hoisted so the chat can quote into it, put text back (undo, edit) and see typing (the buddies listen). */
@Stable
class ComposerState {
    var value by mutableStateOf(TextFieldValue(""))
    val attachments = mutableStateListOf<Attachment>()
    /** When the user last typed (epoch ms). */
    var typedAt by mutableLongStateOf(0L)
    val text: String get() = value.text
    fun set(text: String) { value = TextFieldValue(text, TextRange(text.length)) }
    fun clear() { set(""); attachments.clear() }
    fun quote(text: String) = set(text.lines().take(6).joinToString("\n") { "> $it" } + "\n\n" + this.text)
}

/**
 * [draftKey] non-null = the unsent text is kept per chat: restored on open, saved 0.5 s after typing stops and when the chat
 * closes, cleared once sent (the field empties). Attachments are not kept.
 */
@OptIn(FlowPreview::class)
@Composable
fun rememberComposerState(draftKey: String? = null): ComposerState {
    val store = LocalStore.current
    val state = remember(draftKey) { ComposerState().apply { if (draftKey != null) set(store.draft(draftKey)) } }
    if (draftKey != null) {
        LaunchedEffect(state) { snapshotFlow { state.text }.drop(1).debounce(500).collect { store.saveDraft(draftKey, it) } }
        DisposableEffect(state) { onDispose { store.saveDraft(draftKey, state.text) } }
    }
    return state
}

/** Files dropped anywhere on this area land in [state]'s attachments (PC). */
@Composable
fun Modifier.attachDrop(state: ComposerState): Modifier {
    val fb = LocalFeedback.current
    val toasts = LocalToasts.current
    val s = S.current
    val sc = S.chat
    return fileDrop(
        onFiles = { state.attachments += it; fb.haptic(Haptic.Confirm) },
        onError = { e -> toasts.show(ToastData(when (e) { DeviceError.TooBig -> sc.tooBig; DeviceError.Denied -> sc.denied; DeviceError.Failed -> s.uploadFailed })) },
    )
}

private enum class Trailing { Mic, Send, Stop, Done }

/**
 * The composer (design §3.2): one capsule with [+] attach, the field, the model chip and the Mic/Send/Stop morph; long-press send modes.
 * Used by the bot chat and the group room ([mentions] non-empty = `@` opens the mention menu).
 * Keep this signature; add new parameters only with defaults.
 * [onSlash]: "/" typed into an empty field opens the command palette. [onModel]: shows the model chip ([model] = its label).
 */
@Composable
fun Composer(
    hint: String,
    running: Boolean,
    onSend: (text: String, mode: SendMode, attachments: List<Attachment>) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    mentions: List<Bot> = emptyList(),
    state: ComposerState = rememberComposerState(),
    profile: String? = null,
    onSlash: (() -> Unit)? = null,
    model: String? = null,
    onModel: (() -> Unit)? = null,
) {
    val s = S.current
    val sc = S.chat
    val c = Theme.colors
    val fb = LocalFeedback.current
    val toasts = LocalToasts.current
    val api = LocalStore.current.api
    val scope = rememberCoroutineScope()
    var plusOpen by remember { mutableStateOf(false) }
    var modesOpen by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recordedAt by remember { mutableLongStateOf(0L) }
    var transcribing by remember { mutableStateOf(false) }
    val device = rememberChatDevice(
        onPicked = { state.attachments += it; fb.haptic(Haptic.Confirm) },
        onError = { e -> toasts.show(ToastData(when (e) { DeviceError.TooBig -> sc.tooBig; DeviceError.Denied -> sc.denied; DeviceError.Failed -> s.uploadFailed })) },
    )
    val text = state.value.text
    val hasContent = text.isNotBlank() || state.attachments.isNotEmpty()
    val trailing = when {
        recording -> Trailing.Done
        hasContent -> Trailing.Send
        running -> Trailing.Stop
        device.voice && !transcribing -> Trailing.Mic
        else -> Trailing.Send
    }

    fun send(mode: SendMode) {
        if (!hasContent) return
        onSend(state.text, mode, state.attachments.toList())
        state.clear()
        fb.sound(Sound.Pop); fb.haptic(Haptic.Confirm)
    }

    fun finishRecording() {
        recording = false
        val clip = device.stopVoice() ?: return
        transcribing = true
        scope.launch {
            val r = runCatching { api.transcribe(profile, clip) }
            transcribing = false
            r.exceptionOrNull()?.let { if (it is CancellationException) throw it; toasts.show(ToastData(sc.voiceFailed)); return@launch }
            val heard = r.getOrNull()?.trim().orEmpty()
            if (heard.isEmpty()) toasts.show(ToastData(sc.noSpeech))
            else { state.set(listOf(state.text.trim(), heard).filter { it.isNotEmpty() }.joinToString(" ")); state.typedAt = nowMillis() }
        }
    }

    // `@` mention menu (rooms): the token at the caret.
    val caret = state.value.selection.start.coerceIn(0, text.length)
    val token = text.substring(0, caret).substringAfterLast(' ').substringAfterLast('\n')
    val mentionHits = if (mentions.isNotEmpty() && token.startsWith("@")) {
        val q = token.drop(1).lowercase()
        mentions.filter { it.profile.lowercase().startsWith(q) || it.name.lowercase().startsWith(q) }.take(8)
    } else emptyList()

    Column(modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)) {
        if (mentionHits.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp)) {
            for (b in mentionHits) MentionChip(b) {
                val start = caret - token.length
                val insert = "@${b.profile} "
                state.value = TextFieldValue(text.substring(0, start) + insert + text.substring(caret), TextRange(start + insert.length))
                fb.haptic(Haptic.Tick)
            }
        }
        if (state.attachments.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp)) {
            for (a in state.attachments.toList()) AttachmentChip(a, s.removeAttachment) { state.attachments.remove(a); fb.haptic(Haptic.Tick) }
        }
        // One capsule (design §3.2): [+] · the field, filling the width · model chip · Mic/Send/Stop. Buttons stay at the bottom as it grows.
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp)).background(c.surface2).padding(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box {
                if (recording) IconButton(Icons.close, s.cancel, { device.cancelVoice(); recording = false; fb.haptic(Haptic.Reject) })
                else IconButton(Icons.add, s.attach, { plusOpen = true })
                if (plusOpen) MenuPopup(s.attach, alignEnd = false, onDismiss = { plusOpen = false }) {
                    MenuItem(Icons.attachFile, s.file) { plusOpen = false; device.pick(PickKind.File) }
                    MenuItem(Icons.image, s.photo) { plusOpen = false; device.pick(PickKind.Photo) }
                    if (device.camera) MenuItem(ChatIcons.camera, s.camera) { plusOpen = false; device.pick(PickKind.Camera) }
                    MenuItem(Icons.contentCopy, sc.paste) { plusOpen = false; if (!device.pasteImage()) toasts.show(ToastData(sc.nothingToPaste)) }
                    if (onSlash != null) MenuItem(Icons.bolt, sc.commandsAndSkills) { plusOpen = false; onSlash() }
                }
            }
            Box(Modifier.weight(1f).heightIn(min = 48.dp).padding(start = 4.dp, end = 6.dp, top = 12.dp, bottom = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (recording) RecordingStrip(recordedAt)
                else BasicTextField(
                    value = state.value,
                    onValueChange = { v ->
                        if (onSlash != null && state.value.text.isEmpty() && v.text == "/") { fb.haptic(Haptic.Tick); onSlash() }
                        else { if (v.text != state.value.text) state.typedAt = nowMillis(); state.value = v }
                    },
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                        when {
                            e.type != KeyEventType.KeyDown -> false
                            device.enterSends && e.key == Key.Enter && !e.isShiftPressed -> { send(SendMode.Auto); true }
                            // Ctrl+V with a picture or copied files on the clipboard attaches them; plain text pastes as usual.
                            device.enterSends && e.key == Key.V && e.isCtrlPressed && !e.isShiftPressed -> device.pasteImage()
                            else -> false
                        }
                    },
                    textStyle = Theme.type.body.copy(color = c.text),
                    maxLines = 6,
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (state.value.text.isEmpty()) Txt(if (transcribing) sc.transcribing else hint, Theme.type.body, c.tertiary, maxLines = 1)
                            inner()
                        }
                    },
                )
            }
            if (onModel != null && !recording) ModelChip(model, sc.model, onModel)
            Box {
                SendButton(trailing, hasContent, onClick = {
                    when (trailing) {
                        Trailing.Send -> send(SendMode.Auto)
                        Trailing.Stop -> { onStop(); fb.haptic(Haptic.Reject) }
                        Trailing.Mic -> device.startVoice { ok -> if (ok) { recording = true; recordedAt = nowMillis(); fb.haptic(Haptic.Confirm) } }
                        Trailing.Done -> finishRecording()
                    }
                }, onLongPress = if (trailing == Trailing.Send && hasContent) ({ modesOpen = true }) else null)
                if (modesOpen) MenuPopup(s.send, alignEnd = true, onDismiss = { modesOpen = false }) {
                    if (running) {
                        MenuItem(Icons.send, s.tellNow) { modesOpen = false; send(SendMode.Steer) }
                        MenuItem(Icons.schedule, s.queueAfter) { modesOpen = false; send(SendMode.Queue) }
                        MenuItem(Icons.stop, s.stopAndSend) { modesOpen = false; send(SendMode.StopAndSend) }
                    } else MenuItem(Icons.send, s.send) { modesOpen = false; send(SendMode.Auto) }
                    MenuItem(Icons.lightbulb, s.askAside) { modesOpen = false; send(SendMode.Aside) }
                }
            }
        }
    }
}

@Composable
private fun SendButton(t: Trailing, hasContent: Boolean, onClick: () -> Unit, onLongPress: (() -> Unit)?) {
    val c = Theme.colors
    val s = S.current
    val fill by animateColorAsState(
        when (t) {
            Trailing.Send -> if (hasContent) c.accentFill else c.surface2
            Trailing.Stop -> c.text
            Trailing.Done -> c.accentFill
            Trailing.Mic -> c.surface2
        }, Motion.fade(), label = "fill",
    )
    val label = when (t) { Trailing.Send -> s.send; Trailing.Stop -> s.stop; Trailing.Mic -> s.mic; Trailing.Done -> s.done }
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(fill).squish(onClick, onLongPress = onLongPress, label = label),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            t,
            transitionSpec = { (scaleIn(Motion.morph(), initialScale = .6f) + fadeIn(Motion.morph())) togetherWith (scaleOut(Motion.morph(), targetScale = .6f) + fadeOut(Motion.fade())) },
            label = "trailing",
        ) { m ->
            when (m) {
                Trailing.Send -> Icon(Icons.arrowUpward, if (hasContent) c.onAccent else c.tertiary, 24.dp)
                Trailing.Stop -> Icon(Icons.stopFilled, c.ground, 24.dp)
                Trailing.Mic -> Icon(Icons.mic, c.text, 24.dp)
                Trailing.Done -> Icon(Icons.check, c.onAccent, 24.dp)
            }
        }
    }
}

@Composable
private fun ModelChip(model: String?, label: String, onClick: () -> Unit) {
    val c = Theme.colors
    Box(Modifier.height(48.dp).squish(onClick, haptic = Haptic.Tick, label = label), contentAlignment = Alignment.Center) {
        Row(Modifier.height(36.dp).clip(CircleShape).background(c.surface).padding(start = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(ltr(modelLabel(model) ?: S.chat.auto), Theme.type.caption, c.text, Modifier.widthIn(max = 132.dp), maxLines = 1)
            Icon(Icons.expandMore, c.secondary, 18.dp)
        }
    }
}

@Composable
private fun RecordingStrip(since: Long) {
    val c = Theme.colors
    val now by produceState(nowMillis(), since) { while (true) { delay(250); value = nowMillis() } }
    val pulse = rememberInfiniteTransition(label = "rec").animateFloat(.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dot")
    val secs = ((now - since) / 1000).coerceAtLeast(0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).graphicsLayer { alpha = pulse.value }.clip(CircleShape).background(c.error))
        Spacer(Modifier.width(10.dp))
        Txt(ltr("${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"), Theme.type.body, c.text, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        Txt(S.chat.listening, Theme.type.body, c.secondary, maxLines = 1)
    }
}

@Composable
private fun AttachmentChip(a: Attachment, removeLabel: String, onRemove: () -> Unit) {
    val c = Theme.colors
    Row(
        Modifier.padding(end = 6.dp).height(48.dp).clip(CircleShape).background(c.surface2).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (a.mime.startsWith("image/")) Icons.image else Icons.description, c.secondary, 18.dp)
        Spacer(Modifier.width(6.dp))
        Txt(a.name, Theme.type.caption, c.text, Modifier.widthIn(max = 150.dp), maxLines = 1)
        IconButton(Icons.close, removeLabel, onRemove, iconSize = 18.dp)
    }
}

@Composable
private fun MentionChip(b: Bot, onClick: () -> Unit) {
    val c = Theme.colors
    Row(
        Modifier.padding(end = 6.dp).height(48.dp).clip(CircleShape).background(c.surface2).squish(onClick, haptic = Haptic.Tick, label = b.name)
            .padding(start = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Buddy(b.spec, Mood.Idle, 32.dp, interactive = false)
        Spacer(Modifier.width(6.dp))
        Txt(b.name, Theme.type.callout, c.text, maxLines = 1)
    }
}

/** A small menu that floats above its anchor; closes by back, tapping outside, or its 48dp × (design rule 4). */
@Composable
internal fun MenuPopup(title: String, alignEnd: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Theme.colors
    val lift = with(LocalDensity.current) { 60.dp.roundToPx() }
    Popup(
        alignment = if (alignEnd) Alignment.BottomEnd else Alignment.BottomStart,
        offset = IntOffset(0, -lift),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.padding(8.dp).width(272.dp).shadow(18.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(c.surface)
                .padding(bottom = 6.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Txt(title, Theme.type.caps, c.secondary, maxLines = 1)
                IconButton(Icons.close, S.current.close, onDismiss)
            }
            content()
        }
    }
}

@Composable
internal fun MenuItem(icon: ImageVector, text: String, tint: androidx.compose.ui.graphics.Color = Theme.colors.text, onClick: () -> Unit) =
    PressRow(onClick, minHeight = 56.dp, label = text) {
        Icon(icon, tint, 22.dp)
        Spacer(Modifier.width(14.dp))
        Txt(text, Theme.type.callout, tint, maxLines = 1)
    }
