package dev.sharptalons.obsdirector.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Surface
import androidx.core.content.ContextCompat
import dev.sharptalons.obsdirector.audio.AudioEngine
import dev.sharptalons.obsdirector.audio.Levels
import dev.sharptalons.obsdirector.audio.SessionRecorder
import dev.sharptalons.obsdirector.net.Connection
import dev.sharptalons.obsdirector.net.Protocol
import dev.sharptalons.obsdirector.net.data
import dev.sharptalons.obsdirector.net.ok
import dev.sharptalons.obsdirector.service.MonitorService
import dev.sharptalons.obsdirector.service.Notifier
import dev.sharptalons.obsdirector.video.VideoClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The app's single source of truth: the link to the plugin, what OBS is doing, the mix, clips,
 * alerts and desk messages. The UI observes it; the foreground service keeps it alive with the
 * screen off.
 */
@SuppressLint("StaticFieldLeak")
object Director {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ---- link ----
    private val linkState = MutableStateFlow(LinkState.DISCONNECTED)
    val link: StateFlow<LinkState> get() = linkState
    private val pcState = MutableStateFlow<FoundPc?>(null)
    val pc: StateFlow<FoundPc?> get() = pcState
    private val linkError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> get() = linkError
    private val lostSince = MutableStateFlow(0L)
    val lostAt: StateFlow<Long> get() = lostSince
    private val retries = MutableStateFlow(0)
    val retry: StateFlow<Int> get() = retries
    private val reconnectedGap = MutableStateFlow(0L)
    /** how long the last drop lasted, shown in the "Reconnected" banner for a few seconds */
    val reconnectedAfterMs: StateFlow<Long> get() = reconnectedGap
    private var connection: Connection? = null
    private var welcomed = false
    var pluginVersion = ""
        private set

    // ---- OBS ----
    private val statusState = MutableStateFlow(Status())
    val status: StateFlow<Status> get() = statusState

    // ---- audio ----
    private var engine: AudioEngine? = null
    private val listenState = MutableStateFlow(true)
    val listening: StateFlow<Boolean> get() = listenState
    private val levelsState = MutableStateFlow(Levels())
    val levels: StateFlow<Levels> get() = levelsState
    private val latencyState = MutableStateFlow(0)
    val latencyMs: StateFlow<Int> get() = latencyState
    @Volatile private var recorder: SessionRecorder? = null
    private var recorderFile: String? = null

    // ---- clips ----
    private val runningClip = MutableStateFlow<Long?>(null)
    /** file time where the running clip starts, or null */
    val clipStart: StateFlow<Long?> get() = runningClip
    private val pendingNote = MutableStateFlow<Pair<String, Clip>?>(null)
    val noteFor: StateFlow<Pair<String, Clip>?> get() = pendingNote
    private val lastSaved = MutableStateFlow<String?>(null)
    val savedMessage: StateFlow<String?> get() = lastSaved

    // ---- alerts and desk ----
    private val alertState = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> get() = alertState
    private val deskState = MutableStateFlow<List<DeskMessage>>(emptyList())
    val desk: StateFlow<List<DeskMessage>> get() = deskState
    private val toastState = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> get() = toastState

    // ---- video ----
    private val videoOn = MutableStateFlow(false)
    val video: StateFlow<Boolean> get() = videoOn
    private val videoFrames = MutableStateFlow(0L)
    val videoFrameCount: StateFlow<Long> get() = videoFrames
    private var videoClient: VideoClient? = null
    private var videoSurface: Surface? = null
    private var videoToken: String? = null

    // stream health: frames dropped in each of the last 30 minutes, oldest first
    private val healthState = MutableStateFlow<List<Int>>(emptyList())
    val health: StateFlow<List<Int>> get() = healthState
    private var minuteTicks = 0
    private var minuteStartDropped = -1

    // alert bookkeeping
    private var silentSince = 0L
    private var lastClipAlertAt = 0L
    private var stopRequestedHere = false
    private val dropHistory = ArrayDeque<Pair<Long, Int>>()
    private var lastDurationSave = 0L

    fun init(context: Context) {
        app = context.applicationContext
        scope.launch { alertLoop() }
    }

    fun toast(msg: String) { toastState.value = msg }
    fun clearToast() { toastState.value = null }

    // =============================================================== connect

    fun connect(target: FoundPc, pin: String) {
        disconnect(keepError = false)
        val s = Prefs.settings.value
        pcState.value = target
        linkError.value = null
        linkState.value = LinkState.CONNECTING
        welcomed = false
        listenState.value = s.autoListen
        Prefs.update { it.copy(lastIp = target.ip, lastPort = target.port, lastPin = pin, lastPcName = target.name) }

        val conn = Connection(target.ip, target.port, pin, s.deviceName, object : Connection.Listener {
            override fun onWelcome(info: JsonObject) = onWelcomed(info)
            override fun onMessage(msg: JsonObject) = onPluginMessage(msg)
            override fun onLost(reason: String) = onLinkLost()
            override fun onRejected(message: String) {
                linkError.value = message
                linkState.value = LinkState.DISCONNECTED
                stopAudio()
                MonitorService.stop(app)
            }
            override fun onRetry(attempt: Int) { retries.value = attempt }
        })
        conn.autoReconnect = s.autoReconnect
        connection = conn
        conn.start()
    }

    fun disconnect(keepError: Boolean = false) {
        connection?.close()
        connection = null
        endClipIfRunning("disconnect")
        stopVideo()
        stopAudio()
        finishRecorder()
        linkState.value = LinkState.DISCONNECTED
        if (!keepError) linkError.value = null
        alertState.value = emptyList()
        if (::app.isInitialized) MonitorService.stop(app)
    }

    private fun onWelcomed(info: JsonObject) {
        welcomed = true
        pluginVersion = info["plugin"]?.jsonPrimitive?.content ?: ""
        val name = info["pc"]?.jsonPrimitive?.content
        pcState.value = pcState.value?.copy(name = name ?: pcState.value?.name ?: "OBS PC", plugin = pluginVersion,
            obs = info["obs"]?.jsonPrimitive?.content ?: "")
        val wasLost = linkState.value == LinkState.LOST
        if (wasLost) {
            reconnectedGap.value = SystemClock.elapsedRealtime() - lostSince.value
            scope.launch { delay(5000); reconnectedGap.value = 0 }
            if (Prefs.settings.value.resumeListen && listenWasOn) listenState.value = true
            clearAlert("net")
        }
        linkState.value = LinkState.CONNECTED
        retries.value = 0
        MonitorService.start(app)
        startAudio()
        scope.launch {
            if (Prefs.settings.value.deskPresence) deskFeed("${Prefs.settings.value.deviceName} is listening")
        }
    }

    private var listenWasOn = true

    private fun onLinkLost() {
        if (linkState.value != LinkState.CONNECTED) return
        lostSince.value = SystemClock.elapsedRealtime()
        linkState.value = LinkState.LOST
        listenWasOn = listenState.value
        endClipIfRunning("net")
        stopVideo()
        stopAudio()
        vibrate(longArrayOf(0, 200, 100, 200))
        raise(Alert("net", true, "Connection to ${pcState.value?.name ?: "the PC"} lost.",
            "Listening paused. The recording carries on at the PC. " +
                if (Prefs.settings.value.autoReconnect) "Reconnecting…" else "Reconnect from Settings."))
    }

    // =============================================================== plugin messages

    private fun onPluginMessage(msg: JsonObject) {
        when (msg["type"]?.jsonPrimitive?.content) {
            "status" -> onStatus(Protocol.parseStatus(msg, SystemClock.elapsedRealtime()))
            "event" -> {
                val d = runCatching { msg["data"]?.jsonObject }.getOrNull() ?: JsonObject(emptyMap())
                onEvent(msg["event"]?.jsonPrimitive?.content ?: "", d)
            }
        }
    }

    private fun onStatus(s: Status) {
        val prev = statusState.value
        statusState.value = s
        if (s.recActive && s.file.isNotEmpty()) {
            if (prev.file != s.file || !prev.recActive) ClipStore.ensure(s.file, live = true)
            if (s.fileTimeMs - lastDurationSave > 15_000 || s.fileTimeMs < lastDurationSave) {
                lastDurationSave = s.fileTimeMs
                ClipStore.update(s.file) { it.copy(durationMs = s.fileTimeMs) }
            }
        }
        if (prev.recActive && !s.recActive) onRecordingEnded(prev.file, prev.fileTimeMs)
        dropHistory.addLast(SystemClock.elapsedRealtime() to s.dropped)
        while (dropHistory.size > 300) dropHistory.removeFirst()
    }

    private fun onEvent(name: String, d: JsonObject) {
        when (name) {
            "record.stopped" -> {
                val by = d["by"]?.jsonPrimitive?.content ?: ""
                val mine = stopRequestedHere || by == Prefs.settings.value.deviceName
                stopRequestedHere = false
                if (!mine && Prefs.settings.value.alertRecStop) {
                    raise(Alert("rec", true, "Recording stopped on the OBS PC.",
                        if (by.isNotEmpty()) "$by stopped it, not this device." else "It wasn't from this device.",
                        deskText = "Recording stopped, please check", offerStartRecording = true))
                }
            }
            "record.started" -> clearAlert("rec")
            "desk.reply" -> {
                val id = d["id"]?.jsonPrimitive?.content ?: ""
                val text = d["text"]?.jsonPrimitive?.content ?: ""
                val now = System.currentTimeMillis()
                deskState.value = deskState.value.map { if (it.id == id && it.seenAt == null) it.copy(seenAt = now) else it } +
                    DeskMessage(ClipStore.newId(), true, text)
                vibrate(longArrayOf(0, 60))
                Notifier.desk(app, text)
            }
        }
    }

    private fun onRecordingEnded(file: String, durationMs: Long) {
        endClipIfRunning("stop")
        if (file.isNotEmpty()) ClipStore.update(file) { it.copy(live = false, durationMs = durationMs) }
        finishRecorder()
    }

    // =============================================================== audio

    private fun startAudio() {
        if (engine == null) {
            engine = AudioEngine { pcm, fileTimeUs, recording, gap -> onPcm(pcm, fileTimeUs, recording, gap) }.also { e ->
                e.volume = Prefs.settings.value.volume
                e.targetBufferMs = Prefs.settings.value.bufferMs
                e.listening = listenState.value
                scope.launch { e.levels.collect { levelsState.value = it } }
                scope.launch { e.latencyMs.collect { latencyState.value = it } }
            }
        }
        val port = engine!!.start()
        scope.launch {
            val r = connection?.command("audio.start", mapOf("port" to JsonPrimitive(port)))
            if (r != null && !r.ok()) toast("The PC didn't start the audio: ${r["error"]?.jsonPrimitive?.content}")
        }
    }

    private fun stopAudio() {
        engine?.stop()
        engine = null
        levelsState.value = Levels()
    }

    fun setListening(on: Boolean) {
        listenState.value = on
        engine?.listening = on
    }

    fun setVolume(v: Float) {
        engine?.volume = v
        Prefs.update { it.copy(volume = v) }
    }

    fun setBuffer(ms: Int) {
        engine?.targetBufferMs = ms
        Prefs.update { it.copy(bufferMs = ms) }
    }

    /** Audio thread: hand packets to the session recorder for the recording they belong to. */
    private fun onPcm(pcm: ShortArray, fileTimeUs: Long, recording: Boolean, gap: Boolean) {
        if (!recording || !Prefs.settings.value.saveAudio) {
            if (recorder != null && !recording) finishRecorder()
            return
        }
        val file = statusState.value.file
        if (file.isEmpty()) return
        var rec = recorder
        if (rec == null || recorderFile != file) {
            finishRecorder()
            val name = file.substringBeforeLast('.') + ".m4a"
            val out = File(ClipStore.sessionsDir, name)
            rec = SessionRecorder(out, fileTimeUs / 1000).also { it.start() }
            recorder = rec
            recorderFile = file
            ClipStore.ensure(file, live = true)
            ClipStore.update(file) { it.copy(audioFile = name, audioOffsetMs = fileTimeUs / 1000) }
        }
        rec.write(pcm, fileTimeUs / 1000, gap)
    }

    private fun finishRecorder() {
        val rec = recorder ?: return
        val file = recorderFile
        recorder = null
        recorderFile = null
        scope.launch(Dispatchers.IO) {
            rec.finish()
            if (file != null) ClipStore.update(file) { it.copy(envelope = rec.envelopeSnapshot(), gaps = rec.gapsSnapshot()) }
            if (file != null && Prefs.settings.value.sidecar) writeSidecar(file)
        }
    }

    /** The envelope and gaps of the recording in progress, for adjusting clips while it's still going. */
    fun liveEnvelope(file: String): List<Float>? = if (recorderFile == file) recorder?.envelopeSnapshot() else null
    fun liveGaps(file: String): List<Gap>? = if (recorderFile == file) recorder?.gapsSnapshot() else null

    // =============================================================== time

    /** The recording-file time of what you're hearing now (or what OBS is at, if not listening). */
    fun nowFileTimeMs(): Long {
        val heard = engine?.heardFileTimeMs() ?: -1
        if (heard > 0 && listenState.value) return heard
        val s = statusState.value
        if (!s.recActive) return 0
        val drift = if (s.recPaused) 0 else SystemClock.elapsedRealtime() - s.receivedAt
        return s.fileTimeMs + drift
    }

    // =============================================================== clips

    fun startClip() {
        val s = statusState.value
        if (!s.recActive || runningClip.value != null || linkState.value != LinkState.CONNECTED) return
        val lead = Prefs.settings.value.leadMinutes * 60_000L
        runningClip.value = (nowFileTimeMs() - lead).coerceAtLeast(0)
        pendingNote.value = null
        lastSaved.value = null
        vibrate(longArrayOf(0, 20))
        if (Prefs.settings.value.chapters) scope.launch {
            val n = (ClipStore.recordings.value.firstOrNull { it.file == s.file }?.clips?.size ?: 0) + 1
            connection?.command("chapter.add", mapOf("name" to JsonPrimitive("Clip $n")))
        }
    }

    fun endClip() = finishClip(auto = null)

    fun cancelClip() {
        runningClip.value = null
        lastSaved.value = "Clip cancelled. Nothing saved."
    }

    private fun endClipIfRunning(why: String) {
        if (runningClip.value != null) finishClip(auto = why)
    }

    private fun finishClip(auto: String?) {
        val start = runningClip.value ?: return
        val file = statusState.value.file.ifEmpty { return }
        val pad = if (auto == null) Prefs.settings.value.padSeconds * 1000L else 0
        val end = maxOf(start + 1000, nowFileTimeMs() + pad)
        runningClip.value = null
        val clip = Clip(ClipStore.newId(), start, end, chapterWritten = Prefs.settings.value.chapters && statusState.value.container == "mp4")
        ClipStore.ensure(file, live = statusState.value.recActive)
        ClipStore.addClip(file, clip)
        vibrate(longArrayOf(0, 20, 60, 20))
        val n = ClipStore.recordings.value.firstOrNull { it.file == file }?.clips?.indexOfFirst { it.id == clip.id }?.plus(1) ?: 0
        val why = when (auto) {
            "net" -> ", ended when the connection dropped"
            "stop" -> ", ended when the recording stopped"
            "disconnect" -> ", ended when you disconnected"
            else -> ""
        }
        lastSaved.value = "Clip $n saved, ${tc(start)} → ${tc(end)} (${len(end - start)})$why"
        if (Prefs.settings.value.askNote && auto == null) pendingNote.value = file to clip
    }

    fun saveNote(note: String, tags: List<String>) {
        val (file, clip) = pendingNote.value ?: return
        val c = clip.copy(note = note.trim(), tags = tags)
        ClipStore.updateClip(file, c)
        pendingNote.value = null
        lastSaved.value = "Saved ${tc(c.startMs)} → ${tc(c.endMs)}" + (if (c.note.isNotEmpty()) " · ${c.note}" else "")
    }

    fun skipNote() { pendingNote.value = null }

    // =============================================================== OBS control

    fun recordStart() = command("record.start")
    fun recordStop() { stopRequestedHere = true; command("record.stop") }
    fun recordPause(pause: Boolean) = command(if (pause) "record.pause" else "record.resume")
    fun streamStop() = command("stream.stop")

    private fun command(cmd: String, args: Map<String, JsonPrimitive> = emptyMap()) {
        scope.launch {
            val r = connection?.command(cmd, args) ?: return@launch
            if (!r.ok()) toast("The PC couldn't do that (${r["error"]?.jsonPrimitive?.content ?: "error"}).")
        }
    }

    // =============================================================== desk

    fun sendDesk(text: String) {
        val t = text.trim().take(200)
        if (t.isEmpty()) return
        val m = DeskMessage(ClipStore.newId(), false, t)
        deskState.value = deskState.value + m
        command("desk.send", mapOf("id" to JsonPrimitive(m.id), "text" to JsonPrimitive(t)))
    }

    private suspend fun deskFeed(text: String) {
        connection?.command("desk.feed", mapOf("text" to JsonPrimitive(text)))
    }

    // =============================================================== video

    fun setVideo(on: Boolean) {
        if (on == videoOn.value) return
        videoOn.value = on
        if (on) {
            scope.launch {
                val r = connection?.command("video.start") ?: return@launch
                videoToken = r.data()?.get("token")?.jsonPrimitive?.content
                if (videoToken == null) { videoOn.value = false; toast("The PC couldn't start the live output."); return@launch }
                startVideoClient()
            }
        } else stopVideo()
    }

    fun attachSurface(surface: Surface?) {
        videoSurface = surface
        if (surface == null) { videoClient?.stop(); videoClient = null }
        else if (videoOn.value && videoToken != null) startVideoClient()
    }

    @Synchronized
    private fun startVideoClient() {
        val surface = videoSurface ?: return
        val token = videoToken ?: return
        val host = connection?.peerHost ?: return
        videoClient?.stop()
        videoClient = VideoClient(host, Protocol.VIDEO_PORT, token, surface,
            onFrame = { videoFrames.value = videoFrames.value + 1 },
            onEnded = { if (videoOn.value) scope.launch { delay(1000); if (videoOn.value) startVideoClient() } }
        ).also { it.start() }
    }

    private fun stopVideo() {
        val wasOn = videoOn.value
        videoOn.value = false
        videoClient?.stop()
        videoClient = null
        videoToken = null
        if (wasOn) command("video.stop")
    }

    // =============================================================== alerts

    fun dismissAlert(id: String) = clearAlert(id)

    private fun raise(a: Alert) {
        if (alertState.value.any { it.id == a.id }) return
        alertState.value = listOf(a) + alertState.value
        vibrate(if (a.severe) longArrayOf(0, 300, 120, 300, 120, 300) else longArrayOf(0, 200, 100, 200))
        Notifier.alert(app, a)
    }

    private fun clearAlert(id: String) {
        if (alertState.value.none { it.id == id }) return
        alertState.value = alertState.value.filterNot { it.id == id }
        Notifier.cancelAlert(app, id)
    }

    private suspend fun alertLoop() {
        while (scope.isActive) {
            delay(1000)
            if (linkState.value != LinkState.CONNECTED) continue
            val s = statusState.value
            if (s.streamActive) {
                if (minuteStartDropped < 0) minuteStartDropped = s.dropped
                if (++minuteTicks >= 60) {
                    healthState.value = (healthState.value + (s.dropped - minuteStartDropped).coerceAtLeast(0)).takeLast(30)
                    minuteTicks = 0
                    minuteStartDropped = s.dropped
                }
            }
            val st = Prefs.settings.value
            val lv = levelsState.value
            val now = SystemClock.elapsedRealtime()

            // silent mix
            val silent = engine?.receivingAudio() == true && lv.leftDb < -55 && lv.rightDb < -55
            if (st.silenceSeconds > 0 && s.recActive && silent) {
                if (silentSince == 0L) silentSince = now
                if (now - silentSince >= st.silenceSeconds * 1000L)
                    raise(Alert("silence", false, "Mix silent for ${st.silenceSeconds} s.", "A mic may be muted, or the mix fader down.", deskText = "Mic is muted"))
            } else {
                if (silentSince != 0L && alertState.value.any { it.id == "silence" }) { clearAlert("silence"); toast("Sound is back in the mix") }
                silentSince = 0
            }

            // clipping
            if (st.alertClipping && lv.clipped && now - lastClipAlertAt > 60_000) {
                lastClipAlertAt = now
                raise(Alert("clip", false, "Mix clipping.", "Peaks are hitting 0 dB, so the recording will distort.", deskText = "Check mic levels, it's clipping"))
            }

            // disk space
            if (s.recActive && s.diskFreeMb >= 0) {
                val bytesPerSec = if (s.fileTimeMs > 10_000) s.recBytes * 1000.0 / s.fileTimeMs else 0.0
                if (bytesPerSec > 0) {
                    val hoursLeft = s.diskFreeMb * 1024.0 * 1024.0 / bytesPerSec / 3600.0
                    if (hoursLeft < st.diskHours) raise(Alert("disk", true, "Disk nearly full.",
                        "About ${"%.1f".format(hoursLeft)} h left on the OBS PC.", deskText = "Disk is nearly full"))
                }
            }

            // dropped frames in the last minute
            if (s.streamActive && dropHistory.size > 2) {
                val minuteAgo = dropHistory.firstOrNull { it.first >= now - 60_000 } ?: dropHistory.first()
                val dropped = s.dropped - minuteAgo.second
                if (dropped > st.dropsPerMinute) raise(Alert("drops", false, "Stream dropping frames.",
                    "$dropped dropped in the last minute. Usually the venue's upload, not OBS.", deskText = "Stream is dropping frames"))
                else if (dropped == 0) clearAlert("drops")
            }
        }
    }

    fun deskAboutAlert(a: Alert) {
        a.deskText?.let { sendDesk(it) }
        toast("Sent to the desk")
    }

    // =============================================================== helpers

    private fun vibrate(pattern: LongArray) {
        if (!::app.isInitialized) return
        val v = ContextCompat.getSystemService(app, Vibrator::class.java) ?: return
        runCatching { v.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    private fun writeSidecar(file: String) {
        val rec = ClipStore.recordings.value.firstOrNull { it.file == file } ?: return
        if (rec.clips.isEmpty()) return
        val dir = File(app.filesDir, "exports").apply { mkdirs() }
        File(dir, file.substringBeforeLast('.') + ".csv").writeText(Exporter.csv(rec))
    }

    fun serviceIntent(): Intent = Intent(app, MonitorService::class.java)

    fun tc(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
    }

    fun len(ms: Long): String {
        val s = (ms + 500) / 1000
        return if (s < 60) "$s s" else "${s / 60} m ${"%02d".format(s % 60)} s"
    }
}
