package app.buddyverse.ui.more.files

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddySpec
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.AppStore
import app.buddyverse.core.Attachment
import app.buddyverse.core.RestError
import app.buddyverse.core.api.FileEntry
import app.buddyverse.core.api.FolderListing
import app.buddyverse.core.api.TextFile
import app.buddyverse.core.api.deletePath
import app.buddyverse.core.api.downloadFile
import app.buddyverse.core.api.listFolder
import app.buddyverse.core.api.makeFolder
import app.buddyverse.core.api.putFile
import app.buddyverse.core.api.readBytes
import app.buddyverse.core.api.readText
import app.buddyverse.core.api.workspaceRoot
import app.buddyverse.core.api.writeText
import app.buddyverse.core.nowMillis
import app.buddyverse.design.Feedback
import app.buddyverse.design.Haptic
import app.buddyverse.design.NoFeedback
import app.buddyverse.design.Sound
import app.buddyverse.i18n.EnglishStrings
import app.buddyverse.i18n.FilesPageStrings
import app.buddyverse.i18n.Strings
import app.buddyverse.ui.ToastData
import app.buddyverse.ui.Toasts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.roundToInt

// ---------------------------------------------------------------- paths (the PC is Windows, but POSIX hosts work too)

private fun sepOf(path: String) = if ('\\' in path || (path.length >= 2 && path[1] == ':')) '\\' else '/'

internal fun join(dir: String, name: String): String = if (dir.endsWith('\\') || dir.endsWith('/')) dir + name else "$dir${sepOf(dir)}$name"

/** `C:\Users\alex` → (`C:\`, `C:\`), (`Users`, `C:\Users`), (`alex`, `C:\Users\alex`); `/home/u` → (`/`, `/`), (`home`, `/home`), (`u`, `/home/u`). */
internal fun crumbs(path: String): List<Pair<String, String>> {
    if (path.isEmpty()) return emptyList()
    val sep = sepOf(path)
    val out = mutableListOf<Pair<String, String>>()
    var acc = when {
        path.startsWith("$sep$sep") -> "$sep$sep"
        path.startsWith(sep) -> "$sep"
        else -> ""
    }
    if (acc == "$sep") out += acc to acc
    for (part in path.split(sep).filter { it.isNotEmpty() }) {
        acc = when {
            acc.isEmpty() -> if (part.endsWith(':')) "$part$sep" else part
            acc.endsWith(sep) -> acc + part
            else -> "$acc$sep$part"
        }
        out += (if (part.endsWith(':')) "$part$sep" else part) to acc
    }
    return out
}

private val badChars = Regex("""[\\/:*?"<>|]""")
internal fun validName(n: String): Boolean = n.trim().let { it.isNotEmpty() && it != "." && it != ".." && !badChars.containsMatchIn(it) }

/** `a.txt` → `a (1).txt`, `a (2).txt`… against [taken] (lowercase; Windows names ignore case). */
internal fun uniqueName(name: String, taken: Set<String>): String {
    if (name.lowercase() !in taken) return name
    val stem = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || stem.isEmpty()) "" else ".$it" }
    var i = 1
    while ("$stem ($i)$ext".lowercase() in taken) i++
    return "$stem ($i)$ext"
}

private val clutter = setOf("node_modules", "__pycache__", "venv", "desktop.ini", "thumbs.db")

/** Dot-files, `$Recycle.Bin`-style system names and build clutter; shown with the Hidden chip. */
internal val FileEntry.hiddenish: Boolean
    get() = name.startsWith('.') || name.startsWith('$') || name.startsWith("ntuser", ignoreCase = true) || name.lowercase() in clutter

internal enum class Kind { Folder, Image, Text, Code, Pdf, Video, Audio, Other }

private val imageExt = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
private val codeExt = setOf(
    "kt", "kts", "java", "py", "js", "mjs", "ts", "tsx", "jsx", "json", "yaml", "yml", "toml", "xml", "html", "css", "sh", "ps1",
    "bat", "cmd", "c", "h", "cpp", "rs", "go", "gradle", "sql", "swift", "rb", "php", "lua",
)
private val textExt = setOf("md", "txt", "log", "csv", "ini", "cfg", "conf", "env", "properties")

internal val FileEntry.kind: Kind
    get() {
        if (isDir) return Kind.Folder
        val ext = name.substringAfterLast('.', "").lowercase()
        val m = mime.orEmpty()
        return when {
            ext in imageExt || (m.startsWith("image/") && m != "image/svg+xml") -> Kind.Image
            ext == "pdf" || m == "application/pdf" -> Kind.Pdf
            m.startsWith("video/") || ext in setOf("mp4", "mov", "mkv", "webm", "avi") -> Kind.Video
            m.startsWith("audio/") || ext in setOf("mp3", "wav", "ogg", "m4a", "flac", "aac", "opus") -> Kind.Audio
            ext in codeExt -> Kind.Code
            ext in textExt || m.startsWith("text/") -> Kind.Text
            else -> Kind.Other
        }
    }

/** "512 B", "12.3 KB", "4.1 MB" (Western digits; callers keep it LTR). */
internal fun sizeText(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var u = 0
    while (v >= 1024 && u < units.lastIndex) { v /= 1024; u++ }
    if (u == 0) return "$bytes B"
    val tenths = (v * 10).roundToInt()
    return if (v >= 100 || tenths % 10 == 0) "${v.roundToInt()} ${units[u]}" else "${tenths / 10}.${tenths % 10} ${units[u]}"
}

private val byName = compareBy<FileEntry>({ !it.isDir }, { it.name.lowercase() })

// ---------------------------------------------------------------- viewer state

internal sealed interface Body {
    data object Loading : Body
    class Text(val file: TextFile, val chunks: List<String>) : Body
    class Picture(val image: ImageBitmap) : Body
    class NoPreview(val why: String) : Body
    class Failed(val why: String) : Body
}

/** The open file: what it shows, and the edit draft while editing. */
@Stable
internal class Viewer(val entry: FileEntry) {
    var body by mutableStateOf<Body>(Body.Loading)
    var draft by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)
    var askDiscard by mutableStateOf(false)
    val text: TextFile? get() = (body as? Body.Text)?.file
    val canEdit: Boolean get() = text?.let { !it.truncated && !it.binary } == true
    val dirty: Boolean get() = draft != null && draft != text?.text
}

/** An upload whose name is already taken in [dir]: Replace or Keep both. */
internal class Conflict(val file: Attachment, val dir: String, val path: String)

private fun chunks(text: String): List<String> = text.lines().chunked(60).map { it.joinToString("\n") }

// ---------------------------------------------------------------- the model

/**
 * More ▸ Files state (hermes-api.md §2.6). Browses the PC from a bot's workspace folder (`/api/fs/default-cwd`) with the managed
 * listing (`/api/files`: size, time, parent), previews and edits text (`/api/fs/read-text|write-text`), shows images
 * (`/api/fs/read-data-url`), downloads to the device (`/api/fs/download`), uploads, makes folders and files, deletes.
 * Loads run on the page's [scope]; writes and transfers run on the app scope so leaving the page doesn't cut them off.
 */
@Stable
internal class FilesModel(private val store: AppStore, private val scope: CoroutineScope) {
    private val api get() = store.api
    var fb: Feedback = NoFeedback
    var toasts: Toasts? = null
    var t: FilesPageStrings = FilesPageStrings()
    var s: Strings = EnglishStrings
    var spec: BuddySpec? = null

    var profile by mutableStateOf("")
        private set
    var folder by mutableStateOf<String?>(null)
        private set
    var listing by mutableStateOf<FolderListing?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var showHidden by mutableStateOf(false)
    var query by mutableStateOf("")
    var cue by mutableStateOf<BuddyCue?>(null)
        private set
    var viewer by mutableStateOf<Viewer?>(null)
        private set
    /** Folders to go back to (system back walks it before leaving the page). */
    val history = mutableStateListOf<String>()
    /** Path → progress 0..1 of downloads in flight. */
    val downloads = mutableStateMapOf<String, Float>()
    val uploads = mutableStateListOf<String>()
    val conflicts = mutableStateListOf<Conflict>()
    private var busy by mutableIntStateOf(0)

    private val roots = mutableMapOf<String, String>()
    private var loadJob: Job? = null
    private var viewJob: Job? = null
    private var outsideJob: Job? = null
    private var closeAfterDiscard = false
    private var loadSeq = 0L
    private var serial = 0L

    val mood: Mood
        get() = when {
            loadError != null && listing == null -> Mood.Error
            busy > 0 || loading -> Mood.Working
            else -> Mood.Idle
        }

    val shown: List<FileEntry>
        get() {
            val q = query.trim()
            return listing?.entries.orEmpty().filter { (showHidden || !it.hiddenish) && (q.isEmpty() || it.name.contains(q, ignoreCase = true)) }
        }

    val hiddenCount: Int get() = listing?.entries?.count { it.hiddenish } ?: 0
    val taken: Set<String> get() = listing?.entries.orEmpty().mapTo(HashSet()) { it.name.lowercase() }

    private fun shot(s: OneShot) { cue = BuddyCue(++serial, s) }
    private fun toast(text: String, action: String? = null, onAction: (() -> Unit)? = null, shot: OneShot? = null) =
        toasts?.show(ToastData(text, action, onAction, buddy = if (shot != null) spec else null, shot = shot))

    private fun failed(e: Throwable) {
        fb.haptic(Haptic.Reject)
        fb.sound(Sound.Bonk)
        shot(OneShot.Startle)
        toast(describe(e))
    }

    fun describe(e: Throwable): String = when (e) {
        is RestError -> when (e.status) {
            403 -> t.notAllowed
            404 -> t.notThere
            413 -> t.tooBig
            else -> e.detail.ifBlank { t.failed }
        }
        is IllegalStateException -> t.failed
        else -> t.offline
    }

    // ---- browsing

    /** Jump to [p]'s workspace folder. */
    fun select(p: String) {
        if (p == profile && folder != null) return
        profile = p
        history.clear()
        query = ""
        launchLoad { load(roots[p] ?: runCatching { api.workspaceRoot(p) }.getOrNull()?.also { roots[p] = it }, quiet = false) }
    }

    fun open(path: String) {
        val cur = folder
        if (path == cur) return
        if (cur != null) history += cur
        query = ""
        launchLoad { load(path, quiet = false) }
    }

    fun up() { listing?.parent?.let(::open) }

    fun goBack() {
        val p = history.removeLastOrNull() ?: return
        query = ""
        launchLoad { load(p, quiet = false) }
    }

    fun refresh() = launchLoad { load(folder, quiet = listing != null) }

    /** An agent tool finished somewhere: it may have touched this folder (the gateway has no fs watcher). */
    fun changedOutside() {
        outsideJob?.cancel()
        outsideJob = scope.launch { delay(600); if (!loading) load(folder, quiet = true) }
    }

    private fun launchLoad(block: suspend () -> Unit) {
        loadJob?.cancel()
        loadJob = scope.launch { block() }
    }

    private suspend fun load(path: String?, quiet: Boolean) {
        val my = ++loadSeq
        if (!quiet) loading = true
        try {
            val l = api.listFolder(path)
            listing = l
            if (!quiet && folder != null && folder != l.path) shot(OneShot.Nod)
            folder = l.path
            loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!quiet) {
                if (path != folder) listing = null
                folder = path ?: folder
                loadError = describe(e)
                shot(OneShot.Startle)
            }
        } finally {
            if (my == loadSeq) loading = false
        }
    }

    // ---- viewing and editing

    fun view(e: FileEntry, edit: Boolean = false) {
        val v = Viewer(e)
        viewer = v
        viewJob?.cancel()
        viewJob = scope.launch {
            v.body = try {
                when (e.kind) {
                    Kind.Image -> {
                        val bytes = api.readBytes(e.path, profile)
                        runCatching { withContext(Dispatchers.Default) { bytes.decodeToImageBitmap() } }
                            .fold({ Body.Picture(it) }, { Body.NoPreview(t.noPreview) })
                    }
                    Kind.Pdf, Kind.Video, Kind.Audio -> Body.NoPreview(t.noPreview)
                    else -> {
                        val f = api.readText(e.path, profile)
                        if (f.binary) Body.NoPreview(t.noPreview) else Body.Text(f, chunks(f.text))
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                if (x is RestError && x.status == 413) Body.NoPreview(t.tooBigPreview) else Body.Failed(describe(x))
            }
            if (edit && v.canEdit) v.draft = v.text?.text
        }
    }

    fun retryView() { viewer?.let { view(it.entry) } }

    /** Opens [e] as one of [p]'s files without browsing to its folder (More ▸ Artifacts reuses this viewer). */
    fun viewAs(p: String, e: FileEntry) { profile = p; view(e) }

    fun downloadAs(p: String, e: FileEntry, saver: FileSaver) { profile = p; download(e, saver) }

    /** Back, scrim or ×: a dirty draft asks first (rule 4 still holds: the ask itself closes three ways). */
    fun closeViewer() {
        val v = viewer ?: return
        if (v.dirty) { closeAfterDiscard = true; v.askDiscard = true; return }
        viewJob?.cancel()
        viewer = null
    }

    /** Drops the draft; closes the file too when the ask came from closing it. */
    fun discard() {
        val v = viewer ?: return
        v.askDiscard = false
        v.draft = null
        if (closeAfterDiscard) { viewJob?.cancel(); viewer = null }
    }

    fun keepEditing() { viewer?.askDiscard = false }

    fun startEdit() { viewer?.let { v -> if (v.canEdit) v.draft = v.text?.text } }
    fun stopEdit() {
        val v = viewer ?: return
        if (v.dirty) { closeAfterDiscard = false; v.askDiscard = true } else v.draft = null
    }

    fun save() {
        val v = viewer ?: return
        val text = v.draft ?: return
        val old = v.text ?: return
        if (v.saving) return
        v.saving = true
        busy++
        store.scope.launch {
            try {
                val size = api.writeText(v.entry.path, text, profile)
                v.body = Body.Text(old.copy(text = text, byteSize = size ?: text.encodeToByteArray().size.toLong()), chunks(text))
                v.draft = null
                fb.haptic(Haptic.Confirm)
                shot(OneShot.Done)
                toast(t.saved)
                if (folder != null) refresh()
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                failed(x)
            } finally {
                v.saving = false
                busy--
            }
        }
    }

    // ---- writes

    private fun insert(e: FileEntry) {
        val l = listing ?: return
        if (l.entries.any { it.name.equals(e.name, ignoreCase = true) }) return
        listing = l.copy(entries = (l.entries + e).sortedWith(byName))
    }

    fun create(name: String, dir: Boolean) {
        val base = folder ?: return
        val n = name.trim()
        val path = join(base, n)
        val entry = FileEntry(n, path, dir, size = if (dir) null else 0, mtime = nowMillis() / 1000.0)
        insert(entry)
        fb.haptic(Haptic.Confirm)
        busy++
        store.scope.launch {
            try {
                if (dir) api.makeFolder(path) else api.putFile(path, "text/plain", ByteArray(0), overwrite = false)
                shot(OneShot.Done)
                if (!dir) view(entry, edit = true)
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                failed(x)
            } finally {
                busy--
                refresh()
            }
        }
    }

    fun delete(e: FileEntry) {
        listing?.let { l -> listing = l.copy(entries = l.entries.filterNot { it.path == e.path }) }
        busy++
        store.scope.launch {
            try {
                api.deletePath(e.path, recursive = e.isDir)
                shot(OneShot.Nod)
                toast(t.deleted)
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                failed(x)
            } finally {
                busy--
                refresh()
            }
        }
    }

    fun upload(files: List<Attachment>) {
        val dir = folder ?: return
        files.forEach { a -> put(a, dir, join(dir, a.name.replace(badChars, "_")), overwrite = false) }
    }

    fun resolve(c: Conflict, replace: Boolean) {
        conflicts.remove(c)
        if (replace) put(c.file, c.dir, c.path, overwrite = true)
        else put(c.file, c.dir, join(c.dir, uniqueName(c.path.substringAfterLast(sepOf(c.path)), taken)), overwrite = false)
    }

    fun skip(c: Conflict) { conflicts.remove(c) }

    private fun put(a: Attachment, dir: String, path: String, overwrite: Boolean) {
        val name = path.substringAfterLast(sepOf(path))
        if (dir == folder && !overwrite) insert(FileEntry(name, path, false, a.bytes.size.toLong(), nowMillis() / 1000.0, a.mime))
        uploads += path
        busy++
        store.scope.launch {
            try {
                api.putFile(path, a.mime, a.bytes, overwrite)
                fb.haptic(Haptic.Confirm)
                shot(OneShot.Done)
                toast(t.uploaded(name))
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                if (x is RestError && x.status == 409 && !overwrite) conflicts += Conflict(a, dir, path) else failed(x)
            } finally {
                uploads.remove(path)
                busy--
                if (dir == folder) refresh()
            }
        }
    }

    fun download(e: FileEntry, saver: FileSaver) {
        if (e.isDir || e.path in downloads) return
        val out = saver.create(e.name, e.mime ?: "application/octet-stream")
        if (out == null) { fb.haptic(Haptic.Reject); toast(t.cantSave); return }
        downloads[e.path] = 0f
        busy++
        store.scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    store.downloadFile(e.path, profile, onChunk = out::write, onProgress = { downloads[e.path] = it })
                }
                check(out.finish())
                fb.sound(Sound.Bloop)
                shot(OneShot.Done)
                toast(t.downloaded, action = s.open, onAction = { if (!out.open()) toast(t.noApp) }, shot = OneShot.Done)
            } catch (c: CancellationException) {
                out.abort()
                throw c
            } catch (x: Exception) {
                out.abort()
                failed(x)
            } finally {
                downloads.remove(e.path)
                busy--
            }
        }
    }
}
