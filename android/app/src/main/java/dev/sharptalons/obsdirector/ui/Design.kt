package dev.sharptalons.obsdirector.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sharptalons.obsdirector.audio.Levels
import dev.sharptalons.obsdirector.core.FoundPc
import dev.sharptalons.obsdirector.core.LinkState
import kotlin.math.max

/** The rounded card every section sits in. */
@Composable
fun Section(modifier: Modifier = Modifier, padding: Dp = 18.dp, gap: Dp = 14.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(T.surface).padding(padding),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** A screen title, announced as a heading. */
@Composable
fun ScreenTitle(text: String, sub: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)) {
        Text(text, style = Type.title.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold), modifier = Modifier.semantics { heading() })
        if (sub != null) Text(sub, style = Type.small.copy(fontSize = 13.sp), modifier = Modifier.padding(top = 4.dp))
    }
}

/** A small coloured group label, announced as a heading. */
@Composable
fun GroupLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text, style = Type.label.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color),
        modifier = modifier.padding(start = 6.dp, top = 16.dp, bottom = 8.dp).semantics { heading() })
}

/** A tinted square with an icon: colour says which category the row belongs to. */
@Composable
fun TintTile(icon: ImageVector, tint: Tint, size: Dp = 32.dp, iconSize: Dp = 18.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.31f)).background(tint.bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint.ink, modifier = Modifier.size(iconSize))
    }
}

/** A tinted chip. With [onClick] it's a button and shows a chevron. */
@Composable
fun TintChip(text: String, tint: Tint, dot: Color? = null, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(12.dp)).background(tint.bg)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (dot != null) { Box(Modifier.size(8.dp).clip(CircleShape).background(dot)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = Type.body.copy(fontSize = 13.sp, color = tint.ink, fontWeight = if (onClick != null) FontWeight.Medium else FontWeight.Normal),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (onClick != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = tint.ink, modifier = Modifier.size(16.dp))
    }
}

/**
 * The bar across the top of every screen: the app, the PC and how the link is, Stage mode and
 * the connect icon. [waiting] is the "Waiting for Allow in OBS" text while that's happening.
 */
@Composable
fun HeadBar(link: LinkState, pc: FoundPc?, latencyMs: Int, waiting: String?, onStage: () -> Unit, onConnect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // the app's own mark: a white rounded square with a red record dot
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(T.surface).border(2.dp, T.text, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(T.negative))
        }
        Column(Modifier.weight(1f)) {
            Text("OBS Director", style = Type.body.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(
                    when (link) { LinkState.CONNECTED -> T.positive; LinkState.CONNECTING, LinkState.LOST -> T.warning; else -> T.muted }))
                Spacer(Modifier.width(6.dp))
                Text(
                    when (link) {
                        LinkState.CONNECTED -> "${pc?.name ?: "OBS PC"} · $latencyMs ms"
                        LinkState.CONNECTING -> if (waiting != null) "Waiting for Allow in OBS on ${pc?.name ?: "the PC"}" else "Connecting to ${pc?.name ?: pc?.ip ?: "the PC"}…"
                        LinkState.LOST -> "${pc?.name ?: "OBS PC"} · reconnecting"
                        LinkState.DISCONNECTED -> "Not connected"
                    },
                    style = Type.small, maxLines = 1,
                )
            }
        }
        RoundIcon(Icons.Outlined.TheaterComedy, "Stage mode", T.warning, onStage)
        val (icon, tint, label) = when (link) {
            LinkState.CONNECTED -> Triple(Icons.Outlined.CastConnected, T.positive, "Connected. Change or disconnect")
            LinkState.CONNECTING, LinkState.LOST -> Triple(Icons.Outlined.Cast, T.warning, "Connecting")
            LinkState.DISCONNECTED -> Triple(Icons.Outlined.Cast, T.accent, "Connect to OBS")
        }
        RoundIcon(icon, label, tint, onConnect)
    }
    HorizontalDivider(color = T.rule)
}

@Composable
fun RoundIcon(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(T.surface2).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
}

/**
 * The mix meter, drawn like OBS's: sample peaks on a -60 to 0 dB scale, green to -20 dB, yellow
 * to -9 dB, red above, falling back at OBS's default rate and holding the highest peak for 20 s.
 * It draws on its own canvas, so 60 updates a second never recompose the screen around it.
 */
@Composable
fun ObsMeter(levels: Levels, active: Boolean, modifier: Modifier = Modifier, barHeight: Dp = 10.dp, showScale: Boolean = true, dim: Boolean = false) {
    val latest by rememberUpdatedState(levels)
    var shownL by remember { mutableFloatStateOf(-60f) }
    var shownR by remember { mutableFloatStateOf(-60f) }
    var holdL by remember { mutableFloatStateOf(-60f) }
    var holdR by remember { mutableFloatStateOf(-60f) }
    var holdAtL by remember { mutableLongStateOf(0L) }
    var holdAtR by remember { mutableLongStateOf(0L) }
    LaunchedEffect(active) {
        var last = 0L
        // once listening stops and the bars have fallen, the meter stops drawing frames
        while (active || shownL > -60f || shownR > -60f) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1e9f
                last = now
                val inL = if (active) latest.leftDb.coerceIn(-60f, 0f) else -60f
                val inR = if (active) latest.rightDb.coerceIn(-60f, 0f) else -60f
                shownL = max(inL, shownL - DECAY_DB_PER_S * dt)
                shownR = max(inR, shownR - DECAY_DB_PER_S * dt)
                val ms = now / 1_000_000
                if (shownL >= holdL || ms - holdAtL > HOLD_MS) { holdL = shownL; holdAtL = ms }
                if (shownR >= holdR || ms - holdAtR > HOLD_MS) { holdR = shownR; holdAtR = ms }
            }
        }
        holdL = -60f; holdR = -60f
    }
    val peak = max(holdL, holdR)
    Column(modifier.semantics {
        contentDescription = "Mix level"
        stateDescription = if (!active) "Not listening" else "Left ${shownL.toInt()} dB, right ${shownR.toInt()} dB, peak ${"%.1f".format(peak)} dB"
    }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        MeterBar(shownL, holdL, barHeight, dim)
        MeterBar(shownR, holdR, barHeight, dim)
        if (showScale) {
            // a tick every 5 dB, a number every 10 dB, like the scale beside OBS's own meter
            val tick = T.muted
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                for (d in 0..12) {
                    val x = (size.width * d / 12f).coerceIn(0.5f, size.width - 0.5f)
                    drawRect(tick, Offset(x - 0.5f, 0f), Size(1f, if (d % 2 == 0) size.height else size.height / 2))
                }
            }
            Box(Modifier.fillMaxWidth().height(14.dp).clearAndSetSemantics { }) {
                val st = Type.label.copy(fontSize = 10.sp)
                for (d in 0..6) {
                    val db = -60 + d * 10
                    val text = if (db == 0) "0" else "−${-db}"
                    val align = when (d) { 0 -> Alignment.CenterStart; 6 -> Alignment.CenterEnd; else -> BiasAlignment(2 * d / 6f - 1f) }
                    Text(text, style = st, modifier = Modifier.align(align))
                }
            }
        }
    }
}

/** Peak readout for next to the meter, e.g. "-14.2 dB". */
fun peakLabel(levels: Levels, active: Boolean): String =
    if (!active) "off" else "%.1f dB".format(max(levels.leftDb, levels.rightDb).coerceIn(-60f, 0f)).replace("-", "−")

private const val DECAY_DB_PER_S = 23.53f // OBS's default "Fast" decay
private const val HOLD_MS = 20_000L       // OBS's default peak hold

private fun BiasAlignment(h: Float) = androidx.compose.ui.BiasAlignment(h, 0f)

@Composable
private fun MeterBar(db: Float, holdDb: Float, height: Dp, dim: Boolean) {
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        val g = w * 40f / 60f   // -20 dB
        val y = w * 51f / 60f   // -9 dB
        val lit = w * ((db + 60f) / 60f).coerceIn(0f, 1f)
        val on = if (dim) listOf(Stage.green, Stage.yellow, Stage.red) else listOf(MeterColors.green, MeterColors.yellow, MeterColors.red)
        val off = if (dim) listOf(Stage.track, Stage.track, Stage.track) else listOf(MeterColors.greenOff, MeterColors.yellowOff, MeterColors.redOff)
        val zones = listOf(0f to g, g to y, y to w)
        zones.forEachIndexed { i, (a, b) ->
            drawRect(off[i], Offset(a, 0f), Size(b - a, h))
            if (lit > a) drawRect(on[i], Offset(a, 0f), Size(minOf(lit, b) - a, h))
        }
        if (!dim && holdDb > -60f) {
            val x = w * ((holdDb + 60f) / 60f).coerceIn(0f, 1f)
            drawRect(Color.White, Offset((x - 1f).coerceAtLeast(0f), 0f), Size(2f, h))
        }
    }
}

/**
 * The big Clip button. While a clip runs, it shows the clip's length large inside it.
 * [lengthText] is like "1:12" (the lead time counts: a clip starts before the tap).
 */
@Composable
fun ClipButton(running: Boolean, lengthText: String, enabled: Boolean, hint: String, modifier: Modifier = Modifier, height: Dp = 88.dp, onClick: () -> Unit) {
    if (running) {
        Row(
            modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(16.dp)).background(T.negative)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = "End clip, $lengthText long" }
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("End clip", style = Type.body.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = T.onRec))
                Text("clip length", style = Type.body.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = T.onRec))
            }
            Text(lengthText, style = Type.timecode.copy(fontSize = 40.sp, fontWeight = FontWeight.Bold, color = T.onRec, letterSpacing = (-1).sp))
        }
    } else {
        Column(
            modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(16.dp))
                .border(2.dp, if (enabled) T.negative else T.control, RoundedCornerShape(16.dp))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text("Start clip", style = Type.body.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (enabled) T.text else T.muted))
            Text(hint, style = Type.small.copy(fontSize = 13.sp), modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** A clip's length as "m:ss" (with hours if it ever gets that long). */
fun clipLength(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** A list row that opens a choice, a switch row, or a plain information row, with a tinted icon. */
@Composable
fun SettingItem(icon: ImageVector, tint: Tint, label: String, divider: Boolean, value: String? = null, checked: Boolean? = null, onClick: (() -> Unit)? = null) {
    Column {
        if (divider) HorizontalDivider(color = T.raised)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .then(
                    when {
                        checked != null && onClick != null -> Modifier.clickable(role = Role.Switch, onClick = onClick).semantics { stateDescription = if (checked) "On" else "Off" }
                        onClick != null -> Modifier.clickable(role = Role.Button, onClick = onClick)
                        else -> Modifier
                    }
                )
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TintTile(icon, tint)
            // A long value ("Automatic · Bluetooth · oraimo Necklace Lite") goes under the name
            // instead of squeezing it into a column of single letters.
            val below = value != null && value.length > 18
            Column(Modifier.weight(1f)) {
                Text(label, style = Type.body.copy(fontSize = 15.sp))
                if (below) Text(value!!, style = Type.small.copy(fontSize = 13.sp, color = tint.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            when {
                checked != null -> Box(
                    Modifier.size(52.dp, 30.dp).clip(CircleShape).background(if (checked) T.blue else T.control).padding(3.dp),
                    contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
                ) { Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White)) }
                value != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!below) Text(value, style = Type.body.copy(color = tint.ink), maxLines = 1)
                    // values line up whether or not they have an arrow
                    if (onClick != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = tint.ink, modifier = Modifier.size(18.dp))
                    else Spacer(Modifier.width(18.dp))
                }
            }
        }
    }
}

/** A short sheet from the bottom with one choice per row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChoiceSheet(title: String, options: List<String>, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = T.surface) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = Type.cardTitle.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(bottom = 4.dp).semantics { heading() })
            options.forEach { o ->
                val on = o == current
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(T.sunken)
                        .border(2.dp, if (on) T.accent else T.control, RoundedCornerShape(14.dp))
                        .clickable(role = Role.RadioButton) { onPick(o) }.semantics { selected = on }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (on) T.accent else T.control, CircleShape), contentAlignment = Alignment.Center) {
                        if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(T.accent))
                    }
                    Text(o, style = Type.body.copy(fontSize = 15.sp))
                }
            }
        }
    }
}
