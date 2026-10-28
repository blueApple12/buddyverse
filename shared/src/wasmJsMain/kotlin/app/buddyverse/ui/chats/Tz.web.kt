package app.buddyverse.ui.chats

private fun tzOffsetMin(ms: Double): Int = js("new Date(ms).getTimezoneOffset()")

internal actual fun utcOffsetMs(atMs: Long): Long = -tzOffsetMin(atMs.toDouble()) * 60_000L
