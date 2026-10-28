package app.buddyverse.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import app.buddyverse.core.api.CLAUDE_SUB
import app.buddyverse.core.api.LimitWindow
import app.buddyverse.core.api.PlanLimits
import app.buddyverse.core.api.Spend
import app.buddyverse.core.api.UsageTotals
import app.buddyverse.core.api.allBotsUsage
import app.buddyverse.core.api.planLimits
import app.buddyverse.core.nowMillis
import app.buddyverse.design.Card
import app.buddyverse.design.Icon
import app.buddyverse.design.Icons
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.usage
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.ltr
import app.buddyverse.ui.more.insights.compact
import app.buddyverse.ui.more.insights.money
import app.buddyverse.ui.untilIso
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Last loaded values, kept across Bots-screen visits so the card never flashes empty. */
private object UsageCache {
    var limits: List<PlanLimits>? = null
    var totals: UsageTotals? = null
}

/** Tokens a period moved through the models: fresh input, output and re-read (cached) context. */
internal fun Spend.allTokens(): Long = input + output + cacheRead

/**
 * Bots home ▸ Usage: Claude Max's session (5 h) and weekly limits with resets, other plans' windows (Antigravity…),
 * and all bots' tokens + API-price value for 24 h / 7 days. Refreshes every minute while shown; tap → Insights.
 */
@Composable
fun UsageCard(profiles: List<String>, onOpen: () -> Unit) {
    val store = LocalStore.current
    val t = S.usage
    val c = Theme.colors
    var limits by remember { mutableStateOf(UsageCache.limits) }
    var totals by remember { mutableStateOf(UsageCache.totals) }
    val brain = profiles.firstOrNull() ?: "default"
    LaunchedEffect(profiles) {
        while (true) {
            try { store.api.planLimits(brain).also { limits = it; UsageCache.limits = it } } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            try { store.api.allBotsUsage(profiles).also { totals = it; UsageCache.totals = it } } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            delay(60_000)
        }
    }
    val now = nowMillis()
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).squish(onOpen, label = t.title), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.insights, c.accent, 20.dp)
            Spacer(Modifier.width(8.dp))
            Txt(t.title, Theme.type.headline, modifier = Modifier.weight(1f), maxLines = 1)
            Icon(Icons.chevronRight, c.tertiary, 20.dp)
        }
        val l = limits
        when {
            l == null -> Txt(t.noLimits, Theme.type.sub, c.tertiary, Modifier.padding(top = 8.dp))
            else -> for (p in l) {
                val name = if (p.provider == CLAUDE_SUB || p.provider == "anthropic") t.claudeMax else p.name
                if (l.size > 1 || name != t.claudeMax) Txt(name, Theme.type.caption, c.secondary, Modifier.padding(top = 10.dp), maxLines = 1)
                for (w in p.windows) LimitBar(w, now)
            }
        }
        totals?.let { u ->
            Spacer(Modifier.height(12.dp))
            Txt(t.allBots, Theme.type.caption, c.secondary, maxLines = 1)
            SpendRow(t.day, u.day)
            SpendRow(t.week, u.week)
        }
    }
}

@Composable
private fun SpendRow(label: String, s: Spend) {
    val t = S.usage
    val c = Theme.colors
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Theme.type.callout, modifier = Modifier.width(72.dp), maxLines = 1)
        Txt(ltr(t.tokens(compact(s.allTokens()))), Theme.type.sub, c.secondary, Modifier.weight(1f), maxLines = 1)
        Txt(ltr(t.apiValue(money(s.cost))), Theme.type.sub, c.text, maxLines = 1)
    }
}

/** "Current session · 5% used · resets in 1h 40m" over a fill bar (amber from 75%, red from 90%). */
@Composable
internal fun LimitBar(w: LimitWindow, now: Long) {
    val t = S.usage
    val s = S.current
    val c = Theme.colors
    val pct = w.usedPercent.coerceIn(0.0, 100.0)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(t.window(w.label), Theme.type.callout, modifier = Modifier.weight(1f), maxLines = 1)
            Txt(ltr(listOfNotNull(t.used(pct.roundToInt()), untilIso(w.resetsAt, now, s)?.let(t.resetsIn)).joinToString(" · ")),
                Theme.type.caption, c.secondary, maxLines = 1)
        }
        Spacer(Modifier.height(5.dp))
        val fill = (pct / 100).toFloat()
        Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(c.surface2)) {
            if (fill > 0f) Box(Modifier.fillMaxWidth(maxOf(fill, .02f)).height(7.dp).clip(CircleShape)
                .background(when { pct >= 90 -> c.errorText; pct >= 75 -> c.needsYou; else -> c.working }))
        }
    }
}
