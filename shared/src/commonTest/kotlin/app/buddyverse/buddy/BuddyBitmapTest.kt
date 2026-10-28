package app.buddyverse.buddy

import kotlin.test.Test
import kotlin.test.assertTrue

/** The drawing itself is checked on the phone (shared desktopTest has no Skia runtime). */
class BuddyBitmapTest {
    /** The notification face matches the state: wide round eyes, happy, or sad and greyed. */
    @Test fun faceMatchesTheState() {
        val needs = stillPose(Mood.NeedsYou)
        assertTrue(needs.eyeRound == 1f && needs.eyeScale > 1.3f)
        val done = stillPose(Mood.Idle)
        assertTrue(done.smile > 0f && done.cheeks == 1f && done.desaturate == 0f)
        val failed = stillPose(Mood.Error)
        assertTrue(failed.smile < 0f && failed.desaturate > 0f)
    }
}
