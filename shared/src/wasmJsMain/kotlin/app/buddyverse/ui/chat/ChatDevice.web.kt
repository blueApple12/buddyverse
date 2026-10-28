package app.buddyverse.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import app.buddyverse.core.Attachment
import app.buddyverse.core.toByteArray
import app.buddyverse.design.Theme

private fun tzOffsetMin(ms: Double): Int = js("new Date(ms).getTimezoneOffset()")
private fun touchDevice(): Boolean = js("(navigator.maxTouchPoints || 0) > 0")

/** A hidden `<input type=file>`; every chosen file arrives as (name, type, bytes), then [onDone]. */
private fun pickFiles(accept: String, camera: Boolean, onFile: (String, String, Double, JsAny?) -> Unit, onDone: () -> Unit): Unit = js("""{
    const i = document.createElement('input');
    i.type = 'file'; i.multiple = !camera; if (accept) i.accept = accept; if (camera) i.capture = 'environment';
    i.onchange = async () => {
        for (const f of Array.from(i.files || [])) {
            if (f.size > 26214400) { onFile(f.name, f.type || '', f.size, null); continue; }
            onFile(f.name, f.type || 'application/octet-stream', f.size, new Int8Array(await f.arrayBuffer()));
        }
        onDone();
    };
    i.click();
}""")

/** Called while a key event is being dispatched (Ctrl+V): the browser's own `paste` event brings the files then. */
private fun inKeyEvent(): Boolean = js("!!(window.event && typeof window.event.type === 'string' && window.event.type.indexOf('key') === 0)")
private fun canReadClipboard(): Boolean = js("!!(navigator.clipboard && navigator.clipboard.read)")

/** The async Clipboard API (the composer's Paste menu item; Safari shows its Paste bubble): images only. */
private fun readClipboardImages(onFile: (String, String, Double, JsAny?) -> Unit, onDone: (Boolean) -> Unit): Unit = js("""{
    navigator.clipboard.read().then(async items => {
        let n = 0;
        for (const it of items) {
            const t = it.types.find(x => x.indexOf('image/') === 0);
            if (!t) continue;
            const b = await it.getType(t); n++;
            onFile('pasted.' + (t.split('/')[1] || 'png'), t, b.size, b.size > 26214400 ? null : new Int8Array(await b.arrayBuffer()));
        }
        onDone(n > 0);
    }).catch(() => onDone(false));
}""")

/** Files in a DOM `paste` event (Ctrl/Cmd+V, iOS Paste in a text field) while a chat is open. Returns the handler. */
private fun listenPaste(onFile: (String, String, Double, JsAny?) -> Unit, onDone: () -> Unit): JsAny = js("""{
    const h = async e => {
        const fs = Array.from((e.clipboardData && e.clipboardData.files) || []);
        if (!fs.length) return;
        e.preventDefault(); e.stopPropagation();
        for (const f of fs) onFile(f.name || 'pasted.png', f.type || 'application/octet-stream', f.size, f.size > 26214400 ? null : new Int8Array(await f.arrayBuffer()));
        onDone();
    };
    document.addEventListener('paste', h, true);
    return h;
}""")
private fun unlistenPaste(h: JsAny): Unit = js("document.removeEventListener('paste', h, true)")

/** HTML5 file drag and drop onto the page (the canvas) while a chat is open. Returns the handlers. */
private fun listenDrop(onOver: (Boolean) -> Unit, onFile: (String, String, Double, JsAny?) -> Unit, onDone: () -> Unit): JsAny = js("""{
    let depth = 0;
    const files = e => !!e.dataTransfer && Array.from(e.dataTransfer.types || []).indexOf('Files') >= 0;
    const h = {
        dragenter: e => { if (!files(e)) return; e.preventDefault(); depth++; onOver(true); },
        dragover: e => { if (!files(e)) return; e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; },
        dragleave: e => { if (!files(e)) return; depth = Math.max(0, depth - 1); if (!depth) onOver(false); },
        drop: async e => {
            if (!files(e)) return;
            e.preventDefault(); e.stopPropagation(); depth = 0; onOver(false);
            for (const f of Array.from(e.dataTransfer.files || [])) {
                if (!f.size && !f.type) continue; // a folder
                onFile(f.name, f.type || 'application/octet-stream', f.size, f.size > 26214400 ? null : new Int8Array(await f.arrayBuffer()));
            }
            onDone();
        },
    };
    for (const k in h) document.addEventListener(k, h[k], true);
    return h;
}""")
private fun unlistenDrop(h: JsAny): Unit = js("{ for (const k in h) document.removeEventListener(k, h[k], true); }")

/** Collects (name, type, size, bytes) callbacks into attachments with the pickers' size limits; [finish] delivers. */
private class Batch(private val picked: (List<Attachment>) -> Unit, private val failed: (DeviceError) -> Unit) {
    private val got = mutableListOf<Attachment>()
    private var error: DeviceError? = null
    fun add(name: String, mime: String, size: Double, data: JsAny?) {
        val max = if (mime.startsWith("image/")) MAX_IMAGE_BYTES else MAX_FILE_BYTES
        if (data == null || size > max) error = DeviceError.TooBig
        else got += Attachment(name, mime, data.toByteArray())
    }
    fun finish() {
        if (got.isNotEmpty()) picked(got.toList())
        error?.let(failed)
        got.clear(); error = null
    }
}

/** Files and photos through the browser's picker (iOS: Photos, Camera, Files), pasted images, dropped files. No voice. */
@Composable
actual fun rememberChatDevice(onPicked: (List<Attachment>) -> Unit, onError: (DeviceError) -> Unit): ChatDevice {
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onError)
    DisposableEffect(Unit) {
        val b = Batch({ picked(it) }, { failed(it) })
        val h = listenPaste(b::add, b::finish)
        onDispose { unlistenPaste(h) }
    }
    return remember {
        object : ChatDevice {
            override val camera = touchDevice()
            override val voice = false
            override val enterSends = !touchDevice()
            override fun pick(kind: PickKind) {
                val b = Batch({ picked(it) }, { failed(it) })
                pickFiles(if (kind == PickKind.File) "" else "image/*", kind == PickKind.Camera, b::add, b::finish)
            }
            // Ctrl+V: false, so the text field still pastes text; an image comes with the browser's paste event.
            // The Paste menu item: the async Clipboard API (asks for permission / shows Safari's Paste bubble).
            override fun pasteImage(): Boolean {
                if (inKeyEvent() || !canReadClipboard()) return false
                val b = Batch({ picked(it) }, { failed(it) })
                readClipboardImages(b::add) { any -> if (any) b.finish() else failed(DeviceError.Failed) }
                return true
            }
            override fun startVoice(onStarted: (Boolean) -> Unit) = onStarted(false)
            override fun stopVoice(): Attachment? = null
            override fun cancelVoice() = Unit
        }
    }
}

/** Files dropped anywhere on the page while this chat is open become attachments (HTML5 drop events on the canvas). */
@Composable
actual fun Modifier.fileDrop(onFiles: (List<Attachment>) -> Unit, onError: (DeviceError) -> Unit): Modifier {
    val files by rememberUpdatedState(onFiles)
    val error by rememberUpdatedState(onError)
    var over by remember { mutableStateOf(false) }
    val ring = Theme.colors.accent
    DisposableEffect(Unit) {
        val b = Batch({ files(it) }, { error(it) })
        val h = listenDrop({ over = it }, b::add, b::finish)
        onDispose { unlistenDrop(h) }
    }
    return drawWithContent { drawContent(); if (over) { drawRect(ring.copy(alpha = .08f)); drawRect(ring, style = Stroke(4f)) } }
}

actual fun localOffsetMs(epochMs: Long): Long = -tzOffsetMin(epochMs.toDouble()) * 60_000L
