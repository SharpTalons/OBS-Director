package dev.sharptalons.obsdirector.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.sharptalons.obsdirector.core.Director
import dev.sharptalons.obsdirector.core.Exporter
import dev.sharptalons.obsdirector.core.LinkState
import dev.sharptalons.obsdirector.core.Prefs
import dev.sharptalons.obsdirector.core.Tag
import dev.sharptalons.obsdirector.update.Updates

/** One Settings page: what it's called, its icon, and a line saying what's set there now. */
private class Page(val id: String, val title: String, val icon: ImageVector, val summary: String)

/** The app's version without the "-debug" suffix, to compare with the plugin's. */
private val appVersion get() = Updates.CURRENT_VERSION.substringBefore('-')

/**
 * Settings: a short menu of pages, each opening on its own with a back arrow (the phone's Back
 * button goes back too). Everything is saved on this phone as soon as it changes.
 */
@Composable
fun SettingsScreen(onConnect: () -> Unit) {
    val s by Prefs.settings.collectAsState()
    val link by Director.link.collectAsState()
    val pc by Director.pc.collectAsState()
    var open by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = open.isNotEmpty()) { open = "" }

    val pages = listOf(
        Page("connection", "Connection", Icons.Outlined.Cast,
            if (link == LinkState.CONNECTED) "Connected to ${pc?.name ?: "the PC"} as ${s.deviceName}" else "Not connected · this phone is ${s.deviceName}"),
        Page("listening", "Listening", Icons.Outlined.Headphones,
            "Buffer ${if (s.bufferMs == 0) "auto" else "${s.bufferMs} ms"} · ${if (s.autoListen) "starts when connected" else "starts when you tap Listen"}"),
        Page("saved", "Saved audio", Icons.Outlined.SaveAlt,
            if (s.saveAudio) "Recorded on this phone · kept ${if (s.keepDays == 0) "forever" else "${s.keepDays} days"}" else "Off"),
        Page("clips", "Clips and export", Icons.Outlined.ContentCut,
            "Start ${s.leadMinutes} min before the tap · export as ${s.exportFormat}"),
        Page("alerts", "Alerts", Icons.Outlined.NotificationsActive,
            "Silence ${if (s.silenceSeconds == 0) "off" else "after ${s.silenceSeconds} s"} · disk below ${s.diskHours} h"),
        Page("video", "Live picture", Icons.Outlined.Videocam, "Off each time · stops with the screen"),
        Page("controls", "Controls and display", Icons.Outlined.TouchApp,
            "Hold ${if (s.holdMs == 500) "0.5" else "${s.holdMs / 1000}"} s to stop · screen ${if (s.keepScreenOn) "stays on" else "can sleep"}"),
        Page("about", "Updates and about", Icons.Outlined.Info, "${Updates.NAME} ${Updates.CURRENT_VERSION}"),
    )

    val page = pages.firstOrNull { it.id == open }
    if (page == null) {
        Text("Saved on this phone only.", style = Type.finding)
        Spacer(Modifier.height(12.dp))
        DCard(padding = 6.dp) {
            pages.forEachIndexed { i, p ->
                MenuRow(p) { open = p.id }
                if (i < pages.lastIndex) HorizontalDivider(color = T.rule, modifier = Modifier.padding(start = 52.dp))
            }
        }
        return
    }

    // A page: back arrow and title, then its settings.
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(6.dp)).clickable { open = "" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to Settings", tint = T.text, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(page.title, style = Type.cardTitle)
    }
    Spacer(Modifier.height(8.dp))
    DCard {
        Column {
            when (page.id) {
                "connection" -> ConnectionPage(onConnect)
                "listening" -> ListeningPage()
                "saved" -> SavedAudioPage()
                "clips" -> ClipsPage()
                "alerts" -> AlertsPage()
                "video" -> VideoPage()
                "controls" -> ControlsPage()
                "about" -> AboutPage()
            }
        }
    }
}

@Composable
private fun MenuRow(p: Page, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(p.icon, null, tint = T.accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = Type.body.copy(fontWeight = FontWeight.Medium))
            Text(p.summary, style = Type.small, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = T.muted, modifier = Modifier.size(22.dp))
    }
}

/** A small heading inside a page, to group its rows. */
@Composable
private fun Group(title: String, why: String? = null) {
    Spacer(Modifier.height(14.dp))
    Text(title, style = Type.label.copy(color = T.accent, fontWeight = FontWeight.Medium))
    if (why != null) Text(why, style = Type.small, modifier = Modifier.padding(top = 2.dp))
    Spacer(Modifier.height(4.dp))
}

/** A plain line of explanation under a setting. */
@Composable
private fun Note(text: String) = Text(text, style = Type.small, modifier = Modifier.padding(vertical = 8.dp))

@Composable
private fun VersionMatch() {
    val plugin = Director.pluginVersion
    if (plugin.isBlank()) return
    if (plugin == appVersion) Note("The plugin and this app are both version $appVersion.")
    else Text(
        "The plugin on the PC is $plugin and this app is $appVersion. Install both from the same release so they match.",
        style = Type.small.copy(color = T.warning), modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun ConnectionPage(onConnect: () -> Unit) {
    val s by Prefs.settings.collectAsState()
    val link by Director.link.collectAsState()
    val pc by Director.pc.collectAsState()

    Group("OBS PC")
    if (link == LinkState.DISCONNECTED) {
        SettingRow("Not connected") { PrimaryButton("Connect", Icons.Outlined.Power, height = 34.dp) { onConnect() } }
    } else {
        SettingRow(if (link == LinkState.CONNECTED) "Connected to" else "Connecting to") {
            Text("${pc?.name ?: ""}\n${pc?.ip ?: ""}", style = Type.mono, textAlign = TextAlign.End)
        }
        SettingRow("Use a different PC") { OutlineButton("Change", Icons.Outlined.Cast, height = 34.dp) { onConnect() } }
        SettingRow("Stop following this PC") { OutlineButton("Disconnect", Icons.Outlined.WifiOff, height = 34.dp) { Director.disconnect() } }
        VersionMatch()
    }

    Group("This phone", "Volunteers see this name in the OBS dock. A new name shows from the next connection.")
    OutlinedTextField(
        s.deviceName, { v -> Prefs.update { it.copy(deviceName = v.take(40)) } }, Modifier.fillMaxWidth().padding(vertical = 6.dp),
        label = { Text("Name in OBS") }, singleLine = true, colors = fieldColors(), textStyle = Type.body,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    )

    Group("When the Wi-Fi drops")
    SettingRow("Reconnect by itself") { DSwitch(s.autoReconnect, { v -> Prefs.update { it.copy(autoReconnect = v) } }) }
    SettingRow("Start listening again after reconnecting") { DSwitch(s.resumeListen, { v -> Prefs.update { it.copy(resumeListen = v) } }) }
    Note("A clip that's running when the connection drops ends and is saved at the drop. OBS keeps recording on the PC.")

    Group("How it connects")
    SettingRow("Transmission") { Text("Direct UDP", style = Type.body) }
    Note("Ports TCP 4466 and 4468, UDP 4467, on your local network only.")
}

@Composable
private fun ListeningPage() {
    val s by Prefs.settings.collectAsState()
    val latency by Director.latencyMs.collectAsState()

    val output by Director.router.outputName.collectAsState()
    Group("Play through", "Whatever your phone is using: Bluetooth, a headset, a USB sound card, the headphone jack or the speaker. Switch on the phone and the mix follows.")
    SettingRow("Playing through") { Text(output, style = Type.body, textAlign = TextAlign.End) }
    Note("A Bluetooth headset that only does calls gets the mix over the call channel: mono and a little lower in quality, but you hear it.")

    Group("Delay", "Lower delay can click when Wi-Fi stutters. Auto picks for you.")
    SettingRow("Buffer") {
        Tabs(listOf("Auto", "10 ms", "20 ms", "40 ms"), if (s.bufferMs == 0) "Auto" else "${s.bufferMs} ms") { o ->
            Director.setBuffer(if (o == "Auto") 0 else o.removeSuffix(" ms").toInt())
        }
    }
    SettingRow("Delay now, PC to ear") { Text("$latency ms", style = Type.mono) }

    Group("Starting")
    SettingRow("Start listening when connected") { DSwitch(s.autoListen, { v -> Prefs.update { it.copy(autoListen = v) } }) }
    Note("The mix arrives as 48 kHz stereo and keeps playing with the screen off or another app open.")
}

@Composable
private fun SavedAudioPage() {
    val s by Prefs.settings.collectAsState()

    Group("On this phone", "The whole session's audio, so clips can be heard and re-timed later.")
    SettingRow("Record the session's audio") { DSwitch(s.saveAudio, { v -> Prefs.update { it.copy(saveAudio = v) } }) }
    SettingRow("Keep it for") {
        Tabs(listOf("7 days", "30 days", "Forever"), when (s.keepDays) { 7 -> "7 days"; 0 -> "Forever"; else -> "30 days" }) { o ->
            Prefs.update { it.copy(keepDays = when (o) { "7 days" -> 7; "Forever" -> 0; else -> 30 }) }
        }
    }
    Note("Saved as AAC 128 kb/s (.m4a), about 58 MB an hour. 10 s of spare audio is kept either side of each clip.")
}

@Composable
private fun ClipsPage() {
    val s by Prefs.settings.collectAsState()

    Group("Timing", "You hear a line, then reach for the button. These make up for that.")
    SettingRow("Start clips before the tap") {
        Tabs(listOf("1 min", "2 min", "3 min", "5 min"), "${s.leadMinutes} min") { o -> Prefs.update { it.copy(leadMinutes = o.removeSuffix(" min").toInt()) } }
    }
    SettingRow("Run on past the End tap") {
        Tabs(listOf("0 s", "2 s", "5 s"), "${s.padSeconds} s") { o -> Prefs.update { it.copy(padSeconds = o.removeSuffix(" s").toInt()) } }
    }

    Group("After each clip")
    SettingRow("Ask for a note") { DSwitch(s.askNote, { v -> Prefs.update { it.copy(askNote = v) } }) }

    Group("In the recording", "Clips travel with the recording itself.")
    SettingRow("Write a chapter marker when a clip starts") { DSwitch(s.chapters, { v -> Prefs.update { it.copy(chapters = v) } }) }
    Note("Needs OBS to record in Hybrid MP4 (Settings, Output, Recording Format).")

    Group("Export")
    SettingRow("Default format") { Tabs(Exporter.formats, s.exportFormat) { o -> Prefs.update { it.copy(exportFormat = o) } } }
    SettingRow("Keep a CSV of each recording's clips") { DSwitch(s.sidecar, { v -> Prefs.update { it.copy(sidecar = v) } }) }

    Group("Tags", "Each tag becomes a marker colour in DaVinci Resolve.")
    Tag.entries.forEach { t -> SettingRow(t.label) { Text(t.resolve, style = Type.small) } }
    SettingRow("No tag") { Text("Green", style = Type.small) }
}

@Composable
private fun AlertsPage() {
    val s by Prefs.settings.collectAsState()

    Group("Sound", "Your phone buzzes, even when locked, when something needs you.")
    SettingRow("Mix goes silent for") {
        Tabs(listOf("Off", "10 s", "20 s", "30 s"), if (s.silenceSeconds == 0) "Off" else "${s.silenceSeconds} s") { o ->
            Prefs.update { it.copy(silenceSeconds = if (o == "Off") 0 else o.removeSuffix(" s").toInt()) }
        }
    }
    SettingRow("Mix clips (peaks at 0 dB)") { DSwitch(s.alertClipping, { v -> Prefs.update { it.copy(alertClipping = v) } }) }

    Group("Recording and stream")
    SettingRow("Recording stops, and it wasn't you") { DSwitch(s.alertRecStop, { v -> Prefs.update { it.copy(alertRecStop = v) } }) }
    SettingRow("Disk space on the PC drops below") {
        Tabs(listOf("1 h", "2 h", "4 h"), "${s.diskHours} h") { o -> Prefs.update { it.copy(diskHours = o.removeSuffix(" h").toInt()) } }
    }
    SettingRow("Dropped frames in a minute, more than") {
        Tabs(listOf("50", "100", "500"), "${s.dropsPerMinute}") { o -> Prefs.update { it.copy(dropsPerMinute = o.toInt()) } }
    }
    Note("Losing the connection to the PC always alerts.")
}

@Composable
private fun VideoPage() {
    Group("Live picture", "A small picture of what OBS is sending, to check framing. It never delays the audio.")
    SettingRow("Picture") { Text("640×360, about 24 fps", style = Type.body, textAlign = TextAlign.End) }
    SettingRow("Data while it's on") { Text("About 540 MB an hour", style = Type.body, textAlign = TextAlign.End) }
    Note("It's off each time the app opens, and stops when the screen goes off or you switch apps. Turn it on from Monitor.")
}

@Composable
private fun ControlsPage() {
    val s by Prefs.settings.collectAsState()

    Group("Safety", "So a stray tap can't end a take the volunteers are running.")
    SettingRow("Hold to stop recording") {
        Tabs(listOf("0.5 s", "1 s", "2 s"), if (s.holdMs == 500) "0.5 s" else "${s.holdMs / 1000} s") { o ->
            Prefs.update { it.copy(holdMs = (o.removeSuffix(" s").toFloat() * 1000).toInt()) }
        }
    }
    SettingRow("Allow ending the stream from this phone") { DSwitch(s.allowStreamStop, { v -> Prefs.update { it.copy(allowStreamStop = v) } }) }

    Group("Display and desk", "For a phone propped up while you listen.")
    SettingRow("Keep the screen on while connected") { DSwitch(s.keepScreenOn, { v -> Prefs.update { it.copy(keepScreenOn = v) } }) }
    SettingRow("Tell the desk when you're listening") { DSwitch(s.deskPresence, { v -> Prefs.update { it.copy(deskPresence = v) } }) }
}

@Composable
private fun AboutPage() {
    Group("Updates", "New versions are checked on GitHub each time the app opens. Nothing about you or your phone is sent.")
    UpdateRow()

    Group("Versions", "The app and the OBS plugin are released together with the same version number.")
    SettingRow("This app") { Text(Updates.CURRENT_VERSION, style = Type.mono) }
    SettingRow("Plugin on the PC") { Text(Director.pluginVersion.ifBlank { "Not connected" }, style = Type.mono) }
    VersionMatch()

    Group("Your data")
    Note("Settings, clips and saved audio stay on this phone. The app talks only to the OBS PC you connect to, and to GitHub to check for updates.")
}

