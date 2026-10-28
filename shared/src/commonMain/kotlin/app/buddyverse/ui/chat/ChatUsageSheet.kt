package app.buddyverse.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.buddyverse.core.api.ChatUsage
import app.buddyverse.core.api.chatUsage
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.usage
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.ltr
import app.buddyverse.ui.modelLabel
import app.buddyverse.ui.more.insights.compact
import app.buddyverse.ui.more.insights.grouped
import app.buddyverse.ui.more.insights.money
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Chat ▸ .. ▸ Usage: this chat's context window fill, tokens and API-price cost. Refreshes every 5 s while open. */
@Composable
fun ChatUsageSheet(visible: Boolean, sessionId: String?, profile: String, onDismiss: () -> Unit) {
    val store = LocalStore.current
    val t = S.usage
    val c = Theme.colors
    var u by remember(sessionId) { mutableStateOf<ChatUsage?>(null) }
    LaunchedEffect(visible, sessionId) {
        if (!visible || sessionId == null) return@LaunchedEffect
        while (true) {
            try { u = store.api.chatUsage(sessionId, profile) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            delay(5_000)
        }
    }
    Sheet(visible, onDismiss, title = t.chatUsage) {
        val x = u
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            when {
                sessionId == null -> Txt(t.notStarted, Theme.type.sub, c.tertiary)
                x == null -> Spinner(22.dp, c.tertiary)
                else -> {
                    x.model?.let { Line(t.model, ltr(modelLabel(it) ?: it)) }
                    Label(t.context)
                    val fill = x.contextFill
                    if (fill == null) Txt(t.contextUnknown, Theme.type.sub, c.tertiary)
                    else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Txt(ltr(t.contextOf(compact(x.contextUsed!!), compact(x.contextMax!!))), Theme.type.callout, modifier = Modifier.weight(1f))
                            Txt("${(fill * 100).roundToInt()}%", Theme.type.callout, c.secondary)
                        }
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.surface2)) {
                            if (fill > 0f) Box(Modifier.fillMaxWidth(maxOf(fill, .02f)).height(8.dp).clip(CircleShape)
                                .background(when { fill >= .9f -> c.errorText; fill >= .7f -> c.needsYou; else -> c.working }))
                        }
                    }
                    Label(t.tokensUsed)
                    Line(t.total, ltr(grouped(x.total)), strong = true)
                    Line(t.input, ltr(grouped(x.input)))
                    Line(t.output, ltr(grouped(x.output)))
                    if (x.cacheRead > 0) Line(t.cacheRead, ltr(grouped(x.cacheRead)))
                    if (x.cacheWrite > 0) Line(t.cacheWrite, ltr(grouped(x.cacheWrite)))
                    if (x.reasoning > 0) Line(t.reasoning, ltr(grouped(x.reasoning)))
                    if (x.calls > 0) Line(t.calls, ltr(grouped(x.calls)))
                    Label(t.cost)
                    Line(t.total, ltr(money(x.costUsd ?: 0.0)), strong = true)
                    Txt(t.apiValueNote, Theme.type.caption, c.tertiary, Modifier.padding(top = 4.dp, bottom = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) = Txt(text.uppercase(), Theme.type.caps, Theme.colors.secondary, Modifier.padding(top = 16.dp, bottom = 6.dp), maxLines = 1)

@Composable
private fun Line(label: String, value: String, strong: Boolean = false) {
    val c = Theme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Theme.type.callout, if (strong) c.text else c.secondary, Modifier.weight(1f), maxLines = 1)
        Txt(value, Theme.type.callout, c.text, maxLines = 1)
    }
}
