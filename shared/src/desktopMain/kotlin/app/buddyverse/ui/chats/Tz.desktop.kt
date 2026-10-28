package app.buddyverse.ui.chats

internal actual fun utcOffsetMs(atMs: Long): Long = java.util.TimeZone.getDefault().getOffset(atMs).toLong()
