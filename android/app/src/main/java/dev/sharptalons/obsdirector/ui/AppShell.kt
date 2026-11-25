package dev.sharptalons.obsdirector.ui

import androidx.compose.foundation.layout.offset

import androidx.compose.foundation.layout.isImeVisible

import androidx.compose.material.icons.automirrored.outlined.Chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.outlined.VideoLibrary
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
import dev.sharptalons.obsdirector.MainIntents
import dev.sharptalons.obsdirector.core.ClipStore
import dev.sharptalons.obsdirector.core.Director
import dev.sharptalons.obsdirector.core.LinkState
import dev.sharptalons.obsdirector.core.Prefs
import dev.sharptalons.obsdirector.update.UpdateController
import kotlinx.coroutines.delay

@Composable
fun AppShell() {
    var screen by rememberSaveable { mutableStateOf(MainIntents.startScreen ?: "monitor") }
    var openFile by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(MainIntents.startScreen) { MainIntents.startScreen?.let { screen = it; MainIntents.startScreen = null } }
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

    // Back three times within 2 s closes the app (pop-ups and Settings pages take Back first).
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    var backTaps by remember { mutableStateOf(listOf<Long>()) }
    androidx.activity.compose.BackHandler(enabled = screen != "monitor") {
        screen = when (screen) { "recording" -> "recordings"; else -> "monitor" }
    }
    androidx.activity.compose.BackHandler(enabled = screen == "monitor") {
        val at = android.os.SystemClock.elapsedRealtime()
        val taps = backTaps.filter { at - it < 2000 } + at
        backTaps = taps
        when (taps.size) {
            1 -> Director.toast("Press Back 2 more times to close OBS Director")
            2 -> Director.toast("Press Back once more to close")
            else -> {
                backTaps = emptyList()
                Director.disconnect()
                activity?.finishAndRemoveTask()
            }
        }
    }

    if (screen == "stage") {
        StageScreen(onExit = { screen = "monitor" })
        return
    }

    Column(Modifier.fillMaxSize().background(T.ground).windowInsetsPadding(WindowInsets.statusBars)) {
        HeadBar(link, pc, latency, awaiting, onStage = { screen = "stage" }, onConnect = { connectOpen = true })
        // banners for the link and alerts, shown above every screen
        // Only there when there's something to show, so an empty banner area adds no gap.
        val hasBanners = link == LinkState.LOST || reconnected > 0 || alerts.any { it.id != "net" }
        val topBanners: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        if (a.offerStartRecording) PrimaryButton("Start recording", Icons.Outlined.FiberManualRecord) { Director.recordStart(); Director.dismissAlert(a.id) }
                        if (a.deskText != null) OutlineButton("Tell the desk", Icons.AutoMirrored.Outlined.Send) { Director.deskAboutAlert(a) }
                    }
                }
            }
        }
        val top: (@Composable () -> Unit)? = if (hasBanners) topBanners else null
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            // on tablets, in landscape and on unfolded phones the content keeps a phone's reading width
            val content = Modifier.fillMaxHeight().widthIn(max = 640.dp).fillMaxWidth()
            when (screen) {
                "recording" -> RecordingScreen(openFile, content, top, onBack = { screen = "recordings" })
                "recordings" -> RecordingsScreen(content, top, onOpen = { openFile = it; screen = "recording" })
                "desk" -> DeskScreen(content)
                else -> Column(content.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    top?.invoke()
                    when {
                        screen == "stream" -> {
                            RoundIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to Monitor", T.text) { screen = "monitor" }
                            StreamScreen()
                        }
                        screen == "config" -> SettingsScreen(onConnect = { connectOpen = true })
                        disconnectedMonitor -> NotConnected { connectOpen = true }
                        else -> MonitorScreen(onOpenRecording = { openFile = it; screen = "recording" }, onOpenStream = { screen = "stream" })
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }

            if (toast != null) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).background(T.text).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(toast ?: "", style = Type.body.copy(color = Color.Black, fontWeight = FontWeight.Medium))
                }
            }
        }

        // the tabs step aside while the keyboard is up, as in a chat app
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        val keyboard = WindowInsets.isImeVisible
        if (!keyboard) {
        HorizontalDivider(color = T.rule)
        Row(Modifier.fillMaxWidth().background(T.ground).windowInsetsPadding(WindowInsets.navigationBars).padding(vertical = 6.dp).selectableGroup()) {
            val tab = when (screen) { "recordings", "recording" -> "recordings"; "config" -> "config"; "desk" -> "desk"; else -> "monitor" }
            val unread by Director.deskUnreadCount.collectAsState()
            NavItem("Monitor", Icons.Outlined.Headphones, tab == "monitor", badge = if (alerts.any { it.id != "net" }) "!" else null, Modifier.weight(1f)) { screen = "monitor" }
            NavItem("Recordings", Icons.Outlined.VideoLibrary, tab == "recordings", badge = if (recordings.any { it.live }) "•" else null, Modifier.weight(1f)) { screen = "recordings" }
            NavItem("Desk", Icons.AutoMirrored.Outlined.Chat, tab == "desk", badge = if (unread > 0) (if (unread > 99) "99+" else "$unread") else null, Modifier.weight(1f)) { screen = "desk" }
            NavItem("Settings", Icons.Outlined.Settings, tab == "config", null, Modifier.weight(1f)) { screen = "config" }
        }
        }
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector, selected: Boolean, badge: String?, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(56.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { if (badge != null) stateDescription = when (badge) { "!" -> "Needs attention"; "•" -> "Recording now"; else -> "$badge unread" } }
            .drawBehind { if (selected) drawLine(T.accent, Offset(size.width / 2 - 40f, 0f), Offset(size.width / 2 + 40f, 0f), 4f) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(icon, null, tint = if (selected) T.text else T.muted, modifier = Modifier.size(22.dp))
            if (badge == "!" || badge == "•") Text(badge, style = Type.mono.copy(fontSize = 10.sp, color = if (badge == "!") T.warning else T.text),
                modifier = Modifier.align(Alignment.TopEnd).padding(start = 24.dp).clearAndSetSemantics { })
            else if (badge != null) Text(badge, style = Type.label.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0B1A12)),
                // an unread count, like a chat app's
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 14.dp, y = (-6).dp).clip(CircleShape).background(T.positive)
                    .padding(horizontal = 5.dp, vertical = 1.dp).clearAndSetSemantics { })
        }
        Text(label, style = Type.small.copy(fontSize = 11.sp, color = if (selected) T.text else T.muted))
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
