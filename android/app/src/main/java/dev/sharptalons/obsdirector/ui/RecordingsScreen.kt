package dev.sharptalons.obsdirector.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val tileTints = listOf(Tints.teal, Tints.purple, Tints.amber, Tints.ok)

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

/** The list: one plain row per recording; tap one to see its clips. */
@Composable
fun RecordingsScreen(modifier: Modifier, top: @Composable () -> Unit, onOpen: (String) -> Unit) {
    val recordings by ClipStore.recordings.collectAsState()
    val live = recordings.filter { it.live }
    val past = recordings.filterNot { it.live }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { top() }
        item { ScreenTitle("Recordings", "Tap one to see its clips and export them") }
        if (recordings.isEmpty()) item {
            Section { Text("No recordings yet. Clips you mark while OBS records show up here, one row per recording.", style = Type.body.copy(color = T.muted)) }
        }
        if (live.isNotEmpty()) item { GroupLabel("Now", Tints.rec.ink) }
        items(live, key = { "live:" + it.file }) { r -> RecordingRow(r, null, onOpen) }
        if (past.isNotEmpty()) item { GroupLabel("Earlier", T.muted) }
        itemsIndexed(past, key = { _, r -> r.file }) { i, r -> RecordingRow(r, tileTints[i % tileTints.size], onOpen) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun RecordingRow(r: Recording, tint: Tint?, onOpen: (String) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = r.startedAt }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(18.dp)).background(T.surface)
            .then(if (tint == null) Modifier.border(1.dp, Tints.rec.bg, RoundedCornerShape(18.dp)) else Modifier)
            .clickable(role = Role.Button) { onOpen(r.file) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (tint == null) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Tints.rec.bg), contentAlignment = Alignment.Center) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(T.negative))
            }
        } else {
            Column(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tint.bg), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("${c.get(Calendar.DAY_OF_MONTH)}", style = Type.body.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = tint.ink, lineHeight = 16.sp))
                Text(SimpleDateFormat("MMM", Locale.getDefault()).format(c.time).uppercase(), style = Type.label.copy(fontWeight = FontWeight.Medium, color = tint.ink))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(recordingTitle(r), style = Type.body.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium))
            Text(listOfNotNull(if (r.live) "Recording" else Director.len(r.durationMs), clipsText(r.clips.size)).joinToString(" · "), style = Type.small.copy(fontSize = 13.sp))
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = T.muted, modifier = Modifier.size(20.dp))
    }
}

/** One recording: its clips, each with a + for its actions, and Export at the top. */
@Composable
fun RecordingScreen(file: String, modifier: Modifier, top: @Composable () -> Unit, onBack: () -> Unit) {
    val recordings by ClipStore.recordings.collectAsState()
    val settings by Prefs.settings.collectAsState()
    val playback by ClipPlayer.playback.collectAsState()
    val r = recordings.firstOrNull { it.file == file }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableStateOf("actions") } // actions, adjust, note
    var removed by remember { mutableStateOf<Clip?>(null) }
    var exporting by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { top() }
        item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to Recordings", T.text, onBack)
            Column(Modifier.weight(1f)) {
                Text(r?.let { recordingTitle(it) } ?: "Recording", style = Type.title.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), modifier = Modifier.semantics { heading() })
                Text(listOfNotNull(r?.let { if (it.live) "Recording" else Director.len(it.durationMs) }, r?.let { clipsText(it.clips.size) }).joinToString(" · "), style = Type.small.copy(fontSize = 13.sp))
            }
            PrimaryButton("Export", Icons.Outlined.IosShare, enabled = r?.clips?.isNotEmpty() == true) { exporting = true }
        } }
        if (r == null) { item { Text("This recording isn't on this phone any more.", style = Type.body) }; return@LazyColumn }
        item { Text(r.file, style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp)) }

        if (settings.pcClipLists && r.savedOnPc != null) item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Tints.ok.bg).padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.DesktopWindows, null, tint = Tints.ok.ink, modifier = Modifier.size(20.dp))
                Text("Saved on ${r.savedOnPc} too. The clip list is next to the recording as ${r.file.substringBeforeLast('.')}.edl and .csv, ready for the editor.",
                    style = Type.body.copy(fontSize = 13.sp, color = Tints.ok.ink))
            }
        }

        removed?.let { c -> item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(T.raised).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Clip at ${Director.tc(c.startMs)} deleted.", style = Type.body, modifier = Modifier.weight(1f))
                OutlineButton("Undo", Icons.AutoMirrored.Outlined.Undo) { ClipStore.addClip(r.file, c); removed = null }
            }
        } }
        if (r.clips.isEmpty()) item { Section { Text("No clips in this recording.", style = Type.body.copy(color = T.muted)) } }

        itemsIndexed(r.clips, key = { _, c -> c.id }) { i, c ->
            val isOpen = open == c.id
            val key = "${r.file}:${c.id}"
            val playing = playback.playing && playback.key == key
            val tint = listOf(Tints.rec, Tints.blue, Tints.amber, Tints.teal)[i % 4]
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(T.surface)
                .border(1.dp, if (isOpen) T.accent else T.surface, RoundedCornerShape(18.dp))) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(tint.bg), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = Type.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = tint.ink))
                    }
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(clipLength(c.lengthMs), style = Type.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium))
                            Text(" · at ${Director.tc(c.startMs)}", style = Type.body.copy(fontSize = 15.sp, color = T.muted))
                        }
                        Text(if (playing) "Playing ${Director.len(playback.posMs - playback.fromMs)} of ${Director.len(c.lengthMs)}" else c.note.ifBlank { "No note" },
                            style = Type.body.copy(fontSize = 13.sp, color = if (playing) Tints.ok.ink else if (c.note.isBlank()) T.muted else T.text2), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(if (isOpen) T.blue else T.raised)
                            .clickable(role = Role.Button) { open = if (isOpen) null else c.id; mode = "actions" }
                            .semantics { contentDescription = (if (isOpen) "Close actions for clip " else "Actions for clip ") + (i + 1); stateDescription = if (isOpen) "Expanded" else "Collapsed" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Add, null, tint = T.text, modifier = Modifier.size(20.dp).rotate(if (isOpen) 45f else 0f)) }
                }
                if (isOpen) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (mode) {
                        "adjust" -> AdjustPanel(r, c, onClose = { mode = "actions" })
                        "note" -> NoteEditor(c.note, onSave = { t -> ClipStore.updateClip(r.file, c.copy(note = t.trim())); mode = "actions" }, onCancel = { mode = "actions" })
                        else -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionTile(if (playing) "Stop" else "Play", if (playing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, Tints.blue, Modifier.weight(1f)) {
                                    if (playing) ClipPlayer.stop() else playClip(r, c, key)
                                }
                                ActionTile("Adjust", Icons.Outlined.Tune, Tints.teal, Modifier.weight(1f)) { mode = "adjust" }
                                ActionTile("Note", Icons.Outlined.Edit, Tints.amber, Modifier.weight(1f)) { mode = "note" }
                                ActionTile("Share", Icons.Outlined.Share, Tints.purple, Modifier.weight(1f)) { scope.launch { shareClipAudio(context, r, c) } }
                            }
                            Text("Delete clip", style = Type.body.copy(color = Tints.rec.ink, fontWeight = FontWeight.Medium),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                                    .clickable(role = Role.Button) { ClipPlayer.stop(); ClipStore.removeClip(r.file, c.id); removed = c; open = null }
                                    .padding(vertical = 12.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    if (exporting && r != null) ExportSheet(r) { exporting = false }
}

@Composable
private fun ActionTile(label: String, icon: ImageVector, tint: Tint, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(64.dp).clip(RoundedCornerShape(14.dp)).background(tint.bg).clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = tint.ink, modifier = Modifier.size(22.dp))
        Text(label, style = Type.label.copy(color = tint.ink, fontWeight = FontWeight.Medium), modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun NoteEditor(initial: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var voice by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it.take(140) }, Modifier.weight(1f), label = { Text("Note") }, singleLine = true, colors = fieldColors(), textStyle = Type.body)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(r: Recording, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings by Prefs.settings.collectAsState()
    var format by remember { mutableStateOf(settings.exportFormat) }
    val text = Exporter.export(r, format)
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
            .onSuccess { Director.toast("Saved"); onDismiss() }.onFailure { Director.toast("Couldn't save the file") }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = T.surface) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Export clips", style = Type.cardTitle.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), modifier = Modifier.semantics { heading() })
            Text("${clipsText(r.clips.size)} from ${recordingTitle(r)}", style = Type.small.copy(fontSize = 13.sp))
            exportFormats.forEach { f ->
                val on = f.id == format
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(14.dp)).background(T.sunken)
                        .border(2.dp, if (on) T.accent else T.raised, RoundedCornerShape(14.dp))
                        .clickable(role = Role.RadioButton) { format = f.id }.semantics { selected = on }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(f.tint.bg), contentAlignment = Alignment.Center) {
                        Text(f.badge, style = Type.label.copy(fontWeight = FontWeight.Bold, color = f.tint.ink))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(f.label, style = Type.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium))
                        Text(f.hint, style = Type.small)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                OutlineButton("Save to phone", modifier = Modifier.weight(1f), height = 52.dp) {
                    save.launch("${r.file.substringBeforeLast('.')}.${Exporter.extension(format)}")
                }
                PrimaryButton("Share", Icons.Outlined.Share, Modifier.weight(1f), height = 52.dp) {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share clips"))
                    onDismiss()
                }
            }
        }
    }
}
