package dev.sharptalons.obsdirector.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sharptalons.obsdirector.core.Tag
import kotlinx.coroutines.launch

@Composable
fun DCard(modifier: Modifier = Modifier, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(T.surface)
            .border(1.dp, T.border, RoundedCornerShape(6.dp))
            .padding(padding),
        content = content,
    )
}

@Composable
fun CardHeader(title: String, icon: ImageVector?, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = T.muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(title, style = Type.cardTitle, modifier = Modifier.weight(1f))
        trailing()
    }
}

enum class PillKind { REC, WARN, OK, IDLE }

@Composable
fun Pill(text: String, kind: PillKind, lamp: Boolean = true) {
    val (bg, ink) = when (kind) {
        PillKind.REC -> T.pillNegBg to T.negative
        PillKind.WARN -> T.pillWarnBg to T.warning
        PillKind.OK -> T.pillOkBg to T.pillOkInk
        PillKind.IDLE -> T.pillNeutralBg to T.pillNeutralInk
    }
    Row(
        Modifier.clip(CircleShape).background(bg).padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lamp) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(ink))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = Type.small.copy(color = ink, fontWeight = FontWeight.Medium))
    }
}

/** A label over a monospace figure with a grey unit. */
@Composable
fun Fig(label: String, value: String, unit: String = "", modifier: Modifier = Modifier, valueColor: Color = T.text) {
    Column(modifier) {
        Text(label, style = Type.label)
        Spacer(Modifier.height(4.dp))
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = valueColor)) { append(value) }
            if (unit.isNotEmpty()) withStyle(SpanStyle(color = T.muted, fontSize = 12.sp)) { append(" $unit") }
        }, style = Type.figure, maxLines = 1)
    }
}

@Composable
fun FigRow(vararg figs: Triple<String, String, String>, warnIndex: Int = -1) {
    HorizontalDivider(color = T.rule, modifier = Modifier.padding(top = 18.dp))
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 18.dp)) {
        figs.forEachIndexed { i, (l, v, u) ->
            if (i > 0) Box(Modifier.width(1.dp).height(40.dp).background(T.rule))
            Fig(l, v, u, Modifier.weight(1f).padding(start = if (i > 0) 10.dp else 0.dp), if (i == warnIndex) T.warning else T.text)
        }
    }
}

@Composable
fun DSwitch(checked: Boolean, onChange: (Boolean) -> Unit, label: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier
            .heightIn(min = 44.dp)
            .clickable(role = Role.Switch) { onChange(!checked) }
            .semantics { if (label != null) contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp, 30.dp).clip(CircleShape).background(if (checked) T.blue else T.control).padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) { Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White)) }
        if (label != null) {
            Spacer(Modifier.width(10.dp))
            Text(label, style = Type.body)
        }
    }
}

@Composable
fun PrimaryButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 44.dp, onClick: () -> Unit) {
    Row(
        modifier
            .height(height)
            .clip(CircleShape)
            .background(if (enabled) T.blue else T.raised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, null, tint = if (enabled) Color.White else T.muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = Type.body.copy(color = if (enabled) Color.White else T.muted, fontWeight = FontWeight.Medium))
    }
}

@Composable
fun OutlineButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 44.dp, onClick: () -> Unit) {
    Row(
        modifier
            .height(height)
            .clip(CircleShape)
            .border(1.dp, T.control, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val c = if (enabled) T.text else T.muted
        if (icon != null) { Icon(icon, null, tint = c, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = Type.body.copy(color = c, fontWeight = FontWeight.Medium))
    }
}

/** Press and hold for [holdMs]; a red fill shows the progress. Releasing early cancels. */
@Composable
fun HoldButton(text: String, icon: ImageVector?, holdMs: Int, modifier: Modifier = Modifier, enabled: Boolean = true, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .height(44.dp)
            .clip(CircleShape)
            .border(1.dp, T.control, CircleShape)
            .drawBehind { drawRect(T.negative, size = size.copy(width = size.width * progress.value)) }
            .pointerInput(enabled, holdMs) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    val job = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(holdMs, easing = LinearEasing))
                        onDone()
                        progress.snapTo(0f)
                    }
                    waitForUpOrCancellation()
                    if (progress.value < 1f) job.cancel()
                    scope.launch { progress.animateTo(0f, tween(150)) }
                }
            }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val c = if (!enabled) T.muted else if (progress.value > 0.05f) Color.White else T.text
            if (icon != null) { Icon(icon, null, tint = c, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, style = Type.body.copy(color = c, fontWeight = FontWeight.Medium))
        }
    }
}

/** Selection is always one 1px accent line under the chosen label. */
@Composable
fun Tabs(options: List<String>, selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier
                    .heightIn(min = 36.dp)
                    .clickable(role = Role.Tab) { onSelect(o) }
                    .drawBehind { if (on) drawLine(T.accent, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 2f) },
                contentAlignment = Alignment.Center,
            ) { Text(o, style = Type.body.copy(color = if (on) T.text else T.muted, fontSize = 13.sp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChips(selected: List<String>, onToggle: (String) -> Unit, modifier: Modifier = Modifier, allChip: Boolean = false, allSelected: Boolean = false, onAll: () -> Unit = {}) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (allChip) Chip("All", null, allSelected) { onAll() }
        Tag.entries.forEach { t -> Chip(t.label, Color(t.color), selected.contains(t.id)) { onToggle(t.id) } }
    }
}

@Composable
fun Chip(text: String, dot: Color?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .height(34.dp)
            .clip(CircleShape)
            .border(1.dp, if (selected) T.muted else T.control, CircleShape)
            .background(if (selected) T.raised else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) { Box(Modifier.size(8.dp).clip(CircleShape).background(dot)); Spacer(Modifier.width(7.dp)) }
        Text(text, style = Type.body.copy(fontSize = 13.sp, color = if (selected) T.text else T.muted))
    }
}

@Composable
fun TagPill(t: Tag) {
    Row(
        Modifier.padding(start = 6.dp).clip(CircleShape).background(T.raised).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(Color(t.color)))
        Spacer(Modifier.width(5.dp))
        Text(t.label, style = Type.small.copy(color = T.pillNeutralInk))
    }
}

@Composable
fun MeterRow(name: String, db: Float, active: Boolean) {
    val frac = ((db + 60f) / 60f).coerceIn(0f, 1f)
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = Type.body, modifier = Modifier.width(64.dp))
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(2.dp)).background(T.raised)) {
            Box(Modifier.fillMaxWidth().fillMaxHeight().padding(start = 0.dp).drawBehind {
                drawRect(Color(0x2EE5566F), topLeft = Offset(size.width * 0.85f, 0f), size = size.copy(width = size.width * 0.15f))
                if (active) drawRect(T.meter, size = size.copy(width = size.width * frac))
            })
        }
        Text(
            if (!active) "–" else if (db <= -59f) "-∞ dB" else "%.1f dB".format(db),
            style = Type.mono.copy(color = if (db > -0.5f && active) T.negative else T.text),
            modifier = Modifier.width(72.dp).padding(start = 10.dp),
        )
    }
    HorizontalDivider(color = T.rule)
}

@Composable
fun SettingRow(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Type.body.copy(color = T.muted), modifier = Modifier.weight(1f).padding(end = 12.dp))
            value()
        }
        HorizontalDivider(color = T.rule)
    }
}

@Composable
fun Banner(kind: PillKind, icon: ImageVector, title: String, body: String, onDismiss: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val (bg, ink) = when (kind) {
        PillKind.REC -> T.pillNegBg to T.negative
        PillKind.WARN -> T.pillWarnBg to T.warning
        PillKind.OK -> T.pillOkBg to T.pillOkInk
        PillKind.IDLE -> T.raised to T.text
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(6.dp)).background(bg).padding(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = T.text, fontWeight = FontWeight.Medium)) { append(title) }
                append(" ")
                withStyle(SpanStyle(color = ink)) { append(body) }
            }, style = Type.body, modifier = Modifier.weight(1f))
            if (onDismiss != null) Icon(Icons.Outlined.Close, "Dismiss", tint = ink, modifier = Modifier.size(28.dp).clickable { onDismiss() }.padding(4.dp))
        }
        Row(Modifier.padding(start = 30.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, style = Type.label, modifier = Modifier.padding(bottom = 4.dp))
}
