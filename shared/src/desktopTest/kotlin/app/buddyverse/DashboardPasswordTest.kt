package app.buddyverse

import app.buddyverse.core.DashboardPassword
import app.buddyverse.core.PasswordChange
import app.buddyverse.core.Scrypt
import app.buddyverse.core.WebappLogin
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardPasswordTest {
    private val salt = ByteArray(16) { it.toByte() }

    @Test fun scryptMatchesRfc7914() {
        val dk = Scrypt.derive("password".encodeToByteArray(), "NaCl".encodeToByteArray(), 1024, 8, 16, 64)
        assertEquals("fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b3731622eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640",
            dk.joinToString("") { "%02x".format(it) })
    }

    // Expected strings: Hermes' own params and encoding (hashlib.scrypt, base64), checked with the plugin's _verify_password.
    @Test fun hashIsThePluginFormat() {
        assertEquals("scrypt\$16384\$8\$1\$AAECAwQFBgcICQoLDA0ODw==\$qWPg2Rm8h0sbUXwFxbg4WOYkKtU0yqyQiGUDXrOcV2s=", DashboardPassword.hash("Test-pass-1234!", salt))
        assertEquals("scrypt\$16384\$8\$1\$AAECAwQFBgcICQoLDA0ODw==\$Se+K5kkwqgvVeyCPdaBH6zli2MANeawbBfDXdMHc7AA=", DashboardPassword.hash("סיסמה-ש1", salt))
    }

    @Test fun verifyRoundTrips() {
        val h = DashboardPassword.hash("Test-pass-1234!")
        assertTrue(DashboardPassword.verify("Test-pass-1234!", h))
        assertFalse(DashboardPassword.verify("Test-pass-1234", h))
        assertFalse(DashboardPassword.verify("x", "plain"))
    }

    @Test fun envKeepsEverythingButThePlaintext() {
        val env = "A=1\r\n# HERMES_DASHBOARD_BASIC_AUTH_PASSWORD=old comment\r\n${WebappLogin.USER_KEY}=avi\r\n" +
            "${WebappLogin.PASS_KEY}=secret\r\n${DashboardPassword.HASH_KEY}='scrypt\$old'\r\n${WebappLogin.SECRET_KEY}=s3\r\n"
        val out = DashboardPassword.envWithHash(env, "scrypt\$16384\$8\$1\$a\$b")
        assertEquals("A=1\r\n# HERMES_DASHBOARD_BASIC_AUTH_PASSWORD=old comment\r\n${WebappLogin.USER_KEY}=avi\r\n" +
            "${DashboardPassword.HASH_KEY}='scrypt\$16384\$8\$1\$a\$b'\r\n${WebappLogin.SECRET_KEY}=s3\r\n", out)
        assertNull(DashboardPassword.envValue(out, WebappLogin.PASS_KEY))
        assertEquals("avi", DashboardPassword.envValue(out, WebappLogin.USER_KEY))
        assertEquals("scrypt\$16384\$8\$1\$a\$b", DashboardPassword.envValue(out, DashboardPassword.HASH_KEY))
        assertEquals("X=1\n${DashboardPassword.HASH_KEY}='scrypt\$h'\n", DashboardPassword.envWithHash("X=1", "scrypt\$h"))
        assertEquals("${DashboardPassword.HASH_KEY}='scrypt\$h'\n", DashboardPassword.envWithHash("export ${WebappLogin.PASS_KEY} = p\n", "scrypt\$h"))
    }

    @Test fun formRules() {
        assertEquals(PasswordChange.Problem.TooShort, PasswordChange.problem("old", "short", "short"))
        assertEquals(PasswordChange.Problem.Spaces, PasswordChange.problem("old", " long-enough-pw", " long-enough-pw"))
        assertEquals(PasswordChange.Problem.Mismatch, PasswordChange.problem("old", "long-enough-pw", "long-enough-pX"))
        assertEquals(PasswordChange.Problem.SameAsOld, PasswordChange.problem("long-enough-pw", "long-enough-pw", "long-enough-pw"))
        assertNull(PasswordChange.problem("old", "long-enough-pw", "long-enough-pw"))
        assertEquals(0, PasswordChange.strength("Ab1!"))
        assertEquals(1, PasswordChange.strength("abcdefghij"))
        assertEquals(4, PasswordChange.strength("Correct-Horse-9-Battery"))
        assertEquals("O 0 l", PasswordChange.lookAlikes("xO0lO"))
        assertEquals("", PasswordChange.lookAlikes("abc"))
    }

    /** Leaves a throwaway HERMES_HOME (test password only) for tools/check-dashboard-hash.py to load through Hermes itself. */
    @Test fun writesAtomicallyWithBackup() {
        val dir = File("build/test-dashboard-auth").apply { deleteRecursively(); mkdirs() }
        val env = File(dir, ".env").apply { writeText("${WebappLogin.USER_KEY}=tester\n${WebappLogin.PASS_KEY}=old-pass\n${WebappLogin.SECRET_KEY}=0123456789abcdef0123456789abcdef\n") }
        val bak = File(dir, ".env.bak")
        DashboardPassword.writeAtomic(env, DashboardPassword.envWithHash(env.readText(), DashboardPassword.hash("Test-pass-1234!")), bak)
        assertTrue(bak.readText().contains("old-pass"))
        assertFalse(env.readText().contains("old-pass"))
        assertFalse(File(dir, ".env.tmp").exists())
    }
}
