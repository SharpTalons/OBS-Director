package dev.sharptalons.obsdirector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sharptalons.obsdirector.core.Director
import dev.sharptalons.obsdirector.core.LinkState
import dev.sharptalons.obsdirector.core.Prefs
import dev.sharptalons.obsdirector.core.Settings
import dev.sharptalons.obsdirector.update.Updates

/** One row in a group: a switch, a choice that opens a sheet, an action, or a plain value. */
private sealed class Row(val icon: ImageVector, val label: String) {
    class Switch(icon: ImageVector, label: String, val on: Boolean, val set: (Settings, Boolean) -> Settings) : Row(icon, label)
    class Choice(icon: ImageVector, label: String, val value: String, val options: List<String>, val pick: (Settings, String) -> Settings) : Row(icon, label)
    class Action(icon: ImageVector, label: String, val value: String, val onClick: () -> Unit) : Row(icon, label)
    class Info(icon: ImageVector, label: String, val value: String) : Row(icon, label)
    class Slider(icon: ImageVector, label: String, val percent: Int, val set: (Settings, Int) -> Settings) : Row(icon, label)
}

private class Group(val title: String, val tint: Tint, val rows: List<Row>)

private val formatLabels = mapOf("Resolve EDL" to "DaVinci Resolve", "CSV" to "Spreadsheet", "Text" to "Plain list")

private fun secs(v: String) = v.removeSuffix(" s").toFloat()

/**
 * Settings: one list in coloured groups. Switches flip in place; a choice opens a short sheet
 * from the bottom. Everything is saved on this phone as soon as it changes.
 */
@Composable
fun SettingsScreen(onConnect: () -> Unit) {
    val s by Prefs.settings.collectAsState()
    val link by Director.link.collectAsState()
    val pc by Director.pc.collectAsState()
    val output by Director.router.outputName.collectAsState()
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var choosingOutput by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val outputs by Director.router.available.collectAsState()
    val onBluetooth = remember(output) { !Director.router.current().lowLatency }
    val recordings by dev.sharptalons.obsdirector.core.ClipStore.recordings.collectAsState()
    val chosen = outputs.firstOrNull { it.key == s.outputPref }
    val playThrough = when {
        s.outputPref == "auto" -> "Automatic · $output"
        chosen != null -> chosen.label
        else -> "Not connected · $output for now"
    }
    var help by rememberSaveable { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // checked again each time Settings comes back to the front, after the person visits Android's page
    var canBubble by remember { mutableStateOf(dev.sharptalons.obsdirector.service.BubbleService.canShow(context)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        canBubble = dev.sharptalons.obsdirector.service.BubbleService.canShow(context)
        onPauseOrDispose { }
    }

    val groups = listOf(
        Group("Listening", Tints.teal, listOf(
            Row.Action(Icons.Outlined.Headphones, "Play through", playThrough) { choosingOutput = true },
            Row.Action(Icons.AutoMirrored.Outlined.VolumeUp, "Play a test sound", "Play") { dev.sharptalons.obsdirector.audio.TestSound.play(Director.router) },
            Row.Switch(Icons.Outlined.PlayArrow, "Start listening when connected", s.autoListen) { x, v -> x.copy(autoListen = v) },
            // turns the live mix down while a clip or an OBS recording's AAC plays, so it can be heard over it
            Row.Slider(Icons.Outlined.VolumeDown, "Lower the mix while playing", s.duckPct) { x, v -> x.copy(duckPct = v) },
            // Bluetooth always keeps at least 160 ms queued to play smoothly; say so beside the choice
            Row.Choice(Icons.Outlined.Schedule, "Delay",
                (if (s.bufferMs == 0) "Auto" else "${s.bufferMs} ms") + (if (onBluetooth && s.bufferMs < dev.sharptalons.obsdirector.audio.AudioEngine.BT_CUSHION_MS) " · 160 ms on Bluetooth" else ""),
                listOf("Auto", "20 ms", "40 ms", "80 ms", "160 ms")) { x, v ->
                x.copy(bufferMs = if (v == "Auto") 0 else v.removeSuffix(" ms").toInt())
            },
        )),
        Group("Connection", Tints.ok, listOf(
            Row.Action(Icons.Outlined.DesktopWindows, "OBS PC",
                if (link == LinkState.DISCONNECTED) "Find PCs" else pc?.name ?: pc?.ip ?: "OBS PC", onConnect),
            Row.Action(Icons.Outlined.PhoneAndroid, "This phone in OBS", s.deviceName) { renaming = true },
        )),
        Group("Controls", Tints.amber, listOf(
            Row.Choice(Icons.Outlined.Timer, "Hold to stop recording", if (s.holdMs == 500) "0.5 s" else "${s.holdMs / 1000} s", listOf("0.5 s", "1 s", "2 s")) { x, v ->
                x.copy(holdMs = (secs(v) * 1000).toInt())
            },
            Row.Switch(Icons.Outlined.PowerSettingsNew, "Allow ending the stream", s.allowStreamStop) { x, v -> x.copy(allowStreamStop = v) },
            Row.Switch(Icons.Outlined.LightMode, "Keep the screen on", s.keepScreenOn) { x, v -> x.copy(keepScreenOn = v) },
            Row.Choice(Icons.Outlined.TouchApp, "Touch sound", if (s.touchSound == 0) "Off" else "${s.touchSound}%", listOf("Off", "25%", "50%", "75%", "100%")) { x, v ->
                x.copy(touchSound = if (v == "Off") 0 else v.removeSuffix("%").toInt())
            },
            Row.Choice(Icons.Outlined.Vibration, "Vibration", listOf("Off", "Light", "Medium", "Strong")[s.haptics.coerceIn(0, 3)], listOf("Off", "Light", "Medium", "Strong")) { x, v ->
                x.copy(haptics = listOf("Off", "Light", "Medium", "Strong").indexOf(v).coerceAtLeast(0))
            },
        ) + listOf(
            Row.Switch(Icons.Outlined.PictureInPictureAlt, "Floating bubble when you leave", s.bubble) { x, v -> x.copy(bubble = v) },
        ) + (if (s.bubble && !canBubble) listOf(
            Row.Action(Icons.Outlined.PictureInPictureAlt, "Allow the bubble over other apps", "Allow") {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + context.packageName)))
            },
        ) else emptyList())),
        Group("Clips", Tints.rec, listOf(
            // "Clip 2" where it starts, "Clip 2 – Choir intro" where it ends (once the note is in)
            Row.Switch(Icons.Outlined.Bookmark, "Chapter markers in the OBS recording", s.chapters) { x, v -> x.copy(chapters = v) },
            Row.Switch(Icons.Outlined.TaskAlt, "Save the clip list on the PC", s.pcClipLists) { x, v -> x.copy(pcClipLists = v) },
            Row.Choice(Icons.Outlined.IosShare, "Export as", formatLabels[s.exportFormat] ?: s.exportFormat, formatLabels.values.toList()) { x, v ->
                x.copy(exportFormat = formatLabels.entries.first { it.value == v }.key)
            },
        )),
        Group("Alerts", Tints.orange, listOf(
            Row.Choice(Icons.Outlined.VolumeOff, "Mix goes silent for", if (s.silenceSeconds == 0) "Off" else "${s.silenceSeconds} s", listOf("Off", "10 s", "20 s", "30 s")) { x, v ->
                x.copy(silenceSeconds = if (v == "Off") 0 else secs(v).toInt())
            },
            Row.Switch(Icons.Outlined.Stop, "Someone else stops the recording", s.alertRecStop) { x, v -> x.copy(alertRecStop = v) },
            Row.Choice(Icons.Outlined.Storage, "Disk space below", "${s.diskHours} h", listOf("1 h", "2 h", "4 h")) { x, v -> x.copy(diskHours = v.removeSuffix(" h").toInt()) },
            Row.Choice(Icons.Outlined.ShowChart, "Dropped frames over", "${s.dropsPerMinute} a minute", listOf("50 a minute", "100 a minute", "500 a minute")) { x, v ->
                x.copy(dropsPerMinute = v.substringBefore(' ').toInt())
            },
            Row.Choice(Icons.Outlined.BatteryAlert, "Warn when the battery is below", if (s.batteryWarn == 0) "Off" else "${s.batteryWarn}%", listOf("Off", "20%", "15%", "10%")) { x, v ->
                x.copy(batteryWarn = if (v == "Off") 0 else v.removeSuffix("%").toInt())
            },
        )),
        Group("Audio on this phone", Tints.purple, listOf(
            Row.Switch(Icons.Outlined.Mic, "Save the session audio", s.saveAudio) { x, v -> x.copy(saveAudio = v) },
            Row.Choice(Icons.Outlined.CalendarMonth, "Keep it for", when (s.keepDays) { 0 -> "Forever"; 7 -> "7 days"; else -> "30 days" }, listOf("7 days", "30 days", "Forever")) { x, v ->
                x.copy(keepDays = when (v) { "7 days" -> 7; "Forever" -> 0; else -> 30 })
            },
            Row.Action(Icons.Outlined.DeleteSweep, "Delete all recordings and clips",
                if (recordings.isEmpty()) "None stored" else "${recordings.size} stored") { if (recordings.isNotEmpty()) confirmClear = true },
        )),
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Settings", "Saved on this phone as you change them")
        groups.forEach { g ->
            GroupLabel(g.title, g.tint.ink, Modifier.padding(top = 8.dp))
            Section(padding = 0.dp, gap = 0.dp) {
                g.rows.forEachIndexed { i, r ->
                    val divider = i > 0
                    when (r) {
                        is Row.Switch -> SettingItem(r.icon, g.tint, r.label, divider, checked = r.on, onClick = { Prefs.update { x -> r.set(x, !r.on) } })
                        is Row.Choice -> SettingItem(r.icon, g.tint, r.label, divider, value = r.value, onClick = { sheet = r.label })
                        is Row.Action -> SettingItem(r.icon, g.tint, r.label, divider, value = r.value, onClick = r.onClick)
                        is Row.Info -> SettingItem(r.icon, g.tint, r.label, divider, value = r.value)
                        is Row.Slider -> SliderItem(r, g.tint, divider)
                    }
                }
            }
        }
        GroupLabel("About", T.muted, Modifier.padding(top = 8.dp))
        Section {
            Column {
                Text("${Updates.NAME} ${Updates.CURRENT_VERSION}", style = Type.body.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium))
                val plugin = Director.pluginVersion
                val app = Updates.CURRENT_VERSION.substringBefore('-')
                Text(
                    when {
                        plugin.isBlank() -> "Released together with the OBS plugin, same version number"
                        plugin == app -> "Same version as the plugin"
                        else -> "The plugin on the PC is $plugin. Install both from the same release so they match."
                    },
                    style = Type.small.copy(fontSize = 13.sp, color = if (plugin.isNotBlank() && plugin != app) T.warning else T.muted),
                )
            }
            UpdateRow()
            OutlineButton("How OBS Director works", Icons.AutoMirrored.Outlined.HelpOutline, Modifier.fillMaxWidth()) { help = true }
            Text("Settings, clips and saved audio stay on this phone. The app talks only to the OBS PC you connect to, and to GitHub to check for updates.", style = Type.small)
        }
    }

    val open = groups.flatMap { it.rows }.filterIsInstance<Row.Choice>().firstOrNull { it.label == sheet }
    if (open != null) {
        ChoiceSheet(open.label, open.options, open.value, onPick = { v ->
            if (open.label == "Delay") Director.setBuffer(if (v == "Auto") 0 else v.removeSuffix(" ms").toInt())
            else Prefs.update { x -> open.pick(x, v) }
            // feel the new strength straight away
            if (open.label == "Vibration") Director.vibrate(FeedbackTokens.PREVIEW)
            if (open.label == "Touch sound") dev.sharptalons.obsdirector.core.Feedback.playTap(Prefs.settings.value.touchSound)
            sheet = null
        }, onDismiss = { sheet = null })
    }
    if (renaming) RenameDialog(s.deviceName) { renaming = false }
    if (choosingOutput) {
        // "Automatic" first, then every output connected now, by name.
        val labels = listOf("Automatic (what the phone uses)") + outputs.map { it.label }
        val current = if (chosen == null) labels[0] else chosen.label
        ChoiceSheet("Play through", labels, current, onPick = { v ->
            val key = outputs.firstOrNull { it.label == v }?.key ?: "auto"
            Prefs.update { it.copy(outputPref = key) }
            choosingOutput = false
        }, onDismiss = { choosingOutput = false })
    }
    if (help) HelpDialog { help = false }
    if (confirmClear) ConfirmDelete(
        title = "Delete all recordings and clips?",
        body = "Removes every recording, clip, note and saved audio from this phone. The recordings on the PC aren't touched. " +
            "A recording OBS is making now keeps going, without its clips.",
        onConfirm = { val n = dev.sharptalons.obsdirector.core.ClipStore.clearAll(); Director.toast(if (n == 1) "1 recording deleted" else "$n recordings deleted") },
        onDone = { confirmClear = false },
    )
}

@Composable
private fun RenameDialog(current: String, onDone: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = T.surface,
        title = { Text("This phone in OBS", style = Type.cardTitle) },
        text = {
            Column {
                Text("Volunteers see this name in the OBS dock. A new name shows from the next connection.", style = Type.small)
                OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth().padding(top = 12.dp), singleLine = true,
                    label = { Text("Name") }, colors = fieldColors(), textStyle = Type.body, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) Prefs.update { it.copy(deviceName = name.trim()) }; onDone() }) { Text("Save", color = T.accent) } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel", color = T.text2) } },
    )
}

/** Asks once before deleting something that can't come back. */
@Composable
fun ConfirmDelete(title: String, body: String, confirmLabel: String = "Delete", onConfirm: () -> Unit, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = T.surface,
        title = { Text(title, style = Type.cardTitle) },
        text = { Text(body, style = Type.body.copy(color = T.text2)) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDone() }) { Text(confirmLabel, color = T.negative) } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel", color = T.text2) } },
    )
}

/** A short guide to the whole app, from About in Settings. */
@Composable
private fun HelpDialog(onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = T.surface,
        title = { Text("How OBS Director works", style = Type.cardTitle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                HowStep("1", "Connect", "Open OBS with the plugin on the PC, on the same Wi-Fi. Tap the connect icon at the top and choose the PC. The first time, click Allow on the PC.")
                HowStep("2", "Listen", "Turn on Listen on Monitor to hear the OBS mix. Choose where it plays in Settings, under Listening.")
                HowStep("3", "Mark clips", "Tap Clip when something good happens and End when it's over. The clip starts a little before your tap, so you won't miss the start.")
                HowStep("4", "Use the clips", "Recordings lists each recording with its clips. Play, adjust, add a note, share, or export the list for your editor.")
                HowStep("5", "Talk to the desk", "The Desk tab is a group chat with the OBS dock and other phones. Alerts, like a full disk, show up there too.")
                HowStep("6", "Stage mode", "The masks icon at the top shows a big, dim timer and Clip button for use beside a stage.")
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done", color = T.accent) } },
    )
}

/** A setting with a 0–100% slider under its name: the value shows as you drag, and saves on release. */
@Composable
private fun SliderItem(r: Row.Slider, tint: Tint, divider: Boolean) {
    var v by remember(r.percent) { mutableStateOf(r.percent.toFloat()) }
    Column {
        if (divider) HorizontalDivider(color = T.raised)
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TintTile(r.icon, tint)
                Text(r.label, style = Type.body.copy(fontSize = 15.sp), modifier = Modifier.weight(1f))
                Text(if (v.roundToInt() == 0) "Off" else "${v.roundToInt()}%", style = Type.body.copy(color = tint.ink))
            }
            androidx.compose.material3.Slider(v, { v = (it / 5).roundToInt() * 5f }, Modifier.fillMaxWidth().semantics { contentDescription = r.label },
                valueRange = 0f..100f, onValueChangeFinished = { Prefs.update { x -> r.set(x, v.roundToInt()) } },
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = T.text, activeTrackColor = tint.ink, inactiveTrackColor = T.control))
            Text("A clip or an OBS recording's AAC plays over the live mix lowered this much. 100% silences it. Clipping and OBS carry on either way.",
                style = Type.small.copy(fontSize = 12.sp), modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}
