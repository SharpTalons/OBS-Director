package dev.sharptalons.obsdirector.audio

import dev.sharptalons.obsdirector.core.AudioSeg
import dev.sharptalons.obsdirector.core.ObsSpan
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The phone's own copy of a session: everything it hears from OBS while connected, whether or not
 * OBS is recording. Clips are timed against it, so they work any time.
 *
 * - **Time:** milliseconds from the session's first sample, counted in samples (lost packets are
 *   filled with silence on the way in), so the saved audio and clip times always line up.
 * - **Audio:** AAC in one-minute pieces ([SEGMENT_MS]), so a clip can be played moments after it
 *   is made; [flush] closes the piece being written when a clip needs it sooner.
 * - **Loudness:** one value per 100 ms, for the trimmer's picture and its pauses.
 * - **OBS:** which stretches OBS was recording ([ObsSpan]), from the recording flag and file time
 *   in each packet; a pause, a new file or a jump in file time starts a new stretch.
 *
 * [write] runs on the audio thread and never blocks it; finishing a piece happens on its own thread.
 */
class SessionCapture(val id: String, private val dir: File, private val saveAudio: Boolean, private val onSegment: () -> Unit) {

    companion object {
        const val SEGMENT_MS = 60_000L
        private const val PACKET_MS = AudioEngine.FRAMES_PER_PACKET * 1000L / AudioEngine.RATE
    }

    private val lock = Any()
    @Volatile private var frames = 0L
    /** Session time of the newest sample, in ms. */
    val posMs: Long get() = frames * 1000 / AudioEngine.RATE

    private val envelope = ArrayList<Float>()
    private var windowPeak = 0
    private var windowFrames = 0

    private val spans = ArrayList<ObsSpan>()
    private var open: ObsSpan? = null

    private val segments = ArrayList<AudioSeg>()
    private var recorder: SessionRecorder? = null
    private var recorderStart = 0L
    private var recorderName = ""
    private var segmentCount = 0
    @Volatile private var flushWanted = false
    @Volatile private var finished = false

    /** One packet of the mix: [file] and [fileMs] are what OBS says it is recording, if [recording]. */
    fun write(pcm: ShortArray, gap: Boolean, recording: Boolean, file: String, fileMs: Long) {
        if (finished) return
        val t = posMs

        // the stretches OBS is recording
        synchronized(lock) {
            val o = open
            if (recording && file.isNotEmpty()) {
                val expected = o?.let { it.fileStartMs + (t - it.fromMs) }
                open = if (o == null || o.file != file || abs(fileMs - (expected ?: fileMs)) > 250) {
                    if (o != null) spans.add(o)
                    ObsSpan(file, t, t + PACKET_MS, fileMs)
                } else o.copy(toMs = t + PACKET_MS)
            } else if (o != null) {
                spans.add(o)
                open = null
            }
        }

        // loudness, one value per 100 ms
        var i = 0
        while (i + 1 < pcm.size) {
            val v = max(abs(pcm[i].toInt()), abs(pcm[i + 1].toInt()))
            if (v > windowPeak) windowPeak = v
            if (++windowFrames >= AudioEngine.RATE / 10) {
                synchronized(lock) { envelope.add(sqrt(windowPeak / 32768f)) }
                windowPeak = 0; windowFrames = 0
            }
            i += 2
        }

        // the audio, in pieces
        if (saveAudio) {
            if (recorder != null && (flushWanted || t - recorderStart >= SEGMENT_MS)) closeSegment(t)
            val r = recorder ?: run {
                recorderStart = t
                recorderName = "$id-${"%03d".format(segmentCount++)}.m4a"
                SessionRecorder(File(dir, recorderName), t).also { it.start(); recorder = it }
            }
            r.write(pcm, t, gap)
        }
        flushWanted = false
        frames += pcm.size / 2
    }

    /** Hands the piece being written to its own thread to finish, and starts a new one with the next packet. */
    private fun closeSegment(end: Long) {
        val r = recorder ?: return
        val seg = AudioSeg(recorderName, recorderStart, end)
        recorder = null
        Thread({
            r.finish()
            synchronized(lock) { segments.add(seg); segments.sortBy { it.startMs } }
            onSegment()
        }, "obs-director-segment").start()
    }

    /** Audio finished up to this session time. */
    fun savedUntilMs(): Long = synchronized(lock) { segments.maxOfOrNull { it.endMs } ?: 0 }

    /**
     * Makes sure the audio up to [untilMs] is in finished pieces, closing the one being written if
     * it holds any of it. Blocks (call it off the main thread) for up to 3 s.
     */
    fun flush(untilMs: Long) {
        if (!saveAudio || savedUntilMs() >= untilMs) return
        flushWanted = true
        val deadline = System.currentTimeMillis() + 3000
        while (savedUntilMs() < minOf(untilMs, posMs) && System.currentTimeMillis() < deadline) Thread.sleep(30)
    }

    fun envelope(): List<Float> = synchronized(lock) { ArrayList(envelope) }
    fun spans(): List<ObsSpan> = synchronized(lock) { spans + listOfNotNull(open) }
    fun segments(): List<AudioSeg> = synchronized(lock) { ArrayList(segments) }

    /** Ends the session: the last piece is finished and the last OBS stretch closed. Blocks briefly. */
    fun finish() {
        if (finished) return
        finished = true
        synchronized(lock) { open?.let { spans.add(it) }; open = null }
        recorder?.let { r ->
            r.finish()
            synchronized(lock) { segments.add(AudioSeg(recorderName, recorderStart, posMs)); segments.sortBy { it.startMs } }
        }
        recorder = null
    }
}
