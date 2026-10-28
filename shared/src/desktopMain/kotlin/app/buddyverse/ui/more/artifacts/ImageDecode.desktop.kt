package app.buddyverse.ui.more.artifacts

import androidx.compose.ui.graphics.ImageBitmap
import org.jetbrains.compose.resources.decodeToImageBitmap

/** ponytail: full-size decode on the PC (memory is plentiful there); scale with Skia if a huge gallery ever stutters. */
internal actual fun decodeScaled(bytes: ByteArray, maxSide: Int): ImageBitmap? = runCatching { bytes.decodeToImageBitmap() }.getOrNull()
