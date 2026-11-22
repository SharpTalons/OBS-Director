package dev.sharptalons.obsdirector.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.sharptalons.obsdirector.audio.ClipAudio
import dev.sharptalons.obsdirector.audio.ClipPlayer
import dev.sharptalons.obsdirector.core.Clip
import dev.sharptalons.obsdirector.core.ClipStore
import dev.sharptalons.obsdirector.core.Director
import dev.sharptalons.obsdirector.core.Exporter
import dev.sharptalons.obsdirector.core.Prefs
import dev.sharptalons.obsdirector.core.Recording
import dev.sharptalons.obsdirector.core.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

@Composable
fun ClipsScreen(recordings: List<Recording>) {
    var tagFilter by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    var removed by remember { mutableStateOf<Pair<String, Clip>?>(null) }
    val playback by ClipPlayer.playback.collectAsState()
    val total = recordings.sumOf { it.clips.size }

    Text("$total clips across ${recordings.size} recordings. Play, adjust or export them as markers for your editor.", style = Type.finding)
    Spacer(Modifier.height(16.dp))

    removed?.let { (file, clip) ->
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(6.dp)).background(T.raised).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Clip at ${Director.tc(clip.startMs)} deleted.", style = Type.body, modifier = Modifier.weight(1f))
            OutlineButton("Undo", Icons.AutoMirrored.Outlined.Undo, height = 34.dp) { ClipStore.addClip(file, clip); removed = null }
        }
    }

    DCard {
        CardHeader("Clips by recording", Icons.Outlined.ContentCut)
        TagChips(listOfNotNull(tagFilter), { id -> tagFilter = if (tagFilter == id) null else id }, allChip = true, allSelected = tagFilter == null, onAll = { tagFilter = null })
        Spacer(Modifier.height(8.dp))
        Text("Times are from the start of each file.", style = Type.small)
        if (recordings.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("No recordings yet. Clips you take while monitoring appear here, grouped by recording.", style = Type.body.copy(color = T.muted))
        }
        recordings.forEach { r ->
            Spacer(Modifier.height(20.dp))
            Text(r.file, style = Type.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium))
            val audio = when {
                r.audioFile == null -> "Audio not saved on this device"
                r.live -> "Audio on this device, recording now"
                else -> "Audio on this device" + if (r.gaps.isNotEmpty()) ", ${r.gaps.size} Wi-Fi gaps (${"%.1f".format(r.gaps.sumOf { it.lengthMs } / 1000.0)} s)" else ", complete"
            }
            Text("${if (r.live) "recording now" else Director.tc(r.durationMs) + " long"} · ${r.clips.size} clips · $audio", style = Type.small)
            HorizontalDivider(color = T.rule, modifier = Modifier.padding(top = 8.dp))
            val shown = r.clips.filter { tagFilter == null || it.tags.contains(tagFilter) }
            if (shown.isEmpty()) Text(if (tagFilter == null) "No clips in this recording yet." else "No ${Tag.of(tagFilter!!)?.label} clips in this recording.",
                style = Type.small, modifier = Modifier.padding(vertical = 12.dp))
            shown.forEach { c ->
                val idx = r.clips.indexOf(c) + 1
                val key = "${r.file}:${c.id}"
                val playing = playback.playing && playback.key == key
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$idx", style = Type.mono.copy(color = T.muted), modifier = Modifier.width(26.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${Director.tc(c.startMs)} → ${Director.tc(c.endMs)}", style = Type.mono.copy(fontSize = 14.sp))
                        Text(Director.len(c.lengthMs), style = Type.small)
                        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (playing) "Playing ${Director.len(playback.posMs - playback.fromMs)} of ${Director.len(c.lengthMs)}"
                                else c.note.ifBlank { "No note" }, style = Type.body.copy(color = if (playing) T.pillOkInk else if (c.note.isBlank()) T.muted else T.text))
                            c.tags.mapNotNull { Tag.of(it) }.forEach { TagPill(it) }
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        RowAction(if (playing) "Stop" else "Play", if (playing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow) {
                            if (playing) ClipPlayer.stop() else playClip(r, c, key)
                        }
                        RowAction(if (open == key) "Close" else "Adjust", if (open == key) Icons.Outlined.Close else Icons.Outlined.Tune) {
                            open = if (open == key) null else key
                        }
                    }
                }
                if (open == key) AdjustPanel(r, c, onClose = { open = null }, onDelete = { ClipStore.removeClip(r.file, c.id); removed = r.file to c; open = null })
                HorizontalDivider(color = T.rule)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    ExportCard(recordings)
}

@Composable
private fun RowAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = T.muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = Type.body.copy(color = T.muted))
    }
}

private fun playClip(r: Recording, c: Clip, key: String, from: Long = c.startMs, to: Long = c.endMs) {
    val f = ClipStore.audioFileFor(r)
    when {
        f == null -> Director.toast("This recording's audio isn't saved on this device.")
        r.live -> Director.toast("Playable once the recording stops.")
        !ClipPlayer.play(key, f, r.audioOffsetMs, from, to) -> Director.toast("Couldn't play this file.")
    }
}

@Composable
private fun AdjustPanel(r: Recording, c: Clip, onClose: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var start by remember(c.id) { mutableLongStateOf(c.startMs) }
    var end by remember(c.id) { mutableLongStateOf(c.endMs) }
    var note by remember(c.id) { mutableStateOf(c.note) }
    var tags by remember(c.id) { mutableStateOf(c.tags) }
    var voice by remember(c.id) { mutableStateOf("") }
    var error by remember(c.id) { mutableStateOf<String?>(null) }
    val playback by ClipPlayer.playback.collectAsState()
    val envelope = Director.liveEnvelope(r.file) ?: r.envelope
    val gaps = Director.liveGaps(r.file) ?: r.gaps
    val duration = if (r.live) Director.nowFileTimeMs() else r.durationMs.coerceAtLeast(envelope.size * 100L)
    // the view shows 15 s either side of the clip
    val from = (minOf(start, c.startMs) - 15_000).coerceAtLeast(0)
    val to = (maxOf(end, c.endMs) + 15_000).let { if (duration > 0) it.coerceAtMost(duration) else it }
    val span = (to - from).coerceAtLeast(1)

    fun clamp(which: Char) {
        start = start.coerceAtLeast(0); if (duration > 0) end = end.coerceAtMost(duration)
        error = null
        if (end - start < 1000) { if (which == 's') start = end - 1000 else end = start + 1000; error = "A clip needs at least 1 second." }
    }

    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(6.dp)).background(T.ground).border(1.dp, T.border, RoundedCornerShape(6.dp)).padding(12.dp)) {
        var dragging by remember { mutableStateOf<Char?>(null) }
        Canvas(
            Modifier.fillMaxWidth().height(96.dp)
                .pointerInput(c.id, from, to) {
                    detectHorizontalDragGestures(
                        onDragStart = { o ->
                            val sx = (start - from) / span.toFloat() * size.width
                            val ex = (end - from) / span.toFloat() * size.width
                            dragging = if (abs(o.x - sx) < abs(o.x - ex)) 's' else 'e'
                        },
                        onDragEnd = { dragging = null },
                    ) { change, _ ->
                        val t = from + (change.position.x / size.width * span).toLong()
                        val snapped = (t / 100) * 100
                        if (dragging == 's') { start = snapped; clamp('s') } else { end = snapped; clamp('e') }
                    }
                }
                .pointerInput(c.id, from, to) {
                    detectTapGestures { o ->
                        val t = from + (o.x / size.width * span).toLong()
                        playClip(r, c, "adj", t, minOf(to, t + 3000))
                    }
                }
        ) {
            val bars = 120
            val bw = size.width / bars
            for (k in 0 until bars) {
                val t = from + span * (k + 0.5f) / bars
                val i = (t / 100).toInt()
                val a = envelope.getOrNull(i) ?: 0.05f
                val h = (a.coerceIn(0.04f, 1f)) * size.height * 0.92f
                drawRect(Color(0xFF55555C), Offset(k * bw + bw * 0.2f, (size.height - h) / 2), Size(bw * 0.6f, h))
            }
            gaps.filter { it.atMs in from..to }.forEach { g ->
                val x = (g.atMs - from) / span.toFloat() * size.width
                drawRect(Color(0x40D9A441), Offset(x, 0f), Size((g.lengthMs / span.toFloat() * size.width).coerceAtLeast(3f), size.height))
            }
            val sx = (start - from) / span.toFloat() * size.width
            val ex = (end - from) / span.toFloat() * size.width
            drawRect(Color(0x2944A282), Offset(sx, 0f), Size(ex - sx, size.height))
            drawRect(T.clipGreen, Offset(sx - 1f, 0f), Size(3f, size.height))
            drawRect(T.clipGreen, Offset(ex - 1f, 0f), Size(3f, size.height))
            drawRect(T.clipGreen, Offset(sx - 9f, 0f), Size(18f, 18f))
            drawRect(T.clipGreen, Offset(ex - 9f, 0f), Size(18f, 18f))
            if (playback.playing && playback.key == "adj") {
                val px = (playback.posMs - from) / span.toFloat() * size.width
                drawRect(Color.White, Offset(px, 0f), Size(2f, size.height))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(Director.tc(from), style = Type.mono.copy(fontSize = 12.sp, color = T.muted), modifier = Modifier.weight(1f))
            Text(Director.tc(to), style = Type.mono.copy(fontSize = 12.sp, color = T.muted))
        }
        Text("Drag the green edges, or nudge below. Tap the waveform to hear 3 s from there.", style = Type.small, modifier = Modifier.padding(top = 4.dp))

        Spacer(Modifier.height(12.dp))
        EdgeEditor("Start", start, start != c.startMs) { d -> start += d; clamp('s') }
        Spacer(Modifier.height(10.dp))
        EdgeEditor("End", end, end != c.endMs) { d -> end += d; clamp('e') }
        Spacer(Modifier.height(10.dp))
        Text("Length", style = Type.label)
        Text(Director.len(end - start), style = Type.figure.copy(fontSize = 20.sp))
        error?.let { Text(it, style = Type.small.copy(color = T.negative), modifier = Modifier.padding(top = 6.dp)) }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val adjPlaying = playback.playing && playback.key == "adj"
            PrimaryButton(if (adjPlaying) "Stop" else "Play clip", if (adjPlaying) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, height = 36.dp) {
                if (adjPlaying) ClipPlayer.stop() else playClip(r, c, "adj", start, end)
            }
            OutlineButton("Hear start", Icons.Outlined.PlayArrow, height = 36.dp) { playClip(r, c, "adj", (start - 1500).coerceAtLeast(0), start + 1500) }
            OutlineButton("Hear end", Icons.Outlined.PlayArrow, height = 36.dp) { playClip(r, c, "adj", (end - 1500).coerceAtLeast(0), end + 1500) }
        }

        Spacer(Modifier.height(14.dp))
        Text("Tags", style = Type.label)
        Spacer(Modifier.height(6.dp))
        TagChips(tags, { id -> tags = if (tags.contains(id)) tags - id else tags + id })
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(note, { note = it.take(140) }, Modifier.weight(1f), label = { Text("Note") }, singleLine = true, colors = fieldColors(), textStyle = Type.body)
            Spacer(Modifier.width(8.dp))
            VoiceButton(onText = { note = if (note.isBlank()) it else "$note $it" }, onStatus = { voice = it })
        }
        if (voice.isNotEmpty()) Text(voice, style = Type.small, modifier = Modifier.padding(top = 4.dp))

        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineButton("Delete clip", Icons.Outlined.Delete, height = 36.dp) { ClipPlayer.stop(); onDelete() }
            OutlineButton("Share audio", Icons.Outlined.Share, height = 36.dp) {
                scope.launch { shareClipAudio(context, r, c.copy(startMs = start, endMs = end)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineButton("Revert", Icons.AutoMirrored.Outlined.Undo, Modifier.weight(1f), height = 40.dp) { start = c.startMs; end = c.endMs; note = c.note; tags = c.tags; error = null }
            PrimaryButton("Save", Icons.Outlined.Check, Modifier.weight(1f), height = 40.dp) {
                ClipPlayer.stop()
                ClipStore.updateClip(r.file, c.copy(startMs = start, endMs = end, note = note.trim(), tags = tags))
                Director.toast("Clip saved. The export uses the new times.")
                onClose()
            }
        }
        Text("The chapter in the recording file marks where you tapped Start clip. Changed times go in the export.", style = Type.small, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun EdgeEditor(label: String, ms: Long, moved: Boolean, onNudge: (Long) -> Unit) {
    Text(label, style = Type.label)
    Text("${Director.tc(ms)}.${(ms % 1000) / 100}", style = Type.figure.copy(fontSize = 20.sp, color = if (moved) T.pillOkInk else T.text))
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(-10_000L, -1000L, -100L, 100L, 1000L, 10_000L).forEach { d ->
            Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, T.control, RoundedCornerShape(6.dp)).clickable { onNudge(d) },
                contentAlignment = Alignment.Center) {
                val s = if (abs(d) >= 1000) "${abs(d) / 1000}" else "0.${abs(d) / 100}"
                Text((if (d > 0) "+" else "−") + s, style = Type.mono.copy(fontSize = 12.sp))
            }
        }
    }
}

private suspend fun shareClipAudio(context: Context, r: Recording, c: Clip) {
    val src = ClipStore.audioFileFor(r)
    if (src == null) { Director.toast("This recording's audio isn't saved on this device."); return }
    if (r.live) { Director.toast("Shareable once the recording stops."); return }
    val dir = File(context.filesDir, "exports").apply { mkdirs() }
    val out = File(dir, "${r.file.substringBeforeLast('.')} clip ${Director.tc(c.startMs).replace(':', '-')}.m4a")
    val ok = withContext(Dispatchers.IO) { ClipAudio.cut(src, r.audioOffsetMs, c.startMs, c.endMs, out) }
    if (!ok) { Director.toast("Couldn't cut the clip's audio."); return }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", out)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share clip audio").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun ExportCard(recordings: List<Recording>) {
    val context = LocalContext.current
    val settings by Prefs.settings.collectAsState()
    var file by remember { mutableStateOf(recordings.firstOrNull()?.file) }
    var format by remember { mutableStateOf(settings.exportFormat) }
    val rec = recordings.firstOrNull { it.file == file } ?: recordings.firstOrNull()
    val text = rec?.takeIf { it.clips.isNotEmpty() }?.let { Exporter.export(it, format) } ?: "No clips in this recording yet."
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
            .onSuccess { Director.toast("Saved") }.onFailure { Director.toast("Couldn't save the file") }
    }

    DCard {
        CardHeader("Export for editing", Icons.Outlined.Description)
        if (recordings.isEmpty()) { Text("Nothing to export yet.", style = Type.small); return@DCard }
        Text("Recording", style = Type.label)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            recordings.forEach { r -> Chip(r.file.substringBeforeLast('.'), null, r.file == rec?.file) { file = r.file } }
        }
        Text("Format", style = Type.label)
        Tabs(Exporter.formats, format) { format = it }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(6.dp)).background(T.ground).border(1.dp, T.border, RoundedCornerShape(6.dp))
            .horizontalScroll(rememberScrollState()).padding(12.dp)) {
            Text(text, style = Type.mono.copy(fontSize = 11.sp, color = T.meter, lineHeight = 17.sp))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Copy", Icons.Outlined.ContentCopy, height = 36.dp, enabled = rec?.clips?.isNotEmpty() == true) {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("OBS Director clips", text))
                Director.toast("Copied")
            }
            OutlineButton("Share", Icons.Outlined.Share, height = 36.dp, enabled = rec?.clips?.isNotEmpty() == true) {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share clips"))
            }
            OutlineButton("Save file", Icons.Outlined.Description, height = 36.dp, enabled = rec?.clips?.isNotEmpty() == true) {
                save.launch("${rec?.file?.substringBeforeLast('.')}.${Exporter.extension(format)}")
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(Exporter.how(format), style = Type.small)
    }
}
