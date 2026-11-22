package dev.sharptalons.obsdirector.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Recordings and their clips, kept as one JSON file on this device. */
object ClipStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var file: File
    lateinit var sessionsDir: File
        private set
    private val state = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> get() = state

    fun init(context: Context) {
        file = File(context.filesDir, "recordings.json")
        sessionsDir = File(context.filesDir, "sessions").apply { mkdirs() }
        state.value = runCatching { json.decodeFromString<List<Recording>>(file.readText()) }.getOrDefault(emptyList())
            .map { it.copy(live = false) }
        pruneOldAudio(Prefs.settings.value.keepDays)
    }

    @Synchronized
    private fun save(list: List<Recording>) {
        state.value = list
        runCatching {
            val tmp = File(file.parentFile, "recordings.json.tmp")
            tmp.writeText(json.encodeToString(list))
            tmp.renameTo(file)
        }
    }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    /** The recording for [fileName], created if this is the first we hear of it. */
    @Synchronized
    fun ensure(fileName: String, live: Boolean): Recording {
        val list = state.value
        val found = list.firstOrNull { it.file == fileName }
        if (found != null) {
            if (found.live != live) save(list.map { if (it.file == fileName) it.copy(live = live) else it })
            return state.value.first { it.file == fileName }
        }
        val rec = Recording(file = fileName, startedAt = System.currentTimeMillis(), live = live)
        save(listOf(rec) + list.map { if (live) it.copy(live = false) else it })
        return rec
    }

    @Synchronized
    fun update(fileName: String, change: (Recording) -> Recording) {
        save(state.value.map { if (it.file == fileName) change(it) else it })
    }

    fun addClip(fileName: String, clip: Clip) = update(fileName) { r ->
        r.copy(clips = (r.clips + clip).sortedBy { it.startMs })
    }

    fun updateClip(fileName: String, clip: Clip) = update(fileName) { r ->
        r.copy(clips = r.clips.map { if (it.id == clip.id) clip else it }.sortedBy { it.startMs })
    }

    fun removeClip(fileName: String, clipId: String) = update(fileName) { r ->
        r.copy(clips = r.clips.filterNot { it.id == clipId })
    }

    fun audioFileFor(rec: Recording): File? = rec.audioFile?.let { File(sessionsDir, it) }?.takeIf { it.exists() }

    /** Session audio older than the chosen number of days is deleted; clips and notes stay. */
    fun pruneOldAudio(days: Int) {
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val list = state.value
        var changed = false
        val out = list.map { r ->
            if (!r.live && r.startedAt < cutoff && r.audioFile != null) {
                File(sessionsDir, r.audioFile).delete()
                changed = true
                r.copy(audioFile = null)
            } else r
        }
        if (changed) save(out)
    }
}
