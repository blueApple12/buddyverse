package app.buddyverse.ui.more.artifacts

import androidx.compose.ui.graphics.ImageBitmap
import org.jetbrains.compose.resources.decodeToImageBitmap

internal actual fun decodeScaled(bytes: ByteArray, maxSide: Int): ImageBitmap? = runCatching { bytes.decodeToImageBitmap() }.getOrNull()
