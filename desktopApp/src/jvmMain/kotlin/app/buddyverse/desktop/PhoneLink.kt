package app.buddyverse.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.buddyverse.buddy.Buddy
import app.buddyverse.buddy.Mood
import app.buddyverse.core.AppStore
import app.buddyverse.core.LinkState
import app.buddyverse.core.systemLanguage
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Haptic
import app.buddyverse.design.Icon
import app.buddyverse.design.Icons
import app.buddyverse.design.PillButton
import app.buddyverse.design.Sheet
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.design.squish
import app.buddyverse.i18n.HebrewStrings
import app.buddyverse.i18n.Lang
import app.buddyverse.i18n.S
import app.buddyverse.ui.LocalStore
import app.buddyverse.ui.LocalToasts
import app.buddyverse.ui.ToastData
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** The PC app's own words (tray, phone link, start-up). */
class DesktopStrings(
    val open: String, val phoneLink: String, val phone: String, val quit: String, val copy: String, val copied: String,
    val scanHint: String, val noLink: String, val starting: String, val startFailed: String,
    val web: String, val webHint: String, val openHere: String,
)

private val en = DesktopStrings(
    "Open", "Phone link", "Phone", "Quit", "Copy link", "Link copied",
    "Scan it with the Buddyverse app on your phone, then sign in.", "No phone link yet: the PC side is still starting.",
    "Waking up your PC…", "Couldn't start the PC side",
    "Web version", "Any phone or browser. On iPhone: open in Safari → Share → Add to Home Screen.", "Open on this PC",
)
private val he = DesktopStrings(
    "פתיחה", "קישור לטלפון", "טלפון", "יציאה", "העתקת הקישור", "הקישור הועתק",
    "סרקו עם אפליקציית Buddyverse בטלפון ואז התחברו.", "עדיין אין קישור: המחשב עוד מתעורר.",
    "מעירים את המחשב…", "לא הצלחנו להפעיל את המחשב",
    "גרסת ווב", "כל טלפון או דפדפן. באייפון: פתחו ב-Safari ← שיתוף ← הוספה למסך הבית.", "פתיחה במחשב",
)

/** Same rule as the app's own language pick (AppRoot). */
fun isHebrew(store: AppStore): Boolean = when (store.prefs.value.lang) {
    Lang.System -> systemLanguage() in setOf("he", "iw")
    Lang.Hebrew -> true
    Lang.English -> false
}

fun desktopStrings(store: AppStore): DesktopStrings = if (isHebrew(store)) he else en

private val D: DesktopStrings @Composable get() = if (S.current === HebrewStrings) he else en

/** Rail footer: opens the phone link sheet. */
@Composable
fun PhoneLinkButton(onClick: () -> Unit) {
    val c = Theme.colors
    Column(Modifier.width(72.dp).height(60.dp).squish(onClick, haptic = Haptic.Tick, label = D.phoneLink),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.devices, c.secondary, 26.dp)
        Txt(D.phone, Theme.type.micro, c.secondary, maxLines = 1)
    }
}

/** The current phone link with a QR code and Copy, so the phone can pair (design §3.10). */
@Composable
fun PhoneLinkSheet(open: Boolean, onDismiss: () -> Unit) {
    val c = Theme.colors
    val d = D
    val toasts = LocalToasts.current
    // The link changes when the tunnel restarts: re-read it while the sheet is up.
    val link by produceState<String?>(null, open) {
        while (open) { value = withContext(Dispatchers.IO) { PcSide.phoneLink() }; delay(5_000) }
    }
    Sheet(open, onDismiss, d.phoneLink) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val phone = link
            if (phone == null) {
                Txt(d.noLink, Theme.type.callout, c.secondary, Modifier.padding(vertical = 24.dp))
                return@Column
            }
            val l = phone
            Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White).padding(16.dp)) { Qr(l, Modifier.size(232.dp)) }
            Spacer(Modifier.height(16.dp))
            Txt(l, Theme.type.code, c.text, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            Txt(d.scanHint, Theme.type.caption, c.secondary, Modifier.widthIn(max = 420.dp), maxLines = 2)
            Spacer(Modifier.height(16.dp))
            PillButton(d.copy, {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(l), null)
                toasts.show(ToastData(d.copied))
            }, icon = Icons.contentCopy, haptic = Haptic.Confirm)
            // The web version (PWA) comes from this PC's own gateway: the buddyverse plugin's page, over the same tunnel.
            if (PcSide.webAppInstalled()) {
                val webLink = phone + PcSide.WEB_APP_PATH
                Spacer(Modifier.height(24.dp))
                Txt(d.web, Theme.type.headline, c.text, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Txt(webLink, Theme.type.code, c.text, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                Txt(d.webHint, Theme.type.caption, c.secondary, Modifier.widthIn(max = 420.dp), maxLines = 2)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(d.copy, {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(webLink), null)
                        toasts.show(ToastData(d.copied))
                    }, icon = Icons.contentCopy, haptic = Haptic.Confirm)
                    // On this PC: the local address, so it works even while the tunnel is down.
                    PillButton(d.openHere, {
                        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(PcSide.LOCAL + PcSide.WEB_APP_PATH)) }
                    }, kind = ButtonKind.Outline, haptic = Haptic.Tick)
                }
            }
        }
    }
}

@Composable
private fun Qr(text: String, modifier: Modifier) {
    val m = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0)) }
    Canvas(modifier) {
        // Whole-pixel modules: fractional ones leave hairline gaps that some scanners trip on.
        val cell = kotlin.math.floor(size.minDimension / m.width)
        val o = (size.minDimension - cell * m.width) / 2
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m[x, y]) drawRect(Color.Black, Offset(o + x * cell, o + y * cell), Size(cell, cell))
        }
    }
}

/** While the PC side starts (or failed to): the team asleep, with Retry on failure. Gone once the app is connected. */
@Composable
fun StartupOverlay(onRetry: () -> Unit) {
    val store = LocalStore.current
    val c = Theme.colors
    val d = D
    val state by PcSide.state.collectAsState()
    val link by store.link.collectAsState()
    val bots by store.bots.collectAsState()
    if (state is Startup.Ready || link is LinkState.Ready) return
    Box(Modifier.fillMaxSize().background(c.ground).pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                val specs = bots.map { it.spec }.ifEmpty { listOf(app.buddyverse.buddy.BuddySpec("pc", "", app.buddyverse.buddy.BuddyShape.Squircle, app.buddyverse.buddy.BuddyColors.palette[8])) }
                specs.take(4).forEachIndexed { i, s -> Buddy(s, if (state is Startup.Failed) Mood.Error else Mood.Sleeping, if (i % 2 == 0) 64.dp else 56.dp) }
            }
            Spacer(Modifier.height(24.dp))
            when (val s = state) {
                is Startup.Failed -> {
                    Txt(d.startFailed, Theme.type.title2)
                    Txt(s.detail, Theme.type.caption, c.secondary, Modifier.padding(top = 6.dp).widthIn(max = 420.dp), maxLines = 3)
                    Spacer(Modifier.height(20.dp))
                    PillButton(S.current.retry, onRetry, kind = ButtonKind.Primary)
                }
                else -> Txt(d.starting, Theme.type.title2)
            }
        }
    }
}
