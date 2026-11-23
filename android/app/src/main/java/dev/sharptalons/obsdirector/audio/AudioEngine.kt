package dev.sharptalons.obsdirector.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/** Peak levels of the mix, in dBFS, for the meters and the silence and clipping alerts. */
data class Levels(val leftDb: Float = -90f, val rightDb: Float = -90f, val clipped: Boolean = false, val at: Long = 0)

/**
 * Receives the program mix as PCM over UDP (see docs/PROTOCOL.md), plays it with a small
 * buffer and hands every packet to the session recorder.
 *
 * Packets carry the recording-file time, so the phone always knows which moment of the
 * recording is coming out of its speaker: that is what clips are timed against.
 */
class AudioEngine(private val onPcm: (pcm: ShortArray, fileTimeUs: Long, recording: Boolean, gap: Boolean) -> Unit) {

    companion object {
        const val RATE = 48000
        const val CHANNELS = 2
        const val FRAMES_PER_PACKET = 240 // 5 ms
        private const val HEADER = 24
    }

    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var track: AudioTrack? = null

    @Volatile var listening = true
    /** 0..3: up to 1 is the player's own volume; above 1 the mix is boosted here, with a soft limiter. */
    @Volatile var volume = 0.8f
    /** Follows the phone's active output and says when to make a fresh track. */
    @Volatile var router: OutputRouter? = null
    private var boosted = ShortArray(0)
    /** 0 = adaptive (starts at 10 ms, grows when packets arrive late) */
    @Volatile var targetBufferMs = 0

    private val levelState = MutableStateFlow(Levels())
    val levels: StateFlow<Levels> get() = levelState
    private val latency = MutableStateFlow(0)
    /** End-to-end delay estimate, PC to ear, in ms. */
    val latencyMs: StateFlow<Int> get() = latency

    @Volatile private var lastFileTimeUs = 0L
    @Volatile private var lastPacketAt = 0L
    private var framesWritten = 0L
    private var adaptiveMs = 10
    private var lateCount = 0

    val port: Int get() = socket?.localPort ?: 0

    /** File time (ms) of the audio coming out of the speaker right now, or -1 if unknown. */
    fun heardFileTimeMs(): Long {
        if (lastFileTimeUs <= 0 || SystemClock.elapsedRealtime() - lastPacketAt > 1500) return -1
        return lastFileTimeUs / 1000 - bufferedMs()
    }

    fun receivingAudio(): Boolean = SystemClock.elapsedRealtime() - lastPacketAt < 1500

    private fun bufferedMs(): Long {
        val t = track ?: return 0
        val head = t.playbackHeadPosition.toLong() and 0xffffffffL
        val buffered = max(0L, framesWritten - head)
        return buffered * 1000 / RATE
    }

    fun start(): Int {
        if (running) return port
        val s = DatagramSocket(0).apply {
            soTimeout = 500
            receiveBufferSize = 1 shl 18
        }
        socket = s
        running = true
        thread = Thread({ loop(s) }, "obs-director-audio").apply { start() }
        return s.localPort
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        runCatching { thread?.join(1000) }
        thread = null
        socket = null
        releaseTrack()
        router?.releaseCallRoute()
        levelState.value = Levels()
    }

    private fun makeTrack(): AudioTrack {
        val route = router?.current()
        router?.dirty = false
        route?.let { router?.applyCallRoute(it) }
        val minBytes = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // A call-only Bluetooth headset only hears the call channel.
                    .setUsage(if (route?.callChannel == true) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(max(minBytes, RATE * CHANNELS * 2 / 5)) // room for 200 ms, we keep far less
            .setTransferMode(AudioTrack.MODE_STREAM)
            // Low latency only to the phone or wired headphones: Bluetooth adds its own delay,
            // and on some phones a low-latency track won't move to a Bluetooth headset.
            .setPerformanceMode(if (route == null || route.lowLatency) AudioTrack.PERFORMANCE_MODE_LOW_LATENCY else AudioTrack.PERFORMANCE_MODE_NONE)
            .build()
        route?.device?.let { t.setPreferredDevice(it) }
        return t
    }

    private fun releaseTrack() {
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        framesWritten = 0
    }

    private fun loop(s: DatagramSocket) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ByteArray(4096)
        val pkt = DatagramPacket(buf, buf.size)
        var expectedSeq = -1L
        var lastLevelAt = 0L
        var peakL = 0
        var peakR = 0
        var clipped = false
        val silence = ShortArray(FRAMES_PER_PACKET * CHANNELS)

        while (running) {
            try {
                s.receive(pkt)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                break
            }
            if (pkt.length < HEADER || buf[0] != 'O'.code.toByte() || buf[1] != 'D'.code.toByte() ||
                buf[2] != 'A'.code.toByte() || buf[3] != '1'.code.toByte()) continue
            val bb = ByteBuffer.wrap(buf, 0, pkt.length).order(ByteOrder.LITTLE_ENDIAN)
            val seq = bb.getInt(4).toLong() and 0xffffffffL
            val fileTimeUs = bb.getLong(8)
            val frames = bb.getShort(20).toInt() and 0xffff
            val channels = buf[22].toInt()
            val flags = buf[23].toInt()
            if (channels != CHANNELS || frames <= 0 || HEADER + frames * channels * 2 > pkt.length) continue
            val recording = flags and 1 != 0

            val pcm = ShortArray(frames * channels)
            bb.position(HEADER)
            bb.asShortBuffer().get(pcm)

            // lost packets: fill with silence so the timeline stays true (up to 1 s)
            if (expectedSeq >= 0 && seq > expectedSeq) {
                val missing = (seq - expectedSeq).coerceAtMost(200)
                val packetUs = FRAMES_PER_PACKET * 1_000_000L / RATE
                for (i in 0 until missing) {
                    val t = if (fileTimeUs > 0) fileTimeUs - (missing - i) * packetUs else 0
                    onPcm(silence, t, recording, true)
                }
                lateCount++
            } else if (expectedSeq >= 0 && seq < expectedSeq) {
                continue // late, already covered
            }
            expectedSeq = seq + 1

            lastFileTimeUs = fileTimeUs
            lastPacketAt = SystemClock.elapsedRealtime()
            onPcm(pcm, fileTimeUs, recording, false)

            // levels
            var i = 0
            while (i < pcm.size) {
                val l = abs(pcm[i].toInt()); val r = abs(pcm[i + 1].toInt())
                if (l > peakL) peakL = l
                if (r > peakR) peakR = r
                if (l >= 32700 || r >= 32700) clipped = true
                i += 2
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastLevelAt >= 50) {
                levelState.value = Levels(toDb(peakL), toDb(peakR), clipped, now)
                peakL = 0; peakR = 0; clipped = false
                lastLevelAt = now
            }

            play(pcm)
        }
        releaseTrack()
    }

    private fun play(pcm: ShortArray) {
        if (!listening) {
            if (track != null) { releaseTrack(); router?.releaseCallRoute() }
            return
        }
        // A headset came or went: start a fresh track on the new output.
        if (track != null && router?.dirty == true) releaseTrack()
        val t = track ?: makeTrack().also {
            track = it
            framesWritten = 0
            it.play()
            // Say where the sound actually went, and keep saying it if Android moves it.
            router?.reportRouted(it.routedDevice)
            it.addOnRoutingChangedListener({ r -> router?.reportRouted(r.routedDevice) }, android.os.Handler(android.os.Looper.getMainLooper()))
        }
        val v = volume.coerceIn(0f, 3f)
        t.setVolume(v.coerceAtMost(1f))
        val out = if (v > 1f) boost(pcm, v) else pcm

        val target = if (targetBufferMs > 0) targetBufferMs else adaptiveMs
        val buffered = bufferedMs()
        // grow the adaptive buffer when packets go missing, shrink it slowly when all is well
        if (targetBufferMs == 0) {
            if (lateCount > 3) { adaptiveMs = (adaptiveMs + 10).coerceAtMost(60); lateCount = 0 }
            else if (buffered < 2) { adaptiveMs = (adaptiveMs + 5).coerceAtMost(60) }
        }
        // drift correction: drop a packet when we're ahead, repeat one when we run dry
        if (buffered > target + 25) return
        val n = t.write(out, 0, out.size, AudioTrack.WRITE_NON_BLOCKING)
        if (n > 0) framesWritten += n / CHANNELS
        if (buffered < target / 2) {
            val m = t.write(out, 0, out.size, AudioTrack.WRITE_NON_BLOCKING)
            if (m > 0) framesWritten += m / CHANNELS
        }
        val outMs = (t.bufferSizeInFrames * 1000 / RATE).coerceAtMost(40)
        latency.value = (bufferedMs() + 6 + outMs / 2).toInt()
    }

    /**
     * Louder than 100%: multiply by [gain], then a soft limiter above 70% of full scale rounds
     * peaks off instead of clipping them. Only what you hear: saved audio is untouched.
     */
    private fun boost(pcm: ShortArray, gain: Float): ShortArray {
        if (boosted.size != pcm.size) boosted = ShortArray(pcm.size)
        val knee = 0.7f
        for (i in pcm.indices) {
            var x = pcm[i] / 32768f * gain
            val a = abs(x)
            if (a > knee) {
                val over = (a - knee) / (1f - knee)
                val soft = knee + (1f - knee) * kotlin.math.tanh(over)
                x = if (x < 0) -soft else soft
            }
            boosted[i] = (x * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return boosted
    }

    private fun toDb(peak: Int): Float = if (peak <= 0) -90f else (20 * log10(peak / 32768.0)).toFloat()
}
