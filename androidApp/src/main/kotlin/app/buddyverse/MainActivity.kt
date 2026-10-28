package app.buddyverse

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import app.buddyverse.core.AndroidStore
import app.buddyverse.core.AppStore
import app.buddyverse.design.AndroidFeedback
import app.buddyverse.ui.AgenticApp
import app.buddyverse.ui.Nav
import app.buddyverse.ui.Screen
import app.buddyverse.ui.more.settings.DEVICE_PREFS
import app.buddyverse.ui.more.settings.batteryAsk
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) App.store(applicationContext).let { it.setPrefs(it.prefs.value.copy(notifications = false)) } else askBattery()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        App.view = WeakReference(window.decorView)
        val c = applicationContext
        val store = App.store(c)
        if (savedInstanceState == null) openFrom(intent)
        setContent {
            val nav = remember { Nav() }
            // A tapped notification: that bot's chat on top (once, not again if it is already the top screen).
            LaunchedEffect(nav) {
                store.pendingOpen.filterNotNull().collect { t ->
                    store.pendingOpen.value = null
                    if ((nav.stack.lastOrNull { !it.leaving }?.screen as? Screen.Chat)?.target?.key != t.key) nav.push(Screen.Chat(t))
                }
            }
            AgenticApp(store, App.feedback(c), nav)
        }
        // Once paired with the switch on: the notification grant (Android 13+), then the battery exemption, each asked once.
        lifecycleScope.launch {
            var asked = false
            combine(store.paired, store.prefs) { p, prefs -> p && prefs.notifications }.collect { want ->
                if (want && !asked) {
                    asked = true
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else askBattery()
                }
                if (!want) asked = false
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); openFrom(intent) }

    /** A tapped notification's chat (not again when the same intent comes back from Recents). */
    private fun openFrom(i: Intent?) {
        if (i == null || i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val store = App.store(applicationContext)
        Notifier.targetOf(store, i)?.let { store.pendingOpen.value = it }
    }

    /** Android's "let it run in the background?" dialog, once per install, while listening is on and not yet exempt. */
    private fun askBattery() {
        val device = getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)
        if (!App.store(applicationContext).prefs.value.listen || device.getBoolean(K_ASKED_BATTERY, false)) return
        if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) return
        device.edit().putBoolean(K_ASKED_BATTERY, true).apply()
        runCatching { startActivity(batteryAsk(this)) }
    }

    override fun onStart() {
        super.onStart()
        App.store(applicationContext).setForeground(true)
        ListenService.sync(applicationContext) // back on screen: restart it if the phone killed it
    }
    override fun onStop() { super.onStop(); App.store(applicationContext).setForeground(false) }

    private companion object { const val K_ASKED_BATTERY = "asked-battery" }
}

/** One store (socket, open chats), one notifier and one sound pool per process: they outlive rotation and the activity. */
internal object App {
    var view = WeakReference<View>(null)
    private var store: AppStore? = null
    private var feedback: AndroidFeedback? = null
    /** Starts off-screen: a process woken by boot, the service or a notification has no activity (onStart says otherwise). */
    fun store(c: Context) = store ?: AppStore(AndroidStore(c), MainScope()).also {
        it.setForeground(false); store = it; Notifier(c, it); ListenService.follow(c, it)
    }
    fun feedback(c: Context) = feedback ?: AndroidFeedback(c) { view.get() }.also { feedback = it }
}
