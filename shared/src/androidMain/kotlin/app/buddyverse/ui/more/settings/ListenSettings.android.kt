package app.buddyverse.ui.more.settings

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.buddyverse.design.ButtonKind
import app.buddyverse.design.Card
import app.buddyverse.design.Icon
import app.buddyverse.design.Icons
import app.buddyverse.design.PillButton
import app.buddyverse.design.Theme
import app.buddyverse.design.Txt
import app.buddyverse.i18n.S
import app.buddyverse.i18n.settingsPage
import app.buddyverse.ui.LocalStore

/**
 * The switch for the phone's listening service, then a one-time guide card: the battery exemption (Android's own ask), and
 * on Xiaomi phones (HyperOS / MIUI kill background apps) Autostart and Battery saver "No restrictions", each opened by its
 * deep link where the phone has one, else the app's info page.
 */
@Composable
internal actual fun ListenSettings() {
    val store = LocalStore.current
    val prefs by store.prefs.collectAsState()
    val t = S.settingsPage
    val s = S.current
    val ctx = LocalContext.current
    val device = remember(ctx) { ctx.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE) }
    // Re-read the exemption whenever the screen resumes (the user comes back from the system page).
    val life by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val exempt = remember(life) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    val xiaomi = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
    var dismissed by remember { mutableStateOf(device.getBoolean(K_GUIDE_DONE, false)) }
    Column {
        ToggleRow(Icons.cable, t.listen, prefs.listen, t.listenHint) { on -> store.setPrefs(prefs.copy(listen = on)) }
        if (!prefs.listen || dismissed || !(xiaomi || !exempt)) return@Column
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Txt(t.guideTitle, Theme.type.headline)
            if (xiaomi) Txt(t.guideHyper, Theme.type.sub, Theme.colors.secondary, Modifier.padding(top = 4.dp, bottom = 4.dp))
            Step(t.guideBattery, exempt, s.allow) { open(ctx, batteryAsk(ctx), Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            if (xiaomi) {
                Step(t.guideAutostart, false, s.open) {
                    open(ctx, Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")), appInfo(ctx))
                }
                Step(t.guideSaver, false, s.open) {
                    open(ctx, Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                        .putExtra("package_name", ctx.packageName).putExtra("package_label", ctx.applicationInfo.loadLabel(ctx.packageManager).toString()), appInfo(ctx))
                }
            }
            PillButton(t.guideDone, { device.edit().putBoolean(K_GUIDE_DONE, true).apply(); dismissed = true },
                Modifier.padding(top = 12.dp), kind = ButtonKind.Quiet, height = 48.dp, icon = Icons.check)
        }
    }
}

@Composable
private fun Step(label: String, done: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Theme.type.body, modifier = Modifier.weight(1f), maxLines = 2)
        if (done) Icon(Icons.checkCircle, Theme.colors.text, 24.dp) else PillButton(action, onClick, kind = ButtonKind.Outline, height = 48.dp)
    }
}

/** The first intent this phone can open wins (vendor pages move between OS versions). */
private fun open(ctx: Context, vararg intents: Intent) { for (i in intents) if (runCatching { ctx.startActivity(i) }.isSuccess) return }

private fun appInfo(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

/** Android's own "let this app run in the background?" dialog (needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in the manifest). */
@SuppressLint("BatteryLife")
fun batteryAsk(ctx: Context) = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))

/** Small per-phone flags (asked once, guide dismissed), kept apart from the synced app prefs. */
const val DEVICE_PREFS = "buddyverse-device"
private const val K_GUIDE_DONE = "guide-done"
