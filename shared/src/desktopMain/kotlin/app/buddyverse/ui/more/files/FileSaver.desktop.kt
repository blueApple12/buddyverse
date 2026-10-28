package app.buddyverse.ui.more.files

import androidx.compose.runtime.Composable
import java.awt.Desktop
import java.io.File
import java.io.OutputStream

/** `~/Downloads`, written to `<name>.part` and renamed when complete; a taken name becomes `name (1).ext`. */
private object DesktopSaver : FileSaver {
    override fun create(name: String, mime: String): SavedFile? = runCatching {
        val dir = File(System.getProperty("user.home"), "Downloads").apply { mkdirs() }
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var target = File(dir, name)
        var i = 1
        while (target.exists() || File(dir, target.name + ".part").exists()) target = File(dir, "$stem (${i++})$ext")
        val part = File(dir, target.name + ".part")
        DiskFile(part, target, part.outputStream().buffered())
    }.getOrNull()
}

private class DiskFile(private val part: File, private val target: File, private val out: OutputStream) : SavedFile {
    override fun write(bytes: ByteArray, count: Int) = out.write(bytes, 0, count)
    override fun finish(): Boolean = runCatching { out.close(); check(part.renameTo(target)) }.isSuccess.also { if (!it) abort() }
    override fun abort() { runCatching { out.close() }; part.delete() }
    override fun open(): Boolean = runCatching { Desktop.getDesktop().open(target) }.isSuccess
}

@Composable
actual fun rememberFileSaver(): FileSaver = DesktopSaver
