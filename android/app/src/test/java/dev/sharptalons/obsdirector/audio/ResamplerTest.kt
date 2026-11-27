package dev.sharptalons.obsdirector.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ResamplerTest {
    /** 5 ms packets of a 440 Hz tone, the way the mix arrives. */
    private fun packets(count: Int): List<ShortArray> {
        var t = 0
        return List(count) {
            ShortArray(240 * 2) { i -> if (i % 2 == 0) t++.let { (sin(2 * PI * 440 * it / 48_000) * 16000).toInt().toShort() } else (sin(2 * PI * 440 * (t - 1) / 48_000) * 16000).toInt().toShort() }
        }
    }

    private fun run(ratio: Double, count: Int = 400): ShortArray {
        val r = Resampler()
        val out = ArrayList<Short>()
        for (p in packets(count)) r.process(p, ratio) { o, n -> for (k in 0 until n) out += o[k] }
        return out.toShortArray()
    }

    @Test fun samePaceKeepsEveryFrame() {
        // one frame of delay (the carried frame), otherwise the same count
        assertEquals(400 * 240, run(1.0).size / 2)
    }

    @Test fun fasterAndSlowerChangeLengthByTheRatio() {
        val n = 400 * 240
        assertEquals(n / 1.006, run(1.006).size / 2.0, 3.0)
        assertEquals(n / 0.994, run(0.994).size / 2.0, 3.0)
    }

    @Test fun noJumpsBetweenPackets() {
        // a 440 Hz tone at this level moves at most ~920 per sample; a seam or a dropped/repeated
        // packet would jump far more
        for (ratio in listOf(0.994, 1.0, 1.006)) {
            val out = run(ratio)
            var worst = 0
            var i = 2
            while (i < out.size) { worst = maxOf(worst, abs(out[i] - out[i - 2])); i += 2 }
            assertTrue("ratio $ratio worst step $worst", worst < 1000)
        }
    }
}
