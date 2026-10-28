package app.buddyverse

import app.buddyverse.core.HermesCanary
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HermesCanaryTest {
    private val a = "a".repeat(40)
    private val b = "b".repeat(40)
    private val c = "c".repeat(40)
    private val d = "d".repeat(40)

    @Test fun picksTheNewestCanaryWithTheWebapp() {
        val refs = "0087$a refs/tags/v0.22.5+canary.20261027T070237Z peeled:$b\n" +
            "0087$c refs/tags/v0.22.5+canary.20261028T064834Z peeled:$d\n" +
            "0087$a refs/tags/v0.22.4+canary.20261020T070000Z peeled:$a\n" +
            "003f$a refs/tags/v0.22.5\n0000"
        val n = HermesCanary.newest(refs)!!
        assertEquals("v0.22.5+canary.20261028T064834Z", n.tag)
        assertEquals(d, n.commit) // the peeled commit, not the tag object
    }

    @Test fun ignoresCanariesWithoutTheWebappAndUnpeeledTags() {
        assertNull(HermesCanary.newest("0087$a refs/tags/v0.22.4+canary.20261025T065651Z peeled:$b\n"))
        assertNull(HermesCanary.newest("0057$a refs/tags/v0.22.5+canary.20261028T064834Z\n"))
        assertNull(HermesCanary.newest(""))
    }

    @Test fun detectsAnInstallWithoutTheWebapp() {
        val home = Files.createTempDirectory("hc").toFile()
        try {
            assertFalse(HermesCanary.lacksWebapp(home)) // no checkout: unknown
            File(home, "hermes-agent\\gateway").mkdirs()
            assertTrue(HermesCanary.lacksWebapp(home))
            File(home, "hermes-agent\\gateway\\webapp_listener.py").writeText("")
            assertFalse(HermesCanary.lacksWebapp(home))
        } finally { home.deleteRecursively() }
    }
}
