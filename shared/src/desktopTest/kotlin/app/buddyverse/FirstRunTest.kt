package app.buddyverse

import app.buddyverse.core.AccountRules
import app.buddyverse.core.AccountRules.Problem
import app.buddyverse.core.AccountSetup
import app.buddyverse.core.DashboardPassword
import app.buddyverse.core.InstallPaths
import app.buddyverse.core.SetupArgs
import app.buddyverse.core.WebappLogin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirstRunTest {
    @Test fun passwordRules() {
        assertNull(AccountRules.check("dana", "correct horse", "correct horse"))
        assertEquals(Problem.UserEmpty, AccountRules.check("", "12345678", "12345678"))
        assertEquals(Problem.UserChars, AccountRules.check("da na", "12345678", "12345678"))
        assertEquals(Problem.UserChars, AccountRules.check("dana#x", "12345678", "12345678"))
        assertEquals(Problem.UserLong, AccountRules.check("a".repeat(65), "12345678", "12345678"))
        assertEquals(Problem.PassShort, AccountRules.check("dana", "1234567", "1234567"))
        assertEquals(Problem.PassChars, AccountRules.check("dana", " 12345678", " 12345678"))
        assertEquals(Problem.PassChars, AccountRules.check("dana", "1234\n5678", "1234\n5678"))
        assertEquals(Problem.Mismatch, AccountRules.check("dana", "12345678", "12345679"))
        assertNull(AccountRules.check("dana.k@x-1_y", "12345678", null)) // headless: no confirm field
    }

    @Test fun argParsing() {
        val a = SetupArgs.parse(listOf("--setup", "--username", "dana", "--password-file", "C:\\p.txt", "--skip-phone"), emptyMap()).getOrThrow()
        assertEquals(SetupArgs("dana", "C:\\p.txt", null, true), a)
        val b = SetupArgs.parse(listOf("--setup", "--username=dana", "--password-file=-"), emptyMap()).getOrThrow()
        assertEquals("-", b.passwordFile)
        assertFalse(b.skipPhone)
        val env = mapOf(SetupArgs.ENV_USER to "env-user", SetupArgs.ENV_PASSWORD to "s3cret-pass", SetupArgs.ENV_SKIP_PHONE to "true")
        val c = SetupArgs.parse(listOf("--setup"), env).getOrThrow()
        assertEquals(SetupArgs("env-user", null, "s3cret-pass", true), c)
        assertEquals("flag", SetupArgs.parse(listOf("--setup", "--username", "flag"), env).getOrThrow().username) // flags win
        assertTrue(SetupArgs.parse(listOf("--setup", "--username"), emptyMap()).isFailure)
        assertTrue(SetupArgs.parse(listOf("--setup", "--username", "--skip-phone"), emptyMap()).isFailure)
        assertTrue(SetupArgs.parse(listOf("--setup", "--bogus"), emptyMap()).isFailure)
        assertTrue(SetupArgs.parse(listOf("--setup", "--password", "x"), emptyMap()).isFailure)
        assertEquals("pw", SetupArgs.firstLine("\uFEFFpw\r\nsecond\r\n"))
        assertTrue(SetupArgs.wanted(arrayOf("--setup")))
        assertFalse(SetupArgs.wanted(arrayOf()))
    }

    @Test fun newLoginStoresHashOnly() {
        val hash = DashboardPassword.hash("my password 1", ByteArray(16))
        val env = AccountSetup.envWithNewLogin("OPENROUTER_API_KEY=x\r\n${WebappLogin.PASS_KEY}=old", "dana", hash, "SECRET")
        assertEquals("dana", DashboardPassword.envValue(env, WebappLogin.USER_KEY))
        assertEquals("SECRET", DashboardPassword.envValue(env, WebappLogin.SECRET_KEY))
        assertNull(DashboardPassword.envValue(env, WebappLogin.PASS_KEY))
        assertTrue(DashboardPassword.verify("my password 1", DashboardPassword.envValue(env, DashboardPassword.HASH_KEY)!!))
        assertFalse("my password 1" in env)
        assertTrue(env.startsWith("OPENROUTER_API_KEY=x\r\n"))
        assertTrue(AccountSetup.matchesExisting(env, "my password 1"))
        assertFalse(AccountSetup.matchesExisting(env, "wrong password"))
    }

    @Test fun installOverridesForASecondInstall() {
        val d = InstallPaths.of("C:\\Users\\friend", "C:\\Users\\friend\\AppData\\Local")
        assertEquals(9120, d.port)
        assertEquals("http://127.0.0.1:9120", d.local)
        val env = mapOf("BUDDYVERSE_HOME" to "D:\\t\\bv", "BUDDYVERSE_HERMES_HOME" to "D:\\t\\hermes", "BUDDYVERSE_PORT" to "9135")
        val o = InstallPaths.of("C:\\Users\\friend", "C:\\Users\\friend\\AppData\\Local", env)
        assertEquals(java.io.File("D:\\t\\bv\\bin\\start-hermes.ps1"), o.startScript)
        assertEquals(java.io.File("D:\\t\\hermes\\bin\\hermes.exe"), o.hermesExe)
        assertEquals("http://127.0.0.1:9135", o.local)
        assertEquals(9120, InstallPaths.of("C:\\Users\\x", null, mapOf("BUDDYVERSE_PORT" to "80")).port) // out of range: default
        val child = mutableMapOf("HERMES_HOME" to "C:\\elsewhere\\profiles\\bot", "HERMES_SESSION_ID" to "s1", "PATH" to "p")
        o.childEnv(child)
        assertEquals("D:\\t\\hermes", child["HERMES_HOME"])
        assertNull(child["HERMES_SESSION_ID"])
        assertEquals("9135", child["BUDDYVERSE_PORT"])
        assertEquals("p", child["PATH"])
    }

    @Test fun existingLoginIsFound() {
        assertEquals("me", AccountSetup.existingUser("${WebappLogin.USER_KEY}=me\n", null))
        assertNull(AccountSetup.existingUser("# ${WebappLogin.USER_KEY}=me\n", "model:\n  default: x\n"))
        val yaml = "model:\n  default: x\ndashboard:\n  theme: dark\n  basic_auth:\n    username: \"ann\"\n    password_hash: abc\nother: 1\n"
        assertEquals("ann", AccountSetup.existingUser("", yaml))
        assertNull(AccountSetup.existingUser("", "dashboard:\n  basic_auth: {}\nusername: nope\n"))
        assertTrue(AccountSetup.matchesExisting("${WebappLogin.PASS_KEY}=plain-pass\n", "plain-pass"))
        assertFalse(AccountSetup.matchesExisting("${WebappLogin.PASS_KEY}=plain-pass\n", "other-pass"))
    }
}
