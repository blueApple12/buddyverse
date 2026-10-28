package app.buddyverse.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import app.buddyverse.design.Theme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.buddyverse.core.Attachment
import app.buddyverse.design.Synth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp")
private const val RATE = 16_000
private val FORMAT = AudioFormat(RATE.toFloat(), 16, 1, true, false)

/** Reading files and encoding screenshots runs here, never on the window's thread (a 4K PNG takes hundreds of ms). */
private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Files to attachments (folders skipped), read in the background; the size limits match the pickers. */
private fun deliverFiles(files: List<File>, picked: (List<Attachment>) -> Unit, failed: (DeviceError) -> Unit) {
    if (files.isEmpty()) return
    io.launch {
        val ok = mutableListOf<Attachment>()
        var error: DeviceError? = null
        for (f in files) {
            if (!f.isFile) continue
            val ext = f.name.substringAfterLast('.').lowercase()
            val mime = if (ext in IMAGE_EXT) "image/${if (ext == "jpg") "jpeg" else ext}"
                else runCatching { java.nio.file.Files.probeContentType(f.toPath()) }.getOrNull() ?: "application/octet-stream"
            val max = if (mime.startsWith("image/")) MAX_IMAGE_BYTES else MAX_FILE_BYTES
            if (f.length() > max) { error = DeviceError.TooBig; continue }
            runCatching { ok += Attachment(f.name, mime, f.readBytes()) }.onFailure { error = DeviceError.Failed }
        }
        withContext(Dispatchers.Main) {
            if (ok.isNotEmpty()) picked(ok)
            error?.let(failed)
        }
    }
}

/** [img] encoded as a PNG attachment in the background. */
private fun deliverImage(img: Image, picked: (List<Attachment>) -> Unit, failed: (DeviceError) -> Unit) {
    io.launch {
        val a = runCatching { imageAttachment(img) }.getOrNull()
        withContext(Dispatchers.Main) { if (a != null) picked(listOf(a)) else failed(DeviceError.Failed) }
    }
}

/** An image (screenshot, browser copy) as a PNG attachment. */
private fun imageAttachment(img: Image): Attachment? {
    val buf = img as? BufferedImage ?: BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB).also {
        val g = it.createGraphics(); g.drawImage(img, 0, 0, null); g.dispose()
    }
    val out = ByteArrayOutputStream()
    if (!ImageIO.write(buf, "png", out)) return null
    return Attachment("pasted.png", "image/png", out.toByteArray())
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.fileDrop(onFiles: (List<Attachment>) -> Unit, onError: (DeviceError) -> Unit): Modifier {
    val files = rememberUpdatedState(onFiles)
    val error = rememberUpdatedState(onError)
    var over by remember { mutableStateOf(false) }
    val ring = Theme.colors.accent
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { over = true }
            override fun onExited(event: DragAndDropEvent) { over = false }
            override fun onEnded(event: DragAndDropEvent) { over = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                over = false
                val t = event.awtTransferable
                return runCatching {
                    when {
                        t.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
                            deliverFiles((t.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>(), files.value, error.value); true
                        }
                        t.isDataFlavorSupported(DataFlavor.imageFlavor) ->
                            (t.getTransferData(DataFlavor.imageFlavor) as? Image)?.let { deliverImage(it, files.value, error.value); true } ?: false
                        else -> false
                    }
                }.getOrElse { error.value(DeviceError.Failed); false }
            }
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target)
        .drawWithContent { drawContent(); if (over) { drawRect(ring.copy(alpha = .08f)); drawRect(ring, style = Stroke(4f)) } }
}

private class DesktopDevice(private val picked: (List<Attachment>) -> Unit, private val failed: (DeviceError) -> Unit) : ChatDevice {
    override val camera = false
    override val enterSends = true
    override val voice = runCatching { AudioSystem.isLineSupported(DataLine.Info(TargetDataLine::class.java, FORMAT)) }.getOrDefault(false)
    private var line: TargetDataLine? = null
    private var buffer: ByteArrayOutputStream? = null
    private var reader: Thread? = null

    override fun pick(kind: PickKind) {
        val d = FileDialog(null as Frame?, "", FileDialog.LOAD)
        d.isMultipleMode = true
        if (kind == PickKind.Photo) {
            d.file = IMAGE_EXT.joinToString(";") { "*.$it" } // Windows filter
            d.setFilenameFilter { _, n -> n.substringAfterLast('.').lowercase() in IMAGE_EXT }
        }
        d.isVisible = true
        deliver(d.files.orEmpty().toList())
    }

    private fun deliver(files: List<File>) = deliverFiles(files, picked, failed)

    override fun pasteImage(): Boolean {
        val cb = runCatching { Toolkit.getDefaultToolkit().systemClipboard }.getOrNull() ?: return false
        if (runCatching { cb.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) }.getOrDefault(false)) {
            val files = runCatching { (cb.getData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>() }.getOrDefault(emptyList())
            if (files.isNotEmpty()) { deliver(files); return true }
        }
        if (!runCatching { cb.isDataFlavorAvailable(DataFlavor.imageFlavor) }.getOrDefault(false)) return false
        // Word/Excel/OneNote copy text AND a picture of it: text wins, so the normal paste runs.
        if (runCatching { cb.isDataFlavorAvailable(DataFlavor.stringFlavor) }.getOrDefault(false)) return false
        val img = runCatching { cb.getData(DataFlavor.imageFlavor) as? Image }.getOrNull() ?: return false
        deliverImage(img, picked, failed)
        return true
    }

    override fun startVoice(onStarted: (Boolean) -> Unit) {
        finish() // a second start must not leak the first line
        val ok = runCatching {
            val l = AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, FORMAT)) as TargetDataLine
            l.open(FORMAT); l.start()
            val out = ByteArrayOutputStream()
            val t = Thread {
                val chunk = ByteArray(4096)
                while (l.isOpen) { val n = l.read(chunk, 0, chunk.size); if (n > 0) synchronized(out) { out.write(chunk, 0, n) } else if (n < 0) break }
            }.apply { isDaemon = true; start() }
            line = l; buffer = out; reader = t
        }.isSuccess
        if (!ok) failed(DeviceError.Failed)
        onStarted(ok)
    }

    private fun finish(): ByteArray? {
        val l = line ?: return null
        val out = buffer
        line = null; buffer = null
        runCatching { l.stop(); l.close() }
        runCatching { reader?.join(500) }
        reader = null
        return out?.let { synchronized(it) { it.toByteArray() } }
    }

    override fun stopVoice(): Attachment? {
        val raw = finish() ?: return null
        if (raw.size < RATE / 2) return null // under ~0.25 s
        val pcm = ShortArray(raw.size / 2) { i -> ((raw[2 * i].toInt() and 0xff) or (raw[2 * i + 1].toInt() shl 8)).toShort() }
        return Attachment("voice.wav", "audio/wav", Synth.wav(pcm, RATE))
    }

    override fun cancelVoice() { finish() }
}

@Composable
actual fun rememberChatDevice(onPicked: (List<Attachment>) -> Unit, onError: (DeviceError) -> Unit): ChatDevice {
    val picked = rememberUpdatedState(onPicked)
    val failed = rememberUpdatedState(onError)
    val device = remember { DesktopDevice({ picked.value(it) }, { failed.value(it) }) }
    // Leaving the chat mid-recording must free the mic (Windows keeps its mic light on otherwise).
    DisposableEffect(device) { onDispose { device.cancelVoice() } }
    return device
}

actual fun localOffsetMs(epochMs: Long): Long = java.util.TimeZone.getDefault().getOffset(epochMs).toLong()
