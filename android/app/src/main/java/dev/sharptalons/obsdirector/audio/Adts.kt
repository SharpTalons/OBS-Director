package dev.sharptalons.obsdirector.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Raw AAC with a small header on every frame (ADTS). The session's audio is saved this way so that
 * whatever was written before the app was killed or the phone went off can still be played: an
 * .m4a is only readable once it is finished, an ADTS file is readable up to its last whole frame.
 */
object Adts {
    /** AAC frames hold this many samples per channel. */
    const val FRAME_SAMPLES = 1024

    private fun rateIndex(rate: Int) = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000)
        .indexOf(rate).let { if (it < 0) 3 else it }

    /** The 7-byte header for an AAC-LC frame of [payload] bytes. */
    fun header(payload: Int, rate: Int, channels: Int): ByteArray {
        val len = payload + 7
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            ((1 shl 6) or (rateIndex(rate) shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (len shr 11)).toByte(),
            ((len shr 3) and 0xFF).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    /**
     * Counts the whole frames in [file] and cuts off a frame left half-written, so players accept
     * it. Returns how many frames it holds (0 if it isn't ADTS).
     */
    fun repair(file: File): Long {
        if (!file.exists()) return 0
        RandomAccessFile(file, "rw").use { f ->
            val size = f.length()
            val h = ByteArray(7)
            var pos = 0L
            var frames = 0L
            while (pos + 7 <= size) {
                f.seek(pos)
                f.readFully(h)
                if (h[0] != 0xFF.toByte() || (h[1].toInt() and 0xF0) != 0xF0) break
                val len = ((h[3].toInt() and 3) shl 11) or ((h[4].toInt() and 0xFF) shl 3) or ((h[5].toInt() and 0xFF) shr 5)
                if (len < 7 || pos + len > size) break
                pos += len
                frames++
            }
            if (pos < size) f.setLength(pos)
            return frames
        }
    }

    fun durationMs(frames: Long, rate: Int): Long = frames * FRAME_SAMPLES * 1000 / rate
}
