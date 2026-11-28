package dev.sharptalons.obsdirector.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.sharptalons.obsdirector.audio.ClipAudio
import dev.sharptalons.obsdirector.audio.ClipPlayer
import dev.sharptalons.obsdirector.core.Clip
import dev.sharptalons.obsdirector.core.ClipStore
import dev.sharptalons.obsdirector.core.Director
import dev.sharptalons.obsdirector.core.Exporter
import dev.sharptalons.obsdirector.core.Prefs
import dev.sharptalons.obsdirector.core.Recording
import dev.sharptalons.obsdirector.core.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/** Plays part of a clip from the saved session audio; works during the session too. */
internal fun playClip(r: Recording, c: Clip, key: String, from: Long = c.startMs, to: Long = c.endMs, at: Long = from) =
    Director.playSaved(r, key, from, to, at)

/** Plays an OBS recording's AAC saved on this phone (the live mix is lowered while it plays). */
internal fun playAac(file: String) {
    val f = Director.aacFile(file)
    if (!f.exists()) { Director.toast("The AAC file isn't on this phone."); return }
    val ms = runCatching {
        android.media.MediaMetadataRetriever().run { try { setDataSource(f.path); extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() } finally { release() } }
    }.getOrNull() ?: Long.MAX_VALUE / 4
    if (!ClipPlayer.play("aac:$file", listOf(f to 0L), 0, ms)) Director.toast("Couldn't play the AAC file.")
}

internal fun shareAac(context: Context, file: String) {
    val f = Director.aacFile(file)
    if (!f.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", f)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share the AAC file").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

internal suspend fun shareClipAudio(context: Context, r: Recording, c: Clip) {
    val pieces = Director.sessionAudio(r, (c.startMs - ClipAudio.SPARE_MS).coerceAtLeast(0), c.endMs + ClipAudio.SPARE_MS)
    if (pieces.isEmpty()) { Director.toast("This clip's audio isn't saved on this device."); return }
    val dir = File(context.filesDir, "exports").apply { mkdirs() }
    val name = c.fullName() ?: "${r.file.substringBeforeLast('.')} clip ${Director.tc(c.startMs).replace(':', '-')}"
    val out = File(dir, name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120) + ".m4a")
    val ok = withContext(Dispatchers.IO) { ClipAudio.cut(pieces, c.startMs, c.endMs, out) }
    if (!ok) { Director.toast("Couldn't cut the clip's audio."); return }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", out)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share clip audio").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
