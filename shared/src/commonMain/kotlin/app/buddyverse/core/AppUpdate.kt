package app.buddyverse.core

/**
 * The phone's self-update (ui/UpdateBanner.kt). `bin\publish-apk.ps1` on the PC writes [VERSION_JSON] next to [APK_PATH];
 * the phone reads both only through Hermes (`/api/fs/read-text`, `/api/fs/download`): the tunnel is its only way to the PC.
 * Paths are relative to the PC user's home (Hermes expands `~` to the gateway's user), so any install finds its own
 * `<home>\buddyverse` folder. A PC that doesn't build the app gets both files from the release feed (desktop `ReleaseFeed`).
 */
object AppUpdate {
    const val VERSION_JSON = "~/buddyverse/Buddyverse-version.json"
    const val APK_PATH = "~/buddyverse/Buddyverse.apk"
    const val APK_MIME = "application/vnd.android.package-archive"
    /** At most one check per 10 minutes (open + every return to the foreground). */
    const val CHECK_EVERY_MS = 10 * 60_000L

    /** `Buddyverse-version.json` → release; null when it isn't one (missing fields, bad hash, not json). */
    fun parse(text: String): AppRelease? = runCatching {
        val o = WireJson.parseToJsonElement(text).obj ?: return null
        val code = o.long("versionCode")?.toInt() ?: return null
        val sha = o.str("sha256")?.lowercase()?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } ?: return null
        AppRelease(code, o.str("versionName") ?: code.toString(), sha, o.long("size"))
    }.getOrNull()

    fun isNewer(r: AppRelease?, installedCode: Long): Boolean = r != null && r.versionCode > installedCode

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

data class AppRelease(val versionCode: Int, val versionName: String, val sha256: String, val size: Long? = null)

/** Incremental SHA-256: an APK is hashed chunk by chunk as it streams to disk. */
interface Sha256Stream {
    fun update(bytes: ByteArray, count: Int)
    fun digest(): ByteArray
}

expect fun sha256Stream(): Sha256Stream
