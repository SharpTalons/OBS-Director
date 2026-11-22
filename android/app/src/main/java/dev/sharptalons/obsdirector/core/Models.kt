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
) {
    val lengthMs: Long get() = endMs - startMs
}

/** A gap in the audio saved on the phone (Wi-Fi drop), in file time. */
@Serializable
data class Gap(val atMs: Long, val lengthMs: Long)

/** One OBS recording file, as this phone knows it. */
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
)

@Serializable
data class DeskMessage(
    val id: String,
    val fromDesk: Boolean,
    val text: String,
    val at: Long = System.currentTimeMillis(),
    val seenAt: Long? = null,
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
