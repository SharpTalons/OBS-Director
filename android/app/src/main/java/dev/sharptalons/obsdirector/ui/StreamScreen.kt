package dev.sharptalons.obsdirector.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sharptalons.obsdirector.core.Director
import dev.sharptalons.obsdirector.core.Prefs
import dev.sharptalons.obsdirector.update.Updates

/** Stream and PC details, opened from the Stream chip on Monitor. */
@Composable
fun StreamScreen() {
    val status by Director.status.collectAsState()
    val health by Director.health.collectAsState()
    val settings by Prefs.settings.collectAsState()
    val latency by Director.latencyMs.collectAsState()
    val pc by Director.pc.collectAsState()
    var tab by rememberSaveable { mutableStateOf("Stream") }
    @Suppress("UNUSED_VARIABLE") val tick = rememberTicker(1000)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Stream and PC")
        // tabs: 48 dp tall, the selected one filled blue
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(T.surface).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Stream", "Computer", "Network").forEach { t ->
                val on = t == tab
                Text(t, style = Type.body.copy(fontWeight = FontWeight.Medium, color = if (on) Color.White else T.text2),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(if (on) T.blue else Color.Transparent)
                        .clickable(role = Role.Tab) { tab = t }.semantics { selected = on }.padding(top = 14.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }

        when (tab) {
            "Stream" -> {
                val pct = if (status.totalFrames > 0) status.dropped * 100.0 / status.totalFrames else 0.0
                StatGrid(listOf(
                    Stat("Live for", if (status.streamActive) Director.tc(status.streamDurationMs) else "Off air", Tints.rec),
                    Stat("Bitrate", if (status.streamActive) "%,d kb/s".format(status.streamKbps.toInt()) else "–", Tints.blue),
                    Stat("Dropped frames", "${status.dropped} · ${"%.1f".format(pct)}%", if (status.dropped == 0) Tints.ok else Tints.amber),
                    Stat("Congestion", when { status.congestion < 0.1 -> "Low"; status.congestion < 0.5 -> "Some"; else -> "High" },
                        if (status.congestion < 0.1) Tints.ok else Tints.amber),
                ))
                Section {
                    Text("Dropped frames, last 30 minutes", style = Type.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium))
                    Text("One bar a minute. Amber means it crossed your alert level (${settings.dropsPerMinute} a minute).", style = Type.small)
                    val bars = health.takeLast(30)
                    Canvas(Modifier.fillMaxWidth().height(72.dp).semantics {
                        contentDescription = if (bars.isEmpty()) "No stream data yet" else "Dropped frames per minute, most recent ${bars.lastOrNull() ?: 0}, highest ${bars.maxOrNull() ?: 0}"
                    }) {
                        val n = 30
                        val gap = 3f
                        val w = (size.width - gap * (n - 1)) / n
                        val top = (bars.maxOrNull() ?: 0).coerceAtLeast(settings.dropsPerMinute).toFloat().coerceAtLeast(1f)
                        bars.forEachIndexed { i, d ->
                            val x = (n - bars.size + i) * (w + gap)
                            val h = (size.height * d / top).coerceAtLeast(4f)
                            val c = when { d >= settings.dropsPerMinute -> T.warning; d > 0 -> Tints.ok.ink; else -> T.chartEmpty }
                            drawRect(c, Offset(x, size.height - h), Size(w, h))
                        }
                    }
                    Row { Text("30 min ago", style = Type.small, modifier = Modifier.weight(1f)); Text("now", style = Type.small) }
                }
                if (settings.allowStreamStop && status.streamActive) {
                    HoldButton("Hold to end the stream", Icons.Outlined.PowerSettingsNew, 2000, Modifier.fillMaxWidth()) { Director.streamStop() }
                } else {
                    Text("Ending the stream from this phone is off. Turn it on in Settings, Controls.", style = Type.small)
                }
            }
            "Computer" -> {
                val hours = Director.diskHoursLeft(status)
                StatList(listOf(
                    Stat("Processor", "%.0f%%".format(status.cpu), if (status.cpu < 70) Tints.blue else Tints.amber),
                    Stat("Frame rate", "%.2f".format(status.fps), Tints.ok),
                    Stat("Render lag", "${status.renderLag} frames", if (status.renderLag == 0L) Tints.ok else Tints.amber),
                    Stat("Encoder lag", "${status.encodeLag} frames", if (status.encodeLag == 0L) Tints.ok else Tints.amber),
                    Stat("Disk left for recording", hours?.let { "%.1f h".format(it) } ?: "–", Tints.purple),
                    Stat("Scene", status.scene.ifBlank { "–" }, Tints.teal),
                ))
                Text("Render and encoder lag count frames OBS couldn't keep up with. Anything above 0 that keeps rising means the PC is struggling.", style = Type.small)
            }
            else -> {
                val drops = Director.dropoutsLastMinute()
                val match = Director.pluginVersion.isNotBlank() && Director.pluginVersion == Updates.CURRENT_VERSION.substringBefore('-')
                StatList(listOf(
                    Stat("Phone Wi-Fi", when { drops == 0 && latency < 150 -> "Good"; drops <= 3 -> "Weak"; else -> "Poor" }, if (drops == 0) Tints.ok else Tints.amber),
                    Stat("Delay to the PC", "$latency ms", Tints.blue),
                    Stat("Audio dropouts, last minute", "$drops", if (drops == 0) Tints.ok else Tints.amber),
                    Stat("PC address", pc?.ip ?: "–", Tints.purple),
                    Stat("Plugin and app", Director.pluginVersion.ifBlank { "–" } + if (match) " · match" else if (Director.pluginVersion.isNotBlank()) " · different" else "",
                        if (match) Tints.ok else Tints.amber),
                ))
                Text("This is the link between this phone and the OBS PC, not the stream's internet connection.", style = Type.small)
            }
        }
    }
}

private data class Stat(val label: String, val value: String, val tint: Tint)

@Composable
private fun StatGrid(stats: List<Stat>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        stats.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { s ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(s.tint.bg).padding(14.dp)) {
                        Text(s.label, style = Type.label.copy(color = s.tint.ink))
                        Text(s.value, style = Type.figure.copy(fontSize = 24.sp, fontWeight = FontWeight.Medium), modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatList(stats: List<Stat>) {
    Section(padding = 0.dp, gap = 0.dp) {
        stats.forEachIndexed { i, s ->
            if (i > 0) HorizontalDivider(color = T.raised)
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.label, style = Type.body.copy(fontSize = 15.sp), modifier = Modifier.weight(1f))
                Text(s.value, style = Type.figure.copy(color = s.tint.ink, fontWeight = FontWeight.Medium))
            }
        }
    }
}
