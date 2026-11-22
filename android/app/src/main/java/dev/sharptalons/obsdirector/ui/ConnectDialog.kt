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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
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
 * buttons that stand in for it). Pick a PC found on the network, search again, or type its
 * address; enter the PIN; connect. It closes itself once connected.
 */
@Composable
fun ConnectDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val discovery = remember { Discovery(context) }
    DisposableEffect(Unit) { discovery.start(); onDispose { discovery.stop() } }
    val found by discovery.pcs.collectAsState()
    val settings by Prefs.settings.collectAsState()
    val link by Director.link.collectAsState()
    val current by Director.pc.collectAsState()
    val error by Director.error.collectAsState()

    var manual by remember { mutableStateOf<List<FoundPc>>(emptyList()) }
    var chosen by remember { mutableStateOf(settings.lastIp.takeIf { it.isNotBlank() }) }
    var typing by remember { mutableStateOf(false) }
    var ip by remember { mutableStateOf("") }
    var ipError by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf(settings.lastPin) }
    // Counts down while a search runs, so the button can say so.
    var searching by remember { mutableIntStateOf(6) }
    var searchRun by remember { mutableIntStateOf(0) }
    LaunchedEffect(searchRun) { searching = 6; while (searching > 0) { delay(1000); searching-- } }

    val last = if (settings.lastIp.isNotBlank())
        listOf(FoundPc(settings.lastPcName.ifBlank { "PC at ${settings.lastIp}" }, settings.lastIp, settings.lastPort, lastSeen = 0))
    else emptyList()
    val pcs = (found + manual + last).distinctBy { it.ip }
    if (chosen == null && pcs.isNotEmpty()) chosen = pcs.first().ip
    val target = pcs.firstOrNull { it.ip == chosen }

    // Connected (to the PC picked here): done.
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(link) { if (asked && link == LinkState.CONNECTED) onDismiss() }

    val go = {
        if (target != null && pin.length == 4) {
            asked = true
            Director.connect(target, pin)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(max = 640.dp)
                .clip(RoundedCornerShape(8.dp)).background(T.surface).border(1.dp, T.border, RoundedCornerShape(8.dp))
                .verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Connect to OBS", style = Type.title.copy(fontSize = 20.sp), modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.Close, "Close", tint = T.muted, modifier = Modifier.size(36.dp).clip(CircleShape).clickable { onDismiss() }.padding(6.dp))
            }
            Text("In OBS on the PC, open Tools, OBS Director to see the address and PIN.", style = Type.small, modifier = Modifier.padding(top = 4.dp))

            if (link == LinkState.CONNECTED && current != null) {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(T.pillOkBg).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Connected to ${current?.name ?: "the PC"}", style = Type.body.copy(fontWeight = FontWeight.Medium))
                        Text(current?.ip ?: "", style = Type.small)
                    }
                    OutlineButton("Disconnect", Icons.Outlined.WifiOff, height = 34.dp) { Director.disconnect() }
                }
            }

            // The PCs
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PCs on this network", style = Type.cardTitle, modifier = Modifier.weight(1f))
                OutlineButton(if (searching > 0) "Searching…" else "Search", Icons.Outlined.Refresh, enabled = searching == 0, height = 34.dp) {
                    discovery.search(); searchRun++
                }
            }
            Spacer(Modifier.height(8.dp))
            if (pcs.isEmpty()) {
                Text(
                    if (searching > 0) "Looking for OBS… Make sure OBS is open on the PC, with the plugin installed, on the same Wi-Fi as this phone."
                    else "No PC found. Check OBS is open and on the same network, press Search, or enter the address below.",
                    style = Type.small, modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            pcs.forEach { p -> PcRow(p, selected = p.ip == chosen, isCurrent = link == LinkState.CONNECTED && current?.ip == p.ip) { chosen = p.ip; if (p.ip != settings.lastIp) pin = "" else pin = settings.lastPin } }

            // Manual address
            Spacer(Modifier.height(10.dp))
            if (!typing) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(6.dp)).clickable { typing = true }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Edit, null, tint = T.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Enter the IP address yourself", style = Type.body.copy(color = T.accent))
                }
            } else {
                Text("IP address shown in OBS", style = Type.label)
                Spacer(Modifier.height(6.dp))
                val add = {
                    if (!ipPattern.matches(ip)) ipError = "That doesn't look like an address. It's four numbers with dots, like 192.168.1.24."
                    else {
                        if (manual.none { it.ip == ip }) manual = manual + FoundPc("PC at $ip", ip, Protocol.CONTROL_PORT, lastSeen = 0)
                        chosen = ip; pin = if (ip == settings.lastIp) settings.lastPin else ""; ip = ""; typing = false
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(ip, { ip = it.filter { c -> c.isDigit() || c == '.' }.take(15); ipError = null }, Modifier.weight(1f),
                        placeholder = { Text("192.168.1.24") }, singleLine = true, colors = fieldColors(), textStyle = Type.mono,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { add() }))
                    Spacer(Modifier.width(10.dp))
                    OutlineButton("Use") { add() }
                }
                ipError?.let { Text(it, style = Type.small.copy(color = T.negative), modifier = Modifier.padding(top = 6.dp)) }
            }

            // PIN and connect
            Spacer(Modifier.height(18.dp))
            Text("PIN", style = Type.cardTitle)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(4) }, Modifier.width(150.dp),
                    placeholder = { Text("4 digits") }, singleLine = true, colors = fieldColors(),
                    textStyle = Type.mono.copy(fontSize = 22.sp, letterSpacing = 8.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go() }))
                Box(Modifier.weight(1f)) {
                    PrimaryButton(if (link == LinkState.CONNECTING) "Connecting…" else "Connect", Icons.Outlined.Power, Modifier.fillMaxWidth(),
                        enabled = pin.length == 4 && target != null && link != LinkState.CONNECTING) { go() }
                }
            }
            when {
                target == null -> Text("Choose a PC above first.", style = Type.small, modifier = Modifier.padding(top = 8.dp))
                asked && error != null -> Text(error ?: "", style = Type.small.copy(color = T.negative), modifier = Modifier.padding(top = 8.dp))
                else -> Text("Connecting to ${target.name}. Volunteers see this phone as \"${settings.deviceName}\" in OBS; change that in Settings, Connection.",
                    style = Type.small, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun PcRow(p: FoundPc, selected: Boolean, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(6.dp))
            .background(if (selected) T.raised else T.surface)
            .border(1.dp, if (selected) T.accent else T.border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Computer, null, tint = if (selected) T.accent else T.muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = Type.body.copy(fontWeight = FontWeight.Medium))
            Text(
                listOfNotNull(
                    p.ip,
                    when {
                        isCurrent -> "connected"
                        p.lastSeen == 0L -> "last used"
                        else -> "found now"
                    },
                    p.plugin.takeIf { it.isNotBlank() }?.let { "plugin $it" },
                ).joinToString(" · "),
                style = Type.small,
            )
        }
        Box(Modifier.size(18.dp).clip(CircleShape).border(2.dp, if (selected) T.accent else T.control, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(T.accent))
        }
    }
}
