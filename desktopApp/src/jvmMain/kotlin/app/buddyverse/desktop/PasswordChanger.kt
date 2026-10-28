package app.buddyverse.desktop

import app.buddyverse.core.AppStore
import app.buddyverse.core.AuthError
import app.buddyverse.core.DashboardPassword
import app.buddyverse.core.InstallPaths
import app.buddyverse.core.PasswordChange.Result
import app.buddyverse.core.WebappLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings ▸ Change password on the PC. The gateway hashes the Webapp password once, at start
 * (`plugins/dashboard_auth/basic/__init__.py` `register` → `_settings`), so: check the current one against the running
 * gateway, store only the scrypt hash in `<hermes>\.env` (the plaintext `_PASSWORD` line goes; `.env.bak` until the new one
 * works), restart the gateway (the user confirmed it), sign in with the new one, then update `webapp-login.txt` and this
 * app's own sign-in. If the new password doesn't work after the restart, the old `.env` goes back and the gateway restarts
 * again. Passwords are never logged.
 */
object PasswordChanger {
    private val busy = Mutex()

    suspend fun change(store: AppStore, current: String, new: String): Result {
        if (!busy.tryLock()) return Result.Failed("a change is already running")
        return try { doChange(store, current, new) } finally { busy.unlock() }
    }

    private suspend fun doChange(store: AppStore, current: String, new: String): Result {
        val p = InstallPaths.current
        val env = withContext(Dispatchers.IO) { p.hermesEnv.takeIf { it.isFile }?.readText() } ?: return Result.Failed("${p.hermesEnv} not found")
        val user = DashboardPassword.envValue(env, WebappLogin.USER_KEY) ?: return Result.Failed("no ${WebappLogin.USER_KEY} in .env")
        when (val e = login(user, current)) {
            null -> Unit
            is AuthError.BadCredentials -> return Result.WrongCurrent
            else -> return Result.Failed(e.message.orEmpty())
        }
        val bak = File(p.hermesEnv.path + ".bak")
        withContext(Dispatchers.IO) { DashboardPassword.writeAtomic(p.hermesEnv, DashboardPassword.envWithHash(env, DashboardPassword.hash(new)), bak) }
        val restartErr = PcSide.restartGateway()
        val err = restartErr ?: loginAfterStart(user, new)?.message
        if (err != null) {
            withContext(Dispatchers.IO) { DashboardPassword.writeAtomic(p.hermesEnv, bak.readText()); bak.delete() }
            // Restart again only if the gateway did restart with the new password; otherwise it still has the old one.
            if (restartErr == null) PcSide.restartGateway()
            store.gateway.retryNow()
            return Result.Failed(err)
        }
        withContext(Dispatchers.IO) {
            // Old installs kept a plaintext copy for the PC's own sign-in: keep it in step. New installs have none.
            if (p.webappLogin.isFile) DashboardPassword.writeAtomic(p.webappLogin, WebappLogin.loginFile(user, new))
            bak.delete() // it held the old plaintext
        }
        store.signIn(PcSide.LOCAL, user, new)
        return Result.Done
    }

    /** The provider registers during start-up plugin discovery, maybe just after /api/status answers: a few tries. */
    private suspend fun loginAfterStart(user: String, password: String): AuthError? {
        var last: AuthError? = null
        repeat(5) { i ->
            last = login(user, password) ?: return null
            delay(3_000L + i * 1_000L)
        }
        return last
    }

    private suspend fun login(user: String, password: String) = DashboardPassword.tryLogin(PcSide.LOCAL, user, password)
}
