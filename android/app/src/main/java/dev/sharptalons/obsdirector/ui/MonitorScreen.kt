package dev.sharptalons.obsdirector.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.sharptalons.obsdirector.core.ClipStore
import dev.sharptalons.obsdirector.core.Director
import dev.sharptalons.obsdirector.core.LinkState
import dev.sharptalons.obsdirector.core.Prefs
import dev.sharptalons.obsdirector.core.Tag
import kotlinx.coroutines.delay

/** Ticks once a second so running timecodes update between status messages. */
@Composable
fun rememberTicker(periodMs: Long = 250): Long {
    var t by remember { mutableLongStateOf(0L) }
    LaunchedEffect(periodMs) { while (true) { delay(periodMs); t++ } }
    return t
}

@Composable
fun MonitorScreen(onOpenClips: () -> Unit) {
    val status by Director.status.collectAsState()
    val link by Director.link.collectAsState()
    val recordings by ClipStore.recordings.collectAsState()
    val settings by Prefs.settings.collectAsState()
    val clipStart by Director.clipStart.collectAsState()
    @Suppress("UNUSED_VARIABLE") val tick = rememberTicker()
    val lost = link == LinkState.LOST
    val live = recordings.firstOrNull { it.file == status.file }
    val fileTime = if (status.recActive && !lost) Director.nowFileTimeMs() else status.fileTimeMs

    // the one-line summary
    val summary = buildString {
        if (lost) append("Last heard: recording ${status.file} at ${Director.tc(status.fileTimeMs)}. Nothing here is live until the connection is back; OBS keeps recording on the PC.")
        else {
            append(if (status.recActive) (if (status.recPaused) "Recording paused " else "Recording ") + "${status.file} for ${Director.tc(fileTime)}" else "Not recording")
            append(if (status.streamActive) ", stream live" else ", stream off air")
            append(", ${live?.clips?.size ?: 0} clips so far.")
            if (clipStart != null) append(" A clip is running from ${Director.tc(clipStart!!)}.")
        }
    }
    Text(summary, style = Type.finding)
    Spacer(Modifier.height(16.dp))

    // ---------------- Recording ----------------
    DCard {
        CardHeader("Recording", Icons.Outlined.FiberManualRecord) {
            when {
                !status.recActive -> Pill("Stopped", PillKind.IDLE)
                status.recPaused -> Pill("Paused", PillKind.WARN)
                else -> Pill("Rec", PillKind.REC)
            }
        }
        Text(Director.tc(fileTime), style = Type.timecode.copy(color = if (status.recActive && !lost) T.text else T.muted))
        Spacer(Modifier.height(10.dp))
        if (status.file.isNotEmpty()) Text(status.file, style = Type.body.copy(fontSize = 15.sp))
        Text(
            if (status.recActive) listOfNotNull(Director.pc.value?.name, status.container.uppercase().takeIf { it.isNotBlank() }).joinToString(" · ")
            else "Stopped. One tap starts a new file.", style = Type.small,
        )
        if (settings.chapters && status.recActive) Text(
            if (status.container == "mp4") "${live?.clips?.size ?: 0} clips written into the file as chapter markers"
            else "Chapters need OBS to record in Hybrid MP4; clips go in the export instead", style = Type.small,
        )
        val bytesPerSec = if (status.fileTimeMs > 10_000) status.recBytes * 1000.0 / status.fileTimeMs else 0.0
        val leftS = if (bytesPerSec > 0 && status.diskFreeMb >= 0) (status.diskFreeMb * 1048576.0 / bytesPerSec).toLong() else -1
        FigRow(
            Triple("File size", if (status.recActive) "%.2f".format(status.recBytes / 1e9) else "–", if (status.recActive) "GB" else ""),
            Triple("Bitrate", if (bytesPerSec > 0) "%.1f".format(bytesPerSec * 8 / 1e6) else "–", if (bytesPerSec > 0) "Mb/s" else ""),
            Triple("Disk left", if (leftS >= 0) "${leftS / 3600} h ${"%02d".format((leftS / 60) % 60)}" else "–", if (leftS >= 0) "m" else ""),
        )
        // clips along the file
        if (live != null && status.recActive) {
            Row { Text("Clips in this file", style = Type.label, modifier = Modifier.weight(1f)) }
            Spacer(Modifier.height(6.dp))
            val dur = fileTime.coerceAtLeast(1)
            Box(Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(3.dp)).background(T.raised).drawBehind {
                live.clips.forEach { c ->
                    val x0 = size.width * (c.startMs.toFloat() / dur).coerceIn(0f, 1f)
                    val x1 = size.width * (c.endMs.toFloat() / dur).coerceIn(0f, 1f)
                    drawRect(Color(0x8C44A282), Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(3f), size.height))
                }
                clipStart?.let { cs ->
                    val x0 = size.width * (cs.toFloat() / dur).coerceIn(0f, 1f)
                    drawRect(Color(0x59E5566F), Offset(x0, 0f), Size(size.width - x0, size.height))
                }
            })
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp)) {
                Text("00:00", style = Type.mono.copy(fontSize = 12.sp, color = T.muted), modifier = Modifier.weight(1f))
                Text("now ${Director.tc(fileTime)}", style = Type.mono.copy(fontSize = 12.sp, color = T.muted))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (status.recActive) {
                HoldButton("Hold to stop", Icons.Outlined.Stop, settings.holdMs, Modifier.weight(1f), enabled = !lost) { Director.recordStop() }
                OutlineButton(if (status.recPaused) "Resume" else "Pause", if (status.recPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                    Modifier.weight(1f), enabled = !lost) { Director.recordPause(!status.recPaused) }
            } else {
                PrimaryButton("Start recording", Icons.Outlined.FiberManualRecord, Modifier.weight(1f), enabled = !lost) { Director.recordStart() }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    LiveOutputCard(lost)
    Spacer(Modifier.height(16.dp))
    MixCard(lost)
    Spacer(Modifier.height(16.dp))
    StreamCard(lost)
    Spacer(Modifier.height(16.dp))
    DeskCard()
    Spacer(Modifier.height(12.dp))
    Text("All clips and export", style = Type.body.copy(color = T.accent), modifier = Modifier.clickable { onOpenClips() }.padding(vertical = 8.dp))
}

@Composable
private fun LiveOutputCard(lost: Boolean) {
    val on by Director.video.collectAsState()
    val frames by Director.videoFrameCount.collectAsState()
    val status by Director.status.collectAsState()
    DCard {
        CardHeader("Live output", Icons.Outlined.Tv) {
            Text(if (on) (status.scene.ifBlank { "Program" } + " · 24 fps") else "Off", style = Type.small)
            Spacer(Modifier.width(10.dp))
            DSwitch(on, { Director.setVideo(it) })
        }
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).background(Color(0xFF0B0B0D)), contentAlignment = Alignment.Center) {
            if (on && !lost) {
                AndroidView(factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) = Director.attachSurface(h.surface)
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hgt: Int) {}
                            override fun surfaceDestroyed(h: SurfaceHolder) = Director.attachSurface(null)
                        })
                    }
                }, modifier = Modifier.fillMaxSize())
                if (frames == 0L) Text("Starting the picture…", style = Type.small)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (lost) Icons.Outlined.WifiOff else Icons.Outlined.Tv, null, tint = T.muted, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(if (lost) "Paused until the connection is back." else "Video is off, so it uses no data or battery.", style = Type.small)
                    if (!lost) {
                        Spacer(Modifier.height(12.dp))
                        PrimaryButton("Show live output", Icons.Outlined.PlayArrow, height = 36.dp) { Director.setVideo(true) }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(if (on) "About half a second behind the room. It travels separately from the audio, so it never delays the mix. It stops when the screen turns off."
            else "Turn it on to check framing. About 540 MB an hour while it's on.", style = Type.small)
    }
}

@Composable
private fun MixCard(lost: Boolean) {
    val levels by Director.levels.collectAsState()
    val listening by Director.listening.collectAsState()
    val latency by Director.latencyMs.collectAsState()
    val settings by Prefs.settings.collectAsState()
    var vol by remember { mutableStateOf(settings.volume) }
    DCard {
        CardHeader("Mix", Icons.Outlined.Headphones) { Text("$latency ms behind the PC", style = Type.mono.copy(fontSize = 12.sp, color = T.muted)) }
        Text("The program mix OBS records, left and right", style = Type.small)
        Spacer(Modifier.height(4.dp))
        MeterRow("Left", levels.leftDb, !lost)
        MeterRow("Right", levels.rightDb, !lost)
        Spacer(Modifier.height(6.dp))
        DSwitch(listening, { Director.setListening(it) }, "Listen to the mix")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Volume", style = Type.small, modifier = Modifier.width(60.dp))
            Slider(vol, { vol = it; Director.setVolume(it, save = false) }, Modifier.weight(1f), valueRange = 0f..3f, onValueChangeFinished = { Director.setVolume(vol) },
                colors = SliderDefaults.colors(thumbColor = T.meter, activeTrackColor = T.meter, inactiveTrackColor = T.raised))
            Text("${(vol * 100).toInt()}%", style = Type.mono, modifier = Modifier.width(48.dp).padding(start = 8.dp))
        }
        Text("With the screen off or in another app, the mix keeps playing. Start and end clips from the notification.", style = Type.small)
    }
}

@Composable
private fun StreamCard(lost: Boolean) {
    val s by Director.status.collectAsState()
    val health by Director.health.collectAsState()
    val settings by Prefs.settings.collectAsState()
    DCard {
        CardHeader("Stream", Icons.Outlined.Sensors) { if (s.streamActive) Pill("Live", PillKind.REC) else Pill("Off air", PillKind.IDLE) }
        val pct = if (s.totalFrames > 0) s.dropped * 100.0 / s.totalFrames else 0.0
        Row {
            Fig("Live for", if (s.streamActive) Director.tc(s.streamDurationMs) else "–", modifier = Modifier.weight(1f), valueColor = if (lost) T.muted else T.text)
            Fig("Bitrate", if (s.streamActive) "%,d".format(s.streamKbps.toInt()) else "–", if (s.streamActive) "kb/s" else "", Modifier.weight(1f))
            Fig("Dropped", "${s.dropped}", "%.2f%%".format(pct), Modifier.weight(1f), if (s.dropped > 0) T.warning else T.text)
        }
        Spacer(Modifier.height(14.dp))
        Text("Dropped frames per minute, last 30 minutes", style = Type.label)
        Spacer(Modifier.height(8.dp))
        val cells = List(30 - health.size.coerceAtMost(30)) { -1 } + health.takeLast(30)
        Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            cells.forEach { d ->
                val c = when { d < 0 -> T.raised; d == 0 -> T.idle; d < 60 -> T.warning; else -> T.negative }
                Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(2.dp)).background(c))
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = T.rule)
        SettingRow("Encoder lag") { Text("${s.encodeLag} frames", style = Type.mono) }
        SettingRow("Render lag") { Text("${s.renderLag} frames", style = Type.mono) }
        SettingRow("CPU on the OBS PC") { Text("%.1f %%".format(s.cpu), style = Type.mono) }
        if (settings.allowStreamStop && s.streamActive) {
            Spacer(Modifier.height(12.dp))
            HoldButton("Hold to end stream", Icons.Outlined.Stop, settings.holdMs, enabled = !lost) { Director.streamStop() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeskCard() {
    val msgs by Director.desk.collectAsState()
    var text by remember { mutableStateOf("") }
    val quick = listOf("Check mic levels", "Mic is muted", "Start recording", "Fix the framing", "Stand by", "Great job")
    DCard {
        CardHeader("Desk", Icons.Outlined.ChatBubbleOutline)
        Text("Messages pop up in the OBS Director dock the volunteers see in OBS.", style = Type.small)
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            quick.forEach { q -> Chip(q, null, false) { Director.sendDesk(q) } }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it.take(200) }, Modifier.weight(1f), placeholder = { Text("Message the desk") },
                singleLine = true, colors = fieldColors(), textStyle = Type.body)
            Spacer(Modifier.width(10.dp))
            PrimaryButton("Send", Icons.AutoMirrored.Outlined.Send, enabled = text.isNotBlank()) { Director.sendDesk(text); text = "" }
        }
        msgs.takeLast(5).reversed().forEach { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                Text(if (m.fromDesk) "Desk: ${m.text}" else m.text, style = Type.body.copy(color = if (m.fromDesk) T.pillOkInk else T.text), modifier = Modifier.weight(1f))
                val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                Text(if (m.fromDesk) t.format(m.at) else if (m.seenAt != null) "Seen ${t.format(m.seenAt)}" else "Sent ${t.format(m.at)}", style = Type.small)
            }
            HorizontalDivider(color = T.rule)
        }
    }
}

/** The clip button, the running-clip bar and the note form. Sits above the tab bar. */
@Composable
fun ClipPanel() {
    val clipStart by Director.clipStart.collectAsState()
    val note by Director.noteFor.collectAsState()
    val saved by Director.savedMessage.collectAsState()
    val status by Director.status.collectAsState()
    val link by Director.link.collectAsState()
    val settings by Prefs.settings.collectAsState()
    @Suppress("UNUSED_VARIABLE") val tick = rememberTicker()
    val lost = link == LinkState.LOST
    val now = Director.nowFileTimeMs()

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFF1D1C21))
            .border(1.dp, Color(0xFF2E2E33), RoundedCornerShape(6.dp)).padding(12.dp)
    ) {
        val running = clipStart != null
        val enabled = status.recActive && !lost
        Row(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
                .background(if (running) T.pillNegBg else if (enabled) T.text else T.control)
                .border(1.dp, if (running) T.negative else Color.Transparent, RoundedCornerShape(26.dp))
                .clickable(enabled = enabled) { if (running) Director.endClip() else Director.startClip() },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            val ink = if (running) T.text else if (enabled) Color.Black else T.muted
            if (running) { Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(T.negative)); Spacer(Modifier.width(10.dp)) }
            Icon(if (lost) Icons.Outlined.WifiOff else if (running) Icons.Outlined.Stop else Icons.Outlined.ContentCut, null, tint = ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(when { lost -> "Reconnecting…"; running -> "End clip"; else -> "Start clip" }, style = Type.body.copy(fontSize = 16.sp, color = ink, fontWeight = FontWeight.Medium))
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    lost -> ""
                    running -> run { val s = ((now - clipStart!!) / 1000).coerceAtLeast(0); "${s / 60}:${"%02d".format(s % 60)}" }
                    status.recActive -> "from ${Director.tc((now - settings.leadMinutes * 60_000L).coerceAtLeast(0))}"
                    else -> ""
                }, style = Type.mono.copy(fontSize = 15.sp, color = ink.copy(alpha = 0.7f)),
            )
        }
        if (!status.recActive && !lost) Text("Start recording to clip.", style = Type.small, modifier = Modifier.padding(top = 8.dp))
        if (running) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(6.dp)).background(T.pillNegBg).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Clip running from ${Director.tc(clipStart!!)}", style = Type.small.copy(color = T.negative), modifier = Modifier.weight(1f))
                Text("Cancel clip", style = Type.small.copy(color = T.text), modifier = Modifier.clickable { Director.cancelClip() }.padding(4.dp))
            }
        }
        note?.let { (_, clip) -> NoteForm(clip.id) }
        saved?.let { if (note == null) Text(it, style = Type.small, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun NoteForm(key: String) {
    var text by remember(key) { mutableStateOf("") }
    var tags by remember(key) { mutableStateOf(listOf<String>()) }
    var voiceStatus by remember(key) { mutableStateOf("") }
    Column(Modifier.padding(top = 10.dp)) {
        TagChips(tags, { id -> tags = if (tags.contains(id)) tags - id else tags + id })
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it.take(140) }, Modifier.weight(1f), placeholder = { Text("Add a note, or hold the mic") },
                singleLine = true, colors = fieldColors(), textStyle = Type.body)
            Spacer(Modifier.width(8.dp))
            VoiceButton(onText = { text = if (text.isBlank()) it else "$text $it" }, onStatus = { voiceStatus = it })
        }
        if (voiceStatus.isNotEmpty()) Text(voiceStatus, style = Type.small, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Save", modifier = Modifier.weight(1f)) { Director.saveNote(text, tags) }
            OutlineButton("Skip", modifier = Modifier.weight(1f)) { Director.skipNote() }
        }
    }
}

@Suppress("unused")
private fun tagColor(id: String) = Tag.of(id)?.let { Color(it.color) } ?: T.clipGreen
