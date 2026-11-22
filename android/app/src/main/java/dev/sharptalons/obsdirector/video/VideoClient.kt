package dev.sharptalons.obsdirector.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Receives the plugin's H.264 live output (640x360, about 24 fps) over TCP and decodes it onto a Surface.
 * Frame layout: "ODV1", u32 length, u8 flags (1 = SPS/PPS, 2 = keyframe), i64 pts in us, data.
 */
class VideoClient(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val surface: Surface,
    private val onFrame: () -> Unit,
    private val onEnded: (String) -> Unit,
) {
    @Volatile private var running = false
    private var socket: Socket? = null
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ run() }, "obs-director-video").apply { start() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        runCatching { thread?.join(1000) }
    }

    private fun run() {
        var codec: MediaCodec? = null
        var configured = false
        var pendingConfig: ByteArray? = null
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 4000)
            s.soTimeout = 5000
            socket = s
            s.getOutputStream().apply { write((token + "\n").toByteArray()); flush() }
            val input = DataInputStream(s.getInputStream().buffered(1 shl 16))
            val head = ByteArray(17)
            val info = MediaCodec.BufferInfo()
            while (running) {
                input.readFully(head)
                if (head[0] != 'O'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != 'V'.code.toByte()) break
                val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
                val len = bb.getInt(4)
                val flags = head[8].toInt()
                val pts = bb.getLong(9)
                if (len <= 0 || len > 4_000_000) break
                val data = ByteArray(len)
                input.readFully(data)
                val isConfig = flags and 1 != 0
                val isKey = flags and 2 != 0

                if (isConfig) {
                    if (!configured) pendingConfig = data
                    continue
                }
                if (!configured) {
                    if (!isKey || pendingConfig == null) continue
                    val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 640, 360).apply {
                        setByteBuffer("csd-0", ByteBuffer.wrap(pendingConfig!!))
                        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                    codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    codec.configure(fmt, surface, null, 0)
                    codec.start()
                    configured = true
                }
                val c = codec ?: continue
                val inIdx = c.dequeueInputBuffer(20_000)
                if (inIdx >= 0) {
                    val buf = c.getInputBuffer(inIdx)!!
                    buf.clear()
                    if (data.size <= buf.remaining()) {
                        buf.put(data)
                        c.queueInputBuffer(inIdx, 0, data.size, pts, if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    } else c.queueInputBuffer(inIdx, 0, 0, pts, 0)
                }
                while (true) {
                    val outIdx = c.dequeueOutputBuffer(info, 0)
                    if (outIdx >= 0) {
                        c.releaseOutputBuffer(outIdx, true)
                        onFrame()
                    } else break
                }
            }
            onEnded("stopped")
        } catch (e: Exception) {
            if (running) onEnded(e.message ?: "video ended")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { socket?.close() }
        }
    }
}
