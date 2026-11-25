package dev.sharptalons.obsdirector.ui

import androidx.compose.ui.semantics.paneTitle

import androidx.compose.foundation.layout.height

import androidx.compose.ui.semantics.liveRegion

import androidx.compose.ui.semantics.LiveRegionMode

import androidx.compose.foundation.layout.wrapContentHeight

import androidx.compose.material3.rememberModalBottomSheetState

import androidx.compose.material3.ModalBottomSheet

import androidx.compose.material3.ExperimentalMaterial3Api

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Wifi
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.sharptalons.obsdirector.core.ClipStore
import dev.sharptalons.obsdirector.core.Director
import dev.sharptalons.obsdirector.core.LinkState
import dev.sharptalons.obsdirector.core.PhoneBattery
import dev.sharptalons.obsdirector.core.Prefs
import kotlinx.coroutines.delay

/** Ticks a few times a second so running timecodes update between status messages. */
@Composable
fun rememberTicker(periodMs: Long = 250): Long {
    var t by remember { mutableLongStateOf(0L) }
    LaunchedEffect(periodMs) { while (true) { delay(periodMs); t++ } }
    return t
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MonitorScreen(onOpenRecording: (String) -> Unit, onOpenStream: () -> Unit) {
    val status by Director.status.collectAsState()
    val link by Director.link.collectAsState()
    val settings by Prefs.settings.collectAsState()
    val recordings by ClipStore.recordings.collectAsState()
    val clipStart by Director.clipStart.collectAsState()
    val note by Director.noteFor.collectAsState()
    val saved by Director.savedMessage.collectAsState()
    @Suppress("UNUSED_VARIABLE") val tick = rememberTicker()
    val lost = link == LinkState.LOST
    val fileTime = if (status.recActive && !lost) Director.nowFileTimeMs() else status.fileTimeMs
    val clips = recordings.firstOrNull { it.file == status.file }?.clips?.size ?: 0

    Column(Modifier.semantics { paneTitle = "Monitor" }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BatteryBanner()

        // ---------------- Recording ----------------
        Section {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val (label, tint) = when {
                    !status.recActive -> "STOPPED" to Tints.neutral
                    status.recPaused -> "PAUSED" to Tints.amber
                    else -> "REC" to Tints.rec
                }
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(tint.bg).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (status.recActive) { Box(Modifier.size(8.dp).clip(CircleShape).background(if (status.recPaused) T.warning else T.negative)); Spacer(Modifier.width(6.dp)) }
                    Text(label, style = Type.label.copy(color = tint.ink, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp))
                }
                Text(status.file.ifBlank { "No recording yet" }, style = Type.small.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(Director.tc(fileTime), style = Type.timecode.copy(fontSize = 46.sp, letterSpacing = (-1).sp, color = if (status.recActive && !lost) T.text else T.muted))

            val running = clipStart != null
            ClipButton(
                running = running,
                lengthText = clipLength(Director.nowFileTimeMs() - (clipStart ?: 0)),
                enabled = status.recActive && !lost,
                hint = when {
                    lost -> "Reconnecting…"
                    !status.recActive -> "Start recording to clip"
                    else -> "starts ${settings.leadMinutes} min before your tap"
                },
            ) { if (running) Director.endClip() else Director.startClip() }

            // One line under the Clip button, always the same height: what just happened and the
            // one thing to do about it. Nothing appears or disappears, so nothing below moves.
            var noteOpen by remember { mutableStateOf(false) }
            LaunchedEffect(note) { noteOpen = note != null }
            val clipsText = if (clips == 1) "1 clip in this recording" else if (clips == 0) "No clips yet" else "$clips clips in this recording"
            val (lineText, action) = when {
                running -> "Clip running from ${Director.tc(clipStart ?: 0)}" to ("Cancel" to { Director.cancelClip() })
                note != null -> (saved ?: "Clip saved") to ("Add note" to { noteOpen = true })
                saved != null -> saved!! to (if (clips > 0) "See all" to { onOpenRecording(status.file) } else null)
                else -> clipsText to (if (status.file.isNotBlank() && clips > 0) "See all" to { onOpenRecording(status.file) } else null)
            }
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(lineText, style = Type.body.copy(fontSize = 14.sp, color = T.text2), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                action?.let { (label, onClick) ->
                    Text(label, style = Type.body.copy(color = T.accent, fontWeight = FontWeight.Medium),
                        modifier = Modifier.height(48.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick)
                            .padding(horizontal = 12.dp).wrapContentHeight())
                }
            }
            if (noteOpen && note != null) NoteSheet(note!!.second.id) { noteOpen = false }

            // Recording controls: two equal buttons, or one full-width one, always 48 dp tall.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (status.recActive) {
                    OutlineButton(if (status.recPaused) "Resume" else "Pause", if (status.recPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        Modifier.weight(1f), enabled = !lost) { Director.recordPause(!status.recPaused) }
                    HoldButton("Hold to stop", Icons.Outlined.Stop, settings.holdMs, Modifier.weight(1f), enabled = !lost) { Director.recordStop() }
                } else {
                    PrimaryButton("Start recording", Icons.Outlined.FiberManualRecord, Modifier.weight(1f), enabled = !lost) { Director.recordStart() }
                }
            }
        }

        ListenSection(lost)

        // ---------------- Status line: three equal chips, always the same three ----------------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status.streamActive) TintChip("Live ${clipLength(status.streamDurationMs)}", Tints.rec, dot = T.negative, modifier = Modifier.weight(1.3f), onClick = onOpenStream)
            else TintChip("Off air", Tints.neutral, modifier = Modifier.weight(1.3f), onClick = onOpenStream)
            TintChip(if (status.streamActive) "${status.dropped} dropped" else "– dropped",
                if (!status.streamActive) Tints.neutral else if (status.dropped == 0) Tints.ok else Tints.amber, modifier = Modifier.weight(1f))
            val h = Director.diskHoursLeft(status)
            TintChip(if (h == null) "Disk –" else "Disk ${if (h >= 10) "%.0f".format(h) else "%.1f".format(h)} h",
                if (h != null && h < settings.diskHours) Tints.amber else Tints.purple, modifier = Modifier.weight(1f))
        }

        LivePicture(lost)
        DeskSection()
    }
}

@Composable
private fun BatteryBanner() {
    val battery by PhoneBattery.battery.collectAsState()
    val settings by Prefs.settings.collectAsState()
    var dismissedAt by rememberSaveable { mutableIntStateOf(-1) }
    val low = settings.batteryWarn > 0 && !battery.charging && battery.percent <= settings.batteryWarn
    // Dismissed stays dismissed until the battery drops another 5%.
    if (!low || (dismissedAt >= 0 && battery.percent > dismissedAt - 5)) return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tints.amber.bg).padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
            .semantics { contentDescription = "Phone at ${battery.percent} percent. Plug it in: the session audio is still saving." },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.BatteryAlert, null, tint = Tints.amber.ink, modifier = Modifier.size(22.dp))
        Text("Phone at ${battery.percent}%. Plug it in: the session audio is still saving.", style = Type.body.copy(fontSize = 13.sp, color = Tints.amber.ink), modifier = Modifier.weight(1f))
        RoundIcon(Icons.Outlined.Close, "Dismiss", Tints.amber.ink) { dismissedAt = battery.percent }
    }
}

@Composable
private fun ListenSection(lost: Boolean) {
    val levels by Director.levels.collectAsState()
    val listening by Director.listening.collectAsState()
    val settings by Prefs.settings.collectAsState()
    val output by Director.router.outputName.collectAsState()
    val latency by Director.latencyMs.collectAsState()
    @Suppress("UNUSED_VARIABLE") val tick = rememberTicker(1000)
    var vol by remember { mutableFloatStateOf(settings.volume) }
    val active = listening && !lost

    Section {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TintTile(Icons.Outlined.Headphones, Tints.teal, size = 36.dp, iconSize = 20.dp)
            Column(Modifier.weight(1f)) {
                Text("Listen", style = Type.body.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium), modifier = Modifier.semantics { heading() })
                Text(if (active) "Playing through $output" else if (lost) "Paused until the connection is back" else "Off", style = Type.small.copy(fontSize = 13.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DSwitch(listening, { Director.setListening(it) }, modifier = Modifier.semantics { contentDescription = "Listen" })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mix level, like the OBS meter", style = Type.label, modifier = Modifier.weight(1f))
            Text("Peak ${peakLabel(levels, active)}", style = Type.mono)
        }
        ObsMeter(levels, active)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Volume", style = Type.body.copy(color = T.muted), modifier = Modifier.width(56.dp))
            Slider(
                vol, { vol = it; Director.setVolume(it, save = false) }, Modifier.weight(1f).semantics { stateDescription = "${(vol * 100).toInt()} percent" },
                valueRange = 0f..3f, onValueChangeFinished = { Director.setVolume(vol) },
                colors = SliderDefaults.colors(thumbColor = T.text, activeTrackColor = if (vol > 1f) T.warning else T.accent, inactiveTrackColor = T.control),
            )
            Text("${(vol * 100).toInt()}%", style = Type.mono.copy(fontSize = 14.sp, color = if (vol > 1f) T.warning else T.text), modifier = Modifier.width(48.dp))
        }
        // Always shown, so moving the slider past 100% doesn't push anything down.
        Text(if (vol > 1f) "Boosted above 100%. Loud parts are softened, not clipped." else "Up to 300% for quiet rooms.",
            style = Type.small.copy(color = if (vol > 1f) T.warning else T.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        run {
            val drops = Director.dropoutsLastMinute()
            val (text, tint) = when {
                !active -> "Wi-Fi quality shows here while you listen" to Tints.neutral
                drops == 0 && latency < 150 -> "Wi-Fi good · no dropouts" to Tints.ok
                drops <= 3 -> "Wi-Fi weak · $drops dropout${if (drops == 1) "" else "s"} in the last minute" to Tints.amber
                else -> "Wi-Fi poor · audio keeps breaking up. Move closer to the router." to Tints.rec
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(tint.bg).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Wifi, null, tint = tint.ink, modifier = Modifier.size(18.dp))
                Text(text, style = Type.body.copy(fontSize = 13.sp, color = tint.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun LivePicture(lost: Boolean) {
    val on by Director.video.collectAsState()
    val frames by Director.videoFrameCount.collectAsState()
    Section(padding = 0.dp, gap = 0.dp) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !lost, role = Role.Switch) { Director.setVideo(!on) }
                .semantics { stateDescription = if (on) "On" else "Off" }.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Live picture", style = Type.body.copy(fontSize = 16.sp), modifier = Modifier.weight(1f))
            Text(if (on) "Hide" else "Off · tap to show", style = Type.small.copy(fontSize = 13.sp))
        }
        if (on && !lost) {
            Box(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(T.video), contentAlignment = Alignment.Center) {
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
            }
        }
    }
}

@Composable
private fun DeskSection() {
    val msgs by Director.desk.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    Section(padding = 0.dp, gap = 0.dp) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { open = !open }.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Message the desk", style = Type.body.copy(fontSize = 16.sp), modifier = Modifier.weight(1f))
            Text(if (open) "−" else "+", style = Type.body.copy(fontSize = 20.sp, color = T.muted))
        }
        if (open) Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it.take(200) }, Modifier.weight(1f), placeholder = { Text("Type a message") },
                    singleLine = true, colors = fieldColors(), textStyle = Type.body, label = { Text("Message") })
                Spacer(Modifier.width(8.dp))
                PrimaryButton("Send", Icons.AutoMirrored.Outlined.Send, enabled = text.isNotBlank(), height = 48.dp) { Director.sendDesk(text); text = "" }
            }
            msgs.takeLast(3).reversed().forEach { m ->
                HorizontalDivider(color = T.raised)
                val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                Row {
                    Text(if (m.fromDesk) "Desk: ${m.text}" else m.text, style = Type.body.copy(color = if (m.fromDesk) Tints.ok.ink else T.text), modifier = Modifier.weight(1f))
                    Text(if (m.fromDesk) t.format(m.at) else if (m.seenAt != null) "Seen ${t.format(m.seenAt)}" else "Sent ${t.format(m.at)}", style = Type.small)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteSheet(key: String, onClose: () -> Unit) {
    var text by remember(key) { mutableStateOf("") }
    var voiceStatus by remember(key) { mutableStateOf("") }
    // Slides up over the screen, so writing a note doesn't push the Monitor around.
    ModalBottomSheet(onDismissRequest = { Director.skipNote(); onClose() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = T.surface) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Note for this clip", style = Type.cardTitle.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), modifier = Modifier.semantics { heading() })
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it.take(140) }, Modifier.weight(1f), placeholder = { Text("What was it? Or hold the mic") },
                    singleLine = true, colors = fieldColors(), textStyle = Type.body)
                Spacer(Modifier.width(8.dp))
                VoiceButton(onText = { text = if (text.isBlank()) it else "$text $it" }, onStatus = { voiceStatus = it })
            }
            Text(voiceStatus.ifEmpty { " " }, style = Type.small, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton("Skip", modifier = Modifier.weight(1f)) { Director.skipNote(); onClose() }
                PrimaryButton("Save note", modifier = Modifier.weight(1f)) { Director.saveNote(text, emptyList()); onClose() }
            }
        }
    }
}
