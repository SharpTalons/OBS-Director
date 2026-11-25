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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
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
fun PrimaryButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 48.dp, onClick: () -> Unit) {
    Row(
        modifier
            .height(height.coerceAtLeast(48.dp))
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
fun OutlineButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 48.dp, onClick: () -> Unit) {
    Row(
        modifier
            .height(height.coerceAtLeast(48.dp))
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
            .height(48.dp)
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
    // announced when it appears, so a new alert is heard without hunting for it
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite }.padding(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = T.text, fontWeight = FontWeight.Medium)) { append(title) }
                append(" ")
                withStyle(SpanStyle(color = ink)) { append(body) }
            }, style = Type.body, modifier = Modifier.weight(1f))
            if (onDismiss != null) Box(Modifier.size(48.dp).offset(x = 12.dp, y = (-12).dp).clip(CircleShape).clickable(role = Role.Button, onClick = onDismiss)
                .semantics { contentDescription = "Dismiss" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Close, null, tint = ink, modifier = Modifier.size(20.dp))
            }
        }
        Row(Modifier.padding(start = 30.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
    }
}

