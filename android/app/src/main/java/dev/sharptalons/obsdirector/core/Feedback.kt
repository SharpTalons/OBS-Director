package dev.sharptalons.obsdirector.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import dev.sharptalons.obsdirector.R
import dev.sharptalons.obsdirector.ui.FeedbackTokens

/**
 * What a tap feels and sounds like: a short tap sound at the volume chosen in Settings
 * (Touch sound), and a short vibration at the strength chosen there (Vibration). The sound is one
 * clean tap cut from the supplied recording (res/raw/touch_tap.wav, 60 ms). It plays on the media
 * volume, the one the volume buttons set while listening, so it is as loud as the phone is turned
 * up rather than at the quieter system-sounds volume. It stays quiet when the phone is on silent or
 * vibrate, as system touch sounds do.
 */
object Feedback {
    private var pool: SoundPool? = null
    private var tapId = 0
    private var loaded = false
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
        pool = SoundPool.Builder()
            .setMaxStreams(FeedbackTokens.TAP_STREAMS)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build().also { p ->
                p.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
                tapId = p.load(app, R.raw.touch_tap, 1)
            }
    }

    /** A tap on any button, tile, switch or row. */
    fun tap() {
        if (!::app.isInitialized) return
        val s = Prefs.settings.value
        playTap(s.touchSound)
        tick(s.haptics)
    }

    /** The tap sound at [volume] percent (0 = off). Also used to preview the setting. */
    fun playTap(volume: Int) {
        if (volume <= 0 || !loaded) return
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val v = FeedbackTokens.tapGain(volume)
        pool?.play(tapId, v, v, 1, 0, 1f)
    }

    /**
     * The vibration for a tap, at strength 0..3 (off, light, medium, strong): a short, firm pulse.
     * Android's own "click" effects are too faint on many phones, so this sets the length and, where
     * the phone can, the strength itself.
     */
    fun tick(level: Int) {
        if (level <= 0) return
        val vib = ContextCompat.getSystemService(app, Vibrator::class.java) ?: return
        val k = level.coerceIn(1, 3) - 1
        runCatching {
            val amp = if (vib.hasAmplitudeControl()) FeedbackTokens.TAP_AMPLITUDE[k] else VibrationEffect.DEFAULT_AMPLITUDE
            vib.vibrate(VibrationEffect.createOneShot(FeedbackTokens.TAP_MS[k], amp))
        }
    }

    /** A slider reached a set point (the volume at "as set in OBS"): a vibration only, no sound. */
    fun detent() {
        if (!::app.isInitialized) return
        tick(Prefs.settings.value.haptics)
    }
}
