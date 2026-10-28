package app.buddyverse.ui.more.files

import androidx.compose.runtime.Composable

/** Where downloads land: the phone's Downloads (MediaStore) or the PC's Downloads folder. */
interface FileSaver {
    /** Starts a new file in Downloads; null when this device can't save one. */
    fun create(name: String, mime: String): SavedFile?
}

/** A download being written. [write] blocks briefly (call it off the main thread). */
interface SavedFile {
    fun write(bytes: ByteArray, count: Int)
    /** Closes the file and makes it visible; false = it failed (and was removed). */
    fun finish(): Boolean
    fun abort()
    /** Opens the finished file in the system app for its type; false = no app can. */
    fun open(): Boolean
    /** [open] installs it (an APK on the phone): the button says Install. */
    val installable: Boolean get() = false
}

@Composable
expect fun rememberFileSaver(): FileSaver
