package dev.sharptalons.obsdirector.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sharptalons.obsdirector.core.Director
import dev.sharptalons.obsdirector.core.FoundPc
import dev.sharptalons.obsdirector.core.LinkState
import dev.sharptalons.obsdirector.core.Prefs
import dev.sharptalons.obsdirector.net.Discovery
import dev.sharptalons.obsdirector.net.Protocol

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = T.accent, unfocusedBorderColor = T.control, focusedTextColor = T.text, unfocusedTextColor = T.text,
    cursorColor = T.accent, focusedContainerColor = T.ground, unfocusedContainerColor = T.ground,
    focusedLabelColor = T.muted, unfocusedLabelColor = T.muted, focusedPlaceholderColor = T.muted, unfocusedPlaceholderColor = T.muted,
)

@Composable
fun ConnectScreen(onConnected: () -> Unit) {
    val context = LocalContext.current
    val discovery = remember { Discovery(context) }
    DisposableEffect(Unit) { discovery.start(); onDispose { discovery.stop() } }
    val found by discovery.pcs.collectAsState()
    val settings by Prefs.settings.collectAsState()
    val link by Director.link.collectAsState()
    val error by Director.error.collectAsState()

    var manual by remember { mutableStateOf<List<FoundPc>>(emptyList()) }
    var chosen by remember { mutableStateOf(settings.lastIp.takeIf { it.isNotBlank() }) }
    var ip by remember { mutableStateOf("") }
    var ipError by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf(settings.lastPin) }
    var device by remember { mutableStateOf(settings.deviceName) }

    val last = if (settings.lastIp.isNotBlank()) listOf(FoundPc(settings.lastPcName.ifBlank { "PC at ${settings.lastIp}" }, settings.lastIp, settings.lastPort, lastSeen = 0)) else emptyList()
    val pcs = (found + manual + last).distinctBy { it.ip }
    if (chosen == null && pcs.isNotEmpty()) chosen = pcs.first().ip

    LaunchedEffect(link) { if (link == LinkState.CONNECTED) onConnected() }

    Text("Three steps. In OBS, Tools, OBS Director shows the address and PIN.", style = Type.finding)
    Spacer(Modifier.height(16.dp))

    DCard {
        Text("Step 1", style = Type.label)
        CardHeader("Choose the OBS PC", null) { Text("Found on this network", style = Type.small) }
        if (pcs.isEmpty()) Text("Looking for OBS Director on this network… Make sure OBS is open with the plugin installed.", style = Type.small)
        pcs.forEach { p ->
            val on = p.ip == chosen
            Column(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { chosen = p.ip }
                    .drawBehind { if (on) drawLine(T.accent, Offset(0f, 18f), Offset(0f, size.height - 18f), 2f) }
                    .padding(start = 14.dp, top = 10.dp, bottom = 10.dp)
            ) {
                Text(p.name, style = Type.body.copy(fontWeight = FontWeight.Medium))
                Text(listOfNotNull(p.ip, p.obs.takeIf { it.isNotBlank() }?.let { "OBS $it" },
                    p.plugin.takeIf { it.isNotBlank() }?.let { "plugin $it" } ?: if (p.lastSeen == 0L) "last used" else null).joinToString(" · "),
                    style = Type.small)
            }
            HorizontalDivider(color = T.rule)
        }
        Spacer(Modifier.height(12.dp))
        Text("Not listed? Type the address OBS shows", style = Type.label)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(ip, { ip = it.filter { c -> c.isDigit() || c == '.' }; ipError = null }, Modifier.weight(1f),
                placeholder = { Text("192.168.1.24") }, singleLine = true, colors = fieldColors(), textStyle = Type.mono,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Spacer(Modifier.width(10.dp))
            OutlineButton("Add") {
                if (!Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(ip)) ipError = "That doesn't look like an address. It's four numbers with dots, like 192.168.1.24."
                else { manual = manual + FoundPc("PC at $ip", ip, Protocol.CONTROL_PORT, lastSeen = 0); chosen = ip; ip = "" }
            }
        }
        ipError?.let { Text(it, style = Type.small.copy(color = T.negative), modifier = Modifier.padding(top = 6.dp)) }
    }

    Spacer(Modifier.height(16.dp))
    DCard {
        Text("Step 2", style = Type.label)
        CardHeader("Transmission mode", null) { Text("Fastest first", style = Type.small) }
        ModeRow("Direct UDP", "This app · raw audio, tiny buffer, drift-corrected playback", "≈40 ms", selected = true, enabled = true, fastest = true)
        ModeRow("WebRTC", "For the browser version, coming later", "≈85 ms", selected = false, enabled = false)
        ModeRow("WebSocket", "For the browser version, coming later", "≈150 ms", selected = false, enabled = false)
    }

    Spacer(Modifier.height(16.dp))
    DCard {
        Text("Step 3", style = Type.label)
        CardHeader("Enter the PIN from OBS", null)
        OutlinedTextField(device, { device = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("This device appears in OBS as") },
            singleLine = true, colors = fieldColors(), textStyle = Type.body)
        Spacer(Modifier.height(12.dp))
        val go = {
            val target = pcs.firstOrNull { it.ip == chosen }
            if (target != null && pin.length == 4) {
                Prefs.update { it.copy(deviceName = device.ifBlank { it.deviceName }) }
                Director.connect(target, pin)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(4) }, Modifier.width(170.dp),
                label = { Text("4-digit PIN") }, singleLine = true, colors = fieldColors(),
                textStyle = Type.mono.copy(fontSize = 22.sp, letterSpacing = 8.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }))
            Spacer(Modifier.width(12.dp))
            PrimaryButton(if (link == LinkState.CONNECTING) "Connecting…" else "Connect", Icons.Outlined.Power,
                enabled = pin.length == 4 && chosen != null && link != LinkState.CONNECTING) { go() }
        }
        error?.let { Text(it, style = Type.small.copy(color = T.negative), modifier = Modifier.padding(top = 10.dp)) }
        Spacer(Modifier.height(12.dp))
        Text("The PIN pairs this device. Volunteers see this device's name in the OBS dock while you're connected.", style = Type.small)
    }
}

@Composable
private fun ModeRow(name: String, meta: String, ms: String, selected: Boolean, enabled: Boolean, fastest: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .drawBehind { if (selected) drawLine(T.accent, Offset(0f, 18f), Offset(0f, size.height - 18f), 2f) }
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = Type.body.copy(fontWeight = FontWeight.Medium, color = if (enabled) T.text else T.muted))
                if (fastest) { Spacer(Modifier.width(8.dp)); Pill("Fastest", PillKind.OK, lamp = false) }
            }
            Text(meta, style = Type.small)
        }
        Text(ms, style = Type.figure.copy(color = if (enabled) T.text else T.muted))
    }
    HorizontalDivider(color = T.rule)
}
