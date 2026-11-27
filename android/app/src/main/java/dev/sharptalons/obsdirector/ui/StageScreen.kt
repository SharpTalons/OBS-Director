package dev.sharptalons.obsdirector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sharptalons.obsdirector.core.ClipStore
import dev.sharptalons.obsdirector.core.Director
import dev.sharptalons.obsdirector.core.LinkState
import dev.sharptalons.obsdirector.core.Prefs

/**
 * Stage mode: for a phone propped up in a dark room. Pure black, dim, the screen kept on and
 * turned down; just the timecode, the meter and a big Clip button.
 */
@Composable
fun StageScreen(onExit: () -> Unit) {
    val status by Director.status.collectAsState()
    val link by Director.link.collectAsState()
    val levels by Director.levels.collectAsState()
    val listening by Director.listening.collectAsState()
    val clipStart by Director.clipStart.collectAsState()
    val recordings by ClipStore.recordings.collectAsState()
    val settings by Prefs.settings.collectAsState()
    @Suppress("UNUSED_VARIABLE") val tick = rememberTicker()
    val lost = link == LinkState.LOST
    val clips = recordings.firstOrNull { it.file == status.file }?.clips?.size ?: 0

    // Dim the screen and keep it on while Stage mode is up.
    val activity = LocalContext.current as? android.app.Activity
    DisposableEffect(Unit) {
        val w = activity?.window
        val before = w?.attributes?.screenBrightness
        w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        w?.attributes = w?.attributes?.apply { screenBrightness = 0.15f }
        onDispose {
            w?.attributes = w?.attributes?.apply { screenBrightness = before ?: -1f }
            if (!settings.keepScreenOn) w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Column(
        Modifier.fillMaxSize().semantics { paneTitle = "Stage mode" }.background(Stage.ground).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (status.recActive) Stage.rec else Stage.muted))
            Spacer(Modifier.width(10.dp))
            Text(listOf(if (status.recActive) (if (status.recPaused) "PAUSED" else "REC") else "NOT RECORDING", Director.pc.value?.name ?: "OBS").joinToString(" · "),
                style = Type.body.copy(fontSize = 13.sp, color = Stage.muted), modifier = Modifier.weight(1f))
            Text("Exit stage mode", style = Type.body.copy(color = Stage.muted),
                modifier = Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onExit).padding(horizontal = 4.dp, vertical = 12.dp))
        }
        // landscape: the time and meter on the left, the Clip button filling the right
        val wide = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
        val running = clipStart != null
        val enabled = status.recActive && !lost
        val length = clipLength(Director.nowFileTimeMs() - (clipStart ?: 0))
        val info: @Composable (Modifier) -> Unit = { m -> Column(m, verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically)) {
            Text(Director.tc(if (status.recActive && !lost) Director.nowFileTimeMs() else status.fileTimeMs),
                style = Type.timecode.copy(fontSize = 72.sp, fontWeight = FontWeight.Normal, letterSpacing = (-2).sp, color = Stage.time),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            ObsMeter(levels, listening && !lost, barHeight = 6.dp, showScale = false, dim = true)
            Text(if (clips == 1) "1 clip so far" else "$clips clips so far", style = Type.body.copy(color = Stage.hint), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        } }
        val clipButton: @Composable (Modifier) -> Unit = { m -> Column(
            m.clip(RoundedCornerShape(28.dp))
                .background(if (running) Stage.clip else Stage.surface)
                .border(2.dp, if (running) Stage.clip else Stage.clip.copy(alpha = if (enabled) 1f else 0.4f), RoundedCornerShape(28.dp))
                .clickable(enabled = enabled, role = Role.Button) { if (running) Director.endClip() else Director.startClip() }
                .semantics { contentDescription = if (running) "End clip, $length long" else "Start clip" },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            if (running) {
                Text(length, style = Type.timecode.copy(fontSize = 64.sp, fontWeight = FontWeight.Medium, letterSpacing = (-2).sp, color = Stage.clipInk))
                Text("End clip", style = Type.body.copy(fontSize = 18.sp, color = Stage.clipLabel))
            } else {
                Text("Clip", style = Type.body.copy(fontSize = 26.sp, fontWeight = FontWeight.Medium, color = if (enabled) Stage.text else Stage.muted))
                Text(if (enabled) "starts ${settings.leadMinutes} min before your tap" else "Start recording to clip",
                    style = Type.body.copy(fontSize = 15.sp, color = Stage.hint), modifier = Modifier.padding(top = 6.dp))
            }
        } }
        if (wide) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                info(Modifier.weight(1f).fillMaxHeight())
                clipButton(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            info(Modifier.weight(1f).fillMaxWidth())
            clipButton(Modifier.fillMaxWidth().height(180.dp))
        }
        Text("The screen stays on and dim. Exit stage mode is at the top.", style = Type.small.copy(color = Stage.hint), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
