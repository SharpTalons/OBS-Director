package dev.sharptalons.obsdirector.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class LoudnessTest {
    @Test fun scale() {
        assertEquals(1f, Loudness.gain(50), 1e-4f)
        assertEquals(10f, Loudness.gain(100), 1e-3f)
        assertEquals(0.01f, Loudness.gain(1), 1e-4f)
        assertEquals(10f, Loudness.db(75), 0.01f)
        assertEquals(-20f, Loudness.db(25), 0.5f)
        // each step is louder than the one before
        for (p in 2..100) assertTrue(Loudness.gain(p) > Loudness.gain(p - 1))
    }

    @Test fun oldVolumesCarryOver() {
        assertEquals(50, Loudness.fromGain(1f))
        assertEquals(1, Loudness.fromGain(0f))
        // the old 300% was +9.5 dB: now about 74%
        assertEquals(74, Loudness.fromGain(3f))
        for (p in 1..100) assertEquals(p, Loudness.fromGain(Loudness.gain(p)))
    }

    private fun rms(a: ShortArray) = sqrt(a.sumOf { (it / 32768.0) * (it / 32768.0) } / a.size)

    @Test fun limiterBoostsWithoutClipping() {
        // a second of speech-like sound: a quiet tone with loud bursts, peaking at −6 dBFS
        val n = 48_000
        val input = ShortArray(n * 2) { i ->
            val t = (i / 2).toDouble() / 48_000
            val level = if ((i / 2) % 12_000 < 1_000) 0.5 else 0.08
            (sin(2 * PI * 220 * t) * level * 32767).toInt().toShort()
        }
        val out = ShortArray(input.size)
        Limiter().process(input, Loudness.gain(100), out)
        val peak = out.maxOf { abs(it.toInt()) } / 32768.0
        assertTrue("peak $peak stays under the ceiling", peak <= Limiter.CEILING + 0.001)
        // the full +20 dB on the quiet parts makes the whole far louder: at least 3 times the RMS (+10 dB)
        assertTrue("rms ${rms(out)} vs ${rms(input)}", rms(out) > rms(input) * 3)
    }
}
