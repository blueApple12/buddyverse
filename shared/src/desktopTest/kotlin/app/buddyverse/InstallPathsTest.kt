package app.buddyverse

import app.buddyverse.core.AppRelease
import app.buddyverse.core.AppUpdate
import app.buddyverse.core.InstallPaths
import app.buddyverse.core.ReleaseFeed
import app.buddyverse.core.WebappLogin
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallPathsTest {
    @Test fun everythingDerivesFromTheUsersOwnHome() {
        val p = InstallPaths.of("C:\\Users\\friend", "C:\\Users\\friend\\AppData\\Local")
        assertEquals(File("C:\\Users\\friend\\buddyverse"), p.root)
        assertEquals(File("C:\\Users\\friend\\buddyverse\\bin\\start-hermes.ps1"), p.startScript)
        assertEquals(File("C:\\Users\\friend\\buddyverse\\bin\\cloudflared.exe"), p.cloudflared)
        assertEquals(File("C:\\Users\\friend\\buddyverse\\bin\\phone-link.txt"), p.phoneLinkFile)
        assertEquals(File("C:\\Users\\friend\\buddyverse\\brain"), p.brain)
        assertEquals(File("C:\\Users\\friend\\buddyverse\\Buddyverse.apk"), p.apk)
        assertEquals(File("C:\\Users\\friend\\AppData\\Local\\hermes\\bin\\hermes.exe"), p.hermesExe)
        assertEquals(File("C:\\Users\\friend\\AppData\\Local\\hermes\\plugins\\buddyverse\\dashboard\\index.html"), p.webAppIndex)
        assertEquals(File("C:\\Users\\friend\\AppData\\Local\\hermes\\webapp-login.txt"), p.webappLogin)
    }

    @Test fun missingLocalAppDataFallsBackUnderHome() {
        assertEquals(File("C:\\Users\\x\\AppData\\Local\\hermes"), InstallPaths.of("C:\\Users\\x", null).hermesHome)
        assertEquals(File("C:\\Users\\x\\AppData\\Local\\hermes"), InstallPaths.of("C:\\Users\\x", " ").hermesHome)
    }

    @Test fun phoneUpdaterPathsAreHomeRelative() {
        // Hermes expands `~` to the gateway user's home: the same files on every install, no user name baked in.
        assertEquals("~/buddyverse/Buddyverse-version.json", AppUpdate.VERSION_JSON)
        assertEquals("~/buddyverse/Buddyverse.apk", AppUpdate.APK_PATH)
    }

    @Test fun buildsAppOnlyWithTheSourceTree() {
        val home = kotlin.io.path.createTempDirectory("aos").toFile()
        try {
            val p = InstallPaths(home, home)
            assertFalse(p.buildsApp)
            File(p.root, "app\\androidApp").mkdirs()
            assertTrue(p.buildsApp)
        } finally { home.deleteRecursively() }
    }

    @Test fun releaseFeedDownloadsOnlyNewer() {
        val sha = "a".repeat(64)
        val remote = AppRelease(7, "0.7.0", sha)
        assertEquals("https://github.com/blueApple12/buddyverse/releases/download/v0.1.0-beta/Buddyverse.apk", ReleaseFeed.url(ReleaseFeed.APK))
        assertTrue(ReleaseFeed.shouldDownload(remote, null))
        assertTrue(ReleaseFeed.shouldDownload(remote, "garbage"))
        assertTrue(ReleaseFeed.shouldDownload(remote, """{"versionCode":6,"sha256":"$sha"}"""))
        assertFalse(ReleaseFeed.shouldDownload(remote, """{"versionCode":7,"sha256":"$sha"}"""))
        assertFalse(ReleaseFeed.shouldDownload(null, null))
    }

    @Test fun webappLoginEnv() {
        assertFalse(WebappLogin.hasKey("", WebappLogin.USER_KEY))
        assertFalse(WebappLogin.hasKey("# ${WebappLogin.USER_KEY}=me\n${WebappLogin.USER_KEY}=\n", WebappLogin.USER_KEY))
        assertTrue(WebappLogin.hasKey("A=1\r\n ${WebappLogin.USER_KEY} = \"me\"\r\n", WebappLogin.USER_KEY))
        val env = WebappLogin.withLogin("A=1", "me", "pw", "sec")
        assertEquals("A=1\n${WebappLogin.USER_KEY}=me\n${WebappLogin.PASS_KEY}=pw\n${WebappLogin.SECRET_KEY}=sec\n", env)
        assertTrue(WebappLogin.hasKey(env, WebappLogin.PASS_KEY))
        assertEquals("username: me\npassword: pw\n", WebappLogin.loginFile("me", "pw"))
        val t = WebappLogin.randomToken(24)
        assertEquals(24, t.length)
        assertTrue(t.all { it.isLetterOrDigit() })
    }
}
