package app.buddyverse

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import app.buddyverse.core.AppStore
import app.buddyverse.core.LinkState
import app.buddyverse.core.LiveWatch
import app.buddyverse.i18n.settingsPageStrings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the app's one gateway socket open with the app closed or swiped away, so needs-you / done / failed always reach
 * [Notifier] (design §3.11). A quiet ongoing notification; [LiveWatch] attaches every live session; a wake lock only while
 * the link redials (a dial finishes with the screen off, bounded so a PC that's off doesn't drain the phone); a ~10 min
 * alarm wakes the phone to check the link. Runs while signed in with notifications and the Settings switch on.
 */
class ListenService : Service() {
    private lateinit var store: AppStore
    private lateinit var watch: LiveWatch
    private var job: Job? = null
    private val wake by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "buddyverse:link").apply { setReferenceCounted(false) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = App.store(applicationContext)
        watch = LiveWatch(store)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground first, always (a started-foreground service that stops before it would crash the app), then decide.
        if (job == null && (!goForeground() || !wanted(store))) { stopSelf(); return START_NOT_STICKY }
        if (job == null) {
            watch.start()
            job = store.scope.launch {
                launch { wants(store).collect { if (!it) stopSelf() } }
                store.gateway.state.collect { s ->
                    when {
                        s is LinkState.Connecting && s.attempt < 6 -> wake.acquire(60_000)
                        s is LinkState.Down -> Unit // the backoff wait keeps the dial's lock
                        s is LinkState.Ready -> wake.acquire(8_000) // a few seconds to re-attach the chats and poll the roster
                        else -> wake.release() // stopped, or a long outage: the alarm retries
                    }
                }
            }
        }
        if (intent?.action == TICK) {
            // Long dark sleep: give the heartbeat a few seconds to find a dead link (it then redials under the lock above).
            wake.acquire(15_000)
            store.gateway.start(); store.gateway.retryNow()
        }
        tick(this, true)
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel(); job = null
        watch.stop()
        tick(this, false)
        wake.release()
        super.onDestroy()
    }

    private fun goForeground(): Boolean = runCatching {
        val t = settingsPageStrings(Notifier.hebrew(store))
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, t.listenChannel, NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buddy)
            .setContentTitle(t.listening)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, n)
    }.isSuccess

    companion object {
        const val ID = 0x4c15
        private const val CHANNEL = "listening"
        private const val TICK = "app.buddyverse.TICK"

        private fun wanted(s: AppStore) = s.paired.value && s.prefs.value.notifications && s.prefs.value.listen
        private fun wants(s: AppStore) = combine(s.paired, s.prefs) { _, _ -> wanted(s) }.distinctUntilChanged()

        /** Start it if it should run. Allowed from the app on screen, boot, an app update or with the battery exemption; else a no-op. */
        fun sync(c: Context) {
            if (wanted(App.store(c))) runCatching { c.startForegroundService(Intent(c, ListenService::class.java)) }
        }

        /** Keeps [sync] in step with sign-in and the switches while the process lives (the service stops itself). */
        fun follow(c: Context, s: AppStore) { s.scope.launch { wants(s).collect { if (it) sync(c) } } }

        /** The ~10 min wake-up (inexact; Doze stretches it). ponytail: inexact alarm, no exact-alarm permission; tighten if alerts lag in deep sleep. */
        private fun tick(c: Context, on: Boolean) {
            val am = c.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getService(c, 1, Intent(c, ListenService::class.java).setAction(TICK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            if (on) am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 10 * 60_000L, pi) else am.cancel(pi)
        }
    }
}

/** Back after a reboot or an app update (both may start a foreground service from the background). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) ListenService.sync(context.applicationContext)
    }
}
