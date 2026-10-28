package app.buddyverse.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Same signing key as the installed app, so the system installer updates in place and the pairing stays. */
private class AndroidInstaller(private val ctx: Context) : AppInstaller {
    override val versionCode: Long = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    override fun canInstall(): Boolean = ctx.packageManager.canRequestPackageInstalls()

    // HyperOS / stock: the per-app "Install unknown apps" page with its "Allow from this source" switch.
    override fun openInstallSettings() {
        val pkg = Uri.parse("package:${ctx.packageName}")
        runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
}

@Composable
actual fun rememberAppInstaller(): AppInstaller? {
    val ctx = LocalContext.current.applicationContext
    return remember(ctx) { AndroidInstaller(ctx) }
}
