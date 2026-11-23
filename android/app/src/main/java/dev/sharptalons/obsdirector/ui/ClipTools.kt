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
import androidx.compose.ui.semantics.Role
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

internal fun playClip(r: Recording, c: Clip, key: String, from: Long = c.startMs, to: Long = c.endMs) {
    val f = ClipStore.audioFileFor(r)
    when {
        f == null -> Director.toast("This recording's audio isn't saved on this device.")
        r.live -> Director.toast("Playable once the recording stops.")
        !ClipPlayer.play(key, f, r.audioOffsetMs, from, to) -> Director.toast("Couldn't play this file.")
    }
}

/** Fine-tune a clip's start and end on the waveform; Save keeps the note as it is. */
@Composable
internal fun AdjustPanel(r: Recording, c: Clip, onClose: () -> Unit) {
    var start by remember(c.id) { mutableLongStateOf(c.startMs) }
    var end by remember(c.id) { mutableLongStateOf(c.endMs) }
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
                drawRect(T.waveform, Offset(k * bw + bw * 0.2f, (size.height - h) / 2), Size(bw * 0.6f, h))
            }
            gaps.filter { it.atMs in from..to }.forEach { g ->
                val x = (g.atMs - from) / span.toFloat() * size.width
                drawRect(T.waveGap, Offset(x, 0f), Size((g.lengthMs / span.toFloat() * size.width).coerceAtLeast(3f), size.height))
            }
            val sx = (start - from) / span.toFloat() * size.width
            val ex = (end - from) / span.toFloat() * size.width
            drawRect(T.waveSelection, Offset(sx, 0f), Size(ex - sx, size.height))
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
            PrimaryButton(if (adjPlaying) "Stop" else "Play clip", if (adjPlaying) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, height = 48.dp) {
                if (adjPlaying) ClipPlayer.stop() else playClip(r, c, "adj", start, end)
            }
            OutlineButton("Hear start", Icons.Outlined.PlayArrow, height = 48.dp) { playClip(r, c, "adj", (start - 1500).coerceAtLeast(0), start + 1500) }
            OutlineButton("Hear end", Icons.Outlined.PlayArrow, height = 48.dp) { playClip(r, c, "adj", (end - 1500).coerceAtLeast(0), end + 1500) }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineButton("Revert", Icons.AutoMirrored.Outlined.Undo, Modifier.weight(1f), height = 44.dp) { start = c.startMs; end = c.endMs; error = null }
            PrimaryButton("Save", Icons.Outlined.Check, Modifier.weight(1f), height = 44.dp) {
                ClipPlayer.stop()
                ClipStore.updateClip(r.file, c.copy(startMs = start, endMs = end))
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
            Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, T.control, RoundedCornerShape(10.dp))
                .clickable(role = Role.Button, onClickLabel = "Move $label ${if (d > 0) "later" else "earlier"}") { onNudge(d) },
                contentAlignment = Alignment.Center) {
                val s = if (abs(d) >= 1000) "${abs(d) / 1000}" else "0.${abs(d) / 100}"
                Text((if (d > 0) "+" else "−") + s, style = Type.mono.copy(fontSize = 12.sp))
            }
        }
    }
}

internal suspend fun shareClipAudio(context: Context, r: Recording, c: Clip) {
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
