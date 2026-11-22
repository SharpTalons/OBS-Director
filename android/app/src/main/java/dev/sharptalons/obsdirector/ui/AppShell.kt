package dev.sharptalons.obsdirector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sharptalons.obsdirector.core.ClipStore
import dev.sharptalons.obsdirector.core.Director
import dev.sharptalons.obsdirector.core.LinkState
import dev.sharptalons.obsdirector.core.Prefs
import dev.sharptalons.obsdirector.update.UpdateController
import kotlinx.coroutines.delay

@Composable
fun AppShell() {
    var screen by rememberSaveable { mutableStateOf("monitor") }
    val link by Director.link.collectAsState()
    val pc by Director.pc.collectAsState()
    val alerts by Director.alerts.collectAsState()
    val toast by Director.toast.collectAsState()
    val lostAt by Director.lostAt.collectAsState()
    val retry by Director.retry.collectAsState()
    val reconnected by Director.reconnectedAfterMs.collectAsState()
    val recordings by ClipStore.recordings.collectAsState()
    val latency by Director.latencyMs.collectAsState()
    val awaiting by Director.awaitingApproval.collectAsState()

    val settings by Prefs.settings.collectAsState()
    // The connect pop-up, from the connect icon in the top bar. On first run (no PC paired
    // yet) it opens by itself, once.
    var connectOpen by rememberSaveable { mutableStateOf(false) }
    var firstRunShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!firstRunShown && settings.lastIp.isBlank() && link == LinkState.DISCONNECTED) { connectOpen = true; firstRunShown = true }
    }
    if (connectOpen) ConnectDialog(onDismiss = { connectOpen = false })
    val disconnectedMonitor = screen == "monitor" && link == LinkState.DISCONNECTED
    // The update prompt waits while the connect pop-up is up.
    val pendingUpdate = UpdateController.update
    if (UpdateController.updatePrompt && pendingUpdate != null && !connectOpen) UpdateDialog(pendingUpdate)
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = android.os.SystemClock.elapsedRealtime() } }
    LaunchedEffect(toast) { if (toast != null) { delay(3000); Director.clearToast() } }

    Column(Modifier.fillMaxSize().background(T.ground).windowInsetsPadding(WindowInsets.statusBars)) {
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                // connection banners
                if (link == LinkState.LOST) {
                    val ago = (now - lostAt) / 1000
                    Banner(PillKind.WARN, Icons.Outlined.WifiOff, "Connection to ${pc?.name ?: "the PC"} lost",
                        "· ${ago / 60}:${"%02d".format(ago % 60)} ago. Listening paused. The recording carries on at the PC. Reconnecting, try ${retry.coerceAtLeast(1)}.")
                } else if (reconnected > 0) {
                    val s = reconnected / 1000
                    Banner(PillKind.OK, Icons.Outlined.Wifi, "Reconnected", "after ${s / 60}:${"%02d".format(s % 60)}. Listening again.")
                }
                alerts.filter { it.id != "net" }.forEach { a ->
                    Banner(if (a.severe) PillKind.REC else PillKind.WARN, Icons.Outlined.Warning, a.title, a.body, onDismiss = { Director.dismissAlert(a.id) }) {
                        if (a.offerStartRecording) PrimaryButton("Start recording", Icons.Outlined.FiberManualRecord, height = 34.dp) { Director.recordStart(); Director.dismissAlert(a.id) }
                        if (a.deskText != null) OutlineButton("Tell the desk", Icons.AutoMirrored.Outlined.Send, height = 34.dp) { Director.deskAboutAlert(a) }
                    }
                }

                // scope line, title and the screen's one-line summary; the connect icon on the right
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (link == LinkState.CONNECTED) T.positive else T.muted))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (link) {
                            LinkState.CONNECTED -> "${pc?.name ?: "OBS PC"}  ·  Direct UDP, $latency ms"
                            LinkState.CONNECTING -> if (awaiting != null) "Waiting for Allow in OBS on ${pc?.name ?: "the PC"}" else "Connecting to ${pc?.name ?: pc?.ip ?: "the PC"}…"
                            LinkState.LOST -> "${pc?.name ?: "OBS PC"}  ·  reconnecting"
                            LinkState.DISCONNECTED -> "Not connected"
                        },
                        style = Type.small,
                        modifier = Modifier.weight(1f),
                    )
                    ConnectIcon(link) { connectOpen = true }
                }
                Spacer(Modifier.height(6.dp))
                Text(when {
                    screen == "clips" -> "Clips"
                    screen == "config" -> "Settings"
                    else -> "Monitor"
                }, style = Type.title)
                Spacer(Modifier.height(16.dp))

                when {
                    disconnectedMonitor -> NotConnected { connectOpen = true }
                    screen == "clips" -> ClipsScreen(recordings)
                    screen == "config" -> SettingsScreen(onConnect = { connectOpen = true })
                    else -> MonitorScreen(onOpenClips = { screen = "clips" })
                }
                Spacer(Modifier.height(if (screen == "monitor" && link != LinkState.DISCONNECTED) 140.dp else 24.dp))
            }

            // the clip panel rides above the tab bar while monitoring
            if (screen == "monitor" && link != LinkState.DISCONNECTED) {
                Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 8.dp)) { ClipPanel() }
            }

            if (toast != null) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 12.dp).clip(RoundedCornerShape(6.dp)).background(T.text).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(toast ?: "", style = Type.body.copy(color = Color.Black, fontWeight = FontWeight.Medium))
                }
            }
        }

        HorizontalDivider(color = T.border)
        Row(Modifier.fillMaxWidth().background(T.ground).windowInsetsPadding(WindowInsets.navigationBars).padding(vertical = 6.dp)) {
            val clipCount = recordings.sumOf { it.clips.size }
            NavItem("Monitor", Icons.Outlined.Headphones, screen == "monitor", badge = if (alerts.any { it.id != "net" }) "!" else null, Modifier.weight(1f)) { screen = "monitor" }
            NavItem("Clips", Icons.Outlined.ContentCut, screen == "clips", badge = if (clipCount > 0) "$clipCount" else null, Modifier.weight(1f)) { screen = "clips" }
            NavItem("Settings", Icons.Outlined.Settings, screen == "config", null, Modifier.weight(1f)) { screen = "config" }
        }
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector, selected: Boolean, badge: String?, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(52.dp)
            .clickable(onClick = onClick)
            .drawBehind { if (selected) drawLine(T.accent, Offset(size.width / 2 - 40f, 0f), Offset(size.width / 2 + 40f, 0f), 2f) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(icon, label, tint = if (selected) T.text else T.muted, modifier = Modifier.size(20.dp))
            if (badge != null) Text(badge, style = Type.mono.copy(fontSize = 10.sp, color = if (badge == "!") T.warning else T.text),
                modifier = Modifier.align(Alignment.TopEnd).padding(start = 22.dp))
        }
        Text(label, style = Type.small.copy(fontSize = 11.sp, color = if (selected) T.text else T.muted))
    }
}

/** The top bar's connect icon: opens the connect pop-up. Its colour says how the link is. */
@Composable
private fun ConnectIcon(link: LinkState, onClick: () -> Unit) {
    val (icon, tint, label) = when (link) {
        LinkState.CONNECTED -> Triple(Icons.Outlined.CastConnected, T.positive, "Connected. Change or disconnect")
        LinkState.CONNECTING, LinkState.LOST -> Triple(Icons.Outlined.Cast, T.warning, "Connecting")
        LinkState.DISCONNECTED -> Triple(Icons.Outlined.Cast, T.accent, "Connect to OBS")
    }
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(if (link == LinkState.DISCONNECTED) T.raised else T.ground).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/** Monitor while no PC is connected: reconnect to the last PC in one tap, or pick another. */
@Composable
private fun NotConnected(onConnect: () -> Unit) {
    val settings by Prefs.settings.collectAsState()
    val error by Director.error.collectAsState()
    Text("Connect this phone to the OBS PC to listen, watch and mark clips.", style = Type.finding)
    Spacer(Modifier.height(16.dp))
    DCard {
        CardHeader("Not connected", Icons.Outlined.Cast)
        if (settings.lastIp.isNotBlank()) {
            val name = settings.lastPcName.ifBlank { "PC at ${settings.lastIp}" }
            PrimaryButton("Connect to $name", Icons.Outlined.Power, Modifier.fillMaxWidth()) {
                Director.connect(dev.sharptalons.obsdirector.core.FoundPc(name, settings.lastIp, settings.lastPort, lastSeen = 0))
            }
            Spacer(Modifier.height(10.dp))
            OutlineButton("Choose another PC", Icons.Outlined.Cast, Modifier.fillMaxWidth()) { onConnect() }
        } else {
            Text("Make sure OBS is open on the PC with the OBS Director plugin, on the same Wi-Fi as this phone.", style = Type.small)
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Find PCs", Icons.Outlined.Power, Modifier.fillMaxWidth()) { onConnect() }
        }
        error?.let { Text(it, style = Type.small.copy(color = T.negative), modifier = Modifier.padding(top = 10.dp)) }
    }
}
