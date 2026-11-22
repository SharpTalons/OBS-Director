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

/** Design tokens: the "data terminal" style from the OBS Director design. */
object T {
    val ground = Color(0xFF171719)
    val surface = Color(0xFF131217)
    val raised = Color(0xFF212023)
    val border = Color(0xFF222224)
    val rule = Color(0xFF1B1A1F)
    val control = Color(0xFF3A3A3E)
    val text = Color(0xFFFFFFFF)
    val muted = Color(0xFF8A8C92)
    val accent = Color(0xFF4A8AD6)
    val positive = Color(0xFF44A282)
    val negative = Color(0xFFE5566F)
    val warning = Color(0xFFD9A441)
    val idle = Color(0xFF3A3A3E)
    val meter = Color(0xFFD8D8DC)
    val pillNegBg = Color(0xFF2C1A20)
    val pillWarnBg = Color(0xFF2A2416)
    val pillOkBg = Color(0xFF1B2828)
    val pillOkInk = Color(0xFF5FC39C)
    val pillNeutralBg = Color(0xFF212023)
    val pillNeutralInk = Color(0xFFBDBDC0)
    val clipGreen = Color(0xFF44A282)
}

val Sans = FontFamily(
    Font(R.font.public_sans_regular, FontWeight.Normal),
    Font(R.font.public_sans_medium, FontWeight.Medium),
    Font(R.font.public_sans_semibold, FontWeight.SemiBold),
)

val Mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

object Type {
    val title = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = T.text)
    val finding = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp, color = T.text)
    val cardTitle = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = T.text)
    val body = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp, color = T.text)
    val small = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 17.sp, color = T.muted)
    val label = TextStyle(fontFamily = Sans, fontSize = 12.sp, color = T.muted)
    val timecode = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 34.sp, letterSpacing = (-0.5).sp, color = T.text)
    val figure = TextStyle(fontFamily = Mono, fontSize = 16.sp, color = T.text)
    val mono = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = T.text)
}

@Composable
fun DirectorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = T.text,
            onPrimary = Color.Black,
            secondary = T.accent,
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
