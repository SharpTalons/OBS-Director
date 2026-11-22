package dev.sharptalons.obsdirector.net

import dev.sharptalons.obsdirector.core.Status
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Constants and parsing for the OBS Director protocol (docs/PROTOCOL.md). */
object Protocol {
    const val VERSION = 1
    const val CONTROL_PORT = 4466
    const val BEACON_PORT = 4467
    const val VIDEO_PORT = 4468

    private fun JsonObject.obj(key: String): JsonObject? = runCatching { this[key]?.jsonObject }.getOrNull()
    private fun JsonObject.bool(key: String) = runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: false
    private fun JsonObject.num(key: String) = runCatching { this[key]?.jsonPrimitive?.doubleOrNull }.getOrNull() ?: 0.0
    private fun JsonObject.str(key: String) = runCatching { this[key]?.jsonPrimitive?.content }.getOrNull() ?: ""

    fun parseStatus(o: JsonObject, receivedAt: Long): Status {
        val rec = o.obj("rec") ?: JsonObject(emptyMap())
        val st = o.obj("stream") ?: JsonObject(emptyMap())
        val pc = o.obj("pc") ?: JsonObject(emptyMap())
        return Status(
            recActive = rec.bool("active"),
            recPaused = rec.bool("paused"),
            fileTimeMs = rec.num("fileTimeMs").toLong(),
            file = rec.str("file"),
            container = rec.str("container"),
            recBytes = rec.num("bytes").toLong(),
            diskFreeMb = rec.num("diskFreeMB").toLong(),
            streamActive = st.bool("active"),
            streamDurationMs = st.num("durationMs").toLong(),
            streamKbps = st.num("kbps"),
            dropped = st.num("dropped").toInt(),
            totalFrames = st.num("total").toInt(),
            congestion = st.num("congestion"),
            cpu = pc.num("cpu"),
            fps = pc.num("fps"),
            renderLag = pc.num("renderLag").toLong(),
            encodeLag = pc.num("encodeLag").toLong(),
            scene = o.str("scene"),
            receivedAt = receivedAt,
        )
    }
}
