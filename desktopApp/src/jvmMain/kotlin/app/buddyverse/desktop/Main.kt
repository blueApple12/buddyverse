package app.buddyverse.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import app.buddyverse.core.AppStore
import app.buddyverse.core.ChatTarget
import app.buddyverse.core.FileStore
import app.buddyverse.core.UiSignal
import app.buddyverse.design.DesktopBack
import app.buddyverse.design.Feedback
import app.buddyverse.design.Haptic
import app.buddyverse.design.Sound
import app.buddyverse.design.Synth
import app.buddyverse.i18n.APP_NAME
import app.buddyverse.i18n.EnglishStrings
import app.buddyverse.i18n.HebrewStrings
import app.buddyverse.ui.AgenticApp
import app.buddyverse.ui.Nav
import app.buddyverse.ui.Screen
import app.buddyverse.ui.Tab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.awt.Toolkit
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.imageio.ImageIO
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/** Another start of the app (Start menu, shortcut) only asks the running one to show its window. */
private object SingleInstance {
    private const val PORT = 47915 // its own port: never pokes another app that uses a single-instance socket
    private const val MARKER = "buddyverse\n"
    var onShow: () -> Unit = {}

    fun claim(): Boolean {
        val server = runCatching { ServerSocket(PORT, 8, InetAddress.getLoopbackAddress()) }.getOrNull()
        // Port taken: a running copy replies with MARKER and shows itself. No marker means something else holds the port: run anyway.
        if (server == null) return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 2_000); s.soTimeout = 2_000
                s.getOutputStream().apply { write('s'.code); flush() }
                String(s.getInputStream().readNBytes(MARKER.length), Charsets.US_ASCII) == MARKER
            }
        }.getOrDefault(false).not()
        Thread({ while (true) runCatching { server.accept().use { it.getOutputStream().write(MARKER.toByteArray(Charsets.US_ASCII)); SwingUtilities.invokeLater { onShow() } } } }, "single-instance").apply { isDaemon = true; start() }
        return true
    }
}

/** Sounds through javax.sound; a PC has no haptics (design §5.2, §6). */
private object DesktopFeedback : Feedback {
    private val wavs = Sound.entries.associateWith { Synth.wav(Synth.render(it)) }
    override fun haptic(h: Haptic) = Unit
    override fun sound(s: Sound) {
        val bytes = wavs[s] ?: return
        Thread {
            runCatching {
                val clip = AudioSystem.getClip()
                clip.addLineListener { if (it.type == LineEvent.Type.STOP) clip.close() }
                clip.open(AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes)))
                clip.start()
            }
        }.apply { isDaemon = true; start() }
    }
}

fun main(args: Array<String>) {
    // `--setup ...`: the first-run steps with no window (scripts, CI, AI agents). See HeadlessSetup.
    if (app.buddyverse.core.SetupArgs.wanted(args)) exitProcess(HeadlessSetup.run(args))
    if (!SingleInstance.claim()) return
    val store = AppStore(FileStore(), CoroutineScope(SupervisorJob() + Dispatchers.Main))
    app.buddyverse.core.PasswordChange.onPc = { current, new -> PasswordChanger.change(store, current, new) }
    // A clean PC first runs the setup (Setup.kt); it starts the PC side itself when it gets there.
    val firstRun = Setup.needed()
    if (!firstRun) PcSide.start(store)
    ApkFeed.syncInBackground()
    val icon = BitmapPainter(ImageIO.read(DesktopFeedback::class.java.getResourceAsStream("/icon.png")).toComposeImageBitmap())
    val screen = Toolkit.getDefaultToolkit().screenSize
    application(exitProcessOnExit = true) {
        var visible by remember { mutableStateOf(true) }
        var linkOpen by remember { mutableStateOf(false) }
        var setupOpen by remember { mutableStateOf(firstRun) }
        var raise by remember { mutableIntStateOf(0) }
        val nav = remember { Nav() }
        val tray = rememberTrayState()
        val scope = rememberCoroutineScope()
        val prefs by store.prefs.collectAsState()
        val d = remember(prefs.lang) { desktopStrings(store) }
        fun show() { visible = true; raise++ }
        SingleInstance.onShow = ::show

        Tray(icon, tray, tooltip = APP_NAME, onAction = ::show) {
            Item(d.open, onClick = ::show)
            Item(d.phoneLink) { show(); linkOpen = true }
            Separator()
            Item(d.quit) { store.gateway.stop(); exitProcess(0) }
        }

        val win = rememberWindowState(
            position = WindowPosition(Alignment.Center),
            size = androidx.compose.ui.unit.DpSize(minOf(1240, screen.width * 9 / 10).dp, minOf(820, screen.height * 9 / 10).dp),
        )
        LaunchedEffect(visible, win.isMinimized) { store.setForeground(visible && !win.isMinimized) }
        // Closing the window hides it to the tray; Quit is in the tray menu.
        Window(onCloseRequest = { visible = false }, state = win, visible = visible, title = APP_NAME, icon = icon,
            onPreviewKeyEvent = { shortcut(it, nav, store) }) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(420, 640) }
            LaunchedEffect(raise) { if (raise > 0) { win.isMinimized = false; window.toFront(); window.requestFocus() } }
            LaunchedEffect(Unit) { store.signals.collect { notify(it, store, tray, window.isActive) } }
            AgenticApp(
                store, DesktopFeedback, nav,
                railFooter = { PhoneLinkButton { linkOpen = true } },
                overlay = {
                    PhoneLinkSheet(linkOpen) { linkOpen = false }
                    StartupOverlay { scope.launch { PcSide.retry(store) } }
                    if (setupOpen) SetupOverlay(store) { connect ->
                        setupOpen = false
                        if (connect) { nav.select(Tab.More); nav.connectModel = true; nav.push(Screen.More(app.buddyverse.ui.MorePage.Models)) }
                    }
                },
            )
        }
    }
}

/** Window shortcuts (design §2.2): Esc = Back (closes the top sheet), Ctrl+K = command palette, Alt+1..4 = tabs. Enter sends in the composer. */
private fun shortcut(e: KeyEvent, nav: Nav, store: AppStore): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val tabs = listOf(Key.One, Key.Two, Key.Three, Key.Four)
    return when {
        e.key == Key.Escape -> DesktopBack.back()
        !store.paired.value -> false
        e.isCtrlPressed && e.key == Key.K -> {
            val top = (nav.stack.lastOrNull { !it.leaving }?.screen as? Screen.Chat)?.target
            val target = top ?: (store.bots.value.firstOrNull { it.isDefault } ?: store.bots.value.firstOrNull())
                ?.let { ChatTarget(it.profile) }?.also { nav.push(Screen.Chat(it)) } ?: return false
            nav.palette = target.key
            true
        }
        e.isAltPressed && e.key in tabs -> { nav.select(Tab.entries[tabs.indexOf(e.key)]); true }
        else -> false
    }
}

/** Desktop notifications while the window isn't in front: needs you, done, failed (design §3.11). Clicking one opens the app. */
private fun notify(s: UiSignal, store: AppStore, tray: TrayState, active: Boolean) {
    if (!store.prefs.value.notifications || active) return
    val t = if (isHebrew(store)) HebrewStrings else EnglishStrings
    fun name(p: String) = store.bots.value.firstOrNull { it.profile == p }?.name ?: p
    val n = when (s) {
        is UiSignal.NeedsYou -> Notification(t.notifyNeedsYou(name(s.profile)), t.notifyOpen, Notification.Type.Warning)
        is UiSignal.TurnDone -> Notification(t.notifyDone(name(s.profile)), s.text?.take(200)?.takeIf { it.isNotBlank() } ?: APP_NAME, Notification.Type.Info)
        is UiSignal.Failed -> Notification(t.notifyFailed(name(s.profile)), APP_NAME, Notification.Type.Error)
        else -> return
    }
    tray.sendNotification(n)
}
