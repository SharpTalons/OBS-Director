package dev.sharptalons.obsdirector.audio

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Plays a stretch of saved session audio: a clip, or a few seconds around an edge. The audio is
 * in pieces (a session is saved a minute at a time), so the pieces are chained one after another.
 */
object ClipPlayer {
    data class State(val key: String = "", val fromMs: Long = 0, val toMs: Long = 0, val posMs: Long = 0, val playing: Boolean = false)

    private val handler = Handler(Looper.getMainLooper())
    private var players: List<Pair<MediaPlayer, Long>> = emptyList()
    private var current = 0
    private val state = MutableStateFlow(State())
    val playback: StateFlow<State> get() = state

    /**
     * Plays [fromMs]..[toMs] (in the entry's own time) from [pieces]: each file with the time it
     * starts at, in order. Returns false if nothing could be played.
     */
    fun play(key: String, pieces: List<Pair<File, Long>>, fromMs: Long, toMs: Long): Boolean {
        stop()
        val made = ArrayList<Pair<MediaPlayer, Long>>()
        try {
            for ((file, startMs) in pieces) {
                val mp = MediaPlayer()
                mp.setDataSource(file.absolutePath)
                mp.prepare()
                made += mp to startMs
            }
            if (made.isEmpty()) return false
            // each piece hands over to the next as it ends
            for (k in 0 until made.size - 1) made[k].first.setNextMediaPlayer(made[k + 1].first)
            for ((k, p) in made.withIndex()) p.first.setOnCompletionListener {
                if (k == made.size - 1) stop() else current = k + 1
            }
            val (first, firstStart) = made[0]
            first.seekTo((fromMs - firstStart).coerceAtLeast(0), MediaPlayer.SEEK_CLOSEST)
            first.start()
            players = made
            current = 0
            state.value = State(key, fromMs, toMs, fromMs, true)
            tick()
            return true
        } catch (_: Exception) {
            made.forEach { runCatching { it.first.release() } }
            return false
        }
    }

    private fun tick() {
        handler.postDelayed({
            val (mp, start) = players.getOrNull(current) ?: return@postDelayed
            val pos = runCatching { mp.currentPosition.toLong() + start }.getOrDefault(state.value.toMs)
            state.value = state.value.copy(posMs = pos)
            if (pos >= state.value.toMs) stop() else tick()
        }, 80)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        players.forEach { (mp, _) -> runCatching { mp.stop() }; runCatching { mp.release() } }
        players = emptyList()
        current = 0
        if (state.value.playing) state.value = state.value.copy(playing = false)
    }
}
