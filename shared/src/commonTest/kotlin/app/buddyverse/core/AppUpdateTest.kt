package app.buddyverse.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdateTest {
    private val sha = "531c42517e01305bacf76cc98b3414d81188a12696cb11c28ec244ca4cfa61cf"

    @Test fun parsesPublishedJson() {
        val r = AppUpdate.parse("""{"versionCode":6,"versionName":"0.6.0","sha256":"${sha.uppercase()}","size":7249414,"publishedAt":"2026-10-27T09:17:47Z"}""")
        assertEquals(AppRelease(6, "0.6.0", sha, 7249414), r)
    }

    @Test fun rejectsJunk() {
        assertNull(AppUpdate.parse("not json"))
        assertNull(AppUpdate.parse("""{"versionName":"0.6.0","sha256":"$sha"}"""))
        assertNull(AppUpdate.parse("""{"versionCode":6,"sha256":"abc"}"""))
        assertNull(AppUpdate.parse("""{"versionCode":6,"sha256":"${"z".repeat(64)}"}"""))
        assertEquals("6", AppUpdate.parse("""{"versionCode":6,"sha256":"$sha"}""")?.versionName)
    }

    @Test fun newerOnlyWhenCodeIsHigher() {
        val r = AppRelease(6, "0.6.0", sha)
        assertTrue(AppUpdate.isNewer(r, 5))
        assertFalse(AppUpdate.isNewer(r, 6))
        assertFalse(AppUpdate.isNewer(r, 7))
        assertFalse(AppUpdate.isNewer(null, 1))
    }

    @Test fun streamedHashMatchesOneShot() {
        val data = ByteArray(200_000) { (it * 31).toByte() }
        val s = sha256Stream()
        var i = 0
        while (i < data.size) { val n = minOf(65_536, data.size - i); s.update(data.copyOfRange(i, i + n), n); i += n }
        assertEquals(AppUpdate.hex(sha256(data)), AppUpdate.hex(s.digest()))
        // NIST "abc" vector, lowercase hex.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AppUpdate.hex(sha256("abc".encodeToByteArray())))
    }
}
