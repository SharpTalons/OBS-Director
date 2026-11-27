package dev.sharptalons.obsdirector.audio

/**
 * The rules behind the trimmer, kept apart from the screen so they can be tested.
 *
 * - The trimmer shows the clip with 15 s either side ([window]); dragging an edge near the side
 *   of that view widens it.
 * - A clip is at least 1 s long and stays inside the recording.
 * - When a handle is let go, it settles on the quietest moment within half a second ([snap]),
 *   so a cut lands in the pause between words rather than in the middle of one.
 */
object Trim {
    const val MIN_MS = 1000L
    const val PAD_MS = 15_000L
    /** How far a handle may slide to find a pause. */
    const val SNAP_MS = 500L
    /** The loudness envelope has one value per this many ms. */
    const val ENVELOPE_MS = 100L

    /** The stretch of the recording the trimmer shows for a clip. */
    fun window(startMs: Long, endMs: Long, durationMs: Long): LongRange {
        val from = (startMs - PAD_MS).coerceAtLeast(0)
        val to = (endMs + PAD_MS).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
        return from..maxOf(to, from + MIN_MS)
    }

    fun moveStart(start: Long, end: Long, to: Long): Long = to.coerceIn(0, end - MIN_MS)

    fun moveEnd(start: Long, end: Long, to: Long, durationMs: Long): Long =
        to.coerceAtLeast(start + MIN_MS).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }

    /** Moves the whole clip by [delta], keeping its length, inside the recording. Returns the new start. */
    fun moveBoth(start: Long, end: Long, delta: Long, durationMs: Long): Long {
        val len = end - start
        val max = if (durationMs > 0) durationMs - len else Long.MAX_VALUE
        return (start + delta).coerceIn(0, maxOf(0, max))
    }

    /**
     * The quietest moment within [SNAP_MS] of [ms], from the loudness envelope (0..1 per 100 ms),
     * if it is clearly quieter than where the handle was let go; otherwise [ms] unchanged.
     */
    fun snap(ms: Long, envelope: List<Float>): Long {
        if (envelope.isEmpty()) return ms
        val at = (ms / ENVELOPE_MS).toInt()
        val here = envelope.getOrNull(at) ?: return ms
        val reach = (SNAP_MS / ENVELOPE_MS).toInt()
        var best = at
        for (i in (at - reach).coerceAtLeast(0)..(at + reach).coerceAtMost(envelope.size - 1)) {
            if (envelope[i] < envelope[best] || (envelope[i] == envelope[best] && kotlin.math.abs(i - at) < kotlin.math.abs(best - at))) best = i
        }
        // only move when it is a real pause: much quieter than where it was let go
        return if (best != at && envelope[best] < here * 0.6f) best * ENVELOPE_MS + ENVELOPE_MS / 2 else ms
    }
}
