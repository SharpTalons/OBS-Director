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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
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
    // confirmations are Android's own snackbar, which screen readers announce by themselves
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(toast) {
        val t = toast ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(t)
        Director.clearToast()
    }

    // Back steps out to Monitor from the other tabs. On Monitor, Back is Android's own: the app
    // goes to the background (with the predictive back-to-home preview) and keeps listening and
    // recording, with the bubble and notification to come back.
    androidx.activity.compose.BackHandler(enabled = screen != "monitor") {
        screen = "monitor"
    }

    if (screen == "stage") {
        StageScreen(onExit = { screen = "monitor" })
        return
    }

    // phones get tabs at the bottom; tablets, unfolded phones and landscape get a rail at the side
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    // the dot on Recordings: OBS is recording now (sessions are always live while connected)
    val obsRecording = Director.status.collectAsState().value.recActive && link == LinkState.CONNECTED
    val tab = when (screen) { "recordings", "recording" -> "recordings"; "config" -> "config"; "desk" -> "desk"; "monitor", "stream" -> "monitor"; else -> "monitor" }
    val unread by Director.deskUnreadCount.collectAsState()
    val unreadAlert by Director.deskUnreadAlert.collectAsState()
    val deskBadge = if (unread > 0) (if (unread > 99) "99+" else "$unread") else null
    val deskBadgeColor = when (unreadAlert) { 2 -> T.negative; 1 -> T.warning; else -> T.positive }
    val deskBadgeSays = when (unreadAlert) { 2 -> "including a severe alert"; 1 -> "including an alert"; else -> null }
    val tabs: @Composable (Modifier) -> Unit = { m ->
        NavItem("Monitor", Icons.Outlined.Headphones, tab == "monitor", null, m, rail = wide) { screen = "monitor" }
        NavItem("Recordings", Icons.Outlined.VideoLibrary, tab == "recordings", if (obsRecording) "•" else null, m, rail = wide) { screen = "recordings" }
        NavItem("Desk", Icons.AutoMirrored.Outlined.Chat, tab == "desk", deskBadge, m, badgeColor = deskBadgeColor, badgeSays = deskBadgeSays, rail = wide) { screen = "desk" }
        NavItem("Settings", Icons.Outlined.Settings, tab == "config", null, m, rail = wide) { screen = "config" }
    }

    // The app draws edge to edge, so Android doesn't shrink it for the keyboard; this lifts the
    // screen above the keyboard instead, so the chat box stays in sight while typing.
    Column(Modifier.fillMaxSize().background(T.ground).windowInsetsPadding(WindowInsets.statusBars).imePadding()) {
        // The top bar (connection, Stage mode, connect) belongs to Monitor. The other screens have
        // their own title and give the room to their content; Settings has the OBS PC row.
        if (screen == "monitor") HeadBar(link, pc, latency, awaiting, onStage = { screen = "stage" }, onConnect = { connectOpen = true })
        // Off Monitor there is no top bar, so say so here when the connection isn't healthy
        else if (link != LinkState.CONNECTED) ConnectionStrip(link, pc?.name, awaiting) { connectOpen = true }
        // Alerts don't sit on top of the screens: they notify, and show up in the Desk chat.
        val top: (@Composable () -> Unit)? = null
        Row(Modifier.weight(1f).fillMaxWidth()) {
        if (wide) {
            Column(
                Modifier.width(88.dp).fillMaxHeight().windowInsetsPadding(WindowInsets.navigationBars).padding(top = 12.dp).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
            ) { tabs(Modifier.fillMaxWidth().height(64.dp)) }
            androidx.compose.material3.VerticalDivider(color = T.rule)
        }
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            // the content keeps a phone's reading width
            // a phone's reading width; Recordings may use more on a tablet
            val content = Modifier.fillMaxHeight().widthIn(max = if (wide && (screen == "recordings" || screen == "recording")) 840.dp else 640.dp).fillMaxWidth()
            when (screen) {
                // one screen for recordings and their clips; "recording" opens one of them
                "recording", "recordings" -> RecordingsScreen(content, top, focus = if (screen == "recording") openFile else null)
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

            androidx.compose.material3.SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 560.dp)) { data ->
                androidx.compose.material3.Snackbar(data, containerColor = T.text, contentColor = Color.Black, shape = RoundedCornerShape(12.dp))
            }
        }
        }

        // the tabs step aside while the keyboard is up, as in a chat app
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        val keyboard = WindowInsets.isImeVisible
        if (!wide && !keyboard) {
            HorizontalDivider(color = T.rule)
            Row(Modifier.fillMaxWidth().background(T.ground).windowInsetsPadding(WindowInsets.navigationBars).padding(vertical = 6.dp).selectableGroup()) {
                tabs(Modifier.weight(1f).height(56.dp))
            }
        }
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector, selected: Boolean, badge: String?, modifier: Modifier,
                    badgeColor: Color = T.positive, badgeSays: String? = null, rail: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics {
                if (badge != null) stateDescription = when (badge) {
                    "•" -> "Recording now"
                    else -> listOfNotNull("$badge unread", badgeSays).joinToString(", ")
                }
            }
            .drawBehind {
                if (!selected) return@drawBehind
                // on the rail the mark sits at the inner edge; on the tab bar, along the top
                if (rail) drawLine(T.accent, Offset(2f, size.height / 2 - 40f), Offset(2f, size.height / 2 + 40f), 4f)
                else drawLine(T.accent, Offset(size.width / 2 - 40f, 0f), Offset(size.width / 2 + 40f, 0f), 4f)
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(icon, null, tint = if (selected) T.text else T.muted, modifier = Modifier.size(22.dp))
            if (badge == "•") Text(badge, style = Type.mono.copy(fontSize = 10.sp, color = T.text),
                modifier = Modifier.align(Alignment.TopEnd).padding(start = 24.dp).clearAndSetSemantics { })
            else if (badge != null) Text(badge, style = Type.label.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = T.onBadge),
                // an unread count, like a chat app's; amber or red while an alert is among them
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 14.dp, y = (-6).dp).clip(CircleShape).background(badgeColor)
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
    ScreenTitle("Not connected", "Connect this phone to the OBS PC to listen, watch and mark clips")
    Section {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TintTile(Icons.Outlined.Cast, Tints.ok, size = 36.dp, iconSize = 20.dp)
            Column(Modifier.weight(1f)) {
                val name = settings.lastPcName.ifBlank { "PC at ${settings.lastIp}" }
                Text(if (settings.lastIp.isNotBlank()) name else "No PC yet", style = Type.body.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.semantics { heading() })
                Text(if (settings.lastIp.isNotBlank()) "The last PC this phone used" else "Find the OBS PC on this Wi-Fi", style = Type.small.copy(fontSize = 13.sp))
            }
        }
        if (settings.lastIp.isNotBlank()) {
            val name = settings.lastPcName.ifBlank { "PC at ${settings.lastIp}" }
            PrimaryButton("Connect again", Icons.Outlined.Power, Modifier.fillMaxWidth()) {
                Director.connect(dev.sharptalons.obsdirector.core.FoundPc(name, settings.lastIp, settings.lastPort, lastSeen = 0))
            }
            OutlineButton("Choose another PC", Icons.Outlined.Cast, Modifier.fillMaxWidth()) { onConnect() }
        } else {
            PrimaryButton("Find PCs", Icons.Outlined.Cast, Modifier.fillMaxWidth()) { onConnect() }
        }
        error?.let { Text(it, style = Type.small.copy(color = T.negative)) }
    }
    GroupLabel("How it works", T.muted, Modifier.padding(top = 4.dp))
    Section {
        HowStep("1", "Open OBS on the PC", "The OBS Director plugin has to be installed there.")
        HowStep("2", "Use the same Wi-Fi", "This phone and the PC need to be on the same network.")
        HowStep("3", "Allow this phone", "The first time, OBS asks whether to let this phone in. Click Allow on the PC.")
        HowStep("4", "Listen and mark clips", "Turn on Listen to hear the mix. Tap Clip at a good moment, and End when it's over.")
    }
}

@Composable
fun HowStep(n: String, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.semantics(mergeDescendants = true) { }) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Tints.blue.bg), contentAlignment = Alignment.Center) {
            Text(n, style = Type.label.copy(color = Tints.blue.ink, fontWeight = FontWeight.Bold))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body.copy(fontWeight = FontWeight.Medium))
            Text(body, style = Type.small.copy(fontSize = 13.sp))
        }
    }
}

/** A slim line at the top of Recordings, Desk, Settings and Stream while the connection isn't healthy. */
@Composable
private fun ConnectionStrip(link: LinkState, pcName: String?, waiting: String?, onConnect: () -> Unit) {
    val (text, tint) = when (link) {
        LinkState.LOST -> "Connection to ${pcName ?: "the PC"} lost · reconnecting" to Tints.amber
        LinkState.CONNECTING -> (if (waiting != null) "Waiting for Allow in OBS on ${pcName ?: "the PC"}" else "Connecting to ${pcName ?: "the PC"}…") to Tints.amber
        else -> "Not connected to OBS · tap to connect" to Tints.neutral
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).background(tint.bg).clickable(role = Role.Button, onClickLabel = "Connect", onClick = onConnect)
            .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (link == LinkState.DISCONNECTED) T.muted else T.warning))
        Text(text, style = Type.small.copy(fontSize = 13.sp, color = tint.ink, fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
    }
}
