package dev.sharptalons.obsdirector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.sharptalons.obsdirector.core.Director
import dev.sharptalons.obsdirector.core.FoundPc
import dev.sharptalons.obsdirector.core.LinkState
import dev.sharptalons.obsdirector.core.Prefs
import dev.sharptalons.obsdirector.net.Discovery
import dev.sharptalons.obsdirector.net.Protocol
import kotlinx.coroutines.delay

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = T.accent, unfocusedBorderColor = T.control, focusedTextColor = T.text, unfocusedTextColor = T.text,
    cursorColor = T.accent, focusedContainerColor = T.ground, unfocusedContainerColor = T.ground,
    focusedLabelColor = T.muted, unfocusedLabelColor = T.muted, focusedPlaceholderColor = T.muted, unfocusedPlaceholderColor = T.muted,
)

private val ipPattern = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")

/**
 * The one place to connect: opened from the connect icon in the top bar (or the Connect
 * buttons that stand in for it). Tap a PC to connect to it. The first time, someone at the PC
 * presses Allow in OBS; after that a tap is all it takes. It closes itself once connected.
 */
@Composable
fun ConnectDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val discovery = remember { Discovery(context) }
    val settings by Prefs.settings.collectAsState()
    DisposableEffect(Unit) {
        // A fresh list every time: only PCs that answer now are shown.
        discovery.search()
        onDispose { discovery.stop() }
    }
    val found by discovery.pcs.collectAsState()
    val link by Director.link.collectAsState()
    val current by Director.pc.collectAsState()
    val error by Director.error.collectAsState()
    val waiting by Director.awaitingApproval.collectAsState()

    var typed by remember { mutableStateOf<List<FoundPc>>(emptyList()) }
    var typing by remember { mutableStateOf(false) }
    var ip by remember { mutableStateOf("") }
    var ipError by remember { mutableStateOf<String?>(null) }
    var tapped by remember { mutableStateOf<String?>(null) }
    // Counts down while a search runs, so the button can say so.
    var searching by remember { mutableIntStateOf(6) }
    var searchRun by remember { mutableIntStateOf(0) }
    LaunchedEffect(searchRun) { searching = 6; while (searching > 0) { delay(1000); searching-- } }

    // Only what the scan finds now, plus an address typed in during this visit.
    val pcs = (found + typed).distinctBy { it.ip }

    LaunchedEffect(link) { if (tapped != null && link == LinkState.CONNECTED) onDismiss() }

    val connectTo = { p: FoundPc ->
        tapped = p.ip
        Director.connect(p)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(max = 640.dp)
                .clip(RoundedCornerShape(8.dp)).background(T.surface).border(1.dp, T.border, RoundedCornerShape(8.dp))
                .verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Connect to OBS", style = Type.title.copy(fontSize = 20.sp), modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.Close, "Close", tint = T.muted, modifier = Modifier.size(48.dp).clip(CircleShape).clickable(role = androidx.compose.ui.semantics.Role.Button) { onDismiss() }.padding(12.dp))
            }
            Text("Tap a PC to connect. The first time, someone at the PC presses Allow in OBS.", style = Type.small, modifier = Modifier.padding(top = 4.dp))

            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PCs on this network", style = Type.cardTitle, modifier = Modifier.weight(1f))
                OutlineButton(if (searching > 0) "Searching…" else "Search", Icons.Outlined.Refresh, enabled = searching == 0, height = 34.dp) {
                    discovery.search(); typed.forEach { discovery.probe(it.ip) }; searchRun++
                }
            }
            Spacer(Modifier.height(8.dp))
            if (pcs.isNotEmpty()) Text("A yellow dot means the PC answered the scan and is ready. Tap it to connect.", style = Type.small, modifier = Modifier.padding(bottom = 6.dp))
            if (pcs.isEmpty()) {
                Text(
                    if (searching > 0) "Looking for OBS… Make sure OBS is open on the PC, with the plugin installed, on the same Wi-Fi as this phone."
                    else "No PC found. Check OBS is open and on the same network, press Search, or enter the address below.",
                    style = Type.small, modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            pcs.forEach { p ->
                val isTarget = current?.ip == p.ip && link != LinkState.DISCONNECTED
                val status = when {
                    isTarget && link == LinkState.CONNECTED -> RowStatus.Connected
                    isTarget && waiting != null -> RowStatus.Waiting(waiting ?: "")
                    isTarget -> RowStatus.Connecting
                    tapped == p.ip && error != null -> RowStatus.Failed(error ?: "")
                    else -> RowStatus.Idle
                }
                PcRow(p, status, onDisconnect = { Director.disconnect() }) { if (status !is RowStatus.Connected) connectTo(p) }
            }

            // Typed address
            Spacer(Modifier.height(10.dp))
            if (!typing) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(6.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button) { typing = true }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Edit, null, tint = T.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Enter the IP address yourself", style = Type.body.copy(color = T.accent))
                }
            } else {
                Text("IP address shown in OBS, under Tools, OBS Director", style = Type.label)
                Spacer(Modifier.height(6.dp))
                val go = {
                    if (!ipPattern.matches(ip)) ipError = "That doesn't look like an address. It's four numbers with dots, like 192.168.1.24."
                    else {
                        val pc = pcs.firstOrNull { it.ip == ip } ?: FoundPc("PC at $ip", ip, Protocol.CONTROL_PORT, lastSeen = 0)
                        if (typed.none { it.ip == ip }) typed = typed + pc
                        discovery.probe(ip) // learn its name
                        connectTo(pc)
                        ip = ""; typing = false
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(ip, { ip = it.filter { c -> c.isDigit() || c == '.' }.take(15); ipError = null }, Modifier.weight(1f),
                        placeholder = { Text("192.168.1.24") }, singleLine = true, colors = fieldColors(), textStyle = Type.mono,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { go() }))
                    Spacer(Modifier.width(10.dp))
                    PrimaryButton("Connect", Icons.Outlined.Power, height = 52.dp) { go() }
                }
                ipError?.let { Text(it, style = Type.small.copy(color = T.negative), modifier = Modifier.padding(top = 6.dp)) }
            }

            Spacer(Modifier.height(14.dp))
            Text("This phone shows in OBS as \"${settings.deviceName}\". Change that in Settings, Connection.", style = Type.small)
        }
    }
}

private sealed class RowStatus {
    data object Idle : RowStatus()
    data object Connecting : RowStatus()
    data class Waiting(val message: String) : RowStatus()
    data object Connected : RowStatus()
    data class Failed(val message: String) : RowStatus()
}

@Composable
private fun PcRow(p: FoundPc, status: RowStatus, onDisconnect: () -> Unit, onClick: () -> Unit) {
    val active = status !is RowStatus.Idle && status !is RowStatus.Failed
    val edge = when (status) {
        is RowStatus.Connected -> T.positive
        is RowStatus.Waiting, is RowStatus.Connecting -> T.warning
        is RowStatus.Failed -> T.negative
        else -> T.border
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(6.dp))
            .background(if (active) T.raised else T.surface)
            .border(1.dp, edge, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            // Yellow: found by the scan, ready to connect. Green: connected. Grey: typed in, not found.
            val found = p.lastSeen > 0L
            val dot = when (status) {
                is RowStatus.Connected -> T.positive
                is RowStatus.Failed -> T.negative
                else -> if (found) T.warning else T.idle
            }
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = Type.body.copy(fontWeight = FontWeight.Medium))
                Text(
                    listOfNotNull(p.ip, if (p.lastSeen > 0L) "ready to connect" else "typed in, not found by the scan").joinToString("  ·  "),
                    style = Type.mono.copy(color = T.muted),
                )
            }
            when (status) {
                is RowStatus.Connected -> OutlineButton("Disconnect", Icons.Outlined.WifiOff, height = 34.dp) { onDisconnect() }
                is RowStatus.Connecting -> Text("Connecting…", style = Type.small.copy(color = T.warning))
                is RowStatus.Waiting -> Text("Waiting…", style = Type.small.copy(color = T.warning))
                else -> Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Connect", tint = T.muted, modifier = Modifier.size(22.dp))
            }
        }
        when (status) {
            is RowStatus.Connected -> Text("Connected", style = Type.small.copy(color = T.positive), modifier = Modifier.padding(start = 36.dp, top = 4.dp))
            is RowStatus.Waiting -> Text(status.message, style = Type.small.copy(color = T.warning), modifier = Modifier.padding(start = 36.dp, top = 4.dp))
            is RowStatus.Failed -> Text(status.message, style = Type.small.copy(color = T.negative), modifier = Modifier.padding(start = 36.dp, top = 4.dp))
            else -> {}
        }
    }
}
