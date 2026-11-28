package dev.sharptalons.obsdirector.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import dev.sharptalons.obsdirector.audio.Trim
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sharptalons.obsdirector.audio.ClipPlayer
import dev.sharptalons.obsdirector.core.Clip
import dev.sharptalons.obsdirector.core.ClipStore
import dev.sharptalons.obsdirector.core.Director
import dev.sharptalons.obsdirector.core.Exporter
import dev.sharptalons.obsdirector.core.Prefs
import dev.sharptalons.obsdirector.core.Recording
import dev.sharptalons.obsdirector.core.ObsRecording
import androidx.compose.ui.semantics.selected
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** "Today, 20:35", "Yesterday, 10:02" or "Sunday 15 Nov, 10:02". */
fun recordingTitle(r: Recording): String = whenText(r.startedAt)

fun whenText(at: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = at }
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(c.time)
    return dayText(at)?.let { "$it, $time" } ?: (SimpleDateFormat("EEEE d MMM", Locale.getDefault()).format(c.time) + ", $time")
}

/** "Today", "Yesterday", or null for an older day. */
private fun dayText(at: Long): String? {
    val c = Calendar.getInstance().apply { timeInMillis = at }
    val today = Calendar.getInstance()
    if (c.get(Calendar.YEAR) == today.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) return "Today"
    today.add(Calendar.DAY_OF_YEAR, -1)
    if (c.get(Calendar.YEAR) == today.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) return "Yesterday"
    return null
}

private fun dayLabel(at: Long): String = dayText(at) ?: SimpleDateFormat("EEEE d MMM", Locale.getDefault()).format(java.util.Date(at))

private fun clipsText(n: Int) = when (n) { 0 -> "No clips"; 1 -> "1 clip"; else -> "$n clips" }

private fun clipKey(r: Recording, c: Clip) = "${r.file}:${c.id}"

/**
 * When [ms] of an entry's own time happened: the time of day for a session ("19:14:05"), the
 * recording's time for an entry from before 0.9.0 ("00:12:00").
 */
fun clockAt(r: Recording, ms: Long): String =
    if (r.session) SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(r.startedAt + ms)) else Director.tc(ms)

/** A clip's name: "2026-11-28 19-02-11 clip 2 – Choir intro"; older clips go by their note or time. */
fun clipTitle(r: Recording, c: Clip): String = c.fullName() ?: c.note.ifBlank { "Clip at ${clockAt(r, c.startMs)}" }

private fun clipMeta(r: Recording, c: Clip) = "${clipLength(c.lengthMs)} · ${clockAt(r, c.startMs)}"

private fun mb(bytes: Long) = if (bytes >= 10 shl 20) "${bytes shr 20} MB" else "%.1f MB".format(bytes / 1048576.0)

/**
 * Recordings, in two tabs, kept apart because they are:
 *
 * - **Clips**: recordings of the mix made on this phone, whatever OBS was doing, newest first,
 *   by day. Each has its own Play button; tapping one opens Play, Trim, Note, Share and Delete.
 * - **OBS recordings**: the recordings OBS made on the PC while the phone was connected. They stay
 *   on the PC; Save as AAC has the PC take one's sound out and copies it here to play.
 *
 * A small player shows above the tabs while a clip or an AAC file plays (the live mix is lowered).
 */
@Composable
fun RecordingsScreen(modifier: Modifier, top: (@Composable () -> Unit)?, @Suppress("UNUSED_PARAMETER") focus: String? = null) {
    val all by ClipStore.recordings.collectAsState()
    val status by Director.status.collectAsState()
    val link by Director.link.collectAsState()
    val jobs by Director.aacJobs.collectAsState()
    val playback by ClipPlayer.playback.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    @Suppress("UNUSED_VARIABLE") val tick = rememberTicker(1000)

    var undo by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    LaunchedEffect(undo) { if (undo != null) { delay(6000); undo = null } }

    var tab by rememberSaveable { mutableStateOf("clips") }
    var sheetClip by rememberSaveable { mutableStateOf<String?>(null) } // "entry|clip id"
    var sheetObs by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmAll by rememberSaveable { mutableStateOf(false) }
    val playingKey = if (playback.playing) playback.key else null

    // every clip, newest first, with whether it's in an OBS recording
    val clips = all.flatMap { r ->
        val inObs = r.obsFiles().flatMap { f -> r.forObsFile(f).clips.map { it.id to f } }.toMap()
        r.clips.map { c -> Triple(r, c, inObs[c.id]) }
    }.sortedByDescending { it.second.createdAt }
    val obs = Director.obsRecordings(all, status)
    val connected = link == dev.sharptalons.obsdirector.core.LinkState.CONNECTED

    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (playingKey != null || undo != null) 132.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            top?.let { t -> item { t() } }
            item(key = "title") { ScreenTitle("Recordings", "Clips are kept on this phone; OBS recordings on the PC") }
            item(key = "tabs") {
                Row(Modifier.fillMaxWidth().clip(CircleShape).background(T.surface).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("clips" to "Clips · ${clips.size}", "obs" to "OBS recordings · ${obs.size}").forEach { (id, label) ->
                        val on = tab == id
                        Box(Modifier.weight(1f).height(44.dp).clip(CircleShape).background(if (on) T.blue else Color.Transparent)
                            .clickable(role = Role.Tab) { tab = id }.semantics { selected = on }, contentAlignment = Alignment.Center) {
                            Text(label, style = Type.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (on) Color.White else T.text2), maxLines = 1)
                        }
                    }
                }
            }
            if (tab == "clips") {
                if (clips.isEmpty()) item(key = "clips-empty") {
                    Section { Text("No clips yet. Tap Start clip on Monitor: it records the mix on this phone, whether or not OBS is recording.", style = Type.body.copy(color = T.muted)) }
                }
                clips.groupBy { dayLabel(it.second.createdAt) }.forEach { (day, list) ->
                    item(key = "day-$day") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GroupLabel(day.uppercase(), T.muted)
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.surface)) {
                                list.forEachIndexed { i, (r, c, file) ->
                                    if (i > 0) HorizontalDivider(color = T.rule)
                                    val key = clipKey(r, c)
                                    val live = file != null && status.recActive && file == status.file
                                    ClipRow(r, c, playing = playingKey == key,
                                        tag = when { live -> "OBS recording now" to Tints.rec; file != null -> "In the OBS recording" to Tints.ok; else -> "Not recorded in OBS" to Tints.neutral },
                                        onPlay = { if (playingKey == key) ClipPlayer.stop() else playClip(r, c, key) },
                                        onOpen = { sheetClip = "${r.file}|${c.id}" })
                                }
                            }
                        }
                    }
                }
                if (clips.isNotEmpty()) item(key = "delete-all") { DangerLink("Delete all clips") { confirmAll = true } }
            } else {
                item(key = "obs-intro") {
                    Text("The recordings OBS made while this phone was connected. They stay on the PC; save one's sound as AAC to play it here.",
                        style = Type.small.copy(fontSize = 13.sp))
                }
                if (obs.isEmpty()) item(key = "obs-empty") {
                    Section { Text("None yet. Recordings OBS makes while this phone is connected show up here.", style = Type.body.copy(color = T.muted)) }
                }
                items(obs, key = { "obs-" + it.file }) { o ->
                    ObsRecordingCard(o, clips = clips.count { it.third == o.file }, job = jobs[o.file], connected = connected,
                        hasAac = Director.aacFile(o.file).exists(), playing = playingKey == "aac:${o.file}",
                        onSave = { Director.saveAac(o.file) }, onCancel = { Director.cancelAac(o.file) },
                        onPlay = { if (playingKey == "aac:${o.file}") ClipPlayer.stop() else playAac(o.file) },
                        onMore = { sheetObs = o.file })
                }
            }
        }

        // Undo, then the small player, floating above the tabs so nothing in the list moves
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            undo?.let { (text, action) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(T.text)
                    .semantics { liveRegion = LiveRegionMode.Polite }.padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(text, style = Type.body.copy(color = Color.Black, fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
                    Text("Undo", style = Type.body.copy(color = T.blue, fontWeight = FontWeight.Bold),
                        modifier = Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { action(); undo = null }
                            .padding(horizontal = 14.dp).wrapContentHeight())
                }
            }
            if (playingKey != null && playingKey.contains(':')) MiniPlayer(all, playback)
        }
    }

    // one sheet for everything about a clip
    sheetClip?.let { id ->
        val r = all.firstOrNull { it.file == id.substringBefore('|') }
        val c = r?.clips?.firstOrNull { it.id == id.substringAfter('|') }
        if (r == null || c == null) {
            LaunchedEffect(id) { sheetClip = null }
        } else ClipSheet(r, c, playing = playingKey == clipKey(r, c),
            onPlay = { playClip(r, c, clipKey(r, c)); sheetClip = null },
            onShare = { scope.launch { shareClipAudio(context, r, c) }; sheetClip = null },
            onDelete = {
                if (playingKey == clipKey(r, c)) ClipPlayer.stop()
                ClipStore.removeClip(r.file, c.id)
                undo = "Clip deleted" to { ClipStore.addClip(r.file, c) }
                sheetClip = null
            },
            onDismiss = { sheetClip = null })
    }
    // and one for an OBS recording: its clip list for the editor, and its AAC
    sheetObs?.let { file ->
        val o = obs.firstOrNull { it.file == file }
        if (o == null) LaunchedEffect(file) { sheetObs = null }
        else ObsSheet(o, all, onDismiss = { sheetObs = null })
    }
    if (confirmAll) ConfirmDelete(
        title = "Delete all clips?",
        body = "Removes every clip, note and saved mix audio from this phone. The OBS recordings on the PC, and AAC files saved here, aren't touched. " +
            "Clipping carries on.",
        onConfirm = { ClipPlayer.stop(); ClipStore.clearAll(); Director.toast("All clips deleted") },
        onDone = { confirmAll = false },
    )
}

/** One clip in the list: Play, its name and length, where it is, and ⋯. */
@Composable
private fun ClipRow(r: Recording, c: Clip, playing: Boolean, tag: Pair<String, Tint>, onPlay: () -> Unit, onOpen: () -> Unit) {
    val title = clipTitle(r, c)
    Row(Modifier.fillMaxWidth().background(if (playing) Tints.blue.bg else T.surface).padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(if (playing) T.accent else T.raised)
            .clickable(role = Role.Button) { onPlay() }
            .semantics { contentDescription = (if (playing) "Stop " else "Play ") + title }, contentAlignment = Alignment.Center) {
            Icon(if (playing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, null, tint = if (playing) T.ground else T.text, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClickLabel = "Open") { onOpen() }
            .padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = Type.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(clipMeta(r, c), style = Type.small.copy(fontSize = 12.sp))
            Text(tag.first, style = Type.label.copy(fontSize = 11.sp, color = tag.second.ink),
                modifier = Modifier.clip(CircleShape).background(tag.second.bg).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { onOpen() }
            .semantics { contentDescription = "More for $title" }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.MoreHoriz, null, tint = T.text2)
        }
    }
}

/**
 * One OBS recording: when and how long, its name, its clips, and its AAC: a Save button once
 * it has stopped, a progress bar while the PC converts it and it's copied here, then Play.
 */
@Composable
private fun ObsRecordingCard(o: ObsRecording, clips: Int, job: Director.AacJob?, connected: Boolean, hasAac: Boolean, playing: Boolean,
                             onSave: () -> Unit, onCancel: () -> Unit, onPlay: () -> Unit, onMore: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.surface)
        .then(if (o.live) Modifier.border(1.dp, Tints.rec.bg, RoundedCornerShape(18.dp)) else Modifier)
        .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (o.live) Row(Modifier.clip(CircleShape).background(Tints.rec.bg).padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(T.negative))
                        Text("REC", style = Type.label.copy(fontWeight = FontWeight.Bold, color = Tints.rec.ink))
                    }
                    Text(whenText(o.startedAt) + " · " + Director.len(o.durationMs) + if (o.live) " so far" else "", style = Type.small.copy(fontSize = 13.sp))
                }
                Text(o.file, style = Type.body.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() })
                Text(clipsText(clips), style = Type.small.copy(fontSize = 13.sp))
            }
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onMore)
                .semantics { contentDescription = "More for ${o.file}" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.MoreHoriz, null, tint = T.text2)
            }
        }
        Box(Modifier.padding(end = 8.dp)) {
            when {
                job != null -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(T.sunken).padding(start = 12.dp, top = 10.dp, bottom = 4.dp, end = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (job.stage == "converting") "Getting it ready on the PC…" else "Copying to this phone…", style = Type.body.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
                        Text("${job.percent}%", style = Type.mono.copy(fontSize = 14.sp), modifier = Modifier.padding(end = 8.dp))
                    }
                    // one bar for both steps: converting fills the first fifth, copying the rest
                    val total = if (job.stage == "converting") job.percent * 0.2f else 20f + job.percent * 0.8f
                    Box(Modifier.padding(end = 8.dp).fillMaxWidth().height(8.dp).clip(CircleShape).background(T.raised)
                        .semantics { contentDescription = "Saving as AAC, ${total.toInt()} percent" }) {
                        Box(Modifier.fillMaxWidth(total / 100f).height(8.dp).clip(CircleShape).background(T.accent))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Keeps going if you leave this screen", style = Type.small.copy(fontSize = 12.sp), modifier = Modifier.weight(1f))
                        Text("Cancel", style = Type.body.copy(color = T.accent, fontWeight = FontWeight.Medium),
                            modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onCancel)
                                .padding(horizontal = 12.dp).wrapContentHeight())
                    }
                }
                hasAac -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (playing) Tints.blue.bg else T.sunken).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(if (playing) T.accent else T.raised).clickable(role = Role.Button, onClick = onPlay)
                        .semantics { contentDescription = (if (playing) "Stop " else "Play ") + o.baseName }, contentAlignment = Alignment.Center) {
                        Icon(if (playing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, null, tint = if (playing) T.ground else T.text)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("AAC on this phone", style = Type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium))
                        Text(mb(Director.aacFile(o.file).length()) + " · share or delete from ⋯", style = Type.small.copy(fontSize = 12.sp))
                    }
                }
                o.live -> Text("Save it as AAC once OBS stops recording.", style = Type.small.copy(fontSize = 13.sp))
                else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PrimaryButton("Save as AAC on this phone", Icons.Outlined.FileDownload, Modifier.fillMaxWidth(), enabled = connected, onClick = onSave)
                    Text(if (connected) "The PC takes its sound out as AAC (.m4a) and sends it over Wi-Fi." else "Connect to the PC to save it.", style = Type.small.copy(fontSize = 12.sp))
                }
            }
        }
    }
}

/** The player above the tabs while a clip or an AAC file plays: which, how far, and Stop. */
@Composable
private fun MiniPlayer(all: List<Recording>, p: ClipPlayer.State) {
    val title = if (p.key.startsWith("aac:")) p.key.removePrefix("aac:").substringBeforeLast('.') + ".m4a" else {
        val r = all.firstOrNull { p.key.startsWith(it.file + ":") }
        val c = r?.clips?.firstOrNull { clipKey(r, it) == p.key }
        if (r != null && c != null) clipTitle(r, c) else "Playing"
    }
    val span = (p.toMs - p.fromMs).coerceAtLeast(1)
    val done = (p.posMs - p.fromMs).coerceIn(0, span)
    val duck = Prefs.settings.value.duckPct
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.raised)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${Director.len(done)} of ${Director.len(span)}" + if (duck > 0) " · live mix lowered $duck%" else "", style = Type.small.copy(fontSize = 12.sp, color = T.text2))
            }
            Box(Modifier.size(48.dp).clip(CircleShape).background(T.text).clickable(role = Role.Button) { ClipPlayer.stop() }
                .semantics { contentDescription = "Stop" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Stop, null, tint = T.ground)
            }
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(T.control)) {
            Box(Modifier.fillMaxWidth(done / span.toFloat()).height(3.dp).background(T.accent))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipSheet(r: Recording, c: Clip, playing: Boolean, onPlay: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var mode by rememberSaveable(c.id) { mutableStateOf("clip") } // clip, trim, note
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = T.surface) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Text(when (mode) { "trim" -> "Trim clip"; "note" -> "Note"; else -> clipTitle(r, c) },
                    style = Type.cardTitle.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium), modifier = Modifier.semantics { heading() })
                Text((if (mode == "clip") "" else clipTitle(r, c) + " · ") + clipMeta(r, c) + " · " + dayLabel(c.createdAt), style = Type.small.copy(fontSize = 13.sp))
            }
            when (mode) {
                "trim" -> TrimPanel(r, c, onDone = { mode = "clip" })
                "note" -> NoteEditor(c.note, onSave = { t ->
                    ClipStore.updateClip(r.file, c.copy(note = t.trim()))
                    Director.toast(if (t.isBlank()) "Note removed" else "Note saved")
                    mode = "clip"
                }, onCancel = { mode = "clip" })
                else -> {
                    PrimaryButton(if (playing) "Playing…" else "Play clip", Icons.Outlined.PlayArrow, Modifier.fillMaxWidth(), height = 52.dp, onClick = onPlay)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SheetTile("Trim", Icons.Outlined.ContentCut, Tints.teal, Modifier.weight(1f)) { mode = "trim" }
                        SheetTile("Note", Icons.Outlined.Edit, Tints.amber, Modifier.weight(1f)) { mode = "note" }
                        SheetTile("Share", Icons.Outlined.Share, Tints.purple, Modifier.weight(1f), onShare)
                    }
                    DangerLink("Delete clip", onDelete)
                }
            }
        }
    }
}

@Composable
private fun SheetTile(label: String, icon: ImageVector, tint: Tint, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(76.dp).clip(RoundedCornerShape(14.dp)).background(tint.bg).clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = tint.ink, modifier = Modifier.size(22.dp))
        Text(label, style = Type.body.copy(fontSize = 14.sp, color = tint.ink, fontWeight = FontWeight.Medium), modifier = Modifier.padding(top = 6.dp))
    }
}

/**
 * Trim, the way phones trim video: the sound of the clip with 15 s either side, the kept part lit,
 * and a handle at each end. Drag a handle to move that end, or drag the lit part to move the whole
 * clip; tap elsewhere to bring the nearer end there. Letting go of a handle settles it in the
 * nearest pause (see [Trim.snap]) and plays a moment of sound at that edge, so you hear the cut.
 * ±1 s fine-tunes the end you last touched. Nothing changes until Save.
 */
@Composable
private fun TrimPanel(r: Recording, c: Clip, onDone: () -> Unit) {
    var start by remember(c.id) { mutableLongStateOf(c.startMs) }
    var end by remember(c.id) { mutableLongStateOf(c.endMs) }
    var active by remember(c.id) { mutableStateOf("start") } // the end ±1 s moves
    val playback by ClipPlayer.playback.collectAsState()
    val envelope = Director.liveEnvelope(r.file) ?: r.envelope
    val duration = Director.liveLengthMs(r.file) ?: r.durationMs.coerceAtLeast(envelope.size * Trim.ENVELOPE_MS)
    // the view: the clip with 15 s either side, widened when an end is dragged to its side
    var view by remember(c.id) { mutableStateOf(Trim.window(c.startMs, c.endMs, duration)) }
    val trimPlaying = playback.playing && playback.key == "trim"
    val density = androidx.compose.ui.platform.LocalDensity.current
    val grab = with(density) { 28.dp.toPx() }

    fun hear(edge: String) {
        if (edge == "start") playClip(r, c, "trim", start, minOf(start + 2000, end))
        else if (edge == "end") playClip(r, c, "trim", maxOf(start, end - 2000), end)
    }
    fun settle(edge: String) {
        if (edge == "start") start = Trim.moveStart(start, end, Trim.snap(start, envelope))
        if (edge == "end") end = Trim.moveEnd(start, end, Trim.snap(end, envelope), duration)
        // an end pushed to the side of the view: show more
        if (start - view.first < 1500 || view.last - end < 1500) view = Trim.window(start, end, duration)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(12.dp)).background(T.sunken)
                .semantics { contentDescription = "Trimmer. Keeps ${clockAt(r, start)} to ${clockAt(r, end)}, ${clipLength(end - start)}. Use the buttons below to move each end." }
                .pointerInput(c.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val w = size.width.toFloat()
                        val span = (view.last - view.first).coerceAtLeast(1)
                        fun msAt(x: Float) = view.first + (x / w * span).toLong()
                        val sx = (start - view.first) / span.toFloat() * w
                        val ex = (end - view.first) / span.toFloat() * w
                        val x0 = down.position.x
                        // which part was grabbed: an end, the kept part, or elsewhere (the nearer end jumps there)
                        val target = when {
                            kotlin.math.abs(x0 - sx) <= grab && kotlin.math.abs(x0 - sx) <= kotlin.math.abs(x0 - ex) -> "start"
                            kotlin.math.abs(x0 - ex) <= grab -> "end"
                            x0 > sx && x0 < ex -> "both"
                            kotlin.math.abs(x0 - sx) < kotlin.math.abs(x0 - ex) -> { start = Trim.moveStart(start, end, msAt(x0)); "start" }
                            else -> { end = Trim.moveEnd(start, end, msAt(x0), duration); "end" }
                        }
                        if (target != "both") active = target
                        if (trimPlaying) ClipPlayer.stop()
                        val startAt = start
                        down.consume()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            val x = ch.position.x.coerceIn(0f, w)
                            when (target) {
                                "start" -> start = Trim.moveStart(start, end, msAt(x))
                                "end" -> end = Trim.moveEnd(start, end, msAt(x), duration)
                                "both" -> {
                                    val len = end - start
                                    start = Trim.moveBoth(startAt, startAt + len, ((x - x0) / w * span).toLong(), duration)
                                    end = start + len
                                }
                            }
                            ch.consume()
                        }
                        if (target != "both") { settle(target); hear(target) }
                    }
                },
        ) {
            val span = (view.last - view.first).coerceAtLeast(1)
            val sx = (start - view.first) / span.toFloat() * size.width
            val ex = (end - view.first) / span.toFloat() * size.width
            // the kept part
            drawRect(Tints.teal.bg, Offset(sx, 0f), Size(ex - sx, size.height))
            // the sound: one bar per few pixels, lit inside the clip
            val bars = (size.width / 6f).toInt().coerceAtLeast(20)
            val bw = size.width / bars
            for (k in 0 until bars) {
                val t = view.first + span * k / bars
                val a = (envelope.getOrNull((t / Trim.ENVELOPE_MS).toInt()) ?: 0.08f).coerceIn(0.06f, 1f)
                val h = a * size.height * 0.7f
                drawRect(if (t in start..end) Tints.teal.ink else T.waveform, Offset(k * bw + bw * 0.25f, (size.height - h) / 2), Size(bw * 0.5f, h))
            }
            // the two handles: a line with a grip, brighter for the end ±1 s moves
            for ((x, which) in listOf(sx to "start", ex to "end")) {
                val col = if (active == which) T.text else Tints.teal.ink
                val line = 2.dp.toPx(); val gw = 12.dp.toPx(); val gh = 32.dp.toPx()
                drawRect(col, Offset(x - line / 2, 0f), Size(line, size.height))
                drawRoundRect(col, Offset(x - gw / 2, size.height / 2 - gh / 2), Size(gw, gh), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                // two little ridges on the grip, so it reads as something to drag
                for (k in listOf(-2.5f, 2.5f)) drawRect(T.sunken, Offset(x + k.dp.toPx() - 0.5.dp.toPx(), size.height / 2 - 8.dp.toPx()), Size(1.dp.toPx(), 16.dp.toPx()))
            }
            if (trimPlaying) {
                val px = (playback.posMs - view.first) / span.toFloat() * size.width
                drawRect(Color.White, Offset(px - 1f, 0f), Size(2f, size.height))
            }
        }
        // the two ends and the length; tapping a time picks that end for ±1 s
        Row(verticalAlignment = Alignment.CenterVertically) {
            EdgeTime("Start", clockAt(r, start), active == "start", Modifier.weight(1f)) { active = "start" }
            Text(clipLength(end - start), style = Type.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Length ${clipLength(end - start)}" })
            EdgeTime("End", clockAt(r, end), active == "end", Modifier.weight(1f), alignEnd = true) { active = "end" }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val which = if (active == "start") "start" else "end"
            Text("Move the $which", style = Type.small.copy(fontSize = 13.sp), modifier = Modifier.weight(1f))
            listOf(-1000L to "−1 s", 1000L to "+1 s").forEach { (d, t) ->
                Box(Modifier.width(64.dp).height(48.dp).clip(RoundedCornerShape(12.dp)).background(T.raised)
                    .clickable(role = Role.Button) {
                        if (active == "start") start = Trim.moveStart(start, end, start + d) else end = Trim.moveEnd(start, end, end + d, duration)
                        hear(active)
                    }
                    .semantics { contentDescription = "Move the $which 1 second ${if (d > 0) "later" else "earlier"}" },
                    contentAlignment = Alignment.Center) { Text(t, style = Type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium)) }
            }
        }
        Text(if (envelope.isEmpty()) "Drag the ends, or the lit part to move the whole clip."
            else "Drag the ends, or the lit part to move the whole clip. Each end settles in the nearest pause.",
            style = Type.small.copy(fontSize = 12.sp))
    }
    OutlineButton(if (trimPlaying) "Stop" else "Play ${clipLength(end - start)}", if (trimPlaying) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
        Modifier.fillMaxWidth()) { if (trimPlaying) ClipPlayer.stop() else playClip(r, c, "trim", start, end) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlineButton("Reset", modifier = Modifier.weight(1f), enabled = start != c.startMs || end != c.endMs) {
            start = c.startMs; end = c.endMs; view = Trim.window(c.startMs, c.endMs, duration)
        }
        OutlineButton("Cancel", modifier = Modifier.weight(1f)) { if (trimPlaying) ClipPlayer.stop(); onDone() }
        PrimaryButton("Save", modifier = Modifier.weight(1f)) {
            ClipPlayer.stop()
            ClipStore.updateClip(r.file, c.copy(startMs = start, endMs = end))
            Director.toast("Clip trimmed to ${clipLength(end - start)}")
            onDone()
        }
    }
}

/** One end's time under the trimmer; the one ±1 s moves is underlined in blue. */
@Composable
private fun EdgeTime(label: String, time: String, active: Boolean, modifier: Modifier, alignEnd: Boolean = false, onPick: () -> Unit) {
    Column(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClickLabel = "Fine-tune the ${label.lowercase()}", onClick = onPick)
            .semantics { stateDescription = if (active) "Selected" else "" }.padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = Type.small.copy(fontSize = 12.sp, color = if (active) T.accent else T.muted))
        Text(time, style = Type.body.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (active) T.text else T.text2))
        Box(Modifier.width(24.dp).height(2.dp).background(if (active) T.accent else Color.Transparent))
    }
}

/** An OBS recording's ⋯: its clip list for the editor (in its own times), and its AAC. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ObsSheet(o: ObsRecording, all: List<Recording>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var saving by remember { mutableStateOf("") }
    // the clips (or their parts) inside this recording, from every session that overlaps it
    val view = Recording(file = o.file, startedAt = o.startedAt, clips = all.flatMap { it.forObsFile(o.file).clips }.sortedBy { it.startMs })
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = Exporter.export(view, saving)
        if (uri != null) runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
            .onSuccess { Director.toast("Saved"); onDismiss() }.onFailure { Director.toast("Couldn't save the file") }
    }
    val aac = Director.aacFile(o.file)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = T.surface) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(o.file, style = Type.cardTitle.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium), modifier = Modifier.semantics { heading() })
                Text(whenText(o.startedAt) + " · " + Director.len(o.durationMs) + " · " + clipsText(view.clips.size), style = Type.small.copy(fontSize = 13.sp))
            }
            if (view.clips.isNotEmpty()) {
                Text("Export its clip list, in the recording's own times. Tap a format to share it, or the arrow to save it to the phone.", style = Type.small.copy(fontSize = 13.sp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(T.sunken)) {
                    exportFormats.forEachIndexed { i, f ->
                        if (i > 0) HorizontalDivider(color = T.rule)
                        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).heightIn(min = 60.dp).clickable(role = Role.Button, onClickLabel = "Share") {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, Exporter.export(view, f.id)), "Share clips"))
                                onDismiss()
                            }.padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(f.tint.bg), contentAlignment = Alignment.Center) {
                                    Text(f.badge, style = Type.label.copy(fontWeight = FontWeight.Bold, color = f.tint.ink))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(f.label, style = Type.body.copy(fontSize = 15.sp))
                                    Text(f.hint, style = Type.small.copy(fontSize = 12.sp))
                                }
                            }
                            Box(Modifier.padding(end = 6.dp).size(48.dp).clip(CircleShape).clickable(role = Role.Button) {
                                saving = f.id
                                save.launch("${o.baseName}.${Exporter.extension(f.id)}")
                            }.semantics { contentDescription = "Save the ${f.label} file to the phone" }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.FileDownload, null, tint = T.text2)
                            }
                        }
                    }
                }
            } else Text("No clips in this recording.", style = Type.small.copy(fontSize = 13.sp))
            if (aac.exists()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetTile("Share AAC", Icons.Outlined.Share, Tints.purple, Modifier.weight(1f)) { shareAac(context, o.file); onDismiss() }
                    SheetTile("Delete AAC", Icons.Outlined.DeleteOutline, Tints.rec, Modifier.weight(1f)) {
                        if (ClipPlayer.playback.value.key == "aac:${o.file}") ClipPlayer.stop()
                        Director.deleteAac(o.file); Director.toast("AAC deleted from this phone"); onDismiss()
                    }
                }
            }
            Text("The recording itself stays on the PC.", style = Type.small.copy(fontSize = 12.sp), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** A quiet, full-width red text button, for deleting. */
@Composable
fun DangerLink(text: String, onClick: () -> Unit) {
    Text(text, style = Type.body.copy(color = Tints.rec.ink, fontWeight = FontWeight.Medium), textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick).wrapContentHeight())
}

@Composable
private fun NoteEditor(initial: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var voice by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it.take(140) }, Modifier.weight(1f), label = { Text("Note for this clip") }, singleLine = true, colors = fieldColors(), textStyle = Type.body)
        Spacer(Modifier.width(8.dp))
        VoiceButton(onText = { text = if (text.isBlank()) it else "$text $it" }, onStatus = { voice = it })
    }
    if (voice.isNotEmpty()) Text(voice, style = Type.small)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlineButton("Cancel", Icons.Outlined.Close, Modifier.weight(1f)) { onCancel() }
        PrimaryButton("Save note", modifier = Modifier.weight(1f)) { onSave(text) }
    }
}

private data class Format(val id: String, val badge: String, val label: String, val hint: String, val tint: Tint)

private val exportFormats = listOf(
    Format("Resolve EDL", "EDL", "DaVinci Resolve", "Markers spanning each clip", Tints.blue),
    Format("CSV", "CSV", "Spreadsheet", "Start, end, length and note", Tints.ok),
    Format("Text", "TXT", "Plain list", "To paste into a message", Tints.amber),
)
