package uz.monitorcheck.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Natija holatlari ranglari (IEEE 1789 zonalari va xulosalar uchun). */
object StatusColors {
    val ok = Color(0xFF1A7F37)
    val caution = Color(0xFFBF8700)
    val bad = Color(0xFFCF222E)
    val neutral = Color(0xFF57606A)
}

private val Light = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    secondary = Color(0xFF0D1B2A),
    surfaceVariant = Color(0xFFEFF2F5),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF58A6FF),
    secondary = Color(0xFF9FB3C8),
)

@Composable
fun MonitorCheckTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
