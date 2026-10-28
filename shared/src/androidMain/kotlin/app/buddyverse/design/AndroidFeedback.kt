package app.buddyverse.design

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import java.io.File

/** Haptics through the window's View, sounds through a preloaded SoundPool (design §5.2, §6). */
class AndroidFeedback(private val context: Context, private val view: () -> View?) : Feedback {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val pool = SoundPool.Builder().setMaxStreams(2)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private val ids: Map<Sound, Int> = Sound.entries.associateWith { s ->
        val f = File(context.cacheDir, "ui-${s.name.lowercase()}.wav")
        if (!f.isFile) f.writeBytes(Synth.wav(Synth.render(s)))
        pool.load(f.path, 1)
    }

    override fun haptic(h: Haptic) {
        val v = view() ?: return
        val c = when (h) {
            Haptic.Press -> HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.Tick -> HapticFeedbackConstants.CLOCK_TICK
            Haptic.LongPress -> HapticFeedbackConstants.LONG_PRESS
            Haptic.Confirm -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.Reject -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        }
        v.performHapticFeedback(c)
    }

    override fun sound(s: Sound) {
        // Silent when the ringer is silent or vibrate (design §6).
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        ids[s]?.let { pool.play(it, 1f, 1f, 1, 0, 1f) }
    }
}
