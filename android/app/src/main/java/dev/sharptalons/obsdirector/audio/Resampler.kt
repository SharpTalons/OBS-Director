package dev.sharptalons.obsdirector.audio

/**
 * Plays the mix a fraction faster or slower to keep the playback buffer where it should be,
 * instead of dropping or repeating packets (each of which is a hard jump in the waveform, heard
 * as a click). A change of up to 0.6% is far below what anyone can hear.
 *
 * Interleaved stereo 16-bit; linear interpolation between frames, carrying the last frame and the
 * fractional position from one packet to the next so there is no seam between packets.
 */
class Resampler {
    private var lastL = 0f
    private var lastR = 0f
    /** Position of the next output frame, in input frames after [lastL]/[lastR] (0..1 = between it and the first new frame). */
    private var pos = 0.0
    private var out = ShortArray(0)

    /**
     * Takes one packet and returns how many frames it wrote into [output]. [ratio] is input frames
     * per output frame: above 1 plays faster (fewer frames out), below 1 slower.
     */
    fun process(input: ShortArray, ratio: Double, output: (ShortArray, Int) -> Unit) {
        val frames = input.size / 2
        if (frames == 0) return
        val maxOut = (frames / ratio).toInt() + 4
        if (out.size < maxOut * 2) out = ShortArray(maxOut * 2)
        var n = 0
        // frame k of the extended input: k = 0 is the last frame of the previous packet
        while (pos < frames) {
            val i = pos.toInt()
            val f = (pos - i).toFloat()
            val l0 = if (i == 0) lastL else input[(i - 1) * 2].toFloat()
            val r0 = if (i == 0) lastR else input[(i - 1) * 2 + 1].toFloat()
            val l1 = input[i * 2].toFloat()
            val r1 = input[i * 2 + 1].toFloat()
            out[n * 2] = (l0 + (l1 - l0) * f).toInt().coerceIn(-32768, 32767).toShort()
            out[n * 2 + 1] = (r0 + (r1 - r0) * f).toInt().coerceIn(-32768, 32767).toShort()
            n++
            pos += ratio
        }
        pos -= frames
        lastL = input[(frames - 1) * 2].toFloat()
        lastR = input[(frames - 1) * 2 + 1].toFloat()
        output(out, n * 2)
    }

    fun reset() { pos = 0.0; lastL = 0f; lastR = 0f }
}
