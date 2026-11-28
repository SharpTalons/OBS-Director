package dev.sharptalons.obsdirector.core

import kotlinx.serialization.Serializable

/** One clip inside a recording. Times are milliseconds from the start of the recording file. */
@Serializable
data class Clip(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val note: String = "",
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val chapterWritten: Boolean = false,
    /** the OBS recording's name when the clip started, or the name OBS would have given one */
    val name: String = "",
    /** its number among the clips with that [name]: 1, 2, 3… */
    val number: Int = 0,
) {
    val lengthMs: Long get() = endMs - startMs

    /** "2026-11-28 19-02-11 clip 2 – Choir intro"; null for a clip from before names. */
    fun fullName(): String? = if (name.isBlank() || number <= 0) null
        else "$name clip $number" + (if (note.isNotBlank()) " – ${note.trim()}" else "")
}

/**
 * One OBS recording the phone was connected for: [file] is its name on the PC, [startedAt] when it
 * began (wall clock), [live] while OBS is still making it. Worked out from the sessions' OBS spans.
 */
data class ObsRecording(val file: String, val startedAt: Long, val durationMs: Long, val live: Boolean) {
    val baseName: String get() = file.substringBeforeLast('.')
}

/** A gap in the audio saved on the phone (Wi-Fi drop), in file time. */
@Serializable
data class Gap(val atMs: Long, val lengthMs: Long)

/**
 * A stretch of a session that OBS was recording: session time [fromMs] to [toMs] is the
 * recording [file] from [fileStartMs] on. A pause in OBS starts a new span.
 */
@Serializable
data class ObsSpan(val file: String, val fromMs: Long, val toMs: Long, val fileStartMs: Long)

/** One piece of a session's saved audio (an .m4a in filesDir/sessions), from [startMs] to [endMs] of the session. */
@Serializable
data class AudioSeg(val name: String, val startMs: Long, val endMs: Long)

/**
 * One session: the time this phone was connected, with its own copy of the mix, and the clips
 * marked in it. Clip times are milliseconds from the start of the session, so clips don't
 * depend on OBS recording; [obs] says which stretches OBS was recording, for chapters and the
 * editor's clip lists.
 *
 * Before 0.9.0 an entry was one OBS recording file ([session] false): its clip times are that
 * file's times, and its audio is [audioFile] from [audioOffsetMs].
 */
@Serializable
data class Recording(
    val file: String,
    val startedAt: Long,
    val durationMs: Long = 0,
    val live: Boolean = false,
    val clips: List<Clip> = emptyList(),
    val gaps: List<Gap> = emptyList(),
    /** local AAC copy of the session audio, relative to filesDir/sessions */
    val audioFile: String? = null,
    /** file time of the first sample in [audioFile] */
    val audioOffsetMs: Long = 0,
    /** loudness envelope, one value 0..1 per 100 ms of file time, for the adjust waveform */
    val envelope: List<Float> = emptyList(),
    /** name of the PC that saved this recording's clip list next to the file, once it has */
    val savedOnPc: String? = null,
    /** a session (0.9.0 on), not one OBS recording */
    val session: Boolean = false,
    /** the stretches OBS was recording, in session time */
    val obs: List<ObsSpan> = emptyList(),
    /** the session's saved audio, in pieces */
    val segments: List<AudioSeg> = emptyList(),
) {
    /** The OBS recordings this entry covers, in its own timeline. */
    fun obsSpans(): List<ObsSpan> = if (session) obs else listOf(ObsSpan(file, 0, Long.MAX_VALUE / 4, 0))

    /** Its saved audio, in its own timeline. */
    fun audioSegments(): List<AudioSeg> = if (session) segments else listOfNotNull(audioFile?.let { AudioSeg(it, audioOffsetMs, Long.MAX_VALUE / 4) })

    /** The OBS recording files this entry overlaps, in order. */
    fun obsFiles(): List<String> = obsSpans().map { it.file }.distinct()

    /**
     * This entry's clips as one OBS recording sees them: each clip's part inside [file], in that
     * file's times. Clips, or parts of them, outside the recording are left out. For the
     * editor's clip lists and the sidecar files on the PC.
     */
    fun forObsFile(file: String): Recording {
        val spans = obsSpans().filter { it.file == file }
        val mapped = clips.mapNotNull { c ->
            val parts = spans.mapNotNull { s ->
                val a = maxOf(c.startMs, s.fromMs); val b = minOf(c.endMs, s.toMs)
                if (b > a) (s.fileStartMs + a - s.fromMs) to (s.fileStartMs + b - s.fromMs) else null
            }
            if (parts.isEmpty()) null else c.copy(startMs = parts.minOf { it.first }, endMs = parts.maxOf { it.second })
        }
        return copy(file = file, clips = mapped, session = false, obs = emptyList(), segments = emptyList())
    }
}

/** One message in the desk chat, a group chat with the OBS desk and every connected device. */
@Serializable
data class DeskMessage(
    val id: String,
    /** written at the desk in OBS */
    val fromDesk: Boolean,
    val text: String,
    val at: Long = System.currentTimeMillis(),
    val seenAt: Long? = null,
    /** the sender's name: "Desk", or the phone or browser's name in OBS */
    val from: String = "",
    /** sent from this phone */
    val mine: Boolean = false,
    /** for this phone's own messages: sending, sent (OBS has it), seen (the desk read it), failed */
    val state: String = "sent",
    /** "message"; "alert" (shown on this phone only, with its actions); "note" (connection events) */
    val kind: String = "message",
    val severe: Boolean = false,
    /** alerts: what "Tell the desk" sends, and whether to offer Start recording */
    val deskText: String? = null,
    val offerStart: Boolean = false,
)

enum class Tag(val id: String, val label: String, val color: Long, val resolve: String) {
    KEY("key", "Key point", 0xFFE8C547, "Yellow"),
    FUNNY("funny", "Funny", 0xFFE58AB0, "Pink"),
    STORY("story", "Story", 0xFF7CC0E8, "Sky"),
    QA("qa", "Q&A", 0xFF3FC1C9, "Cyan");

    companion object {
        fun of(id: String): Tag? = entries.firstOrNull { it.id == id }
    }
}

/** A PC running the plugin, found by its beacon or typed in. */
data class FoundPc(
    val name: String,
    val ip: String,
    val port: Int,
    val obs: String = "",
    val plugin: String = "",
    val lastSeen: Long = System.currentTimeMillis(),
    /** The PC's own ID from its beacon, so one PC heard on two of its adapters is listed once. */
    val id: String = "",
)

/** What the plugin reports a few times a second. */
data class Status(
    val recActive: Boolean = false,
    val recPaused: Boolean = false,
    val fileTimeMs: Long = 0,
    val file: String = "",
    val container: String = "",
    val recBytes: Long = 0,
    val diskFreeMb: Long = -1,
    val streamActive: Boolean = false,
    val streamDurationMs: Long = 0,
    val streamKbps: Double = 0.0,
    val dropped: Int = 0,
    val totalFrames: Int = 0,
    val congestion: Double = 0.0,
    val cpu: Double = 0.0,
    val fps: Double = 0.0,
    val renderLag: Long = 0,
    val encodeLag: Long = 0,
    val scene: String = "",
    /** the name OBS would give a recording started now (Settings, Output, Filename Formatting) */
    val nextName: String = "",
    /** SystemClock.elapsedRealtime() when this arrived */
    val receivedAt: Long = 0,
)

enum class LinkState { DISCONNECTED, CONNECTING, CONNECTED, LOST }

data class Alert(
    val id: String,
    val severe: Boolean,
    val title: String,
    val body: String,
    val deskText: String? = null,
    val offerStartRecording: Boolean = false,
    val at: Long = System.currentTimeMillis(),
)
