package app.buddyverse.core

import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * The Hermes Agent build Buddyverse's beta installs: the newest **canary** build. Hermes' stable releases (up to v0.22.5)
 * have no Webapp (the gateway's own listener on 127.0.0.1:9120 that Buddyverse signs in to); canary builds from
 * 26 Oct 2026 on do.
 *
 * Hermes publishes each canary as a git tag on its main branch, `v<stable>+canary.<UTC stamp>`. Its install.ps1 takes
 * only `-Channel stable|main`, so Setup asks the repository for the newest canary tag (git's own ref listing over
 * HTTPS: no GitHub API rate limit, no git needed) and runs the official installer pinned to that commit (`-Commit`).
 */
object HermesCanary {
    const val REPO = "https://github.com/NousResearch/hermes-agent.git"
    /** Webapp support landed in the canary builds of this UTC day. */
    const val FIRST_WITH_WEBAPP = "20261026"

    data class Build(val tag: String, val stamp: String, val commit: String)

    private val LINE = Regex("""[0-9a-f]{40} refs/tags/(v\d+\.\d+\.\d+\+canary\.(\d{8}T\d{6}Z))(?: peeled:([0-9a-f]{40}))?""")

    /** The newest canary in a git ls-refs answer (protocol v2, `peel`): its tag, stamp and commit; null if none. */
    fun newest(lsRefs: String): Build? = LINE.findAll(lsRefs)
        .mapNotNull { m ->
            val commit = m.groupValues[3].ifEmpty { null } ?: return@mapNotNull null // an annotated tag peels to its commit
            Build(m.groupValues[1], m.groupValues[2], commit)
        }
        .filter { it.stamp >= FIRST_WITH_WEBAPP }
        .maxByOrNull { it.stamp }

    /** Lists the repository's `refs/tags/v*` (git smart HTTP, protocol v2) and returns the newest canary. */
    fun fetchNewest(): Build {
        fun pkt(s: String) = "%04x".format(s.toByteArray().size + 4) + s
        val body = pkt("command=ls-refs\n") + "0001" + pkt("peel\n") + pkt("ref-prefix refs/tags/v\n") + "0000"
        val c = (URI("$REPO/git-upload-pack").toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20_000; readTimeout = 60_000
            setRequestProperty("User-Agent", "git/2.45.0 (Buddyverse-Setup)")
            setRequestProperty("Git-Protocol", "version=2")
            setRequestProperty("Content-Type", "application/x-git-upload-pack-request")
            setRequestProperty("Accept", "application/x-git-upload-pack-result")
        }
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) error("github.com answered ${c.responseCode}")
            val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return newest(text) ?: error("no canary build from $FIRST_WITH_WEBAPP on was found")
        } finally { c.disconnect() }
    }

    /**
     * An existing Hermes source install without the Webapp: true only when its checkout is where the official installer
     * puts it and the listener file is missing. Anything else (another layout, a desktop-app install) is unknown: false.
     */
    fun lacksWebapp(hermesHome: File): Boolean {
        val checkout = File(hermesHome, "hermes-agent")
        return File(checkout, "gateway").isDirectory && !File(checkout, "gateway\\webapp_listener.py").isFile
    }

    /** The PowerShell line that moves an existing install to [b] with the official installer (keeps its data). */
    fun updateCommand(b: Build?): String =
        "& ([scriptblock]::Create((irm https://hermes-agent.nousresearch.com/install.ps1))) -Commit ${b?.commit ?: "<newest canary commit>"}"
}
