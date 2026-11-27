package dev.sharptalons.obsdirector.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** "Today, 20:35", "Yesterday, 10:02" or "Sunday 15 Nov, 10:02". */
fun recordingTitle(r: Recording): String {
    val c = Calendar.getInstance().apply { timeInMillis = r.startedAt }
    val today = Calendar.getInstance()
    val days = ((today.timeInMillis / 86_400_000L) - (r.startedAt / 86_400_000L)).toInt()
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(c.time)
    return when {
        days <= 0 && today.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR) -> "Today, $time"
        days == 1 -> "Yesterday, $time"
        else -> SimpleDateFormat("EEEE d MMM", Locale.getDefault()).format(c.time) + ", $time"
    }
}

private fun clipsText(n: Int) = when (n) { 0 -> "No clips"; 1 -> "1 clip"; else -> "$n clips" }

private fun clipKey(r: Recording, c: Clip) = "${r.file}:${c.id}"

private fun clipName(c: Clip) = c.note.ifBlank { "Clip at ${Director.tc(c.startMs)}" }

private fun clipMeta(c: Clip) = "${clipLength(c.lengthMs)} long · at ${Director.tc(c.startMs)}"

private fun recordingMeta(r: Recording) = (if (r.live) "Recording now" else Director.len(r.durationMs)) + " · " + clipsText(r.clips.size)

/**
 * Recordings, clips first, on one screen. Each recording is a card with its clips inside; tap a
 * heading to open or fold it. Every clip has its own Play button, and a small player shows above
 * the tabs while one plays. Tapping a clip opens one sheet with Play, Trim, Note, Share and
 * Delete; a recording's ⋯ opens Export and Delete. Recordings without clips stay folded at the end.
 *
 * [focus] opens and scrolls to one recording (Monitor's "See all").
 */
@Composable
fun RecordingsScreen(modifier: Modifier, top: (@Composable () -> Unit)?, focus: String? = null) {
    val all by ClipStore.recordings.collectAsState()
    val settings by Prefs.settings.collectAsState()
    val playback by ClipPlayer.playback.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // A recording deleted here waits a few seconds before it goes, so Undo can bring it back.
    var pendingRec by remember { mutableStateOf<String?>(null) }
    var undo by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    LaunchedEffect(undo) { if (undo != null) { delay(6000); undo = null } }
    LaunchedEffect(pendingRec) {
        val f = pendingRec ?: return@LaunchedEffect
        delay(6000)
        ClipStore.removeRecording(f)
        if (pendingRec == f) pendingRec = null
    }
    DisposableEffect(Unit) { onDispose { pendingRec?.let { ClipStore.removeRecording(it) } } }
    fun deleteRecording(file: String) {
        if (playback.key.startsWith("$file:")) ClipPlayer.stop()
        pendingRec?.let { ClipStore.removeRecording(it) }
        pendingRec = file
        undo = "Recording deleted" to { pendingRec = null }
    }

    val recordings = all.filter { it.file != pendingRec }
    // the recording OBS is making first, then the newest
    val withClips = recordings.filter { it.live || it.clips.isNotEmpty() }.sortedWith(compareBy<Recording>({ !it.live }, { -it.startedAt }))
    val empty = recordings.filter { !it.live && it.clips.isEmpty() }
    val totalClips = recordings.sumOf { it.clips.size }

    // the recording OBS is making and the two newest others start open
    var open by rememberSaveable {
        mutableStateOf(withClips.filter { it.live }.map { it.file } + withClips.filter { !it.live }.take(2).map { it.file })
    }
    var showEmpty by rememberSaveable { mutableStateOf(false) }
    var sheetClip by rememberSaveable { mutableStateOf<String?>(null) } // "file|clip id"
    var sheetRec by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmAll by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()

    LaunchedEffect(focus) {
        val f = focus ?: return@LaunchedEffect
        if (f !in open) open = open + f
        val i = withClips.indexOfFirst { it.file == f }
        if (i >= 0) list.animateScrollToItem(i + 1 + (if (top != null) 1 else 0))
    }

    val playingKey = if (playback.playing) playback.key else null

    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(), state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (playingKey != null || undo != null) 132.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            top?.let { t -> item { t() } }
            item(key = "title") {
                ScreenTitle("Recordings",
                    if (recordings.isEmpty()) "Kept on this phone"
                    else "${clipsText(totalClips)} in ${withClips.size} ${if (withClips.size == 1) "recording" else "recordings"} · kept on this phone")
            }
            if (recordings.isEmpty()) item {
                Section { Text("No recordings yet. Clips you mark while OBS records show up here, under their recording.", style = Type.body.copy(color = T.muted)) }
            }
            items(withClips, key = { it.file }) { r ->
                val isOpen = r.file in open
                RecordingCard(
                    r = r, open = isOpen, playingKey = playingKey,
                    savedOnPc = if (settings.pcClipLists) r.savedOnPc else null,
                    onToggle = { open = if (isOpen) open - r.file else open + r.file },
                    onMore = { sheetRec = r.file },
                    onPlay = { c -> val key = clipKey(r, c); if (playingKey == key) ClipPlayer.stop() else playClip(r, c, key) },
                    onOpenClip = { c -> sheetClip = "${r.file}|${c.id}" },
                )
            }
            if (empty.isNotEmpty()) {
                item(key = "empty-toggle") {
                    Row(
                        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { showEmpty = !showEmpty }
                            .semantics { stateDescription = if (showEmpty) "Expanded" else "Collapsed" }.padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (empty.size == 1) "1 recording without clips" else "${empty.size} recordings without clips",
                            style = Type.body.copy(fontSize = 15.sp, color = T.text2), modifier = Modifier.weight(1f))
                        Icon(if (showEmpty) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = T.muted)
                    }
                }
                if (showEmpty) item(key = "empty-list") {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.surface)) {
                        empty.forEachIndexed { i, r ->
                            if (i > 0) HorizontalDivider(color = T.rule)
                            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(recordingTitle(r), style = Type.body.copy(fontSize = 15.sp))
                                    Text(Director.len(r.durationMs), style = Type.small.copy(fontSize = 13.sp))
                                }
                                Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { deleteRecording(r.file) }
                                    .semantics { contentDescription = "Delete ${recordingTitle(r)}" }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.DeleteOutline, null, tint = Tints.rec.ink, modifier = Modifier.size(22.dp))
                                }
                            }
                        }
                    }
                }
            }
            if (recordings.isNotEmpty()) item(key = "delete-all") { DangerLink("Delete all recordings and clips") { confirmAll = true } }
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
    // and one for a recording: export and delete
    sheetRec?.let { file ->
        val r = all.firstOrNull { it.file == file }
        if (r == null) LaunchedEffect(file) { sheetRec = null }
        else RecordingSheet(r, onDelete = { deleteRecording(r.file); sheetRec = null }, onDismiss = { sheetRec = null })
    }
    if (confirmAll) ConfirmDelete(
        title = "Delete all recordings and clips?",
        body = "Removes every recording, clip, note and saved audio from this phone. The recordings on the PC aren't touched. " +
            "A recording OBS is making now keeps going, without its clips.",
        onConfirm = { ClipPlayer.stop(); val n = ClipStore.clearAll(); Director.toast(if (n == 1) "1 recording deleted" else "$n recordings deleted") },
        onDone = { confirmAll = false },
    )
}

/** A recording's card: its heading (tap to open or fold, ⋯ for more) and, when open, its clips. */
@Composable
private fun RecordingCard(r: Recording, open: Boolean, playingKey: String?, savedOnPc: String?, onToggle: () -> Unit, onMore: () -> Unit,
                          onPlay: (Clip) -> Unit, onOpenClip: (Clip) -> Unit) {
    val title = recordingTitle(r)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.surface)
        .then(if (r.live) Modifier.border(1.dp, Tints.rec.bg, RoundedCornerShape(18.dp)) else Modifier)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = 64.dp).clickable(role = Role.Button, onClickLabel = if (open) "Fold" else "Open", onClick = onToggle)
                    .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                    .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (r.live) Row(Modifier.clip(CircleShape).background(Tints.rec.bg).padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(T.negative))
                    Text("REC", style = Type.label.copy(fontWeight = FontWeight.Bold, color = Tints.rec.ink))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = Type.body.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium), modifier = Modifier.semantics { heading() })
                    Text(recordingMeta(r), style = Type.small.copy(fontSize = 13.sp))
                }
                Icon(Icons.Outlined.ExpandMore, null, tint = T.muted, modifier = Modifier.size(22.dp).rotate(if (open) 180f else 0f))
            }
            Box(Modifier.padding(end = 6.dp).size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onMore)
                .semantics { contentDescription = "More for $title" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.MoreHoriz, null, tint = T.text2)
            }
        }
        if (open) {
            HorizontalDivider(color = T.rule)
            if (r.clips.isEmpty()) Text("No clips yet. Tap Clip on Monitor when something good happens.", style = Type.body.copy(fontSize = 14.sp, color = T.muted),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp))
            r.clips.forEach { c ->
                val playing = playingKey == clipKey(r, c)
                Row(Modifier.fillMaxWidth().background(if (playing) Tints.blue.bg else T.surface).padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(if (playing) T.accent else T.raised)
                        .clickable(role = Role.Button) { onPlay(c) }
                        .semantics { contentDescription = (if (playing) "Stop " else "Play ") + clipName(c) }, contentAlignment = Alignment.Center) {
                        Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null, tint = if (playing) T.ground else T.text, modifier = Modifier.size(24.dp))
                    }
                    Column(Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClickLabel = "Open") { onOpenClip(c) }
                        .padding(vertical = 4.dp), verticalArrangement = Arrangement.Center) {
                        Text(c.note.ifBlank { "Add a note" }, style = Type.body.copy(fontSize = 15.sp,
                            fontWeight = if (c.note.isBlank()) FontWeight.Normal else FontWeight.Medium, color = if (c.note.isBlank()) T.muted else T.text),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(clipMeta(c), style = Type.small.copy(fontSize = 13.sp))
                    }
                    Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { onOpenClip(c) }
                        .semantics { contentDescription = "More for ${clipName(c)}" }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.MoreHoriz, null, tint = T.text2)
                    }
                }
            }
            savedOnPc?.let { pc ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.DesktopWindows, null, tint = Tints.ok.ink, modifier = Modifier.size(16.dp))
                    Text("The clip list is saved on $pc too, next to the recording.", style = Type.small.copy(fontSize = 12.sp, color = Tints.ok.ink))
                }
            }
        }
    }
}

/** The player above the tabs while a clip plays: which clip, how far, and Stop. */
@Composable
private fun MiniPlayer(all: List<Recording>, p: ClipPlayer.State) {
    val r = all.firstOrNull { p.key.startsWith(it.file + ":") }
    val c = r?.clips?.firstOrNull { clipKey(r, it) == p.key }
    val span = (p.toMs - p.fromMs).coerceAtLeast(1)
    val done = (p.posMs - p.fromMs).coerceIn(0, span)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.raised)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(c?.let { clipName(it) } ?: "Playing", style = Type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${Director.len(done)} of ${Director.len(span)}" + (r?.let { " · " + recordingTitle(it) } ?: ""), style = Type.small.copy(fontSize = 12.sp, color = T.text2))
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
                Text(when (mode) { "trim" -> "Trim clip"; "note" -> "Note"; else -> clipName(c) },
                    style = Type.cardTitle.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium), modifier = Modifier.semantics { heading() })
                Text(clipMeta(c) + " · " + recordingTitle(r), style = Type.small.copy(fontSize = 13.sp))
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

/** Trim: the sound around the clip with the kept part lit, then Start and End, 5 s at a time. */
@Composable
private fun TrimPanel(r: Recording, c: Clip, onDone: () -> Unit) {
    var start by remember(c.id) { mutableLongStateOf(c.startMs) }
    var end by remember(c.id) { mutableLongStateOf(c.endMs) }
    val playback by ClipPlayer.playback.collectAsState()
    val envelope = Director.liveEnvelope(r.file) ?: r.envelope
    val duration = if (r.live) Director.nowFileTimeMs() else r.durationMs.coerceAtLeast(envelope.size * 100L)
    val from = (minOf(start, c.startMs) - 30_000).coerceAtLeast(0)
    val to = (maxOf(end, c.endMs) + 30_000).let { if (duration > 0) it.coerceAtMost(duration) else it }
    val span = (to - from).coerceAtLeast(1)
    val trimPlaying = playback.playing && playback.key == "trim"
    Canvas(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(10.dp)).background(T.sunken)
        .semantics { contentDescription = "Sound around the clip. Kept: ${Director.tc(start)} to ${Director.tc(end)}" }) {
        val sx = (start - from) / span.toFloat() * size.width
        val ex = (end - from) / span.toFloat() * size.width
        drawRect(Tints.teal.bg, Offset(sx, 0f), Size(ex - sx, size.height))
        val bars = 60
        val bw = size.width / bars
        for (k in 0 until bars) {
            val t = from + span * k / bars
            val a = (envelope.getOrNull((t / 100).toInt()) ?: 0.05f).coerceIn(0.06f, 1f)
            val h = a * size.height * 0.85f
            drawRect(if (t in start..end) Tints.teal.ink else T.waveform, Offset(k * bw + bw * 0.2f, (size.height - h) / 2), Size(bw * 0.6f, h))
        }
        if (trimPlaying) {
            val px = (playback.posMs - from) / span.toFloat() * size.width
            drawRect(Color.White, Offset(px, 0f), Size(2f, size.height))
        }
    }
    TrimRow("Starts at", start, onEarlier = { start = (start - 5000).coerceAtLeast(0) }, onLater = { start = minOf(start + 5000, end - 1000) })
    TrimRow("Ends at", end, onEarlier = { end = maxOf(end - 5000, start + 1000) },
        onLater = { end = if (duration > 0) minOf(end + 5000, duration) else end + 5000 })
    OutlineButton(if (trimPlaying) "Stop" else "Play ${Director.len(end - start)}", if (trimPlaying) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
        Modifier.fillMaxWidth()) { if (trimPlaying) ClipPlayer.stop() else playClip(r, c, "trim", start, end) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlineButton("Cancel", modifier = Modifier.weight(1f)) { if (trimPlaying) ClipPlayer.stop(); onDone() }
        PrimaryButton("Save", modifier = Modifier.weight(1f)) {
            ClipPlayer.stop()
            ClipStore.updateClip(r.file, c.copy(startMs = start, endMs = end))
            Director.toast("Clip trimmed to ${clipLength(end - start)}")
            onDone()
        }
    }
}

@Composable
private fun TrimRow(label: String, ms: Long, onEarlier: () -> Unit, onLater: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).background(T.sunken).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.small.copy(fontSize = 13.sp))
            Text(Director.tc(ms), style = Type.body.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium))
        }
        listOf(Triple("−5 s", "5 seconds earlier", onEarlier), Triple("+5 s", "5 seconds later", onLater)).forEach { (t, said, go) ->
            Box(Modifier.width(64.dp).height(48.dp).clip(RoundedCornerShape(12.dp)).background(T.raised)
                .clickable(role = Role.Button, onClick = go).semantics { contentDescription = "$label: $said" },
                contentAlignment = Alignment.Center) { Text(t, style = Type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium)) }
        }
    }
}

/** A recording's ⋯: export its clip list (share it, or save it to the phone) and delete it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingSheet(r: Recording, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var saving by remember { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = Exporter.export(r, saving)
        if (uri != null) runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
            .onSuccess { Director.toast("Saved"); onDismiss() }.onFailure { Director.toast("Couldn't save the file") }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = T.surface) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(recordingTitle(r), style = Type.cardTitle.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium), modifier = Modifier.semantics { heading() })
                Text(recordingMeta(r) + " · " + r.file, style = Type.small.copy(fontSize = 13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (r.clips.isNotEmpty()) {
                Text("Export the clip list for your editor. Tap a format to share it, or the arrow to save it to the phone.", style = Type.small.copy(fontSize = 13.sp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(T.sunken)) {
                    exportFormats.forEachIndexed { i, f ->
                        if (i > 0) HorizontalDivider(color = T.rule)
                        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).heightIn(min = 60.dp).clickable(role = Role.Button, onClickLabel = "Share") {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, Exporter.export(r, f.id)), "Share clips"))
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
                                save.launch("${r.file.substringBeforeLast('.')}.${Exporter.extension(f.id)}")
                            }.semantics { contentDescription = "Save the ${f.label} file to the phone" }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.FileDownload, null, tint = T.text2)
                            }
                        }
                    }
                }
            }
            if (!r.live) {
                DangerLink("Delete this recording", onDelete)
                Text("Only from this phone. The recording on the PC isn't touched.", style = Type.small.copy(fontSize = 12.sp),
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
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
