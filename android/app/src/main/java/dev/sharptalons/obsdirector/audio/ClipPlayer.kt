package dev.sharptalons.obsdirector.audio

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Plays a stretch of saved session audio: a clip, a stretch of the trimmer, or an AAC file. The
 * session audio is in pieces (saved a minute at a time), so the pieces are chained one after
 * another. It can be paused and moved to any point of the stretch ([seek]).
 */
object ClipPlayer {
    /** [playing] stays true while [paused]: something is loaded and the mix stays lowered only while it sounds. */
    data class State(
        val key: String = "",
        val fromMs: Long = 0,
        val toMs: Long = 0,
        val posMs: Long = 0,
        val playing: Boolean = false,
        val paused: Boolean = false,
    ) {
        /** Sound is coming out now. */
        val sounding: Boolean get() = playing && !paused
    }

    private val handler = Handler(Looper.getMainLooper())
    private var players: List<Pair<MediaPlayer, Long>> = emptyList()
    private var pieces: List<Pair<File, Long>> = emptyList()
    private var current = 0
    private val state = MutableStateFlow(State())
    val playback: StateFlow<State> get() = state

    /**
     * Plays [fromMs]..[toMs] (in the entry's own time) from [pieces]: each file with the time it
     * starts at, in order. Starts at [atMs] if given. Returns false if nothing could be played.
     */
    fun play(key: String, pieces: List<Pair<File, Long>>, fromMs: Long, toMs: Long, atMs: Long = fromMs): Boolean {
        stop()
        this.pieces = pieces
        state.value = State(key, fromMs, toMs, atMs, false)
        return startAt(atMs.coerceIn(fromMs, toMs), paused = false)
    }

    /** Opens the pieces from the one holding [ms] onward, and starts there (or waits there, if [paused]). */
    private fun startAt(ms: Long, paused: Boolean): Boolean {
        release()
        val from = pieces.indexOfLast { it.second <= ms }.coerceAtLeast(0)
        val made = ArrayList<Pair<MediaPlayer, Long>>()
        try {
            for ((file, startMs) in pieces.drop(from)) {
                val mp = MediaPlayer()
                mp.setDataSource(file.absolutePath)
                mp.prepare()
                made += mp to startMs
            }
            if (made.isEmpty()) { state.value = State(); return false }
            // each piece hands over to the next as it ends
            for (k in 0 until made.size - 1) made[k].first.setNextMediaPlayer(made[k + 1].first)
            for ((k, p) in made.withIndex()) p.first.setOnCompletionListener {
                if (k == made.size - 1) stop() else current = k + 1
            }
            val (first, firstStart) = made[0]
            first.seekTo((ms - firstStart).coerceAtLeast(0), MediaPlayer.SEEK_CLOSEST)
            if (!paused) first.start()
            players = made
            current = 0
            state.value = state.value.copy(posMs = ms, playing = true, paused = paused)
            if (!paused) tick()
            return true
        } catch (_: Exception) {
            made.forEach { runCatching { it.first.release() } }
            state.value = State()
            return false
        }
    }

    /** Moves to [ms] of what is playing, playing on (or staying paused) from there. */
    fun seek(ms: Long) {
        val s = state.value
        if (!s.playing) return
        handler.removeCallbacksAndMessages(null)
        startAt(ms.coerceIn(s.fromMs, s.toMs), s.paused)
    }

    fun pause() {
        val s = state.value
        if (!s.sounding) return
        handler.removeCallbacksAndMessages(null)
        players.getOrNull(current)?.let { (mp, start) ->
            runCatching { mp.pause() }
            state.value = s.copy(paused = true, posMs = runCatching { mp.currentPosition.toLong() + start }.getOrDefault(s.posMs))
        }
    }

    fun resume() {
        val s = state.value
        if (!s.playing || !s.paused) return
        val mp = players.getOrNull(current)?.first
        if (mp == null || runCatching { mp.start() }.isFailure) { startAt(s.posMs, false); return }
        state.value = s.copy(paused = false)
        tick()
    }

    fun toggle() { if (state.value.paused) resume() else pause() }

    private fun tick() {
        handler.postDelayed({
            val (mp, start) = players.getOrNull(current) ?: return@postDelayed
            val pos = runCatching { mp.currentPosition.toLong() + start }.getOrDefault(state.value.toMs)
            state.value = state.value.copy(posMs = pos)
            if (pos >= state.value.toMs) stop() else tick()
        }, 80)
    }

    private fun release() {
        handler.removeCallbacksAndMessages(null)
        players.forEach { (mp, _) -> runCatching { mp.stop() }; runCatching { mp.release() } }
        players = emptyList()
        current = 0
    }

    fun stop() {
        release()
        if (state.value.playing) state.value = state.value.copy(playing = false, paused = false)
    }
}
