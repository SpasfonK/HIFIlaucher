package com.hifilauncher.ui

import android.graphics.Typeface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * Design tokens.
 *
 * Concept: a piece of audio gear in the dark. True black screen, anodised-aluminium
 * wheel, and a single accent (VU-meter amber) spent on only three things: the wheel's
 * index mark, the progress line, and the mode pill. Everything else stays grey/white.
 *
 * Type: system condensed bold for the track title (instrument-panel feel, no font
 * files needed), regular sans for the rest, tabular figures so times and volume
 * never jitter.
 */
object HiFi {
    val Bg = Color(0xFF000000)
    val Chassis = Color(0xFF16171A)
    val ChassisEdge = Color(0xFF2C2E33)
    val Hub = Color(0xFF1D1E22)
    val Groove = Color(0xFF050506)
    val Mark = Color(0xFF50525A)
    val Hairline = Color(0xFF26282C)
    val Amber = Color(0xFFFFB547)
    val TextHi = Color(0xFFF4F1EC)
    val TextLo = Color(0xFF8C8F96)

    val TitleFont = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD))
    val Tnum = TextStyle(fontFeatureSettings = "tnum")
}

@Composable
fun HiFiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = HiFi.Bg,
            surface = HiFi.Bg,
            primary = HiFi.Amber,
            onBackground = HiFi.TextHi,
            onSurface = HiFi.TextHi,
        ),
        content = content,
    )
}
