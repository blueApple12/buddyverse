package app.buddyverse.ui.project

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyColors
import app.buddyverse.buddy.Mood
import app.buddyverse.core.Bot
import app.buddyverse.core.BotStatus
import app.buddyverse.core.ChatTarget
import app.buddyverse.core.api.LiveSubagent
import app.buddyverse.design.Icon
import app.buddyverse.design.Icons
import app.buddyverse.design.PressRow
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import app.buddyverse.i18n.project
import app.buddyverse.ui.bot.mood
import app.buddyverse.ui.chat.helperSpec
import app.buddyverse.ui.room.specOf

/** Where a helper can be watched: its own chat (read-only) when known, else the chat that spawned it (its work card shows the helper live), else the bot's main chat. */
internal fun helperTarget(b: Bot?, profile: String, sa: LiveSubagent, child: String? = null): ChatTarget =
    if (child != null) ChatTarget(profile, child, botChat = false, watch = true)
    else if (sa.ownerSession == null || sa.ownerSession == b?.botChatId) ChatTarget(profile) else ChatTarget(profile, sa.ownerSession)

/** "#RRGGBB" project color, else the accent. */
@Composable
internal fun projectTint(css: String?): Color = BuddyColors.parse(css) ?: Theme.colors.accent

/** One agent of the project: live buddy, name, what it's doing; tap opens its chat. Its helpers hang under it. */
@Composable
internal fun AgentRow(profile: String, bots: List<Bot>, helpers: List<LiveSubagent>, onOpen: () -> Unit, onHelper: (LiveSubagent) -> Unit) {
    val c = Theme.colors
    val s = S.current
    val b = bots.firstOrNull { it.profile == profile }
    val spec = specOf(profile, bots)
    val status = b?.status ?: BotStatus.Idle
    Column(Modifier.fillMaxWidth()) {
        PressRow(onOpen, minHeight = 72.dp, label = b?.name ?: profile) {
            Buddy(spec, status.mood(), 44.dp, interactive = false)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(b?.name ?: profile, Theme.type.headline, maxLines = 1)
                val line = when (status) {
                    BotStatus.NeedsYou -> s.needsYou
                    BotStatus.Working -> s.working
                    else -> b?.preview ?: b?.description
                }
                if (!line.isNullOrBlank()) Txt(line, Theme.type.sub, when (status) {
                    BotStatus.NeedsYou -> c.needsYouText
                    BotStatus.Working -> c.working
                    else -> c.secondary
                }, maxLines = 1)
            }
            Icon(Icons.chevronRight, c.tertiary, 24.dp, mirror = true)
        }
        for (sa in helpers) key(sa.id) {
            PressRow({ onHelper(sa) }, Modifier.padding(start = 36.dp), minHeight = 56.dp, label = S.project.watch) {
                Buddy(helperSpec(spec, sa.id), Mood.Working, 28.dp, interactive = false)
                Spacer(Modifier.width(12.dp))
                Txt(sa.goal ?: S.project.helpers, Theme.type.sub, c.text, Modifier.weight(1f), maxLines = 1)
                Icon(Icons.visibility, c.secondary, 22.dp)
            }
        }
    }
}

/** Helpers working right now, as minis of their parents; tap one to watch it (every agent stays visible). */
@Composable
internal fun HelpersStrip(helpers: Map<String, List<LiveSubagent>>, bots: List<Bot>, onHelper: (String, LiveSubagent) -> Unit) {
    val c = Theme.colors
    val all = helpers.flatMap { (p, list) -> list.map { p to it } }
    if (all.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(S.project.helpers, Theme.type.caps, c.secondary, Modifier.padding(end = 6.dp), maxLines = 1)
        for ((p, sa) in all) key(p + sa.id) {
            Box(Modifier.size(56.dp).squish({ onHelper(p, sa) }, label = sa.goal ?: S.project.watch), contentAlignment = Alignment.Center) {
                Buddy(helperSpec(specOf(p, bots), sa.id), Mood.Working, 30.dp, interactive = false)
            }
        }
    }
    Spacer(Modifier.height(2.dp))
}
