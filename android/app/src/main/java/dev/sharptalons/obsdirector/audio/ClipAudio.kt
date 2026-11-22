package dev.sharptalons.obsdirector.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Cuts a clip's audio, with 10 s spare either side, out of a finished session file without re-encoding. */
object ClipAudio {
    const val SPARE_MS = 10_000L

    fun cut(session: File, offsetMs: Long, startMs: Long, endMs: Long, out: File): Boolean {
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            ex.setDataSource(session.absolutePath)
            if (ex.trackCount == 0) return false
            ex.selectTrack(0)
            val fmt = ex.getTrackFormat(0)
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val track = muxer.addTrack(fmt)
            muxer.start()
            val fromUs = ((startMs - SPARE_MS - offsetMs).coerceAtLeast(0)) * 1000
            val toUs = (endMs + SPARE_MS - offsetMs) * 1000
            ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buf = ByteBuffer.allocate(1 shl 16)
            val info = MediaCodec.BufferInfo()
            var base = -1L
            while (true) {
                info.size = ex.readSampleData(buf, 0)
                if (info.size < 0) break
                val t = ex.sampleTime
                if (t > toUs) break
                if (base < 0) base = t
                info.offset = 0
                info.presentationTimeUs = t - base
                info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(track, buf, info)
                ex.advance()
            }
            muxer.stop()
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { muxer?.release() }
            ex.release()
        }
    }
}
