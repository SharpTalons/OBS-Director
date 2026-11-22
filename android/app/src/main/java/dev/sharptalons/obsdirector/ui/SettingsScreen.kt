package dev.sharptalons.obsdirector.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.sharptalons.obsdirector.core.Director
import dev.sharptalons.obsdirector.core.Exporter
import dev.sharptalons.obsdirector.core.LinkState
import dev.sharptalons.obsdirector.core.Prefs
import dev.sharptalons.obsdirector.core.Tag

@Composable
private fun Section(title: String, why: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = Type.cardTitle)
    Text(why, style = Type.small, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
    content()
}

@Composable
fun SettingsScreen(onConnect: () -> Unit) {
    val s by Prefs.settings.collectAsState()
    val link by Director.link.collectAsState()
    val pc by Director.pc.collectAsState()
    val latency by Director.latencyMs.collectAsState()

    Text("Saved on this device only.", style = Type.finding)
    DCard {
        Column {
            Section("Connection", "Which OBS PC this device follows, and how the audio travels.") {
                if (link == LinkState.DISCONNECTED) {
                    SettingRow("Not connected") { PrimaryButton("Connect", Icons.Outlined.Power, height = 34.dp) { onConnect() } }
                } else {
                    SettingRow("OBS PC") { Text("${pc?.name ?: ""} · ${pc?.ip ?: ""}", style = Type.mono, textAlign = TextAlign.End) }
                    SettingRow("Transmission mode") { Text("Direct UDP · this app", style = Type.body) }
                    SettingRow("Plugin on the PC") { Text(Director.pluginVersion.ifBlank { "–" }, style = Type.mono) }
                    SettingRow("Change PC") { OutlineButton("Connect", Icons.Outlined.Power, height = 34.dp) { onConnect() } }
                    SettingRow("Disconnect this device") { OutlineButton("Disconnect", Icons.Outlined.WifiOff, height = 34.dp) { Director.disconnect() } }
                }
                SettingRow("Reconnect automatically when the network comes back") { DSwitch(s.autoReconnect, { v -> Prefs.update { it.copy(autoReconnect = v) } }) }
                SettingRow("Start listening again after reconnecting") { DSwitch(s.resumeListen, { v -> Prefs.update { it.copy(resumeListen = v) } }) }
                SettingRow("A clip running when the connection drops") { Text("Ends and saves at the drop", style = Type.body) }
            }

            Section("Audio", "Lower delay clicks sooner when Wi-Fi stutters.") {
                SettingRow("Live link") { Text("PCM, 48 kHz stereo", style = Type.body) }
                SettingRow("Jitter buffer") {
                    Tabs(listOf("Auto", "10 ms", "20 ms", "40 ms"), if (s.bufferMs == 0) "Auto" else "${s.bufferMs} ms") { o ->
                        Director.setBuffer(if (o == "Auto") 0 else o.removeSuffix(" ms").toInt())
                    }
                }
                SettingRow("Delay, PC to ear") { Text("$latency ms", style = Type.mono) }
                SettingRow("Start listening when connected") { DSwitch(s.autoListen, { v -> Prefs.update { it.copy(autoListen = v) } }) }
                SettingRow("Screen off, or another app open") { Text("The mix keeps playing", style = Type.body) }
            }

            Section("Audio saved on this device", "The whole session's audio, so clips can be heard and re-timed later.") {
                SettingRow("Record the session's audio on this device") { DSwitch(s.saveAudio, { v -> Prefs.update { it.copy(saveAudio = v) } }) }
                SettingRow("Saved as") { Text("AAC, 128 kb/s, 48 kHz stereo (.m4a)", style = Type.body, textAlign = TextAlign.End) }
                SettingRow("Space used") { Text("58 MB an hour", style = Type.mono) }
                SettingRow("Keep session audio for") {
                    Tabs(listOf("7 days", "30 days", "Forever"), when (s.keepDays) { 7 -> "7 days"; 0 -> "Forever"; else -> "30 days" }) { o ->
                        Prefs.update { it.copy(keepDays = when (o) { "7 days" -> 7; "Forever" -> 0; else -> 30 }) }
                    }
                }
                SettingRow("Spare audio kept either side of each clip") { Text("10 s", style = Type.mono) }
            }

            Section("Alerts", "Your phone buzzes, even when locked, when something needs you.") {
                SettingRow("Mix goes silent for") {
                    Tabs(listOf("Off", "10 s", "20 s", "30 s"), if (s.silenceSeconds == 0) "Off" else "${s.silenceSeconds} s") { o ->
                        Prefs.update { it.copy(silenceSeconds = if (o == "Off") 0 else o.removeSuffix(" s").toInt()) }
                    }
                }
                SettingRow("Mix clips (peaks at 0 dB)") { DSwitch(s.alertClipping, { v -> Prefs.update { it.copy(alertClipping = v) } }) }
                SettingRow("Recording stops, and it wasn't you") { DSwitch(s.alertRecStop, { v -> Prefs.update { it.copy(alertRecStop = v) } }) }
                SettingRow("Disk space on the OBS PC drops below") {
                    Tabs(listOf("1 h", "2 h", "4 h"), "${s.diskHours} h") { o -> Prefs.update { it.copy(diskHours = o.removeSuffix(" h").toInt()) } }
                }
                SettingRow("Stream drops more frames than this in a minute") {
                    Tabs(listOf("50", "100", "500"), "${s.dropsPerMinute}") { o -> Prefs.update { it.copy(dropsPerMinute = o.toInt()) } }
                }
                SettingRow("Connection to the OBS PC drops") { Text("Always on", style = Type.body) }
            }

            Section("Tags", "One tap when you save a clip. Each becomes a marker colour in Resolve.") {
                Tag.entries.forEach { t -> SettingRow(t.label) { Text("Resolve marker: ${t.resolve}", style = Type.small) } }
                SettingRow("No tag") { Text("Resolve marker: Green", style = Type.small) }
            }

            Section("Recording file", "Clips travel with the recording itself.") {
                SettingRow("Write a chapter marker into the recording when you start a clip") { DSwitch(s.chapters, { v -> Prefs.update { it.copy(chapters = v) } }) }
                SettingRow("Needs OBS set to record") { Text("Hybrid MP4", style = Type.body) }
            }

            Section("Live output", "A small picture of what OBS is sending, to check framing. It never delays the audio.") {
                SettingRow("Picture") { Text("640×360 at about 24 fps, H.264", style = Type.body, textAlign = TextAlign.End) }
                SettingRow("Data used while it's on") { Text("About 540 MB an hour", style = Type.body) }
                SettingRow("Screen off, or another app open") { Text("Video stops", style = Type.body) }
                SettingRow("Starts") { Text("Off each time", style = Type.body) }
            }

            Section("Clips", "You hear a line, then reach for the button. These make up for that.") {
                SettingRow("Start clips this far before the first tap") {
                    Tabs(listOf("1 min", "2 min", "3 min", "5 min"), "${s.leadMinutes} min") { o -> Prefs.update { it.copy(leadMinutes = o.removeSuffix(" min").toInt()) } }
                }
                SettingRow("Run clips on past the End tap by") {
                    Tabs(listOf("0 s", "2 s", "5 s"), "${s.padSeconds} s") { o -> Prefs.update { it.copy(padSeconds = o.removeSuffix(" s").toInt()) } }
                }
                SettingRow("Ask for a note after each clip") { DSwitch(s.askNote, { v -> Prefs.update { it.copy(askNote = v) } }) }
                SettingRow("Keep a CSV of each recording's clips on this device") { DSwitch(s.sidecar, { v -> Prefs.update { it.copy(sidecar = v) } }) }
                SettingRow("Default export format") { Tabs(Exporter.formats, s.exportFormat) { o -> Prefs.update { it.copy(exportFormat = o) } } }
            }

            Section("Controls", "So a stray tap can't end a take the volunteers are running.") {
                SettingRow("Hold to stop recording") {
                    Tabs(listOf("0.5 s", "1 s", "2 s"), if (s.holdMs == 500) "0.5 s" else "${s.holdMs / 1000} s") { o ->
                        Prefs.update { it.copy(holdMs = (o.removeSuffix(" s").toFloat() * 1000).toInt()) }
                    }
                }
                SettingRow("Allow ending the stream from this device") { DSwitch(s.allowStreamStop, { v -> Prefs.update { it.copy(allowStreamStop = v) } }) }
            }

            Section("Display and desk", "For a phone propped up while you listen.") {
                SettingRow("Keep the screen on while connected") { DSwitch(s.keepScreenOn, { v -> Prefs.update { it.copy(keepScreenOn = v) } }) }
                SettingRow("Tell the desk when you're listening") { DSwitch(s.deskPresence, { v -> Prefs.update { it.copy(deskPresence = v) } }) }
            }

            Section("About", "This app, and how it stays up to date.") {
                UpdateRow()
                SettingRow("This device's name in OBS") { Text(s.deviceName, style = Type.body) }
            }
        }
    }
}
