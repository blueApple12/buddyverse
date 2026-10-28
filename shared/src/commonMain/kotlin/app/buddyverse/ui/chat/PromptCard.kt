package app.buddyverse.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.Mood
import app.buddyverse.core.Bot
import app.buddyverse.core.chat.Prompt
import app.buddyverse.core.chat.PromptKind
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Chip
import app.buddyverse.design.Field
import app.buddyverse.design.Haptic
import app.buddyverse.design.IconButton
import app.buddyverse.design.Icons
import app.buddyverse.design.PillButton
import app.buddyverse.design.Radius
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.chat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Inline approval / clarify card (design §3.2), amber leading edge. [onAnswer] gets the chosen value (an approval choice:
 * "once" | "session" | "always" | "deny", or the clarify answer); callers pass it to `ChatController.answer`.
 * Also used by the Bots home needs-you cards ([bot] = who asks). Keep this signature; add parameters only with defaults.
 */
@Composable
fun PromptCard(prompt: Prompt, onAnswer: (value: String) -> Unit, modifier: Modifier = Modifier, bot: Bot? = null) {
    val c = Theme.colors
    val s = S.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val title = when (prompt.kind) {
        PromptKind.Approval -> s.wantsToRun
        PromptKind.Clarify -> s.asks
        PromptKind.Secret -> s.secretTitle
        PromptKind.Sudo -> s.sudoTitle
        PromptKind.Vault -> s.vaultTitle
    }
    Column(
        modifier.fillMaxWidth().padding(vertical = 6.dp)
            .shadow(if (c.dark) 0.dp else 10.dp, RoundedCornerShape(Radius.card.dp), ambientColor = c.shadow, spotColor = c.shadow)
            .clip(RoundedCornerShape(Radius.card.dp)).background(c.surface)
            .drawBehind {
                val w = 2.dp.toPx()
                drawRect(c.needsYou, Offset(if (rtl) size.width - w else 0f, 0f), Size(w, size.height))
            }
            .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (bot != null) { Buddy(bot.spec, Mood.NeedsYou, 32.dp, interactive = false); Spacer(Modifier.width(10.dp)) }
            Txt(if (bot != null) "${bot.name} · $title" else title.replaceFirstChar { it.uppercase() }, Theme.type.headline, c.needsYouText, Modifier.weight(1f), maxLines = 1)
        }
        Spacer(Modifier.padding(top = 6.dp))
        when (prompt.kind) {
            PromptKind.Approval -> Approval(prompt, onAnswer)
            PromptKind.Clarify -> Clarify(prompt, onAnswer)
            else -> SecretInput(prompt, onAnswer)
        }
    }
}

@Composable
private fun CommandBlock(text: String) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(max = 160.dp).clip(RoundedCornerShape(12.dp)).background(Theme.colors.surface2)
                .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp),
        ) { Txt(text, Theme.type.code, Theme.colors.text) }
    }
}

@Composable
private fun Approval(p: Prompt, onAnswer: (String) -> Unit) {
    val s = S.current
    val sc = S.chat
    val choices = p.choices.ifEmpty { listOf("once", "session", "always", "deny") }
    val allow = choices.firstOrNull { it == "once" } ?: choices.firstOrNull { it != "deny" }
    val deny = choices.firstOrNull { it == "deny" }
    val extra = choices.filter { it != allow && it != deny }
    var more by remember(p.id) { mutableStateOf(false) }
    p.description?.takeIf { it.isNotBlank() && it != p.command }?.let { Txt(it, Theme.type.callout, Theme.colors.text) }
    p.command?.takeIf { it.isNotBlank() }?.let { CommandBlock(it) }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (deny != null) PillButton(s.deny, { onAnswer(deny) }, Modifier.widthIn(min = 120.dp), kind = ButtonKind.Outline, height = 48.dp, haptic = Haptic.Reject)
        if (allow != null) PillButton(s.allow, { onAnswer(allow) }, Modifier.widthIn(min = 120.dp), height = 48.dp, haptic = Haptic.Confirm)
        Spacer(Modifier.weight(1f))
        if (extra.isNotEmpty()) IconButton(Icons.moreHoriz, sc.moreChoices, { more = !more })
    }
    AnimatedVisibility(more) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (x in extra) PillButton(choiceLabel(x), { onAnswer(x) }, kind = ButtonKind.Quiet, height = 48.dp, haptic = Haptic.Confirm)
        }
    }
}

@Composable
private fun choiceLabel(x: String): String {
    val s = S.current
    return when (x) {
        "once" -> s.allow
        "session" -> s.allowSession
        "always" -> s.alwaysAllow
        "deny" -> s.deny
        else -> x
    }
}

@Composable
private fun Clarify(p: Prompt, onAnswer: (String) -> Unit) {
    val s = S.current
    val sc = S.chat
    var picked by remember(p.id) { mutableStateOf(setOf<String>()) }
    var typing by remember(p.id) { mutableStateOf(p.choices.isEmpty()) }
    var typed by remember(p.id) { mutableStateOf("") }
    p.question?.let { Txt(it, Theme.type.callout, Theme.colors.text) }
    if (p.choices.isNotEmpty()) Column(Modifier.padding(top = 8.dp)) {
        for (ch in p.choices) Chip(ch, ch in picked, {
            if (p.multiSelect) { picked = if (ch in picked) picked - ch else picked + ch } else onAnswer(ch)
        })
        if (!typing) Chip(sc.other, false, { typing = true })
    }
    if (typing) {
        Spacer(Modifier.padding(top = 8.dp))
        Field(typed, { typed = it }, s.typeAnswer, singleLine = false)
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton(s.skip, { onAnswer("") }, kind = ButtonKind.Outline, height = 48.dp, haptic = Haptic.Reject)
        val answer = when {
            typing && typed.isNotBlank() -> if (p.multiSelect && picked.isNotEmpty()) JsonArray((picked + typed.trim()).map { JsonPrimitive(it) }).toString() else typed.trim()
            p.multiSelect && picked.isNotEmpty() -> JsonArray(picked.map { JsonPrimitive(it) }).toString()
            else -> null
        }
        if (answer != null) PillButton(s.answer, { onAnswer(answer) }, height = 48.dp, haptic = Haptic.Confirm)
    }
}

/** Secret / sudo / vault: masked, never shown or logged; "" = skip (hermes-api.md §2.2). */
@Composable
private fun SecretInput(p: Prompt, onAnswer: (String) -> Unit) {
    val s = S.current
    var value by remember(p.id) { mutableStateOf("") }
    var user by remember(p.id) { mutableStateOf("") }
    val login = p.method == "vault.save_login"
    (p.description ?: p.command?.takeIf { p.kind != PromptKind.Sudo })?.takeIf { it.isNotBlank() }?.let { Txt(it, Theme.type.callout, Theme.colors.secondary) }
    if (p.kind == PromptKind.Sudo) p.command?.takeIf { it.isNotBlank() }?.let { CommandBlock(it) }
    Spacer(Modifier.padding(top = 8.dp))
    if (login) { Field(user, { user = it }, s.connectUser); Spacer(Modifier.padding(top = 8.dp)) }
    Field(value, { value = it }, if (login) s.connectPassword else s.typeAnswer, password = true)
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton(s.skip, { onAnswer("") }, kind = ButtonKind.Outline, height = 48.dp, haptic = Haptic.Reject)
        PillButton(s.submit, {
            onAnswer(if (login) JsonObject(mapOf("identifier" to JsonPrimitive(user), "password" to JsonPrimitive(value))).toString() else value)
        }, enabled = value.isNotEmpty(), height = 48.dp, haptic = Haptic.Confirm)
    }
}
