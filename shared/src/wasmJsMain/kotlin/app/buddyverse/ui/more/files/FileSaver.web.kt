package app.buddyverse.ui.more.files

import androidx.compose.runtime.Composable
import app.buddyverse.core.toJsBytes

private fun blobUrl(bytes: JsAny, mime: String): String = js("URL.createObjectURL(new Blob([bytes], { type: mime }))")
private fun clickDownload(url: String, name: String): Unit =
    js("{ const a = document.createElement('a'); a.href = url; a.download = name; document.body.appendChild(a); a.click(); a.remove(); }")
private fun openUrl(url: String): Unit = js("{ window.open(url, '_blank'); }")

/** Collected in memory, then handed to the browser as a download (iOS: the share/Files sheet). */
private object WebSaver : FileSaver {
    override fun create(name: String, mime: String): SavedFile = BlobFile(name, mime)
}

private class BlobFile(private val name: String, private val mime: String) : SavedFile {
    private var buf = ByteArray(64 * 1024)
    private var len = 0
    private var url: String? = null

    override fun write(bytes: ByteArray, count: Int) {
        if (len + count > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + count))
        bytes.copyInto(buf, len, 0, count)
        len += count
    }

    override fun finish(): Boolean = runCatching {
        val u = blobUrl(buf.copyOf(len).toJsBytes(), mime.ifEmpty { "application/octet-stream" })
        url = u
        clickDownload(u, name)
    }.isSuccess

    override fun abort() { buf = ByteArray(0); len = 0 }
    override fun open(): Boolean = url?.let { openUrl(it); true } ?: false
}

@Composable
actual fun rememberFileSaver(): FileSaver = WebSaver
