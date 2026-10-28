package app.buddyverse.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.RestError
import app.buddyverse.core.api.FileEntry
import app.buddyverse.core.api.downloadFile
import app.buddyverse.core.api.fileSize
import app.buddyverse.core.api.readBytes
import app.buddyverse.design.Haptic
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.PillButton
import app.buddyverse.design.Sound
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.chat
import app.buddyverse.i18n.filesPage
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.more.artifacts.decodeScaled
import app.buddyverse.ui.more.files.DownloadButton
import app.buddyverse.ui.more.files.FileGlyph
import app.buddyverse.ui.more.files.Kind
import app.buddyverse.ui.more.files.SavedFile
import app.buddyverse.ui.more.files.kind
import app.buddyverse.ui.more.files.rememberFileSaver
import app.buddyverse.ui.more.files.sizeText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Chat downloads by path: progress while running, then the saved file (the card's Open / Install). They outlive a card the list scrolls away. */
private object MediaSaves {
    val running = mutableStateMapOf<String, Float>()
    val saved = mutableStateMapOf<String, SavedFile>()
}

private const val PREVIEW_MAX = 8L * 1024 * 1024

/** The files a reply's `MEDIA:` tags point at ([splitMedia]), one card each. [profile] scopes the PC reads to the bot. */
@Composable
fun MediaFiles(paths: List<String>, profile: String?) {
    paths.forEach { MediaCard(it, profile) }
}

/** A file on the PC, fetched only through the API (`/api/fs/download`): glyph, name, size, Download → Open (Install for an APK on the phone). */
@Composable
fun MediaCard(path: String, profile: String?) {
    val c = Theme.colors
    val store = LocalStore.current
    val toasts = LocalToasts.current
    val fb = LocalFeedback.current
    val saver = rememberFileSaver()
    val t = S.filesPage
    val s = S.current
    val sc = S.chat
    val name = remember(path) { fileNameOf(path) }
    val kind = remember(path) { FileEntry(name, path, false).kind }
    val info by produceState<Result<Long?>?>(null, path, profile) { value = runCatching { store.fileSize(path, profile) } }
    val size = info?.getOrNull()
    val gone = (info?.exceptionOrNull() as? RestError)?.status in 400..499
    val preview by produceState<ImageBitmap?>(null, path, size) {
        if (kind == Kind.Image && size != null && size <= PREVIEW_MAX) {
            value = runCatching { withContext(Dispatchers.Default) { decodeScaled(store.api.readBytes(path, profile), 900) } }.getOrNull()
        }
    }
    val progress = MediaSaves.running[path]
    val done = MediaSaves.saved[path]

    fun open(f: SavedFile) { if (!f.open()) toasts.show(ToastData(t.noApp)) }
    fun download() {
        if (path in MediaSaves.running) return
        val out = saver.create(name, "application/octet-stream")
        if (out == null) { fb.haptic(Haptic.Reject); toasts.show(ToastData(t.cantSave)); return }
        MediaSaves.running[path] = 0f
        store.scope.launch {
            try {
                // Streams 64 KiB chunks straight to disk off the main thread (a 74 MB installer never sits in memory).
                withContext(Dispatchers.Default) {
                    store.downloadFile(path, profile, onChunk = out::write, onProgress = { MediaSaves.running[path] = it })
                }
                check(out.finish())
                MediaSaves.saved[path] = out
                fb.haptic(Haptic.Confirm); fb.sound(Sound.Bloop)
                toasts.show(ToastData(t.downloaded, if (out.installable) sc.install else s.open, onAction = { open(out) }, shot = OneShot.Done))
            } catch (x: CancellationException) {
                out.abort()
                throw x
            } catch (x: Exception) {
                out.abort()
                fb.haptic(Haptic.Reject); fb.sound(Sound.Bonk)
                toasts.show(ToastData(if (x is RestError && x.status in 400..499) sc.fileGone else s.error))
            } finally {
                MediaSaves.running.remove(path)
            }
        }
    }

    val meta = when {
        progress != null -> "${t.downloading} ${(progress * 100).toInt()}%"
        gone -> sc.fileGone
        done != null -> t.downloaded
        else -> size?.let(::sizeText).orEmpty()
    }
    Column(Modifier.padding(top = 8.dp).widthIn(max = 380.dp).clip(RoundedCornerShape(16.dp)).background(c.ground)) {
        preview?.let { Image(it, name, Modifier.fillMaxWidth().heightIn(max = 260.dp), contentScale = ContentScale.Fit) }
        Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            FileGlyph(kind, 40)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(name, Theme.type.sub, c.text, maxLines = 2)
                if (meta.isNotEmpty()) Txt(meta, Theme.type.micro, c.secondary, maxLines = 1)
            }
            Spacer(Modifier.width(4.dp))
            when {
                done != null && progress == null -> PillButton(if (done.installable) sc.install else s.open, { open(done) }, Modifier.padding(end = 6.dp), height = 40.dp)
                !gone -> DownloadButton(progress, ::download)
            }
        }
    }
}
