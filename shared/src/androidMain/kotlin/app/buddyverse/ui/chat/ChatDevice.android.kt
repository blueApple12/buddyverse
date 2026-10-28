package app.buddyverse.ui.chat

import android.Manifest
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.buddyverse.core.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private class TooBig : Exception()

private class AndroidParts(val context: Context) {
    var pendingShot: Uri? = null
    var onMic: ((Boolean) -> Unit)? = null
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    val camera: Boolean = Build.VERSION.SDK_INT >= 29 && context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    val voice: Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    fun read(uri: Uri): Attachment {
        val cr = context.contentResolver
        var name = "file"
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                if (!c.isNull(0)) name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val max = if (mime.startsWith("image/")) MAX_IMAGE_BYTES else MAX_FILE_BYTES
        if (size > max) throw TooBig()
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("unreadable")
        if (bytes.size > max) throw TooBig()
        return Attachment(name, mime, bytes)
    }

    /** A gallery row the camera app writes into; deleted again once read (no FileProvider needed). */
    fun newShot(): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "chat-${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        return runCatching { context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) }.getOrNull()?.also { pendingShot = it }
    }

    fun discard(uri: Uri) { runCatching { context.contentResolver.delete(uri, null, null) } }

    fun startRecording(): Boolean = try {
        val f = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(16_000)
        r.setAudioEncodingBitRate(48_000)
        r.setOutputFile(f.path)
        r.prepare()
        r.start()
        recorder = r; file = f
        true
    } catch (e: Exception) {
        recorder?.release(); recorder = null
        false
    }

    fun stopRecording(keep: Boolean): Attachment? {
        val r = recorder ?: return null
        val f = file
        recorder = null; file = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        if (!ok || !keep || f == null) { f?.delete(); return null }
        return runCatching { Attachment("voice.m4a", "audio/mp4", f.readBytes()) }.getOrNull().also { f.delete() }
    }
}

// ponytail: the phone has no file drag into a full-screen app; the + menu covers it.
@Composable
actual fun Modifier.fileDrop(onFiles: (List<Attachment>) -> Unit, onError: (DeviceError) -> Unit): Modifier = this

@Composable
actual fun rememberChatDevice(onPicked: (List<Attachment>) -> Unit, onError: (DeviceError) -> Unit): ChatDevice {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picked = rememberUpdatedState(onPicked)
    val failed = rememberUpdatedState(onError)
    val parts = remember(context) { AndroidParts(context.applicationContext) }

    fun deliver(uris: List<Uri>, deleteAfter: Boolean = false) {
        if (uris.isEmpty()) return
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                uris.map { u -> runCatching { parts.read(u) }.also { if (deleteAfter) parts.discard(u) } }
            }
            val ok = results.mapNotNull { it.getOrNull() }
            if (ok.isNotEmpty()) picked.value(ok)
            results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { failed.value(if (it is TooBig) DeviceError.TooBig else DeviceError.Failed) }
        }
    }

    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { deliver(it) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { deliver(it) }
    val shot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = parts.pendingShot
        parts.pendingShot = null
        if (u != null) { if (ok) deliver(listOf(u), deleteAfter = true) else parts.discard(u) }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) parts.newShot()?.let { shot.launch(it) } ?: failed.value(DeviceError.Failed) else failed.value(DeviceError.Denied)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val cb = parts.onMic
        parts.onMic = null
        if (!ok) failed.value(DeviceError.Denied)
        cb?.invoke(ok && parts.startRecording())
    }

    return remember(parts) {
        object : ChatDevice {
            override val camera = parts.camera
            override val voice = parts.voice
            override val enterSends = false

            override fun pick(kind: PickKind) {
                when (kind) {
                    PickKind.File -> files.launch(arrayOf("*/*"))
                    PickKind.Photo -> photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    PickKind.Camera -> if (parts.granted(Manifest.permission.CAMERA)) parts.newShot()?.let { shot.launch(it) } ?: failed.value(DeviceError.Failed)
                        else cameraPermission.launch(Manifest.permission.CAMERA)
                }
            }

            override fun pasteImage(): Boolean {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
                val clip = cm.primaryClip ?: return false
                val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                if (uris.isEmpty()) return false
                deliver(uris)
                return true
            }

            override fun startVoice(onStarted: (Boolean) -> Unit) {
                if (parts.granted(Manifest.permission.RECORD_AUDIO)) onStarted(parts.startRecording())
                else { parts.onMic = onStarted; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
            }

            override fun stopVoice(): Attachment? = parts.stopRecording(keep = true)
            override fun cancelVoice() { parts.stopRecording(keep = false) }
        }
    }
}

actual fun localOffsetMs(epochMs: Long): Long = java.util.TimeZone.getDefault().getOffset(epochMs).toLong()
