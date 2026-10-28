package app.buddyverse.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.buddyverse.core.BotStatus
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.Icons
import app.buddyverse.design.Motion
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.S
import kotlin.math.roundToInt

/** Floating capsule, 4 tabs: Bots · Chats · Routines · More (design §2.1). */
@Composable
fun TabBar(modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    val store = LocalStore.current
    val s = S.current
    val c = Theme.colors
    val bots by store.bots.collectAsState()
    val needs = bots.count { it.status == BotStatus.NeedsYou }
    val tabs = tabList(s)
    val index = tabs.indexOfFirst { it.first == nav.tab }
    val pill by animateFloatAsState(index.toFloat(), Motion.snappy())
    BoxWithConstraints(
        modifier.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 8.dp).fillMaxWidth().height(68.dp)
            .shadow(18.dp, CircleShape, ambientColor = c.shadow, spotColor = c.shadow).clip(CircleShape).background(c.surface),
    ) {
        val itemW = (maxWidth - 8.dp) / tabs.size
        Box(
            // offset {} already mirrors x in RTL: no extra sign, or the pill is mirrored twice (off screen).
            Modifier.padding(4.dp).offset { IntOffset((pill * itemW.toPx()).roundToInt(), 0) }
                .width(itemW).fillMaxHeight().clip(CircleShape).background(c.accentFill.copy(alpha = if (c.dark) .28f else .12f)),
        )
        Row(Modifier.padding(4.dp).fillMaxWidth()) {
            tabs.forEachIndexed { i, (tab, label, icons) ->
                TabItem(label, if (i == index) icons.second else icons.first, i == index, itemW,
                    badge = if (tab == Tab.Bots) needs else 0) { nav.select(tab) }
            }
        }
    }
}

private fun tabList(s: app.buddyverse.i18n.Strings) = listOf(
    Triple(Tab.Bots, s.tabBots, Icons.smartToy to Icons.smartToyFilled),
    Triple(Tab.Chats, s.tabChats, Icons.forum to Icons.forumFilled),
    Triple(Tab.Routines, s.tabRoutines, Icons.schedule to Icons.scheduleFilled),
    Triple(Tab.More, s.tabMore, Icons.apps to Icons.appsFilled),
)

/** Wide windows: the same 4 tabs as an 80dp rail on the start edge (design §2.2); [footer] sits at its bottom. */
@Composable
fun NavRail(modifier: Modifier = Modifier, footer: @Composable () -> Unit = {}) {
    val nav = LocalNav.current
    val store = LocalStore.current
    val c = Theme.colors
    val bots by store.bots.collectAsState()
    val needs = bots.count { it.status == BotStatus.NeedsYou }
    val tabs = tabList(S.current)
    val index = tabs.indexOfFirst { it.first == nav.tab }
    val pill by animateFloatAsState(index.toFloat(), Motion.snappy())
    val itemH = 68.dp
    Column(modifier.width(80.dp).fillMaxHeight().background(c.surface).padding(top = 16.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                Modifier.offset { IntOffset(0, (pill * itemH.toPx()).roundToInt()) }.padding(vertical = 4.dp).size(72.dp, itemH - 8.dp)
                    .clip(RoundedCornerShape(20.dp)).background(c.accentFill.copy(alpha = if (c.dark) .28f else .12f)),
            )
            Column {
                tabs.forEachIndexed { i, (tab, label, icons) ->
                    Box(Modifier.height(itemH), contentAlignment = Alignment.Center) {
                        TabItem(label, if (i == index) icons.second else icons.first, i == index, 72.dp, badge = if (tab == Tab.Bots) needs else 0) { nav.select(tab) }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        footer()
    }
}

@Composable
private fun TabItem(label: String, icon: ImageVector, active: Boolean, width: Dp, badge: Int, onClick: () -> Unit) {
    val c = Theme.colors
    val hop = remember { Animatable(0f) }
    LaunchedEffect(active) { if (active) { hop.snapTo(0f); hop.animateTo(-3f, Motion.bouncy()); hop.animateTo(0f, Motion.bouncy()) } }
    Box(Modifier.width(width).height(60.dp).squish(onClick, haptic = Haptic.Tick, label = label), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                Icon(icon, if (active) c.accent else c.secondary, 26.dp, Modifier.graphicsLayer { translationY = hop.value * density })
                if (badge > 0) Box(Modifier.align(Alignment.TopEnd).offset(8.dp, (-4).dp).size(18.dp).clip(CircleShape).background(c.needsYou), contentAlignment = Alignment.Center) {
                    Txt("$badge", Theme.type.micro, androidx.compose.ui.graphics.Color.White, maxLines = 1)
                }
            }
            Txt(label, Theme.type.micro, if (active) c.accent else c.secondary, maxLines = 1)
        }
    }
}
