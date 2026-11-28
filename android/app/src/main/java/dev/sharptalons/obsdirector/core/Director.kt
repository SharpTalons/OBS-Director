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
import dev.sharptalons.obsdirector.audio.Loudness
import dev.sharptalons.obsdirector.audio.Levels
import dev.sharptalons.obsdirector.audio.SessionCapture
import dev.sharptalons.obsdirector.net.Connection
import dev.sharptalons.obsdirector.net.Protocol
import dev.sharptalons.obsdirector.net.data
import dev.sharptalons.obsdirector.net.ok
import dev.sharptalons.obsdirector.service.MonitorService
import dev.sharptalons.obsdirector.ui.FeedbackTokens
import dev.sharptalons.obsdirector.service.Notifier
import dev.sharptalons.obsdirector.video.VideoClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlinx.coroutines.withContext

/**
 * The app's single source of truth: the link to the plugin, what OBS is doing, the mix, clips,
 * alerts and desk messages. The UI observes it; the foreground service keeps it alive with the
 * screen off.
 */
@SuppressLint("StaticFieldLeak")
/** Silence from OBS for this long (away, or a long drop) ends a session; the next sound starts a new one. */
private const val SESSION_BREAK_MS = 10 * 60_000L

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
    /** Set while OBS is asking someone at the PC to press Allow for this phone. */
    private val approvalState = MutableStateFlow<String?>(null)
    val awaitingApproval: StateFlow<String?> get() = approvalState
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
    /** The phone's own copy of this session (see [SessionCapture]); clips are timed against it. */
    @Volatile private var capture: SessionCapture? = null
    @Volatile private var lastPcmAt = 0L

    // ---- clips ----
    private val runningClip = MutableStateFlow<Long?>(null)
    /** session time where the running clip starts, or null */
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
    private val deskUnread = MutableStateFlow(0)
    /** Messages from others since the Desk tab was last open. */
    val deskUnreadCount: StateFlow<Int> get() = deskUnread
    @Volatile private var deskOpen = false
    /** The Desk tab is on screen: new messages count as read. */
    private val deskAlert = MutableStateFlow(0)
    /** The most urgent unread line: 0 messages only, 1 an alert, 2 a severe alert. */
    val deskUnreadAlert: StateFlow<Int> get() = deskAlert
    fun setDeskOpen(open: Boolean) { deskOpen = open; if (open) { deskUnread.value = 0; deskAlert.value = 0 } }
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

    /** Follows whatever output the phone is using, and reports it. */
    lateinit var router: dev.sharptalons.obsdirector.audio.OutputRouter
        private set

    fun init(context: Context) {
        app = context.applicationContext
        router = dev.sharptalons.obsdirector.audio.OutputRouter(app).also { it.start() }
        // the output chosen in Settings follows the setting as it changes
        scope.launch { Prefs.settings.collect { router.preferredKey = it.outputPref } }
        scope.launch { alertLoop() }
        scope.launch { clipListLoop() }
        // While a clip plays, turn the mix down by the share Settings say (80% unless changed), so
        // the clip can be heard over it; back up when it stops.
        scope.launch {
            kotlinx.coroutines.flow.combine(dev.sharptalons.obsdirector.audio.ClipPlayer.playback, Prefs.settings) { p, s -> p.sounding to s.duckPct }
                .collect { (playing, pct) -> engine?.duck = if (playing) 1f - pct.coerceIn(50, 100) / 100f else 1f }
        }
        // the live session's length and OBS stretches, kept up to date in the store
        scope.launch { while (true) { delay(5000); saveSession() } }
        recoverClip()
    }

    // ---- a clip that was running when the app stopped ----

    @kotlinx.serialization.Serializable
    private data class RunningClip(val session: String, val startMs: Long, val name: String, val number: Int, val chapter: Boolean)

    private val runningFile get() = File(app.filesDir, "running-clip.json")

    /** Notes the running clip on disk, so it is saved even if the app is killed before it ends. */
    private fun keepRunning(c: RunningClip?) {
        runCatching {
            if (c == null) runningFile.delete()
            else runningFile.writeText(kotlinx.serialization.json.Json.encodeToString(RunningClip.serializer(), c))
        }
    }

    /**
     * The app was closed, killed or switched off while a clip ran: the clip is saved now, ending
     * where the saved sound of its session ends (the sound is written as it comes in, so little
     * or nothing is lost).
     */
    private fun recoverClip() {
        val f = runningFile
        if (!f.exists()) return
        val c = runCatching { kotlinx.serialization.json.Json.decodeFromString(RunningClip.serializer(), f.readText()) }.getOrNull()
        f.delete()
        c ?: return
        val r = ClipStore.recordings.value.firstOrNull { it.file == c.session } ?: return
        if (r.clips.any { it.startMs == c.startMs }) return
        val end = maxOf(c.startMs + 1000, r.durationMs)
        val clip = Clip(ClipStore.newId(), c.startMs, end, name = c.name, number = c.number, chapterWritten = c.chapter, recovered = true)
        ClipStore.addClip(r.file, clip)
        lastSaved.value = "${clip.fullName()} saved after the app closed, ${len(end - c.startMs)} long"
        toast("${clip.fullName()} was running when the app closed. It's saved in Recordings.")
    }

    /** The app's task was swiped away: a running clip is ended and saved before the app goes. */
    fun onAppClosing() {
        endClipIfRunning("closed")
        saveSession()
    }

    /** What each recording's clip list looked like the last time it went to the PC. */
    private val sentClipLists = HashMap<String, Int>()

    /**
     * Keeps each recording's clip list (EDL and CSV) saved next to the recording on the PC, when
     * Settings say so: a second after the clips change, and again after a reconnect. The plugin
     * only writes next to recordings OBS made while it was running, and refuses anything else.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private suspend fun clipListLoop() {
        kotlinx.coroutines.flow.combine(ClipStore.recordings, linkState, Prefs.settings) { recs, link, s -> Triple(recs, link, s.pcClipLists) }
            .debounce(1500)
            .collect { (recs, link, on) ->
                if (!on || link != LinkState.CONNECTED) return@collect
                val conn = connection ?: return@collect
                // each OBS recording gets the clips (or their parts) from every session that overlaps it
                val byFile = LinkedHashMap<String, MutableList<Clip>>()
                for (r in recs) for (f in r.obsFiles()) byFile.getOrPut(f) { ArrayList() } += r.forObsFile(f).clips
                for ((file, clips) in byFile) {
                    if (clips.isEmpty()) continue
                    val view = Recording(file = file, startedAt = 0, clips = clips.sortedBy { it.startMs })
                    val sig = view.clips.hashCode()
                    if (sentClipLists[file] == sig) continue
                    sentClipLists[file] = sig
                    val res = conn.command("clips.write", mapOf(
                        "file" to JsonPrimitive(file),
                        "edl" to JsonPrimitive(Exporter.edl(view)),
                        "csv" to JsonPrimitive(Exporter.csv(view)),
                    ))
                    if (res.ok()) {
                        val pcName = pcState.value?.name ?: "the PC"
                        for (r in recs) if (file in r.obsFiles() && r.savedOnPc != pcName) ClipStore.update(r.file) { it.copy(savedOnPc = pcName) }
                    } else if (res["error"]?.jsonPrimitive?.content == "not_connected" || res["error"]?.jsonPrimitive?.content == "timeout") {
                        sentClipLists.remove(file) // try again on the next change or reconnect
                    }
                }
            }
    }

    fun toast(msg: String) { toastState.value = msg }
    fun clearToast() { toastState.value = null }

    // =============================================================== connect

    /** Connect to [target]. The first time, OBS asks someone at the PC to press Allow. */
    fun connect(target: FoundPc) {
        disconnect(keepError = false)
        val s = Prefs.settings.value
        pcState.value = target
        linkError.value = null
        linkState.value = LinkState.CONNECTING
        welcomed = false
        sentClipLists.clear()
        listenState.value = s.autoListen
        Prefs.update { it.copy(lastIp = target.ip, lastPort = target.port, lastPcName = target.name) }

        val conn = Connection(target.ip, target.port, s.deviceId, s.deviceName, object : Connection.Listener {
            override fun onWelcome(info: JsonObject) = onWelcomed(info)
            override fun onMessage(msg: JsonObject) = onPluginMessage(msg)
            override fun onLost(reason: String) = onLinkLost()
            override fun onPending(message: String) { approvalState.value = message }
            override fun onRejected(message: String) {
                approvalState.value = null
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
        endSession()
        linkState.value = LinkState.DISCONNECTED
        approvalState.value = null
        if (!keepError) linkError.value = null
        alertState.value = emptyList()
        if (::app.isInitialized) MonitorService.stop(app)
    }

    private fun onWelcomed(info: JsonObject) {
        welcomed = true
        approvalState.value = null
        pluginVersion = info["plugin"]?.jsonPrimitive?.content ?: ""
        val name = info["pc"]?.jsonPrimitive?.content
        // Remember the PC's real name, so a PC typed in by address shows its name next time.
        if (!name.isNullOrBlank()) Prefs.update { it.copy(lastPcName = name) }
        pcState.value = pcState.value?.copy(name = name ?: pcState.value?.name ?: "OBS PC", plugin = pluginVersion,
            obs = info["obs"]?.jsonPrimitive?.content ?: "")
        val wasLost = linkState.value == LinkState.LOST
        if (wasLost) {
            reconnectedGap.value = SystemClock.elapsedRealtime() - lostSince.value
            scope.launch { delay(5000); reconnectedGap.value = 0 }
            if (Prefs.settings.value.resumeListen && listenWasOn) listenState.value = true
            clearAlert("net")
            val gap = reconnectedGap.value / 1000
            chatNote(DeskMessage("note-${System.currentTimeMillis()}", false, "Reconnected after ${gap / 60}:${"%02d".format(gap % 60)}. Listening again.", kind = "note"))
        }
        linkState.value = LinkState.CONNECTED
        retries.value = 0
        MonitorService.start(app)
        startAudio()
        scope.launch {
            loadDeskHistory()
            if (Prefs.settings.value.deskPresence) deskFeed("${Prefs.settings.value.deviceName} connected")
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
        vibrate(FeedbackTokens.LINK_LOST)
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
        // clips made while OBS isn't recording take the name OBS would give next; it moves on once OBS has recorded
        if (prev.recActive != s.recActive) idleName = null
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
            "aac.progress" -> {
                val file = d["file"]?.jsonPrimitive?.content ?: return
                val job = aacState.value[file] ?: return
                if (job.stage == "converting") setAac(file, job.copy(percent = d["percent"]?.jsonPrimitive?.content?.toIntOrNull() ?: job.percent))
            }
            "aac.ready" -> {
                val file = d["file"]?.jsonPrimitive?.content ?: return
                if (aacState.value[file]?.stage == "converting") downloadAac(file, d)
            }
            "aac.failed" -> {
                val file = d["file"]?.jsonPrimitive?.content ?: return
                if (aacState.value[file]?.stage == "converting") failAac(file, d["error"]?.jsonPrimitive?.content ?: "")
            }
            "desk.message" -> {
                val m = chatMessage(d) ?: return
                val list = deskState.value
                if (list.any { it.id == m.id }) {
                    // our own message coming back: it reached OBS
                    deskState.value = list.map { if (it.id == m.id) it.copy(at = m.at, state = if (m.state == "seen") "seen" else "sent") else it }
                } else {
                    deskState.value = (list + m).takeLast(500)
                    if (!m.mine) {
                        if (!deskOpen) deskUnread.value = deskUnread.value + 1
                        vibrate(FeedbackTokens.MESSAGE)
                        if (!deskOpen) Notifier.desk(app, "${m.from}: ${m.text}")
                    }
                }
            }
            "desk.seen" -> {
                val ids = (d["ids"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.content }?.toSet() ?: return
                deskState.value = deskState.value.map { if (it.mine && it.id in ids) it.copy(state = "seen", seenAt = System.currentTimeMillis()) else it }
            }
        }
    }

    /** A recording stopping doesn't touch a running clip: clips are timed on the session, not the file. */
    private fun onRecordingEnded(file: String, durationMs: Long) {
        // an entry from before 0.9.0, still marked as recording
        if (file.isNotEmpty()) ClipStore.recordings.value.firstOrNull { it.file == file && !it.session }?.let {
            ClipStore.update(file) { r -> r.copy(live = false, durationMs = durationMs) }
        }
    }

    // =============================================================== audio

    private fun startAudio() {
        if (engine == null) {
            engine = AudioEngine { pcm, fileTimeUs, recording, gap -> onPcm(pcm, fileTimeUs, recording, gap) }.also { e ->
                e.volume = Loudness.gain(Prefs.settings.value.volumePct)
                e.router = router
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

    /** 1–100% on the loudness scale. Heard at once while dragging; saved with [save]. */
    fun setVolume(percent: Int, save: Boolean = true) {
        val p = percent.coerceIn(1, 100)
        engine?.volume = Loudness.gain(p)
        if (save) Prefs.update { it.copy(volumePct = p) }
    }

    fun setBuffer(ms: Int) {
        engine?.targetBufferMs = ms
        Prefs.update { it.copy(bufferMs = ms) }
    }

    /** Audio thread: hand packets to the session recorder for the recording they belong to. */
    /** Hours of recording the OBS PC's disk still has room for, at the current bitrate; null if unknown. */
    fun diskHoursLeft(s: Status): Double? {
        val bytesPerSec = if (s.fileTimeMs > 10_000) s.recBytes * 1000.0 / s.fileTimeMs else 0.0
        if (bytesPerSec <= 0 || s.diskFreeMb < 0) return null
        return s.diskFreeMb * 1048576.0 / bytesPerSec / 3600.0
    }

    // Wi-Fi quality: every audio gap (packets that never arrived) is a dropout.
    private val dropoutTimes = ArrayDeque<Long>()

    /** Audio dropouts in the last minute, for the Wi-Fi quality line. */
    fun dropoutsLastMinute(): Int = synchronized(dropoutTimes) {
        val cutoff = SystemClock.elapsedRealtime() - 60_000
        while (dropoutTimes.isNotEmpty() && dropoutTimes.first() < cutoff) dropoutTimes.removeFirst()
        dropoutTimes.size
    }

    private fun onPcm(pcm: ShortArray, fileTimeUs: Long, recording: Boolean, gap: Boolean) {
        if (gap) synchronized(dropoutTimes) { dropoutTimes.addLast(SystemClock.elapsedRealtime()) }
        val now = SystemClock.elapsedRealtime()
        var cap = capture
        // back after a long time away: that was another session
        if (cap != null && now - lastPcmAt > SESSION_BREAK_MS) { endSession(); cap = null }
        lastPcmAt = now
        if (cap == null) cap = beginSession()
        cap.write(pcm, gap, recording, statusState.value.file, fileTimeUs / 1000)
    }

    /** A new session starts with the first sound after connecting. */
    private fun beginSession(): SessionCapture {
        val id = "session-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        ClipStore.startSession(id)
        val cap = SessionCapture(id, ClipStore.sessionsDir, Prefs.settings.value.saveAudio) { saveSession(withEnvelope = true) }
        capture = cap
        return cap
    }

    /** Writes the live session's length, OBS stretches and audio pieces to the store (and its loudness, now and then). */
    private fun saveSession(withEnvelope: Boolean = false) {
        val cap = capture ?: return
        val spans = cap.spans(); val segs = cap.segments(); val pos = cap.posMs
        ClipStore.update(cap.id) { r ->
            r.copy(durationMs = pos, obs = spans, segments = segs, envelope = if (withEnvelope) cap.envelope() else r.envelope)
        }
    }

    private fun endSession() {
        val cap = capture ?: return
        capture = null
        scope.launch(Dispatchers.IO) {
            cap.finish()
            ClipStore.update(cap.id) { r ->
                r.copy(live = false, durationMs = cap.posMs, obs = cap.spans(), segments = cap.segments(), envelope = cap.envelope())
            }
        }
    }

    /** The loudness of the live session, for the trimmer, while it's still going. */
    fun liveEnvelope(file: String): List<Float>? = capture?.takeIf { it.id == file }?.envelope()

    /** How long the live session [file] is so far, or null if it isn't the live one. */
    fun liveLengthMs(file: String): Long? = capture?.takeIf { it.id == file }?.posMs

    /** The live session, if there is one. */
    val sessionId: String? get() = capture?.id

    /** Session time of what you are hearing now (the newest sound, less what is queued to play). */
    fun heardSessionMs(): Long {
        val cap = capture ?: return 0
        return (cap.posMs - (engine?.queuedMs() ?: 0)).coerceAtLeast(0)
    }

    /**
     * The saved audio of [r] covering [fromMs]..[toMs], as files and where each starts. For the
     * live session, the piece being written is finished first if it holds any of it, so a clip
     * can be played or shared moments after it is made.
     */
    suspend fun sessionAudio(r: Recording, fromMs: Long, toMs: Long): List<Pair<File, Long>> = withContext(Dispatchers.IO) {
        val cap = capture?.takeIf { it.id == r.file }
        if (cap != null) {
            cap.flush(toMs)
            ClipStore.audioFor(r, fromMs, toMs, cap.segments())
        } else ClipStore.audioFor(r, fromMs, toMs)
    }

    /** Plays saved audio of [r] (see [sessionAudio]); says why when there is none. */
    fun playSaved(r: Recording, key: String, fromMs: Long, toMs: Long, atMs: Long = fromMs) {
        scope.launch {
            val pieces = sessionAudio(r, fromMs, toMs)
            when {
                pieces.isEmpty() -> toast(if (Prefs.settings.value.saveAudio) "No audio saved for this part." else "No audio saved: Save the session audio is off in Settings.")
                !dev.sharptalons.obsdirector.audio.ClipPlayer.play(key, pieces, fromMs, toMs, atMs) -> toast("Couldn't play this audio.")
            }
        }
    }

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

    /**
     * Starts a clip, whether or not OBS is recording: clips are timed on the session (the phone's
     * own copy of the mix), so starting and stopping the recording never touches them, and
     * starting or ending a clip never touches the recording. While OBS records, the clip also
     * gets a chapter marker in the recording, if Settings say so.
     */
    fun startClip() {
        val s = statusState.value
        if (runningClip.value != null || linkState.value != LinkState.CONNECTED || capture == null) return
        // a note still waiting from the clip before: that clip's end marker goes in now
        if (pendingNote.value != null) skipNote()
        // it starts at your tap; trim it afterwards to take in more (the sound around it is kept)
        runningClip.value = heardSessionMs().coerceAtLeast(0)
        val (name, number) = nextClip()
        runningName = name to number
        keepRunning(RunningClip(capture!!.id, runningClip.value!!, name, number, Prefs.settings.value.chapters && s.recActive && s.container == "mp4"))
        lastSaved.value = null
        vibrate(FeedbackTokens.CLIP_START)
        if (Prefs.settings.value.chapters && s.recActive) scope.launch {
            connection?.command("chapter.add", mapOf("name" to JsonPrimitive("Clip $number")))
        }
    }

    /** The name and number the clip started now gets ("2026-11-28 19-02-11", 3). */
    fun nextClip(): Pair<String, Int> {
        val s = statusState.value
        val name = when {
            s.recActive && s.file.isNotBlank() -> s.file.substringBeforeLast('.')
            s.nextName.isNotBlank() -> s.nextName
            else -> java.text.SimpleDateFormat("yyyy-MM-dd HH-mm-ss", java.util.Locale.US).format(java.util.Date())
        }
        // the name OBS would give changes every second: clips made before OBS records keep the first one
        val base = if (s.recActive) name else (idleName?.takeIf { it.second == capture?.id }?.first ?: name.also { idleName = it to capture?.id })
        val n = ClipStore.recordings.value.sumOf { r -> r.clips.count { it.name == base } }
        val max = ClipStore.recordings.value.flatMap { it.clips }.filter { it.name == base }.maxOfOrNull { it.number } ?: 0
        return base to maxOf(n, max) + 1
    }

    /** What the next clip will be called, for Monitor. */
    fun nextClipName(): String = runningName?.let { "${it.first} clip ${it.second}" } ?: nextClip().let { "${it.first} clip ${it.second}" }

    private var runningName: Pair<String, Int>? = null
    /** the name OBS would have given, kept for the clips of this session made while OBS isn't recording */
    private var idleName: Pair<String, String?>? = null
    /** a clip's end marker waits for its note: (number, whether OBS was recording when the clip ended) */
    private var endMarker: Pair<Int, Boolean>? = null
    private var endMarkerJob: kotlinx.coroutines.Job? = null

    /** How long the running clip is so far. */
    fun runningClipMs(): Long = runningClip.value?.let { (heardSessionMs() - it).coerceAtLeast(0) } ?: 0

    fun endClip() = finishClip(auto = null)

    fun cancelClip() {
        runningClip.value = null
        keepRunning(null)
        lastSaved.value = "Clip cancelled. Nothing saved."
    }

    private fun endClipIfRunning(why: String) {
        if (runningClip.value != null) finishClip(auto = why)
    }

    private fun finishClip(auto: String?) {
        val start = runningClip.value ?: return
        val cap = capture ?: run { runningClip.value = null; return }
        val file = cap.id
        val end = maxOf(start + 1000, heardSessionMs())
        runningClip.value = null
        keepRunning(null)
        val (name, number) = runningName ?: nextClip()
        runningName = null
        val recording = statusState.value.recActive
        val clip = Clip(ClipStore.newId(), start, end, chapterWritten = Prefs.settings.value.chapters && statusState.value.container == "mp4" && recording,
            name = name, number = number)
        saveSession()
        ClipStore.addClip(file, clip)
        // close the audio piece being written, so the new clip can be played straight away
        scope.launch(Dispatchers.IO) { cap.flush(end); saveSession() }
        vibrate(FeedbackTokens.CLIP_SAVED)
        val why = when (auto) {
            "net" -> ", ended when the connection dropped"
            "disconnect" -> ", ended when you disconnected"
            "closed" -> ", ended when the app closed"
            else -> ""
        }
        lastSaved.value = "${clip.fullName()} saved, ${len(end - start)} long$why"
        // The end marker ("Clip 2 – Choir intro") waits for the note, a minute at most: OBS can
        // only mark the moment it's told, and can't rename a marker afterwards.
        endMarker = if (Prefs.settings.value.chapters && recording) number to true else null
        endMarkerJob?.cancel()
        if (Prefs.settings.value.askNote && auto == null) {
            pendingNote.value = file to clip
            endMarkerJob = scope.launch { delay(60_000); if (pendingNote.value?.second?.id == clip.id) skipNote() }
        } else {
            pendingNote.value = null
            writeEndMarker("")
        }
    }

    /** Adds the clip's end marker to the OBS recording, with its note, if OBS is still recording. */
    private fun writeEndMarker(note: String) {
        val (n, _) = endMarker ?: return
        endMarker = null
        endMarkerJob?.cancel()
        if (!statusState.value.recActive) return
        val name = if (note.isBlank()) "Clip $n end" else "Clip $n – ${note.trim()}"
        scope.launch { connection?.command("chapter.add", mapOf("name" to JsonPrimitive(name.take(120)))) }
    }

    fun saveNote(note: String, tags: List<String>) {
        val (file, clip) = pendingNote.value ?: return
        val c = clip.copy(note = note.trim(), tags = tags)
        ClipStore.updateClip(file, c)
        pendingNote.value = null
        writeEndMarker(c.note)
        lastSaved.value = (c.fullName() ?: "Clip") + " saved"
    }

    fun skipNote() {
        pendingNote.value = null
        writeEndMarker("")
    }

    // =============================================================== OBS recordings and their AACs

    /**
     * The OBS recordings the phone was connected for, newest first, from the sessions' OBS spans
     * (and entries from before 0.9.0, which were one recording each).
     */
    fun obsRecordings(recs: List<Recording>, s: Status): List<ObsRecording> {
        val found = LinkedHashMap<String, ObsRecording>()
        fun add(o: ObsRecording) {
            val had = found[o.file]
            found[o.file] = if (had == null) o else had.copy(startedAt = minOf(had.startedAt, o.startedAt), durationMs = maxOf(had.durationMs, o.durationMs))
        }
        for (r in recs) {
            if (!r.session) { add(ObsRecording(r.file, r.startedAt, r.durationMs, false)); continue }
            for (span in r.obs) add(ObsRecording(span.file, r.startedAt + span.fromMs - span.fileStartMs, span.fileStartMs + span.toMs - span.fromMs, false))
        }
        if (s.recActive && s.file.isNotBlank()) add(ObsRecording(s.file, System.currentTimeMillis() - s.fileTimeMs, s.fileTimeMs, true))
        return found.values.map { it.copy(live = s.recActive && it.file == s.file && linkState.value == LinkState.CONNECTED) }.sortedByDescending { it.startedAt }
    }

    /** Saving an OBS recording as AAC: converting on the PC, then copying to the phone. */
    data class AacJob(val stage: String, val percent: Int)

    private val aacState = MutableStateFlow<Map<String, AacJob>>(emptyMap())
    val aacJobs: StateFlow<Map<String, AacJob>> get() = aacState
    private val aacCopies = HashMap<String, kotlinx.coroutines.Job>()
    private fun setAac(file: String, job: AacJob?) { aacState.value = if (job == null) aacState.value - file else aacState.value + (file to job) }

    /** Where an OBS recording's AAC is kept on the phone. */
    fun aacFile(file: String): File = File(File(app.filesDir, "aac").apply { mkdirs() }, file.substringBeforeLast('.') + ".m4a")

    /** Asks the PC to turn a finished recording's sound into AAC (with OBS's own FFmpeg), then copies it here. */
    fun saveAac(file: String) {
        if (aacState.value[file] != null) return
        setAac(file, AacJob("converting", 0))
        scope.launch {
            val r = connection?.command("recording.aac", mapOf("file" to JsonPrimitive(file)))
            if (r == null || !r.ok()) return@launch failAac(file, r?.get("error")?.jsonPrimitive?.content ?: "not_connected")
            val d = r.data()
            if (d?.get("state")?.jsonPrimitive?.content == "ready") downloadAac(file, d)
        }
    }

    fun cancelAac(file: String) {
        val job = aacState.value[file] ?: return
        aacCopies.remove(file)?.cancel()
        if (job.stage == "converting") scope.launch { connection?.command("recording.aac.cancel", mapOf("file" to JsonPrimitive(file))) }
        setAac(file, null)
    }

    fun deleteAac(file: String) { aacFile(file).delete() }

    private fun failAac(file: String, error: String) {
        setAac(file, null)
        if (error == "cancelled") return
        toast(when (error) {
            "still_recording" -> "OBS is still making that recording. Save it once it stops."
            "aac_unavailable" -> "The PC can't make AAC files: OBS's FFmpeg isn't the version the plugin expects. Update OBS and the plugin."
            "unknown_recording" -> "The PC no longer knows that recording (OBS was restarted since)."
            "recording_missing" -> "That recording isn't on the PC any more."
            "no_audio" -> "That recording has no sound to save."
            "busy" -> "The PC is making another AAC file. Try again once it's done."
            "unknown_command" -> "Update the OBS Director plugin on the PC to save AAC files."
            "not_connected", "timeout" -> "Not connected to the PC."
            else -> "The PC couldn't make the AAC file ($error)."
        })
    }

    /** Copies the finished AAC from the PC's web port, a piece at a time, with progress. */
    private fun downloadAac(file: String, d: JsonObject) {
        val token = d["token"]?.jsonPrimitive?.content ?: return failAac(file, "bad_reply")
        val port = d["port"]?.jsonPrimitive?.content?.toIntOrNull() ?: 4470
        val ip = pcState.value?.ip ?: return failAac(file, "not_connected")
        setAac(file, AacJob("copying", 0))
        aacCopies[file] = scope.launch(Dispatchers.IO) {
            val out = aacFile(file)
            val part = File(out.path + ".part")
            val ok = runCatching {
                java.net.Socket().use { sock ->
                    sock.connect(java.net.InetSocketAddress(ip, port), 5000)
                    sock.soTimeout = 20_000
                    sock.getOutputStream().apply {
                        write("GET /aac/$token HTTP/1.1\r\nHost: $ip:$port\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                    val input = java.io.BufferedInputStream(sock.getInputStream(), 1 shl 16)
                    fun line(): String {
                        val sb = StringBuilder()
                        while (true) { val b = input.read(); if (b < 0 || b == '\n'.code) break; if (b != '\r'.code) sb.append(b.toChar()) }
                        return sb.toString()
                    }
                    check(line().split(' ').getOrNull(1) == "200") { "not found" }
                    var length = -1L
                    while (true) {
                        val h = line()
                        if (h.isEmpty()) break
                        if (h.lowercase().startsWith("content-length:")) length = h.substringAfter(':').trim().toLongOrNull() ?: -1
                    }
                    var got = 0L
                    var shown = -1
                    part.outputStream().use { o ->
                        val buf = ByteArray(1 shl 16)
                        while (isActive) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            got += n
                            val pct = if (length > 0) (got * 100 / length).toInt() else 0
                            if (pct != shown) { shown = pct; setAac(file, AacJob("copying", pct)) }
                        }
                    }
                    check(isActive && (length < 0 || got == length)) { "incomplete" }
                }
                check(part.renameTo(out))
            }.isSuccess
            aacCopies.remove(file)
            if (!ok) part.delete()
            if (aacState.value[file]?.stage != "copying") return@launch // cancelled
            setAac(file, null)
            if (ok) toast("${file.substringBeforeLast('.')}.m4a saved on this phone") else toast("Copying the AAC file from the PC stopped. Try again.")
        }
    }

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
        val t = text.trim().take(1000)
        if (t.isEmpty()) return
        val m = DeskMessage(java.util.UUID.randomUUID().toString(), false, t, from = Prefs.settings.value.deviceName, mine = true, state = "sending")
        deskState.value = deskState.value + m
        postDesk(m)
    }

    /** Send again a message that didn't go. */
    fun retryDesk(id: String) {
        val m = deskState.value.firstOrNull { it.id == id && it.state == "failed" } ?: return
        deskState.value = deskState.value.map { if (it.id == id) it.copy(state = "sending") else it }
        postDesk(m)
    }

    private fun postDesk(m: DeskMessage) {
        scope.launch {
            val r = connection?.command("desk.send", mapOf("id" to JsonPrimitive(m.id), "text" to JsonPrimitive(m.text)))
            val ok = r?.ok() == true
            deskState.value = deskState.value.map {
                if (it.id != m.id) it
                else if (ok) it.copy(state = if (it.state == "seen") "seen" else "sent") else it.copy(state = "failed")
            }
        }
    }

    private fun chatMessage(o: JsonObject): DeskMessage? {
        val id = o["id"]?.jsonPrimitive?.content ?: return null
        val text = o["text"]?.jsonPrimitive?.content ?: return null
        val prim = { k: String -> o[k]?.jsonPrimitive?.content }
        val seen = prim("seen") == "true"
        return DeskMessage(
            id = id, fromDesk = prim("desk") == "true", text = text,
            at = prim("at")?.toDoubleOrNull()?.toLong() ?: System.currentTimeMillis(),
            from = prim("from") ?: "", mine = prim("mine") == "true", state = if (seen) "seen" else "sent",
        )
    }

    /** After connecting: the chat so far, from OBS, plus anything of ours that didn't go. */
    private suspend fun loadDeskHistory() {
        val r = connection?.command("desk.history") ?: return
        if (!r.ok()) return
        val msgs = (r.data()?.get("messages") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.let(::chatMessage) } ?: return
        val ids = msgs.map { it.id }.toSet()
        val pending = deskState.value.filter { it.mine && it.state != "sent" && it.state != "seen" && it.id !in ids }
        // this phone's own alert cards and notes stay, in time order
        val local = deskState.value.filter { it.kind != "message" }
        deskState.value = (msgs + pending + local).sortedBy { it.at }.takeLast(500)
        pending.filter { it.state == "failed" || it.state == "sending" }.forEach { postDesk(it) }
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

    /** An alert: a notification, and a card in the Desk chat (on this phone only) with its actions. */
    private fun raise(a: Alert) {
        if (alertState.value.any { it.id == a.id }) return
        alertState.value = listOf(a) + alertState.value
        vibrate(if (a.severe) FeedbackTokens.ALERT_SEVERE else FeedbackTokens.ALERT)
        Notifier.alert(app, a)
        chatNote(DeskMessage("alert-${a.id}-${System.currentTimeMillis()}", false, a.body, from = a.title, kind = "alert",
            severe = a.severe, deskText = a.deskText, offerStart = a.offerStartRecording))
    }

    /** A line in this phone's chat that isn't a message: an alert card, or a connection note. */
    fun chatNote(m: DeskMessage) {
        deskState.value = (deskState.value + m).takeLast(500)
        // a connection note is shown, but doesn't count as unread
        if (!deskOpen && m.kind != "note") {
            deskUnread.value = deskUnread.value + 1
            if (m.kind == "alert") deskAlert.value = maxOf(deskAlert.value, if (m.severe) 2 else 1)
        }
    }

    private fun clearAlert(id: String) {
        if (alertState.value.none { it.id == id }) return
        alertState.value = alertState.value.filterNot { it.id == id }
        Notifier.cancelAlert(app, id)
    }

    private var batteryWarnedAt = -1

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

            // the phone's own battery: once each time it drops another 5% below the warning level
            val b = PhoneBattery.battery.value
            if (st.batteryWarn > 0 && !b.charging && b.percent in 1..st.batteryWarn) {
                if (batteryWarnedAt < 0 || b.percent <= batteryWarnedAt - 5) {
                    batteryWarnedAt = b.percent
                    clearAlert("battery")
                    raise(Alert("battery", b.percent <= 5, "Phone at ${b.percent}%.", "Plug it in: the session audio is still saving."))
                }
            } else if (b.charging || b.percent > st.batteryWarn) { batteryWarnedAt = -1; clearAlert("battery") }

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

    /**
     * Vibrates at the strength chosen in Settings (off, light, medium, strong). Patterns are
     * off/on pairs; phones that can't vary the strength get shorter buzzes for light.
     */
    fun vibrate(pattern: LongArray) {
        if (!::app.isInitialized) return
        val level = Prefs.settings.value.haptics.coerceIn(0, 3)
        if (level == 0) return
        val v = ContextCompat.getSystemService(app, Vibrator::class.java) ?: return
        // stronger settings buzz both harder (where the phone can) and longer
        val scale = FeedbackTokens.BUZZ_LENGTH_SCALE[level - 1]
        val timing = LongArray(pattern.size) { i -> if (i % 2 == 1) (pattern[i] * scale).toLong().coerceAtLeast(10) else pattern[i] }
        runCatching {
            if (v.hasAmplitudeControl()) {
                val strength = FeedbackTokens.BUZZ_AMPLITUDE[level - 1]
                v.vibrate(VibrationEffect.createWaveform(timing, IntArray(pattern.size) { i -> if (i % 2 == 1) strength else 0 }, -1))
            } else {
                v.vibrate(VibrationEffect.createWaveform(timing, -1))
            }
        }
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
