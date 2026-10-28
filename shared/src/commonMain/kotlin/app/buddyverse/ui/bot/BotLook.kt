package app.buddyverse.ui.bot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.BuddyShape
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.OneShot
import app.buddyverse.core.Bot
import app.buddyverse.core.BotStatus

/** Shape names as Bot Mode writes them to `ui_meta` ([BuddyShape.parse] reads both spellings). */
internal fun BuddyShape.wire(): String = when (this) {
    BuddyShape.Blob -> "blob"
    BuddyShape.Pebble -> "circle"
    BuddyShape.Squircle -> "squircle"
    BuddyShape.Tablet -> "pill"
    BuddyShape.Wedge -> "triangle"
    BuddyShape.Hex -> "hexagon"
    BuddyShape.Cloud -> "cloud"
    BuddyShape.Teardrop -> "drop"
}

internal fun Color.css(): String = "#" + (toArgb() and 0xFFFFFF).toString(16).padStart(6, '0')

/** The `ui_meta` look patch for a title + shape + color (hermes-api.md §2.4 Edit bot: changing the look sets `custom`). */
internal fun look(title: String, shape: BuddyShape, color: Color): Map<String, Any?> =
    mapOf("title" to title, "shape" to shape.wire(), "color" to color.css(), "imageKind" to "shape", "custom" to true)

internal val HANDLE = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
internal val RESERVED = setOf("hermes", "default", "test", "tmp", "root", "sudo")

/** A free profile id for a friendly [title]. Letters outside a-z (Hebrew names) fall back to `bot`. */
internal fun handleFor(title: String, taken: Set<String>): String {
    val base = title.lowercase().map { if (it in 'a'..'z' || it in '0'..'9' || it == '_') it else '-' }.joinToString("")
        .replace(Regex("-+"), "-").trim('-', '_').take(56).ifEmpty { "bot" }
    return freeHandle(base, taken)
}

/** [base] if free, else `base-2`, `base-3`… (Desktop's duplicate naming), always within 64 chars. */
internal fun freeHandle(base: String, taken: Set<String>): String {
    fun free(h: String) = h !in taken && h !in RESERVED
    if (free(base)) return base
    return (2..999).asSequence().map { "${base.take(60)}-$it" }.first(::free)
}

/** Starter persona for a new bot (Desktop `composeSoul`; the server adds the teammate protocol itself). */
internal fun defaultSoul(title: String, handle: String, role: String): String = buildString {
    append("# ").append(title).append("\n\n")
    if (role.isNotBlank()) append("**Role:** ").append(role).append("\n\n")
    append("You are ").append(title).append(", a persistent named agent (profile `").append(handle).append("`) on this machine.\n")
    append("You keep your own memory, skills, and conversation history across sessions.\n")
}

internal fun BotStatus.mood(): Mood = when (this) {
    BotStatus.Idle -> Mood.Idle
    BotStatus.Working -> Mood.Working
    BotStatus.NeedsYou -> Mood.NeedsYou
    BotStatus.Error -> Mood.Error
    BotStatus.Offline -> Mood.Offline
}

private val showcase = listOf(
    OneShot.Wave, OneShot.Giggle, OneShot.Done, OneShot.Learned, OneShot.Wiggle, OneShot.Greeting,
    OneShot.Nod, OneShot.Stretch, OneShot.Yawn, OneShot.Dizzy, OneShot.Spin, OneShot.Dance, OneShot.Laugh,
)

/** One-shot reactions for a buddy: [fire] plays one, [next] walks through every reaction (tap the big buddy). */
@Stable
internal class Cues {
    var cue by mutableStateOf<BuddyCue?>(null)
        private set
    fun fire(s: OneShot) { cue = BuddyCue((cue?.serial ?: 0L) + 1, s) }
    fun next() = fire(showcase[((cue?.serial ?: 0L) % showcase.size).toInt()])
}

/** Counts each false→true flip of [open]: key a sheet's body on it so every opening starts fresh, while closing keeps it intact. */
@Composable
internal fun openCount(open: Boolean): Int {
    val s = remember { intArrayOf(0, 0) }
    if (open && s[1] == 0) s[0]++
    s[1] = if (open) 1 else 0
    return s[0]
}

/** A name / role / look just saved in the studio. */
@Immutable
data class BotEdit(val name: String, val description: String, val shape: BuddyShape, val color: Color)

/** Studio edits on their way to the server, shown at once until the roster poll carries them (design rule 3: optimistic first). */
object BotEdits {
    val pending = mutableStateMapOf<String, BotEdit>()
}

/** This bot with its pending studio edit, if any. Draw bots through it so a saved look shows the same frame. */
fun Bot.edited(): Bot = BotEdits.pending[profile]?.let { e ->
    copy(name = e.name, description = e.description, spec = spec.copy(name = e.name, shape = e.shape, color = e.color))
} ?: this
