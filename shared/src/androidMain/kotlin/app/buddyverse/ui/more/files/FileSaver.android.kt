package app.buddyverse.ui.more.files

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.OutputStream

/** MediaStore Downloads (API 29+, no storage permission). ponytail: Android 9 can't save; add an app-folder fallback if anyone runs it. */
private class AndroidSaver(private val ctx: Context) : FileSaver {
    override fun create(name: String, mime: String): SavedFile? {
        if (Build.VERSION.SDK_INT < 29) return null
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: mime
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, type)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return null
        val out = runCatching { cr.openOutputStream(uri) }.getOrNull()
        if (out == null) { runCatching { cr.delete(uri, null, null) }; return null }
        return StoreFile(ctx, uri, type, out)
    }
}

/** Only built by [AndroidSaver] on API 29+. */
private class StoreFile(private val ctx: Context, private val uri: Uri, private val type: String, private val out: OutputStream) : SavedFile {
    override fun write(bytes: ByteArray, count: Int) = out.write(bytes, 0, count)

    // ACTION_VIEW on this content:// Downloads URI hands it to the package installer (REQUEST_INSTALL_PACKAGES in the manifest).
    override val installable get() = type == "application/vnd.android.package-archive"

    override fun finish(): Boolean = runCatching {
        out.close()
        ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }.isSuccess.also { if (!it) abort() }

    override fun abort() {
        runCatching { out.close() }
        runCatching { ctx.contentResolver.delete(uri, null, null) }
    }

    override fun open(): Boolean = runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
}

@Composable
actual fun rememberFileSaver(): FileSaver {
    val ctx = LocalContext.current.applicationContext
    return remember(ctx) { AndroidSaver(ctx) }
}
