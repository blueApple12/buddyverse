package app.buddyverse.ui.more.artifacts

import androidx.compose.ui.graphics.ImageBitmap

/** Decodes an image no larger than about [maxSide] px on its long side (a grid of full-size photos would run the phone out of memory). Null = not an image. */
internal expect fun decodeScaled(bytes: ByteArray, maxSide: Int): ImageBitmap?
