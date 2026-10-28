package app.buddyverse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import app.buddyverse.design.SheetHost
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.BuddyClock
import app.buddyverse.buddy.BuddyCue
import app.buddyverse.buddy.LocalBuddyClock
import app.buddyverse.buddy.LookTarget
import app.buddyverse.buddy.Mood
import app.buddyverse.buddy.ProvideBuddyClock
import app.buddyverse.core.AppStore
import app.buddyverse.core.LinkState
import app.buddyverse.core.ThemePref
import app.buddyverse.core.UiSignal
import app.buddyverse.core.systemLanguage
import app.buddyverse.design.AppTheme
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Feedback
import app.buddyverse.design.GatedFeedback
import app.buddyverse.design.Haptic
import app.buddyverse.design.LocalFeedback
import app.buddyverse.design.Motion
import app.buddyverse.design.PillButton
import app.buddyverse.design.Sound
import app.buddyverse.design.SystemBars
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.BackHandler
import app.buddyverse.i18n.EnglishStrings
import app.buddyverse.i18n.HebrewStrings
import app.buddyverse.i18n.Lang
import app.buddyverse.i18n.LocalStrings
import app.buddyverse.i18n.S
import app.buddyverse.ui.chat.ChatScreen
import app.buddyverse.ui.chats.ChatsScreen
import app.buddyverse.ui.connect.ConnectScreen
import app.buddyverse.ui.home.BotsScreen
import app.buddyverse.ui.more.MoreDetail
import app.buddyverse.ui.more.MoreScreen
import app.buddyverse.ui.project.ProjectScreen
import app.buddyverse.ui.room.RoomScreen
import app.buddyverse.ui.routines.RoutinesScreen
import kotlinx.coroutines.delay

val LocalStore = androidx.compose.runtime.staticCompositionLocalOf<AppStore> { error("no store") }

/**
 * The whole app. The PC app passes its own [nav] (window shortcuts drive it), a [railFooter] for the wide layout's rail
 * and an [overlay] drawn above the screens (its sheets, inside the app's theme and strings).
 */
@Composable
fun AgenticApp(
    store: AppStore, platform: Feedback, nav: Nav = remember { Nav() },
    railFooter: @Composable () -> Unit = {}, overlay: @Composable () -> Unit = {},
) {
    val prefs by store.prefs.collectAsState()
    val dark = when (prefs.theme) { ThemePref.System -> isSystemInDarkTheme(); ThemePref.Light -> false; ThemePref.Dark -> true }
    val hebrew = when (prefs.lang) { Lang.System -> systemLanguage() in setOf("he", "iw"); Lang.Hebrew -> true; Lang.English -> false }
    val strings = if (hebrew) HebrewStrings else EnglishStrings
    val feedback = remember(platform) { GatedFeedback(platform, { store.prefs.value.sounds }, { store.prefs.value.haptics }, { store.foreground.value }) }
    val toasts = remember { Toasts() }
    CompositionLocalProvider(
        LocalStrings provides strings,
        LocalLayoutDirection provides if (hebrew) LayoutDirection.Rtl else LayoutDirection.Ltr,
        LocalFeedback provides feedback,
        LocalStore provides store,
        LocalNav provides nav,
        LocalToasts provides toasts,
    ) {
        // A 3-button bar (a gesture bar is ~24 dp) gets a dark strip we paint under its keys, in both themes: some bars ignore
        // the light-keys flag (Android 16's 3-button taskbar draws white keys whatever the app asks), so light keys on dark it is.
        val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val keysStrip = navBar >= 40.dp
        SystemBars(dark, darkNavBar = dark || keysStrip)
        AppTheme(dark) {
            ProvideBuddyClock(prefs.motion) {
                val clock = LocalBuddyClock.current
                val focus = androidx.compose.ui.platform.LocalFocusManager.current
                val systemBack = app.buddyverse.design.rememberSystemBack()
                val imeInsets = WindowInsets.ime
                val density = androidx.compose.ui.platform.LocalDensity.current
                Box(
                    Modifier.fillMaxSize().background(Theme.colors.ground)
                        // A focused field with the keyboard down eats Back just to drop its focus (a sheet stayed open, a chat
                        // didn't close). Take that Back first: drop focus and run the real Back. With the keyboard up, Back
                        // still only closes the keyboard.
                        .onPreInterceptKeyBeforeSoftKeyboard { e ->
                            LookTarget.inputAtMs = app.buddyverse.core.nowMillis()
                            if (e.key != Key.Back || imeInsets.getBottom(density) > 0) return@onPreInterceptKeyBeforeSoftKeyboard false
                            if (e.type == KeyEventType.KeyUp) { focus.clearFocus(); systemBack() }
                            true
                        }
                        .pointerInput(Unit) {
                        // Observe the finger without consuming it: buddies look at it (design §4.6).
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                e.changes.firstOrNull()?.let { LookTarget.x = it.position.x; LookTarget.y = it.position.y; LookTarget.atNanos = clock.fast.longValue; LookTarget.inputAtMs = app.buddyverse.core.nowMillis() }
                            }
                        }
                    },
                ) {
                    val paired by store.paired.collectAsState()
                    if (!paired) ConnectScreen() else Main(railFooter)
                    overlay()
                    ToastHost()
                    if (keysStrip) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(navBar).background(if (Theme.colors.dark) Theme.colors.ground else Theme.colors.text))
                }
                Signals(store, feedback, toasts)
            }
        }
    }
}

@Composable
private fun Signals(store: AppStore, fb: Feedback, toasts: Toasts) {
    val strings = S.current
    LaunchedEffect(store, strings) {
        store.signals.collect { s ->
            when (s) {
                is UiSignal.TurnDone -> if (s.long) { fb.sound(Sound.Bloop); fb.haptic(Haptic.Confirm) }
                is UiSignal.NeedsYou -> { fb.sound(Sound.BoopBoop); fb.haptic(Haptic.Confirm); delay(120); fb.haptic(Haptic.Tick) }
                is UiSignal.Failed -> { fb.sound(Sound.Bonk); fb.haptic(Haptic.Reject) }
                is UiSignal.Toast -> toasts.show(ToastData(s.text))
                is UiSignal.Problem -> toasts.show(ToastData(strings.oops(s.kind)))
            }
        }
    }
}

private val frozenClock = BuddyClock()

@Composable
private fun Main(railFooter: @Composable () -> Unit) {
    val nav = LocalNav.current
    BackHandler(nav.covered) { nav.pop() }
    // Back on another tab's root goes home to Bots first; only Back on Bots leaves the app.
    BackHandler(!nav.covered && nav.tab != Tab.Bots) { nav.select(Tab.Bots) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        nav.wide = wide
        // Games dock beside the screens when there is room for list + chat + games; otherwise they float as a card.
        val dock = wide && maxWidth >= 1100.dp
        if (wide) Wide(railFooter, dock, maxWidth) else Narrow(constraints.maxWidth.toFloat())
        LinkBanner(Modifier.align(Alignment.TopCenter).then(if (wide) Modifier.padding(start = 80.dp).widthIn(max = 720.dp) else Modifier))
        // Phone self-update chip: on Bots home only, and only while the PC is reachable (the link banner owns the top otherwise).
        val linkUp = LocalStore.current.link.collectAsState().value is LinkState.Ready
        UpdateBanner(Modifier.align(Alignment.TopCenter), visible = linkUp && !nav.covered && nav.tab == Tab.Bots)
        if (!dock) app.buddyverse.ui.play.GamesFloat()
    }
}

/** Phone: tabs with the floating tab bar; pushed screens slide over them. */
@Composable
private fun Narrow(w: Float) {
    val nav = LocalNav.current
    val cover = remember { mutableFloatStateOf(0f) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val coveredFully = nav.covered && cover.floatValue >= .999f
    // Tabs stay composed (scroll state kept) but stop ticking while a pushed screen covers them.
    CompositionLocalProvider(LocalBuddyClock provides if (coveredFully) frozenClock else LocalBuddyClock.current) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationX = (if (rtl) 1f else -1f) * .25f * w * cover.floatValue
            alpha = 1f - .1f * cover.floatValue
        }.then(if (coveredFully) Modifier.clearAndSetSemantics { } else Modifier)) { // hidden screens stay out of TalkBack
            // A tab's sheets draw above the floating tab bar while one is up.
            Box(Modifier.fillMaxSize().zIndex(if (SheetHost.open > 0) 1f else 0f)) { TabContent(nav.tab) }
            NavBarGround(Modifier.align(Alignment.BottomCenter))
            TabBar(Modifier.align(Alignment.BottomCenter))
        }
    }
    PushedStack(w, rtl, cover)
}

/** Wide window (design §2.2): nav rail, the tab as a 380dp list pane, pushed screens in the detail pane beside it. */
@Composable
private fun Wide(railFooter: @Composable () -> Unit, dock: Boolean, width: androidx.compose.ui.unit.Dp) {
    val nav = LocalNav.current
    val c = Theme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // The games panel takes the end edge; the list pane gives up a little so the chat keeps ~460dp.
    val docked = dock && nav.play
    val panelW = (width.value * .3f).coerceIn(340f, 440f).dp
    val listW = if (docked) (width - 80.dp - panelW - 460.dp).coerceIn(300.dp, 380.dp) else 380.dp
    Row(Modifier.fillMaxSize()) {
        NavRail(footer = railFooter)
        Box(Modifier.width(listW).fillMaxHeight()) { TabContent(nav.tab) }
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.hairline))
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            if (nav.stack.none { !it.leaving }) EmptyDetail()
            PushedStack(constraints.maxWidth.toFloat(), rtl, null)
        }
        if (docked) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.hairline))
            app.buddyverse.ui.play.GamesDocked(Modifier.width(panelW).fillMaxHeight())
        }
    }
}

@Composable
private fun TabContent(tab: Tab) = when (tab) {
    Tab.Bots -> BotsScreen()
    Tab.Chats -> ChatsScreen()
    Tab.Routines -> RoutinesScreen()
    Tab.More -> MoreScreen()
}

@Composable
private fun PushedStack(w: Float, rtl: Boolean, cover: MutableFloatState?) {
    val nav = LocalNav.current
    val top = nav.stack.lastOrNull { !it.leaving }
    nav.stack.forEachIndexed { i, e ->
        androidx.compose.runtime.key(e.id) {
            Pushed(e, w, rtl, if (i == 0) cover else null, hidden = top != null && e !== top && !e.leaving) {
                when (val s = e.screen) {
                    is Screen.Chat -> ChatScreen(s.target)
                    is Screen.GroupRoom -> RoomScreen(s.room)
                    is Screen.More -> MoreDetail(s.page)
                    is Screen.Project -> ProjectScreen(s.profile, s.projectId)
                }
            }
        }
    }
}

/** The detail pane before anything is open: the team idling, so the window is never a dead grey box. */
@Composable
private fun EmptyDetail() {
    val store = LocalStore.current
    val bots by store.bots.collectAsState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
            bots.take(5).forEachIndexed { i, b -> Buddy(b.spec, Mood.Idle, if (i % 2 == 0) 72.dp else 60.dp) }
        }
    }
}

/** Ground fading in just above the system bar, so tiles scrolling under the floating tab bar never run into the keys. */
@Composable
private fun NavBarGround(modifier: Modifier) {
    val ground = Theme.colors.ground
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    if (nav == 0.dp) return
    Box(modifier.fillMaxWidth().height(nav + 24.dp).background(Brush.verticalGradient(0f to ground.copy(alpha = 0f), 24f / (nav.value + 24f) to ground)))
}

/** A pushed screen slides in from the end edge on the `sheet` spring; the screen below drifts 25% toward start (design §5.1). */
@Composable
private fun Pushed(e: Entry, width: Float, rtl: Boolean, cover: MutableFloatState?, hidden: Boolean, content: @Composable () -> Unit) {
    val nav = LocalNav.current
    // A replace (close session → Main chat) swaps in place: no slide, so no frame of the tab below.
    val p = remember { Animatable(if (e.instant) 1f else 0f) }
    LaunchedEffect(e.leaving) {
        if (!e.leaving) p.animateTo(1f, Motion.sheet()) else { p.animateTo(0f, Motion.sheet()); nav.removed(e) }
    }
    if (cover != null) cover.floatValue = p.value
    Box(
        Modifier.fillMaxSize().graphicsLayer { translationX = (if (rtl) -1f else 1f) * width * (1f - p.value) }
            .shadow(if (p.value < 1f) 24.dp else 0.dp).background(Theme.colors.ground)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .then(if (hidden) Modifier.clearAndSetSemantics { } else Modifier),
    ) { content() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (cover != null && nav.stack.isEmpty()) cover.floatValue = 0f } }
}

/** "Can't reach your PC" / reconnecting strip with Retry and Change link (task scope 1). */
@Composable
private fun LinkBanner(modifier: Modifier) {
    val store = LocalStore.current
    val link by store.link.collectAsState()
    val s = S.current
    val c = Theme.colors
    // Only after a dial has actually failed (Down, or Connecting on a retry): a slow first connect is not "can't reach".
    val failing = link is LinkState.Down || (link as? LinkState.Connecting)?.attempt?.let { it > 0 } == true
    // Give a quick reconnect a moment before showing anything (no flashes under ~1.5 s).
    val since = remember(failing) { app.buddyverse.core.nowMillis() }
    var tick by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(failing) { while (failing) { delay(500); tick = app.buddyverse.core.nowMillis() } }
    val showDown = failing && (tick - since > 1500 || (link as? LinkState.Down)?.reason?.contains("ticket") == true)
    AnimatedVisibility(showDown, modifier.statusBarsPadding().padding(12.dp),
        enter = slideInVertically(Motion.snappy()) { -it } + fadeIn(Motion.fade()), exit = slideOutVertically(Motion.snappy()) { -it } + fadeOut(Motion.fade())) {
        Row(
            Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(c.surface).padding(start = 8.dp, end = 8.dp)
                .heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val bots by store.bots.collectAsState()
            val b = bots.firstOrNull()
            if (b != null) Buddy(b.spec, Mood.Offline, 44.dp, interactive = false)
            Spacer(Modifier.width(10.dp))
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Txt(s.pcOffTitle, Theme.type.headline, maxLines = 1)
                Txt(s.reconnecting, Theme.type.caption, c.secondary, maxLines = 1)
            }
            PillButton(s.retry, { store.gateway.retryNow() }, height = 48.dp, kind = ButtonKind.Quiet)
            Spacer(Modifier.width(4.dp))
            PillButton(s.changeLink, { store.signOut() }, height = 48.dp, kind = ButtonKind.Outline)
        }
    }
}

@Composable
private fun ToastHost() {
    val toasts = LocalToasts.current
    val t = toasts.current
    val c = Theme.colors
    LaunchedEffect(t?.id) { if (t != null) { delay(4_000); toasts.dismiss(t.id) } }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 92.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(t != null, enter = slideInVertically(Motion.bouncy()) { it } + fadeIn(Motion.fade()), exit = slideOutVertically(Motion.snappy()) { it } + fadeOut(Motion.fade())) {
            val last = remember { arrayOfNulls<ToastData>(1) }
            val shown = t ?: last[0] ?: return@AnimatedVisibility
            last[0] = shown
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 60.dp).shadow(16.dp, RoundedCornerShape(30.dp))
                    .clip(RoundedCornerShape(30.dp)).background(if (c.dark) c.surface2 else c.text).padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (shown.buddy != null) Buddy(shown.buddy, Mood.Idle, 40.dp, cue = shown.shot?.let { BuddyCue(shown.id, it) }, interactive = false)
                Spacer(Modifier.width(10.dp))
                Txt(shown.text, Theme.type.callout, if (c.dark) c.text else c.ground, Modifier.weight(1f), maxLines = 2)
                if (shown.action != null) PillButton(shown.action, { shown.onAction?.invoke(); toasts.dismiss(shown.id) }, height = 48.dp, kind = ButtonKind.Primary)
            }
        }
    }
}

