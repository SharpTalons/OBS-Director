package dev.sharptalons.obsdirector.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import dev.sharptalons.obsdirector.core.Gap
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Saves the session's mix on the phone as AAC (128 kb/s, 48 kHz stereo), plus a loudness
 * envelope (one value per 100 ms) for the trimmer's waveform and a list of Wi-Fi gaps.
 *
 * The file is raw AAC with a header on each frame ([Adts]), written frame by frame and pushed to
 * storage every few seconds, so a piece cut short by the app being killed or the phone switching
 * off still plays up to the moment it stopped.
 */
class SessionRecorder(private val file: File, val offsetMs: Long) {

    private class Chunk(val pcm: ShortArray?)

    private val queue = LinkedBlockingQueue<Chunk>(4000)
    private val thread = Thread({ run() }, "obs-director-recorder")
    @Volatile private var finished = false

    private val envelopeLock = Any()
    private val envelope = ArrayList<Float>()
    private var windowPeak = 0
    private var windowFrames = 0
    private val gaps = ArrayList<Gap>()
    private var gapStartMs = -1L
    private var gapLen = 0L
    @Volatile var framesIn = 0L
        private set

    fun start() = thread.start()

    /** Called on the audio thread for every 5 ms packet, real or filled-in silence. */
    fun write(pcm: ShortArray, fileTimeMs: Long, gap: Boolean) {
        if (finished) return
        if (gap) {
            if (gapStartMs < 0) { gapStartMs = fileTimeMs; gapLen = 0 }
            gapLen += pcm.size / 2 * 1000L / AudioEngine.RATE
        } else if (gapStartMs >= 0) {
            synchronized(envelopeLock) { gaps.add(Gap(gapStartMs, gapLen)) }
            gapStartMs = -1
        }
        var i = 0
        while (i < pcm.size) {
            val v = max(abs(pcm[i].toInt()), abs(pcm[i + 1].toInt()))
            if (v > windowPeak) windowPeak = v
            windowFrames++
            if (windowFrames >= AudioEngine.RATE / 10) {
                synchronized(envelopeLock) { envelope.add(sqrt(windowPeak / 32768f)) }
                windowPeak = 0; windowFrames = 0
            }
            i += 2
        }
        framesIn += pcm.size / 2
        queue.offer(Chunk(pcm))
    }

    fun envelopeSnapshot(): List<Float> = synchronized(envelopeLock) { ArrayList(envelope) }
    fun gapsSnapshot(): List<Gap> = synchronized(envelopeLock) { ArrayList(gaps) }
    val durationMs: Long get() = framesIn * 1000 / AudioEngine.RATE

    /** Finishes the file. Blocks until the muxer has written it (a few hundred ms). */
    fun finish() {
        if (finished) return
        finished = true
        if (gapStartMs >= 0) synchronized(envelopeLock) { gaps.add(Gap(gapStartMs, gapLen)) }
        queue.offer(Chunk(null))
        runCatching { thread.join(5000) }
    }

    private fun run() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioEngine.RATE, AudioEngine.CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = runCatching { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC) }.getOrNull() ?: return
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val out = FileOutputStream(file)
        var lastSync = System.currentTimeMillis()
        val info = MediaCodec.BufferInfo()
        var frame = ByteArray(4096)
        var samples = 0L
        var eosQueued = false
        var done = false
        var carry: ShortArray? = null
        var carryPos = 0

        fun drain() {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            if (frame.size < info.size + 7) frame = ByteArray(info.size + 7)
                            System.arraycopy(Adts.header(info.size, AudioEngine.RATE, AudioEngine.CHANNELS), 0, frame, 0, 7)
                            buf.position(info.offset); buf.get(frame, 7, info.size)
                            // header and frame in one write: a kill leaves at most one frame half-written
                            out.write(frame, 0, info.size + 7)
                            val now = System.currentTimeMillis()
                            if (now - lastSync > 3000) { runCatching { out.fd.sync() }; lastSync = now }
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { done = true; return }
                    }
                    else -> {}
                }
            }
        }

        try {
            while (!done) {
                if (!eosQueued) {
                    if (carry == null) {
                        val c = queue.poll(50, TimeUnit.MILLISECONDS)
                        if (c != null) {
                            if (c.pcm == null) {
                                val inIdx = codec.dequeueInputBuffer(10_000)
                                if (inIdx >= 0) {
                                    codec.queueInputBuffer(inIdx, 0, 0, samples * 1_000_000L / AudioEngine.RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    eosQueued = true
                                } else queue.offer(c)
                            } else { carry = c.pcm; carryPos = 0 }
                        }
                    }
                    val pcm = carry
                    if (pcm != null) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)!!
                            inBuf.clear()
                            val room = inBuf.remaining() / 2
                            val n = minOf(room, pcm.size - carryPos)
                            val sb = inBuf.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                            sb.put(pcm, carryPos, n)
                            codec.queueInputBuffer(inIdx, 0, n * 2, samples * 1_000_000L / AudioEngine.RATE, 0)
                            samples += n / AudioEngine.CHANNELS
                            carryPos += n
                            if (carryPos >= pcm.size) carry = null
                        }
                    }
                }
                drain()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { out.fd.sync() }
            runCatching { out.close() }
        }
    }
}
