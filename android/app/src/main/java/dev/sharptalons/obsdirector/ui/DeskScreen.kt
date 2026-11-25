package dev.sharptalons.obsdirector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sharptalons.obsdirector.core.DeskMessage
import dev.sharptalons.obsdirector.core.Director
import dev.sharptalons.obsdirector.core.LinkState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Each sender keeps one colour for their name, as in a group chat. */
private val nameInks = listOf(Tints.blue.ink, Tints.amber.ink, Tints.ok.ink, Tints.rec.ink, Tints.purple.ink, Tints.teal.ink, Tints.orange.ink)
private fun nameInk(name: String) = nameInks[(name.hashCode() and 0x7fffffff) % nameInks.size]

private fun dayLabel(at: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = at }
    val today = Calendar.getInstance()
    val sameYear = c.get(Calendar.YEAR) == today.get(Calendar.YEAR)
    val d = today.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && d == 0 -> "Today"
        sameYear && d == 1 -> "Yesterday"
        else -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(at))
    }
}

/**
 * The desk chat: a group chat with the OBS desk and every phone and browser connected, laid out
 * like a messaging app. Others on the left with their name, this phone's on the right with ticks:
 * a clock while sending, grey ticks when OBS has it, blue when the desk has seen it.
 * Enter starts a new line; Ctrl+Enter (on a keyboard) or the send button sends.
 */
@Composable
fun DeskScreen(modifier: Modifier) {
    val msgs by Director.desk.collectAsState()
    val link by Director.link.collectAsState()
    val connected = link == LinkState.CONNECTED
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val list = rememberLazyListState()

    DisposableEffect(Unit) { Director.setDeskOpen(true); onDispose { Director.setDeskOpen(false) } }
    // newest at the bottom; follow new messages
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(0) }

    val send = { if (connected && draft.text.isNotBlank()) { Director.sendDesk(draft.text); draft = TextFieldValue("") } }

    Column(modifier.background(T.ground)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Desk", style = Type.title.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold), modifier = Modifier.semantics { heading() })
                Text(if (connected) "Everyone connected to OBS sees these" else "Connect to OBS to chat", style = Type.small.copy(fontSize = 13.sp))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (msgs.isEmpty()) {
                Text("No messages yet. Write to the volunteers at the OBS desk; everyone connected sees it.",
                    style = Type.body.copy(color = T.muted), modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            // reverse layout keeps the newest message at the bottom, like a chat
            LazyColumn(Modifier.fillMaxWidth(), state = list, reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val rows = msgs.asReversed()
                items(rows.size, key = { rows[it].id }) { i ->
                    val m = rows[i]
                    val older = rows.getOrNull(i + 1)
                    Column {
                        if (older == null || dayLabel(older.at) != dayLabel(m.at)) DayChip(dayLabel(m.at))
                        when (m.kind) {
                            "alert" -> AlertCard(m)
                            "note" -> NoteChip(m)
                            else -> Bubble(m, showName = !m.mine && (older == null || older.kind != "message" || older.from != m.from || older.mine || dayLabel(older.at) != dayLabel(m.at)))
                        }
                    }
                }
            }
        }
        // the bar at the bottom: the message box and send
        Row(Modifier.fillMaxWidth().background(T.surface).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(T.raised).padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart) {
                if (draft.text.isEmpty()) Text(if (connected) "Message" else "Not connected", style = Type.body.copy(fontSize = 16.sp, color = T.muted))
                BasicTextField(
                    draft, { draft = TextFieldValue(it.text.take(1000), it.selection, it.composition) },
                    enabled = connected,
                    textStyle = Type.body.copy(fontSize = 16.sp, color = T.text),
                    cursorBrush = SolidColor(T.accent),
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Message" }
                        // Enter is a new line; Ctrl+Enter on a keyboard sends
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && e.isCtrlPressed) { send(); true } else false
                        },
                )
            }
            val canSend = connected && draft.text.isNotBlank()
            Box(Modifier.size(48.dp).clip(CircleShape).background(if (canSend) T.blue else T.raised)
                .clickable(enabled = canSend, role = Role.Button) { send() }.semantics { contentDescription = "Send" },
                contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Outlined.Send, null, tint = if (canSend) Color.White else T.muted, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun DayChip(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Type.small.copy(fontSize = 12.sp, color = T.text2),
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(T.surface).padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

@Composable
private fun Bubble(m: DeskMessage, showName: Boolean) {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.at))
    val failed = m.state == "failed"
    Row(Modifier.fillMaxWidth().padding(top = if (showName) 6.dp else 0.dp), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
        if (m.mine) Spacer(Modifier.width(48.dp))
        Column(
            Modifier.widthIn(max = 320.dp)
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = if (m.mine) 14.dp else 4.dp, bottomEnd = if (m.mine) 4.dp else 14.dp))
                .background(if (m.mine) T.chatOut else T.chatIn)
                .then(if (failed) Modifier.clickable(role = Role.Button) { Director.retryDesk(m.id) } else Modifier)
                .padding(start = 12.dp, end = 10.dp, top = 7.dp, bottom = 6.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = (if (m.mine) "You" else m.from) + ", " + time + ": " + m.text + when (m.state.takeIf { m.mine }) {
                        "sending" -> ". Sending"; "seen" -> ". Seen at the desk"; "failed" -> ". Not sent, tap to try again"; "sent" -> ". Sent"; else -> ""
                    }
                },
        ) {
            if (showName) Text(m.from.ifBlank { "Someone" }, style = Type.small.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (m.fromDesk) Tints.ok.ink else nameInk(m.from)))
            Text(m.text, style = Type.body.copy(fontSize = 15.sp, lineHeight = 21.sp, color = T.text))
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (failed) Text("Not sent · tap to try again  ", style = Type.small.copy(fontSize = 11.sp, color = T.negative))
                Text(time, style = Type.small.copy(fontSize = 11.sp, color = if (m.mine) T.chatOutMuted else T.muted))
                if (m.mine) {
                    Spacer(Modifier.width(4.dp))
                    val (icon, tint) = when (m.state) {
                        "sending" -> Icons.Outlined.Schedule to T.chatOutMuted
                        "failed" -> Icons.Outlined.ErrorOutline to T.negative
                        "seen" -> Icons.Outlined.DoneAll to T.accent
                        else -> Icons.Outlined.DoneAll to T.chatOutMuted
                    }
                    Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
                }
            }
        }
        if (!m.mine) Spacer(Modifier.width(48.dp))
    }
}

/** An alert, on this phone only: centred like a chat's system message, with its actions. */
@Composable
private fun AlertCard(m: DeskMessage) {
    val tint = if (m.severe) Tints.rec else Tints.amber
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.at))
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 340.dp).fillMaxWidth(0.88f).clip(RoundedCornerShape(14.dp)).background(tint.bg).padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Alert at $time: ${m.from} ${m.text}" },
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.ErrorOutline, null, tint = tint.ink, modifier = Modifier.size(18.dp))
                Text(m.from, style = Type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = T.text), modifier = Modifier.weight(1f))
                Text(time, style = Type.small.copy(fontSize = 11.sp, color = tint.ink))
            }
            Text(m.text, style = Type.body.copy(fontSize = 14.sp, color = tint.ink))
            if (m.deskText != null || m.offerStart) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (m.offerStart) PrimaryButton("Start recording") { Director.recordStart() }
                m.deskText?.let { t -> OutlineButton("Tell the desk") { Director.sendDesk(t) } }
            }
        }
    }
}

/** A connection note: a small centred line, like a chat's "joined" message. */
@Composable
private fun NoteChip(m: DeskMessage) {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.at))
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text("$time  ${m.text}", style = Type.small.copy(fontSize = 12.sp, color = T.text2),
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(T.surface).padding(horizontal = 12.dp, vertical = 5.dp))
    }
}
