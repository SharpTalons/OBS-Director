package dev.sharptalons.obsdirector.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.sharptalons.obsdirector.R

/**
 * Design tokens, the same names as the design canvas (DESIGN.md). Dark only, by design: the app
 * is used in dark rooms. Text colours meet WCAG AA on [ground] and [surface].
 */
object T {
    // the desk chat: this phone's bubbles in green, everyone else's in grey, like a messaging app
    val chatOut = Color(0xFF005C4B)
    val chatOutMuted = Color(0xFFA5D6C9)
    val chatIn = Color(0xFF2A2A30)
    // the Adjust waveform and the dropped-frames chart
    val waveform = Color(0xFF55555C)
    val waveGap = Color(0x40D9A441)
    val waveSelection = Color(0x2944A282)
    val chartEmpty = Color(0xFF2E3A33)
    val ground = Color(0xFF121214)
    val surface = Color(0xFF1C1C20)
    val surface2 = Color(0xFF1E1E22)
    val sunken = Color(0xFF151518)
    val raised = Color(0xFF2A2A30) // the "control" fill: secondary buttons, tracks
    val border = Color(0xFF26262B)
    val rule = Color(0xFF26262B)
    val control = Color(0xFF3A3A40) // strong lines, switch off
    val text = Color(0xFFFFFFFF)
    val text2 = Color(0xFFC9CBD0)
    val muted = Color(0xFFA6A8AE)
    /** Blue for text and outlines on dark. */
    val accent = Color(0xFF7FB0EC)
    /** Blue for filled buttons: white text on it is 5.8:1. */
    val blue = Color(0xFF2B66B0)
    val positive = Color(0xFF4CC38A)
    val negative = Color(0xFFFF5C76)
    val warning = Color(0xFFF2C46B)
    val idle = Color(0xFF3A3A40)
    val onRec = Color(0xFF1A0B0E)
    val video = Color(0xFF0B0B0D)
    val meter = Color(0xFFD8D8DC)
    val pillNegBg = Color(0xFF3A1B22)
    val pillWarnBg = Color(0xFF33291A)
    val pillOkBg = Color(0xFF183327)
    val pillOkInk = Color(0xFF6EDDA8)
    val pillNeutralBg = Color(0xFF2A2A30)
    val pillNeutralInk = Color(0xFFC9CBD0)
    val clipGreen = Color(0xFF4CC38A)
}

/** A tinted tile or chip: a dark background and a light ink of one hue. Colour means category. */
data class Tint(val bg: Color, val ink: Color)

object Tints {
    val rec = Tint(Color(0xFF3A1B22), Color(0xFFFF8A9C))      // recording, clips, live
    val ok = Tint(Color(0xFF183327), Color(0xFF6EDDA8))       // good status, connection
    val blue = Tint(Color(0xFF172A40), Color(0xFF7FB0EC))     // play, info, bitrate
    val teal = Tint(Color(0xFF14303A), Color(0xFF5FD4E8))     // listening, adjust
    val amber = Tint(Color(0xFF33291A), Color(0xFFF2C46B))    // warnings, notes, controls
    val orange = Tint(Color(0xFF3A2614), Color(0xFFF7A35C))   // alerts
    val purple = Tint(Color(0xFF2A2340), Color(0xFFB9A2FF))   // disk, audio on the phone, share
    val neutral = Tint(Color(0xFF2A2A30), Color(0xFFFFFFFF))
}

/** OBS's own meter colours: green to -20 dB, yellow to -9 dB, red above; dim when unlit. */
object MeterColors {
    val green = Color(0xFF4CFF4C)
    val yellow = Color(0xFFFFFF4C)
    val red = Color(0xFFFF4C4C)
    val greenOff = Color(0xFF1F4A1F)
    val yellowOff = Color(0xFF4A4A1F)
    val redOff = Color(0xFF4A1F1F)
}

/** Stage mode: dim, low-glare colours on black. */
object Stage {
    val ground = Color(0xFF000000)
    val text = Color(0xFFD4D5D8)
    val time = Color(0xFFE2E3E6)
    val muted = Color(0xFF9A9CA2)
    val hint = Color(0xFF8E9096)
    val rec = Color(0xFFB23A4D)
    val clip = Color(0xFF5A1E28)
    val clipInk = Color(0xFFF0C9CF)
    val clipLabel = Color(0xFFE3B5BD)
    val surface = Color(0xFF0D0D0D)
    val track = Color(0xFF141414)
    val green = Color(0xFF2E7D3A)
    val yellow = Color(0xFF8C8A2E)
    val red = Color(0xFF8C2E3A)
}

/** Roboto throughout (Apache 2.0, bundled so every phone shows the same type). */
val Sans = FontFamily(
    Font(R.font.roboto_regular, FontWeight.Normal),
    Font(R.font.roboto_medium, FontWeight.Medium),
    Font(R.font.roboto_medium, FontWeight.SemiBold),
    Font(R.font.roboto_bold, FontWeight.Bold),
)

/** Timecodes and figures: Roboto too, with fixed-width digits so numbers don't jitter. */
val Mono = Sans
private const val TABULAR = "tnum"

object Type {
    val title = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = T.text)
    val finding = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp, color = T.text)
    val cardTitle = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = T.text)
    val body = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp, color = T.text)
    val small = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 17.sp, color = T.muted)
    val label = TextStyle(fontFamily = Sans, fontSize = 12.sp, color = T.muted)
    val timecode = TextStyle(fontFamily = Mono, fontFeatureSettings = TABULAR, fontWeight = FontWeight.Medium, fontSize = 34.sp, letterSpacing = (-0.5).sp, color = T.text)
    val figure = TextStyle(fontFamily = Mono, fontFeatureSettings = TABULAR, fontSize = 16.sp, color = T.text)
    val mono = TextStyle(fontFamily = Mono, fontFeatureSettings = TABULAR, fontSize = 13.sp, color = T.text)
}

@Composable
fun DirectorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = T.text,
            onPrimary = Color.Black,
            secondary = T.blue,
            background = T.ground,
            surface = T.surface,
            onSurface = T.text,
            onBackground = T.text,
            error = T.negative,
            outline = T.control,
        ),
        content = content,
    )
}
