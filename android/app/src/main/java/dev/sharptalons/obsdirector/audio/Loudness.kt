package dev.sharptalons.obsdirector.audio

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The volume slider's scale. The slider runs from 1 to 100%, but ears hear in decibels, so each
 * step is an even step in loudness rather than in amplitude:
 *
 * - 1% is 40 dB quieter than OBS sends it, 25% is 20 dB quieter;
 * - 50% is the mix as OBS sends it;
 * - 75% is 12 dB louder (more than twice as loud to the ear), 100% is 24 dB louder (about five times).
 *
 * Above 50% the boost is done in the app, with a limiter that holds peaks just under full scale.
 */
object Loudness {
    const val AS_SENT = 50
    const val MIN_DB = -40f
    const val MAX_DB = 24f

    /** The labels under the slider, at 1, 25, 50, 75 and 100%. */
    val marks = listOf("−40 dB", "−20", "As sent", "+12", "+24 dB")

    fun db(percent: Int): Float {
        val p = percent.coerceIn(1, 100)
        return if (p <= AS_SENT) MIN_DB - MIN_DB * (p - 1) / (AS_SENT - 1f) else MAX_DB * (p - AS_SENT) / (100f - AS_SENT)
    }

    /** The gain to apply: 0.01 at 1%, 1 at 50%, about 16 at 100%. */
    fun gain(percent: Int): Float = 10f.pow(db(percent) / 20f)

    /** The slider position for a gain, for volumes saved before this scale (0–300% of amplitude). */
    fun fromGain(gain: Float): Int {
        if (gain <= 0f) return 1
        val d = 20f * log10(gain)
        val p = if (d <= 0f) 1 + (d - MIN_DB) / -MIN_DB * (AS_SENT - 1) else AS_SENT + d / MAX_DB * (100 - AS_SENT)
        return p.roundToInt().coerceIn(1, 100)
    }

    /** In plain words, with the dB after: "As OBS sends it", "About half as loud (−10 dB)", "About twice as loud (+12 dB)". */
    fun describe(percent: Int): String {
        val d = db(percent).roundToInt()
        // to the ear, every 10 dB is twice (or half) as loud
        val times = 2.0.pow(d / 10.0)
        val words = when {
            d == 0 -> return "As OBS sends it"
            times >= 1.8 -> "About ${if (times < 2.5) "twice" else if (times < 3.5) "3 times" else if (times < 4.5) "4 times" else "5 times"} as loud"
            times > 1.05 -> "A little louder"
            times <= 0.12 -> "Very quiet"
            times <= 0.3 -> "About a quarter as loud"
            times <= 0.6 -> "About half as loud"
            else -> "A little quieter"
        }
        return "$words (${if (d > 0) "+" else "−"}${kotlin.math.abs(d)} dB)" + (if (d > 0) ". A limiter keeps loud parts clean." else "")
    }
}

/**
 * The boost's limiter, for interleaved stereo 16-bit samples. It turns the whole sound down for a
 * moment when a peak would pass 98% of full scale, and lets it back up over about 100 ms, so quiet
 * speech comes up by the full boost while shouts and claps don't distort. Both channels move
 * together, so the stereo picture stays put.
 */
class Limiter {
    /** The gain it is holding back by now; 1 when it isn't. */
    var hold = 1f
        private set

    fun process(input: ShortArray, gain: Float, out: ShortArray) {
        var i = 0
        while (i + 1 < input.size) {
            val l = input[i] / 32768f * gain
            val r = input[i + 1] / 32768f * gain
            val peak = maxOf(kotlin.math.abs(l), kotlin.math.abs(r))
            val want = if (peak * hold > CEILING) CEILING / peak else 1f
            hold = if (want < hold) want else hold + (want - hold) * RELEASE
            // letting go never lifts a peak past the ceiling
            if (peak * hold > CEILING) hold = CEILING / peak
            out[i] = (l * hold * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            out[i + 1] = (r * hold * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            i += 2
        }
    }

    companion object {
        const val CEILING = 0.98f
        /** About 100 ms to let go at 48 kHz: quick enough to keep the level up between words. */
        const val RELEASE = 0.0002f
    }
}
