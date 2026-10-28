package app.buddyverse.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import app.buddyverse.core.Attachment

enum class PickKind { File, Photo, Camera }
enum class DeviceError { TooBig, Failed, Denied }

/** The phone/PC parts the composer needs: pickers, image paste, voice capture (design §3.2 Composer). */
@Stable
interface ChatDevice {
    val camera: Boolean
    val voice: Boolean
    /** Hardware Enter sends (PC); on the phone Enter is a new line. */
    val enterSends: Boolean
    fun pick(kind: PickKind)
    /** Attach an image or file from the clipboard; false = nothing to attach. */
    fun pasteImage(): Boolean
    /** Starts recording (asks for the mic first if needed); [onStarted] false = denied or no mic. */
    fun startVoice(onStarted: (Boolean) -> Unit)
    /** Stops and returns the clip, or null when nothing usable was recorded. */
    fun stopVoice(): Attachment?
    fun cancelVoice()
}

/** Files dragged onto this area (PC) become attachments; a no-op where the platform has no file drag. */
@Composable
expect fun Modifier.fileDrop(onFiles: (List<Attachment>) -> Unit, onError: (DeviceError) -> Unit): Modifier

@Composable
expect fun rememberChatDevice(onPicked: (List<Attachment>) -> Unit, onError: (DeviceError) -> Unit): ChatDevice

/** Local time-zone offset at [epochMs] (day separators; commonMain has no time zones). */
expect fun localOffsetMs(epochMs: Long): Long

const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
const val MAX_FILE_BYTES = 16L * 1024 * 1024
