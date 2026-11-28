package dev.sharptalons.obsdirector.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Cuts a clip's audio, with 10 s spare either side, out of the saved session audio without
 * re-encoding. The audio is in pieces; the parts of each that the clip covers are joined.
 */
object ClipAudio {
    const val SPARE_MS = 10_000L

    /** [pieces]: each file with the time it starts at, in order. */
    fun cut(pieces: List<Pair<File, Long>>, startMs: Long, endMs: Long, out: File): Boolean {
        if (pieces.isEmpty()) return false
        val fromUs = (startMs - SPARE_MS).coerceAtLeast(0) * 1000
        val toUs = (endMs + SPARE_MS) * 1000
        var muxer: MediaMuxer? = null
        var track = -1
        var base = -1L
        var lastUs = -1L
        return try {
            val buf = ByteBuffer.allocate(1 shl 16)
            val info = MediaCodec.BufferInfo()
            for ((file, pieceStartMs) in pieces) {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(file.absolutePath)
                    if (ex.trackCount == 0) continue
                    ex.selectTrack(0)
                    if (muxer == null) {
                        muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        track = muxer.addTrack(ex.getTrackFormat(0))
                        muxer.start()
                    }
                    val offUs = pieceStartMs * 1000
                    ex.seekTo((fromUs - offUs).coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    while (true) {
                        info.size = ex.readSampleData(buf, 0)
                        if (info.size < 0) break
                        val t = ex.sampleTime + offUs
                        if (t > toUs) break
                        if (t >= fromUs && t > lastUs) {
                            if (base < 0) base = t
                            info.offset = 0
                            info.presentationTimeUs = t - base
                            info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            muxer!!.writeSampleData(track, buf, info)
                            lastUs = t
                        }
                        ex.advance()
                    }
                } finally {
                    ex.release()
                }
            }
            muxer?.stop()
            base >= 0
        } catch (_: Exception) {
            false
        } finally {
            runCatching { muxer?.release() }
        }
    }
}
