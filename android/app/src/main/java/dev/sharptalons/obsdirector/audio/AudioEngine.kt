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
        /** The least cushion kept queued for a Bluetooth output. */
        const val BT_CUSHION_MS = 160
        private const val HEADER = 24
    }

    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var track: AudioTrack? = null

    @Volatile var listening = true
    /** A gain, from [Loudness]: up to 1 is the player's own volume; above 1 the mix is boosted here, through a limiter. */
    @Volatile var volume = 1f
    /** While a clip plays, the mix is turned down to this share of [volume] (1 = not at all). */
    @Volatile var duck = 1f
    /** Where the dip is now: it moves towards [duck] over about 150 ms, so it fades rather than clicks. */
    private var duckNow = 1f
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
    /** The track has begun playing (its play head moved). Until then it is filled, never trimmed. */
    private var started = false
    private var trackMadeAt = 0L
    private var lastHead = 0L
    private var headStillSince = 0L
    private var adaptiveMs = 10
    private var lateCount = 0
    /** The current track plays to Bluetooth: a deeper buffer and cushion. */
    private var bluetooth = false
    /** The buffer level, smoothed over about half a second: Bluetooth's play head moves in jumps. */
    private var smoothedMs = -1.0
    private val resampler = Resampler()

    val port: Int get() = socket?.localPort ?: 0

    /** File time (ms) of the audio coming out of the speaker right now, or -1 if unknown. */
    fun heardFileTimeMs(): Long {
        if (lastFileTimeUs <= 0 || SystemClock.elapsedRealtime() - lastPacketAt > 1500) return -1
        return lastFileTimeUs / 1000 - bufferedMs()
    }

    /** How much of the mix is queued in the player now, in ms: how far behind the newest packet you hear. */
    fun queuedMs(): Long = if (listening) bufferedMs() else 0

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
        // Bluetooth pulls audio in uneven bursts of 20-40 ms, so it gets a deep buffer (400 ms)
        // and a cushion of about 160 ms; the phone's speaker and wired headphones keep a small one.
        // Either way playback starts once the start threshold is queued (below), not when the
        // whole buffer is full: a buffer that never fills kept some outputs silent.
        bluetooth = route?.lowLatency == false
        val bytes = max(minBytes, RATE * CHANNELS * 2 * (if (bluetooth) 400 else 60) / 1000)
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
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            // Low latency only to the phone or wired headphones: Bluetooth adds its own delay,
            // and on some phones a low-latency track won't move to a Bluetooth headset.
            .setPerformanceMode(if (route == null || route.lowLatency) AudioTrack.PERFORMANCE_MODE_LOW_LATENCY else AudioTrack.PERFORMANCE_MODE_NONE)
            .build()
        // Start once ~20 ms is queued (120 ms on Bluetooth), not when the whole buffer is full.
        if (android.os.Build.VERSION.SDK_INT >= 31)
            runCatching { t.setStartThresholdInFrames(minOf(t.bufferSizeInFrames, RATE * (if (bluetooth) 120 else 20) / 1000)) }
        route?.device?.let { t.setPreferredDevice(it) }
        started = false
        resampler.reset()
        smoothedMs = -1.0
        trackMadeAt = SystemClock.elapsedRealtime()
        lastHead = 0
        headStillSince = 0
        return t
    }

    private fun releaseTrack() {
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        framesWritten = 0
        started = false
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
                // and keep the cushion steady while listening: a short loss plays as a short gap
                if (missing <= 20) repeat(missing.toInt()) { play(silence) }
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
        duckNow += (duck.coerceIn(0f, 1f) - duckNow) * 0.035f * (pcm.size / 2f / FRAMES_PER_PACKET)
        if (kotlin.math.abs(duck - duckNow) < 0.002f) duckNow = duck
        val v = (volume * duckNow).coerceIn(0f, 10f)
        t.setVolume(v.coerceAtMost(1f))
        val out = if (v > 1f) boost(pcm, v) else pcm

        val now = SystemClock.elapsedRealtime()
        val head = t.playbackHeadPosition.toLong() and 0xffffffffL
        if (!started) {
            if (head > 0) started = true
            else {
                // Not playing yet: keep filling until Android starts it (write stops at full).
                val n0 = t.write(out, 0, out.size, AudioTrack.WRITE_NON_BLOCKING)
                if (n0 > 0) framesWritten += n0 / CHANNELS
                // Full and still not moving after 2 s: this output didn't take the track. Make a
                // fresh one (it lands wherever Android routes now) rather than stay silent.
                if (now - trackMadeAt > 2000) { releaseTrack(); router?.dirty = true }
                return
            }
        }
        // Playing, but the head stopped (output switched under us, or it hung): start again.
        if (head != lastHead) { lastHead = head; headStillSince = now }
        else if (headStillSince > 0 && now - headStillSince > 1500) { releaseTrack(); return }

        // The cushion to keep queued. Bluetooth never goes below 160 ms, whatever Delay says.
        val target = if (bluetooth) max(BT_CUSHION_MS, targetBufferMs) else if (targetBufferMs > 0) targetBufferMs else adaptiveMs
        val buffered = bufferedMs()
        smoothedMs = if (smoothedMs < 0) buffered.toDouble() else smoothedMs + (buffered - smoothedMs) * 0.02
        // grow the adaptive buffer when packets go missing (phone speaker and wired only)
        if (!bluetooth && targetBufferMs == 0 && lateCount > 3) { adaptiveMs = (adaptiveMs + 10).coerceAtMost(60); lateCount = 0 }

        // Ran dry (Wi-Fi stalled): there is already a gap, so put the cushion back in one go
        // rather than limp along at the edge and crackle.
        if (buffered < 3) {
            if (!bluetooth && targetBufferMs == 0) adaptiveMs = (adaptiveMs + 5).coerceAtMost(60)
            writeSilence(t, target / 2)
            smoothedMs = target / 2.0
        }
        // Far too much queued (a long stall that then caught up): start afresh, once.
        if (smoothedMs > target * 3 + 200) { releaseTrack(); return }

        // Keep the cushion by playing a hair faster or slower (at most 0.6%), never by dropping
        // or repeating packets, which click.
        val err = (smoothedMs - target) / target.coerceAtLeast(10)
        val ratio = 1.0 + (err * 0.004).coerceIn(-0.006, 0.006)
        resampler.process(out, ratio) { o, len ->
            val n = t.write(o, 0, len, AudioTrack.WRITE_NON_BLOCKING)
            if (n > 0) framesWritten += n / CHANNELS
        }
        val outMs = (t.bufferSizeInFrames * 1000 / RATE).coerceAtMost(40)
        latency.value = (bufferedMs() + 6 + outMs / 2).toInt()
    }

    private fun writeSilence(t: AudioTrack, ms: Int) {
        val frames = RATE * ms / 1000
        val z = ShortArray(frames * CHANNELS)
        val n = t.write(z, 0, z.size, AudioTrack.WRITE_NON_BLOCKING)
        if (n > 0) framesWritten += n / CHANNELS
    }

    /** Louder than the mix: the gain, then [Limiter]. Only what you hear: the saved audio is untouched. */
    private fun boost(pcm: ShortArray, gain: Float): ShortArray {
        if (boosted.size != pcm.size) boosted = ShortArray(pcm.size)
        limiter.process(pcm, gain, boosted)
        return boosted
    }

    private val limiter = Limiter()

    private fun toDb(peak: Int): Float = if (peak <= 0) -90f else (20 * log10(peak / 32768.0)).toFloat()

}

/**
 * A short two-note chime through the output the mix would use now, to check it from Settings
 * without OBS. Uses the same kind of track and the same routing as listening.
 */
object TestSound {
    fun play(router: OutputRouter?) {
        Thread({
            runCatching {
                val route = router?.current()
                route?.let { router.applyCallRoute(it) }
                val rate = 48000
                val n = rate * 7 / 10
                val pcm = ShortArray(n * 2)
                for (i in 0 until n) {
                    val f = if (i < n / 2) 660.0 else 880.0
                    val env = minOf(1.0, i / 480.0, (n - i) / 4800.0)
                    val v = (kotlin.math.sin(2 * Math.PI * f * i / rate) * 0.35 * env * 32767).toInt().toShort()
                    pcm[2 * i] = v; pcm[2 * i + 1] = v
                }
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(if (route?.callChannel == true) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                route?.device?.let { t.setPreferredDevice(it) }
                t.write(pcm, 0, pcm.size)
                t.play()
                router?.reportRouted(t.routedDevice)
                Thread.sleep(900)
                t.release()
                if (route?.callChannel == true && router != null) router.releaseCallRoute()
            }
        }, "obs-director-test-sound").start()
    }
}
