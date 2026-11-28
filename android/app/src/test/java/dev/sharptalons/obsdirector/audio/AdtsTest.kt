package dev.sharptalons.obsdirector.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AdtsTest {
    private fun frame(payload: Int) = Adts.header(payload, 48000, 2) + ByteArray(payload) { 0x11 }

    @Test fun headerCarriesTheFrameLength() {
        val h = Adts.header(300, 48000, 2)
        assertEquals(0xFF, h[0].toInt() and 0xFF)
        val len = ((h[3].toInt() and 3) shl 11) or ((h[4].toInt() and 0xFF) shl 3) or ((h[5].toInt() and 0xFF) shr 5)
        assertEquals(307, len)
        // 48 kHz is index 3, stereo is channel configuration 2
        assertEquals(3, (h[2].toInt() shr 2) and 0xF)
        assertEquals(2, ((h[2].toInt() and 1) shl 2) or ((h[3].toInt() and 0xFF) shr 6))
    }

    @Test fun aPieceCutShortKeepsItsWholeFrames() {
        val f = File.createTempFile("piece", ".aac")
        val whole = frame(340) + frame(355) + frame(320)
        // the app was killed halfway through writing the fourth frame
        f.writeBytes(whole + frame(330).copyOf(100))
        assertEquals(3, Adts.repair(f))
        assertEquals(whole.size.toLong(), f.length())
        assertEquals(64, Adts.durationMs(3, 48000))
        f.delete()
    }

    @Test fun somethingElseIsNotCounted() {
        val f = File.createTempFile("piece", ".aac")
        f.writeBytes(ByteArray(50) { 1 })
        assertEquals(0, Adts.repair(f))
        f.delete()
    }
}
