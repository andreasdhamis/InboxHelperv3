package gr.ipexpert.inboxhelper.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF1D1C1A)
val Muted = Color(0xFF5E5A52)
val Ground = Color(0xFFF3F0E9)
val Paper = Color(0xFFFBFAF6)
val Line = Color(0xFFDDD6C8)
val Teal = Color(0xFF0D5F5B)
val TealSoft = Color(0xFFE3F0EE)
val Urgent = Color(0xFFC2571A)
val UrgentText = Color(0xFFA3410F)
val Amber = Color(0xFFF1DFB8)
val AmberText = Color(0xFF6B4A07)
val LowBg = Color(0xFFE6E0D4)

private val scheme = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = TealSoft,
    onPrimaryContainer = Teal,
    secondary = Urgent,
    background = Ground,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDE8DE),
    onSurfaceVariant = Muted,
    outline = Line,
    error = UrgentText,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
