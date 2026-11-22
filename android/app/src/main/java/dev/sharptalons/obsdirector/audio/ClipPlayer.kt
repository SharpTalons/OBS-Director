package dev.sharptalons.obsdirector.audio

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Plays a stretch of a saved session file: a clip, or a few seconds around an edge. */
object ClipPlayer {
    data class State(val key: String = "", val fromMs: Long = 0, val toMs: Long = 0, val posMs: Long = 0, val playing: Boolean = false)

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private val state = MutableStateFlow(State())
    val playback: StateFlow<State> get() = state

    /**
     * [fromMs]/[toMs] are recording file times; [offsetMs] is the file time where the audio file starts.
     * Returns false if the file can't be played (for example, the recording is still going).
     */
    fun play(key: String, file: File, offsetMs: Long, fromMs: Long, toMs: Long): Boolean {
        stop()
        val mp = MediaPlayer()
        return try {
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            val start = (fromMs - offsetMs).coerceAtLeast(0)
            mp.seekTo(start, MediaPlayer.SEEK_CLOSEST)
            mp.start()
            player = mp
            state.value = State(key, fromMs, toMs, fromMs, true)
            tick(offsetMs)
            true
        } catch (_: Exception) {
            mp.release()
            false
        }
    }

    private fun tick(offsetMs: Long) {
        handler.postDelayed({
            val mp = player ?: return@postDelayed
            val pos = runCatching { mp.currentPosition.toLong() + offsetMs }.getOrDefault(state.value.toMs)
            state.value = state.value.copy(posMs = pos)
            if (pos >= state.value.toMs || !mp.isPlaying) stop() else tick(offsetMs)
        }, 80)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        if (state.value.playing) state.value = state.value.copy(playing = false)
    }
}
